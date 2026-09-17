package com.afterduty.service.gap;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.afterduty.model.Atom;
import com.afterduty.model.IdentifiedCondition;
import com.afterduty.model.UserGapState;
import com.afterduty.service.VasrdIndexService;
import com.afterduty.service.llm.AtomCorpusRenderer;
import com.afterduty.service.llm.LlmJobRequest;
import com.afterduty.service.llm.LlmJobResult;
import com.afterduty.service.llm.LlmJobService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * VASRD-aware evidence gap analyzer. Exposes buildRequest/parseResponse for GapStateMachine.
 */
@Service
public class EvidenceGapAnalyzer {

    private static final Logger log = LoggerFactory.getLogger(EvidenceGapAnalyzer.class);

    private static final int MAX_OUTPUT_TOKENS = 8192;
    private static final int THINKING_BUDGET = 8000;

    @Nullable
    private VasrdIndexService vasrdIndexService;
    private final LlmJobService llmJobService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Mission 6b — GAP FAN-OUT restructure. When ON (default), the LIVE atom corpus
     * (identical for every condition in the run) becomes the byte-stable cached
     * prefix block; only the single condition + its KB/triad context ride the
     * volatile userMessage tail. When OFF, today's exact flat shape is emitted
     * (atoms inline in the userMessage, no structuredPrompt).
     */
    @Value("${va-claim.llm.prompt-caching:true}")
    boolean promptCachingEnabled = true;

    private static final String SYSTEM_PROMPT = """
            You are a senior VA disability claims evidence strategist. You work for the veteran,
            not the VA. Your only goal is to identify evidence that is actually missing and that
            would measurably strengthen this specific condition — either by proving an unproven
            triad leg or by unlocking the next higher VASRD rating threshold.

            HARD RULES
            - Every gap MUST reference a specific 38 CFR Part 4 VASRD criterion by code and the
              exact wording of the criterion you are trying to meet. Generic advice is rejected.
            - Every gap MUST be concrete and actionable: where to get the evidence, who issues
              it, and what the veteran should ask for in plain language.
            - Do NOT propose evidence the veteran already has in the atom list. Read the atom
              list carefully before you suggest anything.
            - Do NOT propose evidence that would only matter for a different condition.
            - Do NOT invent exposures, events, or diagnoses. Use only what is in the evidence.
            - If a gap is not likely to change the rating or triad outcome, omit it. Fewer,
              stronger gaps are better than a long list.
            - Rank by rating impact first, triad completion second, nice-to-have last.
            - When the condition is presumptive (PACT Act, Agent Orange, Gulf War, etc.) and
              presumption is already established, the nexus gap is usually NOT real — do not
              recommend a nexus letter unless VA is likely to rebut presumption.
            - SECONDARY CONDITIONS (38 CFR 3.310): when a "Secondary To" primary is shown, this
              is a SECONDARY claim. It has NO separate in-service event — the in-service
              requirement is satisfied THROUGH the service-connected primary. So: (a) do NOT
              recommend a direct in-service-event gap (no service-treatment-record / STR gap for
              the in_service leg). (b) If "Primary Service-Connection Status" is UNKNOWN, the
              single most important gap is to CONFIRM the primary: emit ONE gap (type
              personal_statement or service_record, triad_leg "in_service") whose title/description
              plainly say "Confirm your <primary> is VA service-connected and its rating — your
              <this condition> claim depends on it." If the status is KNOWN or LIKELY, do NOT emit
              that confirmation gap. (c) Frame the nexus gap as the SECONDARY↔PRIMARY medical link
              — that the primary CAUSED or AGGRAVATED this condition (causation or aggravation) —
              not a direct service nexus.

            GAP TYPES (use exactly these):
            nexus_letter, imo_independent_medical_opinion, c_and_p_exam_request,
            buddy_statement, treatment_record, medication_log, specialist_opinion,
            imaging_study, lab_test, pharmacy_record, service_record, personal_statement,
            presumptive_documentation

            OUTPUT
            Return ONLY a JSON array. Each element:
            {
              "type": "<one of the gap types above>",
              "title": "<short human title, 6–10 words>",
              "description": "<one paragraph; reference VASRD criterion verbatim and what's missing>",
              "vasrd_reference": "<e.g. '38 CFR 4.97 DC 6602, 60% criterion: at least monthly visits...'>",
              "current_rating": <int — current rating for this condition>,
              "target_rating": <int — the rating this evidence would unlock, or current rating if it just fills a triad leg>,
              "triad_leg": "diagnosis" | "in_service" | "nexus" | "severity" | null,
              "priority": "high" | "medium" | "low",
              "rating_impact": "<one sentence — expected rating change or triad completion>",
              "how_to_get_it": "<1–3 sentences — concrete steps: who to ask, what form or language to use>",
              "estimated_time": "<e.g. '1–2 weeks', '30 days', 'same day'>",
              "estimated_cost_usd": <int — out-of-pocket cost the veteran should expect, 0 if VA-covered>
            }

            Remember: a VA rater will see this claim. They want proof, not narrative. Every gap
            you propose must be something a rater would actually credit.
            """;

    public EvidenceGapAnalyzer(LlmJobService llmJobService) {
        this.llmJobService = llmJobService;
    }

    @Autowired(required = false)
    public void setVasrdIndexService(VasrdIndexService vasrdIndexService) {
        this.vasrdIndexService = vasrdIndexService;
    }

    /**
     * Convenience: submit an evidence-gap analysis job via LlmJobService.
     */
    public UUID submit(IdentifiedCondition condition, List<Atom> atoms, Long claimId, Long userId) {
        return llmJobService.submit(buildRequest(condition, atoms, claimId, userId));
    }

    /**
     * Build a provider-agnostic LlmJobRequest for evidence gap analysis of one condition.
     *
     * <p>Mission 6b — GAP FAN-OUT restructure: the LIVE atom corpus (the same list for
     * every condition this run) becomes a byte-stable cached prefix block shared by all
     * gap calls; only this condition + its KB/triad context ride the volatile
     * {@code userMessage} tail. With prompt-caching OFF, today's exact flat shape is
     * emitted (atoms inline in the userMessage, no {@code structuredPrompt}).
     */
    public LlmJobRequest buildRequest(IdentifiedCondition condition, List<Atom> atoms,
                                       Long claimId, Long userId) {
        return buildRequest(condition, atoms, claimId, userId, List.of());
    }

    /**
     * P1-6 variant — additionally injects the veteran's dismissed gaps as
     * do-not-re-propose context. The block rides the volatile {@code userMessage}
     * tail, AFTER the cache_control-stable prefix (system blocks + atom corpus),
     * so it never busts the shared prompt cache. Empty list = byte-identical to
     * the 4-arg overload.
     */
    public LlmJobRequest buildRequest(IdentifiedCondition condition, List<Atom> atoms,
                                       Long claimId, Long userId,
                                       List<UserGapState> dismissed) {
        String triadSummary = buildTriadSummary(condition);
        String dismissedContext = renderDismissedContext(dismissed);
        String secondaryContext = buildSecondaryContext(condition);

        String kbContext = vasrdIndexService != null
                ? vasrdIndexService.contextForCondition(
                        condition.getName(), condition.getVasrdCode(), condition.getBodySystem())
                : "";

        LlmJobRequest.Builder b = LlmJobRequest.builder()
                .purpose("gap_evidence")
                .maxTokens(MAX_OUTPUT_TOKENS)
                .thinkingBudget(THINKING_BUDGET)
                .claimId(claimId)
                .userId(userId)
                .conditionId(condition.getId())
                .batchGroupKey("gap_evidence_" + claimId);

        if (promptCachingEnabled) {
            // Volatile tail: only this condition (+ its do-not-re-propose context).
            // The atoms are the cached prefix; the dismissed block rides the tail
            // so it can never invalidate the shared cache_control breakpoint.
            String volatileTail = String.format("""
                    Condition: %s (VASRD %s, currently rated at %d%%)
                    Body System: %s
                    Presumptive: %s
                    Presumptive Basis: %s
                    %s
                    VA Knowledge-Base Reference:
                    %s

                    Current Triad State:
                    %s
                    %s
                    Use the EVIDENCE ATOM CORPUS above as the veteran's atoms already on file.
                    Identify the real, actionable gaps — ranked by rating impact first.
                    Return the JSON array only.""",
                    condition.getName(),
                    condition.getVasrdCode() != null ? condition.getVasrdCode() : "unknown",
                    condition.getEstimatedRating() != null ? condition.getEstimatedRating() : 0,
                    condition.getBodySystem() != null ? condition.getBodySystem() : "unknown",
                    Boolean.TRUE.equals(condition.getIsPresumptive()) ? "yes" : "no",
                    condition.getPresumptiveBasis() != null ? condition.getPresumptiveBasis() : "n/a",
                    secondaryContext,
                    kbContext,
                    triadSummary,
                    dismissedContext);
            return b
                    .structuredPrompt(new LlmJobRequest.StructuredPrompt(
                            List.of(SYSTEM_PROMPT), AtomCorpusRenderer.render(atoms)))
                    .userMessage(volatileTail)
                    .build();
        }

        // Flag OFF — today's exact flat shape: atoms inline in the userMessage.
        String atomSummary = buildAtomSummary(atoms);
        String userMessage = String.format("""
                Condition: %s (VASRD %s, currently rated at %d%%)
                Body System: %s
                Presumptive: %s
                Presumptive Basis: %s
                %s
                VA Knowledge-Base Reference:
                %s

                Current Triad State:
                %s
                %s
                Evidence Atoms Already On File (%d):
                %s

                Identify the real, actionable gaps — ranked by rating impact first.
                Return the JSON array only.
                """,
                condition.getName(),
                condition.getVasrdCode() != null ? condition.getVasrdCode() : "unknown",
                condition.getEstimatedRating() != null ? condition.getEstimatedRating() : 0,
                condition.getBodySystem() != null ? condition.getBodySystem() : "unknown",
                Boolean.TRUE.equals(condition.getIsPresumptive()) ? "yes" : "no",
                condition.getPresumptiveBasis() != null ? condition.getPresumptiveBasis() : "n/a",
                secondaryContext,
                kbContext,
                triadSummary,
                dismissedContext,
                atoms.size(),
                atomSummary);

        return b
                .systemPrompt(SYSTEM_PROMPT)
                .userMessage(userMessage)
                .build();
    }

    /**
     * Parse the LLM response JSON array into a list of gap maps.
     */
    public List<Map<String, Object>> parseResponse(LlmJobResult result) {
        try {
            String cleaned = cleanJsonResponse(result.getText());
            return objectMapper.readValue(cleaned, new TypeReference<List<Map<String, Object>>>() {});
        } catch (Exception e) {
            log.error("Failed to parse evidence gaps JSON: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * P1-6 — deterministic do-not-re-propose block rendered from the veteran's
     * dismissed {@link UserGapState} rows (string template ONLY — no LLM ever
     * writes this text). Empty input renders "" so the prompt stays
     * byte-identical when nothing was dismissed; entries are sorted by
     * (type, leg) so the rendering is stable across runs. Leading newline is
     * part of the block: the templates place it on its own %s line between the
     * triad state and the closing instructions.
     */
    private static String renderDismissedContext(List<UserGapState> dismissed) {
        if (dismissed == null || dismissed.isEmpty()) return "";
        StringBuilder sb = new StringBuilder(
                "\nThe veteran has said these do not apply — do not re-propose them:");
        dismissed.stream()
                .sorted(Comparator.comparing(UserGapState::getGapType)
                        .thenComparing(g -> g.getTriadLeg() == null ? "" : g.getTriadLeg()))
                .forEach(g -> {
                    sb.append("\n- ").append(g.getGapType());
                    if (g.getTriadLeg() != null) {
                        sb.append(" (triad leg: ").append(g.getTriadLeg()).append(")");
                    }
                });
        return sb.toString();
    }

    /**
     * Secondary-aware analysis (38 CFR 3.310) context for the gap prompt. Returns
     * "" for a direct claim (condition.secondaryTo null/blank) so the prompt stays
     * byte-identical to the pre-feature shape for non-secondaries. For a secondary
     * condition it renders the PRIMARY name and the resolved PRIMARY-service-
     * connection status (derived deterministically from the reframed in-service
     * triad leg written by {@code reconcileSecondary} — STRONG=KNOWN, MODERATE=LIKELY,
     * else UNKNOWN), so the SYSTEM_PROMPT secondary carve-out can (a) suppress the
     * direct in-service-event gap, (b) emit an ask-the-veteran confirmation gap only
     * when UNKNOWN, and (c) frame the nexus gap as the secondary↔primary link.
     * Trailing newline preserves the blank line before "VA Knowledge-Base Reference".
     */
    private String buildSecondaryContext(IdentifiedCondition condition) {
        String primary = condition.getSecondaryTo();
        if (primary == null || primary.isBlank()) return "";
        return "Secondary To (38 CFR 3.310 — primary condition): " + primary.strip() + "\n"
                + "Primary Service-Connection Status: " + resolvePrimaryScStatusForPrompt(condition) + "\n";
    }

    /**
     * Map the (already-reconciled) in-service triad leg to a plain-language
     * primary-SC status token for the prompt. {@code reconcileSecondary} sets the
     * in-service leg to STRONG when the primary is KNOWN service-connected, MODERATE
     * when it is a LIKELY in-claim strong primary, and MISSING/WEAK when UNKNOWN.
     * Null/absent leg (secondary-aware flag OFF, so no reconcile ran) ⇒ UNKNOWN, the
     * safe "ask the veteran" default.
     */
    private String resolvePrimaryScStatusForPrompt(IdentifiedCondition condition) {
        Map<String, Object> leg = condition.getTriadInService();
        Object status = leg == null ? null : leg.get("status");
        String s = status == null ? "" : status.toString().toUpperCase();
        return switch (s) {
            case "STRONG" -> "KNOWN (evidence indicates the primary is VA service-connected)";
            case "MODERATE" -> "LIKELY (primary is a strong claim in this analysis but not yet granted)";
            default -> "UNKNOWN (ask the veteran to confirm the primary is VA service-connected)";
        };
    }

    private String buildTriadSummary(IdentifiedCondition condition) {
        StringBuilder sb = new StringBuilder();
        if (condition.getTriadDiagnosis() != null) {
            sb.append("Diagnosis: ").append(condition.getTriadDiagnosis()).append("\n");
        }
        if (condition.getTriadInService() != null) {
            sb.append("In-Service Event: ").append(condition.getTriadInService()).append("\n");
        }
        if (condition.getTriadNexus() != null) {
            sb.append("Nexus: ").append(condition.getTriadNexus()).append("\n");
        }
        if (condition.getRatingRationale() != null) {
            sb.append("Rating Rationale: ").append(condition.getRatingRationale()).append("\n");
        }
        return sb.length() > 0 ? sb.toString() : "No triad evidence on file.";
    }

    private String buildAtomSummary(List<Atom> atoms) {
        if (atoms.isEmpty()) return "No atoms available.";
        Map<String, List<Atom>> byType = atoms.stream()
                .collect(Collectors.groupingBy(Atom::getType));
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, List<Atom>> entry : byType.entrySet()) {
            sb.append("  ").append(entry.getKey()).append(":\n");
            for (Atom atom : entry.getValue()) {
                sb.append("    - ").append(atom.getValue());
                if (atom.getTimestamp() != null) sb.append(" [").append(atom.getTimestamp()).append("]");
                sb.append(" (confidence: ").append(String.format("%.2f", atom.getConfidence())).append(")\n");
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
