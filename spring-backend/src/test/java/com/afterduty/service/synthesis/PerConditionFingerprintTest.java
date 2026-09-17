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
 * Phase B item B2 — ATTRIBUTED PER-CONDITION FINGERPRINTS, end to end with
 * {@code va-claim.synthesis.per-condition-fingerprint=true} (the new flag ON) on
 * the same substrate as {@link IncrementalSynthesisGenerationTest}.
 *
 * <p>What this pins, per the report's §5 item 2 requirements:
 * <ul>
 *   <li>identify prompt v2 attribution ({@code supporting_atom_ids}) is parsed and
 *       persisted per condition;</li>
 *   <li>a new fact dirties ONLY the conditions attributed to it — the inverse of
 *       {@code IncrementalSynthesisGenerationTest.newFact_reRatesOnlyAffectedRun...},
 *       which pins the corpus-wide (flag OFF) behavior where the same upload
 *       dirties everything;</li>
 *   <li>safety valve (a): unattributed conditions fall back to the corpus-wide
 *       hash and dirty on any new fact;</li>
 *   <li>safety valve (b): a NEW condition is always dirty;</li>
 *   <li>safety valve (c): a condition whose last full run is >30 days old goes
 *       dirty on the next run even when fingerprint-clean;</li>
 *   <li>the scheduler's no-new-facts short-circuit still works (it compares the
 *       corpus fingerprint column, not the now-scoped evidence fingerprint).</li>
 * </ul>
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
        "va-claim.analysis.incremental=true",
        "va-claim.synthesis.per-condition-fingerprint=true"
})
class PerConditionFingerprintTest {

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
    @Autowired UserRepository userRepository;
    @Autowired FakeLlmAsyncProvider fake;

    private Claim claim;
    private Long ptsdAtomId;
    private Long tinnitusAtomId;

    @BeforeEach
    void setUp() {
        fake.reset();
        fake.setResponse("synthesis_rate", RATE);
        fake.setResponse("synthesis_verify", "[]");

        User u = new User();
        u.setEmail("vet@example.com");
        u.setName("Test Veteran");
        u.setSubscriptionExpiresAt(Instant.now().plus(30, ChronoUnit.DAYS));
        u = userRepository.save(u);

        claim = claimRepository.save(Claim.builder().userId(u.getId()).status(Claim.ClaimStatus.DRAFT).build());
        ptsdAtomId = saveAtom(10L, "diagnosis", "PTSD established", "VA exam 2023");
        tinnitusAtomId = saveAtom(11L, "symptom", "tinnitus ringing", "audiology 2023");

        setIdentifyAttributed(ptsdAtomId, tinnitusAtomId);
    }

    private Long saveAtom(Long evidenceId, String type, String value, String source) {
        return atomRepository.save(Atom.builder().claimId(claim.getId())
                .evidenceId(evidenceId).type(type).value(value).source(source)
                .createdBy("ai:test").build()).getId();
    }

    /** Identify (and pass-through merger) response: PTSD + Tinnitus, each citing its own atoms. */
    private void setIdentifyAttributed(Long ptsdAtoms, Long tinnitusAtoms) {
        setIdentify(String.format("""
                [
                  {"name":"PTSD","vasrd_code":"9411","body_system":"mental","confidence":0.9,
                   "supporting_atom_ids":[%d]},
                  {"name":"Tinnitus","vasrd_code":"6260","body_system":"ear","confidence":0.8,
                   "supporting_atom_ids":[%d]}
                ]
                """, ptsdAtoms, tinnitusAtoms));
    }

    private void setIdentify(String json) {
        fake.setResponse("synthesis_identify", json);
        fake.setResponse("synthesis_duplicate_merger", json);
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
        fail("synthesis did not reach COMPLETE within the tick budget");
    }

    private List<IdentifiedCondition> active() {
        return conditionRepository.findByClaimIdAndSupersededByIsNull(claim.getId());
    }

    private IdentifiedCondition activeByName(String name) {
        return active().stream().filter(c -> name.equals(c.getName())).findFirst().orElseThrow();
    }

    private long rateJobsSubmitted() {
        return fake.submittedJobs().stream()
                .filter(j -> "synthesis_rate".equals(j.purpose()))
                .map(FakeLlmAsyncProvider.SubmittedJob::internalJobId)
                .distinct().count();
    }

    private long identifyJobCount() {
        return fake.submittedJobs().stream()
                .filter(j -> "synthesis_identify".equals(j.purpose()))
                .map(FakeLlmAsyncProvider.SubmittedJob::internalJobId)
                .distinct().count();
    }

    // -------------------------------------------------------------------------
    // Attribution parse + persist (item B2 step 1)
    // -------------------------------------------------------------------------

    @Test
    void attribution_isParsedAndPersistedPerCondition() {
        runSynthesisToComplete();

        IdentifiedCondition ptsd = activeByName("PTSD");
        IdentifiedCondition tinnitus = activeByName("Tinnitus");
        assertEquals(List.of(ptsdAtomId), ptsd.getSupportingAtomIds(),
                "identify's supporting_atom_ids are persisted on the PTSD row");
        assertEquals(List.of(tinnitusAtomId), tinnitus.getSupportingAtomIds(),
                "identify's supporting_atom_ids are persisted on the Tinnitus row");

        assertNotNull(ptsd.getCorpusFingerprint(), "the corpus fingerprint is stamped");
        assertEquals(ptsd.getCorpusFingerprint(), tinnitus.getCorpusFingerprint(),
                "all rows of a generation share the run's corpus fingerprint");
        assertNotEquals(ptsd.getEvidenceFingerprint(), tinnitus.getEvidenceFingerprint(),
                "scoped evidence fingerprints differ per condition (each hashes its own atoms)");
        assertNotNull(ptsd.getLastFullRunAt(), "a freshly rated (dirty) condition is stamped with its full-run time");
    }

    // -------------------------------------------------------------------------
    // The headline: a new fact dirties ONLY the conditions attributed to it
    // -------------------------------------------------------------------------

    @Test
    void newFact_dirtiesOnlyTheConditionAttributedToIt() {
        runSynthesisToComplete();
        long rateJobsAfterRun1 = rateJobsSubmitted();
        assertEquals(2, rateJobsAfterRun1, "first run rates both conditions");

        // New evidence relevant to PTSD only; identify v2 attributes it to PTSD.
        Long prazosinAtomId = saveAtom(12L, "medication", "prazosin 2mg", "pharmacy 2024");
        setIdentify(String.format("""
                [
                  {"name":"PTSD","vasrd_code":"9411","body_system":"mental","confidence":0.9,
                   "supporting_atom_ids":[%d,%d]},
                  {"name":"Tinnitus","vasrd_code":"6260","body_system":"ear","confidence":0.8,
                   "supporting_atom_ids":[%d]}
                ]
                """, ptsdAtomId, prazosinAtomId, tinnitusAtomId));

        runSynthesisToComplete();

        assertEquals(2, active().size(), "still 2 active conditions, no duplicates");
        assertEquals(rateJobsAfterRun1 + 1, rateJobsSubmitted(),
                "ONLY the condition attributed to the new atom is re-rated; the other carries forward");
        assertEquals(70, activeByName("Tinnitus").getEstimatedRating(),
                "the untouched condition keeps its carried-forward rating");
        assertEquals(List.of(ptsdAtomId, prazosinAtomId), activeByName("PTSD").getSupportingAtomIds(),
                "the re-rated condition's attribution now includes the new atom");
    }

    @Test
    void unchangedReRun_carriesForwardEverything_zeroRateJobs() {
        runSynthesisToComplete();
        long rateJobsAfterRun1 = rateJobsSubmitted();

        runSynthesisToComplete();

        assertEquals(rateJobsAfterRun1, rateJobsSubmitted(),
                "an unchanged re-run under scoped fingerprints still carries everything forward");
        assertEquals(2, active().size());
    }

    // -------------------------------------------------------------------------
    // Safety valve (a) — unattributed conditions dirty on ANY new fact
    // -------------------------------------------------------------------------

    @Test
    void unattributedConditions_fallBackToCorpusHash_bothDirtyOnAnyNewFact() {
        // Identify emits NO supporting_atom_ids (attribution-missing).
        setIdentify("""
                [
                  {"name":"PTSD","vasrd_code":"9411","body_system":"mental","confidence":0.9},
                  {"name":"Tinnitus","vasrd_code":"6260","body_system":"ear","confidence":0.8}
                ]
                """);
        runSynthesisToComplete();
        long rateJobsAfterRun1 = rateJobsSubmitted();
        assertEquals(2, rateJobsAfterRun1);
        assertNull(activeByName("PTSD").getSupportingAtomIds(),
                "attribution-missing persists as null");

        saveAtom(12L, "medication", "prazosin 2mg", "pharmacy 2024"); // any new fact

        runSynthesisToComplete();

        assertEquals(rateJobsAfterRun1 + 2, rateJobsSubmitted(),
                "valve (a): unattributed conditions use the corpus-wide hash and BOTH go dirty");
    }

    // -------------------------------------------------------------------------
    // Safety valve (b) — a NEW condition is always dirty
    // -------------------------------------------------------------------------

    @Test
    void newCondition_isAlwaysDirty_existingAttributedOnesCarryForward() {
        runSynthesisToComplete();
        long rateJobsAfterRun1 = rateJobsSubmitted();
        assertEquals(2, rateJobsAfterRun1);

        Long gerdAtomId = saveAtom(12L, "diagnosis", "GERD established", "GI clinic 2024");
        setIdentify(String.format("""
                [
                  {"name":"PTSD","vasrd_code":"9411","body_system":"mental","confidence":0.9,
                   "supporting_atom_ids":[%d]},
                  {"name":"Tinnitus","vasrd_code":"6260","body_system":"ear","confidence":0.8,
                   "supporting_atom_ids":[%d]},
                  {"name":"GERD","vasrd_code":"7346","body_system":"digestive","confidence":0.8,
                   "supporting_atom_ids":[%d]}
                ]
                """, ptsdAtomId, tinnitusAtomId, gerdAtomId));

        runSynthesisToComplete();

        assertEquals(3, active().size(), "the new condition joins the active generation");
        assertEquals(rateJobsAfterRun1 + 1, rateJobsSubmitted(),
                "valve (b): ONLY the brand-new condition is rated; the attributed existing ones carry forward");
        assertEquals(70, activeByName("PTSD").getEstimatedRating());
        assertEquals(70, activeByName("Tinnitus").getEstimatedRating());
    }

    // -------------------------------------------------------------------------
    // Safety valve (c) — 30-day full-refresh escape
    // -------------------------------------------------------------------------

    @Test
    void staleFullRun_forcesFullRefresh_onOtherwiseCleanReRun() {
        runSynthesisToComplete();
        long rateJobsAfterRun1 = rateJobsSubmitted();
        assertEquals(2, rateJobsAfterRun1);

        // Age the active generation's last full pass beyond the 30-day window.
        for (IdentifiedCondition c : active()) {
            c.setLastFullRunAt(Instant.now().minus(40, ChronoUnit.DAYS));
            conditionRepository.save(c);
        }

        runSynthesisToComplete(); // evidence unchanged — fingerprint-clean re-run

        assertEquals(rateJobsAfterRun1 + 2, rateJobsSubmitted(),
                "valve (c): fingerprint-clean conditions older than the refresh window are re-rated");
        for (IdentifiedCondition c : active()) {
            assertNotNull(c.getLastFullRunAt());
            assertTrue(c.getLastFullRunAt().isAfter(Instant.now().minus(1, ChronoUnit.DAYS)),
                    "the refreshed generation carries a fresh full-run timestamp");
        }
    }

    // -------------------------------------------------------------------------
    // The scheduler's no-new-facts short-circuit survives scoping
    // -------------------------------------------------------------------------

    @Test
    void duplicateContentReUpload_stillShortCircuits_underScopedFingerprints() {
        runSynthesisToComplete();
        long identifyAfterRun1 = identifyJobCount();
        assertEquals(1, identifyAfterRun1);

        // Duplicate-content re-upload: prior atoms superseded, byte-identical new
        // atoms minted with fresh ids/created_at. Corpus content is unchanged.
        for (Atom a : atomRepository.findByClaimIdAndSupersededByIsNull(claim.getId())) {
            Long evidenceId = a.getEvidenceId();
            String type = a.getType();
            String value = a.getValue();
            String source = a.getSource();
            a.setSupersededBy(99L);
            atomRepository.save(a);
            saveAtom(evidenceId, type, value, source);
        }

        scheduler.tick();
        submitter.tick();
        poller.tick();

        assertEquals(identifyAfterRun1, identifyJobCount(),
                "the no-new-facts short-circuit still fires: it compares the CORPUS fingerprint column, "
                        + "which scoping leaves corpus-wide");
        assertNull(claimRepository.findById(claim.getId()).orElseThrow().getSynthesisState(),
                "no synthesis run was started");
    }
}
