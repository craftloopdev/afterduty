package com.afterduty.service.extraction;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.afterduty.model.Atom;
import com.afterduty.model.MedicalEvent;
import com.afterduty.repository.AtomRepository;
import com.afterduty.repository.MedicalEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Runs cross-event aggregation after all events are extracted.
 * Detects medication courses, score trends, and medication changes.
 */
@Service
public class CrossEventAggregator {

    private static final Logger log = LoggerFactory.getLogger(CrossEventAggregator.class);

    private final MedicalEventRepository medicalEventRepository;
    private final AtomRepository atomRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final Set<String> TRACKED_SCORES = Set.of(
            "phq-9", "phq9", "pcl-5", "pcl5", "gaf", "columbia", "audit-c", "bdi", "bai"
    );

    public CrossEventAggregator(MedicalEventRepository medicalEventRepository,
                                 AtomRepository atomRepository) {
        this.medicalEventRepository = medicalEventRepository;
        this.atomRepository = atomRepository;
    }

    /**
     * Run cross-event aggregation for a claim.
     * Creates summary atoms from patterns found across events.
     *
     * @return list of aggregated atoms that were created
     */
    public List<Atom> aggregate(Long claimId) {
        log.info("Running cross-event aggregation for claim {}", claimId);

        List<MedicalEvent> events = medicalEventRepository.findByClaimId(claimId);
        if (events.isEmpty()) {
            log.info("No medical events found for claim {}", claimId);
            return List.of();
        }

        // Mission 5a: aggregate over LIVE atoms only. A re-extracted document's
        // prior atoms are superseded; aggregating them alongside the fresh ones
        // would double-count medication courses, score series, etc.
        List<Atom> allAtoms = atomRepository.findByClaimIdAndSupersededByIsNull(claimId);
        List<Atom> aggregatedAtoms = new ArrayList<>();

        // 1. Medication course summaries
        aggregatedAtoms.addAll(buildMedicationCourseSummaries(events, allAtoms, claimId));

        // 2. Score trend tracking
        aggregatedAtoms.addAll(buildScoreTrends(allAtoms, claimId));

        // 3. Medication change detection
        aggregatedAtoms.addAll(detectMedicationChanges(allAtoms, claimId));

        // Save all aggregated atoms
        if (!aggregatedAtoms.isEmpty()) {
            atomRepository.saveAll(aggregatedAtoms);
            log.info("Created {} aggregated atoms for claim {}", aggregatedAtoms.size(), claimId);
        }

        return aggregatedAtoms;
    }

    /**
     * Count medication courses by grouping atoms by drug name across events.
     */
    private List<Atom> buildMedicationCourseSummaries(List<MedicalEvent> events,
                                                       List<Atom> allAtoms, Long claimId) {
        List<Atom> results = new ArrayList<>();

        // Group medication atoms by drug name (lowercased, normalized)
        Map<String, List<Atom>> medsByDrug = new LinkedHashMap<>();

        for (Atom atom : allAtoms) {
            if ("medication".equals(atom.getType()) || "prescription".equals(atom.getType())) {
                String drugName = extractDrugName(atom.getValue());
                if (drugName != null) {
                    medsByDrug.computeIfAbsent(drugName, k -> new ArrayList<>()).add(atom);
                }
            }
        }

        // Also scan event medications_mentioned fields
        for (MedicalEvent event : events) {
            if (event.getMedicationsMentioned() != null) {
                try {
                    List<String> meds = objectMapper.readValue(
                            event.getMedicationsMentioned(), new TypeReference<List<String>>() {});
                    for (String med : meds) {
                        String normalized = med.toLowerCase().trim();
                        // Just track the event dates for these medications
                        medsByDrug.computeIfAbsent(normalized, k -> new ArrayList<>());
                    }
                } catch (Exception e) {
                    // ignore parse errors
                }
            }
        }

        for (Map.Entry<String, List<Atom>> entry : medsByDrug.entrySet()) {
            String drug = entry.getKey();
            List<Atom> atoms = entry.getValue();

            if (atoms.size() < 2) continue; // Only summarize multi-course medications

            // Collect unique dates
            List<String> dates = atoms.stream()
                    .map(Atom::getTimestamp)
                    .filter(Objects::nonNull)
                    .distinct()
                    .sorted()
                    .collect(Collectors.toList());

            String dateList = dates.isEmpty()
                    ? atoms.size() + " occurrences (dates unknown)"
                    : String.join(", ", dates);

            String value = drug + ": " + atoms.size() + " courses (" + dateList + ")";

            Atom summary = Atom.builder()
                    .claimId(claimId)
                    .type("medication_course_summary")
                    .value(value)
                    .source("cross-event aggregation")
                    .createdBy("ai:aggregator")
                    .confidence(0.9)
                    .build();

            results.add(summary);
        }

        log.info("Built {} medication course summaries for claim {}", results.size(), claimId);
        return results;
    }

    /**
     * Track mental health score trends across events.
     */
    private List<Atom> buildScoreTrends(List<Atom> allAtoms, Long claimId) {
        List<Atom> results = new ArrayList<>();

        // Group mental health scores by assessment instrument
        Map<String, List<Atom>> scoresByInstrument = new LinkedHashMap<>();

        for (Atom atom : allAtoms) {
            if ("mental_health_score".equals(atom.getType()) || "test_result".equals(atom.getType())) {
                String instrument = extractInstrumentName(atom.getValue());
                if (instrument != null) {
                    scoresByInstrument.computeIfAbsent(instrument, k -> new ArrayList<>()).add(atom);
                }
            }
        }

        for (Map.Entry<String, List<Atom>> entry : scoresByInstrument.entrySet()) {
            String instrument = entry.getKey();
            List<Atom> scores = entry.getValue();

            if (scores.size() < 2) continue; // Need at least 2 data points for a trend

            // Sort by date
            scores.sort(Comparator.comparing(
                    a -> a.getTimestamp() != null ? a.getTimestamp() : "",
                    String::compareTo));

            // Build trend string
            List<String> dataPoints = new ArrayList<>();
            for (Atom score : scores) {
                String numericValue = extractNumericScore(score.getValue());
                String date = score.getTimestamp() != null ? score.getTimestamp() : "unknown";
                if (numericValue != null) {
                    dataPoints.add(numericValue + " (" + date + ")");
                }
            }

            if (dataPoints.size() < 2) continue;

            // Determine trend direction
            String trend = determineTrend(scores);

            String value = instrument.toUpperCase() + ": " + String.join(" -> ", dataPoints) + " — " + trend;

            Atom trendAtom = Atom.builder()
                    .claimId(claimId)
                    .type("score_trend")
                    .value(value)
                    .source("cross-event aggregation")
                    .createdBy("ai:aggregator")
                    .confidence(0.85)
                    .build();

            results.add(trendAtom);
        }

        log.info("Built {} score trend atoms for claim {}", results.size(), claimId);
        return results;
    }

    /**
     * Detect medication changes: dose increases, new drugs, discontinuations.
     */
    private List<Atom> detectMedicationChanges(List<Atom> allAtoms, Long claimId) {
        List<Atom> results = new ArrayList<>();

        // Group by drug name and look for dose changes
        Map<String, List<Atom>> medsByDrug = new LinkedHashMap<>();
        for (Atom atom : allAtoms) {
            if ("medication".equals(atom.getType()) || "prescription".equals(atom.getType())) {
                String drugName = extractDrugName(atom.getValue());
                if (drugName != null) {
                    medsByDrug.computeIfAbsent(drugName, k -> new ArrayList<>()).add(atom);
                }
            }
        }

        for (Map.Entry<String, List<Atom>> entry : medsByDrug.entrySet()) {
            String drug = entry.getKey();
            List<Atom> atoms = entry.getValue();

            if (atoms.size() < 2) continue;

            // Sort by date
            atoms.sort(Comparator.comparing(
                    a -> a.getTimestamp() != null ? a.getTimestamp() : "",
                    String::compareTo));

            // Look for dose changes in sequential atoms
            for (int i = 0; i < atoms.size() - 1; i++) {
                String dose1 = extractDose(atoms.get(i).getValue());
                String dose2 = extractDose(atoms.get(i + 1).getValue());

                if (dose1 != null && dose2 != null && !dose1.equals(dose2)) {
                    String date1 = atoms.get(i).getTimestamp() != null ? atoms.get(i).getTimestamp() : "unknown";
                    String date2 = atoms.get(i + 1).getTimestamp() != null ? atoms.get(i + 1).getTimestamp() : "unknown";

                    String changeType = compareDoses(dose1, dose2);
                    String value = drug + " " + changeType + " from " + dose1 + " to " + dose2 +
                            " between " + date1 + " and " + date2;

                    Atom changeAtom = Atom.builder()
                            .claimId(claimId)
                            .type("medication_change")
                            .value(value)
                            .source("cross-event aggregation")
                            .createdBy("ai:aggregator")
                            .confidence(0.85)
                            .build();

                    results.add(changeAtom);
                }
            }

            // Check for discontinued medications
            Atom lastAtom = atoms.get(atoms.size() - 1);
            String lastValue = lastAtom.getValue().toLowerCase();
            if (lastValue.contains("discontinued") || lastValue.contains("stopped") || lastValue.contains("dc'd")) {
                String date = lastAtom.getTimestamp() != null ? lastAtom.getTimestamp() : "unknown";
                String value = drug + " discontinued as of " + date;

                Atom dcAtom = Atom.builder()
                        .claimId(claimId)
                        .type("medication_change")
                        .value(value)
                        .source("cross-event aggregation")
                        .createdBy("ai:aggregator")
                        .confidence(0.8)
                        .build();

                results.add(dcAtom);
            }
        }

        log.info("Detected {} medication changes for claim {}", results.size(), claimId);
        return results;
    }

    // --- Helper methods ---

    private String extractDrugName(String value) {
        if (value == null || value.isBlank()) return null;
        // Take the first word or first few words before a dose pattern
        String lower = value.toLowerCase().trim();
        // Remove common prefixes
        lower = lower.replaceFirst("^(medication|prescription|rx):\\s*", "");
        // Extract up to the first number or comma
        int idx = -1;
        for (int i = 0; i < lower.length(); i++) {
            char c = lower.charAt(i);
            if (Character.isDigit(c) || c == ',' || c == ';' || c == ':') {
                idx = i;
                break;
            }
        }
        String name = idx > 0 ? lower.substring(0, idx).trim() : lower.split("\\s+")[0];
        return name.isBlank() ? null : name;
    }

    private String extractInstrumentName(String value) {
        if (value == null) return null;
        String lower = value.toLowerCase();
        for (String score : TRACKED_SCORES) {
            if (lower.contains(score)) {
                return score.replace("-", "");
            }
        }
        return null;
    }

    private String extractNumericScore(String value) {
        if (value == null) return null;
        // Find first number in the value
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\b(\\d+\\.?\\d*)\\b").matcher(value);
        if (m.find()) {
            return m.group(1);
        }
        return null;
    }

    private String determineTrend(List<Atom> scores) {
        List<Double> values = new ArrayList<>();
        for (Atom score : scores) {
            String num = extractNumericScore(score.getValue());
            if (num != null) {
                try {
                    values.add(Double.parseDouble(num));
                } catch (NumberFormatException e) {
                    // skip
                }
            }
        }
        if (values.size() < 2) return "INSUFFICIENT DATA";

        double first = values.get(0);
        double last = values.get(values.size() - 1);

        if (last > first * 1.1) return "WORSENING";
        if (last < first * 0.9) return "IMPROVING";
        return "STABLE";
    }

    private String extractDose(String value) {
        if (value == null) return null;
        // Match patterns like "50mg", "100 mg", "0.5mg"
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(
                "(\\d+\\.?\\d*)\\s*(mg|mcg|ml|units?|iu)", java.util.regex.Pattern.CASE_INSENSITIVE
        ).matcher(value);
        if (m.find()) {
            return m.group(1) + m.group(2).toLowerCase();
        }
        return null;
    }

    private String compareDoses(String dose1, String dose2) {
        try {
            double d1 = Double.parseDouble(dose1.replaceAll("[^\\d.]", ""));
            double d2 = Double.parseDouble(dose2.replaceAll("[^\\d.]", ""));
            if (d2 > d1) return "increased";
            if (d2 < d1) return "decreased";
            return "changed";
        } catch (NumberFormatException e) {
            return "changed";
        }
    }
}
