package com.afterduty.service.synthesis;

import com.afterduty.model.*;
import com.afterduty.repository.*;
import com.afterduty.service.PresumptiveRulesService;
import com.afterduty.service.llm.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Increment E — end-to-end LIVE-path proof that the deterministic VASRD rating engine
 * ({@link VasrdDecisionEngine}) is wired into {@link SynthesisStateMachine}'s rate
 * fan-out deterministic-first / LLM-fallback, behind the DEFAULT-OFF
 * {@code va-claim.analysis.deterministic-rating} flag.
 *
 * <p>Four flag/evidence states, each a {@code @Nested} slice (mirrors
 * {@link RatingHonestyStateMachineTest} / {@link PresumptiveAtomInferenceStateMachineTest}):
 * <ol>
 *   <li><b>FlagOnCriteriaMet</b> — asthma (6602) whose atoms carry a criteria-supporting
 *       FEV-1 value ⇒ the deterministic rating is USED (persisted, 38-CFR-cited rationale,
 *       evidence-grounded confidence) and NO {@code synthesis_rate} LLM job is fanned out
 *       for that condition.</li>
 *   <li><b>FlagOnCriteriaNotMet</b> — asthma (6602) whose atoms do NOT support any tier
 *       (no FEV-1, no daily bronchodilator, no corticosteroid courses) ⇒ falls through to
 *       the LLM path; a {@code synthesis_rate} job IS submitted and the LLM's number wins.</li>
 *   <li><b>FlagOff</b> — same criteria-met asthma atoms, flag OFF (default) ⇒ the
 *       condition is rated by the LLM exactly as today; the deterministic engine is never
 *       consulted (proven by the LLM rate job firing AND the LLM's number being served).</li>
 *   <li><b>FlagOnUncoveredCode</b> — PTSD (9411, a non-quantitative code the engine does
 *       not cover), flag ON ⇒ always LLM; a {@code synthesis_rate} job is submitted.</li>
 * </ol>
 *
 * <p>"LLM rate job NOT fanned out for a condition" is asserted against
 * {@link FakeLlmAsyncProvider#submittedJobs()} filtered to purpose {@code synthesis_rate}
 * and the condition id.
 */
class DeterministicRatingStateMachineTest {

    // Identify + merge return one condition. The rate agent, if it is ever reached,
    // returns a DELIBERATELY-WRONG 20% at high confidence so a divergence from the
    // deterministic engine's number is observable (deterministic FEV-1 66% ⇒ 30%).
    static final String IDENTIFY_ASTHMA = """
            [
              {"name":"Bronchial Asthma","vasrd_code":"6602","body_system":"respiratory",
               "is_presumptive":false,"presumptive_basis":null,
               "triad_diagnosis":{"status":"STRONG","evidence":["Asthma diagnosed"],"confidence":0.9},
               "triad_in_service":{"status":"STRONG","evidence":["In-service onset"],"confidence":0.9},
               "triad_nexus":{"status":"STRONG","evidence":["Continuity of symptoms"],"confidence":0.9},
               "confidence":0.9}
            ]
            """;
    static final String IDENTIFY_PTSD = """
            [
              {"name":"PTSD","vasrd_code":"9411","body_system":"mental",
               "is_presumptive":false,"presumptive_basis":null,
               "triad_diagnosis":{"status":"STRONG","evidence":["PTSD diagnosed"],"confidence":0.9},
               "triad_in_service":{"status":"STRONG","evidence":["Stressor documented"],"confidence":0.9},
               "triad_nexus":{"status":"STRONG","evidence":["Nexus opinion"],"confidence":0.9},
               "confidence":0.9}
            ]
            """;
    // Wrong-on-purpose LLM rating so the deterministic override is visible when the
    // deterministic path fires, and the LLM number is visible when it falls through.
    static final String RATE_LLM = """
            {"estimated_rating":20,"rating_rationale":"LLM free-choice rating.","confidence":0.85}
            """;

    /** Shared harness. Subclasses set the identify/merge body + atoms in setUp overrides. */
    abstract static class Base {
        @Autowired SynthesisStateMachine synthesis;
        @Autowired LlmJobSubmitter submitter;
        @Autowired LlmJobPoller poller;
        @Autowired ClaimRepository claimRepository;
        @Autowired AtomRepository atomRepository;
        @Autowired IdentifiedConditionRepository conditionRepository;
        @Autowired FakeLlmAsyncProvider fake;

        Claim claim;

        /** The identify/merge condition JSON for this slice (asthma unless overridden). */
        String identifyBody() { return IDENTIFY_ASTHMA; }

        /** Seed this slice's evidence atoms. */
        abstract void seedAtoms(Long claimId);

        @BeforeEach
        void setUp() {
            fake.reset();
            fake.setResponse("synthesis_identify", identifyBody());
            fake.setResponse("synthesis_duplicate_merger", identifyBody());
            fake.setResponse("synthesis_rate", RATE_LLM);
            fake.setResponse("synthesis_verify", "[]");

            claim = claimRepository.save(Claim.builder().userId(1L).status(Claim.ClaimStatus.DRAFT).build());
            seedAtoms(claim.getId());
        }

        void runSynthesisToComplete() {
            Claim c = claimRepository.findById(claim.getId()).orElseThrow();
            c.setSynthesisState(null);
            c.setSynthesisInProgress(false);
            claimRepository.save(c);
            // Drive exactly ONE synthesis run. Each advance() performs one state
            // transition; after the VERIFYING→COMPLETE transition the state is reset to
            // null. We must STOP there: leaving the loop running would let the next
            // advance() (state == null ⇒ NONE) kick off a SECOND identify→…→rate run,
            // which would double the rate-job ledger this test inspects. So we break the
            // instant the state returns to null AFTER we have advanced at least once.
            for (int i = 0; i < 12; i++) {
                Claim cur = claimRepository.findById(claim.getId()).orElseThrow();
                synthesis.advance(cur);
                submitter.tick();
                poller.tick();
                Claim after = claimRepository.findById(claim.getId()).orElseThrow();
                if (after.getSynthesisState() == null) return;   // one run completed
            }
            fail("synthesis did not complete");
        }

        IdentifiedCondition onlyCondition() {
            List<IdentifiedCondition> conds = conditionRepository.findByClaimIdAndSupersededByIsNull(claim.getId());
            assertEquals(1, conds.size(), "expected exactly one active condition");
            return conds.get(0);
        }

        /**
         * How many DISTINCT synthesis_rate LLM jobs were fanned out for the given
         * condition id. Counts distinct internal job ids because the fake substrate
         * records each job twice — once eagerly in {@code LlmJobService.submit}
         * (pre-registration so the poller recognizes it) and once when the batch
         * {@code LlmJobSubmitter} ships it — so raw entry counts double every job.
         */
        long rateJobsFor(Long conditionId) {
            return fake.submittedJobs().stream()
                    .filter(j -> "synthesis_rate".equals(j.purpose()))
                    .filter(j -> conditionId.equals(j.conditionId()))
                    .map(FakeLlmAsyncProvider.SubmittedJob::internalJobId)
                    .distinct()
                    .count();
        }

        /** Total DISTINCT synthesis_rate LLM jobs fanned out this run (any condition). */
        long totalRateJobs() {
            return fake.submittedJobs().stream()
                    .filter(j -> "synthesis_rate".equals(j.purpose()))
                    .map(FakeLlmAsyncProvider.SubmittedJob::internalJobId)
                    .distinct()
                    .count();
        }
    }

    // -------------------------------------------------------------------------
    // 1. Flag ON + criteria-supporting atoms ⇒ deterministic used, no LLM rate job.
    // -------------------------------------------------------------------------

    @Nested
    @DataJpaTest
    @Import({
            LlmProviderRouterTestConfig.class, LlmJobService.class, LlmJobSubmitter.class, LlmJobPoller.class,
            SynthesisStateMachine.class, com.afterduty.service.DomainCorrectionsService.class, ConditionIdentificationAgent.class, DuplicateConditionMerger.class,
            RatingAgent.class, SynthesisVerificationAgent.class, EnhancedSynthesisOrchestrator.class,
            VasrdDecisionEngine.class,
            ConditionGenerationService.class, com.afterduty.service.AnalysisScheduler.class,
            com.afterduty.service.VaMathService.class, PresumptiveRulesService.class
    })
    @TestPropertySource(properties = {
            "va-claim.llm.submitter.batch-size=50",
            "va-claim.llm.submitter.poll-ms=9999999",
            "va-claim.llm.submitter.initial-delay-ms=9999999",
            "va-claim.llm.poller.poll-ms=9999999",
            "va-claim.llm.poller.initial-delay-ms=9999999",
            "va-claim.llm.orphan-deadline-min=30",
            "va-claim.analysis.deterministic-rating=true"
    })
    class FlagOnCriteriaMet extends Base {
        @Override
        void seedAtoms(Long claimId) {
            // A parseable FEV-1 of 66% predicted ⇒ VasrdDecisionEngine rates 6602 at 30%.
            atomRepository.save(Atom.builder().claimId(claimId).evidenceId(10L)
                    .type("clinical_finding").value("Pulmonary function test: FEV-1 66% predicted")
                    .source("PFT report").createdBy("ai:test").build());
        }

        @Test
        void deterministicRatingUsed_noLlmRateJobForCondition() {
            runSynthesisToComplete();

            IdentifiedCondition asthma = onlyCondition();
            // The deterministic engine's number (30% for FEV-1 66%), NOT the LLM's 20%.
            assertEquals(30, asthma.getEstimatedRating(),
                    "deterministic FEV-1 66% must rate 30%, overriding the LLM's 20%");
            // Rationale is the 38-CFR-cited deterministic one, not the LLM string.
            assertNotNull(asthma.getRatingRationale());
            assertTrue(asthma.getRatingRationale().contains("FEV-1"),
                    "rationale should cite the FEV-1 the engine matched: " + asthma.getRatingRationale());
            assertTrue(asthma.getRatingRationale().contains("6602"),
                    "rationale should cite DC 6602: " + asthma.getRatingRationale());
            // Confidence is the engine's evidence-grounded value (0.92 for the FEV-1 tier),
            // not the LLM self-report (0.85).
            assertNotNull(asthma.getConfidence());
            assertTrue(asthma.getConfidence() >= 0.9,
                    "confidence should be the engine's evidence-grounded value: " + asthma.getConfidence());
            // The headline win: NO LLM rate job was fanned out for this condition.
            assertEquals(0, rateJobsFor(asthma.getId()),
                    "flag ON + criteria met: the LLM rate job must NOT be submitted for this condition");
            assertEquals(0, totalRateJobs(),
                    "no synthesis_rate job should have been submitted at all this run");
        }
    }

    // -------------------------------------------------------------------------
    // 2. Flag ON + atoms that DON'T support criteria ⇒ falls through to LLM.
    // -------------------------------------------------------------------------

    @Nested
    @DataJpaTest
    @Import({
            LlmProviderRouterTestConfig.class, LlmJobService.class, LlmJobSubmitter.class, LlmJobPoller.class,
            SynthesisStateMachine.class, com.afterduty.service.DomainCorrectionsService.class, ConditionIdentificationAgent.class, DuplicateConditionMerger.class,
            RatingAgent.class, SynthesisVerificationAgent.class, EnhancedSynthesisOrchestrator.class,
            VasrdDecisionEngine.class,
            ConditionGenerationService.class, com.afterduty.service.AnalysisScheduler.class,
            com.afterduty.service.VaMathService.class, PresumptiveRulesService.class
    })
    @TestPropertySource(properties = {
            "va-claim.llm.submitter.batch-size=50",
            "va-claim.llm.submitter.poll-ms=9999999",
            "va-claim.llm.submitter.initial-delay-ms=9999999",
            "va-claim.llm.poller.poll-ms=9999999",
            "va-claim.llm.poller.initial-delay-ms=9999999",
            "va-claim.llm.orphan-deadline-min=30",
            "va-claim.analysis.deterministic-rating=true"
    })
    class FlagOnCriteriaNotMet extends Base {
        @Override
        void seedAtoms(Long claimId) {
            // Respiratory evidence with NO deciding measure: no FEV-1, no "daily
            // bronchodilator" phrasing, no corticosteroid course ⇒ rateAsthma returns empty.
            atomRepository.save(Atom.builder().claimId(claimId).evidenceId(10L)
                    .type("clinical_finding").value("Wheezing on exam; dyspnea on exertion")
                    .source("VA").createdBy("ai:test").build());
        }

        @Test
        void fallsThroughToLlm_rateJobSubmitted() {
            runSynthesisToComplete();

            IdentifiedCondition asthma = onlyCondition();
            // The LLM's number is served (deterministic engine returned empty).
            assertEquals(20, asthma.getEstimatedRating(),
                    "engine can't rate from these atoms ⇒ LLM's 20% stands");
            // The LLM rate job WAS fanned out for this condition.
            assertEquals(1, rateJobsFor(asthma.getId()),
                    "flag ON but criteria unmet: the LLM rate job MUST be submitted");
        }
    }

    // -------------------------------------------------------------------------
    // 3. Flag OFF (default) ⇒ LLM path, deterministic engine never consulted.
    // -------------------------------------------------------------------------

    @Nested
    @DataJpaTest
    @Import({
            LlmProviderRouterTestConfig.class, LlmJobService.class, LlmJobSubmitter.class, LlmJobPoller.class,
            SynthesisStateMachine.class, com.afterduty.service.DomainCorrectionsService.class, ConditionIdentificationAgent.class, DuplicateConditionMerger.class,
            RatingAgent.class, SynthesisVerificationAgent.class, EnhancedSynthesisOrchestrator.class,
            VasrdDecisionEngine.class,
            ConditionGenerationService.class, com.afterduty.service.AnalysisScheduler.class,
            com.afterduty.service.VaMathService.class, PresumptiveRulesService.class
    })
    @TestPropertySource(properties = {
            "va-claim.llm.submitter.batch-size=50",
            "va-claim.llm.submitter.poll-ms=9999999",
            "va-claim.llm.submitter.initial-delay-ms=9999999",
            "va-claim.llm.poller.poll-ms=9999999",
            "va-claim.llm.poller.initial-delay-ms=9999999",
            "va-claim.llm.orphan-deadline-min=30",
            "va-claim.analysis.deterministic-rating=false"
    })
    class FlagOff extends Base {
        @Override
        void seedAtoms(Long claimId) {
            // SAME criteria-supporting FEV-1 atoms as scenario 1: proves that with the flag
            // OFF the deterministic engine is NOT consulted even when it COULD have fired.
            atomRepository.save(Atom.builder().claimId(claimId).evidenceId(10L)
                    .type("clinical_finding").value("Pulmonary function test: FEV-1 66% predicted")
                    .source("PFT report").createdBy("ai:test").build());
        }

        @Test
        void everyConditionGoesToLlm_asToday() {
            runSynthesisToComplete();

            IdentifiedCondition asthma = onlyCondition();
            // Flag OFF: the LLM's number stands, NOT the engine's 30% — proving the engine
            // was never consulted despite the FEV-1 being present.
            assertEquals(20, asthma.getEstimatedRating(),
                    "flag OFF: LLM's 20% is served; the deterministic engine must not fire");
            assertEquals(1, rateJobsFor(asthma.getId()),
                    "flag OFF: the LLM rate job MUST be submitted, exactly as today");
        }
    }

    // -------------------------------------------------------------------------
    // 4. Flag ON + uncovered non-quantitative code (PTSD 9411) ⇒ always LLM.
    // -------------------------------------------------------------------------

    @Nested
    @DataJpaTest
    @Import({
            LlmProviderRouterTestConfig.class, LlmJobService.class, LlmJobSubmitter.class, LlmJobPoller.class,
            SynthesisStateMachine.class, com.afterduty.service.DomainCorrectionsService.class, ConditionIdentificationAgent.class, DuplicateConditionMerger.class,
            RatingAgent.class, SynthesisVerificationAgent.class, EnhancedSynthesisOrchestrator.class,
            VasrdDecisionEngine.class,
            ConditionGenerationService.class, com.afterduty.service.AnalysisScheduler.class,
            com.afterduty.service.VaMathService.class, PresumptiveRulesService.class
    })
    @TestPropertySource(properties = {
            "va-claim.llm.submitter.batch-size=50",
            "va-claim.llm.submitter.poll-ms=9999999",
            "va-claim.llm.submitter.initial-delay-ms=9999999",
            "va-claim.llm.poller.poll-ms=9999999",
            "va-claim.llm.poller.initial-delay-ms=9999999",
            "va-claim.llm.orphan-deadline-min=30",
            "va-claim.analysis.deterministic-rating=true"
    })
    class FlagOnUncoveredCode extends Base {
        @Override
        String identifyBody() { return IDENTIFY_PTSD; }

        @Override
        void seedAtoms(Long claimId) {
            atomRepository.save(Atom.builder().claimId(claimId).evidenceId(10L)
                    .type("diagnosis").value("PTSD with occupational impairment")
                    .source("psych eval").createdBy("ai:test").build());
        }

        @Test
        void uncoveredCodeAlwaysLlm_evenWithFlagOn() {
            runSynthesisToComplete();

            IdentifiedCondition ptsd = onlyCondition();
            assertEquals("9411", ptsd.getVasrdCode());
            // 9411 is not in VasrdDecisionEngine's switch ⇒ the LLM number is served.
            assertEquals(20, ptsd.getEstimatedRating(),
                    "uncovered code ⇒ LLM's 20% stands");
            assertEquals(1, rateJobsFor(ptsd.getId()),
                    "flag ON but uncovered code: the LLM rate job MUST be submitted");
        }
    }
}
