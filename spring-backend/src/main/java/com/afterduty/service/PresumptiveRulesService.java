package com.afterduty.service;

import org.springframework.stereotype.Service;

import java.util.*;
import java.util.regex.Pattern;

/**
 * Presumptive service connection rules -- PACT Act, Agent Orange, Gulf War.
 * Pure business logic, zero AI dependencies.
 */
@Service
public class PresumptiveRulesService {

    private static final List<Map<String, Object>> PRESUMPTIVE_RULES = List.of(
            // PACT Act -- Burn Pit / Airborne Hazards (post-9/11)
            Map.of(
                    "category", "PACT Act — Burn Pit Exposure",
                    "qualifying_service", Map.of(
                            "locations", List.of("Iraq", "Afghanistan", "Southwest Asia", "Syria", "Jordan", "Egypt", "Lebanon", "Yemen", "Uzbekistan"),
                            "after", "2001-09-11"
                    ),
                    "conditions", List.of(
                            Map.of("name", "Bronchial Asthma", "vasrd_code", "6602"),
                            Map.of("name", "Sinusitis (chronic)", "vasrd_code", "6513"),
                            Map.of("name", "Rhinitis (chronic)", "vasrd_code", "6522"),
                            Map.of("name", "Laryngitis (chronic)", "vasrd_code", "6516"),
                            Map.of("name", "Lung Cancer", "vasrd_code", "6819"),
                            Map.of("name", "Pancreatic Cancer", "vasrd_code", "7319"),
                            Map.of("name", "Kidney Cancer", "vasrd_code", "7528"),
                            Map.of("name", "Head Cancer (any type)", "vasrd_code", "6819"),
                            Map.of("name", "Neck Cancer (any type)", "vasrd_code", "6819"),
                            Map.of("name", "Respiratory Cancer (any type)", "vasrd_code", "6819"),
                            Map.of("name", "Gastrointestinal Cancer", "vasrd_code", "7343"),
                            Map.of("name", "Reproductive Cancer", "vasrd_code", "7528"),
                            Map.of("name", "Melanoma", "vasrd_code", "7833"),
                            Map.of("name", "Lymphatic Cancer", "vasrd_code", "7715"),
                            Map.of("name", "Glioblastoma", "vasrd_code", "8003"),
                            Map.of("name", "Constrictive Bronchiolitis", "vasrd_code", "6600"),
                            Map.of("name", "Constrictive Pericarditis", "vasrd_code", "7002")
                    )
            ),
            // Agent Orange -- Vietnam/Thailand era
            Map.of(
                    "category", "Agent Orange (Herbicide Exposure)",
                    "qualifying_service", Map.of(
                            "locations", List.of("Vietnam", "Thailand"),
                            "between", List.of("1962-01-01", "1975-05-07")
                    ),
                    "conditions", List.of(
                            Map.of("name", "Diabetes Mellitus Type 2", "vasrd_code", "7913"),
                            Map.of("name", "Ischemic Heart Disease", "vasrd_code", "7005"),
                            Map.of("name", "Parkinson's Disease", "vasrd_code", "8004"),
                            Map.of("name", "Chronic B-Cell Leukemia", "vasrd_code", "7703"),
                            Map.of("name", "Hodgkin's Disease", "vasrd_code", "7709"),
                            Map.of("name", "Non-Hodgkin's Lymphoma", "vasrd_code", "7715"),
                            Map.of("name", "Prostate Cancer", "vasrd_code", "7528"),
                            Map.of("name", "Bladder Cancer", "vasrd_code", "7528"),
                            Map.of("name", "Lung Cancer", "vasrd_code", "6819"),
                            Map.of("name", "Soft Tissue Sarcoma", "vasrd_code", "5012"),
                            Map.of("name", "AL Amyloidosis", "vasrd_code", "7717"),
                            Map.of("name", "Peripheral Neuropathy (early onset)", "vasrd_code", "8520"),
                            Map.of("name", "Porphyria Cutanea Tarda", "vasrd_code", "7815"),
                            Map.of("name", "Chloracne", "vasrd_code", "7829"),
                            Map.of("name", "Hypertension", "vasrd_code", "7101"),
                            Map.of("name", "Monoclonal Gammopathy", "vasrd_code", "7704")
                    )
            ),
            // Gulf War Illness -- Southwest Asia 1990+
            Map.of(
                    "category", "Gulf War Presumptives",
                    "qualifying_service", Map.of(
                            "locations", List.of("Southwest Asia", "Iraq", "Kuwait", "Saudi Arabia", "Bahrain", "Qatar", "UAE", "Oman"),
                            "after", "1990-08-02"
                    ),
                    // The "Undiagnosed Illness" entries are the 38 CFR 3.317
                    // sign/symptom lanes — they apply ONLY while the illness is
                    // undiagnosed. They share VASRD codes with common DIAGNOSED
                    // conditions (8100 = migraines, 6847 = sleep apnea …), so a
                    // bare code join would falsely flag those as presumptive
                    // (DC-2026-001/-002 in domain-corrections.json — exactly the
                    // bug that shipped). `undiagnosed: true` lets consumers
                    // exclude them from diagnosed-condition matching.
                    "conditions", List.of(
                            Map.of("name", "Chronic Fatigue Syndrome", "vasrd_code", "6354"),
                            Map.of("name", "Fibromyalgia", "vasrd_code", "5025"),
                            Map.of("name", "Irritable Bowel Syndrome", "vasrd_code", "7319"),
                            Map.of("name", "Undiagnosed Illness (functional gastrointestinal)", "vasrd_code", "7399", "undiagnosed", true),
                            Map.of("name", "Undiagnosed Illness (chronic headaches)", "vasrd_code", "8100", "undiagnosed", true),
                            Map.of("name", "Undiagnosed Illness (joint pain)", "vasrd_code", "5099", "undiagnosed", true),
                            Map.of("name", "Undiagnosed Illness (neurological symptoms)", "vasrd_code", "8099", "undiagnosed", true),
                            Map.of("name", "Undiagnosed Illness (sleep disturbances)", "vasrd_code", "6847", "undiagnosed", true),
                            Map.of("name", "Undiagnosed Illness (skin conditions)", "vasrd_code", "7899", "undiagnosed", true),
                            Map.of("name", "Undiagnosed Illness (respiratory symptoms)", "vasrd_code", "6899", "undiagnosed", true)
                    )
            )
    );

    /**
     * The keywords {@link #checkPresumptiveConnections} matches on for exposure
     * risks. Kept here (next to the matching logic in the rules service) so the
     * text-derivation below stays in lock-step with what the engine actually
     * scores. Order is stable; matching is substring, lower-cased.
     */
    private static final List<String> EXPOSURE_KEYWORDS =
            List.of("burn pit", "agent orange", "herbicide", "gulf war");

    /**
     * The UNION of every {@code qualifying_service.locations} across all
     * {@link #PRESUMPTIVE_RULES}, in canonical casing, de-duplicated. Derived from
     * the rules list (never hand-copied) so the vocabulary can never drift from
     * what the engine matches on. First-seen casing wins.
     */
    @SuppressWarnings("unchecked")
    static List<String> allQualifyingLocations() {
        LinkedHashSet<String> locations = new LinkedHashSet<>();
        for (Map<String, Object> rule : PRESUMPTIVE_RULES) {
            Map<String, Object> qualifying = (Map<String, Object>) rule.get("qualifying_service");
            List<String> ruleLocations = (List<String>) qualifying.getOrDefault("locations", List.of());
            locations.addAll(ruleLocations);
        }
        return new ArrayList<>(locations);
    }

    /**
     * Derive a provisional service profile from free-text evidence (atom values,
     * document snippets, etc.) so the same rules engine that reads a manually
     * entered {@code ServiceProfile} can also fire off facts already extracted
     * from records. Scans each text (lower-cased, substring match) for:
     * <ul>
     *   <li>deployment LOCATIONS — the union of every rule's qualifying locations
     *       ({@link #allQualifyingLocations}); each hit becomes
     *       {@code {"location": <canonical Location>}} in {@code deployments}
     *       (de-duplicated, canonical casing preserved);</li>
     *   <li>exposure RISKS — the {@link #EXPOSURE_KEYWORDS} the engine already
     *       scores ("burn pit", "agent orange", "herbicide", "gulf war"), added
     *       verbatim to {@code exposure_risks} (de-duplicated).</li>
     * </ul>
     * Service dates are returned EMPTY on purpose: this is a provisional inference
     * with no confirmed dates, and the engine's date gate stays permissive when
     * the dates are blank — the correct default for an inferred (not confirmed)
     * profile. The returned map is shaped exactly like the profile
     * {@link #checkPresumptiveConnections} consumes.
     *
     * @param texts free-text strings to scan (null-safe; null/blank entries skipped)
     * @return {@code {"deployments": [...], "exposure_risks": [...],
     *         "service_start": "", "service_end": ""}}
     */
    public Map<String, Object> deriveServiceProfileFromText(Collection<String> texts) {
        LinkedHashSet<String> foundLocations = new LinkedHashSet<>();
        LinkedHashSet<String> foundExposures = new LinkedHashSet<>();
        List<String> locations = allQualifyingLocations();

        if (texts != null) {
            for (String text : texts) {
                if (text == null || text.isBlank()) continue;
                String lower = text.toLowerCase();
                for (String location : locations) {
                    // Word-boundary match so a short location token like "Oman"
                    // matches "served in Oman" but NOT "Romania" (and "Syria" not
                    // "Assyria") — false-positive presumptive flags are damaging
                    // for a claims tool even when labeled provisional.
                    if (containsWord(lower, location.toLowerCase())) foundLocations.add(location);
                }
                for (String keyword : EXPOSURE_KEYWORDS) {
                    if (lower.contains(keyword)) foundExposures.add(keyword);
                }
            }
        }

        List<Map<String, Object>> deployments = new ArrayList<>();
        for (String location : foundLocations) {
            deployments.add(Map.of("location", location));
        }

        Map<String, Object> profile = new LinkedHashMap<>();
        profile.put("deployments", deployments);
        profile.put("exposure_risks", new ArrayList<>(foundExposures));
        profile.put("service_start", "");
        profile.put("service_end", "");
        return profile;
    }

    /** Case-insensitive word-boundary containment (input already lower-cased).
     *  Used for location tokens so short names don't substring-match inside a
     *  larger unrelated word. */
    private static boolean containsWord(String haystackLower, String needleLower) {
        return Pattern.compile("\\b" + Pattern.quote(needleLower) + "\\b")
                .matcher(haystackLower).find();
    }

    /**
     * Check if veteran's service qualifies for any presumptive conditions.
     *
     * @param serviceProfile Map with keys: deployments, exposure_risks, service_start, service_end
     * @return List of matching conditions with basis
     */
    @SuppressWarnings("unchecked")
    public List<Map<String, String>> checkPresumptiveConnections(Map<String, Object> serviceProfile) {
        List<Map<String, String>> matches = new ArrayList<>();

        List<?> deployments = serviceProfile.get("deployments") instanceof List<?> l ? l : List.of();
        List<?> exposureRisks = serviceProfile.get("exposure_risks") instanceof List<?> l ? l : List.of();
        String serviceStart = Objects.toString(serviceProfile.getOrDefault("service_start", ""), "");
        String serviceEnd = Objects.toString(serviceProfile.getOrDefault("service_end", ""), "");

        // Collect all deployment locations
        List<String> deploymentLocations = new ArrayList<>();
        for (Object dep : deployments) {
            if (dep instanceof Map<?, ?> depMap) {
                Object locObj = depMap.get("location");
                String loc = locObj != null ? locObj.toString() : "";
                if (!loc.isEmpty()) deploymentLocations.add(loc.toLowerCase());
            } else if (dep instanceof String s) {
                deploymentLocations.add(s.toLowerCase());
            }
        }

        for (Map<String, Object> rule : PRESUMPTIVE_RULES) {
            String category = (String) rule.get("category");
            Map<String, Object> qualifying = (Map<String, Object>) rule.get("qualifying_service");
            List<String> ruleLocations = ((List<String>) qualifying.getOrDefault("locations", List.of()))
                    .stream().map(String::toLowerCase).toList();

            // Check location match
            boolean locationMatch = deploymentLocations.stream().anyMatch(depLoc ->
                    ruleLocations.stream().anyMatch(ruleLoc ->
                            ruleLoc.contains(depLoc) || depLoc.contains(ruleLoc)
                    )
            );

            // Check exposure_risks for burn pit / herbicide keywords
            boolean exposureMatch = false;
            String categoryLower = category.toLowerCase();
            for (Object risk : exposureRisks) {
                String riskLower = risk instanceof String s ? s.toLowerCase() : "";
                if (riskLower.contains("burn pit") && categoryLower.contains("burn pit")) exposureMatch = true;
                if (riskLower.contains("agent orange") && categoryLower.contains("agent orange")) exposureMatch = true;
                if (riskLower.contains("herbicide") && categoryLower.contains("herbicide")) exposureMatch = true;
                if (riskLower.contains("gulf war") && categoryLower.contains("gulf war")) exposureMatch = true;
            }

            if (!locationMatch && !exposureMatch) continue;

            // Check date range
            boolean dateOk = true;
            if (qualifying.containsKey("after")) {
                String after = (String) qualifying.get("after");
                if (!serviceEnd.isEmpty() && serviceEnd.compareTo(after) < 0) dateOk = false;
            }
            if (qualifying.containsKey("between")) {
                List<String> between = (List<String>) qualifying.get("between");
                String start = between.get(0);
                String end = between.get(1);
                if (!serviceStart.isEmpty() && serviceStart.compareTo(end) > 0) dateOk = false;
                if (!serviceEnd.isEmpty() && serviceEnd.compareTo(start) < 0) dateOk = false;
            }

            if (!dateOk) continue;

            List<Map<String, Object>> conditions = (List<Map<String, Object>>) rule.get("conditions");
            for (Map<String, Object> condition : conditions) {
                Map<String, String> match = new LinkedHashMap<>();
                match.put("condition", (String) condition.get("name"));
                match.put("vasrd_code", (String) condition.get("vasrd_code"));
                match.put("basis", category);
                match.put("category", category);
                // Sign/symptom lanes for UNDIAGNOSED illness (38 CFR 3.317) —
                // consumers must not attach these to diagnosed conditions.
                if (Boolean.TRUE.equals(condition.get("undiagnosed"))) {
                    match.put("undiagnosed", "true");
                }
                matches.add(match);
            }
        }

        return matches;
    }
}
