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
import static org.assertj.core.api.Assertions.within;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * Rating honesty (owner-approved) — deterministic objective-evidence tagging +
 * evidence-based confidence tempering ({@code EnhancedSynthesisOrchestrator
 * .assessRatingEvidence} / {@code temperedConfidence}).
 *
 * <p>The production case that motivated this: asthma 6602 rated 30% with a high
 * self-reported LLM confidence but NO pulmonary-function test (PFT/FEV-1) in the atoms.
 * The pass tags the estimate "needs a PFT…" and pulls the served confidence below the
 * model's self-report so an unsupported rating can't display 90%+. Mirrors
 * {@link SecondaryAwareReconciliationTest}: mocked {@link AtomRepository}, real
 * orchestrator logic, no AI.
 */
@ExtendWith(MockitoExtension.class)
class RatingEvidenceAssessmentTest {

    @Mock AtomRepository atomRepository;

    private EnhancedSynthesisOrchestrator orchestrator;

    private static final Long CLAIM_ID = 42L;

    @BeforeEach
    void setUp() {
        orchestrator = new EnhancedSynthesisOrchestrator();
        orchestrator.setAtomRepository(atomRepository);
        orchestrator.setRatingEvidenceRequirements(new RatingEvidenceRequirements());
    }

    private Atom atom(String value) {
        return Atom.builder().id(1L).claimId(CLAIM_ID).type("clinical_finding").value(value).source("VA").build();
    }

    /** A supported triad leg (STRONG). */
    private Map<String, Object> strongLeg() {
        Map<String, Object> leg = new LinkedHashMap<>();
        leg.put("status", "STRONG");
        leg.put("evidence", new ArrayList<>(List.of("supported")));
        leg.put("confidence", 0.9);
        return leg;
    }

    /** Asthma 6602 rated 30%, high self-reported confidence, fully-supported triad. */
    private IdentifiedCondition asthma6602(double llmConfidence) {
        return IdentifiedCondition.builder()
                .id(100L).claimId(CLAIM_ID).name("Asthma").vasrdCode("6602")
                .bodySystem("respiratory").estimatedRating(30).confidence(llmConfidence)
                .ratingRationale("Daily inhalational therapy documented — 30% under 6602.")
                .triadDiagnosis(strongLeg()).triadInService(strongLeg()).triadNexus(strongLeg())
                .build();
    }

    @Test
    void asthma6602_noPftAtom_tagsNeedsPft_andTempersConfidenceBelowLlm() {
        // No PFT/FEV-1 anywhere in the corpus (no attribution → falls back to corpus).
        when(atomRepository.findByClaimIdAndSupersededByIsNull(CLAIM_ID)).thenReturn(List.of(
                atom("Albuterol inhaler prescribed, used daily"),
                atom("Wheezing on exam, dyspnea on exertion")));

        IdentifiedCondition asthma = asthma6602(0.88);
        int assessed = orchestrator.assessRatingEvidence(new ArrayList<>(List.of(asthma)), CLAIM_ID);

        assertThat(assessed).isEqualTo(1);
        // Note mentions the PFT and reads as an estimate, not an alarm.
        assertThat(asthma.getRatingEvidenceNote()).isNotNull();
        String note = asthma.getRatingEvidenceNote().toLowerCase();
        assertThat(note).contains("estimate").contains("pulmonary function").contains("pft");
        // Confidence tempered well below the LLM's 0.88 (full triad → 1.0, min(0.88,1.0)=0.88,
        // ×0.6 = 0.528). Spec: was 0.88 → now ≤ ~0.6.
        assertThat(asthma.getConfidence()).isLessThanOrEqualTo(0.6);
        assertThat(asthma.getConfidence()).isCloseTo(0.528, within(1e-9));
        // Rating NUMBER untouched.
        assertThat(asthma.getEstimatedRating()).isEqualTo(30);
    }

    @Test
    void asthma6602_withFev1Atom_noNote_confidenceNotPenalized() {
        when(atomRepository.findByClaimIdAndSupersededByIsNull(CLAIM_ID)).thenReturn(List.of(
                atom("Spirometry 2023: FEV-1 62% predicted, FEV-1/FVC 0.68"),
                atom("Albuterol inhaler prescribed")));

        IdentifiedCondition asthma = asthma6602(0.88);
        int assessed = orchestrator.assessRatingEvidence(new ArrayList<>(List.of(asthma)), CLAIM_ID);

        assertThat(assessed).isEqualTo(1);
        // Measure present → no note.
        assertThat(asthma.getRatingEvidenceNote()).isNull();
        // Full triad, present → confidence = min(0.88, 1.0) = 0.88, NOT penalized.
        assertThat(asthma.getConfidence()).isCloseTo(0.88, within(1e-9));
        assertThat(asthma.getEstimatedRating()).isEqualTo(30);
    }

    @Test
    void unmappedCode_mentalHealth_isCompletelyUntouched() {
        lenient().when(atomRepository.findByClaimIdAndSupersededByIsNull(CLAIM_ID))
                .thenReturn(List.of(atom("Combat stressor documented")));

        IdentifiedCondition ptsd = IdentifiedCondition.builder()
                .id(200L).claimId(CLAIM_ID).name("PTSD").vasrdCode("9411")
                .bodySystem("mental").estimatedRating(70).confidence(0.9)
                .triadDiagnosis(strongLeg()).triadInService(strongLeg()).triadNexus(strongLeg())
                .build();

        int assessed = orchestrator.assessRatingEvidence(new ArrayList<>(List.of(ptsd)), CLAIM_ID);

        // 9411 is not in the curated map → not assessed, nothing changed.
        assertThat(assessed).isZero();
        assertThat(ptsd.getRatingEvidenceNote()).isNull();
        assertThat(ptsd.getConfidence()).isCloseTo(0.9, within(1e-9)); // LLM value preserved exactly
    }

    @Test
    void hearing6100_noAudiogram_tagsNeedsAudiogram() {
        when(atomRepository.findByClaimIdAndSupersededByIsNull(CLAIM_ID)).thenReturn(List.of(
                atom("Veteran reports difficulty hearing conversations"),
                atom("Noise exposure from artillery in service")));

        IdentifiedCondition hearing = IdentifiedCondition.builder()
                .id(300L).claimId(CLAIM_ID).name("Bilateral Hearing Loss").vasrdCode("6100")
                .bodySystem("auditory").estimatedRating(10).confidence(0.8)
                .triadDiagnosis(strongLeg()).triadInService(strongLeg()).triadNexus(strongLeg())
                .build();

        int assessed = orchestrator.assessRatingEvidence(new ArrayList<>(List.of(hearing)), CLAIM_ID);

        assertThat(assessed).isEqualTo(1);
        assertThat(hearing.getRatingEvidenceNote()).isNotNull();
        assertThat(hearing.getRatingEvidenceNote().toLowerCase())
                .contains("audiogram").contains("maryland cnc");
        assertThat(hearing.getConfidence()).isLessThan(0.8);
    }

    @Test
    void attributedAtoms_scopeTheCheck_otherConditionsPftDoesNotSatisfy() {
        // A DIFFERENT respiratory condition's PFT lives in the corpus, but THIS asthma's
        // own attributed atoms (id 500) hold no PFT → must still tag.
        Atom noPft = Atom.builder().id(500L).claimId(CLAIM_ID).type("clinical_finding")
                .value("Albuterol used daily; wheezing on exam").source("VA").build();
        when(atomRepository.findAllById(List.of(500L))).thenReturn(List.of(noPft));

        IdentifiedCondition asthma = asthma6602(0.85);
        asthma.setSupportingAtomIds(new ArrayList<>(List.of(500L)));

        int assessed = orchestrator.assessRatingEvidence(new ArrayList<>(List.of(asthma)), CLAIM_ID);

        assertThat(assessed).isEqualTo(1);
        assertThat(asthma.getRatingEvidenceNote()).isNotNull();
        assertThat(asthma.getRatingEvidenceNote().toLowerCase()).contains("pft");
        // findByClaimIdAndSupersededByIsNull is NEVER consulted (attribution scoped it).
    }

    @Test
    void temperedConfidenceFormula_isMinCappedThenPenalizedWithFloor() {
        // present=true → base = min(llm, completeness), no penalty.
        assertThat(orchestrator.temperedConfidence(0.88, 1.0, true)).isCloseTo(0.88, within(1e-9));
        assertThat(orchestrator.temperedConfidence(0.95, 0.67, true)).isCloseTo(0.67, within(1e-9));

        // present=false → base ×0.6, floored at 0.40.
        assertThat(orchestrator.temperedConfidence(0.88, 1.0, false)).isCloseTo(0.528, within(1e-9));
        // Low completeness pushes base low; ×0.6 would undercut the floor → floor wins.
        assertThat(orchestrator.temperedConfidence(0.90, 0.33, false)).isCloseTo(0.40, within(1e-9));
        // A near-zero base still floors at 0.40 (an inferred estimate isn't "no confidence").
        assertThat(orchestrator.temperedConfidence(0.10, 0.0, false)).isCloseTo(0.40, within(1e-9));

        // Inputs clamped to [0,1].
        assertThat(orchestrator.temperedConfidence(1.5, 2.0, true)).isCloseTo(1.0, within(1e-9));
        assertThat(orchestrator.temperedConfidence(-0.3, 0.9, true)).isCloseTo(0.0, within(1e-9));
    }

    @Test
    void triadCompleteness_isFractionOfStrongOrModerateLegs() {
        IdentifiedCondition full = asthma6602(0.5);
        assertThat(orchestrator.triadCompleteness(full)).isCloseTo(1.0, within(1e-9));

        Map<String, Object> missing = new LinkedHashMap<>();
        missing.put("status", "MISSING");
        IdentifiedCondition twoOfThree = asthma6602(0.5);
        twoOfThree.setTriadNexus(missing);
        assertThat(orchestrator.triadCompleteness(twoOfThree)).isCloseTo(2.0 / 3.0, within(1e-9));

        IdentifiedCondition none = IdentifiedCondition.builder()
                .id(1L).claimId(CLAIM_ID).name("X").vasrdCode("6602").build(); // null triads
        assertThat(orchestrator.triadCompleteness(none)).isCloseTo(0.0, within(1e-9));
    }

    @Test
    void weakTriad_missingMeasure_stillFlooredNotZero() {
        // Weak triad (completeness low) + missing PFT → base tiny, but floor holds at 0.40.
        when(atomRepository.findByClaimIdAndSupersededByIsNull(CLAIM_ID))
                .thenReturn(List.of(atom("Reports shortness of breath")));

        Map<String, Object> weak = new LinkedHashMap<>();
        weak.put("status", "WEAK");
        IdentifiedCondition asthma = IdentifiedCondition.builder()
                .id(600L).claimId(CLAIM_ID).name("Asthma").vasrdCode("6602")
                .bodySystem("respiratory").estimatedRating(30).confidence(0.7)
                .triadDiagnosis(weak).triadInService(weak).triadNexus(weak)
                .build();

        orchestrator.assessRatingEvidence(new ArrayList<>(List.of(asthma)), CLAIM_ID);
        assertThat(asthma.getConfidence()).isCloseTo(0.40, within(1e-9));
        assertThat(asthma.getRatingEvidenceNote()).isNotNull();
    }

    @Test
    void noOp_onNullOrEmptyConditionList() {
        assertThat(orchestrator.assessRatingEvidence(null, CLAIM_ID)).isZero();
        assertThat(orchestrator.assessRatingEvidence(new ArrayList<>(), CLAIM_ID)).isZero();
    }
}
