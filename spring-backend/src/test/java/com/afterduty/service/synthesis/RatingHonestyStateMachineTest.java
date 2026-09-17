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
 * Rating honesty — end-to-end LIVE-path proof that the objective-evidence post-pass runs
 * inside {@link SynthesisStateMachine} (flag ON) and is fully disabled (flag OFF).
 *
 * <p>A veteran with asthma (6602) rated 30% at a HIGH self-reported LLM confidence (0.8)
 * but NO pulmonary-function test (PFT/FEV-1) in the evidence atoms should come out of
 * synthesis with a "needs a PFT" note and a confidence tempered below the LLM's; with the
 * flag OFF it should keep the LLM's raw 0.8 and carry no note. Mirrors
 * {@link PresumptiveAtomInferenceStateMachineTest}'s two-@Nested flag-state structure.
 */
class RatingHonestyStateMachineTest {

    // Identify + merge return asthma 6602 with a supported triad (so completeness=1.0,
    // isolating the missing-PFT penalty). The identify LLM leaves it non-presumptive.
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
    // The rate agent self-reports 0.88 confidence over evidence that lacks any PFT.
    static final String RATE = """
            {"estimated_rating":30,"rating_rationale":"Daily inhalational therapy — 30% under 6602.","confidence":0.88}
            """;

    /** Shared harness both flag states reuse. */
    abstract static class Base {
        @Autowired SynthesisStateMachine synthesis;
        @Autowired LlmJobSubmitter submitter;
        @Autowired LlmJobPoller poller;
        @Autowired ClaimRepository claimRepository;
        @Autowired AtomRepository atomRepository;
        @Autowired IdentifiedConditionRepository conditionRepository;
        @Autowired FakeLlmAsyncProvider fake;

        Claim claim;

        @BeforeEach
        void setUp() {
            fake.reset();
            fake.setResponse("synthesis_identify", IDENTIFY_ASTHMA);
            fake.setResponse("synthesis_duplicate_merger", IDENTIFY_ASTHMA);
            fake.setResponse("synthesis_rate", RATE);
            fake.setResponse("synthesis_verify", "[]");

            claim = claimRepository.save(Claim.builder().userId(1L).status(Claim.ClaimStatus.DRAFT).build());
            // Respiratory evidence WITHOUT any PFT/FEV-1 — the exact honesty gap.
            atomRepository.save(Atom.builder().claimId(claim.getId()).evidenceId(10L)
                    .type("medication").value("Albuterol inhaler prescribed, used daily").source("VA")
                    .createdBy("ai:test").build());
            atomRepository.save(Atom.builder().claimId(claim.getId()).evidenceId(10L)
                    .type("clinical_finding").value("Wheezing on exam; dyspnea on exertion").source("VA")
                    .createdBy("ai:test").build());
        }

        void runSynthesisToComplete() {
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

        IdentifiedCondition onlyCondition() {
            List<IdentifiedCondition> conds = conditionRepository.findByClaimIdAndSupersededByIsNull(claim.getId());
            assertEquals(1, conds.size(), "expected exactly one active condition");
            return conds.get(0);
        }
    }

    @Nested
    @DataJpaTest
    @Import({
            LlmProviderRouterTestConfig.class, LlmJobService.class, LlmJobSubmitter.class, LlmJobPoller.class,
            SynthesisStateMachine.class, com.afterduty.service.DomainCorrectionsService.class, ConditionIdentificationAgent.class, DuplicateConditionMerger.class,
            RatingAgent.class, SynthesisVerificationAgent.class, EnhancedSynthesisOrchestrator.class,
            RatingEvidenceRequirements.class,
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
            "va-claim.analysis.rating-honesty=true"
    })
    class FlagOn extends Base {
        @Test
        void tagsNeedsPftAndTempersConfidenceBelowLlm() {
            runSynthesisToComplete();

            IdentifiedCondition asthma = onlyCondition();
            assertNotNull(asthma.getRatingEvidenceNote(), "missing-PFT asthma should be tagged");
            assertTrue(asthma.getRatingEvidenceNote().toLowerCase().contains("pulmonary function"),
                    "note should name the PFT: " + asthma.getRatingEvidenceNote());
            // 0.88 self-report, full triad (1.0), missing measure → 0.88 × 0.6 = 0.528 ≤ 0.6.
            assertNotNull(asthma.getConfidence());
            assertTrue(asthma.getConfidence() <= 0.6,
                    "confidence must be tempered below the LLM's 0.88: " + asthma.getConfidence());
            assertTrue(asthma.getConfidence() < 0.88, "confidence must be strictly below the LLM self-report");
            // Rating NUMBER untouched.
            assertEquals(30, asthma.getEstimatedRating());
        }
    }

    @Nested
    @DataJpaTest
    @Import({
            LlmProviderRouterTestConfig.class, LlmJobService.class, LlmJobSubmitter.class, LlmJobPoller.class,
            SynthesisStateMachine.class, com.afterduty.service.DomainCorrectionsService.class, ConditionIdentificationAgent.class, DuplicateConditionMerger.class,
            RatingAgent.class, SynthesisVerificationAgent.class, EnhancedSynthesisOrchestrator.class,
            RatingEvidenceRequirements.class,
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
            "va-claim.analysis.rating-honesty=false"
    })
    class FlagOff extends Base {
        @Test
        void leavesConfidenceAndNoteExactlyAsRatingLeftThem() {
            runSynthesisToComplete();

            IdentifiedCondition asthma = onlyCondition();
            assertNull(asthma.getRatingEvidenceNote(), "flag OFF: no evidence note written");
            // flag OFF: the LLM's raw self-reported 0.88 stands untouched.
            assertNotNull(asthma.getConfidence());
            assertEquals(0.88, asthma.getConfidence(), 1e-9,
                    "flag OFF: served confidence is the LLM self-report, not tempered");
            assertEquals(30, asthma.getEstimatedRating());
        }
    }
}
