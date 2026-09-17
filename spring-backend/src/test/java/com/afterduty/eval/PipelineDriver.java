package com.afterduty.eval;

import com.afterduty.model.Atom;
import com.afterduty.model.Claim;
import com.afterduty.model.EvidenceItem;
import com.afterduty.model.IdentifiedCondition;
import com.afterduty.repository.AtomRepository;
import com.afterduty.repository.ClaimRepository;
import com.afterduty.repository.EvidenceItemRepository;
import com.afterduty.repository.IdentifiedConditionRepository;
import com.afterduty.service.extraction.ExtractionStateMachine;
import com.afterduty.service.gap.GapStateMachine;
import com.afterduty.service.llm.FakeLlmAsyncProvider;
import com.afterduty.service.llm.LlmJobPoller;
import com.afterduty.service.llm.LlmJobSubmitter;
import com.afterduty.service.synthesis.SynthesisStateMachine;

import java.util.List;

/**
 * Drives a golden case end-to-end through the REAL pipeline state machines on the
 * fake substrate (spec §2.2 PipelineDriver). Each phase runs
 * extraction (single-pass) → synthesis → gap to a terminal state, with the
 * submitter/poller ticked manually between {@code advance()} calls — exactly the
 * committed pattern from {@code SinglePassExtractionStateMachineTest} +
 * {@code IncrementalSynthesisGenerationTest} + {@code IncrementalGapCarryForwardTest}.
 *
 * <p>Bounded ticks (offline: 200) so a wedged pipeline fails loud rather than hangs.
 * Returns a {@link PipelineEndState} snapshot after each phase.
 */
public final class PipelineDriver {

    /** Max advance/tick iterations per stage before declaring the pipeline wedged. */
    public static final int OFFLINE_TICK_BUDGET = 200;

    private final ExtractionStateMachine extraction;
    private final SynthesisStateMachine synthesis;
    private final GapStateMachine gap;
    private final LlmJobSubmitter submitter;
    private final LlmJobPoller poller;
    private final ClaimRepository claimRepository;
    private final AtomRepository atomRepository;
    private final IdentifiedConditionRepository conditionRepository;
    private final EvidenceItemRepository evidenceItemRepository;
    private final FakeLlmAsyncProvider fake;

    public PipelineDriver(ExtractionStateMachine extraction,
                          SynthesisStateMachine synthesis,
                          GapStateMachine gap,
                          LlmJobSubmitter submitter,
                          LlmJobPoller poller,
                          ClaimRepository claimRepository,
                          AtomRepository atomRepository,
                          IdentifiedConditionRepository conditionRepository,
                          EvidenceItemRepository evidenceItemRepository,
                          FakeLlmAsyncProvider fake) {
        this.extraction = extraction;
        this.synthesis = synthesis;
        this.gap = gap;
        this.submitter = submitter;
        this.poller = poller;
        this.claimRepository = claimRepository;
        this.atomRepository = atomRepository;
        this.conditionRepository = conditionRepository;
        this.evidenceItemRepository = evidenceItemRepository;
        this.fake = fake;
    }

    /**
     * Run one phase to terminal and snapshot the end state. Extraction is always
     * driven; synthesis + gap run unless the extraction phase ended in a hard claim
     * ERROR (the abstention/garbage cases) — in which case the outcome is FAILED and
     * synthesis is intentionally not forced.
     */
    public PipelineEndState runPhase(Claim claim, int tickBudget) {
        runExtraction(claim, tickBudget);

        Claim afterExtract = reload(claim);
        boolean extractionErrored = afterExtract.getStatus() == Claim.ClaimStatus.ERROR;

        boolean synthesisErrored = false;
        if (!extractionErrored) {
            runSynthesis(claim, tickBudget);
            // If synthesis abstained (marked the claim ERROR — the unparseable-identify
            // path), do NOT run the gap stage: a zero-condition gap completion calls
            // markGapAnalysisComplete which clears the ERROR status, masking the
            // deliberate abstention. Stop here and report FAILED.
            synthesisErrored = reload(claim).getStatus() == Claim.ClaimStatus.ERROR;
            if (!synthesisErrored) {
                runGap(claim, tickBudget);
            }
        }

        return snapshot(claim, extractionErrored || synthesisErrored);
    }

    public PipelineEndState runPhase(Claim claim) {
        return runPhase(claim, OFFLINE_TICK_BUDGET);
    }

    // ------------------------------------------------------------------ stages

    private void runExtraction(Claim claim, int budget) {
        for (int i = 0; i < budget; i++) {
            Claim cur = reload(claim);
            // Extraction is COMPLETE when extractionState is null AFTER having started.
            if (i > 0 && cur.getExtractionState() == null) {
                return;
            }
            extraction.advance(cur);
            submitter.tick();
            poller.tick();
            Claim after = reload(claim);
            if (after.getStatus() == Claim.ClaimStatus.ERROR && after.getExtractionState() == null) {
                return; // hard extraction failure (garbage doc) — stop here.
            }
        }
        throw new IllegalStateException("extraction did not reach terminal within " + budget + " ticks");
    }

    private void runSynthesis(Claim claim, int budget) {
        Claim c = reload(claim);
        c.setSynthesisState(null);
        c.setSynthesisInProgress(false);
        claimRepository.save(c);
        for (int i = 0; i < budget; i++) {
            Claim cur = reload(claim);
            if (i > 0 && cur.getSynthesisState() == null) {
                return;
            }
            synthesis.advance(cur);
            submitter.tick();
            poller.tick();
        }
        throw new IllegalStateException("synthesis did not complete within " + budget + " ticks");
    }

    private void runGap(Claim claim, int budget) {
        Claim c = reload(claim);
        c.setGapState(null);
        c.setGapAnalysisInProgress(false);
        claimRepository.save(c);
        for (int i = 0; i < budget; i++) {
            Claim cur = reload(claim);
            if (i > 0 && cur.getGapState() == null) {
                return;
            }
            gap.advance(cur);
            submitter.tick();
            poller.tick();
        }
        throw new IllegalStateException("gap analysis did not complete within " + budget + " ticks");
    }

    // ------------------------------------------------------------------ snapshot

    private PipelineEndState snapshot(Claim claim, boolean extractionErrored) {
        Claim reloaded = reload(claim);
        List<IdentifiedCondition> active =
                conditionRepository.findByClaimIdAndSupersededByIsNull(claim.getId());
        List<IdentifiedCondition> allRows = conditionRepository.findByClaimId(claim.getId());
        List<Atom> liveAtoms = atomRepository.findByClaimIdAndSupersededByIsNull(claim.getId());
        List<EvidenceItem> evidence = evidenceItemRepository.findByClaimId(claim.getId());
        List<FakeLlmAsyncProvider.SubmittedJob> jobs = fake.submittedJobs();

        String outcome;
        if (extractionErrored || reloaded.getStatus() == Claim.ClaimStatus.ERROR) {
            outcome = PipelineEndState.OUTCOME_FAILED;
        } else if (active.isEmpty()) {
            outcome = PipelineEndState.OUTCOME_COMPLETE_ZERO;
        } else {
            outcome = PipelineEndState.OUTCOME_COMPLETE;
        }

        return new PipelineEndState(outcome, reloaded, active, allRows, liveAtoms, evidence, jobs);
    }

    private Claim reload(Claim claim) {
        return claimRepository.findById(claim.getId()).orElseThrow();
    }
}
