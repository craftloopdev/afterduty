package com.afterduty.service.synthesis;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The curated code → required-objective-evidence map ({@link RatingEvidenceRequirements}).
 * Pure/deterministic; no Spring, no AI.
 */
class RatingEvidenceRequirementsTest {

    private final RatingEvidenceRequirements reqs = new RatingEvidenceRequirements();

    @Test
    void respiratoryCodes_mapToPft() {
        for (String code : List.of("6600", "6602", "6603", "6604", "6825", "6840", "6845", "6846")) {
            assertThat(reqs.forCode(code)).as("code %s should require a PFT", code).isPresent();
            assertThat(reqs.forCode(code).get().measureName()).contains("pulmonary function");
        }
    }

    @Test
    void sleepApnea6847_notMappedToPft() {
        // 6847 (sleep apnea) is graded on a sleep study + CPAP requirement, NOT a PFT —
        // it must never be tagged "needs a PFT".
        assertThat(reqs.forCode("6847")).isEmpty();
    }

    @Test
    void hearing6100_mapsToAudiogram() {
        assertThat(reqs.forCode("6100")).isPresent();
        assertThat(reqs.forCode("6100").get().measureName())
                .contains("audiogram").contains("Maryland CNC");
    }

    @Test
    void suffixedCode_resolvesToItsFamily() {
        // "6602-..." (a suffixed diagnostic code) still resolves to asthma → PFT.
        assertThat(reqs.forCode("6602-7833")).isPresent();
        assertThat(reqs.forCode("6602-7833").get().measureName()).contains("pulmonary function");
    }

    @Test
    void unmappedCodes_returnEmpty_neverGuess() {
        // Mental health, musculoskeletal, digestive, tinnitus — deliberately NOT mapped.
        for (String code : List.of("9411", "5260", "7346", "6260", "7913")) {
            assertThat(reqs.forCode(code)).as("code %s must be unmapped", code).isEmpty();
        }
        // Null / blank / non-numeric → unmapped (never tagged).
        assertThat(reqs.forCode(null)).isEmpty();
        assertThat(reqs.forCode("")).isEmpty();
        assertThat(reqs.forCode("   ")).isEmpty();
        assertThat(reqs.forCode("abc")).isEmpty();
    }

    @Test
    void measurePresent_matchesKeywordsCaseInsensitively() {
        RatingEvidenceRequirements.Requirement pft = reqs.forCode("6602").orElseThrow();
        assertThat(reqs.measurePresent(pft, List.of("Spirometry: FEV-1 62% predicted"))).isTrue();
        assertThat(reqs.measurePresent(pft, List.of("PULMONARY FUNCTION TEST performed"))).isTrue();
        assertThat(reqs.measurePresent(pft, List.of("Albuterol inhaler prescribed daily"))).isFalse();
        // Null-safe.
        assertThat(reqs.measurePresent(pft, null)).isFalse();
        assertThat(reqs.measurePresent(pft, java.util.Arrays.asList((String) null))).isFalse();
    }

    @Test
    void audiogramKeywords_matchPuretoneAndSpeechDiscrimination() {
        RatingEvidenceRequirements.Requirement audio = reqs.forCode("6100").orElseThrow();
        assertThat(reqs.measurePresent(audio, List.of("Puretone thresholds 45 dB average"))).isTrue();
        assertThat(reqs.measurePresent(audio, List.of("Maryland CNC speech discrimination 84%"))).isTrue();
        assertThat(reqs.measurePresent(audio, List.of("Reports trouble hearing in crowds"))).isFalse();
    }
}
