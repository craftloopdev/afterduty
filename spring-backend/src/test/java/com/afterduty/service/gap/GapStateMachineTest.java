package com.afterduty.service.gap;

import com.afterduty.model.*;
import com.afterduty.repository.*;
import com.afterduty.service.llm.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests every state transition in {@link GapStateMachine}.
 *
 * Strategy mirrors SynthesisStateMachineTest: real H2 JPA, FakeLlmAsyncProvider
 * configured with canned JSON, and direct calls to advance() with
 * LlmJobSubmitter/Poller ticks in between.
 */
@DataJpaTest
@Import({
        com.afterduty.service.llm.LlmProviderRouterTestConfig.class,
        com.afterduty.service.llm.LlmJobService.class,
        com.afterduty.service.llm.LlmJobSubmitter.class,
        com.afterduty.service.llm.LlmJobPoller.class,
        GapStateMachine.class,
        EvidenceGapAnalyzer.class,
        GapValidationAgent.class,
        WhatIfScenarioGenerator.class,
        UserGapStateService.class,
        com.afterduty.service.synthesis.ConditionGenerationService.class,
        com.afterduty.service.AnalysisScheduler.class
})
@TestPropertySource(properties = {
        "va-claim.llm.submitter.batch-size=50",
        "va-claim.llm.submitter.poll-ms=9999999",
        "va-claim.llm.submitter.initial-delay-ms=9999999",
        "va-claim.llm.poller.poll-ms=9999999",
        "va-claim.llm.poller.initial-delay-ms=9999999",
        "va-claim.llm.orphan-deadline-min=30",
        // P1-9 — the what-if stage defaults OFF in prod (rendered nowhere); this
        // suite exercises it, so turn it on explicitly.
        "va-claim.gap.whatif-enabled=true"
})
class GapStateMachineTest {

    static final String GAP_EVIDENCE_RESPONSE = """
            [
              {"gap":"No nexus letter","severity":"high","recommendation":"Obtain IMO"},
              {"gap":"Missing buddy statements","severity":"medium","recommendation":"Collect statements"}
            ]
            """;

    static final String GAP_VALIDATION_RESPONSE = """
            [
              {"gap":"No nexus letter","severity":"high","recommendation":"Obtain IMO","verdict":"keep"},
              {"gap":"Missing buddy statements","severity":"medium","recommendation":"Collect statements","verdict":"keep"}
            ]
            """;

    static final String GAP_WHATIF_RESPONSE = """
            [
              {"scenario":"If nexus letter obtained, rating could increase to 70%","probability":"high"},
              {"scenario":"Buddy statements could strengthen credibility","probability":"medium"}
            ]
            """;

    @Autowired
    GapStateMachine gapStateMachine;

    @Autowired
    LlmJobSubmitter llmJobSubmitter;

    @Autowired
    LlmJobPoller llmJobPoller;

    @Autowired
    ClaimRepository claimRepository;

    @Autowired
    IdentifiedConditionRepository conditionRepository;

    @Autowired
    AtomRepository atomRepository;

    @Autowired
    ClaimPipelineJobRepository pipelineJobRepository;

    @Autowired
    LlmJobRepository llmJobRepository;

    @Autowired
    FakeLlmAsyncProvider fakeProvider;

    private Claim claim;
    private IdentifiedCondition cond1;
    private IdentifiedCondition cond2;

    @BeforeEach
    void setUp() {
        fakeProvider.reset();
        fakeProvider.setResponse("gap_evidence", GAP_EVIDENCE_RESPONSE);
        fakeProvider.setResponse("gap_validation", GAP_VALIDATION_RESPONSE);
        fakeProvider.setResponse("gap_whatif", GAP_WHATIF_RESPONSE);

        claim = Claim.builder()
                .userId(1L)
                .status(Claim.ClaimStatus.ANALYZED)
                .build();
        claim = claimRepository.save(claim);

        Atom atom = Atom.builder()
                .claimId(claim.getId())
                .type("diagnosis")
                .value("PTSD diagnosed")
                .source("VA exam")
                .createdBy("ai:test")
                .build();
        atomRepository.save(atom);

        cond1 = conditionRepository.save(IdentifiedCondition.builder()
                .claimId(claim.getId())
                .name("PTSD")
                .vasrdCode("9411")
                .estimatedRating(50)
                .build());

        cond2 = conditionRepository.save(IdentifiedCondition.builder()
                .claimId(claim.getId())
                .name("TBI")
                .vasrdCode("8045")
                .estimatedRating(10)
                .build());
    }

    // -------------------------------------------------------------------------
    // Scenario 14: NONE fans out one gap_evidence job per condition
    // -------------------------------------------------------------------------

    @Test
    void NONE_fansOutEvidenceJobsPerCondition() {
        assertNull(claim.getGapState(), "Precondition: gapState must be null");

        gapStateMachine.advance(claim);

        Claim reloaded = claimRepository.findById(claim.getId()).orElseThrow();
        assertEquals("EVIDENCE_GAPS", reloaded.getGapState(),
                "After first advance(), gapState must be EVIDENCE_GAPS");

        List<ClaimPipelineJob> pjobs = pipelineJobRepository
                .findByClaimIdAndStage(claim.getId(), "gap_evidence");
        assertEquals(2, pjobs.size(),
                "Exactly 2 ClaimPipelineJob rows must be created (one per condition)");

        // Each must carry the corresponding conditionId.
        for (ClaimPipelineJob pj : pjobs) {
            assertNotNull(pj.getConditionId(),
                    "Each gap_evidence pipeline job must carry a conditionId");
        }

        // Verify the submitted jobs have purpose=gap_evidence and correct batchGroupKey.
        List<FakeLlmAsyncProvider.SubmittedJob> submitted = fakeProvider.submittedJobs().stream()
                .filter(j -> "gap_evidence".equals(j.purpose()))
                .toList();
        assertEquals(2, submitted.size());
        String expectedGroupKey = "gap_evidence_" + claim.getId();
        for (FakeLlmAsyncProvider.SubmittedJob sj : submitted) {
            assertEquals(expectedGroupKey, sj.batchGroupKey(),
                    "gap_evidence jobs must share batchGroupKey = gap_evidence_<claimId>");
        }
    }

    // -------------------------------------------------------------------------
    // Scenario 15: EVIDENCE_GAPS → VALIDATING persists gap arrays
    // -------------------------------------------------------------------------

    @Test
    void EVIDENCE_GAPS_to_VALIDATING_persistsGaps() {
        gapStateMachine.advance(claim); // NONE → EVIDENCE_GAPS
        llmJobSubmitter.tick();
        llmJobPoller.tick();

        gapStateMachine.advance(claim); // EVIDENCE_GAPS → VALIDATING

        Claim reloaded = claimRepository.findById(claim.getId()).orElseThrow();
        assertEquals("VALIDATING", reloaded.getGapState(),
                "State must advance to VALIDATING after all gap_evidence jobs succeed");

        // Both conditions must have their gaps populated.
        List<IdentifiedCondition> conditions = conditionRepository.findByClaimId(claim.getId());
        for (IdentifiedCondition c : conditions) {
            assertNotNull(c.getGaps(),
                    "Each condition must have its gaps field populated after EVIDENCE_GAPS stage");
            assertFalse(c.getGaps().isEmpty(),
                    "Parsed gaps must be non-empty (canned response contains 2 gaps)");
        }

        // 2 gap_validation pipeline jobs must be submitted.
        List<ClaimPipelineJob> valJobs = pipelineJobRepository
                .findByClaimIdAndStage(claim.getId(), "gap_validation");
        assertEquals(2, valJobs.size(),
                "2 gap_validation pipeline jobs must be submitted after EVIDENCE_GAPS → VALIDATING");
    }

    // -------------------------------------------------------------------------
    // Scenario 16: VALIDATING → WHATIF applies verdicts
    // -------------------------------------------------------------------------

    @Test
    void VALIDATING_to_WHATIF_appliesVerdicts() {
        gapStateMachine.advance(claim);
        llmJobSubmitter.tick(); llmJobPoller.tick();
        gapStateMachine.advance(claim); // → VALIDATING
        llmJobSubmitter.tick(); llmJobPoller.tick();

        gapStateMachine.advance(claim); // VALIDATING → WHATIF

        Claim reloaded = claimRepository.findById(claim.getId()).orElseThrow();
        assertEquals("WHATIF", reloaded.getGapState(),
                "State must advance to WHATIF after all gap_validation jobs succeed");

        // 2 gap_whatif pipeline jobs must be submitted (one per condition with non-empty gaps).
        List<ClaimPipelineJob> whatifJobs = pipelineJobRepository
                .findByClaimIdAndStage(claim.getId(), "gap_whatif");
        assertEquals(2, whatifJobs.size(),
                "2 gap_whatif pipeline jobs must be submitted after VALIDATING → WHATIF");

        String expectedGroupKey = "gap_whatif_" + claim.getId();
        List<FakeLlmAsyncProvider.SubmittedJob> submitted = fakeProvider.submittedJobs().stream()
                .filter(j -> "gap_whatif".equals(j.purpose()))
                .toList();
        for (FakeLlmAsyncProvider.SubmittedJob sj : submitted) {
            assertEquals(expectedGroupKey, sj.batchGroupKey(),
                    "gap_whatif jobs must share batchGroupKey = gap_whatif_<claimId>");
        }
    }

    // -------------------------------------------------------------------------
    // Scenario 17: WHATIF → COMPLETE saves scenarios + marks complete
    // -------------------------------------------------------------------------

    @Test
    void WHATIF_to_COMPLETE_savesScenariosAndMarksComplete() {
        gapStateMachine.advance(claim);
        llmJobSubmitter.tick(); llmJobPoller.tick();
        gapStateMachine.advance(claim);
        llmJobSubmitter.tick(); llmJobPoller.tick();
        gapStateMachine.advance(claim); // → WHATIF
        llmJobSubmitter.tick(); llmJobPoller.tick();

        gapStateMachine.advance(claim); // WHATIF → COMPLETE

        Claim reloaded = claimRepository.findById(claim.getId()).orElseThrow();
        assertNull(reloaded.getGapState(),
                "gapState must be null after gap analysis is COMPLETE");
        assertNotNull(reloaded.getLastGapAnalysisAt(),
                "lastGapAnalysisAt must be set when gap analysis completes");
        assertFalse(Boolean.TRUE.equals(reloaded.getGapAnalysisInProgress()),
                "gapAnalysisInProgress must be false after markGapAnalysisComplete");

        // Both conditions must have whatIfScenarios populated.
        List<IdentifiedCondition> conditions = conditionRepository.findByClaimId(claim.getId());
        for (IdentifiedCondition c : conditions) {
            assertNotNull(c.getWhatIfScenarios(),
                    "Each condition must have whatIfScenarios populated after WHATIF stage");
            assertFalse(c.getWhatIfScenarios().isEmpty(),
                    "Parsed whatIfScenarios must be non-empty (canned response contains 2 scenarios)");
        }
    }

    // -------------------------------------------------------------------------
    // Scenario 18: no conditions → immediately complete, no LLM jobs
    // -------------------------------------------------------------------------

    @Test
    void noConditions_immediatelyComplete() {
        // Remove the conditions set up in @BeforeEach.
        conditionRepository.deleteAll();

        gapStateMachine.advance(claim);

        // No gap pipeline jobs of any kind should be submitted.
        assertTrue(pipelineJobRepository.findByClaimIdAndStage(claim.getId(), "gap_evidence").isEmpty(),
                "No gap_evidence jobs must be submitted when there are no conditions");
        assertTrue(pipelineJobRepository.findByClaimIdAndStage(claim.getId(), "gap_validation").isEmpty());
        assertTrue(pipelineJobRepository.findByClaimIdAndStage(claim.getId(), "gap_whatif").isEmpty());
        assertTrue(fakeProvider.submittedJobs().isEmpty(),
                "FakeLlmAsyncProvider must have zero submitted jobs when conditions list is empty");

        Claim reloaded = claimRepository.findById(claim.getId()).orElseThrow();
        assertNull(reloaded.getGapState(),
                "gapState must be null (immediately complete) when no conditions exist");
        assertNotNull(reloaded.getLastGapAnalysisAt(),
                "lastGapAnalysisAt must be set even for the empty-conditions fast-path");
    }

    // -------------------------------------------------------------------------
    // Scenario 19: partial fan-out failure — the run is marked FAILED with the
    // error recorded instead of wedging in VALIDATING forever. (A FAILED job is
    // terminal: nothing retries it, so "wait for allSucceeded" could never win.)
    // -------------------------------------------------------------------------

    @Test
    void partialFanoutFailure_marksGapAnalysisFailed() {
        gapStateMachine.advance(claim);
        llmJobSubmitter.tick(); llmJobPoller.tick();
        gapStateMachine.advance(claim); // → VALIDATING

        List<ClaimPipelineJob> valJobs = pipelineJobRepository
                .findByClaimIdAndStage(claim.getId(), "gap_validation");
        assertFalse(valJobs.isEmpty(), "Precondition: gap_validation pipeline jobs must exist");

        // Submit the validation jobs normally, then manually flip the first one
        // to FAILED (terminal) to simulate a partial provider failure.
        llmJobSubmitter.tick();
        llmJobPoller.tick();
        LlmJob firstValJob = llmJobRepository.findById(valJobs.get(0).getLlmJobId()).orElseThrow();
        firstValJob.setStatus(LlmJob.Status.FAILED);
        firstValJob.setErrorMessage("Simulated provider failure");
        llmJobRepository.save(firstValJob);

        gapStateMachine.advance(claim); // all terminal, not all succeeded → FAILED

        Claim reloaded = claimRepository.findById(claim.getId()).orElseThrow();
        assertNull(reloaded.getGapState(),
                "Failed run must clear gapState so the claim is not wedged");
        assertFalse(Boolean.TRUE.equals(reloaded.getGapAnalysisInProgress()),
                "gapAnalysisInProgress must drop on failure");
        assertEquals(Claim.ClaimStatus.ERROR, reloaded.getStatus(),
                "Claim must surface the failure via status=ERROR");
        assertNotNull(reloaded.getAnalysisMessage(), "pipelineError must be recorded");
        assertTrue(reloaded.getAnalysisMessage().contains("gap_validation_failed"),
                "analysisMessage must name the failed stage, got: " + reloaded.getAnalysisMessage());
        assertTrue(pipelineJobRepository.findByClaimIdAndStage(claim.getId(), "gap_whatif").isEmpty(),
                "No gap_whatif jobs must be submitted after a failed validation stage");
    }

    // -------------------------------------------------------------------------
    // Scenario 19b: a validation job still in flight does NOT advance or fail —
    // the stage simply waits (allTerminal gate).
    // -------------------------------------------------------------------------

    @Test
    void validationStillInFlight_waitsWithoutFailing() {
        gapStateMachine.advance(claim);
        llmJobSubmitter.tick(); llmJobPoller.tick();
        gapStateMachine.advance(claim); // → VALIDATING

        // Keep validation jobs non-terminal.
        fakeProvider.setStatus("gap_validation", ProviderJobStatus.IN_PROGRESS);
        llmJobSubmitter.tick();
        llmJobPoller.tick();

        gapStateMachine.advance(claim); // must be a no-op

        Claim reloaded = claimRepository.findById(claim.getId()).orElseThrow();
        assertEquals("VALIDATING", reloaded.getGapState(),
                "gapState must remain VALIDATING while validation jobs are still in flight");
        assertNotEquals(Claim.ClaimStatus.ERROR, reloaded.getStatus(),
                "An in-flight stage must not be marked failed");
    }
}
