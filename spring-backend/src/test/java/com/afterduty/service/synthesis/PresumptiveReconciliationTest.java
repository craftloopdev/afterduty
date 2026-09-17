package com.afterduty.service.synthesis;

import com.afterduty.model.Atom;
import com.afterduty.model.IdentifiedCondition;
import com.afterduty.repository.AtomRepository;
import com.afterduty.repository.ServiceProfileRepository;
import com.afterduty.service.PresumptiveRulesService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * Increment B — provisional PACT/presumptive reconciliation from evidence atoms.
 *
 * <p>Uses a REAL {@link PresumptiveRulesService} (pure business logic, no AI) and
 * mocked repositories so the assertions exercise the true rules engine + the
 * orchestrator's flip/rewrite logic together.
 */
@ExtendWith(MockitoExtension.class)
class PresumptiveReconciliationTest {

    @Mock AtomRepository atomRepository;
    @Mock ServiceProfileRepository serviceProfileRepository;

    private final PresumptiveRulesService presumptiveRulesService = new PresumptiveRulesService();

    private EnhancedSynthesisOrchestrator orchestrator;

    private static final Long CLAIM_ID = 42L;
    private static final Long USER_ID = 7L;

    @BeforeEach
    void setUp() {
        orchestrator = new EnhancedSynthesisOrchestrator();
        orchestrator.setPresumptiveRulesService(presumptiveRulesService);
        orchestrator.setServiceProfileRepository(serviceProfileRepository);
        orchestrator.setAtomRepository(atomRepository);
        // No stored profile — the veteran never filled it in (the case this targets).
        lenient().when(serviceProfileRepository.findByUserId(USER_ID)).thenReturn(Optional.empty());
    }

    private Atom atom(String value) {
        return Atom.builder().id(1L).claimId(CLAIM_ID).type("event").value(value).source("STR").build();
    }

    private IdentifiedCondition asthmaNotPresumptive() {
        Map<String, Object> staleNexus = new java.util.LinkedHashMap<>();
        staleNexus.put("status", "MISSING");
        staleNexus.put("evidence", new ArrayList<>(List.of(
                "No specific nexus IMO letter identified",
                "Diagnosed with asthma on 2019-03-01")));
        staleNexus.put("confidence", 0.2);
        return IdentifiedCondition.builder()
                .id(100L).claimId(CLAIM_ID).name("Bronchial Asthma").vasrdCode("6602")
                .bodySystem("respiratory").isPresumptive(false).triadNexus(staleNexus).build();
    }

    @Test
    void flipsAsthmaToProvisionalPresumptiveAndRewritesNexus() {
        when(atomRepository.findByClaimIdAndSupersededByIsNull(CLAIM_ID)).thenReturn(List.of(
                atom("Deployment to Iraq 2004-2005"),
                atom("Documented burn pit exposure during deployment")));

        IdentifiedCondition asthma = asthmaNotPresumptive();
        List<IdentifiedCondition> conditions = new ArrayList<>(List.of(asthma));

        int flipped = orchestrator.reconcilePresumptiveFromEvidence(conditions, CLAIM_ID, USER_ID);

        assertThat(flipped).isEqualTo(1);
        assertThat(asthma.getIsPresumptive()).isTrue();
        assertThat(asthma.getPresumptiveBasis())
                .contains("Burn Pit")
                .contains("provisional")
                .contains("confirm");

        // Nexus rewritten to STRONG, presumed-nexus content, no "nexus letter"/"IMO" ask.
        Object status = asthma.getTriadNexus().get("status");
        assertThat(status).isEqualTo("STRONG");

        @SuppressWarnings("unchecked")
        List<String> evidence = (List<String>) asthma.getTriadNexus().get("evidence");
        String joined = String.join(" | ", evidence).toLowerCase();
        // The stale "you still need a nexus letter / IMO letter" ask is gone. (The
        // new presumed-nexus bullet does say "no private nexus opinion (IMO)
        // required" — that's the point — so we assert on the ASK phrasings, not the
        // bare substring "imo".)
        assertThat(joined).doesNotContain("nexus letter");
        assertThat(joined).doesNotContain("imo letter");
        assertThat(joined).doesNotContain("no specific nexus");
        assertThat(joined).contains("presumed");
        assertThat(joined).contains("provisional");
        // Real diagnosis bullet preserved; only the "no nexus letter" ask removed.
        assertThat(evidence).anyMatch(b -> b.contains("Diagnosed with asthma"));
    }

    // DC-2026-001/-002 (domain-corrections.json): the 38 CFR 3.317
    // "Undiagnosed Illness" lanes share VASRD codes with common DIAGNOSED
    // conditions (8100 migraines, 6847 sleep apnea). The bare code join used to
    // flip those to "Gulf War Presumptives" — the exact live bug from the
    // owner's 2026-08-02 screenshot. Undiagnosed lanes must never attach to a
    // diagnosed condition row.
    @Test
    void diagnosedMigraine_neverFlipsViaUndiagnosedHeadacheLane() {
        when(atomRepository.findByClaimIdAndSupersededByIsNull(CLAIM_ID)).thenReturn(List.of(
                atom("Deployment to Kuwait 1991 during Gulf War operations")));

        IdentifiedCondition migraine = IdentifiedCondition.builder()
                .id(200L).claimId(CLAIM_ID).name("Migraine Headaches").vasrdCode("8100")
                .bodySystem("neurological").isPresumptive(false).build();
        IdentifiedCondition sleepApnea = IdentifiedCondition.builder()
                .id(201L).claimId(CLAIM_ID).name("Obstructive Sleep Apnea (OSA)").vasrdCode("6847")
                .bodySystem("respiratory").isPresumptive(false).build();
        List<IdentifiedCondition> conditions = new ArrayList<>(List.of(migraine, sleepApnea));

        int flipped = orchestrator.reconcilePresumptiveFromEvidence(conditions, CLAIM_ID, USER_ID);

        assertThat(flipped).isZero();
        assertThat(migraine.getIsPresumptive()).isFalse();
        assertThat(migraine.getPresumptiveBasis()).isNull();
        assertThat(sleepApnea.getIsPresumptive()).isFalse();
    }

    @Test
    void diagnosedMucmiConditions_stillFlipViaGulfWarRule() {
        // The named MUCMIs (fibromyalgia, CFS, IBS) are genuinely presumptive
        // under 3.317 even when diagnosed — the undiagnosed-lane fix must not
        // sweep them away.
        when(atomRepository.findByClaimIdAndSupersededByIsNull(CLAIM_ID)).thenReturn(List.of(
                atom("Deployment to Kuwait 1991 during Gulf War operations")));

        IdentifiedCondition fibro = IdentifiedCondition.builder()
                .id(210L).claimId(CLAIM_ID).name("Fibromyalgia").vasrdCode("5025")
                .bodySystem("musculoskeletal").isPresumptive(false).build();
        List<IdentifiedCondition> conditions = new ArrayList<>(List.of(fibro));

        int flipped = orchestrator.reconcilePresumptiveFromEvidence(conditions, CLAIM_ID, USER_ID);

        assertThat(flipped).isEqualTo(1);
        assertThat(fibro.getIsPresumptive()).isTrue();
        assertThat(fibro.getPresumptiveBasis()).contains("Gulf War");
    }

    @Test
    void leavesNonPresumptiveCodeUntouched() {
        when(atomRepository.findByClaimIdAndSupersededByIsNull(CLAIM_ID)).thenReturn(List.of(
                atom("Deployment to Iraq 2004-2005"),
                atom("Documented burn pit exposure during deployment")));

        // Tinnitus (6260) is not a PACT burn-pit presumptive condition.
        Map<String, Object> nexus = new java.util.LinkedHashMap<>();
        nexus.put("status", "MISSING");
        nexus.put("evidence", new ArrayList<>(List.of("No nexus letter on file")));
        nexus.put("confidence", 0.3);
        IdentifiedCondition tinnitus = IdentifiedCondition.builder()
                .id(200L).claimId(CLAIM_ID).name("Tinnitus").vasrdCode("6260")
                .bodySystem("ear").isPresumptive(false).triadNexus(nexus).build();

        List<IdentifiedCondition> conditions = new ArrayList<>(List.of(tinnitus));
        int flipped = orchestrator.reconcilePresumptiveFromEvidence(conditions, CLAIM_ID, USER_ID);

        assertThat(flipped).isZero();
        assertThat(tinnitus.getIsPresumptive()).isFalse();
        assertThat(tinnitus.getPresumptiveBasis()).isNull();
        assertThat(tinnitus.getTriadNexus().get("status")).isEqualTo("MISSING");
    }

    @Test
    void alreadyPresumptiveConditionIsNotReflipped() {
        when(atomRepository.findByClaimIdAndSupersededByIsNull(CLAIM_ID)).thenReturn(List.of(
                atom("Deployment to Iraq 2004-2005"), atom("burn pit exposure")));

        IdentifiedCondition asthma = asthmaNotPresumptive();
        asthma.setIsPresumptive(true);
        asthma.setPresumptiveBasis("Already set by identify LLM");

        List<IdentifiedCondition> conditions = new ArrayList<>(List.of(asthma));
        int flipped = orchestrator.reconcilePresumptiveFromEvidence(conditions, CLAIM_ID, USER_ID);

        assertThat(flipped).isZero();
        // Untouched — reconciliation only flips conditions not already presumptive.
        assertThat(asthma.getPresumptiveBasis()).isEqualTo("Already set by identify LLM");
    }

    @Test
    void noMatchWhenAtomsHaveNoQualifyingSignals() {
        when(atomRepository.findByClaimIdAndSupersededByIsNull(CLAIM_ID)).thenReturn(List.of(
                atom("Stationed at Fort Benning, routine training"),
                atom("Annual dental exam")));

        IdentifiedCondition asthma = asthmaNotPresumptive();
        List<IdentifiedCondition> conditions = new ArrayList<>(List.of(asthma));

        int flipped = orchestrator.reconcilePresumptiveFromEvidence(conditions, CLAIM_ID, USER_ID);

        assertThat(flipped).isZero();
        assertThat(asthma.getIsPresumptive()).isFalse();
    }

    @Test
    void noOpWhenOptionalDependenciesAbsent() {
        // Mirror the null-guards in buildPresumptiveContext — an orchestrator wired
        // without the optional beans (e.g. a test slice) must safely return 0.
        EnhancedSynthesisOrchestrator bare = new EnhancedSynthesisOrchestrator();
        IdentifiedCondition asthma = asthmaNotPresumptive();
        List<IdentifiedCondition> conditions = new ArrayList<>(List.of(asthma));

        int flipped = bare.reconcilePresumptiveFromEvidence(conditions, CLAIM_ID, USER_ID);

        assertThat(flipped).isZero();
        assertThat(asthma.getIsPresumptive()).isFalse();
    }
}
