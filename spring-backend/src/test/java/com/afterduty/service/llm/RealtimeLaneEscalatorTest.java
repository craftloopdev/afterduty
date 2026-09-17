package com.afterduty.service.llm;

import com.afterduty.model.Claim;
import com.afterduty.model.ClaimPipelineJob;
import com.afterduty.model.IdentifiedCondition;
import com.afterduty.model.LlmJob;
import com.afterduty.repository.ClaimPipelineJobRepository;
import com.afterduty.repository.ClaimRepository;
import com.afterduty.repository.IdentifiedConditionRepository;
import com.afterduty.repository.LlmJobRepository;
import com.afterduty.service.synthesis.ConditionGenerationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase B item B2, report §5 item 3 — the ≤3-dirty realtime lane rule. A small
 * incremental RE-RUN's rate/verify/gap jobs must never ride {@code anthropic-batch}
 * (a nexus-letter upload must not vanish into a 24h SLA); the full-run path keeps
 * its configured lanes.
 *
 * <p>Unit-tests {@link RealtimeLaneEscalator}'s decision against real H2 state
 * (conditions with generation markers + synthesis_rate pipeline rows — the exact
 * committed signals production reads), then proves the {@link LlmJobSubmitter}
 * wiring end-to-end: a QUEUED batch job on a small re-run is submitted with its
 * provider rewritten to the realtime lane.
 */
@DataJpaTest
@Import({
        LlmProviderRouterTestConfig.class,
        LlmJobService.class,
        LlmJobSubmitter.class,
        RealtimeLaneEscalator.class
})
@TestPropertySource(properties = {
        "va-claim.llm.submitter.batch-size=50",
        "va-claim.llm.submitter.poll-ms=9999999",
        "va-claim.llm.submitter.initial-delay-ms=9999999",
        "va-claim.llm.realtime-dirty-threshold=3"
})
class RealtimeLaneEscalatorTest {

    @Autowired RealtimeLaneEscalator escalator;
    @Autowired LlmJobSubmitter submitter;
    @Autowired ClaimRepository claimRepository;
    @Autowired IdentifiedConditionRepository conditionRepository;
    @Autowired ClaimPipelineJobRepository pipelineJobRepository;
    @Autowired LlmJobRepository llmJobRepository;

    private Long claimId;

    @BeforeEach
    void setUp() {
        claimId = claimRepository.save(Claim.builder().userId(1L)
                .status(Claim.ClaimStatus.DRAFT).build()).getId();
    }

    // ------------------------------------------------------------------ helpers

    private void saveCondition(String name, Long supersededBy) {
        IdentifiedCondition c = new IdentifiedCondition();
        c.setClaimId(claimId);
        c.setName(name);
        c.setSupersededBy(supersededBy);
        conditionRepository.save(c);
    }

    /** Mid-RATING re-run shape: an active prior generation + a pending new one. */
    private void seedRerunMidRating() {
        saveCondition("PTSD gen1", null);
        saveCondition("Tinnitus gen1", null);
        saveCondition("PTSD gen2", ConditionGenerationService.PENDING_MARKER);
        saveCondition("Tinnitus gen2", ConditionGenerationService.PENDING_MARKER);
    }

    /** Post-flip (gap window) re-run shape: real superseded pointers, no pending. */
    private void seedRerunPostFlip() {
        saveCondition("PTSD gen2", null);
        saveCondition("Tinnitus gen2", null);
        IdentifiedCondition old = new IdentifiedCondition();
        old.setClaimId(claimId);
        old.setName("PTSD gen1");
        old.setSupersededBy(42L); // real replacement pointer
        conditionRepository.save(old);
    }

    private void seedRateRows(int dirtyCount) {
        for (int i = 0; i < dirtyCount; i++) {
            pipelineJobRepository.save(new ClaimPipelineJob(
                    claimId, "synthesis_rate", UUID.randomUUID(), (long) (100 + i), null));
        }
    }

    private LlmJob batchJob(String purpose) {
        return LlmJob.builder()
                .provider(AnthropicBatchProvider.NAME)
                .modelName("claude-sonnet-4-6")
                .purpose(purpose)
                .status(LlmJob.Status.QUEUED)
                .claimId(claimId)
                .requestPayload("{}")
                .build();
    }

    // ------------------------------------------------------------------ decision

    @Test
    void smallRerun_midRating_escalatesRateAndVerifyJobs() {
        seedRerunMidRating();
        seedRateRows(2); // 2 dirty ≤ 3

        LlmJob rate = batchJob("synthesis_rate");
        assertTrue(escalator.maybeEscalate(rate), "≤3-dirty re-run rate job escalates");
        assertEquals(VertexAnthropicProvider.NAME, rate.getProvider(),
                "escalation moves the job onto the realtime Claude lane");
        assertEquals("claude-sonnet-4-6", rate.getModelName(),
                "the model is unchanged — this is a lane change, not a model change");

        LlmJob verify = batchJob("synthesis_verify");
        assertTrue(escalator.maybeEscalate(verify), "the run's verify job rides realtime too");
    }

    @Test
    void smallRerun_postFlip_escalatesGapJobs() {
        seedRerunPostFlip();
        seedRateRows(1); // last synthesis run's rate rows persist through the gap stage

        for (String purpose : new String[]{"gap_evidence", "gap_validation", "gap_whatif"}) {
            LlmJob gap = batchJob(purpose);
            assertTrue(escalator.maybeEscalate(gap), purpose + " escalates on a small re-run");
            assertEquals(VertexAnthropicProvider.NAME, gap.getProvider());
        }
    }

    @Test
    void allCleanRerun_zeroDirty_escalatesVerify() {
        seedRerunMidRating();
        // No rate rows at all: every condition carried forward — the cheapest re-run.
        LlmJob verify = batchJob("synthesis_verify");
        assertTrue(escalator.maybeEscalate(verify), "0 dirty ≤ 3 — the verify hop stays realtime");
    }

    @Test
    void largeRerun_keepsConfiguredBatchLane() {
        seedRerunMidRating();
        seedRateRows(4); // > threshold

        LlmJob rate = batchJob("synthesis_rate");
        assertFalse(escalator.maybeEscalate(rate), ">3-dirty re-runs keep their configured lanes");
        assertEquals(AnthropicBatchProvider.NAME, rate.getProvider());
    }

    @Test
    void firstRun_keepsConfiguredBatchLane() {
        // First-ever analysis: only a pending generation, no active prior, no
        // superseded rows — the FULL-run path, even when small.
        saveCondition("PTSD gen1", ConditionGenerationService.PENDING_MARKER);
        saveCondition("Tinnitus gen1", ConditionGenerationService.PENDING_MARKER);
        seedRateRows(2);

        LlmJob rate = batchJob("synthesis_rate");
        assertFalse(escalator.maybeEscalate(rate), "the full-run path keeps its current lanes");
        assertEquals(AnthropicBatchProvider.NAME, rate.getProvider());
    }

    @Test
    void nonBatchProvider_otherPurposes_andNullClaim_areUntouched() {
        seedRerunMidRating();
        seedRateRows(1);

        LlmJob alreadyRealtime = batchJob("synthesis_rate");
        alreadyRealtime.setProvider(VertexAnthropicProvider.NAME);
        assertFalse(escalator.maybeEscalate(alreadyRealtime), "already-realtime jobs are untouched");

        LlmJob extraction = batchJob("extraction_doc");
        assertFalse(escalator.maybeEscalate(extraction), "non-rate/verify/gap purposes are untouched");
        assertEquals(AnthropicBatchProvider.NAME, extraction.getProvider());

        LlmJob noClaim = LlmJob.builder()
                .provider(AnthropicBatchProvider.NAME)
                .purpose("synthesis_rate")
                .status(LlmJob.Status.QUEUED)
                .requestPayload("{}")
                .build();
        assertFalse(escalator.maybeEscalate(noClaim), "a claimless job cannot be scoped — untouched");
    }

    @Test
    void thresholdZero_disablesEscalation() {
        seedRerunMidRating();
        seedRateRows(1);
        escalator.setDirtyThreshold(0);
        try {
            assertFalse(escalator.maybeEscalate(batchJob("synthesis_rate")),
                    "REALTIME_DIRTY_THRESHOLD=0 is the rollback lever");
        } finally {
            escalator.setDirtyThreshold(3);
        }
    }

    // ------------------------------------------------------------------ wiring

    @Test
    void submitterTick_rewritesLaneBeforeSubmission_andPersistsIt() {
        seedRerunMidRating();
        seedRateRows(1);

        LlmJob queued = llmJobRepository.save(batchJob("synthesis_rate"));

        submitter.tick();

        LlmJob after = llmJobRepository.findById(queued.getId()).orElseThrow();
        assertEquals(VertexAnthropicProvider.NAME, after.getProvider(),
                "the submitter consults the escalator before grouping and persists the new lane");
        assertEquals(LlmJob.Status.SUBMITTED, after.getStatus(),
                "the job was submitted on the escalated lane in the same tick");
    }
}
