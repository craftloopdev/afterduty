package com.afterduty.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class VaMathServiceTest {

    private VaMathService service;

    @BeforeEach
    void setUp() {
        service = new VaMathService();
    }

    // --- Single condition ratings ---

    @Nested
    class SingleCondition {
        @Test
        void singleTenPercent() {
            var result = service.calculateCombinedRating(List.of(10));
            assertEquals(10, result.get("combined_rating"));
        }

        @Test
        void singleFiftyPercent() {
            var result = service.calculateCombinedRating(List.of(50));
            assertEquals(50, result.get("combined_rating"));
        }

        @Test
        void singleHundredPercent() {
            var result = service.calculateCombinedRating(List.of(100));
            assertEquals(100, result.get("combined_rating"));
        }

        @Test
        void singleRatingExactValueMatches() {
            var result = service.calculateCombinedRating(List.of(70));
            assertEquals(70.0, result.get("exact_value"));
            assertEquals(70, result.get("combined_rating"));
        }
    }

    // --- Two conditions combined ---

    @Nested
    class TwoConditions {
        @Test
        void seventyAndThirtyCombinesTo80() {
            var result = service.calculateCombinedRating(List.of(70, 30));
            assertEquals(79.0, result.get("exact_value"));
            assertEquals(80, result.get("combined_rating"));
        }

        @Test
        void fiftyAndThirty() {
            var result = service.calculateCombinedRating(List.of(50, 30));
            assertEquals(65.0, result.get("exact_value"));
            assertEquals(70, result.get("combined_rating"));
        }

        @Test
        void tenAndTen() {
            var result = service.calculateCombinedRating(List.of(10, 10));
            assertEquals(19.0, result.get("exact_value"));
            assertEquals(20, result.get("combined_rating"));
        }

        @Test
        void orderDoesNotMatter() {
            var r1 = service.calculateCombinedRating(List.of(30, 70));
            var r2 = service.calculateCombinedRating(List.of(70, 30));
            assertEquals(r1.get("combined_rating"), r2.get("combined_rating"));
            assertEquals(r1.get("exact_value"), r2.get("exact_value"));
        }
    }

    // --- Three or more conditions ---

    @Nested
    class ThreeOrMoreConditions {
        @Test
        void threeConditions() {
            var result = service.calculateCombinedRating(List.of(50, 30, 20));
            assertEquals(72.0, result.get("exact_value"));
            assertEquals(70, result.get("combined_rating"));
        }

        @Test
        void fourConditions() {
            var result = service.calculateCombinedRating(List.of(40, 30, 20, 10));
            assertEquals(69.76, result.get("exact_value"));
            assertEquals(70, result.get("combined_rating"));
        }

        @Test
        void manySmallConditions() {
            var result = service.calculateCombinedRating(List.of(10, 10, 10, 10, 10));
            double exactValue = (Double) result.get("exact_value");
            assertEquals(40.95, exactValue, 0.01);
            assertEquals(40, result.get("combined_rating"));
        }

        @Test
        void threeConditionsUnsortedInput() {
            var r1 = service.calculateCombinedRating(List.of(20, 50, 30));
            var r2 = service.calculateCombinedRating(List.of(50, 30, 20));
            assertEquals(r1.get("combined_rating"), r2.get("combined_rating"));
        }
    }

    // --- Bilateral factor ---

    @Nested
    class BilateralFactor {
        @Test
        void bilateralPairAddsTenPercentBonus() {
            var result = service.calculateCombinedRating(List.of(30, 20), List.of(new int[]{30, 20}));
            double bf = (Double) result.get("bilateral_factor");
            assertEquals(4.4, bf, 0.1);
            assertTrue(bf > 0);
        }

        @Test
        void bilateralFactorIncludedInExactValue() {
            var withBf = service.calculateCombinedRating(List.of(30, 20), List.of(new int[]{30, 20}));
            var withoutBf = service.calculateCombinedRating(List.of(30, 20));
            assertTrue((Double) withBf.get("exact_value") > (Double) withoutBf.get("exact_value"));
        }

        @Test
        void bilateralFactorZeroWhenNoPairs() {
            var result = service.calculateCombinedRating(List.of(50, 30));
            assertEquals(0.0, result.get("bilateral_factor"));
        }

        @Test
        void bilateralStepsDocumented() {
            var result = service.calculateCombinedRating(List.of(20, 20), List.of(new int[]{20, 20}));
            @SuppressWarnings("unchecked")
            List<String> steps = (List<String>) result.get("steps");
            long bilateralSteps = steps.stream().filter(s -> s.contains("Bilateral")).count();
            assertTrue(bilateralSteps > 0);
        }
    }

    // --- Bilateral-only claims (§4.26 worked-example math) ---
    //
    // §4.26: combine the paired ratings via the §4.25 table, ADD 10% of that
    // value (not combine), round to a whole number, then treat the result as a
    // SINGLE disability for all further combinations / final conversion. The
    // bilateral-factored value must re-enter the combination list — adding only
    // the +10% factor (the prior bug) collapses a paired-only claim toward 0%.
    //
    // Authoritative anchor (38 CFR §4.26 regulatory example): 60/20/10/10 with
    // the two 10s bilateral → severity order 60, 21, 20 → 60&21 combine to 68,
    // 68&20 combine to 74 → final 70%. So 10+10 bilateral = 21 as one rating.

    @Nested
    class BilateralOnlyClaim {

        @Test
        void bilateralOnlyTenAndTenCombinesTo20() {
            // 10 + 10 → §4.25 table 19; +10% of 19 = 1.9 → 20.9 → 21 as a whole-
            // number bilateral value treated as ONE disability (per the §4.26
            // regulatory example, which lists 10/10 bilateral as "21"). With no
            // other ratings the combination of {21} is exactly 21.0, and final
            // §4.25 conversion to nearest 10 → 20%.
            var result = service.calculateCombinedRating(List.of(), List.of(new int[]{10, 10}));
            assertEquals(20, result.get("combined_rating"));
            assertEquals(21.0, (Double) result.get("exact_value"), 0.001);
            assertEquals(1.9, (Double) result.get("bilateral_factor"), 0.001);
        }

        @Test
        void bilateralOnlyTwentyAndTwentyCombinesTo40() {
            // 20 + 20 → §4.25 table 36; +10% of 36 = 3.6 → 39.6 → 40 as the whole-
            // number bilateral value treated as one disability. Combination of
            // {40} is 40.0; final §4.25 conversion to nearest 10 → 40%.
            var result = service.calculateCombinedRating(List.of(), List.of(new int[]{20, 20}));
            assertEquals(40, result.get("combined_rating"));
            assertEquals(40.0, (Double) result.get("exact_value"), 0.001);
            assertEquals(3.6, (Double) result.get("bilateral_factor"), 0.001);
        }

        @Test
        void bilateralPairPlusThirdRating() {
            // 50 PTSD + bilateral 10/10 knees. Bilateral value = 21 (one rating).
            // Severity order 50, 21 → §4.25: 1-(1-.50)(1-.21) = 0.605 → 60.5 exact;
            // final conversion to nearest 10 → 60%. (The §4.25 TABLE intermediate
            // 50⊕21 = 61, which likewise converts to 60% — same final degree.)
            var result = service.calculateCombinedRating(
                    List.of(50), List.of(new int[]{10, 10}));
            assertEquals(60, result.get("combined_rating"));
            assertEquals(60.5, (Double) result.get("exact_value"), 0.001);
        }

        @Test
        void bilateralValueTreatedAsSingleDisabilityNotJustBonus() {
            // Regression guard for the original bug: a paired-only claim must not
            // collapse toward 0. The +10% factor ALONE (1.9) rounds to 0; the
            // bilateral-factored value (21) must drive the combination.
            var result = service.calculateCombinedRating(List.of(), List.of(new int[]{10, 10}));
            assertTrue((Integer) result.get("combined_rating") >= 20,
                    "paired-only claim must not collapse toward 0%");
            assertTrue((Double) result.get("exact_value") > 10.0,
                    "exact value must reflect the bilateral-factored disability, not just the +10% bonus");
        }
    }

    // --- More than two members sharing a code: PyramidingRules does NOT form a
    // bilateral pair for 3+, so no bilateralPairs reach the math service. This
    // asserts the math service behaves as a plain combination when given 3 raw
    // ratings and no pairs (the unchanged, no-bilateral path).
    @Nested
    class ThreePlusBilateralMembers {
        @Test
        void threeSharedRatingsNoPairCombineNormally() {
            // No bilateralPairs supplied → straight §4.25 combination of 10,10,10.
            var result = service.calculateCombinedRating(List.of(10, 10, 10), List.of());
            assertEquals(0.0, result.get("bilateral_factor"));
            assertEquals(27.1, (Double) result.get("exact_value"), 0.01);
            assertEquals(30, result.get("combined_rating"));
        }
    }

    // --- No-bilateral path unchanged ---
    @Nested
    class NoBilateralUnchanged {
        @Test
        void emptyBilateralListMatchesNullBilateral() {
            var withEmpty = service.calculateCombinedRating(List.of(50, 30), List.of());
            var withNull = service.calculateCombinedRating(List.of(50, 30), null);
            assertEquals(withNull.get("combined_rating"), withEmpty.get("combined_rating"));
            assertEquals(withNull.get("exact_value"), withEmpty.get("exact_value"));
            assertEquals(0.0, withEmpty.get("bilateral_factor"));
        }

        @Test
        void plainCombinationUnaffected() {
            var result = service.calculateCombinedRating(List.of(70, 30), List.of());
            assertEquals(79.0, result.get("exact_value"));
            assertEquals(80, result.get("combined_rating"));
        }
    }

    // --- Zero / empty with bilateral argument unchanged ---
    @Nested
    class ZeroEmptyWithBilateral {
        @Test
        void emptyRatingsAndNoPairsStaysZero() {
            var result = service.calculateCombinedRating(List.of(), List.of());
            assertEquals(0, result.get("combined_rating"));
            assertEquals(0.0, result.get("monthly_estimate"));
        }

        @Test
        void nullRatingsWithEmptyBilateralStaysZero() {
            var result = service.calculateCombinedRating(null, List.of());
            assertEquals(0, result.get("combined_rating"));
        }
    }

    // --- Rounding rules ---

    @Nested
    class RoundingRules {
        @Test void fortyFiveRoundsTo50() { assertEquals(50, service.vaRound(45.0)); }
        @Test void fortyFourRoundsTo40() { assertEquals(40, service.vaRound(44.0)); }
        @Test void exactMultipleOf10Unchanged() { assertEquals(70, service.vaRound(70.0)); }
        @Test void fiftyFiveRoundsTo60() { assertEquals(60, service.vaRound(55.0)); }
        @Test void fiftyFourRoundsTo50() { assertEquals(50, service.vaRound(54.0)); }
        @Test void fiveRoundsTo10() { assertEquals(10, service.vaRound(5.0)); }
        @Test void fourRoundsTo0() { assertEquals(0, service.vaRound(4.0)); }
        @Test void ninetyFiveRoundsTo100() { assertEquals(100, service.vaRound(95.0)); }

        @Test
        void roundingInFullCalculation() {
            var result = service.calculateCombinedRating(List.of(70, 30));
            assertEquals(80, result.get("rounded_value"));
        }
    }

    // --- Zero rating ---

    @Nested
    class ZeroRating {
        @Test
        void emptyRatingsReturnsZero() {
            var result = service.calculateCombinedRating(List.of());
            assertEquals(0, result.get("combined_rating"));
            assertEquals(0.0, result.get("monthly_estimate"));
        }

        @Test
        void singleZeroRating() {
            var result = service.calculateCombinedRating(List.of(0));
            assertEquals(0, result.get("combined_rating"));
            assertEquals(0.0, result.get("monthly_estimate"));
        }

        @Test
        void zeroWithOtherRatings() {
            var withoutZero = service.calculateCombinedRating(List.of(50));
            var withZero = service.calculateCombinedRating(List.of(50, 0));
            assertEquals(withoutZero.get("combined_rating"), withZero.get("combined_rating"));
        }
    }

    // --- Boundary 50% ---

    @Nested
    class Boundary50 {
        @Test
        void exact50Stays50() {
            var result = service.calculateCombinedRating(List.of(50));
            assertEquals(50.0, result.get("exact_value"));
            assertEquals(50, result.get("combined_rating"));
        }

        @Test void justAbove45RoundsTo50() { assertEquals(50, service.vaRound(45.0)); }
        @Test void justBelow45RoundsTo40() { assertEquals(40, service.vaRound(44.9)); }

        @Test
        void combinationLandingNear50() {
            var result = service.calculateCombinedRating(List.of(30, 30));
            assertEquals(51.0, result.get("exact_value"));
            assertEquals(50, result.get("combined_rating"));
        }
    }

    // --- Compensation lookup ---

    @Nested
    class CompensationLookup {
        /**
         * P1-3 — veteran-alone rates effective December 1, 2025 (payment year
         * 2026), verified against va.gov/disability/compensation-rates/veteran-rates/
         * on 2026-07-02. When RATES_YEAR bumps, refresh every row here from the
         * published table — never compute them.
         */
        @ParameterizedTest
        @CsvSource({
                "0, 0.00",
                "10, 180.42",
                "20, 356.66",
                "30, 552.47",
                "40, 795.84",
                "50, 1132.90",
                "60, 1435.02",
                "70, 1808.45",
                "80, 2102.15",
                "90, 2362.30",
                "100, 3938.58"
        })
        void rate2026(int rating, double expected) {
            assertEquals(expected, service.estimateCompensation(rating));
        }

        @Test
        void invalidRatingReturnsZero() {
            assertEquals(0.0, service.estimateCompensation(15));
        }

        @Test
        void compensationInFullCalculation() {
            var result = service.calculateCombinedRating(List.of(70, 30));
            assertEquals(80, result.get("combined_rating"));
            assertEquals(2102.15, result.get("monthly_estimate"));
        }
    }

    // --- Rate-table freshness (P1-3 COLA rot guard) ---

    @Nested
    class RateTableFreshness {
        /**
         * VA rates change every December 1 (COLA). RATES_YEAR N rates are correct
         * for payment year N (i.e. through Nov 30 of year N) — so a table is
         * defensibly current while {@code RATES_YEAR >= currentYear - 1} and
         * plainly rotten after that (that's how BASE_RATES sat on 2024 values in
         * mid-2026 while the UI claimed "current VA rates", ~5% low). This guard
         * fails the build once the constant is ~18 months stale.
         */
        @Test
        void rateTableIsNotStale() {
            int currentYear = java.time.Year.now().getValue();
            assertTrue(VaMathService.RATES_YEAR >= currentYear - 1,
                    "VaMathService.RATES_YEAR is " + VaMathService.RATES_YEAR + " but the current year is "
                            + currentYear + ": the VA compensation tables have COLA-rotted. Update BASE_RATES, "
                            + "SPOUSE_ADD, CHILD_ADD, PARENT_ADD, SMC_K_AMOUNT and RATES_YEAR together from "
                            + "https://www.va.gov/disability/compensation-rates/veteran-rates/ and refresh the "
                            + "spot-check values in this test.");
        }

        /** ratesYear surfaced on the wire must always be the year the table was verified for. */
        @Test
        void ratesYearMatchesVerifiedTable() {
            // 100% veteran-alone is the canary cell: if someone bumps RATES_YEAR
            // without refreshing the table (or vice versa), this pairing breaks.
            assertEquals(2026, VaMathService.RATES_YEAR);
            assertEquals(3938.58, service.estimateCompensation(100));
        }
    }

    // --- combineTwo helper ---

    @Nested
    class CombineTwo {
        @Test void combine50And30() { assertEquals(65, service.combineTwo(50, 30)); }
        @Test void combine70And30() { assertEquals(79, service.combineTwo(70, 30)); }
        @Test void combine0And50() { assertEquals(50, service.combineTwo(0, 50)); }
        @Test void combine100AndAnything() { assertEquals(100, service.combineTwo(100, 50)); }
    }

    // --- Labeled sequential combine (combineLabeled) ---
    //
    // The labeled walk MUST be a byte-for-byte re-expression of the authoritative
    // §4.25 math: same descending sort, same diminishing-returns recurrence, same
    // vaRound. These tests pin that single-source property so the controller's
    // combineSteps can never quietly drift from calculateCombinedRating.

    @Nested
    class LabeledCombine {

        @Test
        void sortsContributorsDescendingByRating() {
            var steps = service.combineLabeled(List.of(
                    new VaMathService.LabeledRating("GERD", 30),
                    new VaMathService.LabeledRating("Mental health", 70)));
            // First non-rounding step is the highest-rated contributor.
            assertEquals("Mental health", steps.get(0).label());
            assertEquals(70, steps.get(0).rating());
            assertEquals("GERD", steps.get(1).label());
            assertEquals(30, steps.get(1).rating());
        }

        @Test
        void perStepPointsAndRemainingAreCorrect() {
            var steps = service.combineLabeled(List.of(
                    new VaMathService.LabeledRating("Mental health", 70),
                    new VaMathService.LabeledRating("GERD", 30)));
            // 70 of 100 → 70 pts, remaining 30, combined 70.
            assertEquals(70.0, steps.get(0).pointsAdded(), 0.001);
            assertEquals(70.0, steps.get(0).combinedAfter(), 0.001);
            assertEquals(30.0, steps.get(0).remainingAfter(), 0.001);
            // 30% of remaining 30 → 9 pts, remaining 21, combined 79.
            assertEquals(9.0, steps.get(1).pointsAdded(), 0.001);
            assertEquals(79.0, steps.get(1).combinedAfter(), 0.001);
            assertEquals(21.0, steps.get(1).remainingAfter(), 0.001);
        }

        @Test
        void finalRoundingStepMatchesAuthoritativeCombinedRating() {
            var labeled = service.combineLabeled(List.of(
                    new VaMathService.LabeledRating("A", 70),
                    new VaMathService.LabeledRating("B", 30)));
            var authoritative = service.calculateCombinedRating(List.of(70, 30));

            var rounding = labeled.get(labeled.size() - 1);
            assertTrue(rounding.rounding());
            assertNull(rounding.rating());
            assertEquals("Rounded to nearest 10", rounding.label());
            // Last contributor's cumulative (79.0) rounds to the same 80 the
            // authoritative overload reports, and the rounding step carries 80.
            assertEquals(80.0, rounding.combinedAfter(), 0.001);
            assertEquals(authoritative.get("combined_rating"), (int) rounding.combinedAfter());
            var lastContrib = labeled.get(labeled.size() - 2);
            assertEquals(service.vaRound(lastContrib.combinedAfter()),
                    (int) rounding.combinedAfter());
        }

        @Test
        void carriesAbsorbedMembersOnTheContributor() {
            var steps = service.combineLabeled(List.of(
                    new VaMathService.LabeledRating(
                            "Mental health", 70, List.of("MDD", "Anxiety"))));
            assertEquals(List.of("MDD", "Anxiety"), steps.get(0).absorbedMembers());
        }

        @Test
        void emptyContributorsYieldsNoSteps() {
            assertTrue(service.combineLabeled(List.of()).isEmpty());
            assertTrue(service.combineLabeled(null).isEmpty());
        }

        /** The labeled walk equals the authoritative overload across a range of multisets. */
        @Test
        void labeledWalkAgreesWithAuthoritativeAcrossMultisets() {
            List<List<Integer>> cases = List.of(
                    List.of(70, 30), List.of(60, 40, 40), List.of(50, 30, 20),
                    List.of(40, 30, 20, 10), List.of(10, 10, 10, 10, 10), List.of(100));
            for (List<Integer> ratings : cases) {
                var authoritative = service.calculateCombinedRating(ratings);
                var labeled = service.combineLabeled(ratings.stream()
                        .map(r -> new VaMathService.LabeledRating("c" + r, r))
                        .toList());
                int labeledRounded = (int) labeled.get(labeled.size() - 1).combinedAfter();
                assertEquals(authoritative.get("combined_rating"), labeledRounded,
                        "labeled walk must match authoritative for " + ratings);
            }
        }
    }

    // --- Steps output ---

    @Nested
    class StepsOutput {
        @Test
        void stepsArePopulated() {
            var result = service.calculateCombinedRating(List.of(50, 30));
            @SuppressWarnings("unchecked")
            List<String> steps = (List<String>) result.get("steps");
            assertFalse(steps.isEmpty());
        }

        @Test
        void emptyInputHasNoSteps() {
            var result = service.calculateCombinedRating(List.of());
            @SuppressWarnings("unchecked")
            List<String> steps = (List<String>) result.get("steps");
            assertTrue(steps.isEmpty());
        }
    }
}
