package com.afterduty.service;

import com.afterduty.model.*;
import com.afterduty.repository.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Pipeline verifier: runs quality checks on extraction, synthesis, and gap analysis output,
 * then collects metrics into a PipelineMetrics record.
 */
@Service
public class PipelineVerifierService {

    private static final Logger log = LoggerFactory.getLogger(PipelineVerifierService.class);

    private static final Set<Integer> VALID_RATINGS = Set.of(0, 10, 20, 30, 40, 50, 60, 70, 80, 100);

    private static final Pattern VASRD_PATTERN = Pattern.compile("\\b\\d{4}\\b");
    private static final Pattern EVIDENCE_TYPE_PATTERN = Pattern.compile(
            "(?i)(nexus|buddy statement|C&P|service treatment|medical record|DBQ|IMO|specialist)");

    private final AtomRepository atomRepository;
    private final ConditionRepository conditionRepository;
    private final AiCallLogRepository aiCallLogRepository;
    private final PipelineMetricsRepository pipelineMetricsRepository;

    public PipelineVerifierService(AtomRepository atomRepository,
                                   ConditionRepository conditionRepository,
                                   AiCallLogRepository aiCallLogRepository,
                                   PipelineMetricsRepository pipelineMetricsRepository) {
        this.atomRepository = atomRepository;
        this.conditionRepository = conditionRepository;
        this.aiCallLogRepository = aiCallLogRepository;
        this.pipelineMetricsRepository = pipelineMetricsRepository;
    }

    // ---- Extraction verification ----

    public ExtractionReport verifyExtraction(Long claimId) {
        // Mission 5a: the verifier reports on the LIVE atom set (what synthesis
        // actually sees); superseded atoms from re-extracted docs are excluded so
        // the counts/coverage reflect the current analysis, not retired duplicates.
        List<Atom> atoms = atomRepository.findByClaimIdAndSupersededByIsNull(claimId);
        ExtractionReport report = new ExtractionReport();
        report.totalAtomCount = atoms.size();

        int medicationCount = 0;
        int serviceRecordCount = 0;
        double confidenceSum = 0.0;
        List<Long> lowConfidenceAtomIds = new ArrayList<>();

        for (Atom atom : atoms) {
            String type = atom.getType() != null ? atom.getType().toLowerCase() : "";
            String value = atom.getValue() != null ? atom.getValue().toLowerCase() : "";

            if (type.contains("medication") || type.contains("prescription") ||
                    value.contains("mg") || value.contains("prescribed") || value.contains("dosage")) {
                medicationCount++;
            }
            if (type.contains("service") || type.contains("military") ||
                    type.contains("deployment") || type.contains("service_record")) {
                serviceRecordCount++;
            }

            double conf = atom.getConfidence() != null ? atom.getConfidence() : 0.0;
            confidenceSum += conf;
            if (conf < 0.5) {
                lowConfidenceAtomIds.add(atom.getId());
            }
        }

        report.medicationAtomCount = medicationCount;
        report.serviceRecordAtomCount = serviceRecordCount;
        report.avgConfidence = atoms.isEmpty() ? 0.0 : confidenceSum / atoms.size();
        report.lowConfidenceAtomIds = lowConfidenceAtomIds;

        log.info("Extraction verification for claim {}: {} atoms, {} medication, {} service, avgConf={}, {} low-confidence",
                claimId, report.totalAtomCount, report.medicationAtomCount, report.serviceRecordAtomCount,
                String.format("%.2f", report.avgConfidence), report.lowConfidenceAtomIds.size());

        return report;
    }

    // ---- Synthesis verification ----

    public SynthesisReport verifySynthesis(Long claimId) {
        // Mission 5b — report on the active generation only.
        List<IdentifiedCondition> conditions = conditionRepository.findByClaimIdAndSupersededByIsNull(claimId);

        SynthesisReport report = new SynthesisReport();
        report.conditionCount = conditions.size();

        int validVasrdCount = 0;
        List<String> invalidRatingConditions = new ArrayList<>();
        List<String> missingTriadConditions = new ArrayList<>();
        double ratingSum = 0.0;
        double confidenceSum = 0.0;
        int ratingCount = 0;

        for (IdentifiedCondition c : conditions) {
            // VASRD code check
            String vasrd = c.getVasrdCode();
            if (vasrd != null && !vasrd.isBlank() && VASRD_PATTERN.matcher(vasrd).find()) {
                validVasrdCount++;
            }

            // Rating validity check
            Integer rating = c.getEstimatedRating();
            if (rating != null && VALID_RATINGS.contains(rating)) {
                ratingSum += rating;
                ratingCount++;
            } else {
                invalidRatingConditions.add(c.getName() + " (rating=" + rating + ")");
            }

            // Triad completeness check
            if (!hasValidTriad(c.getTriadDiagnosis()) ||
                    !hasValidTriad(c.getTriadInService()) ||
                    !hasValidTriad(c.getTriadNexus())) {
                missingTriadConditions.add(c.getName());
            }

            double conf = c.getConfidence() != null ? c.getConfidence() : 0.0;
            confidenceSum += conf;
        }

        report.validVasrdCodeCount = validVasrdCount;
        report.avgRating = ratingCount > 0 ? ratingSum / ratingCount : 0.0;
        report.avgConfidence = conditions.isEmpty() ? 0.0 : confidenceSum / conditions.size();
        report.invalidRatingConditions = invalidRatingConditions;
        report.missingTriadConditions = missingTriadConditions;

        log.info("Synthesis verification for claim {}: {} conditions, {} valid VASRD, avgRating={}, avgConf={}",
                claimId, report.conditionCount, report.validVasrdCodeCount,
                String.format("%.1f", report.avgRating), String.format("%.2f", report.avgConfidence));

        return report;
    }

    private boolean hasValidTriad(Map<String, Object> triad) {
        if (triad == null || triad.isEmpty()) return false;
        return triad.containsKey("status") && triad.containsKey("evidence");
    }

    // ---- Gap analysis verification ----

    public GapAnalysisReport verifyGapAnalysis(Long claimId) {
        // Mission 5b — report on the active generation only.
        List<IdentifiedCondition> conditions = conditionRepository.findByClaimIdAndSupersededByIsNull(claimId);

        GapAnalysisReport report = new GapAnalysisReport();
        int totalGaps = 0;
        int specificGaps = 0;
        int totalWhatIfs = 0;
        List<String> invalidWhatIfConditions = new ArrayList<>();

        for (IdentifiedCondition c : conditions) {
            List<Map<String, Object>> gaps = c.getGaps();
            if (gaps != null) {
                totalGaps += gaps.size();
                for (Map<String, Object> gap : gaps) {
                    String desc = gap.get("description") != null ? gap.get("description").toString() : "";
                    String type = gap.get("type") != null ? gap.get("type").toString() : "";
                    String combined = desc + " " + type;
                    if (VASRD_PATTERN.matcher(combined).find() || EVIDENCE_TYPE_PATTERN.matcher(combined).find()) {
                        specificGaps++;
                    }
                }
            }

            List<Map<String, Object>> whatIfs = c.getWhatIfScenarios();
            if (whatIfs != null) {
                totalWhatIfs += whatIfs.size();
                for (Map<String, Object> wi : whatIfs) {
                    boolean hasCurrent = wi.containsKey("current_rating") && wi.get("current_rating") != null;
                    boolean hasPotential = wi.containsKey("potential_rating") && wi.get("potential_rating") != null;
                    if (!hasCurrent || !hasPotential) {
                        invalidWhatIfConditions.add(c.getName());
                        break;
                    }
                }
            }
        }

        report.gapCount = totalGaps;
        report.specificGapCount = specificGaps;
        report.whatIfCount = totalWhatIfs;
        report.invalidWhatIfConditions = invalidWhatIfConditions;

        log.info("Gap analysis verification for claim {}: {} gaps ({} specific), {} what-ifs",
                claimId, report.gapCount, report.specificGapCount, report.whatIfCount);

        return report;
    }

    // ---- Collect all metrics ----

    public PipelineMetrics collectMetrics(Long claimId) {
        long start = System.currentTimeMillis();

        ExtractionReport extraction = verifyExtraction(claimId);
        SynthesisReport synthesis = verifySynthesis(claimId);
        GapAnalysisReport gapAnalysis = verifyGapAnalysis(claimId);

        PipelineMetrics metrics = new PipelineMetrics();
        metrics.setClaimId(claimId);
        metrics.setRunTimestamp(Instant.now());

        // Extraction
        metrics.setExtractionAtomCount(extraction.totalAtomCount);
        metrics.setExtractionMedicationAtomCount(extraction.medicationAtomCount);
        metrics.setExtractionServiceRecordAtomCount(extraction.serviceRecordAtomCount);
        metrics.setExtractionAvgConfidence(extraction.avgConfidence);

        // Synthesis
        metrics.setSynthesisConditionCount(synthesis.conditionCount);
        metrics.setSynthesisAvgRating(synthesis.avgRating);
        metrics.setSynthesisAvgConfidence(synthesis.avgConfidence);
        metrics.setSynthesisValidVasrdCodeCount(synthesis.validVasrdCodeCount);

        // Gap analysis
        metrics.setGapAnalysisGapCount(gapAnalysis.gapCount);
        metrics.setGapAnalysisSpecificGapCount(gapAnalysis.specificGapCount);
        metrics.setGapAnalysisWhatIfCount(gapAnalysis.whatIfCount);

        // Cost/token totals from AI call logs
        List<AiCallLog> callLogs = aiCallLogRepository.findByClaimIdOrderByCreatedAtDesc(claimId);
        long totalTokens = 0;
        double totalCost = 0.0;
        for (AiCallLog cl : callLogs) {
            if (cl.getInputTokens() != null) totalTokens += cl.getInputTokens();
            if (cl.getOutputTokens() != null) totalTokens += cl.getOutputTokens();
            if (cl.getThinkingTokens() != null) totalTokens += cl.getThinkingTokens();
            if (cl.getTotalCost() != null) totalCost += cl.getTotalCost().doubleValue();
        }
        metrics.setTotalTokensUsed(totalTokens);
        metrics.setTotalCostUsd(totalCost);

        long elapsed = System.currentTimeMillis() - start;
        metrics.setTotalDurationMs(elapsed);

        // Build notes with warnings
        StringBuilder notes = new StringBuilder();
        if (!extraction.lowConfidenceAtomIds.isEmpty()) {
            notes.append("LOW_CONF_ATOMS: ").append(extraction.lowConfidenceAtomIds.size()).append(" atoms below 0.5 confidence. ");
        }
        if (!synthesis.invalidRatingConditions.isEmpty()) {
            notes.append("INVALID_RATINGS: ").append(String.join(", ", synthesis.invalidRatingConditions)).append(". ");
        }
        if (!synthesis.missingTriadConditions.isEmpty()) {
            notes.append("MISSING_TRIAD: ").append(String.join(", ", synthesis.missingTriadConditions)).append(". ");
        }
        if (!gapAnalysis.invalidWhatIfConditions.isEmpty()) {
            notes.append("INVALID_WHATIF: ").append(String.join(", ", gapAnalysis.invalidWhatIfConditions)).append(". ");
        }
        if (notes.length() > 0) {
            metrics.setNotes(notes.toString().trim());
        }

        metrics = pipelineMetricsRepository.save(metrics);

        log.info("Pipeline metrics collected for claim {}: atoms={}, conditions={}, gaps={}, tokens={}, cost=${}",
                claimId, metrics.getExtractionAtomCount(), metrics.getSynthesisConditionCount(),
                metrics.getGapAnalysisGapCount(), metrics.getTotalTokensUsed(),
                String.format("%.4f", metrics.getTotalCostUsd()));

        return metrics;
    }

    // ---- Report DTOs ----

    public static class ExtractionReport {
        public int totalAtomCount;
        public int medicationAtomCount;
        public int serviceRecordAtomCount;
        public double avgConfidence;
        public List<Long> lowConfidenceAtomIds = new ArrayList<>();
    }

    public static class SynthesisReport {
        public int conditionCount;
        public int validVasrdCodeCount;
        public double avgRating;
        public double avgConfidence;
        public List<String> invalidRatingConditions = new ArrayList<>();
        public List<String> missingTriadConditions = new ArrayList<>();
    }

    public static class GapAnalysisReport {
        public int gapCount;
        public int specificGapCount;
        public int whatIfCount;
        public List<String> invalidWhatIfConditions = new ArrayList<>();
    }
}
