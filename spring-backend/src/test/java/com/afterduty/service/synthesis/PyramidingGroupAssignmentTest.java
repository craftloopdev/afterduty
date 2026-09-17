package com.afterduty.service.synthesis;

import com.afterduty.model.IdentifiedCondition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pyramiding grouped view — deterministic §4.14 / §4.130 group assignment.
 *
 * <p>Exercises the REAL {@link PyramidingGroups} code map + the orchestrator's
 * {@code assignPyramidingGroups} pass together (no AI, no repositories), mirroring
 * {@link PyramidingRulesTest} / {@link PresumptiveReconciliationTest} style.
 */
class PyramidingGroupAssignmentTest {

    private EnhancedSynthesisOrchestrator orchestrator;
    private long nextId;

    @BeforeEach
    void setUp() {
        orchestrator = new EnhancedSynthesisOrchestrator();
        orchestrator.setPyramidingGroups(new PyramidingGroups());
        nextId = 1;
    }

    private IdentifiedCondition cond(String name, String code, int rating) {
        IdentifiedCondition c = new IdentifiedCondition();
        c.setId(nextId++);
        c.setName(name);
        c.setVasrdCode(code);
        c.setEstimatedRating(rating);
        return c;
    }

    @Test
    void groupsMentalHealthConditions_primaryIsHighestRated_othersAbsorbed() {
        // The owner's headline case: PTSD 70 + MDD 50 + anxiety 30.
        IdentifiedCondition ptsd = cond("PTSD", "9411", 70);
        IdentifiedCondition mdd = cond("Major Depressive Disorder", "9434", 50);
        IdentifiedCondition anxiety = cond("Generalized Anxiety Disorder", "9400", 30);
        List<IdentifiedCondition> conditions = new ArrayList<>(List.of(ptsd, mdd, anxiety));

        int grouped = orchestrator.assignPyramidingGroups(conditions);

        assertThat(grouped).isEqualTo(3);

        // All three land in the canonical §4.130 group.
        for (IdentifiedCondition c : conditions) {
            assertThat(c.getPyramidGroup()).isEqualTo(PyramidingGroups.MENTAL_HEALTH_GROUP);
            // Every member is stamped with the group's effective rating (PTSD's 70).
            assertThat(c.getPyramidGroupRating()).isEqualTo(70);
        }

        // PTSD is the effective/primary member — marked, no absorb reason.
        assertThat(ptsd.getPyramidPrimary()).isTrue();
        assertThat(ptsd.getPyramidReason()).isNull();

        // The other two are absorbed — NOT primary, carry the "strongest counts" reason
        // naming PTSD + 70% (the exact rows PyramidingRules excludes from combined math).
        assertThat(mdd.getPyramidPrimary()).isFalse();
        assertThat(mdd.getPyramidReason())
                .contains("Rated together")
                .contains("PTSD")
                .contains("70%")
                .contains("don't add");
        assertThat(anxiety.getPyramidPrimary()).isFalse();
        assertThat(anxiety.getPyramidReason()).contains("PTSD").contains("70%");

        // The rating NUMBER is never changed.
        assertThat(ptsd.getEstimatedRating()).isEqualTo(70);
        assertThat(mdd.getEstimatedRating()).isEqualTo(50);
        assertThat(anxiety.getEstimatedRating()).isEqualTo(30);
    }

    @Test
    void nonMentalCondition_isUngrouped() {
        IdentifiedCondition ptsd = cond("PTSD", "9411", 70);
        IdentifiedCondition tinnitus = cond("Tinnitus", "6260", 10);
        List<IdentifiedCondition> conditions = new ArrayList<>(List.of(ptsd, tinnitus));

        orchestrator.assignPyramidingGroups(conditions);

        // Tinnitus is not a mental-health code — no group, no marker, no reason.
        assertThat(tinnitus.getPyramidGroup()).isNull();
        assertThat(tinnitus.getPyramidPrimary()).isNull();
        assertThat(tinnitus.getPyramidReason()).isNull();
        assertThat(tinnitus.getPyramidGroupRating()).isNull();

        // A single mental-health condition (group of one) gets no reason — nothing to
        // rate "together" with — but it IS the (trivial) primary of its own group.
        assertThat(ptsd.getPyramidGroup()).isEqualTo(PyramidingGroups.MENTAL_HEALTH_GROUP);
        assertThat(ptsd.getPyramidPrimary()).isTrue();
        assertThat(ptsd.getPyramidReason()).isNull();
        assertThat(ptsd.getPyramidGroupRating()).isEqualTo(70);
    }

    @Test
    void isConsistentWithPyramidingRules_absorbedMembersDropFromCombinedMath() {
        // The group pass sets pyramidReason on the non-effective members; PyramidingRules
        // then EXCLUDES exactly those from the combined ratings. This proves the two
        // layers agree and nothing is double-counted.
        IdentifiedCondition ptsd = cond("PTSD", "9411", 70);
        IdentifiedCondition mdd = cond("MDD", "9434", 50);
        IdentifiedCondition anxiety = cond("Anxiety", "9400", 30);
        List<IdentifiedCondition> conditions = new ArrayList<>(List.of(ptsd, mdd, anxiety));

        orchestrator.assignPyramidingGroups(conditions);
        PyramidingRules.Plan plan = new PyramidingRules().plan(conditions);

        // Only PTSD (70) survives into the combined math; MDD + anxiety are absorbed.
        assertThat(plan.ratings()).containsExactly(70);
    }

    @Test
    void tbi_notMerged_getsOverlapNoteOnly_whenMentalGroupPresent() {
        IdentifiedCondition ptsd = cond("PTSD", "9411", 70);
        IdentifiedCondition tbi = cond("Traumatic Brain Injury", "8045", 40);
        tbi.setRatingRationale("Residual headaches and memory difficulty documented.");
        List<IdentifiedCondition> conditions = new ArrayList<>(List.of(ptsd, tbi));

        orchestrator.assignPyramidingGroups(conditions);

        // TBI is NEVER placed in the mental-health group and NEVER absorbed — its
        // separate physical/cognitive residuals stay independently rated.
        assertThat(tbi.getPyramidGroup()).isNull();
        assertThat(tbi.getPyramidPrimary()).isNull();
        assertThat(tbi.getPyramidReason()).isNull();
        assertThat(tbi.getEstimatedRating()).isEqualTo(40);

        // It gets an advisory overlap note appended (append-only, prior text preserved).
        assertThat(tbi.getRatingRationale())
                .contains("Residual headaches")
                .contains("TBI")
                .contains("overlap")
                .contains("PTSD")
                .contains("§4.130");

        // PyramidingRules keeps TBI in the combined math (no pyramidReason → not excluded).
        PyramidingRules.Plan plan = new PyramidingRules().plan(conditions);
        assertThat(plan.ratings()).containsExactlyInAnyOrder(70, 40);
    }

    @Test
    void tbi_alone_getsNoOverlapNote_whenNoMentalGroup() {
        IdentifiedCondition tbi = cond("Traumatic Brain Injury", "8045", 40);
        List<IdentifiedCondition> conditions = new ArrayList<>(List.of(tbi));

        int grouped = orchestrator.assignPyramidingGroups(conditions);

        // No mental-health group exists, so nothing overlaps — TBI is untouched.
        assertThat(grouped).isEqualTo(0);
        assertThat(tbi.getRatingRationale()).isNull();
        assertThat(tbi.getPyramidGroup()).isNull();
    }

    @Test
    void flagOffSemantics_emptyOrNullList_isNoOp() {
        // The flag lives in SynthesisStateMachine; the pass itself must be a clean no-op
        // on empty/null input (the caller skips it entirely when the flag is off).
        assertThat(orchestrator.assignPyramidingGroups(List.of())).isEqualTo(0);
        assertThat(orchestrator.assignPyramidingGroups(null)).isEqualTo(0);
    }

    @Test
    void tieOnRating_brokenDeterministicallyByLowestId() {
        // Two mental conditions tied at 50 — the lower-id one is the stable primary.
        IdentifiedCondition first = cond("Depression", "9434", 50); // id 1
        IdentifiedCondition second = cond("Anxiety", "9400", 50);    // id 2
        List<IdentifiedCondition> conditions = new ArrayList<>(List.of(first, second));

        orchestrator.assignPyramidingGroups(conditions);

        assertThat(first.getPyramidPrimary()).isTrue();
        assertThat(second.getPyramidPrimary()).isFalse();
        assertThat(second.getPyramidReason()).contains("Depression");
    }
}
