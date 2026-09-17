package com.afterduty.service.synthesis;

import com.afterduty.model.Atom;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deterministic VASRD rating engine (Strategies S17 + S20).
 *
 * S17 — Atomic Rating Rules: conditions with a single fixed rating
 *        (tinnitus, erectile dysfunction).
 * S20 — VASRD Decision Trees: quantitative conditions where rating
 *        can be derived from atom values without AI (asthma, sleep apnea,
 *        knee limitation, migraines).
 *
 * If a condition can be rated deterministically, this engine returns the
 * result immediately. Otherwise it returns Optional.empty() and the caller
 * falls through to the AI-based RatingAgent.
 */
@Service
public class VasrdDecisionEngine {

    private static final Logger log = LoggerFactory.getLogger(VasrdDecisionEngine.class);

    private static final Set<String> CORTICOSTEROIDS = Set.of(
            "prednisone", "methylprednisolone", "dexamethasone"
    );

    private static final Pattern DEGREE_PATTERN = Pattern.compile("(\\d+)\\s*(?:degrees?|\\u00B0)");
    private static final Pattern FEV1_PATTERN = Pattern.compile("(?i)fev[- ]?1[^\\d]*(\\d+(?:\\.\\d+)?)\\s*%?");
    private static final Pattern NUMERIC_NEAR_KEYWORD = Pattern.compile("(\\d+(?:\\.\\d+)?)");

    // ------------------------------------------------------------------ public API

    /**
     * Attempt a deterministic rating for the given VASRD code.
     *
     * @return a map with keys {estimated_rating, rating_rationale, confidence}
     *         or Optional.empty() if the condition cannot be rated by code.
     */
    public Optional<Map<String, Object>> tryDeterministicRating(String vasrdCode, List<Atom> atoms) {
        if (vasrdCode == null || atoms == null || atoms.isEmpty()) {
            return Optional.empty();
        }

        String code = vasrdCode.trim();

        return switch (code) {
            // ---- S17: Atomic rules (fixed ratings) ----
            case "6260" -> rateTinnitus(atoms);
            case "7522" -> rateErectileDysfunction(atoms);

            // ---- S20: Decision trees (quantitative) ----
            case "6602" -> rateAsthma(atoms);
            case "6847" -> rateSleepApnea(atoms);
            case "5260", "5261" -> rateKneeLimitation(atoms);
            case "8100" -> rateMigraines(atoms);

            default -> Optional.empty();
        };
    }

    // ------------------------------------------------------------------ S17: Atomic rules

    private Optional<Map<String, Object>> rateTinnitus(List<Atom> atoms) {
        boolean hasDiagnosis = atoms.stream().anyMatch(a ->
                "diagnosis".equalsIgnoreCase(a.getType()) ||
                lower(a.getValue()).contains("tinnitus"));
        if (hasDiagnosis) {
            log.info("S17 tinnitus: diagnosis atom found, assigning 10%");
            return Optional.of(result(10,
                    "Maximum schedular rating per 38 CFR \u00A7 4.87.",
                    0.99));
        }
        return Optional.empty();
    }

    private Optional<Map<String, Object>> rateErectileDysfunction(List<Atom> atoms) {
        boolean hasDiagnosis = atoms.stream().anyMatch(a ->
                "diagnosis".equalsIgnoreCase(a.getType()) ||
                lower(a.getValue()).contains("erectile dysfunction") ||
                lower(a.getValue()).contains("ed"));
        if (hasDiagnosis) {
            log.info("S17 ED: diagnosis atom found, assigning 0% + SMC-K note");
            return Optional.of(result(0,
                    "Rated 0% per 38 CFR \u00A7 4.115b, DC 7522. "
                    + "Eligible for Special Monthly Compensation (SMC-K).",
                    0.99));
        }
        return Optional.empty();
    }

    // ------------------------------------------------------------------ S20: Decision trees

    private Optional<Map<String, Object>> rateAsthma(List<Atom> atoms) {
        // 1. Corticosteroid courses (unique evidence sources)
        int courses = countCorticosteroidCourses(atoms);
        if (courses >= 3) {
            log.info("S20 asthma: {} corticosteroid courses -> 60%", courses);
            return Optional.of(result(60,
                    String.format("%d corticosteroid courses documented from unique sources. "
                            + "60%% per 38 CFR \u00A7 4.97, DC 6602.", courses),
                    0.95));
        }

        // 2. Daily bronchodilator use
        boolean dailyBronchodilator = atoms.stream().anyMatch(a -> {
            String v = lower(a.getValue());
            return v.contains("daily") &&
                   (v.contains("bronchodilator") || v.contains("albuterol") || v.contains("inhaler"));
        });
        if (dailyBronchodilator) {
            log.info("S20 asthma: daily bronchodilator use -> 30%");
            return Optional.of(result(30,
                    "Daily inhalational bronchodilator therapy documented. "
                    + "30% per 38 CFR \u00A7 4.97, DC 6602.",
                    0.90));
        }

        // 3. FEV-1 value
        for (Atom a : atoms) {
            OptionalDouble fev1 = extractFev1(a.getValue());
            if (fev1.isPresent()) {
                double val = fev1.getAsDouble();
                int rating;
                if (val < 40) rating = 100;
                else if (val <= 55) rating = 60;
                else if (val <= 70) rating = 30;
                else if (val <= 80) rating = 10;
                else {
                    continue; // >80% is essentially normal, fall through
                }
                log.info("S20 asthma: FEV-1 {}% -> {}%", val, rating);
                return Optional.of(result(rating,
                        String.format("FEV-1 of %.0f%% predicted. %d%% per 38 CFR \u00A7 4.97, DC 6602.",
                                val, rating),
                        0.92));
            }
        }

        return Optional.empty();
    }

    private Optional<Map<String, Object>> rateSleepApnea(List<Atom> atoms) {
        boolean hasCpap = false;
        boolean hasRespFailure = false;
        boolean hasHypersomnolence = false;

        for (Atom a : atoms) {
            String v = lower(a.getValue());
            if (v.contains("cpap") || v.contains("continuous positive airway pressure")) {
                hasCpap = true;
            }
            if (v.contains("respiratory failure")) {
                hasRespFailure = true;
            }
            if (v.contains("daytime hypersomnolence") || v.contains("excessive daytime sleepiness")) {
                hasHypersomnolence = true;
            }
        }

        if (hasRespFailure) {
            log.info("S20 sleep apnea: respiratory failure -> 100%");
            return Optional.of(result(100,
                    "Chronic respiratory failure documented. "
                    + "100% per 38 CFR \u00A7 4.97, DC 6847.",
                    0.95));
        }
        if (hasCpap) {
            log.info("S20 sleep apnea: CPAP use -> 50%");
            return Optional.of(result(50,
                    "Use of CPAP machine documented. "
                    + "50% per 38 CFR \u00A7 4.97, DC 6847.",
                    0.95));
        }
        if (hasHypersomnolence) {
            log.info("S20 sleep apnea: daytime hypersomnolence -> 30%");
            return Optional.of(result(30,
                    "Persistent daytime hypersomnolence documented. "
                    + "30% per 38 CFR \u00A7 4.97, DC 6847.",
                    0.90));
        }

        return Optional.empty();
    }

    private Optional<Map<String, Object>> rateKneeLimitation(List<Atom> atoms) {
        for (Atom a : atoms) {
            String v = lower(a.getValue());
            if (!v.contains("flexion")) {
                continue;
            }
            Matcher m = DEGREE_PATTERN.matcher(v);
            if (m.find()) {
                int degrees = Integer.parseInt(m.group(1));
                int rating;
                if (degrees <= 15) rating = 30;
                else if (degrees <= 30) rating = 20;
                else if (degrees <= 45) rating = 10;
                else if (degrees <= 60) rating = 0;
                else {
                    continue; // >60 degrees of flexion = essentially normal ROM
                }
                log.info("S20 knee: flexion limited to {}deg -> {}%", degrees, rating);
                return Optional.of(result(rating,
                        String.format("Flexion limited to %d\u00B0. %d%% per 38 CFR \u00A7 4.71a, DC 5260.",
                                degrees, rating),
                        0.92));
            }
        }
        return Optional.empty();
    }

    private Optional<Map<String, Object>> rateMigraines(List<Atom> atoms) {
        for (Atom a : atoms) {
            String v = lower(a.getValue());
            if (!v.contains("prostrating")) {
                continue;
            }
            if (v.contains("very frequent") || v.contains("several per month")
                    || v.contains("multiple per month") || v.contains("frequent prolonged")) {
                log.info("S20 migraines: very frequent prostrating attacks -> 50%");
                return Optional.of(result(50,
                        "Very frequent, completely prostrating attacks documented. "
                        + "50% per 38 CFR \u00A7 4.124a, DC 8100.",
                        0.90));
            }
            if (v.contains("characteristic prostrating")) {
                log.info("S20 migraines: characteristic prostrating attacks -> 30%");
                return Optional.of(result(30,
                        "Characteristic prostrating attacks documented. "
                        + "30% per 38 CFR \u00A7 4.124a, DC 8100.",
                        0.88));
            }
        }
        return Optional.empty();
    }

    // ------------------------------------------------------------------ helpers

    /**
     * Count unique evidence sources that mention corticosteroid medications.
     * Each unique evidenceId counts as one course; atoms without an evidenceId
     * are counted individually (conservative: assume separate courses).
     */
    int countCorticosteroidCourses(List<Atom> atoms) {
        Set<Long> seenEvidenceIds = new HashSet<>();
        int orphanCount = 0;

        for (Atom a : atoms) {
            String v = lower(a.getValue());
            boolean isSteroid = CORTICOSTEROIDS.stream().anyMatch(v::contains);
            if (!isSteroid) {
                continue;
            }
            if (a.getEvidenceId() != null) {
                seenEvidenceIds.add(a.getEvidenceId());
            } else {
                orphanCount++;
            }
        }
        return seenEvidenceIds.size() + orphanCount;
    }

    /**
     * Extract a numeric value appearing near a keyword in the text.
     * Returns OptionalDouble.empty() if not found.
     */
    OptionalDouble extractNumericValue(String text, String keyword) {
        if (text == null || keyword == null) {
            return OptionalDouble.empty();
        }
        String lower = lower(text);
        int idx = lower.indexOf(lower(keyword));
        if (idx < 0) {
            return OptionalDouble.empty();
        }
        // Search within 40 chars after the keyword
        int start = idx + keyword.length();
        int end = Math.min(lower.length(), start + 40);
        String window = text.substring(start, end);
        Matcher m = NUMERIC_NEAR_KEYWORD.matcher(window);
        if (m.find()) {
            try {
                return OptionalDouble.of(Double.parseDouble(m.group(1)));
            } catch (NumberFormatException e) {
                return OptionalDouble.empty();
            }
        }
        return OptionalDouble.empty();
    }

    // ------------------------------------------------------------------ private utilities

    private OptionalDouble extractFev1(String text) {
        if (text == null) return OptionalDouble.empty();
        Matcher m = FEV1_PATTERN.matcher(text);
        if (m.find()) {
            try {
                return OptionalDouble.of(Double.parseDouble(m.group(1)));
            } catch (NumberFormatException e) {
                return OptionalDouble.empty();
            }
        }
        return OptionalDouble.empty();
    }

    private static Map<String, Object> result(int estimatedRating, String rationale, double confidence) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("estimated_rating", estimatedRating);
        map.put("rating_rationale", rationale);
        map.put("confidence", confidence);
        return map;
    }

    private static String lower(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT);
    }
}
