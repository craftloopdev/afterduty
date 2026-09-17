package com.afterduty.service.synthesis;

import com.afterduty.model.Atom;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * S1 + S4: Pre-computes evidence summaries and criteria mappings BEFORE AI sees the data.
 * This deterministic layer ensures the AI gets structured, counted, and mapped evidence
 * rather than raw atom dumps — dramatically improving rating accuracy.
 */
@Service
public class EvidencePreComputer {

    private static final Logger log = LoggerFactory.getLogger(EvidencePreComputer.class);

    // VASRD code range helpers
    private static boolean isRespiratory(int code) { return code >= 6600 && code <= 6899; }
    private static boolean isMentalHealth(int code) { return (code >= 9200 && code <= 9440) || code == 8045; }
    private static boolean isMusculoskeletal(int code) { return code >= 5000 && code <= 5299; }
    private static boolean isDigestive(int code) { return code >= 7300 && code <= 7399; }

    // Common medication keywords for respiratory
    private static final List<String> CORTICOSTEROIDS = List.of(
            "prednisone", "prednisolone", "methylprednisolone", "medrol", "dexamethasone",
            "budesonide", "fluticasone", "beclomethasone", "mometasone", "triamcinolone"
    );

    private static final List<String> INHALER_KEYWORDS = List.of(
            "inhaler", "albuterol", "proventil", "ventolin", "proair",
            "symbicort", "advair", "breo", "dulera", "spiriva", "combivent"
    );

    private static final List<String> EQUIPMENT_KEYWORDS = List.of(
            "cpap", "bipap", "inhaler", "nebulizer", "wheelchair", "brace",
            "hearing aid", "prosthetic", "cane", "walker", "crutch", "oxygen"
    );

    // ─────────────────────────────────────────────
    // S1: Main entry point — Pre-Computed Evidence Brief
    // ─────────────────────────────────────────────

    /**
     * Produces a structured evidence brief that gets injected into the AI prompt.
     */
    public String computeEvidenceBrief(String conditionName, String vasrdCode, List<Atom> atoms) {
        if (atoms == null || atoms.isEmpty()) {
            return "=== PRE-COMPUTED EVIDENCE BRIEF ===\nNo atoms available for " + conditionName + "\n";
        }

        int code = parseVasrdCode(vasrdCode);
        StringBuilder sb = new StringBuilder();
        sb.append("=== PRE-COMPUTED EVIDENCE BRIEF ===\n");
        sb.append("Condition: ").append(conditionName).append(" (VASRD ").append(vasrdCode).append(")\n");
        sb.append("Total atoms: ").append(atoms.size()).append("\n\n");

        // Universal summaries (all conditions)
        sb.append(buildMedicationSummary(atoms));
        sb.append(buildEquipmentSummary(atoms));
        sb.append(buildDiagnosisSummary(atoms));

        // Condition-specific summaries
        if (isRespiratory(code)) {
            sb.append("\n--- RESPIRATORY-SPECIFIC ---\n");
            sb.append(buildCorticosteroidCourses(atoms));
            sb.append(buildFev1Values(atoms));
            sb.append(buildInhalerUsage(atoms));
        }

        if (isMentalHealth(code)) {
            sb.append("\n--- MENTAL HEALTH-SPECIFIC ---\n");
            sb.append(buildPhq9Scores(atoms));
            sb.append(buildPcl5Scores(atoms));
            sb.append(buildGafScores(atoms));
            sb.append(buildHospitalizations(atoms));
            sb.append(buildEmploymentStatus(atoms));
        }

        if (isMusculoskeletal(code)) {
            sb.append("\n--- MUSCULOSKELETAL-SPECIFIC ---\n");
            sb.append(buildRangeOfMotion(atoms));
            sb.append(buildFlareUps(atoms));
            sb.append(buildAssistiveDevices(atoms));
        }

        if (isDigestive(code)) {
            sb.append("\n--- GI/DIGESTIVE-SPECIFIC ---\n");
            sb.append(buildWeightChanges(atoms));
            sb.append(buildGiEvents(atoms));
            sb.append(buildAnemiaLabs(atoms));
        }

        // S4: Evidence-to-Criteria mapping
        String criteriaMapping = buildCriteriaMapping(vasrdCode, atoms);
        if (!criteriaMapping.isEmpty()) {
            sb.append("\n").append(criteriaMapping);
        }

        log.info("Pre-computed evidence brief for {} ({}): {} chars from {} atoms",
                conditionName, vasrdCode, sb.length(), atoms.size());
        return sb.toString();
    }

    // ─────────────────────────────────────────────
    // Universal summaries
    // ─────────────────────────────────────────────

    private String buildMedicationSummary(List<Atom> atoms) {
        List<Atom> medAtoms = filterByTypeOrValue(atoms, "medication", null);
        if (medAtoms.isEmpty()) return "Medications: NONE FOUND\n";

        // Group by drug name (first word of value, lowercased)
        Map<String, List<Atom>> byDrug = new LinkedHashMap<>();
        for (Atom a : medAtoms) {
            String drug = extractDrugName(a.getValue());
            byDrug.computeIfAbsent(drug, k -> new ArrayList<>()).add(a);
        }

        StringBuilder sb = new StringBuilder("Medications:\n");
        for (Map.Entry<String, List<Atom>> entry : byDrug.entrySet()) {
            List<Atom> drugAtoms = entry.getValue();
            long uniqueSources = drugAtoms.stream()
                    .map(Atom::getEvidenceId)
                    .filter(Objects::nonNull)
                    .distinct()
                    .count();
            Set<String> dosages = drugAtoms.stream()
                    .map(a -> extractDosage(a.getValue()))
                    .filter(d -> !d.isEmpty())
                    .collect(Collectors.toCollection(LinkedHashSet::new));

            sb.append("  - ").append(entry.getKey());
            sb.append(" | sources: ").append(uniqueSources);
            if (!dosages.isEmpty()) {
                sb.append(" | dosages: ").append(String.join(", ", dosages));
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    private String buildEquipmentSummary(List<Atom> atoms) {
        List<Atom> equipAtoms = new ArrayList<>();
        for (Atom a : atoms) {
            String val = lower(a.getValue());
            String type = lower(a.getType());
            if ("equipment".equals(type) || "device".equals(type)) {
                equipAtoms.add(a);
            } else {
                for (String kw : EQUIPMENT_KEYWORDS) {
                    if (val.contains(kw)) {
                        equipAtoms.add(a);
                        break;
                    }
                }
            }
        }
        if (equipAtoms.isEmpty()) return "Prescribed equipment: NONE FOUND\n";

        StringBuilder sb = new StringBuilder("Prescribed equipment:\n");
        Set<String> seen = new LinkedHashSet<>();
        for (Atom a : equipAtoms) {
            String desc = a.getValue().trim();
            String key = lower(desc);
            if (seen.add(key)) {
                sb.append("  - ").append(desc);
                if (a.getTimestamp() != null) sb.append(" (").append(a.getTimestamp()).append(")");
                if (a.getEvidenceId() != null) sb.append(" [evidence:").append(a.getEvidenceId()).append("]");
                sb.append("\n");
            }
        }
        return sb.toString();
    }

    private String buildDiagnosisSummary(List<Atom> atoms) {
        List<Atom> diagAtoms = filterByTypeOrValue(atoms, "diagnosis", null);
        if (diagAtoms.isEmpty()) return "Diagnoses: NONE FOUND\n";

        StringBuilder sb = new StringBuilder("Diagnoses:\n");
        for (Atom a : diagAtoms) {
            sb.append("  - ").append(a.getValue());
            if (a.getTimestamp() != null) sb.append(" | date: ").append(a.getTimestamp());
            if (a.getSource() != null) sb.append(" | provider: ").append(a.getSource());
            sb.append("\n");
        }
        return sb.toString();
    }

    // ─────────────────────────────────────────────
    // Respiratory (6600-6899)
    // ─────────────────────────────────────────────

    private String buildCorticosteroidCourses(List<Atom> atoms) {
        List<Atom> steroidAtoms = new ArrayList<>();
        for (Atom a : atoms) {
            String val = lower(a.getValue());
            for (String steroid : CORTICOSTEROIDS) {
                if (val.contains(steroid)) {
                    steroidAtoms.add(a);
                    break;
                }
            }
        }
        if (steroidAtoms.isEmpty()) return "Corticosteroid courses: 0\n";

        long uniqueSources = steroidAtoms.stream()
                .map(Atom::getEvidenceId)
                .filter(Objects::nonNull)
                .distinct()
                .count();

        StringBuilder sb = new StringBuilder();
        sb.append("Corticosteroid courses: ").append(steroidAtoms.size())
                .append(" mentions across ").append(uniqueSources).append(" unique evidence sources\n");
        for (Atom a : steroidAtoms) {
            sb.append("  - ").append(a.getValue());
            if (a.getEvidenceId() != null) sb.append(" [evidence:").append(a.getEvidenceId()).append("]");
            if (a.getTimestamp() != null) sb.append(" (").append(a.getTimestamp()).append(")");
            sb.append("\n");
        }
        return sb.toString();
    }

    private String buildFev1Values(List<Atom> atoms) {
        Pattern fev1Pattern = Pattern.compile("(?i)fev[- ]?1[^\\d]*(\\d+\\.?\\d*)\\s*%?");
        List<String> findings = new ArrayList<>();
        for (Atom a : atoms) {
            Matcher m = fev1Pattern.matcher(a.getValue());
            if (m.find()) {
                String entry = "FEV-1: " + m.group(1) + "%";
                if (a.getTimestamp() != null) entry += " (" + a.getTimestamp() + ")";
                findings.add(entry);
            }
        }
        if (findings.isEmpty()) return "FEV-1 values: NONE FOUND\n";
        StringBuilder sb = new StringBuilder("FEV-1 values:\n");
        findings.forEach(f -> sb.append("  - ").append(f).append("\n"));
        return sb.toString();
    }

    private String buildInhalerUsage(List<Atom> atoms) {
        List<Atom> inhalerAtoms = new ArrayList<>();
        for (Atom a : atoms) {
            String val = lower(a.getValue());
            for (String kw : INHALER_KEYWORDS) {
                if (val.contains(kw)) {
                    inhalerAtoms.add(a);
                    break;
                }
            }
        }
        if (inhalerAtoms.isEmpty()) return "Inhaler usage: NONE FOUND\n";

        boolean daily = false;
        boolean asNeeded = false;
        for (Atom a : inhalerAtoms) {
            String val = lower(a.getValue());
            if (val.contains("daily") || val.contains("every day") || val.contains("twice daily")
                    || val.contains("bid") || val.contains("maintenance")) {
                daily = true;
            }
            if (val.contains("as needed") || val.contains("prn") || val.contains("rescue")) {
                asNeeded = true;
            }
        }

        StringBuilder sb = new StringBuilder("Inhaler usage: ");
        List<String> modes = new ArrayList<>();
        if (daily) modes.add("DAILY");
        if (asNeeded) modes.add("AS-NEEDED/PRN");
        if (modes.isEmpty()) modes.add("frequency unclear");
        sb.append(String.join(" + ", modes));
        sb.append(" (").append(inhalerAtoms.size()).append(" mentions)\n");
        return sb.toString();
    }

    // ─────────────────────────────────────────────
    // Mental Health (9200-9440 + 8045 TBI)
    // ─────────────────────────────────────────────

    private String buildPhq9Scores(List<Atom> atoms) {
        return buildScoreSummary(atoms, "PHQ-9", Pattern.compile("(?i)phq[- ]?9[^\\d]*(\\d+)"));
    }

    private String buildPcl5Scores(List<Atom> atoms) {
        return buildScoreSummary(atoms, "PCL-5", Pattern.compile("(?i)pcl[- ]?5[^\\d]*(\\d+)"));
    }

    private String buildGafScores(List<Atom> atoms) {
        return buildScoreSummary(atoms, "GAF", Pattern.compile("(?i)gaf[^\\d]*(\\d+)"));
    }

    private String buildScoreSummary(List<Atom> atoms, String scoreName, Pattern pattern) {
        List<double[]> scoreEntries = new ArrayList<>(); // [score, index]
        List<String> details = new ArrayList<>();

        for (int i = 0; i < atoms.size(); i++) {
            Atom a = atoms.get(i);
            Matcher m = pattern.matcher(a.getValue());
            if (m.find()) {
                double score = Double.parseDouble(m.group(1));
                scoreEntries.add(new double[]{score, i});
                String entry = scoreName + ": " + (int) score;
                if (a.getTimestamp() != null) entry += " (" + a.getTimestamp() + ")";
                details.add(entry);
            }
        }

        if (details.isEmpty()) return scoreName + " scores: NONE FOUND\n";

        String trend = computeTrend(scoreEntries);
        StringBuilder sb = new StringBuilder();
        sb.append(scoreName).append(" scores (trend: ").append(trend).append("):\n");
        details.forEach(d -> sb.append("  - ").append(d).append("\n"));
        return sb.toString();
    }

    private String computeTrend(List<double[]> entries) {
        if (entries.size() < 2) return "insufficient data";
        double first = entries.get(0)[0];
        double last = entries.get(entries.size() - 1)[0];
        double diff = last - first;
        if (Math.abs(diff) < 2) return "STABLE";
        return diff > 0 ? "WORSENING" : "IMPROVING";
    }

    private String buildHospitalizations(List<Atom> atoms) {
        List<String> hospKeywords = List.of("hospitalization", "hospitalized", "inpatient",
                "psychiatric admission", "psych ward", "committed", "involuntary hold", "5150", "baker act");
        List<Atom> hospAtoms = new ArrayList<>();
        for (Atom a : atoms) {
            String val = lower(a.getValue());
            for (String kw : hospKeywords) {
                if (val.contains(kw)) {
                    hospAtoms.add(a);
                    break;
                }
            }
        }
        if (hospAtoms.isEmpty()) return "Psychiatric hospitalizations: 0\n";
        StringBuilder sb = new StringBuilder();
        sb.append("Psychiatric hospitalizations: ").append(hospAtoms.size()).append("\n");
        for (Atom a : hospAtoms) {
            sb.append("  - ").append(a.getValue());
            if (a.getTimestamp() != null) sb.append(" (").append(a.getTimestamp()).append(")");
            sb.append("\n");
        }
        return sb.toString();
    }

    private String buildEmploymentStatus(List<Atom> atoms) {
        List<String> empKeywords = List.of("employment", "employed", "unemployed", "fired", "terminated",
                "quit", "unable to work", "occupational", "work history", "job", "employer",
                "tdiu", "individual unemployability", "marginal employment");
        List<Atom> empAtoms = new ArrayList<>();
        for (Atom a : atoms) {
            String val = lower(a.getValue());
            String type = lower(a.getType());
            if ("employment".equals(type)) {
                empAtoms.add(a);
            } else {
                for (String kw : empKeywords) {
                    if (val.contains(kw)) {
                        empAtoms.add(a);
                        break;
                    }
                }
            }
        }
        if (empAtoms.isEmpty()) return "Employment status: NO DATA\n";
        StringBuilder sb = new StringBuilder("Employment-related evidence:\n");
        for (Atom a : empAtoms) {
            sb.append("  - ").append(a.getValue());
            if (a.getTimestamp() != null) sb.append(" (").append(a.getTimestamp()).append(")");
            sb.append("\n");
        }
        return sb.toString();
    }

    // ─────────────────────────────────────────────
    // Musculoskeletal (5000-5299)
    // ─────────────────────────────────────────────

    private String buildRangeOfMotion(List<Atom> atoms) {
        Pattern romPattern = Pattern.compile("(?i)(flexion|extension|abduction|adduction|rotation)[^\\d]*(\\d+)\\s*(?:degrees|deg|°)");
        List<String> findings = new ArrayList<>();
        for (Atom a : atoms) {
            Matcher m = romPattern.matcher(a.getValue());
            while (m.find()) {
                String entry = m.group(1) + ": " + m.group(2) + "°";
                if (a.getTimestamp() != null) entry += " (" + a.getTimestamp() + ")";
                findings.add(entry);
            }
            // Also catch atoms that are explicitly ROM type
            if ("range_of_motion".equals(lower(a.getType())) || "rom".equals(lower(a.getType()))) {
                String entry = a.getValue();
                if (a.getTimestamp() != null) entry += " (" + a.getTimestamp() + ")";
                if (!findings.contains(entry)) findings.add(entry);
            }
        }
        if (findings.isEmpty()) return "Range of motion: NO DATA\n";
        StringBuilder sb = new StringBuilder("Range of motion:\n");
        findings.forEach(f -> sb.append("  - ").append(f).append("\n"));
        return sb.toString();
    }

    private String buildFlareUps(List<Atom> atoms) {
        List<String> flareKeywords = List.of("flare-up", "flare up", "flareup", "exacerbation",
                "acute episode", "worsening pain", "increased pain", "painful motion");
        int count = 0;
        for (Atom a : atoms) {
            String val = lower(a.getValue());
            for (String kw : flareKeywords) {
                if (val.contains(kw)) {
                    count++;
                    break;
                }
            }
        }
        return "Flare-ups/exacerbations: " + count + " mentions\n";
    }

    private String buildAssistiveDevices(List<Atom> atoms) {
        List<String> deviceKeywords = List.of("cane", "brace", "wheelchair", "walker",
                "crutch", "orthotic", "knee brace", "ankle brace", "wrist brace", "back brace",
                "splint", "walking aid", "mobility aid");
        Set<String> found = new LinkedHashSet<>();
        for (Atom a : atoms) {
            String val = lower(a.getValue());
            for (String kw : deviceKeywords) {
                if (val.contains(kw)) {
                    found.add(kw);
                }
            }
        }
        if (found.isEmpty()) return "Assistive devices: NONE FOUND\n";
        return "Assistive devices: " + String.join(", ", found) + "\n";
    }

    // ─────────────────────────────────────────────
    // GI/Digestive (7300-7399)
    // ─────────────────────────────────────────────

    private String buildWeightChanges(List<Atom> atoms) {
        Pattern weightPattern = Pattern.compile("(?i)(\\d+\\.?\\d*)\\s*(?:lbs?|pounds?|kg)");
        List<String> findings = new ArrayList<>();
        for (Atom a : atoms) {
            String val = lower(a.getValue());
            if (val.contains("weight") || val.contains("bmi") || "weight".equals(lower(a.getType()))) {
                Matcher m = weightPattern.matcher(a.getValue());
                if (m.find()) {
                    String entry = m.group(0);
                    if (a.getTimestamp() != null) entry += " (" + a.getTimestamp() + ")";
                    findings.add(entry);
                }
            }
        }
        if (findings.isEmpty()) return "Weight changes: NO DATA\n";
        StringBuilder sb = new StringBuilder("Weight values:\n");
        findings.forEach(f -> sb.append("  - ").append(f).append("\n"));
        return sb.toString();
    }

    private String buildGiEvents(List<Atom> atoms) {
        Map<String, Integer> events = new LinkedHashMap<>();
        events.put("hematemesis", 0);
        events.put("melena", 0);
        events.put("vomiting", 0);
        events.put("diarrhea", 0);
        events.put("nausea", 0);
        events.put("abdominal pain", 0);

        for (Atom a : atoms) {
            String val = lower(a.getValue());
            for (String key : events.keySet()) {
                if (val.contains(key)) {
                    events.merge(key, 1, Integer::sum);
                }
            }
        }

        boolean any = events.values().stream().anyMatch(v -> v > 0);
        if (!any) return "GI events: NONE FOUND\n";

        StringBuilder sb = new StringBuilder("GI events:\n");
        events.forEach((event, count) -> {
            if (count > 0) {
                sb.append("  - ").append(event).append(": ").append(count).append(" mentions\n");
            }
        });
        return sb.toString();
    }

    private String buildAnemiaLabs(List<Atom> atoms) {
        Pattern hgbPattern = Pattern.compile("(?i)(?:hemoglobin|hgb|hb)[^\\d]*(\\d+\\.?\\d*)");
        Pattern hctPattern = Pattern.compile("(?i)(?:hematocrit|hct)[^\\d]*(\\d+\\.?\\d*)\\s*%?");
        List<String> findings = new ArrayList<>();

        for (Atom a : atoms) {
            Matcher m = hgbPattern.matcher(a.getValue());
            if (m.find()) {
                String entry = "Hemoglobin: " + m.group(1) + " g/dL";
                if (a.getTimestamp() != null) entry += " (" + a.getTimestamp() + ")";
                findings.add(entry);
            }
            m = hctPattern.matcher(a.getValue());
            if (m.find()) {
                String entry = "Hematocrit: " + m.group(1) + "%";
                if (a.getTimestamp() != null) entry += " (" + a.getTimestamp() + ")";
                findings.add(entry);
            }
        }
        if (findings.isEmpty()) return "Anemia labs (Hgb/Hct): NO DATA\n";
        StringBuilder sb = new StringBuilder("Anemia labs:\n");
        findings.forEach(f -> sb.append("  - ").append(f).append("\n"));
        return sb.toString();
    }

    // ─────────────────────────────────────────────
    // S4: Evidence-to-Criteria Mapping
    // ─────────────────────────────────────────────

    /**
     * Maps each atom to known VASRD threshold criteria, producing a formatted table
     * showing which evidence supports which rating level.
     */
    public String buildCriteriaMapping(String vasrdCode, List<Atom> atoms) {
        int code = parseVasrdCode(vasrdCode);
        List<CriteriaMatch> matches = new ArrayList<>();

        for (Atom a : atoms) {
            List<CriteriaMatch> atomMatches = matchAtomToCriteria(code, a);
            matches.addAll(atomMatches);
        }

        if (matches.isEmpty()) return "";

        StringBuilder sb = new StringBuilder("=== EVIDENCE-TO-CRITERIA MAPPING (S4) ===\n");
        sb.append(String.format("| %-50s | %-35s | %-15s |\n", "Evidence", "VASRD Criteria", "Supports Rating"));
        sb.append("|").append("-".repeat(52)).append("|").append("-".repeat(37)).append("|").append("-".repeat(17)).append("|\n");

        for (CriteriaMatch cm : matches) {
            String evidenceCol = truncate(cm.evidence, 50);
            String criteriaCol = truncate(cm.criteria, 35);
            String ratingCol = cm.rating;
            sb.append(String.format("| %-50s | %-35s | %-15s |\n", evidenceCol, criteriaCol, ratingCol));
        }

        return sb.toString();
    }

    private List<CriteriaMatch> matchAtomToCriteria(int code, Atom atom) {
        List<CriteriaMatch> matches = new ArrayList<>();
        String val = lower(atom.getValue());
        String src = atom.getEvidenceId() != null ? "evidence:" + atom.getEvidenceId() : atom.getSource();

        // ─── Respiratory criteria (6600-6899) ───
        if (isRespiratory(code)) {
            // Corticosteroid courses
            for (String steroid : CORTICOSTEROIDS) {
                if (val.contains(steroid)) {
                    matches.add(new CriteriaMatch(
                            steroid + " from " + src,
                            code + " corticosteroid course",
                            "60%"
                    ));
                    break;
                }
            }
            // CPAP
            if (val.contains("cpap") || val.contains("bipap")) {
                matches.add(new CriteriaMatch(
                        "CPAP/BiPAP prescribed",
                        "6847 requires CPAP/BiPAP",
                        "50%"
                ));
            }
            // FEV-1
            Pattern fev1 = Pattern.compile("(?i)fev[- ]?1[^\\d]*(\\d+\\.?\\d*)\\s*%?");
            Matcher m = fev1.matcher(atom.getValue());
            if (m.find()) {
                double pct = Double.parseDouble(m.group(1));
                String rating;
                if (pct < 40) rating = "100%";
                else if (pct < 56) rating = "60%";
                else if (pct < 71) rating = "30%";
                else rating = "10%";
                matches.add(new CriteriaMatch(
                        "FEV-1 " + m.group(1) + "% from " + src,
                        code + " FEV-1 predicted",
                        rating
                ));
            }
            // Daily inhaler
            if (val.contains("daily") && containsAny(val, INHALER_KEYWORDS)) {
                matches.add(new CriteriaMatch(
                        "Daily inhaler use from " + src,
                        code + " daily inhalational therapy",
                        "30%"
                ));
            }
            // Oxygen therapy
            if (val.contains("oxygen therapy") || val.contains("supplemental oxygen")) {
                matches.add(new CriteriaMatch(
                        "Oxygen therapy from " + src,
                        code + " requires oxygen therapy",
                        "100%"
                ));
            }
        }

        // ─── Mental health criteria (9200-9440, 8045) ───
        if (isMentalHealth(code)) {
            // PHQ-9
            Pattern phq9 = Pattern.compile("(?i)phq[- ]?9[^\\d]*(\\d+)");
            Matcher mp = phq9.matcher(atom.getValue());
            if (mp.find()) {
                int score = Integer.parseInt(mp.group(1));
                String rating;
                if (score >= 20) rating = "70%+";
                else if (score >= 15) rating = "50%";
                else if (score >= 10) rating = "30%";
                else rating = "10%";
                matches.add(new CriteriaMatch(
                        "PHQ-9 score " + score + " from " + src,
                        code + " depression severity",
                        rating
                ));
            }
            // PCL-5
            Pattern pcl5 = Pattern.compile("(?i)pcl[- ]?5[^\\d]*(\\d+)");
            Matcher mpcl = pcl5.matcher(atom.getValue());
            if (mpcl.find()) {
                int score = Integer.parseInt(mpcl.group(1));
                String rating;
                if (score >= 60) rating = "70%+";
                else if (score >= 44) rating = "50%";
                else if (score >= 33) rating = "30%";
                else rating = "10%";
                matches.add(new CriteriaMatch(
                        "PCL-5 score " + score + " from " + src,
                        code + " PTSD severity",
                        rating
                ));
            }
            // GAF
            Pattern gaf = Pattern.compile("(?i)gaf[^\\d]*(\\d+)");
            Matcher mg = gaf.matcher(atom.getValue());
            if (mg.find()) {
                int score = Integer.parseInt(mg.group(1));
                String rating;
                if (score <= 40) rating = "70%+";
                else if (score <= 50) rating = "50%";
                else if (score <= 60) rating = "30%";
                else rating = "10%";
                matches.add(new CriteriaMatch(
                        "GAF score " + score + " from " + src,
                        code + " global functioning",
                        rating
                ));
            }
            // Unemployability
            if (val.contains("unable to work") || val.contains("unemployable") || val.contains("tdiu")) {
                matches.add(new CriteriaMatch(
                        "Unemployability evidence from " + src,
                        code + " total occupational impairment",
                        "100%"
                ));
            }
            // Hospitalizations
            if (val.contains("hospitalization") || val.contains("inpatient") || val.contains("psychiatric admission")) {
                matches.add(new CriteriaMatch(
                        "Psych hospitalization from " + src,
                        code + " intermittent inability to perform ADLs",
                        "50%+"
                ));
            }
            // Suicidal ideation
            if (val.contains("suicidal ideation") || val.contains("suicidal thoughts")) {
                matches.add(new CriteriaMatch(
                        "Suicidal ideation from " + src,
                        code + " SI symptom",
                        "70%"
                ));
            }
        }

        // ─── Musculoskeletal criteria (5000-5299) ───
        if (isMusculoskeletal(code)) {
            // Range of motion - flexion
            Pattern flexion = Pattern.compile("(?i)flexion[^\\d]*(\\d+)\\s*(?:degrees|deg|°)");
            Matcher mf = flexion.matcher(atom.getValue());
            if (mf.find()) {
                int degrees = Integer.parseInt(mf.group(1));
                String rating;
                if (degrees <= 15) rating = "40%+";
                else if (degrees <= 30) rating = "30%";
                else if (degrees <= 45) rating = "20%";
                else if (degrees <= 60) rating = "10%";
                else rating = "0%";
                if (!"0%".equals(rating)) {
                    matches.add(new CriteriaMatch(
                            "Flexion " + degrees + "° from " + src,
                            code + " limitation of flexion",
                            rating
                    ));
                }
            }
            // Ankylosis
            if (val.contains("ankylosis") || val.contains("ankylosed")) {
                matches.add(new CriteriaMatch(
                        "Ankylosis from " + src,
                        code + " ankylosis",
                        "30%+"
                ));
            }
            // Assistive device
            for (String dev : List.of("wheelchair", "cane", "walker", "crutch")) {
                if (val.contains(dev)) {
                    matches.add(new CriteriaMatch(
                            dev + " use from " + src,
                            code + " assistive device required",
                            "20%+"
                    ));
                    break;
                }
            }
        }

        // ─── GI/Digestive criteria (7300-7399) ───
        if (isDigestive(code)) {
            // Hematemesis / melena
            if (val.contains("hematemesis") || val.contains("melena")) {
                matches.add(new CriteriaMatch(
                        "GI bleeding from " + src,
                        code + " hematemesis/melena episode",
                        "60%"
                ));
            }
            // Weight loss
            if (val.contains("weight loss") || val.contains("lost weight")) {
                matches.add(new CriteriaMatch(
                        "Weight loss from " + src,
                        code + " material weight loss",
                        "30%+"
                ));
            }
            // Anemia
            Pattern hgb = Pattern.compile("(?i)(?:hemoglobin|hgb|hb)[^\\d]*(\\d+\\.?\\d*)");
            Matcher mh = hgb.matcher(atom.getValue());
            if (mh.find()) {
                double level = Double.parseDouble(mh.group(1));
                if (level < 10.0) {
                    matches.add(new CriteriaMatch(
                            "Hemoglobin " + mh.group(1) + " from " + src,
                            code + " anemia",
                            "30%+"
                    ));
                }
            }
            // Incapacitating episodes
            if (val.contains("incapacitating") || val.contains("bedridden")) {
                matches.add(new CriteriaMatch(
                        "Incapacitating episode from " + src,
                        code + " incapacitating episodes",
                        "20%+"
                ));
            }
        }

        return matches;
    }

    // ─────────────────────────────────────────────
    // Utility methods
    // ─────────────────────────────────────────────

    private int parseVasrdCode(String vasrdCode) {
        if (vasrdCode == null || vasrdCode.isEmpty()) return 0;
        try {
            return Integer.parseInt(vasrdCode.replaceAll("[^0-9]", ""));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String lower(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT);
    }

    private List<Atom> filterByTypeOrValue(List<Atom> atoms, String type, String valueKeyword) {
        return atoms.stream()
                .filter(a -> {
                    boolean typeMatch = type == null || lower(a.getType()).contains(type);
                    boolean valueMatch = valueKeyword == null || lower(a.getValue()).contains(valueKeyword);
                    return typeMatch && valueMatch;
                })
                .collect(Collectors.toList());
    }

    private String extractDrugName(String value) {
        if (value == null || value.isEmpty()) return "unknown";
        // Take first meaningful word (skip dosage numbers)
        String[] parts = value.trim().split("\\s+");
        for (String part : parts) {
            if (!part.matches("\\d+.*") && part.length() > 1) {
                return part.toLowerCase(Locale.ROOT);
            }
        }
        return parts[0].toLowerCase(Locale.ROOT);
    }

    private String extractDosage(String value) {
        if (value == null) return "";
        Pattern dosage = Pattern.compile("(\\d+\\.?\\d*\\s*(?:mg|mcg|ml|g|units|iu))", Pattern.CASE_INSENSITIVE);
        Matcher m = dosage.matcher(value);
        return m.find() ? m.group(1) : "";
    }

    private boolean containsAny(String text, List<String> keywords) {
        for (String kw : keywords) {
            if (text.contains(kw)) return true;
        }
        return false;
    }

    private String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max - 3) + "...";
    }

    // ─────────────────────────────────────────────
    // Inner class for criteria mapping results
    // ─────────────────────────────────────────────

    private static class CriteriaMatch {
        final String evidence;
        final String criteria;
        final String rating;

        CriteriaMatch(String evidence, String criteria, String rating) {
            this.evidence = evidence;
            this.criteria = criteria;
            this.rating = rating;
        }
    }
}
