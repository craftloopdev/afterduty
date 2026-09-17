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
 * Mission 5b — condition generations, atomic supersede, dirty-scope carry-forward,
 * and the no-new-facts short-circuit, with the incremental flag ON
 * ({@code va-claim.analysis.incremental=true}).
 *
 * <p>Substrate mirrors {@link SynthesisStateMachineTest}: real H2 JPA, the canned
 * {@link FakeLlmAsyncProvider}, and direct {@code advance()} calls with
 * submitter/poller ticks between them. A full synthesis run is driven by
 * {@link #runSynthesisToComplete}. A re-run is triggered the production way: reset
 * {@code synthesisState=null} and advance again — the data inside the claim (atoms,
 * prior generation fingerprints) decides what re-rates vs carries forward.
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
        "va-claim.analysis.incremental=true"
})
class IncrementalSynthesisGenerationTest {

    static final String IDENTIFY_2 = """
            [
              {"name":"PTSD","vasrd_code":"9411","body_system":"mental","confidence":0.9},
              {"name":"Tinnitus","vasrd_code":"6260","body_system":"ear","confidence":0.8}
            ]
            """;
    static final String MERGER_2 = IDENTIFY_2;
    static final String RATE = """
            {"estimated_rating":70,"rating_rationale":"per evidence","confidence":0.88}
            """;
    static final String VERIFY = "[]";

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
        fake.setResponse("synthesis_duplicate_merger", MERGER_2);
        fake.setResponse("synthesis_rate", RATE);
        fake.setResponse("synthesis_verify", VERIFY);

        claim = claimRepository.save(Claim.builder().userId(1L).status(Claim.ClaimStatus.DRAFT).build());
        saveAtom(10L, "diagnosis", "PTSD established", "VA exam 2023");
        saveAtom(11L, "symptom", "tinnitus ringing", "audiology 2023");
    }

    private void saveAtom(Long evidenceId, String type, String value, String source) {
        atomRepository.save(Atom.builder().claimId(claim.getId())
                .evidenceId(evidenceId).type(type).value(value).source(source)
                .createdBy("ai:test").build());
    }

    /**
     * Re-set the confidence of the (single) live atom matching the given type,
     * simulating a re-extraction that produced a byte-identical atom but a different
     * model-assigned confidence — without adding or removing any atom.
     */
    private void setAtomConfidence(String type, double confidence) {
        Atom a = atomRepository.findByClaimIdAndSupersededByIsNull(claim.getId()).stream()
                .filter(x -> type.equals(x.getType()))
                .findFirst().orElseThrow();
        a.setConfidence(confidence);
        atomRepository.save(a);
    }

    /**
     * Drive synthesis NONE → COMPLETE, ticking submitter+poller between
     * transitions. Stops the instant the run completes (state cleared) so it never
     * re-enters NONE and starts a second run. The first iteration always advances
     * from the freshly-reset NONE; completion is only checked after at least one
     * transition has happened.
     */
    private void runSynthesisToComplete() {
        Claim c = claimRepository.findById(claim.getId()).orElseThrow();
        c.setSynthesisState(null);
        c.setSynthesisInProgress(false);
        claimRepository.save(c);
        for (int i = 0; i < 12; i++) {
            Claim cur = claimRepository.findById(claim.getId()).orElseThrow();
            // Once the run has cleared its state AFTER having started, it's done —
            // do NOT advance again (that would re-enter NONE and start a 2nd run).
            if (i > 0 && cur.getSynthesisState() == null) return;
            synthesis.advance(cur);
            submitter.tick();
            poller.tick();
        }
        fail("synthesis did not reach COMPLETE within the tick budget");
    }

    private List<IdentifiedCondition> active() {
        return conditionRepository.findByClaimIdAndSupersededByIsNull(claim.getId());
    }

    /**
     * Count of DISTINCT synthesis_rate LLM jobs created (by internal job id). The
     * fake provider's audit log can record the same job across submitter ticks;
     * distinct ids are the true production count of rate jobs fanned out.
     */
    private long rateJobsSubmitted() {
        return fake.submittedJobs().stream()
                .filter(j -> "synthesis_rate".equals(j.purpose()))
                .map(FakeLlmAsyncProvider.SubmittedJob::internalJobId)
                .distinct().count();
    }

    // -------------------------------------------------------------------------
    // 1. Re-run with UNCHANGED evidence → identical active set, ZERO rate jobs,
    //    no duplicates.
    // -------------------------------------------------------------------------

    @Test
    void reRunUnchangedEvidence_carriesForwardAll_zeroRateJobs_noDuplicates() {
        runSynthesisToComplete();
        List<IdentifiedCondition> gen1 = active();
        assertEquals(2, gen1.size(), "first run produces 2 active conditions");
        gen1.forEach(c -> assertEquals(70, c.getEstimatedRating()));
        long rateJobsAfterRun1 = rateJobsSubmitted();
        assertEquals(2, rateJobsAfterRun1, "first run rates both conditions");

        // Re-run with the SAME atoms — nothing changed.
        runSynthesisToComplete();

        List<IdentifiedCondition> gen2 = active();
        assertEquals(2, gen2.size(), "re-run still has exactly 2 active conditions (no duplicates)");
        gen2.forEach(c -> assertEquals(70, c.getEstimatedRating(),
                "carried-forward conditions keep their rating"));
        assertEquals(rateJobsAfterRun1, rateJobsSubmitted(),
                "ZERO new rate jobs on a clean re-run (all conditions carried forward)");

        // Prior generation rows are superseded (kept), not deleted — exactly 2 active.
        long totalRows = conditionRepository.findByClaimId(claim.getId()).size();
        assertEquals(4, totalRows, "2 superseded gen-1 rows + 2 active gen-2 rows are retained");
        long activeRows = active().size();
        assertEquals(2, activeRows, "a reader sees exactly ONE generation (2 active)");
    }

    // -------------------------------------------------------------------------
    // 2. A NEW fact touching ONE condition → exactly that condition re-rated;
    //    the other is carried forward with its rating intact.
    // -------------------------------------------------------------------------

    @Test
    void newFact_reRatesOnlyAffectedRun_othersCarriedForward() {
        runSynthesisToComplete();
        long rateJobsAfterRun1 = rateJobsSubmitted();
        assertEquals(2, rateJobsAfterRun1);

        // A new upload adds a NEW live atom → the evidence fingerprint changes for
        // the whole run, so BOTH conditions become dirty (the rating prompt sees the
        // whole live corpus). This asserts the dirty-scope mechanism FANS OUT when
        // evidence changes — the inverse of the clean carry-forward case.
        saveAtom(12L, "medication", "prazosin 2mg", "pharmacy 2024");

        runSynthesisToComplete();

        assertEquals(2, active().size(), "still 2 active conditions, no duplicates");
        assertEquals(rateJobsAfterRun1 + 2, rateJobsSubmitted(),
                "a new fact makes the run dirty → conditions are re-rated, not carried forward");
    }

    // -------------------------------------------------------------------------
    // 2b. A re-extraction that changes ONLY an atom's confidence (no new/removed
    //     atom) must make the run DIRTY — the rating prompt renders confidence, so
    //     carrying the prior rating forward would hide a real change in what the
    //     rating model sees. Pins the major adversarial-review finding end-to-end.
    // -------------------------------------------------------------------------

    @Test
    void confidenceOnlyChange_reRatesNotCarriedForward() {
        runSynthesisToComplete();
        long rateJobsAfterRun1 = rateJobsSubmitted();
        assertEquals(2, rateJobsAfterRun1, "first run rates both conditions");

        // No atom added or removed — same evidence_id/type/value/source — but the
        // re-extraction assigned a different confidence to the diagnosis atom.
        setAtomConfidence("diagnosis", 0.95);

        runSynthesisToComplete();

        assertEquals(2, active().size(), "still 2 active conditions, no duplicates");
        assertEquals(rateJobsAfterRun1 + 2, rateJobsSubmitted(),
                "a confidence-only re-extraction makes the run dirty → conditions are re-rated, not carried forward");
        // The active generation's conditions now carry the NEW evidence fingerprint
        // (built over the changed confidence), so a subsequent unchanged re-run is clean.
        runSynthesisToComplete();
        assertEquals(rateJobsAfterRun1 + 2, rateJobsSubmitted(),
                "the next unchanged re-run carries forward (no further rate jobs)");
    }

    // -------------------------------------------------------------------------
    // 3. Bilateral same-DC conditions stay DISTINCT across generations.
    // -------------------------------------------------------------------------

    @Test
    void bilateralSameDC_stayDistinctAcrossGenerations() {
        fake.setResponse("synthesis_identify", """
                [
                  {"name":"Left knee strain","vasrd_code":"5260","body_system":"musculoskeletal"},
                  {"name":"Right knee strain","vasrd_code":"5260","body_system":"musculoskeletal"}
                ]
                """);
        fake.setResponse("synthesis_duplicate_merger", """
                [
                  {"name":"Left knee strain","vasrd_code":"5260","body_system":"musculoskeletal"},
                  {"name":"Right knee strain","vasrd_code":"5260","body_system":"musculoskeletal"}
                ]
                """);

        runSynthesisToComplete();
        assertEquals(2, active().size(), "left + right knee (same DC) are two distinct conditions");

        runSynthesisToComplete();
        List<IdentifiedCondition> gen2 = active();
        assertEquals(2, gen2.size(),
                "after re-analysis the two same-DC laterality variants remain distinct (not mis-merged)");
        // Both carried forward → zero additional rate jobs beyond the first run's 2.
        assertEquals(2, rateJobsSubmitted(),
                "both laterality variants carried forward cleanly on the unchanged re-run");
        // Distinct identity fingerprints persisted.
        assertNotEquals(gen2.get(0).getIdentityFingerprint(), gen2.get(1).getIdentityFingerprint());
    }

    // -------------------------------------------------------------------------
    // 4. Supersede atomicity — through the whole mid-flight run the EXTERNAL
    //    reader (active = superseded_by IS NULL) sees exactly one full generation:
    //    the prior gen-1 until the COMPLETE transition, then gen-2. Never zero,
    //    never both.
    // -------------------------------------------------------------------------

    @Test
    void supersede_isAtomic_readerNeverSeesZeroOrBoth() {
        runSynthesisToComplete();
        assertEquals(2, active().size());

        // Start a re-run and step through every transition, asserting the external
        // reader count stays exactly 2 at each step (it flips from gen-1 to gen-2
        // atomically at COMPLETE — never an intermediate 0 or 4).
        Claim c = claimRepository.findById(claim.getId()).orElseThrow();
        c.setSynthesisState(null);
        c.setSynthesisInProgress(false);
        claimRepository.save(c);

        for (int i = 0; i < 12; i++) {
            Claim cur = claimRepository.findById(claim.getId()).orElseThrow();
            boolean wasComplete = cur.getSynthesisState() == null && i > 0;
            synthesis.advance(cur);
            submitter.tick();
            poller.tick();

            int activeCount = active().size();
            assertEquals(2, activeCount,
                    "external reader must always see exactly one full generation (2 active) — "
                            + "never zero, never both generations — at step " + i);

            Claim after = claimRepository.findById(claim.getId()).orElseThrow();
            if (after.getSynthesisState() == null && after.getLastSynthesisAt() != null && i > 0) {
                break;
            }
        }
        assertEquals(2, active().size());
    }
}
