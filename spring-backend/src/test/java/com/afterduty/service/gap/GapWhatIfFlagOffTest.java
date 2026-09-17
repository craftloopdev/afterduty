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
 * P1-9 — the gap_whatif stage is OFF by default (va-claim.gap.whatif-enabled
 * resolves false from application.yml; no client renders whatIfScenarios, so
 * the stage was pure spend on unread output). With the flag off, gap analysis
 * must complete right after validation: validated gaps persisted, ZERO
 * gap_whatif jobs submitted, whatIfScenarios left null.
 *
 * <p>Deliberately does NOT set va-claim.gap.whatif-enabled — this suite proves
 * the shipped DEFAULT skips the stage. Suites that exercise what-if set the
 * property true explicitly (see {@link GapStateMachineTest}).
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
        "va-claim.llm.orphan-deadline-min=30"
})
class GapWhatIfFlagOffTest {

    static final String GAP_EVIDENCE_RESPONSE = """
            [
              {"gap":"No nexus letter","severity":"high","recommendation":"Obtain IMO"}
            ]
            """;

    static final String GAP_VALIDATION_RESPONSE = """
            [
              {"gap":"No nexus letter","severity":"high","recommendation":"Obtain IMO","verdict":"keep"}
            ]
            """;

    @Autowired GapStateMachine gapStateMachine;
    @Autowired LlmJobSubmitter llmJobSubmitter;
    @Autowired LlmJobPoller llmJobPoller;
    @Autowired ClaimRepository claimRepository;
    @Autowired IdentifiedConditionRepository conditionRepository;
    @Autowired AtomRepository atomRepository;
    @Autowired ClaimPipelineJobRepository pipelineJobRepository;
    @Autowired FakeLlmAsyncProvider fakeProvider;

    private Claim claim;

    @BeforeEach
    void setUp() {
        fakeProvider.reset();
        fakeProvider.setResponse("gap_evidence", GAP_EVIDENCE_RESPONSE);
        fakeProvider.setResponse("gap_validation", GAP_VALIDATION_RESPONSE);
        // Deliberately NO gap_whatif response: a submitted what-if job would
        // wedge, so completion below is proof the stage was skipped.

        claim = claimRepository.save(Claim.builder()
                .userId(1L).status(Claim.ClaimStatus.ANALYZED).build());
        atomRepository.save(Atom.builder()
                .claimId(claim.getId()).type("diagnosis").value("PTSD diagnosed")
                .source("VA exam").createdBy("ai:test").build());
        conditionRepository.save(IdentifiedCondition.builder()
                .claimId(claim.getId()).name("PTSD").vasrdCode("9411")
                .estimatedRating(50).build());
    }

    @Test
    void whatIfDisabledByDefault_completesAfterValidation_zeroWhatIfJobs() {
        gapStateMachine.advance(claim);                 // NONE → EVIDENCE_GAPS
        llmJobSubmitter.tick(); llmJobPoller.tick();
        gapStateMachine.advance(claim);                 // EVIDENCE_GAPS → VALIDATING
        llmJobSubmitter.tick(); llmJobPoller.tick();
        gapStateMachine.advance(claim);                 // VALIDATING → COMPLETE (what-if skipped)

        // Zero what-if jobs anywhere — no pipeline rows, no provider submissions.
        assertTrue(pipelineJobRepository.findByClaimIdAndStage(claim.getId(), "gap_whatif").isEmpty(),
                "No gap_whatif pipeline jobs may be submitted while the stage is disabled");
        assertTrue(fakeProvider.submittedJobs().stream().noneMatch(j -> "gap_whatif".equals(j.purpose())),
                "No gap_whatif LLM jobs may reach the provider while the stage is disabled");

        // Gap analysis completed cleanly (same terminal shape as the enabled path).
        Claim reloaded = claimRepository.findById(claim.getId()).orElseThrow();
        assertNull(reloaded.getGapState(), "gapState must be null once gap analysis completes");
        assertFalse(Boolean.TRUE.equals(reloaded.getGapAnalysisInProgress()),
                "gapAnalysisInProgress must be cleared");
        assertNotNull(reloaded.getLastGapAnalysisAt(), "completion must be stamped");

        // Validated gaps are persisted; what-if output simply doesn't exist.
        List<IdentifiedCondition> conditions = conditionRepository.findByClaimId(claim.getId());
        for (IdentifiedCondition c : conditions) {
            assertNotNull(c.getGaps(), "Validated gaps must be persisted before the skip");
            assertFalse(c.getGaps().isEmpty(), "Canned validation response contains one kept gap");
            assertNull(c.getWhatIfScenarios(),
                    "whatIfScenarios must stay null while the stage is disabled");
        }
    }
}
