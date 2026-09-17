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
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Increment B — end-to-end LIVE-path proof that the provisional presumptive
 * reconciliation runs inside {@link SynthesisStateMachine} (flag ON) and is fully
 * disabled (flag OFF). A veteran with an asthma condition (6602) and evidence
 * atoms mentioning an Iraq deployment + burn-pit exposure — but NO manually
 * entered ServiceProfile — should come out of synthesis with a PROVISIONAL PACT
 * presumptive and a rewritten nexus, and with the flag OFF should stay exactly as
 * the identify LLM left it (non-presumptive).
 *
 * <p>Two @Nested inner classes carry different @TestPropertySource flag states.
 */
class PresumptiveAtomInferenceStateMachineTest {

    // Identify + merge return one burn-pit-eligible condition (asthma 6602) that
    // the identify LLM did NOT mark presumptive and whose nexus asks for a letter.
    static final String IDENTIFY_ASTHMA = """
            [
              {"name":"Bronchial Asthma","vasrd_code":"6602","body_system":"respiratory",
               "is_presumptive":false,"presumptive_basis":null,
               "triad_nexus":{"status":"MISSING","evidence":["No specific nexus IMO letter identified"],"confidence":0.2},
               "confidence":0.9}
            ]
            """;
    static final String RATE = """
            {"estimated_rating":30,"rating_rationale":"per evidence","confidence":0.8}
            """;

    private static final String[] BASE_PROPS = {
            "va-claim.llm.submitter.batch-size=50",
            "va-claim.llm.submitter.poll-ms=9999999",
            "va-claim.llm.submitter.initial-delay-ms=9999999",
            "va-claim.llm.poller.poll-ms=9999999",
            "va-claim.llm.poller.initial-delay-ms=9999999",
            "va-claim.llm.orphan-deadline-min=30"
    };

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
            // Evidence atoms that carry the qualifying-service facts — no ServiceProfile row.
            atomRepository.save(Atom.builder().claimId(claim.getId()).evidenceId(10L)
                    .type("event").value("Deployment to Iraq 2004-2005 (OIF)").source("DD-214")
                    .createdBy("ai:test").build());
            atomRepository.save(Atom.builder().claimId(claim.getId()).evidenceId(10L)
                    .type("exposure").value("Documented burn pit exposure at FOB").source("PDHA")
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
            SynthesisStateMachine.class, ConditionIdentificationAgent.class, DuplicateConditionMerger.class,
            RatingAgent.class, SynthesisVerificationAgent.class, EnhancedSynthesisOrchestrator.class,
            ConditionGenerationService.class, com.afterduty.service.AnalysisScheduler.class,
            com.afterduty.service.VaMathService.class, PresumptiveRulesService.class,
            com.afterduty.service.DomainCorrectionsService.class
    })
    @TestPropertySource(properties = {
            "va-claim.llm.submitter.batch-size=50",
            "va-claim.llm.submitter.poll-ms=9999999",
            "va-claim.llm.submitter.initial-delay-ms=9999999",
            "va-claim.llm.poller.poll-ms=9999999",
            "va-claim.llm.poller.initial-delay-ms=9999999",
            "va-claim.llm.orphan-deadline-min=30",
            "va-claim.analysis.presumptive-atom-inference=true"
    })
    class FlagOn extends Base {
        @Test
        void flipsToProvisionalPresumptiveFromAtomEvidence() {
            runSynthesisToComplete();

            IdentifiedCondition asthma = onlyCondition();
            assertTrue(asthma.getIsPresumptive(), "atom-derived burn-pit signal should flip 6602 presumptive");
            assertNotNull(asthma.getPresumptiveBasis());
            assertTrue(asthma.getPresumptiveBasis().contains("provisional"),
                    "basis must be clearly provisional: " + asthma.getPresumptiveBasis());

            @SuppressWarnings("unchecked")
            Map<String, Object> nexus = (Map<String, Object>) (Map<?, ?>) asthma.getTriadNexus();
            assertEquals("STRONG", nexus.get("status"));
            String joined = nexus.get("evidence").toString().toLowerCase();
            assertFalse(joined.contains("nexus letter"), "rewritten nexus must not ask for a nexus letter");
            assertTrue(joined.contains("presumed"), "rewritten nexus should state presumption");
        }
    }

    @Nested
    @DataJpaTest
    @Import({
            LlmProviderRouterTestConfig.class, LlmJobService.class, LlmJobSubmitter.class, LlmJobPoller.class,
            SynthesisStateMachine.class, ConditionIdentificationAgent.class, DuplicateConditionMerger.class,
            RatingAgent.class, SynthesisVerificationAgent.class, EnhancedSynthesisOrchestrator.class,
            ConditionGenerationService.class, com.afterduty.service.AnalysisScheduler.class,
            com.afterduty.service.VaMathService.class, PresumptiveRulesService.class,
            com.afterduty.service.DomainCorrectionsService.class
    })
    @TestPropertySource(properties = {
            "va-claim.llm.submitter.batch-size=50",
            "va-claim.llm.submitter.poll-ms=9999999",
            "va-claim.llm.submitter.initial-delay-ms=9999999",
            "va-claim.llm.poller.poll-ms=9999999",
            "va-claim.llm.poller.initial-delay-ms=9999999",
            "va-claim.llm.orphan-deadline-min=30",
            "va-claim.analysis.presumptive-atom-inference=false"
    })
    class FlagOff extends Base {
        @Test
        void leavesConditionExactlyAsIdentifyLeftIt() {
            runSynthesisToComplete();

            IdentifiedCondition asthma = onlyCondition();
            assertFalse(Boolean.TRUE.equals(asthma.getIsPresumptive()),
                    "flag OFF: is_presumptive stays as the identify LLM set it (false)");
            assertNull(asthma.getPresumptiveBasis(), "flag OFF: no provisional basis written");
            String joined = asthma.getTriadNexus().get("evidence").toString().toLowerCase();
            assertTrue(joined.contains("no specific nexus imo letter"),
                    "flag OFF: original nexus text preserved untouched");
        }
    }
}
