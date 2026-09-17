package com.afterduty.service.synthesis;

import com.afterduty.model.*;
import com.afterduty.repository.*;
import com.afterduty.service.llm.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests every state transition in {@link SynthesisStateMachine}.
 *
 * Strategy: persist a Claim + Atoms via real H2 JPA, call advance() directly,
 * run LlmJobSubmitter.tick() + LlmJobPoller.tick() to move jobs to SUCCEEDED,
 * then call advance() again and assert the observable DB state.
 *
 * FakeLlmAsyncProvider is configured with canned JSON responses that the agent's
 * parseResponse() methods will accept. The tests do NOT assert which internal
 * method was called — they assert rows in DB and state column values.
 */
@DataJpaTest
@Import({
        com.afterduty.service.llm.LlmProviderRouterTestConfig.class,
        com.afterduty.service.llm.LlmJobService.class,
        com.afterduty.service.llm.LlmJobSubmitter.class,
        com.afterduty.service.llm.LlmJobPoller.class,
        SynthesisStateMachine.class, com.afterduty.service.DomainCorrectionsService.class,
        ConditionIdentificationAgent.class,
        DuplicateConditionMerger.class,
        RatingAgent.class,
        SynthesisVerificationAgent.class,
        EnhancedSynthesisOrchestrator.class,
        ConditionGenerationService.class,
        com.afterduty.service.AnalysisScheduler.class,
        com.afterduty.service.VaMathService.class
})
@TestPropertySource(properties = {
        "va-claim.llm.submitter.batch-size=50",
        "va-claim.llm.submitter.poll-ms=9999999",
        "va-claim.llm.submitter.initial-delay-ms=9999999",
        "va-claim.llm.poller.poll-ms=9999999",
        "va-claim.llm.poller.initial-delay-ms=9999999",
        "va-claim.llm.orphan-deadline-min=30"
})
class SynthesisStateMachineTest {

    // Canned JSON responses keyed by purpose. Must be valid JSON that the
    // agent's parseResponse() implementations accept.
    static final String IDENTIFY_RESPONSE_3_CONDITIONS = """
            [
              {"name":"PTSD","vasrd_code":"9411","body_system":"mental","confidence":0.9},
              {"name":"TBI","vasrd_code":"8045","body_system":"neurological","confidence":0.8},
              {"name":"Tinnitus","vasrd_code":"6260","body_system":"ear","confidence":0.75}
            ]
            """;

    static final String IDENTIFY_RESPONSE_EMPTY = "[]";

    /** Merger keeps 3 → 2 (drops the duplicate). */
    static final String MERGER_RESPONSE_2_CONDITIONS = """
            [
              {"name":"PTSD","vasrd_code":"9411","body_system":"mental","confidence":0.9,"merged_from":[]},
              {"name":"TBI","vasrd_code":"8045","body_system":"neurological","confidence":0.8,"merged_from":["Tinnitus"]}
            ]
            """;

    static final String RATE_RESPONSE = """
            {"estimated_rating":70,"rating_rationale":"Frequent flashbacks, avoidance, hyperarousal","confidence":0.88}
            """;

    static final String VERIFY_RESPONSE = """
            [
              {"condition":"PTSD","issue":"none","action":"keep","pyramid_group":"A","pyramid_reason":"Highest rated"}
            ]
            """;

    @Autowired
    SynthesisStateMachine synthesisStateMachine;

    @Autowired
    LlmJobSubmitter llmJobSubmitter;

    @Autowired
    LlmJobPoller llmJobPoller;

    @Autowired
    ClaimRepository claimRepository;

    @Autowired
    AtomRepository atomRepository;

    @Autowired
    IdentifiedConditionRepository conditionRepository;

    @Autowired
    ClaimPipelineJobRepository pipelineJobRepository;

    @Autowired
    LlmJobRepository llmJobRepository;

    @Autowired
    FakeLlmAsyncProvider fakeProvider;

    private Claim claim;

    @BeforeEach
    void setUpClaimAndAtoms() {
        fakeProvider.reset();
        fakeProvider.setResponse("synthesis_identify", IDENTIFY_RESPONSE_3_CONDITIONS);
        fakeProvider.setResponse("synthesis_duplicate_merger", MERGER_RESPONSE_2_CONDITIONS);
        fakeProvider.setResponse("synthesis_rate", RATE_RESPONSE);
        fakeProvider.setResponse("synthesis_verify", VERIFY_RESPONSE);

        claim = Claim.builder()
                .userId(1L)
                .status(Claim.ClaimStatus.DRAFT)
                .synthesisNeeded(true)
                .build();
        claim = claimRepository.save(claim);

        // Persist at least one Atom so the identify stage has something to work with.
        Atom atom = Atom.builder()
                .claimId(claim.getId())
                .type("diagnosis")
                .value("PTSD diagnosis established")
                .source("VA examination 2023")
                .createdBy("ai:test")
                .build();
        atomRepository.save(atom);
    }

    // -------------------------------------------------------------------------
    // Scenario 7: NONE → IDENTIFYING submits identify job
    // -------------------------------------------------------------------------

    @Test
    void NONE_to_IDENTIFYING_submitsIdentifyJob() {
        assertNull(claim.getSynthesisState(), "Precondition: synthesisState must be null");

        synthesisStateMachine.advance(claim);

        Claim reloaded = claimRepository.findById(claim.getId()).orElseThrow();
        assertEquals("IDENTIFYING", reloaded.getSynthesisState(),
                "After first advance(), synthesisState must be IDENTIFYING");
        assertTrue(Boolean.TRUE.equals(reloaded.getSynthesisInProgress()),
                "synthesisInProgress must be true after first transition");

        List<ClaimPipelineJob> pjobs = pipelineJobRepository
                .findByClaimIdAndStage(claim.getId(), "synthesis_identify");
        assertEquals(1, pjobs.size(),
                "Exactly one ClaimPipelineJob row must exist at stage synthesis_identify");

        UUID llmJobId = pjobs.get(0).getLlmJobId();
        LlmJob llmJob = llmJobRepository.findById(llmJobId).orElseThrow();
        assertEquals("synthesis_identify", llmJob.getPurpose());
        assertEquals(LlmJob.Status.QUEUED, llmJob.getStatus(),
                "The LlmJob must be QUEUED immediately after submit (before submitter tick)");
    }

    // -------------------------------------------------------------------------
    // Scenario 8: IDENTIFYING waits while pending
    // -------------------------------------------------------------------------

    @Test
    void IDENTIFYING_waitsWhilePending() {
        // Force identify job to stay IN_PROGRESS.
        fakeProvider.setStatus("synthesis_identify", ProviderJobStatus.IN_PROGRESS);

        synthesisStateMachine.advance(claim); // NONE → IDENTIFYING
        llmJobSubmitter.tick();
        llmJobPoller.tick(); // poller sees IN_PROGRESS, job stays non-terminal

        int pipelineJobCountBefore = pipelineJobRepository
                .findByClaimIdAndStage(claim.getId(), "synthesis_identify").size();

        synthesisStateMachine.advance(claim); // should be a no-op

        Claim reloaded = claimRepository.findById(claim.getId()).orElseThrow();
        assertEquals("IDENTIFYING", reloaded.getSynthesisState(),
                "State must remain IDENTIFYING while the identify job is still pending");

        // No new pipeline jobs should have been created.
        int pipelineJobCountAfter = pipelineJobRepository
                .findByClaimIdAndStage(claim.getId(), "synthesis_identify").size();
        assertEquals(pipelineJobCountBefore, pipelineJobCountAfter,
                "No new ClaimPipelineJob rows must be inserted during a no-op tick");
    }

    // -------------------------------------------------------------------------
    // Scenario 9: IDENTIFYING → MERGING on success
    // -------------------------------------------------------------------------

    @Test
    void IDENTIFYING_to_MERGING_onSuccess() {
        synthesisStateMachine.advance(claim); // NONE → IDENTIFYING
        llmJobSubmitter.tick();
        llmJobPoller.tick(); // QUEUED → SUBMITTED → SUCCEEDED

        synthesisStateMachine.advance(claim); // IDENTIFYING → MERGING

        Claim reloaded = claimRepository.findById(claim.getId()).orElseThrow();
        assertEquals("MERGING", reloaded.getSynthesisState(),
                "State must advance to MERGING after identify job SUCCEEDED with 3 conditions");

        List<ClaimPipelineJob> mergerJobs = pipelineJobRepository
                .findByClaimIdAndStage(claim.getId(), "synthesis_duplicate_merger");
        assertEquals(1, mergerJobs.size(),
                "Exactly one ClaimPipelineJob row must exist at stage synthesis_duplicate_merger");

        UUID mergerJobId = mergerJobs.get(0).getLlmJobId();
        LlmJob mergerLlmJob = llmJobRepository.findById(mergerJobId).orElseThrow();
        assertEquals("synthesis_duplicate_merger", mergerLlmJob.getPurpose());
    }

    // -------------------------------------------------------------------------
    // Scenario 10: MERGING → RATING persists conditions and fans out
    // -------------------------------------------------------------------------

    @Test
    void MERGING_to_RATING_persistsConditionsAndFansOut() {
        // Walk from NONE → MERGING.
        synthesisStateMachine.advance(claim);
        llmJobSubmitter.tick();
        llmJobPoller.tick();
        synthesisStateMachine.advance(claim); // → MERGING
        llmJobSubmitter.tick();
        llmJobPoller.tick();

        synthesisStateMachine.advance(claim); // MERGING → RATING

        Claim reloaded = claimRepository.findById(claim.getId()).orElseThrow();
        assertEquals("RATING", reloaded.getSynthesisState(),
                "State must advance to RATING after merger SUCCEEDED with 2 conditions");

        // 2 IdentifiedCondition rows must have been persisted (one per merged condition).
        List<IdentifiedCondition> conditions = conditionRepository.findByClaimId(claim.getId());
        assertEquals(2, conditions.size(),
                "After MERGING → RATING transition, exactly 2 IdentifiedCondition rows must exist");

        // 2 synthesis_rate ClaimPipelineJob rows (fan-out, one per condition).
        List<ClaimPipelineJob> ratePjobs = pipelineJobRepository
                .findByClaimIdAndStage(claim.getId(), "synthesis_rate");
        assertEquals(2, ratePjobs.size(),
                "Exactly 2 ClaimPipelineJob rows must be created at stage synthesis_rate (one per condition)");

        // Each rate pipeline job must have a conditionId set.
        for (ClaimPipelineJob rpj : ratePjobs) {
            assertNotNull(rpj.getConditionId(),
                    "Every synthesis_rate ClaimPipelineJob row must carry a conditionId");
        }

        // Both rate jobs must share the same batchGroupKey = "synthesis_rate_<claimId>".
        String expectedGroupKey = "synthesis_rate_" + claim.getId();
        List<FakeLlmAsyncProvider.SubmittedJob> rateSubmissions = fakeProvider.submittedJobs().stream()
                .filter(j -> "synthesis_rate".equals(j.purpose()))
                .toList();
        assertEquals(2, rateSubmissions.size());
        for (FakeLlmAsyncProvider.SubmittedJob sj : rateSubmissions) {
            assertEquals(expectedGroupKey, sj.batchGroupKey(),
                    "synthesis_rate jobs must share batchGroupKey = synthesis_rate_<claimId>");
        }
    }

    // -------------------------------------------------------------------------
    // Scenario 11: RATING waits for all rate jobs; when all done, parses + verifies
    // -------------------------------------------------------------------------

    @Test
    void RATING_waitsForAllRateJobs_thenParsesAndSubmitsVerify() {
        // Walk from NONE → RATING.
        synthesisStateMachine.advance(claim);
        llmJobSubmitter.tick();
        llmJobPoller.tick();
        synthesisStateMachine.advance(claim);
        llmJobSubmitter.tick();
        llmJobPoller.tick();
        synthesisStateMachine.advance(claim); // → RATING

        // Force exactly one rate job to stay IN_PROGRESS.
        fakeProvider.setStatus("synthesis_rate", ProviderJobStatus.IN_PROGRESS);
        llmJobSubmitter.tick();
        llmJobPoller.tick();

        // advance() while one job is still pending must be a no-op.
        synthesisStateMachine.advance(claim);
        Claim midCheck = claimRepository.findById(claim.getId()).orElseThrow();
        assertEquals("RATING", midCheck.getSynthesisState(),
                "State must remain RATING while not all synthesis_rate jobs are SUCCEEDED");

        // Now let all rate jobs complete.
        fakeProvider.clearStatus("synthesis_rate");
        llmJobPoller.tick();

        synthesisStateMachine.advance(claim); // RATING → VERIFYING

        Claim reloaded = claimRepository.findById(claim.getId()).orElseThrow();
        assertEquals("VERIFYING", reloaded.getSynthesisState(),
                "State must advance to VERIFYING once all rate jobs succeed");

        // All IdentifiedCondition rows must have estimatedRating updated from rate response.
        List<IdentifiedCondition> conditions = conditionRepository.findByClaimId(claim.getId());
        for (IdentifiedCondition c : conditions) {
            assertEquals(70, c.getEstimatedRating(),
                    "estimatedRating must be populated from the rate LlmJobResult");
        }

        // One synthesis_verify job must be queued.
        List<ClaimPipelineJob> verifyPjobs = pipelineJobRepository
                .findByClaimIdAndStage(claim.getId(), "synthesis_verify");
        assertEquals(1, verifyPjobs.size(),
                "Exactly one synthesis_verify ClaimPipelineJob row must exist after RATING → VERIFYING");
    }

    // -------------------------------------------------------------------------
    // Scenario 12: VERIFYING → COMPLETE applies corrections + marks complete
    // -------------------------------------------------------------------------

    @Test
    void VERIFYING_to_COMPLETE_appliesCorrectionsAndMarksComplete() {
        // Walk from NONE → VERIFYING.
        synthesisStateMachine.advance(claim);
        llmJobSubmitter.tick(); llmJobPoller.tick();
        synthesisStateMachine.advance(claim);
        llmJobSubmitter.tick(); llmJobPoller.tick();
        synthesisStateMachine.advance(claim);
        llmJobSubmitter.tick(); llmJobPoller.tick();
        synthesisStateMachine.advance(claim); // → VERIFYING
        llmJobSubmitter.tick(); llmJobPoller.tick();

        synthesisStateMachine.advance(claim); // VERIFYING → COMPLETE

        Claim reloaded = claimRepository.findById(claim.getId()).orElseThrow();
        // markSynthesisComplete must set synthesisState to null (or state machine does it directly).
        assertNull(reloaded.getSynthesisState(),
                "After COMPLETE transition, synthesisState must be null");
        assertNotNull(reloaded.getLastSynthesisAt(),
                "lastSynthesisAt must be set when synthesis completes");
        assertFalse(Boolean.TRUE.equals(reloaded.getSynthesisInProgress()),
                "synthesisInProgress must be false after markSynthesisComplete");
    }

    // -------------------------------------------------------------------------
    // Scenario 13a: an UNPARSEABLE identify response is a FAILURE, not a
    // silent zero-conditions COMPLETE (the worst veteran-harm bug: a parse
    // failure must never tell a veteran with records "no conditions found").
    // -------------------------------------------------------------------------

    @Test
    void identifyUnparseable_marksSynthesisFailed() {
        fakeProvider.setResponse("synthesis_identify", "I could not produce JSON {{{");

        synthesisStateMachine.advance(claim); // NONE → IDENTIFYING
        llmJobSubmitter.tick();
        llmJobPoller.tick();

        synthesisStateMachine.advance(claim); // IDENTIFYING: unparseable → FAILED

        Claim reloaded = claimRepository.findById(claim.getId()).orElseThrow();
        assertNull(reloaded.getSynthesisState(),
                "Failed run must clear synthesisState so the claim is not wedged");
        assertFalse(Boolean.TRUE.equals(reloaded.getSynthesisInProgress()),
                "synthesisInProgress must drop on failure");
        assertEquals(Claim.ClaimStatus.ERROR, reloaded.getStatus(),
                "Claim must surface the failure via status=ERROR");
        assertNotNull(reloaded.getAnalysisMessage(), "pipelineError must be recorded");
        assertTrue(reloaded.getAnalysisMessage().contains("identify_unparseable"),
                "analysisMessage must carry the identify_unparseable pipeline error, got: "
                        + reloaded.getAnalysisMessage());
        assertNotNull(reloaded.getLastSynthesisAt(),
                "lastSynthesisAt must be stamped so the failed run does not immediately re-trigger");
        assertNotNull(reloaded.getLastGapAnalysisAt(),
                "lastGapAnalysisAt must be stamped so a failed synthesis never triggers a paid gap run on stale conditions");

        // No downstream jobs and no conditions.
        assertTrue(pipelineJobRepository.findByClaimIdAndStage(claim.getId(), "synthesis_duplicate_merger").isEmpty(),
                "No merger jobs must be submitted when identify is unparseable");
        assertEquals(0, conditionRepository.findByClaimId(claim.getId()).size(),
                "No IdentifiedCondition rows must be created when identify is unparseable");
    }

    // -------------------------------------------------------------------------
    // Scenario 13a': a successfully parsed EMPTY array is a legitimate answer
    // even when atoms exist (e.g. non-medical uploads) — completes empty,
    // never status=ERROR.
    // -------------------------------------------------------------------------

    @Test
    void identifyEmpty_withAtoms_completesEmptyWithoutError() {
        fakeProvider.setResponse("synthesis_identify", IDENTIFY_RESPONSE_EMPTY);

        synthesisStateMachine.advance(claim); // NONE → IDENTIFYING
        llmJobSubmitter.tick();
        llmJobPoller.tick();

        synthesisStateMachine.advance(claim); // IDENTIFYING: parsed [] → COMPLETE

        Claim reloaded = claimRepository.findById(claim.getId()).orElseThrow();
        assertNull(reloaded.getSynthesisState(),
                "Legitimate empty identify must complete, not wedge");
        assertNotEquals(Claim.ClaimStatus.ERROR, reloaded.getStatus(),
                "A parsed empty array is a real answer, not an error");
        assertNotNull(reloaded.getLastSynthesisAt(),
                "lastSynthesisAt must be set on the empty fast-path");
        assertTrue(pipelineJobRepository.findByClaimIdAndStage(claim.getId(), "synthesis_duplicate_merger").isEmpty(),
                "No merger jobs must be submitted when identify returns empty");
    }

    // -------------------------------------------------------------------------
    // Scenario 13b: empty identify on a claim with NO atoms legitimately
    // completes empty (nothing to synthesize).
    // -------------------------------------------------------------------------

    @Test
    void identifyEmpty_withNoAtoms_skipsToComplete() {
        fakeProvider.setResponse("synthesis_identify", IDENTIFY_RESPONSE_EMPTY);
        atomRepository.deleteAll();

        synthesisStateMachine.advance(claim); // NONE → IDENTIFYING
        llmJobSubmitter.tick();
        llmJobPoller.tick();

        synthesisStateMachine.advance(claim); // IDENTIFYING: [] with no atoms → COMPLETE

        Claim reloaded = claimRepository.findById(claim.getId()).orElseThrow();
        assertNull(reloaded.getSynthesisState(),
                "When identify returns [] and the claim has no atoms, synthesis completes empty");
        assertNotEquals(Claim.ClaimStatus.ERROR, reloaded.getStatus(),
                "Legitimate empty completion must NOT mark the claim ERROR");
        assertNotNull(reloaded.getLastSynthesisAt(),
                "lastSynthesisAt must be set on the empty fast-path");
        assertTrue(pipelineJobRepository.findByClaimIdAndStage(claim.getId(), "synthesis_duplicate_merger").isEmpty(),
                "No merger jobs must be submitted when identify returns empty");
    }

    // -------------------------------------------------------------------------
    // Scenario 13c: a FAILED identify job marks the run FAILED with the error
    // recorded instead of wedging the claim in IDENTIFYING forever.
    // -------------------------------------------------------------------------

    @Test
    void identifyJobFailed_marksSynthesisFailedInsteadOfWedging() {
        fakeProvider.setFailure("synthesis_identify", "Simulated provider failure");

        synthesisStateMachine.advance(claim); // NONE → IDENTIFYING
        llmJobSubmitter.tick();
        llmJobPoller.tick(); // job lands as FAILED

        synthesisStateMachine.advance(claim); // IDENTIFYING: all terminal, not all succeeded → FAILED

        Claim reloaded = claimRepository.findById(claim.getId()).orElseThrow();
        assertNull(reloaded.getSynthesisState(),
                "Failed run must clear synthesisState so the claim is not wedged");
        assertFalse(Boolean.TRUE.equals(reloaded.getSynthesisInProgress()),
                "synthesisInProgress must drop on failure");
        assertEquals(Claim.ClaimStatus.ERROR, reloaded.getStatus(),
                "Claim must surface the failure via status=ERROR");
        assertNotNull(reloaded.getAnalysisMessage(), "pipelineError must be recorded");
        assertTrue(reloaded.getAnalysisMessage().contains("synthesis_identify_failed"),
                "analysisMessage must name the failed stage, got: " + reloaded.getAnalysisMessage());
        assertTrue(reloaded.getAnalysisMessage().contains("Simulated provider failure"),
                "analysisMessage must carry the provider error, got: " + reloaded.getAnalysisMessage());
    }

    // -------------------------------------------------------------------------
    // Scenario 13d: a re-run's doIdentify clears the prior run's stale
    // ClaimPipelineJob rows so stage readers never parse old results.
    // -------------------------------------------------------------------------

    @Test
    void reRun_deletesStaleIdentifyPipelineJobs() {
        // Simulate a leftover row from a previous run.
        pipelineJobRepository.save(new ClaimPipelineJob(
                claim.getId(), "synthesis_identify", UUID.randomUUID(), null, null));
        assertEquals(1, pipelineJobRepository
                .findByClaimIdAndStage(claim.getId(), "synthesis_identify").size());

        synthesisStateMachine.advance(claim); // NONE → IDENTIFYING (new run)

        List<ClaimPipelineJob> pjobs = pipelineJobRepository
                .findByClaimIdAndStage(claim.getId(), "synthesis_identify");
        assertEquals(1, pjobs.size(),
                "The new run must replace — not append to — prior-run synthesis_identify rows");
        assertNotNull(llmJobRepository.findById(pjobs.get(0).getLlmJobId()).orElse(null),
                "The surviving row must point at this run's real LlmJob (the stale row is gone)");
    }
}
