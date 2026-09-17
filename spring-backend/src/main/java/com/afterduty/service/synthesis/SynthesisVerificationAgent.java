package com.afterduty.service.synthesis;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.afterduty.service.llm.LlmJobRequest;
import com.afterduty.service.llm.LlmJobResult;
import com.afterduty.service.llm.LlmJobService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Phase 3 Sub-Agent: Synthesis Verification.
 * Exposes buildRequest/parseResponse for use by SynthesisStateMachine.
 */
@Service
public class SynthesisVerificationAgent {

    private static final Logger log = LoggerFactory.getLogger(SynthesisVerificationAgent.class);

    private final LlmJobService llmJobService;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private com.afterduty.service.DomainCorrectionsService domainCorrectionsService;

    private static final String SYSTEM_PROMPT = """
            You are a quality assurance reviewer for VA disability claims. Review these identified conditions \
            and ratings for errors.

            Check each condition:
            1. Is the VASRD code valid and correct for this condition?
            2. Does the rating match the documented severity?
            3. Is there sufficient evidence for each triad element?
            4. Are there missing secondary conditions that should have been identified?
            5. Are there pyramiding issues (same disability rated twice under different codes)?

            For each issue found, return:
            {"condition_name": "<name>", "issue_type": "<invalid_code|rating_mismatch|insufficient_evidence|missing_secondary|pyramiding>", \
            "description": "<detailed description>", "suggested_fix": "<what should be changed>"}

            Return a JSON array of issues. Empty array [] = no issues found.
            Return ONLY the JSON array, no other text.
            """;

    public SynthesisVerificationAgent(LlmJobService llmJobService) {
        this.llmJobService = llmJobService;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setDomainCorrectionsService(com.afterduty.service.DomainCorrectionsService svc) {
        this.domainCorrectionsService = svc;
    }

    /**
     * Convenience: submit a verification job via LlmJobService.
     */
    public UUID submit(List<Map<String, Object>> conditions, Long claimId, Long userId) {
        return llmJobService.submit(buildRequest(conditions, claimId, userId));
    }

    /**
     * Build a provider-agnostic LlmJobRequest for condition verification.
     */
    public LlmJobRequest buildRequest(List<Map<String, Object>> conditions, Long claimId, Long userId) {
        String conditionsSummary = formatConditionsForReview(conditions);

        // Self-correction KB (domain-corrections.json): feedback-disproven
        // claims the reviewer must flag on sight. Empty KB → empty string, the
        // message is byte-identical to before the KB existed.
        String corpus = domainCorrectionsService != null ? domainCorrectionsService.promptCorpus() : "";
        String correctionsSection = corpus.isEmpty() ? "" : "\n=== " + corpus + "===\n";

        String userMessage = String.format("""
                Review the following identified VA disability conditions and their ratings for errors.
                %s
                === CONDITIONS TO REVIEW (%d total) ===
                %s
                """, correctionsSection, conditions.size(), conditionsSummary);

        return LlmJobRequest.builder()
                .purpose("synthesis_verify")
                .systemPrompt(SYSTEM_PROMPT)
                .userMessage(userMessage)
                .claimId(claimId)
                .userId(userId)
                .build();
    }

    /**
     * Parse the LLM response JSON array into a list of issue maps.
     */
    public List<Map<String, Object>> parseResponse(LlmJobResult result) {
        try {
            String cleaned = cleanJsonResponse(result.getText());
            return objectMapper.readValue(cleaned, new TypeReference<List<Map<String, Object>>>() {});
        } catch (Exception e) {
            log.error("Failed to parse verification response: {}", e.getMessage());
            return List.of();
        }
    }

    private String formatConditionsForReview(List<Map<String, Object>> conditions) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < conditions.size(); i++) {
            Map<String, Object> c = conditions.get(i);
            sb.append("\n### ").append(i + 1).append(". ").append(c.getOrDefault("name", "Unknown"));
            sb.append(" (VASRD ").append(c.getOrDefault("vasrd_code", "?")).append(")\n");
            sb.append("Body system: ").append(c.getOrDefault("body_system", "unknown")).append("\n");
            sb.append("Rating: ").append(c.getOrDefault("estimated_rating", 0)).append("%\n");
            sb.append("Rating rationale: ").append(c.getOrDefault("rating_rationale", "none")).append("\n");
            sb.append("Presumptive: ").append(c.getOrDefault("is_presumptive", false));
            Object basis = c.get("presumptive_basis");
            if (basis != null) sb.append(" (").append(basis).append(")");
            sb.append("\n");
            sb.append("Diagnosis triad: ").append(c.getOrDefault("triad_diagnosis", "N/A")).append("\n");
            sb.append("In-service triad: ").append(c.getOrDefault("triad_in_service", "N/A")).append("\n");
            sb.append("Nexus triad: ").append(c.getOrDefault("triad_nexus", "N/A")).append("\n");
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
