package com.afterduty.service;

import com.afterduty.exception.UsageLimitException;
import com.afterduty.model.*;
import com.afterduty.repository.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.afterduty.model.PipelineMetrics;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Pipeline orchestrator: Layer 1 (Gemini extraction) then Layer 2 (Claude synthesis + gap analysis).
 */
@Service
public class PipelineService {

    private static final Logger log = LoggerFactory.getLogger(PipelineService.class);

    private final ClaudeSynthesisService claudeSynthesisService;
    private final DocumentStorageService documentStorageService;
    private final ConditionPostProcessService conditionPostProcessService;
    private final PipelineVerifierService pipelineVerifierService;
    private final ClaimRepository claimRepository;
    private final EvidenceRepository evidenceRepository;
    private final AtomRepository atomRepository;
    private final ConditionRepository conditionRepository;
    private final UsageGuard usageGuard;
    private final ServiceHistoryAdjudicationService serviceHistoryAdjudicationService;

    public PipelineService(ClaudeSynthesisService claudeSynthesisService,
                           DocumentStorageService documentStorageService,
                           ConditionPostProcessService conditionPostProcessService,
                           PipelineVerifierService pipelineVerifierService,
                           ClaimRepository claimRepository, EvidenceRepository evidenceRepository,
                           AtomRepository atomRepository, ConditionRepository conditionRepository,
                           UsageGuard usageGuard,
                           ServiceHistoryAdjudicationService serviceHistoryAdjudicationService) {
        this.claudeSynthesisService = claudeSynthesisService;
        this.documentStorageService = documentStorageService;
        this.conditionPostProcessService = conditionPostProcessService;
        this.pipelineVerifierService = pipelineVerifierService;
        this.claimRepository = claimRepository;
        this.evidenceRepository = evidenceRepository;
        this.atomRepository = atomRepository;
        this.conditionRepository = conditionRepository;
        this.usageGuard = usageGuard;
        this.serviceHistoryAdjudicationService = serviceHistoryAdjudicationService;
    }

    /**
     * Process a single evidence item: extract atoms via Gemini.
     *
     * If the evidence has a GCS path, downloads from GCS.
     * Otherwise falls back to base64-encoded raw content.
     */
    @Async
    public void processEvidence(Long evidenceId, Long userId) {
        EvidenceItem evidence = evidenceRepository.findById(evidenceId).orElse(null);
        if (evidence == null) {
            log.warn("Evidence {} not found", evidenceId);
            return;
        }

        Claim claim = claimRepository.findById(evidence.getClaimId()).orElse(null);
        if (claim == null) return;

        try {
            usageGuard.assertCapacity(userId);
        } catch (UsageLimitException ex) {
            evidence.setProcessingStatus("deferred_usage_limit");
            evidence.setProcessingMessage(
                "Paused — plan limit reached. Resumes " + ex.getPeriodEnd() + ".");
            evidenceRepository.saveAndFlush(evidence);
            log.info("Evidence {} deferred — usage limit reached for user {}", evidenceId, userId);
            return;
        }

        claim.setStatus(Claim.ClaimStatus.EXTRACTING);
        claimRepository.save(claim);

        String filename = evidence.getFilename() != null ? evidence.getFilename() : "document";

        try {
            // P0-3 — honest status lifecycle. At submit the document is only
            // QUEUED: the ExtractionStateMachine writes "processing" when it
            // actually submits this doc's extraction job, and "processed" only
            // after that doc's facts parse successfully. Writing "processed"
            // here made every document show a green done badge before (or
            // without ever) being read.
            updateProgress(evidence, "queued",
                    "\"" + filename + "\" is waiting for AI analysis...");

            evidence.setAiSummary("Queued");
            evidence.setAiExtractedData(Map.of("queued", true));
            evidenceRepository.save(evidence);

            claim.setLastEvidenceAt(Instant.now());
            claim.setSynthesisNeeded(true);
            // Kick off async extraction pipeline; AnalysisScheduler will drive it
            if (claim.getExtractionState() == null) {
                claim.setExtractionState("NONE");
            }
            claim.setStatus(Claim.ClaimStatus.EXTRACTING);
            claimRepository.save(claim);

            log.info("Evidence {} queued for async extraction via ExtractionStateMachine", evidenceId);

        } catch (Exception e) {
            log.error("Extraction failed for evidence {}", evidenceId, e);
            evidence.setProcessingStatus("error");
            evidence.setProcessingMessage("Extraction failed: " + e.getMessage());
            evidence.setAiExtractedData(Map.of("error", e.getMessage()));
            evidenceRepository.save(evidence);
            claim.setStatus(Claim.ClaimStatus.ERROR);
            claimRepository.save(claim);
        }
    }

    /** Re-enqueue extraction for a previously deferred evidence row. */
    @Async
    public void processEvidenceForReset(Long evidenceId) {
        EvidenceItem ev = evidenceRepository.findById(evidenceId).orElse(null);
        if (ev == null) return;
        Claim claim = claimRepository.findById(ev.getClaimId()).orElse(null);
        if (claim == null) return;
        processEvidence(evidenceId, claim.getUserId());
    }

    /**
     * Update evidence processing progress (flushes immediately for polling visibility).
     */
    private void updateProgress(EvidenceItem evidence, String status, String message) {
        evidence.setProcessingStatus(status);
        evidence.setProcessingMessage(message);
        evidenceRepository.saveAndFlush(evidence);
        log.debug("Evidence {} progress: [{}] {}", evidence.getId(), status, message);
    }

    /**
     * Update claim analysis progress (flushes immediately for polling visibility).
     */
    private void updateClaimProgress(Claim claim, String stage, String message, int pct) {
        claim.setAnalysisStage(stage);
        claim.setAnalysisMessage(message);
        claim.setAnalysisProgressPct(pct);
        claimRepository.saveAndFlush(claim);
        log.info("Claim {} analysis [{}]: {} ({}%)", claim.getId(), stage, message, pct);
    }

    /**
     * Run the full synthesis + gap analysis pipeline.
     */
    @Transactional
    public Map<String, Object> runFullPipeline(Long claimId, Long userId) {
        Claim claim = claimRepository.findById(claimId)
                .orElseThrow(() -> new RuntimeException("Claim not found: " + claimId));

        usageGuard.assertCapacity(userId);

        claim.setStatus(Claim.ClaimStatus.SYNTHESIZING);
        updateClaimProgress(claim, "identifying",
                "Analyzing your evidence to identify potential conditions...", 10);

        String synthesisResult = claudeSynthesisService.runSynthesis(claimId, userId);

        updateClaimProgress(claim, "deduplicating",
                "Removing duplicate conditions and detecting pyramiding rules...", 50);

        // Post-process: deduplicate and detect pyramiding
        conditionPostProcessService.postProcess(claimId);

        updateClaimProgress(claim, "gap_analysis",
                "Identifying evidence gaps and calculating what-if scenarios...", 65);

        // Deliberately NOT re-checking the cap here. runFullPipeline runs
        // inside @Transactional, so a guard throw mid-pipeline would roll
        // back the AiCallLog row that synthesis just wrote — the cost is
        // gone from our books but already paid to Claude/Vertex, and the
        // scheduler would re-enter and re-pay. The single entry-time check
        // above is the sole guard for the synth+gap pair.
        String gapResult = claudeSynthesisService.runGapAnalysis(claimId, userId);

        // Service History P3 (Part B): pipeline-time LLM adjudication of genuine
        // equal-authority service-date conflicts. Runs HERE, not at read time —
        // the profile endpoint only reads the persisted resolution. Best-effort +
        // safe-by-default: makes NO LLM call unless a real conflict exists, and
        // never throws (a failure here must not abort the analysis).
        int adjudicated = serviceHistoryAdjudicationService.adjudicateForUser(claimId, userId);
        if (adjudicated > 0) {
            log.info("Service-history: adjudicated {} conflict(s) for claim {} (user {})",
                    adjudicated, claimId, userId);
        }

        updateClaimProgress(claim, "complete",
                "Analysis complete — review your conditions below", 100);

        claim.setLastAnalyzedAt(Instant.now());
        claim.setSynthesisNeeded(false);
        claim.setStatus(Claim.ClaimStatus.ANALYZED);
        claimRepository.save(claim);

        // Collect pipeline metrics
        try {
            PipelineMetrics metrics = pipelineVerifierService.collectMetrics(claimId);
            log.info("Pipeline metrics for claim {}: atoms={}, conditions={}, gaps={}, cost=${}",
                    claimId, metrics.getExtractionAtomCount(), metrics.getSynthesisConditionCount(),
                    metrics.getGapAnalysisGapCount(), String.format("%.4f", metrics.getTotalCostUsd()));
        } catch (Exception e) {
            log.warn("Failed to collect pipeline metrics for claim {}: {}", claimId, e.getMessage());
        }

        return Map.of("synthesis", synthesisResult, "gap_analysis", gapResult);
    }

    /**
     * Run synthesis only (no gap analysis).
     */
    @Transactional
    public String runSynthesisOnly(Long claimId, Long userId) {
        return claudeSynthesisService.runSynthesis(claimId, userId);
    }

    /**
     * Run gap analysis only (no synthesis).
     */
    @Transactional
    public String runGapAnalysisOnly(Long claimId, Long userId) {
        return claudeSynthesisService.runGapAnalysis(claimId, userId);
    }

    /**
     * Check if all evidence in a claim is processed and auto-run synthesis.
     */
    private void checkAndRunSynthesis(Long claimId, Long userId) {
        List<EvidenceItem> allEvidence = evidenceRepository.findByClaimIdOrderByCreatedAt(claimId);
        boolean allProcessed = allEvidence.stream()
                .allMatch(e -> "processed".equals(e.getProcessingStatus()) || "error".equals(e.getProcessingStatus()));
        // Mission 5a: readiness gate counts LIVE atoms only (a re-extracted doc's
        // superseded atoms are not synthesizable evidence).
        long atomCount = atomRepository.countByClaimIdAndSupersededByIsNull(claimId);

        if (allProcessed && atomCount > 0) {
            Claim claim = claimRepository.findById(claimId).orElse(null);
            if (claim != null && Boolean.TRUE.equals(claim.getSynthesisNeeded())) {
                log.info("All evidence processed for claim {} -- running synthesis with {} atoms", claimId, atomCount);
                runFullPipeline(claimId, userId);
            }
        }
    }
}
