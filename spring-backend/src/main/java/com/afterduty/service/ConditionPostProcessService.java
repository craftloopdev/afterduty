package com.afterduty.service;

import com.afterduty.model.IdentifiedCondition;
import com.afterduty.repository.ConditionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Post-processing for conditions: deduplication and pyramiding detection.
 * Runs after synthesis to clean up and annotate conditions.
 */
@Service
public class ConditionPostProcessService {

    private static final Logger log = LoggerFactory.getLogger(ConditionPostProcessService.class);

    private final ConditionRepository conditionRepository;

    public ConditionPostProcessService(ConditionRepository conditionRepository) {
        this.conditionRepository = conditionRepository;
    }

    /**
     * Run all post-processing on conditions for a claim.
     */
    @Transactional
    public void postProcess(Long claimId) {
        deduplicateConditions(claimId);
        detectPyramiding(claimId);
    }

    // ==================== DEDUPLICATION ====================

    /**
     * Merge conditions with the same VASRD code. Keep the highest-rated one,
     * merge evidence from others, mark others as superseded.
     */
    @Transactional
    public int deduplicateConditions(Long claimId) {
        // Mission 5b — operate on the ACTIVE generation only. (Retired prior-gen
        // rows already carry a non-null superseded_by; the same-VASRD dedup below
        // sets superseded_by = keeper.id WITHIN this active generation.)
        List<IdentifiedCondition> conditions = conditionRepository.findByClaimIdAndSupersededByIsNull(claimId);
        if (conditions.size() <= 1) return 0;

        // Group by VASRD code (skip nulls)
        Map<String, List<IdentifiedCondition>> byVasrd = conditions.stream()
                .filter(c -> c.getVasrdCode() != null && !c.getVasrdCode().isBlank())
                .collect(Collectors.groupingBy(IdentifiedCondition::getVasrdCode));

        int mergedCount = 0;
        for (Map.Entry<String, List<IdentifiedCondition>> entry : byVasrd.entrySet()) {
            List<IdentifiedCondition> group = entry.getValue();
            if (group.size() <= 1) continue;

            // Sort by estimated rating descending, then confidence descending
            group.sort((a, b) -> {
                int ratingCompare = Integer.compare(
                        b.getEstimatedRating() != null ? b.getEstimatedRating() : 0,
                        a.getEstimatedRating() != null ? a.getEstimatedRating() : 0);
                if (ratingCompare != 0) return ratingCompare;
                return Double.compare(
                        b.getConfidence() != null ? b.getConfidence() : 0,
                        a.getConfidence() != null ? a.getConfidence() : 0);
            });

            IdentifiedCondition keeper = group.get(0);

            // Merge evidence from others into keeper
            for (int i = 1; i < group.size(); i++) {
                IdentifiedCondition dupe = group.get(i);
                mergeTriadEvidence(keeper, dupe);
                dupe.setSupersededBy(keeper.getId());
                conditionRepository.save(dupe);
                mergedCount++;
                log.info("Dedup: '{}' (VASRD {}, {}%) superseded by '{}' ({}%)",
                        dupe.getName(), dupe.getVasrdCode(), dupe.getEstimatedRating(),
                        keeper.getName(), keeper.getEstimatedRating());
            }
            conditionRepository.save(keeper);
        }

        if (mergedCount > 0) {
            log.info("Deduplicated {} conditions for claim {}", mergedCount, claimId);
        }
        return mergedCount;
    }

    @SuppressWarnings("unchecked")
    private void mergeTriadEvidence(IdentifiedCondition keeper, IdentifiedCondition source) {
        keeper.setTriadDiagnosis(mergeTriadElement(keeper.getTriadDiagnosis(), source.getTriadDiagnosis()));
        keeper.setTriadInService(mergeTriadElement(keeper.getTriadInService(), source.getTriadInService()));
        keeper.setTriadNexus(mergeTriadElement(keeper.getTriadNexus(), source.getTriadNexus()));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> mergeTriadElement(Map<String, Object> a, Map<String, Object> b) {
        if (a == null) return b;
        if (b == null) return a;

        Map<String, Object> merged = new HashMap<>(a);
        // Merge evidence lists
        List<String> evidenceA = (List<String>) a.getOrDefault("evidence", List.of());
        List<String> evidenceB = (List<String>) b.getOrDefault("evidence", List.of());
        Set<String> combined = new LinkedHashSet<>(evidenceA);
        combined.addAll(evidenceB);
        merged.put("evidence", new ArrayList<>(combined));
        return merged;
    }

    // ==================== PYRAMIDING DETECTION ====================

    // VASRD code ranges for pyramiding groups
    private static final Map<String, int[]> PYRAMID_RANGES = Map.of(
            "mental_health", new int[]{9200, 9440},
            "respiratory", new int[]{6600, 6899},
            "digestive", new int[]{7200, 7399},
            "musculoskeletal_spine", new int[]{5235, 5243}
    );

    // Individual VASRD codes that pyramid with specific groups
    private static final Map<Integer, String> INDIVIDUAL_PYRAMID_CODES = Map.of(
            8045, "mental_health"  // TBI pyramids with mental health conditions
    );

    // Known same-condition pairs (normalized names)
    private static final List<String[]> SAME_CONDITIONS = List.of(
            new String[]{"ptsd", "complex ptsd", "post-traumatic stress disorder", "complex post-traumatic stress disorder"},
            new String[]{"major depressive disorder", "depression", "major depression", "depressive disorder"},
            new String[]{"generalized anxiety disorder", "anxiety", "anxiety disorder"},
            new String[]{"insomnia", "sleep disturbance", "chronic insomnia"}
    );

    /**
     * Detect pyramiding groups among active (non-superseded) conditions.
     */
    @Transactional
    public void detectPyramiding(Long claimId) {
        // Mission 5b — active generation only.
        List<IdentifiedCondition> conditions = conditionRepository.findByClaimIdAndSupersededByIsNull(claimId);

        if (conditions.size() <= 1) return;

        // Reset existing pyramid assignments
        for (IdentifiedCondition c : conditions) {
            c.setPyramidGroup(null);
            c.setPyramidReason(null);
        }

        // Detect by VASRD code range
        for (IdentifiedCondition c : conditions) {
            if (c.getVasrdCode() == null) continue;
            try {
                int code = Integer.parseInt(c.getVasrdCode());
                for (Map.Entry<String, int[]> range : PYRAMID_RANGES.entrySet()) {
                    if (code >= range.getValue()[0] && code <= range.getValue()[1]) {
                        c.setPyramidGroup(range.getKey());
                        c.setPyramidReason("VASRD code " + c.getVasrdCode() + " is in the " +
                                range.getKey().replace("_", " ") + " category (38 CFR § 4.14)");
                        break;
                    }
                }
            } catch (NumberFormatException ignored) {}

            // Check individual codes
            if (c.getPyramidGroup() == null) {
                try {
                    int code = Integer.parseInt(c.getVasrdCode());
                    String group = INDIVIDUAL_PYRAMID_CODES.get(code);
                    if (group != null) {
                        c.setPyramidGroup(group);
                        c.setPyramidReason("VASRD code " + c.getVasrdCode() + " pyramids with " +
                                group.replace("_", " ") + " conditions (38 CFR § 4.14, § 4.124a)");
                    }
                } catch (NumberFormatException ignored2) {}
            }
        }

        // Detect same-condition duplicates by PRIMARY condition name
        // Strip secondary qualifiers like "(Secondary to PTSD)" before matching
        for (IdentifiedCondition c : conditions) {
            String normalizedName = c.getName().toLowerCase().trim();
            // Remove parenthetical secondary qualifiers for matching
            String primaryName = normalizedName.replaceAll("\\(secondary to [^)]+\\)", "").trim();
            for (String[] group : SAME_CONDITIONS) {
                for (String alias : group) {
                    if (primaryName.contains(alias)) {
                        if (c.getPyramidGroup() == null) {
                            c.setPyramidGroup("mental_health"); // all current same-conditions are mental health
                        }
                        c.setPyramidReason(c.getPyramidReason() != null
                                ? c.getPyramidReason() + "; also considered same condition as other " + group[0] + " variants"
                                : "Same condition category as " + group[0] + " (38 CFR § 4.14)");
                        break;
                    }
                }
            }
        }

        // Also detect by body_system containing "Mental"
        for (IdentifiedCondition c : conditions) {
            if (c.getBodySystem() != null && c.getBodySystem().toLowerCase().contains("mental") && c.getPyramidGroup() == null) {
                c.setPyramidGroup("mental_health");
                c.setPyramidReason("Mental health conditions are rated under a single evaluation (38 CFR § 4.14, § 4.130)");
            }
        }

        // The highest-rated member of each pyramid group is the ABSORBER: it must
        // keep a BLANK pyramidReason so PyramidingRules.plan counts it (non-blank
        // reason = excluded). Without this, a whole group — e.g. PTSD 70% +
        // anxiety 50% both stamped "mental_health" — drops out of the combined
        // rating entirely. Absorbed members' reasons name their absorber.
        Map<String, List<IdentifiedCondition>> byGroup = conditions.stream()
                .filter(c -> c.getPyramidGroup() != null)
                .collect(Collectors.groupingBy(IdentifiedCondition::getPyramidGroup));
        for (List<IdentifiedCondition> group : byGroup.values()) {
            IdentifiedCondition absorber = group.stream()
                    .max(Comparator
                            .comparingInt((IdentifiedCondition c) ->
                                    c.getEstimatedRating() != null ? c.getEstimatedRating() : -1)
                            .thenComparingDouble(c ->
                                    c.getConfidence() != null ? c.getConfidence() : 0.0))
                    .orElse(null);
            if (absorber == null) continue;
            absorber.setPyramidReason(null);
            for (IdentifiedCondition c : group) {
                if (c != absorber && c.getPyramidReason() != null) {
                    c.setPyramidReason("Rated inside the '" + absorber.getName()
                            + "' evaluation (38 CFR § 4.14) — VA won't pay the same disability twice");
                }
            }
        }

        // Save all
        for (IdentifiedCondition c : conditions) {
            conditionRepository.save(c);
        }

        // Log pyramid groups
        Map<String, List<String>> groups = conditions.stream()
                .filter(c -> c.getPyramidGroup() != null)
                .collect(Collectors.groupingBy(IdentifiedCondition::getPyramidGroup,
                        Collectors.mapping(IdentifiedCondition::getName, Collectors.toList())));
        for (Map.Entry<String, List<String>> g : groups.entrySet()) {
            log.info("Pyramid group '{}': {}", g.getKey(), g.getValue());
        }
    }
}
