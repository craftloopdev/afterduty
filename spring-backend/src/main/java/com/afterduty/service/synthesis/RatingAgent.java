package com.afterduty.service.synthesis;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.afterduty.model.Atom;
import com.afterduty.model.IdentifiedCondition;
import com.afterduty.service.VasrdDataService;
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
 * Phase 3 Sub-Agent: Rating Assignment.
 * Exposes buildRequest/parseResponse for use by SynthesisStateMachine.
 */
@Service
public class RatingAgent {

    private static final Logger log = LoggerFactory.getLogger(RatingAgent.class);

    @Nullable
    private VasrdDataService vasrdDataService;
    private final LlmJobService llmJobService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Mission 6b — when ON (default), the rate fan-out ships a stable cached prefix
     * (frozen system prompt + the byte-stable atom corpus under one cache_control
     * breakpoint) shared by every condition in a run, and the volatile per-condition
     * payload rides the userMessage tail. When OFF, buildRequest emits today's exact
     * flat shape (atoms folded into the userMessage, plain systemPrompt, no
     * structuredPrompt) — byte-identical to the pre-6b request.
     */
    @Value("${va-claim.llm.prompt-caching:true}")
    boolean promptCachingEnabled = true;

    private static final String SYSTEM_PROMPT = """
            You are a VA disability rating specialist. Rate this specific condition based on the evidence provided.

            Assign a rating from this EXACT set: 0, 10, 20, 30, 40, 50, 60, 70, 80, 100

            CRITICAL INSTRUCTIONS:
            1. Match evidence to SPECIFIC rating criteria thresholds. The rating is determined by which threshold the evidence meets.
            2. COUNT medication prescriptions across ALL documents. If prednisone appears in 3 separate prescriptions across different dates, that counts as 3 courses — even if each document only shows one prescription.
            3. Look for PATTERNS across time, not just single data points:
               - Multiple prescriptions of the same drug = recurring need
               - Increasing dosages = worsening condition
               - Emergency/urgent care visits = exacerbations
               - Equipment prescribed (CPAP, nebulizer, wheelchair) = functional limitation
            4. For respiratory conditions (6600-6899): count corticosteroid courses (prednisone, methylprednisolone, dexamethasone) across ALL records. Each separate prescription = one course.
            5. For mental health (9200-9440): assess overall functional impairment from ALL evidence combined, not from a single note.
            6. For musculoskeletal: look for range of motion measurements in degrees.

            COMMON RATING THRESHOLDS:
            - Asthma (6602): 10%=FEV-1 71-80%. 30%=FEV-1 56-70% OR daily inhalational therapy OR daily oral bronchodilator. 60%=at least 3 courses systemic corticosteroids/year OR FEV-1 40-55%. 100%=FEV-1 <40% OR more than one attack/week.
            - PTSD (9411): 30%=occupational impairment with occasional decrease. 50%=reduced reliability and productivity. 70%=deficiencies in most areas. 100%=total impairment.
            - Tinnitus (6260): Maximum schedular rating is 10%.
            - Sleep Apnea (6847): 30%=persistent daytime hypersomnolence. 50%=requires CPAP. 100%=chronic respiratory failure.
            - GERD (7346): 10%=two or more symptoms. 30%=persistently recurrent epigastric distress with substernal pain, regurgitation. 60%=with material weight loss, hematemesis, melena, anemia.

            If evidence supports a higher rating, assign it. Do not under-rate. The veteran deserves the rating their evidence supports.

            Return JSON: {"estimated_rating": N, "rating_rationale": "detailed explanation referencing specific criteria thresholds and evidence", "confidence": 0.0-1.0}
            Return ONLY the JSON object, no other text.
            """;

    public RatingAgent(LlmJobService llmJobService) {
        this.llmJobService = llmJobService;
    }

    @Autowired(required = false)
    public void setVasrdDataService(VasrdDataService vasrdDataService) {
        this.vasrdDataService = vasrdDataService;
    }

    /**
     * Convenience: submit a rating job via LlmJobService.
     */
    public UUID submit(IdentifiedCondition condition, List<Atom> atoms, Long claimId, Long userId) {
        return llmJobService.submit(buildRequest(condition, atoms, claimId, userId));
    }

    /**
     * Build a provider-agnostic LlmJobRequest for rating a single condition.
     *
     * <p>Mission 6b — RATE FAN-OUT restructure. The atom corpus (the same LIVE atom
     * list for every condition in the run) becomes a byte-stable cached prefix shared
     * across all rate calls; only the single condition + its VASRD criteria ride the
     * volatile {@code userMessage} tail. Every rate call in one run therefore renders
     * the identical cached prefix bytes (asserted in {@code RatingAgentTest}).
     *
     * <p>With prompt-caching OFF this returns today's exact flat shape: the atoms are
     * folded back into the {@code userMessage} (count + corpus inline) and no
     * {@code structuredPrompt} is attached, so the request is byte-identical to the
     * pre-6b request.
     */
    public LlmJobRequest buildRequest(IdentifiedCondition condition, List<Atom> atoms,
                                       Long claimId, Long userId) {
        String conditionName = condition.getName();
        String vasrdCode = condition.getVasrdCode();

        String vasrdCriteria = lookupVasrdCriteria(vasrdCode);
        String medSummary = buildMedicationSummary(atoms);

        LlmJobRequest.Builder b = LlmJobRequest.builder()
                .purpose("synthesis_rate")
                .claimId(claimId)
                .userId(userId)
                .conditionId(condition.getId())
                .batchGroupKey("synthesis_rate_" + claimId);

        if (promptCachingEnabled) {
            // Volatile tail: only this one condition. The atom corpus is NOT here —
            // it is the cached prefix block, identical for every condition this run.
            String volatileTail = String.format("""
                    Rate this specific VA disability condition based on the evidence in the
                    EVIDENCE ATOM CORPUS above.

                    Condition: %s (VASRD %s)

                    VASRD Rating Criteria:
                    %s

                    %s""", conditionName, vasrdCode != null ? vasrdCode : "unknown",
                    vasrdCriteria, medSummary);
            return b
                    .structuredPrompt(new LlmJobRequest.StructuredPrompt(
                            List.of(SYSTEM_PROMPT), AtomCorpusRenderer.render(atoms)))
                    .userMessage(volatileTail)
                    .build();
        }

        // Flag OFF — today's exact flat shape: atoms inline in the userMessage.
        String atomText = formatAtoms(atoms);
        String userMessage = String.format("""
                Rate this specific VA disability condition based on the evidence provided.

                Condition: %s (VASRD %s)

                VASRD Rating Criteria:
                %s

                %s

                Evidence atoms (%d total):
                %s
                """, conditionName, vasrdCode != null ? vasrdCode : "unknown",
                vasrdCriteria, medSummary, atoms.size(), atomText);

        return b
                .systemPrompt(SYSTEM_PROMPT)
                .userMessage(userMessage)
                .build();
    }

    /**
     * Parse the LLM response JSON object into a rating map.
     */
    public Map<String, Object> parseResponse(LlmJobResult result) {
        try {
            String cleaned = cleanJsonResponse(result.getText());
            return objectMapper.readValue(cleaned, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            log.warn("RatingAgent: failed to parse rating response, defaulting to 0");
            return Map.of("estimated_rating", 0, "rating_rationale", "Rating could not be determined", "confidence", 0.3);
        }
    }

    private String lookupVasrdCriteria(String vasrdCode) {
        if (vasrdCode == null) return "No VASRD code provided -- use general VA rating principles.";
        if (vasrdDataService == null) return "VASRD lookup unavailable -- use general VA rating principles for code " + vasrdCode + ".";

        Optional<Map<String, Object>> codeData = vasrdDataService.getByCode(vasrdCode);
        if (codeData.isPresent()) {
            Map<String, Object> data = codeData.get();
            StringBuilder sb = new StringBuilder();
            sb.append("Code: ").append(data.getOrDefault("code", vasrdCode)).append("\n");
            sb.append("Name: ").append(data.getOrDefault("name", "")).append("\n");
            sb.append("Body System: ").append(data.getOrDefault("body_system", "")).append("\n");
            Object criteria = data.get("rating_criteria");
            if (criteria != null) {
                sb.append("Rating Criteria:\n").append(criteria).append("\n");
            }
            return sb.toString();
        }

        return "VASRD code " + vasrdCode + " not found in local database -- use general VA rating principles for this diagnostic code.";
    }

    private String buildMedicationSummary(List<Atom> atoms) {
        Map<String, java.util.Set<String>> drugSources = new java.util.LinkedHashMap<>();
        List<String> corticosteroids = List.of("prednisone", "prednisolone", "methylprednisolone",
                "dexamethasone", "hydrocortisone", "budesonide", "triamcinolone");

        for (Atom a : atoms) {
            String type = a.getType() != null ? a.getType().toLowerCase() : "";
            if (!type.contains("medication") && !type.contains("prescription")) continue;

            String val = a.getValue() != null ? a.getValue().toLowerCase() : "";
            String source = a.getSource() != null ? a.getSource() : "unknown";

            for (String drug : corticosteroids) {
                if (val.contains(drug)) {
                    drugSources.computeIfAbsent(drug, k -> new java.util.LinkedHashSet<>()).add(source);
                }
            }
        }

        if (drugSources.isEmpty()) return "";

        StringBuilder sb = new StringBuilder("=== CORTICOSTEROID COURSE COUNT (pre-computed) ===\n");
        int totalCourses = 0;
        for (var entry : drugSources.entrySet()) {
            int count = entry.getValue().size();
            totalCourses += count;
            sb.append(String.format("- %s: %d course(s) from sources: %s\n",
                    entry.getKey(), count, String.join(", ", entry.getValue())));
        }
        sb.append(String.format("TOTAL systemic corticosteroid courses: %d\n", totalCourses));
        sb.append("NOTE: Each unique evidence source = one course. 3+ courses/year meets VASRD 6602 60% threshold.\n");
        return sb.toString();
    }

    private String formatAtoms(List<Atom> atoms) {
        if (atoms.isEmpty()) return "No specific evidence atoms available.";
        return atoms.stream()
                .map(a -> String.format("- [%s] %s (source: %s, confidence: %.2f)",
                        a.getType(), a.getValue(), a.getSource(), a.getConfidence()))
                .collect(Collectors.joining("\n"));
    }

    private String cleanJsonResponse(String text) {
        String cleaned = text.strip();
        if (cleaned.startsWith("```json")) cleaned = cleaned.substring(7);
        else if (cleaned.startsWith("```")) cleaned = cleaned.substring(3);
        if (cleaned.endsWith("```")) cleaned = cleaned.substring(0, cleaned.length() - 3);
        return cleaned.strip();
    }
}
