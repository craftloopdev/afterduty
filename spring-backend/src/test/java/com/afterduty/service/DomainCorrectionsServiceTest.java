package com.afterduty.service;

import com.afterduty.model.IdentifiedCondition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The self-correction knowledge base — loads the REAL domain-corrections.json
 * (the file is the product; a fixture would let it rot) and pins both
 * enforcement lanes: deterministic strip + prompt corpus.
 */
class DomainCorrectionsServiceTest {

    private final DomainCorrectionsService svc = new DomainCorrectionsService();

    @BeforeEach
    void setUp() {
        svc.load();
    }

    private static IdentifiedCondition cond(String name, String code, boolean presumptive, String basis) {
        Map<String, Object> nexus = new LinkedHashMap<>();
        nexus.put("status", "STRONG");
        nexus.put("evidence", List.of("Nexus presumed under Gulf War Presumptives"));
        return IdentifiedCondition.builder()
                .id(1L).claimId(1L).name(name).vasrdCode(code)
                .isPresumptive(presumptive).presumptiveBasis(basis)
                .triadNexus(nexus).build();
    }

    @Test
    void loadsTheShippedKb() {
        assertThat(svc.size()).isGreaterThanOrEqualTo(2); // DC-2026-001 + -002
    }

    @Test
    void stripsGulfWarPresumptiveFromDiagnosedMigraine() {
        IdentifiedCondition migraine = cond("Migraine Headaches", "8100", true,
                "Gulf War Presumptives (provisional — confirm your qualifying service dates/location to finalize)");
        int repaired = svc.enforce(new ArrayList<>(List.of(migraine)));

        assertThat(repaired).isEqualTo(1);
        assertThat(migraine.getIsPresumptive()).isFalse();
        assertThat(migraine.getPresumptiveBasis()).isNull();
        // Nexus rewritten to an honest needs-evidence state, correction cited.
        assertThat(migraine.getTriadNexus().get("status")).isEqualTo("WEAK");
        @SuppressWarnings("unchecked")
        List<String> evidence = (List<String>) migraine.getTriadNexus().get("evidence");
        String joined = String.join(" | ", evidence);
        assertThat(joined).contains("DC-2026-001");
        assertThat(joined).contains("3.317");
    }

    @Test
    void stripsGulfWarPresumptiveFromDiagnosedSleepApnea() {
        IdentifiedCondition osa = cond("Obstructive Sleep Apnea (OSA)", "6847", true, "Gulf War Presumptives");
        assertThat(svc.enforce(new ArrayList<>(List.of(osa)))).isEqualTo(1);
        assertThat(osa.getIsPresumptive()).isFalse();
    }

    @Test
    void basisMustMatch_pactPresumptiveMigraineHypotheticalIsNotStripped() {
        // The corrections are NARROW: DC-2026-001 targets the Gulf War basis
        // specifically — a different basis is a different (unrecorded) claim.
        IdentifiedCondition migraine = cond("Migraine Headaches", "8100", true, "PACT Act — Burn Pit Exposure");
        assertThat(svc.enforce(new ArrayList<>(List.of(migraine)))).isZero();
        assertThat(migraine.getIsPresumptive()).isTrue();
    }

    @Test
    void nameMustMatch_otherGulfWarPresumptivesUntouched() {
        IdentifiedCondition fibro = cond("Fibromyalgia", "5025", true, "Gulf War Presumptives");
        assertThat(svc.enforce(new ArrayList<>(List.of(fibro)))).isZero();
        assertThat(fibro.getIsPresumptive()).isTrue();
    }

    @Test
    void nonPresumptiveConditionsNeverTouched() {
        IdentifiedCondition migraine = cond("Migraine Headaches", "8100", false, null);
        Map<String, Object> originalNexus = migraine.getTriadNexus();
        assertThat(svc.enforce(new ArrayList<>(List.of(migraine)))).isZero();
        assertThat(migraine.getTriadNexus()).isSameAs(originalNexus);
    }

    @Test
    void blankBasisStillStripped_unexplainedPresumptiveCannotStand() {
        // The identify LLM can set is_presumptive=true with no basis at all —
        // a name-matched correction still applies (P2 hardening).
        IdentifiedCondition migraine = cond("Migraine Headaches", "8100", true, null);
        assertThat(svc.enforce(new ArrayList<>(List.of(migraine)))).isEqualTo(1);
        assertThat(migraine.getIsPresumptive()).isFalse();
    }

    @Test
    void llmPhrasedBasisVariantsAreCaught() {
        for (String basis : List.of("Southwest Asia environmental exposure",
                "38 CFR 3.317 undiagnosed illness presumption")) {
            IdentifiedCondition migraine = cond("Migraine Headaches", "8100", true, basis);
            assertThat(svc.enforce(new ArrayList<>(List.of(migraine))))
                    .as("basis: %s", basis).isEqualTo(1);
        }
    }

    @Test
    void genuinelyUndiagnosedIllnessConditionIsNeverStripped() {
        // A true 3.317 undiagnosed-illness condition is a DIFFERENT claim than
        // the diagnosed one the correction targets (P5 guard).
        IdentifiedCondition undiagnosed = cond(
                "Undiagnosed Illness (migraine-like chronic headaches)", "8100", true, "Gulf War Presumptives");
        assertThat(svc.enforce(new ArrayList<>(List.of(undiagnosed)))).isZero();
        assertThat(undiagnosed.getIsPresumptive()).isTrue();
    }

    @Test
    void correctedNexusCarriesTheSchemaConfidenceKey() {
        IdentifiedCondition migraine = cond("Migraine Headaches", "8100", true, "Gulf War Presumptives");
        svc.enforce(new ArrayList<>(List.of(migraine)));
        assertThat(migraine.getTriadNexus().get("confidence")).isEqualTo(0.9);
    }

    @Test
    void promptCorpusCarriesEveryEntryWithAuthority() {
        String corpus = svc.promptCorpus();
        assertThat(corpus).contains("KNOWN CORRECTIONS");
        assertThat(corpus).contains("DC-2026-001");
        assertThat(corpus).contains("DC-2026-002");
        assertThat(corpus).contains("38 CFR 3.317");
    }
}
