package com.afterduty.service.synthesis;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.afterduty.service.llm.LlmJobRequest;
import com.afterduty.service.llm.LlmJobResult;
import com.afterduty.service.llm.LlmJobService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.UUID;

/**
 * Runs after ConditionIdentificationAgent to identify and merge duplicate
 * conditions. Exposes buildRequest/parseResponse for SynthesisStateMachine.
 */
@Service
public class DuplicateConditionMerger {

    private static final Logger log = LoggerFactory.getLogger(DuplicateConditionMerger.class);

    private static final int MAX_OUTPUT_TOKENS = 4096;
    private static final int THINKING_BUDGET = 6000;

    private final LlmJobService llmJobService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final String SYSTEM_PROMPT = """
            You are a VA disability claims duplicate-condition auditor. You receive a list of
            identified conditions from a synthesis agent and must decide which are duplicates of
            each other.

            DEFINITION OF DUPLICATE
            Two conditions are duplicates when they describe the SAME clinical entity at the same
            anatomic location — e.g., "PTSD" and "Post-Traumatic Stress Disorder", or "Lumbar Strain"
            and "Low Back Strain (L4–L5)". Different names for the same underlying VASRD entry.

            NOT DUPLICATES — DO NOT MERGE
            - Bilateral conditions: "Right knee patellofemoral syndrome" and "Left knee patellofemoral
              syndrome" are separate claims and earn separate ratings. Never merge them.
            - Secondary vs primary: "PTSD" and "Depression secondary to PTSD" are separate claims.
            - Different VASRD diagnostic codes that happen to share a body system.
            - Conditions that share a body system but have distinct symptom sets (e.g., migraines vs
              tension headaches).

            WHEN IN DOUBT, DO NOT MERGE. False merges cost veterans money. A missed merge is a small
            presentation issue; a wrong merge suppresses a separately-ratable condition.

            MERGE OUTPUT SHAPE — return ONLY this JSON object:
            {
              "merge_groups": [
                {
                  "keep_index": <int — index in input array to keep as the canonical record>,
                  "absorb_indices": [<int>, <int>, ...],
                  "canonical_name": "<short name for the merged record>",
                  "reason": "<1 sentence explaining why they are the same clinical entity>",
                  "merged_notes": "<paragraph of evidence from all absorbed copies, deduped>"
                }
              ],
              "standalone_indices": [<int>, ...]
            }

            Every input index must appear exactly once across keep_index, absorb_indices, or
            standalone_indices. If there are no duplicates, return merge_groups=[] and list all
            indices in standalone_indices.
            """;

    public DuplicateConditionMerger(LlmJobService llmJobService) {
        this.llmJobService = llmJobService;
    }

    /**
     * Convenience: submit a duplicate-merger job via LlmJobService.
     */
    public UUID submit(List<Map<String, Object>> conditions, Long claimId, Long userId) {
        return llmJobService.submit(buildRequest(conditions, claimId, userId));
    }

    /**
     * Build a provider-agnostic LlmJobRequest for duplicate merging.
     */
    public LlmJobRequest buildRequest(List<Map<String, Object>> conditions, Long claimId, Long userId) {
        String conditionsJson;
        try {
            conditionsJson = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(conditions);
        } catch (Exception e) {
            conditionsJson = "[]";
        }

        String userMessage = String.format("""
                Identified conditions (%d):
                %s

                Decide which (if any) are duplicates. Return the JSON object only.
                """,
                conditions.size(),
                conditionsJson);

        return LlmJobRequest.builder()
                .purpose("synthesis_duplicate_merger")
                .systemPrompt(SYSTEM_PROMPT)
                .userMessage(userMessage)
                .maxTokens(MAX_OUTPUT_TOKENS)
                .thinkingBudget(THINKING_BUDGET)
                .claimId(claimId)
                .userId(userId)
                .build();
    }

    /**
     * Parse the LLM response and apply merge decisions to the original condition list.
     */
    public List<Map<String, Object>> parseResponse(LlmJobResult result,
                                                    List<Map<String, Object>> originalConditions) {
        return applyMergeDecision(originalConditions, result.getText());
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> applyMergeDecision(List<Map<String, Object>> input, String responseText) {
        try {
            String cleaned = cleanJsonResponse(responseText);

            // If the model returned a plain JSON array, treat it directly as the merged list.
            // This handles test fakes and any provider that returns the final list rather than
            // the merge-decision object.
            if (cleaned.startsWith("[")) {
                List<Map<String, Object>> direct = objectMapper.readValue(
                        cleaned, new TypeReference<List<Map<String, Object>>>() {});
                log.info("Duplicate merger received direct array — using {} merged condition(s)", direct.size());
                return direct;
            }

            Map<String, Object> parsed = objectMapper.readValue(cleaned,
                    new TypeReference<Map<String, Object>>() {});

            List<Map<String, Object>> groups = objectMapper.convertValue(
                    parsed.getOrDefault("merge_groups", List.of()),
                    new TypeReference<List<Map<String, Object>>>() {});
            List<Integer> standalone = objectMapper.convertValue(
                    parsed.getOrDefault("standalone_indices", List.of()),
                    new TypeReference<List<Integer>>() {});

            Set<Integer> absorbed = new HashSet<>();
            List<Map<String, Object>> merged = new ArrayList<>();

            for (Map<String, Object> group : groups) {
                Integer keepIdx = toInt(group.get("keep_index"));
                List<Integer> absorbIdx = objectMapper.convertValue(
                        group.getOrDefault("absorb_indices", List.of()),
                        new TypeReference<List<Integer>>() {});
                if (keepIdx == null || keepIdx < 0 || keepIdx >= input.size()) continue;
                if (absorbIdx == null || absorbIdx.isEmpty()) {
                    merged.add(input.get(keepIdx));
                    absorbed.add(keepIdx);
                    continue;
                }

                Map<String, Object> kept = new LinkedHashMap<>(input.get(keepIdx));
                String canonicalName = asString(group.get("canonical_name"));
                if (canonicalName != null) kept.put("name", canonicalName);

                List<String> duplicateNames = new ArrayList<>();
                for (Integer i : absorbIdx) {
                    if (i == null || i < 0 || i >= input.size() || i.equals(keepIdx)) continue;
                    Map<String, Object> dup = input.get(i);
                    duplicateNames.add(asString(dup.get("name")));
                    absorbed.add(i);
                    mergeConfidence(kept, dup);
                    mergeTriadEvidence(kept, dup);
                }
                kept.put("merged_duplicates", duplicateNames);
                String reason = asString(group.get("reason"));
                if (reason != null) kept.put("merge_reason", reason);
                String notes = asString(group.get("merged_notes"));
                if (notes != null) kept.put("merged_notes", notes);
                merged.add(kept);
                absorbed.add(keepIdx);
            }

            for (Integer i : standalone) {
                if (i == null || i < 0 || i >= input.size()) continue;
                if (absorbed.contains(i)) continue;
                merged.add(input.get(i));
                absorbed.add(i);
            }

            // Safety: include anything the model forgot about
            for (int i = 0; i < input.size(); i++) {
                if (!absorbed.contains(i)) merged.add(input.get(i));
            }

            if (!groups.isEmpty()) {
                log.info("Duplicate merger collapsed {} conditions into {}", input.size(), merged.size());
            }
            return merged;
        } catch (Exception e) {
            log.error("Failed to parse merger output: {} — returning originals", e.getMessage());
            return input;
        }
    }

    @SuppressWarnings("unchecked")
    private void mergeConfidence(Map<String, Object> kept, Map<String, Object> dup) {
        Object kc = kept.get("confidence");
        Object dc = dup.get("confidence");
        if (kc instanceof Number && dc instanceof Number) {
            double k = ((Number) kc).doubleValue();
            double d = ((Number) dc).doubleValue();
            kept.put("confidence", Math.min(1.0, Math.max(k, d) + 0.5 * (1 - Math.max(k, d)) * (k * d)));
        }
    }

    @SuppressWarnings("unchecked")
    private void mergeTriadEvidence(Map<String, Object> kept, Map<String, Object> dup) {
        for (String leg : List.of("triad_diagnosis", "triad_in_service", "triad_nexus")) {
            Object dupLeg = dup.get(leg);
            Object keptLeg = kept.get(leg);
            if (dupLeg instanceof Map && keptLeg instanceof Map) {
                Map<String, Object> keptMap = new LinkedHashMap<>((Map<String, Object>) keptLeg);
                Map<String, Object> dupMap = (Map<String, Object>) dupLeg;
                Object keptEv = keptMap.get("evidence");
                Object dupEv = dupMap.get("evidence");
                if (keptEv instanceof List && dupEv instanceof List) {
                    Set<Object> combined = new LinkedHashSet<>();
                    combined.addAll((List<Object>) keptEv);
                    combined.addAll((List<Object>) dupEv);
                    keptMap.put("evidence", new ArrayList<>(combined));
                    kept.put(leg, keptMap);
                }
            } else if (dupLeg != null && keptLeg == null) {
                kept.put(leg, dupLeg);
            }
        }
    }

    private static Integer toInt(Object o) {
        if (o instanceof Number n) return n.intValue();
        if (o instanceof String s) try { return Integer.parseInt(s); } catch (Exception ignored) {}
        return null;
    }

    private static String asString(Object o) {
        if (o instanceof String s && !s.isBlank() && !"null".equalsIgnoreCase(s)) return s;
        return null;
    }

    private String cleanJsonResponse(String text) {
        String cleaned = text.strip();
        if (cleaned.startsWith("```json")) cleaned = cleaned.substring(7);
        else if (cleaned.startsWith("```")) cleaned = cleaned.substring(3);
        if (cleaned.endsWith("```")) cleaned = cleaned.substring(0, cleaned.length() - 3);
        return cleaned.strip();
    }
}
