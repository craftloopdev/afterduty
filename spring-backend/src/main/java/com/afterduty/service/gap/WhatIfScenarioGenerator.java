package com.afterduty.service.gap;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.afterduty.model.IdentifiedCondition;
import com.afterduty.service.llm.LlmJobRequest;
import com.afterduty.service.llm.LlmJobResult;
import com.afterduty.service.llm.LlmJobService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * What-if scenario generator. Exposes buildRequest/parseResponse for GapStateMachine.
 */
@Service
public class WhatIfScenarioGenerator {

    private static final Logger log = LoggerFactory.getLogger(WhatIfScenarioGenerator.class);

    private final LlmJobService llmJobService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${va-claim.gemini.thinking-budget:10240}")
    private int thinkingBudget;

    private static final int MAX_OUTPUT_TOKENS = 65536;

    private static final String SYSTEM_PROMPT = """
            You are a VA disability rating impact analyst. For each evidence gap, calculate the potential \
            impact on this veteran's rating.

            For each scenario, provide:
            - scenario: description of what changes
            - action: what the veteran does
            - current_rating: the current rating percentage
            - potential_rating: the likely new rating (from valid set: 0, 10, 20, 30, 40, 50, 60, 70, 80, 100)
            - monthly_delta: approximate change in monthly compensation (use 2024 VA rate tables)
            - action_required: specific steps
            - confidence: how likely this outcome is (0-1)
            - timeline: estimated time to obtain evidence (e.g., '2-4 weeks', '1-3 months')

            Return ONLY a JSON array ordered by monthly_delta descending (highest impact first), no other text.
            """;

    public WhatIfScenarioGenerator(LlmJobService llmJobService) {
        this.llmJobService = llmJobService;
    }

    /**
     * Convenience: submit a what-if scenario job via LlmJobService.
     */
    public UUID submit(IdentifiedCondition condition, List<Map<String, Object>> gaps,
                       Long claimId, Long userId) {
        return llmJobService.submit(buildRequest(condition, gaps, claimId, userId));
    }

    /**
     * Build a provider-agnostic LlmJobRequest for what-if scenario generation.
     */
    public LlmJobRequest buildRequest(IdentifiedCondition condition,
                                       List<Map<String, Object>> gaps,
                                       Long claimId, Long userId) {
        StringBuilder gapsSummary = new StringBuilder();
        for (int i = 0; i < gaps.size(); i++) {
            Map<String, Object> gap = gaps.get(i);
            gapsSummary.append(String.format("  %d. [%s] %s (priority: %s, impact: %s)\n",
                    i + 1,
                    gap.getOrDefault("type", "unknown"),
                    gap.getOrDefault("description", ""),
                    gap.getOrDefault("priority", "medium"),
                    gap.getOrDefault("impact", "")));
        }

        String userMessage = String.format("""
                Condition: %s (VASRD %s)
                Current Rating: %d%%

                For each gap below, if the veteran obtains this evidence, what would the new rating likely be?

                Gaps:
                %s
                """,
                condition.getName(),
                condition.getVasrdCode() != null ? condition.getVasrdCode() : "unknown",
                condition.getEstimatedRating() != null ? condition.getEstimatedRating() : 0,
                gapsSummary);

        return LlmJobRequest.builder()
                .purpose("gap_whatif")
                .systemPrompt(SYSTEM_PROMPT)
                .userMessage(userMessage)
                .maxTokens(MAX_OUTPUT_TOKENS)
                .thinkingBudget(thinkingBudget)
                .claimId(claimId)
                .userId(userId)
                .conditionId(condition.getId())
                .batchGroupKey("gap_whatif_" + claimId)
                .build();
    }

    /**
     * Parse the LLM response JSON array into a list of scenario maps.
     */
    public List<Map<String, Object>> parseResponse(LlmJobResult result) {
        try {
            String cleaned = cleanJsonResponse(result.getText());
            return objectMapper.readValue(cleaned, new TypeReference<List<Map<String, Object>>>() {});
        } catch (Exception e) {
            log.error("Failed to parse what-if scenarios JSON: {}", e.getMessage());
            return List.of();
        }
    }

    private String cleanJsonResponse(String text) {
        String cleaned = text.strip();
        if (cleaned.startsWith("```json")) cleaned = cleaned.substring(7);
        else if (cleaned.startsWith("```")) cleaned = cleaned.substring(3);
        if (cleaned.endsWith("```")) cleaned = cleaned.substring(0, cleaned.length() - 3);
        return cleaned.strip();
    }
}
