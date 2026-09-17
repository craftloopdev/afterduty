package com.afterduty.service.synthesis;

import com.afterduty.model.Atom;
import com.afterduty.model.IdentifiedCondition;
import com.afterduty.repository.AtomRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * Secondary-aware analysis (38 CFR 3.310) — deterministic triad reconciliation.
 *
 * <p>The production case that motivated this: GERD secondary to PTSD. The identify
 * pipeline force-fit the DIRECT triad and marked the "in-service" leg PARTIAL with
 * secondary-link content, so a potentially COMPLETE secondary claim looked
 * incomplete. {@link EnhancedSynthesisOrchestrator#reconcileSecondary} rewrites the
 * in-service leg to represent the load-bearing question — is the PRIMARY
 * service-connected? — resolving that status from evidence atoms (KNOWN), an
 * in-claim strong primary (LIKELY), or neither (UNKNOWN → ask the veteran).
 *
 * <p>Mirrors {@link PresumptiveReconciliationTest}: mocked {@link AtomRepository},
 * real orchestrator logic, no AI.
 */
@ExtendWith(MockitoExtension.class)
class SecondaryAwareReconciliationTest {

    @Mock AtomRepository atomRepository;

    private EnhancedSynthesisOrchestrator orchestrator;

    private static final Long CLAIM_ID = 42L;

    @BeforeEach
    void setUp() {
        orchestrator = new EnhancedSynthesisOrchestrator();
        orchestrator.setAtomRepository(atomRepository);
    }

    private Atom atom(String value) {
        return Atom.builder().id(1L).claimId(CLAIM_ID).type("event").value(value).source("VA").build();
    }

    /** GERD (7346), claimed secondary to PTSD, with a fake in-service bullet the identify LLM produced. */
    private IdentifiedCondition gerdSecondaryToPtsd() {
        Map<String, Object> fakeInService = new LinkedHashMap<>();
        fakeInService.put("status", "PARTIAL");
        fakeInService.put("evidence", new ArrayList<>(List.of(
                "PTSD from combat service is primary link to GERD",
                "No documented in-service event for GERD itself")));
        fakeInService.put("confidence", 0.4);

        Map<String, Object> nexus = new LinkedHashMap<>();
        nexus.put("status", "MODERATE");
        nexus.put("evidence", new ArrayList<>(List.of(
                "GERD medically linked to PTSD medications and stress (causation)")));
        nexus.put("confidence", 0.6);

        return IdentifiedCondition.builder()
                .id(100L).claimId(CLAIM_ID).name("GERD (Gastroesophageal Reflux Disease)")
                .vasrdCode("7346").bodySystem("digestive").estimatedRating(30)
                .secondaryTo("PTSD (Post-Traumatic Stress Disorder)")
                .triadInService(fakeInService).triadNexus(nexus).build();
    }

    private IdentifiedCondition strongPtsd() {
        Map<String, Object> diag = new LinkedHashMap<>();
        diag.put("status", "STRONG");
        diag.put("evidence", new ArrayList<>(List.of("PTSD diagnosed by VA psychologist 2020")));
        Map<String, Object> nexus = new LinkedHashMap<>();
        nexus.put("status", "STRONG");
        nexus.put("evidence", new ArrayList<>(List.of("Combat stressor documented in DD-214")));
        return IdentifiedCondition.builder()
                .id(101L).claimId(CLAIM_ID).name("PTSD (Post-Traumatic Stress Disorder)")
                .vasrdCode("9411").bodySystem("mental").estimatedRating(70)
                .triadDiagnosis(diag).triadNexus(nexus).build();
    }

    @SuppressWarnings("unchecked")
    @Test
    void primaryLikely_inClaimStrongPtsd_reframesInServiceLegToPrimarySc_moderate() {
        // No SC atom on file, but PTSD is present as a strong direct claim → LIKELY.
        when(atomRepository.findByClaimIdAndSupersededByIsNull(CLAIM_ID)).thenReturn(List.of(
                atom("Combat deployment 2004"), atom("GERD symptoms since 2021")));

        IdentifiedCondition gerd = gerdSecondaryToPtsd();
        List<IdentifiedCondition> conditions = new ArrayList<>(List.of(gerd, strongPtsd()));

        int reframed = orchestrator.reconcileSecondary(conditions, CLAIM_ID);

        assertThat(reframed).isEqualTo(1);

        Map<String, Object> leg = gerd.getTriadInService();
        assertThat(leg.get("status")).isEqualTo("MODERATE");
        // Reasoning names the primary and the secondary framing.
        String reasoning = String.valueOf(leg.get("reasoning")).toLowerCase();
        assertThat(reasoning).contains("ptsd");
        assertThat(reasoning).contains("3.310");
        List<String> evidence = (List<String>) leg.get("evidence");
        String joined = String.join(" | ", evidence).toLowerCase();
        assertThat(joined).contains("ptsd");
        assertThat(joined).contains("not yet");   // "likely, not yet granted"
        // Fake in-service ask removed; real primary-link content is not fabricated back in.
        assertThat(joined).doesNotContain("no documented in-service event");

        // Rating NUMBER untouched (GERD stays on its own code 7346).
        assertThat(gerd.getEstimatedRating()).isEqualTo(30);
        // Transitive dependency surfaced in the rationale.
        assertThat(gerd.getRatingRationale().toLowerCase())
                .contains("secondary to").contains("ptsd").contains("3.310");

        // Nexus leg preserved (causation link intact).
        assertThat(gerd.getTriadNexus().get("status")).isEqualTo("MODERATE");
        List<String> nexusEvidence = (List<String>) gerd.getTriadNexus().get("evidence");
        assertThat(String.join(" ", nexusEvidence).toLowerCase()).contains("causation");
    }

    @SuppressWarnings("unchecked")
    @Test
    void primaryKnown_scAtomOnFile_reframesInServiceLegToStrong() {
        when(atomRepository.findByClaimIdAndSupersededByIsNull(CLAIM_ID)).thenReturn(List.of(
                atom("PTSD is service connected, rated at 70%"),
                atom("GERD symptoms since 2021")));

        IdentifiedCondition gerd = gerdSecondaryToPtsd();
        // PTSD NOT in the claim as a condition — only the SC atom. Still KNOWN.
        List<IdentifiedCondition> conditions = new ArrayList<>(List.of(gerd));

        int reframed = orchestrator.reconcileSecondary(conditions, CLAIM_ID);

        assertThat(reframed).isEqualTo(1);
        Map<String, Object> leg = gerd.getTriadInService();
        assertThat(leg.get("status")).isEqualTo("STRONG");
        List<String> evidence = (List<String>) leg.get("evidence");
        assertThat(String.join(" ", evidence).toLowerCase())
                .contains("service-connected").contains("ptsd");
        assertThat(gerd.getRatingRationale().toLowerCase()).contains("established");
    }

    @SuppressWarnings("unchecked")
    @Test
    void primaryUnknown_noPtsdConditionNoScAtom_reframesToMissingWithConfirmBullet() {
        when(atomRepository.findByClaimIdAndSupersededByIsNull(CLAIM_ID)).thenReturn(List.of(
                atom("GERD symptoms since 2021"), atom("Routine training at Fort Sill")));

        IdentifiedCondition gerd = gerdSecondaryToPtsd();
        // No PTSD condition, no SC atom → UNKNOWN.
        List<IdentifiedCondition> conditions = new ArrayList<>(List.of(gerd));

        int reframed = orchestrator.reconcileSecondary(conditions, CLAIM_ID);

        assertThat(reframed).isEqualTo(1);
        Map<String, Object> leg = gerd.getTriadInService();
        assertThat(leg.get("status")).isEqualTo("MISSING");
        List<String> evidence = (List<String>) leg.get("evidence");
        String joined = String.join(" | ", evidence).toLowerCase();
        // The ask-the-veteran bullet the gap layer keys on.
        assertThat(joined).contains("confirm your");
        assertThat(joined).contains("ptsd");
        assertThat(joined).contains("service-connected");
        assertThat(gerd.getRatingRationale().toLowerCase()).contains("confirming");
    }

    @Test
    void nonSecondaryDirectCondition_isUntouched() {
        lenient().when(atomRepository.findByClaimIdAndSupersededByIsNull(CLAIM_ID))
                .thenReturn(List.of(atom("Combat deployment 2004")));

        Map<String, Object> inService = new LinkedHashMap<>();
        inService.put("status", "STRONG");
        inService.put("evidence", new ArrayList<>(List.of("In-service knee injury 2005")));
        inService.put("confidence", 0.9);
        IdentifiedCondition knee = IdentifiedCondition.builder()
                .id(300L).claimId(CLAIM_ID).name("Right Knee Strain").vasrdCode("5260")
                .bodySystem("musculoskeletal").estimatedRating(10)
                .triadInService(inService).build();   // secondaryTo == null

        List<IdentifiedCondition> conditions = new ArrayList<>(List.of(knee));
        int reframed = orchestrator.reconcileSecondary(conditions, CLAIM_ID);

        assertThat(reframed).isZero();
        // Direct in-service leg is exactly as it was.
        assertThat(knee.getTriadInService().get("status")).isEqualTo("STRONG");
        assertThat(knee.getTriadInService().get("reasoning")).isNull();
        assertThat(knee.getRatingRationale()).isNull();
    }

    @Test
    void noOpWhenAtomRepositoryAbsent_stillReframesFromInClaimPrimary() {
        // No AtomRepository (bare orchestrator) → KNOWN can't be inferred, but an
        // in-claim strong primary still yields LIKELY (degrades safely).
        EnhancedSynthesisOrchestrator bare = new EnhancedSynthesisOrchestrator();
        IdentifiedCondition gerd = gerdSecondaryToPtsd();
        List<IdentifiedCondition> conditions = new ArrayList<>(List.of(gerd, strongPtsd()));

        int reframed = bare.reconcileSecondary(conditions, CLAIM_ID);

        assertThat(reframed).isEqualTo(1);
        assertThat(gerd.getTriadInService().get("status")).isEqualTo("MODERATE");
    }

    @Test
    void mapToCondition_parsesSecondaryTo_andTrimsBlankToNull() {
        // Present secondary_to → mapped through.
        Map<String, Object> secondaryMap = new LinkedHashMap<>();
        secondaryMap.put("name", "GERD (Gastroesophageal Reflux Disease)");
        secondaryMap.put("vasrd_code", "7346");
        secondaryMap.put("secondary_to", "PTSD (Post-Traumatic Stress Disorder)");
        IdentifiedCondition secondary = orchestrator.mapToCondition(secondaryMap, CLAIM_ID);
        assertThat(secondary.getSecondaryTo()).isEqualTo("PTSD (Post-Traumatic Stress Disorder)");

        // Absent secondary_to → null (direct claim).
        Map<String, Object> directMap = new LinkedHashMap<>();
        directMap.put("name", "Right Knee Strain");
        directMap.put("vasrd_code", "5260");
        assertThat(orchestrator.mapToCondition(directMap, CLAIM_ID).getSecondaryTo()).isNull();

        // Blank/whitespace secondary_to → normalized to null (not a phantom secondary).
        Map<String, Object> blankMap = new LinkedHashMap<>();
        blankMap.put("name", "Tinnitus");
        blankMap.put("vasrd_code", "6260");
        blankMap.put("secondary_to", "   ");
        assertThat(orchestrator.mapToCondition(blankMap, CLAIM_ID).getSecondaryTo()).isNull();
    }

    @Test
    void flagOffSemantics_areTheCallerGate_reconcileIsANoOpForDirectClaims() {
        // The flag lives in SynthesisStateMachine; the orchestrator method itself is
        // only ever called when the flag is ON. But even when called, a run with NO
        // secondary conditions reframes nothing — the OFF-equivalent for direct claims.
        lenient().when(atomRepository.findByClaimIdAndSupersededByIsNull(CLAIM_ID))
                .thenReturn(List.of(atom("Combat deployment 2004")));
        List<IdentifiedCondition> conditions = new ArrayList<>(List.of(strongPtsd()));
        assertThat(orchestrator.reconcileSecondary(conditions, CLAIM_ID)).isZero();
    }

    @Test
    void fuzzyMatch_ptsdAbbreviationMatchesFullParentheticalName() {
        // "PTSD" (secondaryTo) ↔ "PTSD (Post-Traumatic Stress Disorder)" (condition name).
        assertThat(orchestrator.namesMatchFuzzy(
                "PTSD (Post-Traumatic Stress Disorder)", "PTSD")).isTrue();
        assertThat(orchestrator.namesMatchFuzzy(
                "GERD (Gastroesophageal Reflux Disease)",
                "GERD (Gastroesophageal Reflux Disease)")).isTrue();
        // Unrelated conditions must not collide.
        assertThat(orchestrator.namesMatchFuzzy("Bilateral Tinnitus", "Lumbar Strain")).isFalse();
    }
}
