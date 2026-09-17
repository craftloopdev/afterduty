package com.afterduty.service.gap;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.afterduty.model.Atom;
import com.afterduty.model.IdentifiedCondition;
import com.afterduty.service.llm.AtomCorpusRenderer;
import com.afterduty.service.llm.LlmJobRequest;
import com.afterduty.service.llm.LlmJobResult;
import com.afterduty.service.llm.LlmJobService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Adversarial validator for gap suggestions. Exposes buildRequest/parseResponse
 * for GapStateMachine.
 */
@Service
public class GapValidationAgent {

    private static final Logger log = LoggerFactory.getLogger(GapValidationAgent.class);

    private static final int MAX_OUTPUT_TOKENS = 8192;
    private static final int THINKING_BUDGET = 8000;

    private final LlmJobService llmJobService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Mission 6b — GAP FAN-OUT restructure (validation). When ON (default), the LIVE
     * atom corpus becomes the byte-stable cached prefix shared by every validation
     * call this run; only the condition + the candidate gaps ride the volatile
     * userMessage tail. When OFF, today's exact flat shape (atoms inline) is emitted.
     */
    @Value("${va-claim.llm.prompt-caching:true}")
    boolean promptCachingEnabled = true;

    private static final String SYSTEM_PROMPT = """
            You are a Rating Veterans Service Representative (RVSR) — a real VA rater — reviewing
            a list of evidence-gap suggestions someone else generated for a veteran's claim.
            Your job is to stress-test each suggestion against how a rater actually thinks.

            For every proposed gap, answer three questions with evidence:

              1. REAL — Is the gap real? Given the evidence already on file (atoms + triad
                 summary), would a rater actually say this evidence is missing? If the claim
                 already has sufficient evidence for the stated goal, the gap is NOT real.

              2. CRITICAL — If the veteran obtains this evidence, will it actually move the
                 needle (higher rating, presumption granted, triad leg closed)? A gap that
                 would not change the outcome is NOT critical — it is noise.

              3. NEEDED — Is this the lowest-friction path to the same outcome? A rater prefers
                 official records and objective tests over narrative letters. If a cheaper or
                 faster piece of evidence would work as well, this gap is NOT needed — downgrade
                 it or replace it.

            RATER REALITY CHECKS
            - Presumption already granted → nexus letter is NOT needed, mark NOT real.
            - DBQ / C&P exam already on file within the relevant timeframe → another C&P is
              only needed if functional impairment has demonstrably changed.
            - Buddy statements help for combat stressors, fear-of-hostile-military-activity,
              and MST. They generally do NOT move ratings for orthopedic or presumptive claims.
            - Private medical opinions are weighted LESS than VA exams unless they address a
              specific deficit in the VA record (IMO rebutting a VA opinion, for example).
            - Pharmacy records > self-reported symptoms. Objective tests > subjective reports.
            - A gap claiming to unlock a higher rating must match the exact VASRD criterion
              for that threshold. If it does not, reject it.

            VERDICT SCHEMA — return ONE JSON object with this shape, ordered by the input gaps:
            {
              "validated_gaps": [
                {
                  "original_index": <int, 0-based index in the input array>,
                  "keep": <bool>,
                  "is_real": <bool>,
                  "is_critical": <bool>,
                  "is_needed": <bool>,
                  "rater_priority": "high" | "medium" | "low" | "reject",
                  "rater_note": "<1–2 sentences explaining the call in VA rater voice>",
                  "revised_description": "<optional tightened description; null to keep the original>",
                  "revised_how_to_get_it": "<optional tightened steps; null to keep the original>"
                }
              ],
              "overall_note": "<one short paragraph summarizing the review>"
            }

            Be strict. A shorter list of real, critical, needed gaps is a better outcome than a
            long list of fluff.
            """;

    public GapValidationAgent(LlmJobService llmJobService) {
        this.llmJobService = llmJobService;
    }

    /**
     * Convenience: submit a gap-validation job via LlmJobService.
     */
    public UUID submit(IdentifiedCondition condition, List<Atom> atoms,
                       List<Map<String, Object>> proposedGaps, Long claimId, Long userId) {
        return llmJobService.submit(buildRequest(condition, atoms, proposedGaps, claimId, userId));
    }

    /**
     * Build a provider-agnostic LlmJobRequest for validating gap suggestions.
     *
     * <p>Mission 6b — the LIVE atom corpus becomes a byte-stable cached prefix shared
     * by every validation call this run; only this condition + the candidate gaps ride
     * the volatile {@code userMessage} tail. With prompt-caching OFF, today's exact flat
     * shape is emitted (atoms inline, no {@code structuredPrompt}).
     */
    public LlmJobRequest buildRequest(IdentifiedCondition condition, List<Atom> atoms,
                                       List<Map<String, Object>> proposedGaps,
                                       Long claimId, Long userId) {
        String gapsJson;
        try {
            gapsJson = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(proposedGaps);
        } catch (Exception e) {
            log.error("Failed to serialize proposed gaps: {}", e.getMessage());
            gapsJson = String.valueOf(proposedGaps);
        }

        LlmJobRequest.Builder b = LlmJobRequest.builder()
                .purpose("gap_validation")
                .maxTokens(MAX_OUTPUT_TOKENS)
                .thinkingBudget(THINKING_BUDGET)
                .claimId(claimId)
                .userId(userId)
                .conditionId(condition.getId())
                .batchGroupKey("gap_validation_" + claimId);

        if (promptCachingEnabled) {
            String volatileTail = String.format("""
                    Condition: %s (VASRD %s, currently rated at %d%%)
                    Presumptive: %s
                    Presumptive Basis: %s

                    Triad state:
                    %s

                    The veteran's atoms on file are in the EVIDENCE ATOM CORPUS above.

                    Candidate gaps to validate (%d):
                    %s

                    Apply the REAL / CRITICAL / NEEDED filter. Return the JSON object only.""",
                    condition.getName(),
                    condition.getVasrdCode() != null ? condition.getVasrdCode() : "unknown",
                    condition.getEstimatedRating() != null ? condition.getEstimatedRating() : 0,
                    Boolean.TRUE.equals(condition.getIsPresumptive()) ? "yes" : "no",
                    condition.getPresumptiveBasis() != null ? condition.getPresumptiveBasis() : "n/a",
                    buildTriadSummary(condition),
                    proposedGaps.size(),
                    gapsJson);
            return b
                    .structuredPrompt(new LlmJobRequest.StructuredPrompt(
                            List.of(SYSTEM_PROMPT), AtomCorpusRenderer.render(atoms)))
                    .userMessage(volatileTail)
                    .build();
        }

        // Flag OFF — today's exact flat shape: atoms inline in the userMessage.
        String userMessage = String.format("""
                Condition: %s (VASRD %s, currently rated at %d%%)
                Presumptive: %s
                Presumptive Basis: %s

                Triad state:
                %s

                Atoms on file (%d):
                %s

                Candidate gaps to validate (%d):
                %s

                Apply the REAL / CRITICAL / NEEDED filter. Return the JSON object only.
                """,
                condition.getName(),
                condition.getVasrdCode() != null ? condition.getVasrdCode() : "unknown",
                condition.getEstimatedRating() != null ? condition.getEstimatedRating() : 0,
                Boolean.TRUE.equals(condition.getIsPresumptive()) ? "yes" : "no",
                condition.getPresumptiveBasis() != null ? condition.getPresumptiveBasis() : "n/a",
                buildTriadSummary(condition),
                atoms.size(),
                buildAtomSummary(atoms),
                proposedGaps.size(),
                gapsJson);

        return b
                .systemPrompt(SYSTEM_PROMPT)
                .userMessage(userMessage)
                .build();
    }

    /**
     * Parse the LLM response and apply verdicts to the original gap list.
     */
    public List<Map<String, Object>> parseResponse(LlmJobResult result,
                                                    List<Map<String, Object>> originalProposedGaps) {
        return applyVerdicts(originalProposedGaps, result.getText());
    }

    private List<Map<String, Object>> applyVerdicts(List<Map<String, Object>> proposedGaps, String responseText) {
        try {
            String cleaned = cleanJsonResponse(responseText);
            Map<String, Object> parsed = objectMapper.readValue(cleaned,
                    new TypeReference<Map<String, Object>>() {});
            List<Map<String, Object>> verdicts = objectMapper.convertValue(
                    parsed.get("validated_gaps"),
                    new TypeReference<List<Map<String, Object>>>() {});
            if (verdicts == null) {
                log.warn("Validator returned no validated_gaps — keeping originals");
                return proposedGaps;
            }

            String overallNote = parsed.get("overall_note") instanceof String s ? s : null;

            List<Map<String, Object>> output = new ArrayList<>();
            for (Map<String, Object> verdict : verdicts) {
                Object idx = verdict.get("original_index");
                if (!(idx instanceof Number)) continue;
                int i = ((Number) idx).intValue();
                if (i < 0 || i >= proposedGaps.size()) continue;

                boolean keep = asBool(verdict.get("keep"), true);
                if (!keep) continue;

                Map<String, Object> merged = new LinkedHashMap<>(proposedGaps.get(i));
                merged.put("is_real", asBool(verdict.get("is_real"), true));
                merged.put("is_critical", asBool(verdict.get("is_critical"), true));
                merged.put("is_needed", asBool(verdict.get("is_needed"), true));
                Object raterPriority = verdict.get("rater_priority");
                if (raterPriority != null) {
                    merged.put("rater_priority", raterPriority);
                    merged.put("priority", raterPriority);
                }
                if (verdict.get("rater_note") instanceof String note && !note.isBlank()) {
                    merged.put("rater_note", note);
                }
                String revisedDesc = asStringOrNull(verdict.get("revised_description"));
                if (revisedDesc != null) merged.put("description", revisedDesc);
                String revisedSteps = asStringOrNull(verdict.get("revised_how_to_get_it"));
                if (revisedSteps != null) merged.put("how_to_get_it", revisedSteps);

                merged.put("validated", true);
                output.add(merged);
            }

            if (overallNote != null && !overallNote.isBlank() && !output.isEmpty()) {
                output.get(0).put("rater_overall_note", overallNote);
            }

            log.info("Validator kept {} of {} gaps", output.size(), proposedGaps.size());
            return output;
        } catch (Exception e) {
            log.error("Failed to parse validator output: {} — keeping originals", e.getMessage());
            return proposedGaps;
        }
    }

    private static boolean asBool(Object o, boolean fallback) {
        if (o instanceof Boolean b) return b;
        if (o instanceof String s) return Boolean.parseBoolean(s);
        return fallback;
    }

    private static String asStringOrNull(Object o) {
        if (o instanceof String s && !s.isBlank() && !"null".equalsIgnoreCase(s)) return s;
        return null;
    }

    private String buildTriadSummary(IdentifiedCondition c) {
        StringBuilder sb = new StringBuilder();
        if (c.getTriadDiagnosis() != null) sb.append("Diagnosis: ").append(c.getTriadDiagnosis()).append("\n");
        if (c.getTriadInService() != null) sb.append("In-Service Event: ").append(c.getTriadInService()).append("\n");
        if (c.getTriadNexus() != null) sb.append("Nexus: ").append(c.getTriadNexus()).append("\n");
        if (c.getRatingRationale() != null) sb.append("Rating Rationale: ").append(c.getRatingRationale()).append("\n");
        return sb.length() > 0 ? sb.toString() : "No triad evidence on file.";
    }

    private String buildAtomSummary(List<Atom> atoms) {
        if (atoms.isEmpty()) return "(none)";
        Map<String, List<Atom>> byType = atoms.stream()
                .collect(Collectors.groupingBy(Atom::getType));
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, List<Atom>> entry : byType.entrySet()) {
            sb.append("  ").append(entry.getKey()).append(":\n");
            for (Atom atom : entry.getValue()) {
                sb.append("    - ").append(atom.getValue());
                if (atom.getTimestamp() != null) sb.append(" [").append(atom.getTimestamp()).append("]");
                sb.append("\n");
            }
        }
        return sb.toString();
    }

    private String cleanJsonResponse(String text) {
        String cleaned = text.strip();
        if (cleaned.startsWith("```json")) cleaned = cleaned.substring(7);
        else if (cleaned.startsWith("```")) cleaned = cleaned.substring(3);
        if (cleaned.endsWith("```")) cleaned = cleaned.substring(0, cleaned.length() - 3);
        return cleaned.strip();
    }
}
