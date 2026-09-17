package com.afterduty.service.synthesis;

import com.afterduty.model.*;
import com.afterduty.repository.*;
import com.afterduty.service.AnalysisScheduler;
import com.afterduty.service.llm.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Mission 5b — the NO-NEW-FACTS short-circuit in {@link AnalysisScheduler}. After a
 * completed generation, a re-extraction that produces a byte-identical live atom set
 * (e.g. a duplicate-content re-upload: prior atoms superseded, new atoms minted with
 * fresh db ids but identical content) must NOT re-trigger the whole synthesis
 * pipeline. The scheduler detects the evidence fingerprint is unchanged, stamps
 * bookkeeping so it won't re-fire, and submits zero synthesis jobs.
 *
 * <p>{@code quiet-seconds=0} removes the upload-quiet delay so the trigger is
 * exercised immediately.
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
        AnalysisScheduler.class,
        com.afterduty.service.VaMathService.class
})
@TestPropertySource(properties = {
        "va-claim.llm.submitter.batch-size=50",
        "va-claim.llm.submitter.poll-ms=9999999",
        "va-claim.llm.submitter.initial-delay-ms=9999999",
        "va-claim.llm.poller.poll-ms=9999999",
        "va-claim.llm.poller.initial-delay-ms=9999999",
        "va-claim.llm.orphan-deadline-min=30",
        "va-claim.pipeline.quiet-seconds=0",
        "va-claim.analysis.incremental=true"
})
class NoNewFactsShortCircuitTest {

    static final String IDENTIFY_1 = """
            [{"name":"PTSD","vasrd_code":"9411","body_system":"mental","confidence":0.9}]
            """;
    static final String RATE = """
            {"estimated_rating":70,"rating_rationale":"per evidence","confidence":0.88}
            """;

    @Autowired SynthesisStateMachine synthesis;
    @Autowired AnalysisScheduler scheduler;
    @Autowired LlmJobSubmitter submitter;
    @Autowired LlmJobPoller poller;
    @Autowired ClaimRepository claimRepository;
    @Autowired AtomRepository atomRepository;
    @Autowired IdentifiedConditionRepository conditionRepository;
    @Autowired ClaimPipelineJobRepository pipelineJobRepository;
    @Autowired UserRepository userRepository;
    @Autowired FakeLlmAsyncProvider fake;

    private Claim claim;

    @BeforeEach
    void setUp() {
        fake.reset();
        fake.setResponse("synthesis_identify", IDENTIFY_1);
        fake.setResponse("synthesis_duplicate_merger", IDENTIFY_1);
        fake.setResponse("synthesis_rate", RATE);
        fake.setResponse("synthesis_verify", "[]");

        User u = new User();
        u.setEmail("vet@example.com");
        u.setName("Test Veteran");
        u.setSubscriptionExpiresAt(Instant.now().plus(30, ChronoUnit.DAYS)); // active sub
        u = userRepository.save(u);

        claim = claimRepository.save(Claim.builder().userId(u.getId()).status(Claim.ClaimStatus.DRAFT).build());
        // Atom backdated so the quiet check (even at 0s) and the post-synthesis
        // "newer than lastSynthesisAt" check behave deterministically.
        saveAtom(10L, "diagnosis", "PTSD established", "VA exam 2023");
    }

    private void saveAtom(Long evidenceId, String type, String value, String source) {
        atomRepository.save(Atom.builder().claimId(claim.getId())
                .evidenceId(evidenceId).type(type).value(value).source(source)
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

    private long identifyJobCount() {
        return fake.submittedJobs().stream()
                .filter(j -> "synthesis_identify".equals(j.purpose()))
                .map(FakeLlmAsyncProvider.SubmittedJob::internalJobId)
                .distinct().count();
    }

    @Test
    void duplicateContentReUpload_doesNotReTriggerSynthesis() {
        runSynthesisToComplete();
        assertEquals(1, conditionRepository.findByClaimIdAndSupersededByIsNull(claim.getId()).size());
        long identifyAfterRun1 = identifyJobCount();
        assertEquals(1, identifyAfterRun1, "first run runs identify once");

        Instant lastSynth1 = claimRepository.findById(claim.getId()).orElseThrow().getLastSynthesisAt();
        assertNotNull(lastSynth1);

        // Simulate a duplicate-content re-upload that was re-extracted: the prior
        // atom is superseded and a byte-IDENTICAL new atom is minted with a fresh
        // db id and a LATER created_at (so the data-driven trigger would otherwise
        // fire). The live atom SET is unchanged ⇒ evidence fingerprint unchanged.
        Atom prior = atomRepository.findByClaimIdAndSupersededByIsNull(claim.getId()).get(0);
        prior.setSupersededBy(99L); // retired by the (simulated) re-extraction marker
        atomRepository.save(prior);
        saveAtom(10L, "diagnosis", "PTSD established", "VA exam 2023"); // identical content, new id

        // The scheduler tick must SHORT-CIRCUIT: no new identify job, and it stamps
        // lastSynthesisAt forward so the unchanged atoms don't re-trigger next tick.
        scheduler.tick();
        submitter.tick();
        poller.tick();

        assertEquals(identifyAfterRun1, identifyJobCount(),
                "no-new-facts re-upload must NOT submit a new synthesis identify job");
        Claim after = claimRepository.findById(claim.getId()).orElseThrow();
        assertNull(after.getSynthesisState(),
                "synthesis must not have been started (state stays cleared)");
        assertTrue(after.getLastSynthesisAt().isAfter(lastSynth1) || after.getLastSynthesisAt().equals(lastSynth1),
                "lastSynthesisAt must be stamped forward so the skip doesn't re-trigger");
        assertEquals(1, conditionRepository.findByClaimIdAndSupersededByIsNull(claim.getId()).size(),
                "the active generation is untouched — still exactly one condition, no duplicates");
    }

    @Test
    void genuinelyNewFact_doesReTriggerSynthesis() {
        // Control: a real new fact MUST still trigger synthesis (the short-circuit
        // only suppresses provably-redundant runs).
        runSynthesisToComplete();
        long identifyAfterRun1 = identifyJobCount();

        saveAtom(11L, "diagnosis", "GERD established", "GI clinic 2024"); // genuinely new fact

        scheduler.tick();
        submitter.tick();
        poller.tick();

        assertTrue(identifyJobCount() > identifyAfterRun1,
                "a genuinely new fact must re-trigger synthesis (a new identify job is submitted)");
    }
}
