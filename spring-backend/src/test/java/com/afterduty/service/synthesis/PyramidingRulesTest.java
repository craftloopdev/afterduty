package com.afterduty.service.synthesis;

import com.afterduty.model.IdentifiedCondition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PyramidingRulesTest {

    PyramidingRules rules;
    long nextId;

    @BeforeEach
    void setUp() {
        rules = new PyramidingRules();
        nextId = 1;
    }

    /** Test fixture: a condition with the minimal fields the rules care about. */
    IdentifiedCondition cond(String name, String code, int rating) {
        IdentifiedCondition c = new IdentifiedCondition();
        c.setId(nextId++);
        c.setName(name);
        c.setVasrdCode(code);
        c.setEstimatedRating(rating);
        return c;
    }

    @Test
    void plan_emptyInput_returnsEmptyPlan() {
        PyramidingRules.Plan plan = rules.plan(List.of());
        assertThat(plan.ratings()).isEmpty();
        assertThat(plan.bilateralPairs()).isEmpty();
        assertThat(plan.notes()).isEmpty();
    }

    @Test
    void plan_dropsZeroAndNullRatings() {
        IdentifiedCondition zero = cond("ZeroRated", "5260", 0);
        IdentifiedCondition nullRating = cond("NullRated", "5261", 0);
        nullRating.setEstimatedRating(null);
        IdentifiedCondition real = cond("Real", "6502", 30);

        PyramidingRules.Plan plan = rules.plan(List.of(zero, nullRating, real));

        assertThat(plan.ratings()).containsExactly(30);
    }

    @Test
    void plan_excludesAbsorbedConditions_andLogsReason() {
        IdentifiedCondition ptsd = cond("PTSD", "9411", 70);
        IdentifiedCondition anxiety = cond("Anxiety", "9400", 30);
        anxiety.setPyramidGroup("PTSD");
        anxiety.setPyramidReason("§4.130 — single mental rating; PTSD is the higher evaluation");

        PyramidingRules.Plan plan = rules.plan(List.of(ptsd, anxiety));

        assertThat(plan.ratings()).containsExactly(70);
        assertThat(plan.bilateralPairs()).isEmpty();
        assertThat(plan.notes())
                .anyMatch(n -> n.contains("Anxiety") && n.contains("absorbed"));
    }

    @Test
    void plan_capsTinnitusAtTen() {
        IdentifiedCondition tinnitus = cond("Tinnitus", "6260", 30);
        IdentifiedCondition back = cond("Back strain", "5237", 20);

        PyramidingRules.Plan plan = rules.plan(List.of(tinnitus, back));

        assertThat(plan.ratings()).containsExactlyInAnyOrder(10, 20);
        assertThat(plan.notes())
                .anyMatch(n -> n.toLowerCase().contains("tinnitus") && n.contains("10"));
    }

    @Test
    void plan_doesNotMutateInput_whenCappingTinnitus() {
        IdentifiedCondition tinnitus = cond("Tinnitus", "6260", 30);
        rules.plan(List.of(tinnitus));
        assertThat(tinnitus.getEstimatedRating()).isEqualTo(30);
    }

    @Test
    void plan_detectsBilateralPair_byMatchingVasrdCode() {
        IdentifiedCondition leftKnee = cond("Left knee strain", "5260", 20);
        IdentifiedCondition rightKnee = cond("Right knee strain", "5260", 30);

        PyramidingRules.Plan plan = rules.plan(List.of(leftKnee, rightKnee));

        // Pair members are pulled out of ratings into bilateralPairs so the
        // VaMathService applies the §4.26 +10% factor.
        assertThat(plan.ratings()).isEmpty();
        assertThat(plan.bilateralPairs()).hasSize(1);
        assertThat(plan.bilateralPairs().get(0)).containsExactlyInAnyOrder(20, 30);
        assertThat(plan.notes())
                .anyMatch(n -> n.contains("Bilateral") && n.contains("5260"));
    }

    @Test
    void plan_threeConditionsSharingCode_areNotPaired() {
        // Three knees can't happen anatomically, but if Claude returns three
        // 5260s the pair-detector should refuse to guess.
        IdentifiedCondition a = cond("Knee A", "5260", 10);
        IdentifiedCondition b = cond("Knee B", "5260", 20);
        IdentifiedCondition c = cond("Knee C", "5260", 30);

        PyramidingRules.Plan plan = rules.plan(List.of(a, b, c));

        assertThat(plan.bilateralPairs()).isEmpty();
        assertThat(plan.ratings()).containsExactlyInAnyOrder(10, 20, 30);
    }

    @Test
    void plan_blankVasrdCode_doesNotPair() {
        IdentifiedCondition a = cond("Vague A", "", 20);
        IdentifiedCondition b = cond("Vague B", "", 30);

        PyramidingRules.Plan plan = rules.plan(List.of(a, b));

        assertThat(plan.bilateralPairs()).isEmpty();
        assertThat(plan.ratings()).containsExactlyInAnyOrder(20, 30);
    }

    @Test
    void plan_combinedScenario_mentalCollapseTinnitusCapBilateralPair() {
        // Realistic fixture: PTSD (70) absorbs Anxiety (30 absorbed),
        // tinnitus over-rated at 30, plus a bilateral knee pair, plus
        // an unrelated back strain.
        IdentifiedCondition ptsd = cond("PTSD", "9411", 70);
        IdentifiedCondition anxiety = cond("Anxiety", "9400", 30);
        anxiety.setPyramidReason("§4.130 — single mental rating");
        IdentifiedCondition tinnitus = cond("Tinnitus", "6260", 30);
        IdentifiedCondition leftKnee = cond("Left knee", "5260", 20);
        IdentifiedCondition rightKnee = cond("Right knee", "5260", 20);
        IdentifiedCondition back = cond("Back", "5237", 40);

        PyramidingRules.Plan plan = rules.plan(
                List.of(ptsd, anxiety, tinnitus, leftKnee, rightKnee, back));

        // Expected ratings list: PTSD 70, tinnitus 10 (capped), back 40.
        // Anxiety dropped (absorbed). Knees moved into bilateralPairs.
        assertThat(plan.ratings()).containsExactlyInAnyOrder(70, 10, 40);
        assertThat(plan.bilateralPairs()).hasSize(1);
        assertThat(plan.bilateralPairs().get(0)).containsExactlyInAnyOrder(20, 20);
        assertThat(plan.notes()).hasSize(3);  // absorbed + capped + bilateral
    }
}
