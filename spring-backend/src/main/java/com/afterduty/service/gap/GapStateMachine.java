package com.afterduty.service.gap;

import com.afterduty.model.Atom;
import com.afterduty.model.Claim;
import com.afterduty.model.ClaimPipelineJob;
import com.afterduty.model.IdentifiedCondition;
import com.afterduty.repository.AtomRepository;
import com.afterduty.repository.ClaimPipelineJobRepository;
import com.afterduty.repository.ClaimRepository;
import com.afterduty.repository.IdentifiedConditionRepository;
import com.afterduty.repository.LlmJobRepository;
import com.afterduty.service.AnalysisScheduler;
import com.afterduty.service.llm.LlmJobRequest;
import com.afterduty.service.llm.LlmJobResult;
import com.afterduty.service.llm.LlmJobService;
import com.afterduty.service.synthesis.ConditionGenerationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/**
 * Hand-rolled state machine driving the gap-analysis pipeline.
 * State stored in Claim.gapState (string-enum). Advances at most one
 * transition per call to advance(). AnalysisScheduler.tick() drives it.
 */
@Service
public class GapStateMachine {

    private static final Logger log = LoggerFactory.getLogger(GapStateMachine.class);

    public enum State { NONE, EVIDENCE_GAPS, VALIDATING, WHATIF, COMPLETE }

    private final ClaimRepository claimRepository;
    private final IdentifiedConditionRepository conditionRepository;
    private final AtomRepository atomRepository;
    private final ClaimPipelineJobRepository pipelineJobRepository;
    private final LlmJobService llmJobService;
    private final LlmJobRepository llmJobRepository;
    private final EvidenceGapAnalyzer evidenceGapAnalyzer;
    private final GapValidationAgent gapValidationAgent;
    private final WhatIfScenarioGenerator whatIfScenarioGenerator;
    private final AnalysisScheduler analysisScheduler;
    private final ConditionGenerationService generationService;
    private final UserGapStateService userGapStateService;

    /**
     * Mission 5b — gate gap dirty-scope carry-forward behind the same incremental
     * flag. Flag OFF restores today's exact semantics: gaps fan out over every
     * active condition every run. Rollback = INCREMENTAL_ANALYSIS=false.
     */
    @Value("${va-claim.analysis.incremental:true}")
    private boolean incrementalEnabled;

    /**
     * P1-9 — what-if scenarios are generated, validated and paid for on every
     * run but rendered NOWHERE in the product, so the gap_whatif stage is pure
     * spend on unread output. Default OFF: gap analysis completes right after
     * validation and whatIfScenarios stays null / carried forward. Flip with
     * GAP_WHATIF_ENABLED=true (va-claim.gap.whatif-enabled) once a renderer
     * ships. Tests that exercise the what-if stage set it true explicitly.
     */
    @Value("${va-claim.gap.whatif-enabled:false}")
    private boolean whatifEnabled;

    public GapStateMachine(ClaimRepository claimRepository,
                            IdentifiedConditionRepository conditionRepository,
                            AtomRepository atomRepository,
                            ClaimPipelineJobRepository pipelineJobRepository,
                            LlmJobService llmJobService,
                            LlmJobRepository llmJobRepository,
                            EvidenceGapAnalyzer evidenceGapAnalyzer,
                            GapValidationAgent gapValidationAgent,
                            WhatIfScenarioGenerator whatIfScenarioGenerator,
                            AnalysisScheduler analysisScheduler,
                            ConditionGenerationService generationService,
                            UserGapStateService userGapStateService) {
        this.claimRepository = claimRepository;
        this.conditionRepository = conditionRepository;
        this.atomRepository = atomRepository;
        this.pipelineJobRepository = pipelineJobRepository;
        this.llmJobService = llmJobService;
        this.llmJobRepository = llmJobRepository;
        this.evidenceGapAnalyzer = evidenceGapAnalyzer;
        this.gapValidationAgent = gapValidationAgent;
        this.whatIfScenarioGenerator = whatIfScenarioGenerator;
        this.analysisScheduler = analysisScheduler;
        this.generationService = generationService;
        this.userGapStateService = userGapStateService;
    }

    @Transactional
    public void advance(Claim claim) {
        State current = parseState(claim.getGapState());
        switch (current) {
            case NONE          -> doFanoutEvidence(claim);
            case EVIDENCE_GAPS -> doFanoutValidatingIfReady(claim);
            case VALIDATING    -> doFanoutWhatIfIfReady(claim);
            case WHATIF        -> doCompleteIfReady(claim);
            case COMPLETE      -> { /* no-op */ }
        }
    }

    // -------------------------------------------------------------------------
    // NONE → EVIDENCE_GAPS (or immediate COMPLETE if no conditions)
    // -------------------------------------------------------------------------

    private void doFanoutEvidence(Claim claim) {
        // Mission 5b — read the ACTIVE generation only. A re-analysis supersedes
        // the prior generation's conditions; gap analysis must never fan out over
        // (or carry gaps from) retired rows.
        List<IdentifiedCondition> conditions =
                conditionRepository.findByClaimIdAndSupersededByIsNull(claim.getId());

        if (conditions.isEmpty()) {
            log.info("[gap] No conditions for claim {} — fast-pathing to COMPLETE", claim.getId());
            analysisScheduler.markGapAnalysisComplete(claim.getId(), "unknown");
            return;
        }

        // Mission 5b — dirty-scope: fan out gap analysis ONLY over conditions that
        // need it. A CLEAN condition carried its prior gaps/what-ifs forward during
        // synthesis (non-null gaps), so it is skipped here; only DIRTY conditions
        // (freshly re-rated this run ⇒ null gaps) are analyzed. With the flag OFF
        // every condition has null gaps after rating, so this is the full fan-out.
        List<IdentifiedCondition> dirtyConditions = incrementalEnabled
                ? conditions.stream().filter(generationService::needsGapAnalysis).toList()
                : conditions;

        if (dirtyConditions.isEmpty()) {
            // Every active condition already has carried-forward gap analysis —
            // nothing to (re)analyze. Complete the gap stage cleanly; the existing
            // gaps/what-ifs on the conditions are authoritative.
            log.info("[gap] All {} active condition(s) clean (gaps carried forward) for claim {} — "
                    + "fast-pathing to COMPLETE", conditions.size(), claim.getId());
            analysisScheduler.markGapAnalysisComplete(claim.getId(), "carry-forward");
            return;
        }

        // Mission 5a: gap analysis reads LIVE atoms only — superseded atoms from
        // a re-extracted document must not feed the per-condition evidence/gap
        // prompts (they would re-introduce stale facts the new extraction replaced).
        List<Atom> atoms = atomRepository.findByClaimIdAndSupersededByIsNull(claim.getId());
        String batchGroupKey = "gap_evidence_" + claim.getId();

        // Clear stale rows from prior runs so the IfReady readers only ever see
        // this run's jobs.
        pipelineJobRepository.deleteByClaimIdAndStage(claim.getId(), "gap_evidence");
        List<ClaimPipelineJob> pjobs = new ArrayList<>();
        for (IdentifiedCondition cond : dirtyConditions) {
            // P1-6 — inject the veteran's dismissed gaps as do-not-re-propose
            // context. Rides the volatile tail AFTER the cached prefix, so the
            // shared cache_control breakpoint is untouched.
            LlmJobRequest req = evidenceGapAnalyzer.buildRequest(cond, atoms, claim.getId(),
                    claim.getUserId(), userGapStateService.dismissedFor(claim.getId(), cond));
            // Override batchGroupKey to ensure correct group key. Mission 6b — carry
            // the structuredPrompt through the rebuild, else the cached atom-corpus
            // prefix block (and its cache_control breakpoint) is silently dropped.
            req = LlmJobRequest.builder()
                    .purpose(req.getPurpose())
                    .systemPrompt(req.getSystemPrompt())
                    .userMessage(req.getUserMessage())
                    .structuredPrompt(req.getStructuredPrompt())
                    .maxTokens(req.getMaxTokens())
                    .thinkingBudget(req.getThinkingBudget())
                    .claimId(claim.getId())
                    .userId(claim.getUserId())
                    .conditionId(cond.getId())
                    .batchGroupKey(batchGroupKey)
                    .build();
            UUID jobId = llmJobService.submit(req);
            pjobs.add(new ClaimPipelineJob(claim.getId(), "gap_evidence", jobId, cond.getId(), null));
        }
        pipelineJobRepository.saveAll(pjobs);

        claim.setGapState(State.EVIDENCE_GAPS.name());
        claim.setGapAnalysisInProgress(true);
        claimRepository.save(claim);
        log.info("[gap] NONE → EVIDENCE_GAPS for claim {}, {} dirty condition(s) ({} active, "
                + "{} clean carried forward)", claim.getId(), dirtyConditions.size(), conditions.size(),
                conditions.size() - dirtyConditions.size());
    }

    // -------------------------------------------------------------------------
    // EVIDENCE_GAPS → VALIDATING
    // -------------------------------------------------------------------------

    private void doFanoutValidatingIfReady(Claim claim) {
        List<ClaimPipelineJob> evidencePjobs = pipelineJobRepository
                .findByClaimIdAndStage(claim.getId(), "gap_evidence");
        if (evidencePjobs.isEmpty()) return;

        Set<UUID> jobIds = collectJobIds(evidencePjobs);
        if (!llmJobService.allTerminal(jobIds)) return;
        if (!llmJobService.allSucceeded(jobIds)) {
            failGapAnalysis(claim, "gap_evidence", jobIds);
            return;
        }

        // Mission 5a: gap analysis reads LIVE atoms only — superseded atoms from
        // a re-extracted document must not feed the per-condition evidence/gap
        // prompts (they would re-introduce stale facts the new extraction replaced).
        List<Atom> atoms = atomRepository.findByClaimIdAndSupersededByIsNull(claim.getId());
        String batchGroupKey = "gap_validation_" + claim.getId();

        pipelineJobRepository.deleteByClaimIdAndStage(claim.getId(), "gap_validation");
        List<ClaimPipelineJob> valPjobs = new ArrayList<>();
        for (ClaimPipelineJob epj : evidencePjobs) {
            LlmJobResult evidenceResult = llmJobService.getResult(epj.getLlmJobId()).orElseThrow();
            List<Map<String, Object>> gaps = evidenceGapAnalyzer.parseResponse(evidenceResult);

            if (epj.getConditionId() == null) continue;
            conditionRepository.findById(epj.getConditionId()).ifPresent(cond -> {
                // P1-6 — a run wholesale-replaces the gap JSON; stamp the
                // veteran's durable statuses (user_gap_state) back onto the
                // fresh gaps so re-analysis never resurrects what they
                // resolved/dismissed. Visible in the VALIDATING window too.
                cond.setGaps(userGapStateService.reapply(claim.getId(), cond, gaps));
                conditionRepository.save(cond);
            });
        }

        // Now fan out validation jobs for the conditions analyzed THIS run that
        // have gaps. Mission 5b — restrict to the dirty set (the conditions that
        // had a gap_evidence job this run); a clean carried-forward condition's
        // gaps were already validated in the run that produced them and must not
        // be re-validated. With the flag OFF the dirty set is every active
        // condition, so this is the full validation fan-out, unchanged.
        Set<Long> runConditionIds = new HashSet<>();
        for (ClaimPipelineJob epj : evidencePjobs) {
            if (epj.getConditionId() != null) runConditionIds.add(epj.getConditionId());
        }
        List<IdentifiedCondition> conditions =
                conditionRepository.findByClaimIdAndSupersededByIsNull(claim.getId());
        for (IdentifiedCondition cond : conditions) {
            if (incrementalEnabled && !runConditionIds.contains(cond.getId())) continue;
            List<Map<String, Object>> gaps = cond.getGaps();
            if (gaps == null || gaps.isEmpty()) continue;

            LlmJobRequest baseReq = gapValidationAgent.buildRequest(cond, atoms, gaps, claim.getId(), claim.getUserId());
            // Mission 6b — carry the structuredPrompt through the rebuild so the cached
            // atom-corpus prefix (and its cache_control breakpoint) survives.
            LlmJobRequest req = LlmJobRequest.builder()
                    .purpose(baseReq.getPurpose())
                    .systemPrompt(baseReq.getSystemPrompt())
                    .userMessage(baseReq.getUserMessage())
                    .structuredPrompt(baseReq.getStructuredPrompt())
                    .maxTokens(baseReq.getMaxTokens())
                    .thinkingBudget(baseReq.getThinkingBudget())
                    .claimId(claim.getId())
                    .userId(claim.getUserId())
                    .conditionId(cond.getId())
                    .batchGroupKey(batchGroupKey)
                    .build();
            UUID jobId = llmJobService.submit(req);
            valPjobs.add(new ClaimPipelineJob(claim.getId(), "gap_validation", jobId, cond.getId(), null));
        }

        if (valPjobs.isEmpty()) {
            // No conditions with gaps → skip to complete
            log.info("[gap] EVIDENCE_GAPS: no gaps found for claim {} — fast-pathing to COMPLETE", claim.getId());
            analysisScheduler.markGapAnalysisComplete(claim.getId(), "unknown");
            return;
        }

        pipelineJobRepository.saveAll(valPjobs);
        claim.setGapState(State.VALIDATING.name());
        claimRepository.save(claim);
        log.info("[gap] EVIDENCE_GAPS → VALIDATING for claim {}", claim.getId());
    }

    // -------------------------------------------------------------------------
    // VALIDATING → WHATIF
    // -------------------------------------------------------------------------

    private void doFanoutWhatIfIfReady(Claim claim) {
        List<ClaimPipelineJob> valPjobs = pipelineJobRepository
                .findByClaimIdAndStage(claim.getId(), "gap_validation");
        if (valPjobs.isEmpty()) return;

        Set<UUID> jobIds = collectJobIds(valPjobs);
        if (!llmJobService.allTerminal(jobIds)) {
            // Still in progress — don't advance
            log.debug("[gap] VALIDATING: not all validation jobs terminal for claim {}", claim.getId());
            return;
        }
        if (!llmJobService.allSucceeded(jobIds)) {
            failGapAnalysis(claim, "gap_validation", jobIds);
            return;
        }

        String batchGroupKey = "gap_whatif_" + claim.getId();
        pipelineJobRepository.deleteByClaimIdAndStage(claim.getId(), "gap_whatif");
        List<ClaimPipelineJob> whatifPjobs = new ArrayList<>();

        for (ClaimPipelineJob vpj : valPjobs) {
            if (vpj.getConditionId() == null) continue;
            conditionRepository.findById(vpj.getConditionId()).ifPresent(cond -> {
                List<Map<String, Object>> originalGaps = cond.getGaps();
                if (originalGaps == null) originalGaps = List.of();

                LlmJobResult valResult = llmJobService.getResult(vpj.getLlmJobId()).orElseThrow();
                List<Map<String, Object>> updatedGaps = gapValidationAgent.parseResponse(valResult, originalGaps);
                // P1-6 — the validator rebuilds the gap maps; re-stamp the
                // veteran's durable statuses onto the final persisted list.
                cond.setGaps(userGapStateService.reapply(claim.getId(), cond, updatedGaps));
                conditionRepository.save(cond);
            });
        }

        // P1-9 — what-if disabled: the validated gaps are persisted (above), so
        // complete the gap analysis here instead of fanning out gap_whatif jobs
        // whose output no client renders.
        if (!whatifEnabled) {
            String modelName = llmJobRepository.findById(valPjobs.get(0).getLlmJobId())
                    .map(j -> j.getModelName())
                    .orElse("unknown");
            analysisScheduler.markGapAnalysisComplete(claim.getId(), modelName);
            log.info("[gap] VALIDATING → COMPLETE for claim {} (what-if stage disabled)", claim.getId());
            return;
        }

        // Fan out whatif jobs for the conditions validated THIS run that have
        // non-empty gaps. Mission 5b — restrict to the dirty set (conditions that
        // had a gap_validation job this run); clean carried-forward conditions
        // already have their what-if scenarios and must not be regenerated. Flag
        // OFF ⇒ the dirty set is every active condition (full fan-out, unchanged).
        Set<Long> runConditionIds = new HashSet<>();
        for (ClaimPipelineJob vpj : valPjobs) {
            if (vpj.getConditionId() != null) runConditionIds.add(vpj.getConditionId());
        }
        List<IdentifiedCondition> conditions =
                conditionRepository.findByClaimIdAndSupersededByIsNull(claim.getId());
        for (IdentifiedCondition cond : conditions) {
            if (incrementalEnabled && !runConditionIds.contains(cond.getId())) continue;
            List<Map<String, Object>> gaps = cond.getGaps();
            if (gaps == null || gaps.isEmpty()) continue;

            LlmJobRequest req = whatIfScenarioGenerator.buildRequest(cond, gaps, claim.getId(), claim.getUserId());
            // Ensure batchGroupKey is correct. WhatIf does not resend the atom corpus
            // so its structuredPrompt is null, but carry it through for uniformity with
            // the other gap stages (Mission 6b).
            req = LlmJobRequest.builder()
                    .purpose(req.getPurpose())
                    .systemPrompt(req.getSystemPrompt())
                    .userMessage(req.getUserMessage())
                    .structuredPrompt(req.getStructuredPrompt())
                    .maxTokens(req.getMaxTokens())
                    .thinkingBudget(req.getThinkingBudget())
                    .claimId(claim.getId())
                    .userId(claim.getUserId())
                    .conditionId(cond.getId())
                    .batchGroupKey(batchGroupKey)
                    .build();
            UUID jobId = llmJobService.submit(req);
            whatifPjobs.add(new ClaimPipelineJob(claim.getId(), "gap_whatif", jobId, cond.getId(), null));
        }

        if (whatifPjobs.isEmpty()) {
            log.info("[gap] VALIDATING: no validated gaps for claim {} — fast-pathing to COMPLETE", claim.getId());
            analysisScheduler.markGapAnalysisComplete(claim.getId(), "unknown");
            return;
        }

        pipelineJobRepository.saveAll(whatifPjobs);
        claim.setGapState(State.WHATIF.name());
        claimRepository.save(claim);
        log.info("[gap] VALIDATING → WHATIF for claim {}", claim.getId());
    }

    // -------------------------------------------------------------------------
    // WHATIF → COMPLETE
    // -------------------------------------------------------------------------

    private void doCompleteIfReady(Claim claim) {
        List<ClaimPipelineJob> whatifPjobs = pipelineJobRepository
                .findByClaimIdAndStage(claim.getId(), "gap_whatif");
        if (whatifPjobs.isEmpty()) return;

        Set<UUID> jobIds = collectJobIds(whatifPjobs);
        if (!llmJobService.allTerminal(jobIds)) return;
        if (!llmJobService.allSucceeded(jobIds)) {
            failGapAnalysis(claim, "gap_whatif", jobIds);
            return;
        }

        for (ClaimPipelineJob wpj : whatifPjobs) {
            if (wpj.getConditionId() == null) continue;
            conditionRepository.findById(wpj.getConditionId()).ifPresent(cond -> {
                LlmJobResult whatifResult = llmJobService.getResult(wpj.getLlmJobId()).orElseThrow();
                List<Map<String, Object>> scenarios = whatIfScenarioGenerator.parseResponse(whatifResult);
                cond.setWhatIfScenarios(scenarios);
                conditionRepository.save(cond);
            });
        }

        // Determine model name from a whatif job
        String modelName = llmJobRepository.findById(whatifPjobs.get(0).getLlmJobId())
                .map(j -> j.getModelName())
                .orElse("unknown");

        analysisScheduler.markGapAnalysisComplete(claim.getId(), modelName);
        log.info("[gap] WHATIF → COMPLETE for claim {}", claim.getId());
    }

    // -------------------------------------------------------------------------
    // Utilities
    // -------------------------------------------------------------------------

    private State parseState(String s) {
        return s == null ? State.NONE : State.valueOf(s);
    }

    private Set<UUID> collectJobIds(List<ClaimPipelineJob> pjobs) {
        Set<UUID> ids = new HashSet<>();
        for (ClaimPipelineJob pj : pjobs) ids.add(pj.getLlmJobId());
        return ids;
    }

    /**
     * Terminal failure: one or more jobs in the stage FAILED. Previously a
     * FAILED job left allSucceeded false forever, wedging the claim mid-
     * pipeline with gapAnalysisInProgress=true and no surfaced error.
     */
    private void failGapAnalysis(Claim claim, String stage, Set<UUID> jobIds) {
        String error = stage + "_failed: " + firstFailureMessage(jobIds);
        log.error("[gap] Stage {} FAILED for claim {} — {}", stage, claim.getId(), error);
        analysisScheduler.markGapAnalysisFailed(claim.getId(), error);
    }

    private String firstFailureMessage(Set<UUID> jobIds) {
        return llmJobRepository.findAllById(jobIds).stream()
                .filter(j -> j.getStatus() == com.afterduty.model.LlmJob.Status.FAILED)
                .map(j -> j.getErrorMessage())
                .filter(Objects::nonNull)
                .findFirst()
                .orElse("LLM job failed");
    }
}
