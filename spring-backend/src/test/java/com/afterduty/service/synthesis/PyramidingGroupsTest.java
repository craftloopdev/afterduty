package com.afterduty.service.synthesis;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pyramiding grouped view — the deterministic code → canonical-group map boundaries.
 * Pure/no-AI; guards the §4.130 mental-code range and the TBI (8045) carve-out.
 */
class PyramidingGroupsTest {

    private final PyramidingGroups groups = new PyramidingGroups();

    @Test
    void mentalDisorderCodes_mapToTheMentalHealthGroup() {
        // Representative codes across the §4.130 formula's diagnostic families.
        for (String code : new String[]{
                "9201", // schizophrenia (range start)
                "9210", // psychotic disorder
                "9300", // delirium
                "9326", // dementia due to other conditions
                "9400", // generalized anxiety
                "9411", // PTSD
                "9412", // panic disorder
                "9413", // anxiety disorder NOS
                "9421", // somatic symptom disorder
                "9432", // bipolar
                "9434", // major depressive disorder
                "9435", // mood disorder NOS
                "9440", // chronic adjustment disorder (range end)
                "9520", // anorexia
                "9521"  // bulimia
        }) {
            assertThat(groups.groupFor(code))
                    .as("code %s should be mental-health", code)
                    .contains(PyramidingGroups.MENTAL_HEALTH_GROUP);
        }
    }

    @Test
    void suffixedMentalCode_stillResolves() {
        assertThat(groups.groupFor("9411-9434")).contains(PyramidingGroups.MENTAL_HEALTH_GROUP);
    }

    @Test
    void nonMentalAndBoundaryCodes_areUngrouped() {
        // Just outside the range, and clearly-physical codes → no group.
        assertThat(groups.groupFor("9200")).isEmpty(); // just below range
        assertThat(groups.groupFor("9441")).isEmpty(); // just above the 9201-9440 block
        assertThat(groups.groupFor("6260")).isEmpty(); // tinnitus
        assertThat(groups.groupFor("5237")).isEmpty(); // back strain
        assertThat(groups.groupFor("6602")).isEmpty(); // asthma
    }

    @Test
    void blankNullAndNonNumericCodes_areUngrouped() {
        assertThat(groups.groupFor(null)).isEmpty();
        assertThat(groups.groupFor("")).isEmpty();
        assertThat(groups.groupFor("   ")).isEmpty();
        assertThat(groups.groupFor("abc")).isEmpty();
    }

    @Test
    void tbi_isNotGrouped_butIsFlaggedAsOverlap() {
        // 8045 must NOT be in the mental-health group (its physical/cognitive residuals
        // are separately rated) but IS recognized as an overlap case for the note.
        assertThat(groups.groupFor("8045")).isEmpty();
        assertThat(groups.isTbiOverlap("8045")).isTrue();
        assertThat(groups.isTbiOverlap("9411")).isFalse();
        assertThat(groups.isTbiOverlap(null)).isFalse();
    }

    @Test
    void overlapNote_namesThePrimary_andIsNonAlarming() {
        String note = groups.overlapNote("PTSD");
        assertThat(note).contains("TBI").contains("PTSD").contains("§4.130")
                .contains("won't").doesNotContain("absorbed");
        // Null primary falls back to a generic phrasing.
        assertThat(groups.overlapNote(null)).contains("your mental-health condition");
    }
}
