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

import static org.junit.jupiter.api.Assertions.*;

/**
 * Mission 5b — flag OFF ({@code va-claim.analysis.incremental=false}) preserves
 * today's exact semantics: a synthesis re-run APPENDS a fresh set of conditions
 * (no supersede, no generations), every condition is re-rated (no carry-forward),
 * and no fingerprints are written. This is the rollback guarantee.
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
        "va-claim.llm.orphan-deadline-min=30",
        "va-claim.analysis.incremental=false"
})
class GenerationFlagOffTest {

    static final String IDENTIFY_2 = """
            [
              {"name":"PTSD","vasrd_code":"9411","body_system":"mental","confidence":0.9},
              {"name":"Tinnitus","vasrd_code":"6260","body_system":"ear","confidence":0.8}
            ]
            """;
    static final String RATE = """
            {"estimated_rating":70,"rating_rationale":"per evidence","confidence":0.88}
            """;

    @Autowired SynthesisStateMachine synthesis;
    @Autowired LlmJobSubmitter submitter;
    @Autowired LlmJobPoller poller;
    @Autowired ClaimRepository claimRepository;
    @Autowired AtomRepository atomRepository;
    @Autowired IdentifiedConditionRepository conditionRepository;
    @Autowired FakeLlmAsyncProvider fake;

    private Claim claim;

    @BeforeEach
    void setUp() {
        fake.reset();
        fake.setResponse("synthesis_identify", IDENTIFY_2);
        fake.setResponse("synthesis_duplicate_merger", IDENTIFY_2);
        fake.setResponse("synthesis_rate", RATE);
        fake.setResponse("synthesis_verify", "[]");

        claim = claimRepository.save(Claim.builder().userId(1L).status(Claim.ClaimStatus.DRAFT).build());
        atomRepository.save(Atom.builder().claimId(claim.getId()).evidenceId(10L)
                .type("diagnosis").value("PTSD established").source("VA exam 2023")
                .createdBy("ai:test").build());
    }

    private void runSynthesisToComplete() {
        Claim c = claimRepository.findById(claim.getId()).orElseThrow();
        c.setSynthesisState(null);
        c.setSynthesisInProgress(false);
        claimRepository.save(c);
        for (int i = 0; i < 12; i++) {
            Claim cur = claimRepository.findById(claim.getId()).orElseThrow();
            if (i > 0 && cur.getSynthesisState() == null) return;
            synthesis.advance(cur);
            submitter.tick();
            poller.tick();
        }
        fail("synthesis did not complete");
    }

    private long distinctRateJobs() {
        return fake.submittedJobs().stream()
                .filter(j -> "synthesis_rate".equals(j.purpose()))
                .map(FakeLlmAsyncProvider.SubmittedJob::internalJobId)
                .distinct().count();
    }

    @Test
    void flagOff_reRunAppendsConditions_reRatesAll_noFingerprints() {
        runSynthesisToComplete();

        List<IdentifiedCondition> afterRun1 = conditionRepository.findByClaimId(claim.getId());
        assertEquals(2, afterRun1.size(), "first run creates 2 conditions");
        assertEquals(2, distinctRateJobs(), "first run rates both");
        // Flag OFF: no generation bookkeeping written.
        afterRun1.forEach(c -> {
            assertNull(c.getSupersededBy(), "flag OFF: conditions are not pending/superseded");
            assertNull(c.getIdentityFingerprint(), "flag OFF: no identity fingerprint written");
            assertNull(c.getEvidenceFingerprint(), "flag OFF: no evidence fingerprint written");
        });

        // Re-run with unchanged evidence — legacy behavior APPENDS a second set and
        // re-rates everything (the old duplicate-accumulation behavior, preserved
        // exactly for rollback parity).
        runSynthesisToComplete();

        List<IdentifiedCondition> afterRun2 = conditionRepository.findByClaimId(claim.getId());
        assertEquals(4, afterRun2.size(),
                "flag OFF: a re-run APPENDS conditions (legacy semantics — no supersede)");
        assertEquals(4, distinctRateJobs(),
                "flag OFF: every condition is re-rated each run (no carry-forward)");
        afterRun2.forEach(c -> assertNull(c.getSupersededBy(),
                "flag OFF: nothing is ever superseded — active readers behave as the full set"));
    }
}
