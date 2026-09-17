package com.afterduty.service.gap;

import com.afterduty.model.*;
import com.afterduty.repository.*;
import com.afterduty.service.llm.*;
import com.afterduty.service.synthesis.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Mission 5b — gap dirty-scope carry-forward, end to end through both state
 * machines (flag ON). After synthesis + gap complete once, an unchanged re-run
 * carries the gap outputs forward onto the new generation and submits ZERO new
 * gap LLM jobs; the active conditions keep their gaps.
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
        GapStateMachine.class,
        EvidenceGapAnalyzer.class,
        GapValidationAgent.class,
        WhatIfScenarioGenerator.class,
        UserGapStateService.class,
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
        // P1-9 — the what-if stage defaults OFF in prod (rendered nowhere); this
        // suite exercises it, so turn it on explicitly.
        "va-claim.gap.whatif-enabled=true",
        "va-claim.analysis.incremental=true"
})
class IncrementalGapCarryForwardTest {

    static final String IDENTIFY = """
            [{"name":"PTSD","vasrd_code":"9411","body_system":"mental","confidence":0.9}]
            """;
    static final String RATE = """
            {"estimated_rating":70,"rating_rationale":"per evidence","confidence":0.88}
            """;
    static final String GAP_EVIDENCE = """
            [{"gap":"No nexus letter","severity":"high","recommendation":"Obtain IMO"}]
            """;
    static final String GAP_VALIDATION = """
            [{"gap":"No nexus letter","severity":"high","recommendation":"Obtain IMO","verdict":"keep"}]
            """;
    static final String GAP_WHATIF = """
            [{"scenario":"With nexus","current_rating":70,"potential_rating":100}]
            """;

    @Autowired SynthesisStateMachine synthesis;
    @Autowired GapStateMachine gap;
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
        fake.setResponse("synthesis_identify", IDENTIFY);
        fake.setResponse("synthesis_duplicate_merger", IDENTIFY);
        fake.setResponse("synthesis_rate", RATE);
        fake.setResponse("synthesis_verify", "[]");
        fake.setResponse("gap_evidence", GAP_EVIDENCE);
        fake.setResponse("gap_validation", GAP_VALIDATION);
        fake.setResponse("gap_whatif", GAP_WHATIF);

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

    private void runGapToComplete() {
        Claim c = claimRepository.findById(claim.getId()).orElseThrow();
        c.setGapState(null);
        c.setGapAnalysisInProgress(false);
        claimRepository.save(c);
        for (int i = 0; i < 12; i++) {
            Claim cur = claimRepository.findById(claim.getId()).orElseThrow();
            if (i > 0 && cur.getGapState() == null) return;
            gap.advance(cur);
            submitter.tick();
            poller.tick();
        }
        fail("gap analysis did not complete");
    }

    private long distinctJobs(String purpose) {
        return fake.submittedJobs().stream()
                .filter(j -> purpose.equals(j.purpose()))
                .map(FakeLlmAsyncProvider.SubmittedJob::internalJobId)
                .distinct().count();
    }

    @Test
    void unchangedReRun_carriesGapsForward_zeroNewGapJobs() {
        runSynthesisToComplete();
        runGapToComplete();

        List<IdentifiedCondition> gen1 = conditionRepository.findByClaimIdAndSupersededByIsNull(claim.getId());
        assertEquals(1, gen1.size());
        List<Map<String, Object>> gen1Gaps = gen1.get(0).getGaps();
        assertNotNull(gen1Gaps);
        assertEquals(1, gen1Gaps.size(), "first gap run produces a gap");
        assertNotNull(gen1.get(0).getWhatIfScenarios());
        assertFalse(gen1.get(0).getWhatIfScenarios().isEmpty(), "first run produces a what-if");

        long gapEvidenceAfterRun1 = distinctJobs("gap_evidence");
        long gapWhatifAfterRun1 = distinctJobs("gap_whatif");
        assertEquals(1, gapEvidenceAfterRun1);
        assertEquals(1, gapWhatifAfterRun1);

        // Re-run synthesis (unchanged evidence) → the new generation's condition is
        // CLEAN and carried its gaps/what-ifs forward during synthesis.
        runSynthesisToComplete();
        List<IdentifiedCondition> gen2 = conditionRepository.findByClaimIdAndSupersededByIsNull(claim.getId());
        assertEquals(1, gen2.size(), "still exactly one active condition (no duplicates)");
        assertNotNull(gen2.get(0).getGaps(), "carried-forward condition retains its gaps");
        assertEquals(1, gen2.get(0).getGaps().size());
        assertFalse(gen2.get(0).getWhatIfScenarios().isEmpty(), "what-ifs carried forward too");

        // Now run the gap stage on the carried-forward generation — it must NOT
        // submit any new gap jobs (every active condition is clean).
        runGapToComplete();
        assertEquals(gapEvidenceAfterRun1, distinctJobs("gap_evidence"),
                "ZERO new gap_evidence jobs on the clean re-run (gaps carried forward)");
        assertEquals(gapWhatifAfterRun1, distinctJobs("gap_whatif"),
                "ZERO new gap_whatif jobs on the clean re-run");

        // Gaps still intact on the active condition.
        List<IdentifiedCondition> finalActive =
                conditionRepository.findByClaimIdAndSupersededByIsNull(claim.getId());
        assertEquals(1, finalActive.size());
        assertEquals(1, finalActive.get(0).getGaps().size(), "gaps survive the whole re-analysis");
    }
}
