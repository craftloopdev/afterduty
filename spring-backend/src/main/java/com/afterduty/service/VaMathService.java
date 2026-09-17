package com.afterduty.service;

import org.springframework.stereotype.Service;

import java.util.*;

/**
 * VA combined rating calculator — bilateral factor, diminishing returns, rounding.
 * Includes the December-1-2025-effective (payment year {@link #RATES_YEAR}) VA
 * compensation rates with dependent-based additions and SMC-K.
 */
@Service
public class VaMathService {

    /**
     * The VA compensation payment year the tables below correspond to.
     *
     * <p>P1-3 (COLA rot): VA rates change every December 1 (COLA). This constant
     * is surfaced to veterans via {@code ratesYear} on
     * {@code GET /api/claim/combined-rating} ("at 2026 VA rates"), and
     * {@code VaMathServiceTest}'s COLA-rot guard fails the build once this
     * constant is ~18 months stale. When bumping it, update {@link #BASE_RATES},
     * the dependent-addition tables, and {@link #SMC_K_AMOUNT} together from
     * https://www.va.gov/disability/compensation-rates/veteran-rates/ —
     * NEVER bump the year without re-verifying every dollar figure.
     */
    public static final int RATES_YEAR = 2026;

    /**
     * VA compensation rates effective December 1, 2025 (payment year 2026) —
     * veteran alone, no dependents. Verified against
     * va.gov/disability/compensation-rates/veteran-rates/ on 2026-07-02.
     */
    private static final Map<Integer, Double> BASE_RATES = Map.ofEntries(
            Map.entry(0, 0.00),
            Map.entry(10, 180.42),
            Map.entry(20, 356.66),
            Map.entry(30, 552.47),
            Map.entry(40, 795.84),
            Map.entry(50, 1132.90),
            Map.entry(60, 1435.02),
            Map.entry(70, 1808.45),
            Map.entry(80, 2102.15),
            Map.entry(90, 2362.30),
            Map.entry(100, 3938.58)
    );

    /**
     * Additional monthly amount for a spouse, indexed by combined rating (30%+).
     * December-1-2025-effective values (verified: with-spouse minus veteran-alone
     * rates on va.gov, e.g. 100%: $4,158.17 − $3,938.58 = $219.59).
     */
    private static final Map<Integer, Double> SPOUSE_ADD = Map.ofEntries(
            Map.entry(30, 65.00), Map.entry(40, 87.00), Map.entry(50, 109.00),
            Map.entry(60, 131.00), Map.entry(70, 153.00), Map.entry(80, 175.00),
            Map.entry(90, 197.00), Map.entry(100, 219.59)
    );

    /** Additional monthly amount per child under 18, indexed by combined rating (30%+). Dec-1-2025 values. */
    private static final Map<Integer, Double> CHILD_ADD = Map.ofEntries(
            Map.entry(30, 32.00), Map.entry(40, 43.00), Map.entry(50, 54.00),
            Map.entry(60, 65.00), Map.entry(70, 76.00), Map.entry(80, 87.00),
            Map.entry(90, 98.00), Map.entry(100, 109.11)
    );

    /** Additional monthly amount per dependent parent, indexed by combined rating (30%+). Dec-1-2025 values. */
    private static final Map<Integer, Double> PARENT_ADD = Map.ofEntries(
            Map.entry(30, 52.00), Map.entry(40, 70.00), Map.entry(50, 88.00),
            Map.entry(60, 105.00), Map.entry(70, 123.00), Map.entry(80, 140.00),
            Map.entry(90, 158.00), Map.entry(100, 176.24)
    );

    /** SMC-K flat monthly addition. Dec-1-2025 value ($136.06 × 1.028 COLA). */
    public static final double SMC_K_AMOUNT = 139.87;

    /**
     * Calculate VA combined disability rating.
     */
    public Map<String, Object> calculateCombinedRating(List<Integer> ratings, List<int[]> bilateralPairs) {
        boolean noRatings = (ratings == null || ratings.isEmpty());
        boolean noPairs = (bilateralPairs == null || bilateralPairs.isEmpty());
        if (noRatings && noPairs) {
            return Map.of(
                    "combined_rating", 0,
                    "exact_value", 0.0,
                    "rounded_value", 0,
                    "bilateral_factor", 0.0,
                    "monthly_estimate", 0.0,
                    "steps", List.of()
            );
        }

        List<Integer> sortedRatings =
                new ArrayList<>(ratings == null ? List.of() : ratings);
        List<String> steps = new ArrayList<>();

        // §4.26 bilateral factor: combine the paired ratings via the §4.25 table,
        // ADD 10% of that value (not combine), round to a whole number, and treat
        // the result as ONE disability for all further combinations / final
        // conversion. The bilateral-factored value re-enters the combination list
        // as a single rating — it is not merely a +10% bonus tacked on at the end.
        double bilateralFactor = 0.0;
        if (bilateralPairs != null) {
            for (int[] pair : bilateralPairs) {
                int combined = combineTwo(pair[0], pair[1]);
                double bf = combined * 0.10;
                bilateralFactor += bf;
                int bilateralValue = (int) Math.round(combined + bf);
                sortedRatings.add(bilateralValue);
                steps.add(String.format(
                        "Bilateral pair %d%% + %d%%: combined = %d%%, +10%% factor = %.1f%%, "
                                + "bilateral value = %d%% (treated as one disability)",
                        pair[0], pair[1], combined, bf, bilateralValue));
            }
        }

        sortedRatings.sort(Comparator.reverseOrder());

        double remaining = 100.0;
        for (int rating : sortedRatings) {
            double value = remaining * (rating / 100.0);
            remaining -= value;
            steps.add(String.format("Apply %d%%: %.2f points, remaining capacity: %.2f%%",
                    rating, value, remaining));
        }

        double exactValue = 100.0 - remaining;
        if (bilateralFactor > 0) {
            steps.add(String.format("Combined with bilateral factor included: %.2f%%", exactValue));
        }

        int roundedValue = vaRound(exactValue);
        steps.add(String.format("Rounded to nearest 10: %d%%", roundedValue));
        exactValue = Math.round(exactValue * 100.0) / 100.0;

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("combined_rating", roundedValue);
        result.put("exact_value", exactValue);
        result.put("rounded_value", roundedValue);
        result.put("bilateral_factor", Math.round(bilateralFactor * 100.0) / 100.0);
        result.put("monthly_estimate", estimateCompensation(roundedValue, false, 0, 0, false));
        result.put("steps", steps);
        return result;
    }

    /** Convenience overload without bilateral pairs. */
    public Map<String, Object> calculateCombinedRating(List<Integer> ratings) {
        return calculateCombinedRating(ratings, null);
    }

    /**
     * One labeled contributor entering the §4.25 combine — a single condition, a
     * pyramiding group's surviving member, or a pre-combined bilateral pair. The
     * {@code rating} is the whole-number value that enters the diminishing-returns
     * walk (already tinnitus-capped / bilateral-factored by the caller); {@code
     * absorbedMembers} are the pyramided-in condition names that are rated together
     * and DON'T add (surfaced under the step for the veteran).
     */
    public record LabeledRating(String label, int rating, List<String> absorbedMembers) {
        public LabeledRating(String label, int rating) {
            this(label, rating, List.of());
        }
    }

    /** One step of the labeled sequential combine (see {@link #combineLabeled}). */
    public record LabeledStep(
            String label,
            Integer rating,          // null on the final rounding step
            double pointsAdded,      // 2dp — points this contributor added
            double combinedAfter,    // precise cumulative %, 2dp (or the final rounded int on the last step)
            double remainingAfter,   // 2dp remaining capacity after this contributor
            List<String> absorbedMembers,
            boolean rounding         // true only on the final "Rounded to nearest 10" step
    ) {}

    /**
     * Labeled sequential combine — the SAME §4.25 diminishing-returns math as
     * {@link #calculateCombinedRating(List, List)}, but each step carries a human
     * label (condition / group / bilateral pair) instead of an anonymous rating.
     *
     * <p>Single-source guarantee: contributors are sorted DESCENDING by rating and
     * walked with the identical {@code points = remaining * rating/100; remaining
     * -= points} recurrence, then the cumulative is VA-rounded with {@link #vaRound}
     * — byte-for-byte the same numbers the authoritative overload produces when fed
     * the same rating multiset. The caller passes bilateral pairs ALREADY combined
     * into one {@link LabeledRating} (matching how {@code calculateCombinedRating}
     * folds a pair into one entry before the descending sort), so the two walks see
     * the same value set and MUST land on the same combined rating. The caller is
     * expected to assert that equality and drop these steps on any divergence.
     *
     * @return ordered steps: one per contributor (descending) + a final rounding step.
     */
    public List<LabeledStep> combineLabeled(List<LabeledRating> contributors) {
        List<LabeledStep> steps = new ArrayList<>();
        if (contributors == null || contributors.isEmpty()) {
            return steps;
        }

        List<LabeledRating> sorted = new ArrayList<>(contributors);
        sorted.sort(Comparator.comparingInt(LabeledRating::rating).reversed());

        double remaining = 100.0;
        for (LabeledRating c : sorted) {
            double points = remaining * (c.rating() / 100.0);
            remaining -= points;
            double combined = 100.0 - remaining;
            steps.add(new LabeledStep(
                    c.label(),
                    c.rating(),
                    round2(points),
                    round2(combined),
                    round2(remaining),
                    c.absorbedMembers() == null ? List.of() : c.absorbedMembers(),
                    false));
        }

        int rounded = vaRound(100.0 - remaining);
        steps.add(new LabeledStep(
                "Rounded to nearest 10", null, 0.0, rounded, round2(remaining), List.of(), true));
        return steps;
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    /**
     * Calculate with full dependent info. Returns the same map plus dependent breakdown.
     */
    public Map<String, Object> calculateWithDependents(List<Integer> ratings,
                                                        boolean married, int children,
                                                        int dependentParents, boolean smcK) {
        Map<String, Object> base = calculateCombinedRating(ratings);
        int combinedRating = (int) base.get("combined_rating");
        List<String> steps = new ArrayList<>((List<String>) base.get("steps"));

        double baseAmount = BASE_RATES.getOrDefault(combinedRating, 0.0);
        double total = baseAmount;

        if (combinedRating >= 30) {
            if (married) {
                double spouseAdd = SPOUSE_ADD.getOrDefault(combinedRating, 0.0);
                total += spouseAdd;
                steps.add(String.format("Spouse addition at %d%%: +$%.2f", combinedRating, spouseAdd));
            }
            if (children > 0) {
                double childAdd = CHILD_ADD.getOrDefault(combinedRating, 0.0) * children;
                total += childAdd;
                steps.add(String.format("%d child(ren) addition at %d%%: +$%.2f", children, combinedRating, childAdd));
            }
            if (dependentParents > 0) {
                int parents = Math.min(dependentParents, 2);
                double parentAdd = PARENT_ADD.getOrDefault(combinedRating, 0.0) * parents;
                total += parentAdd;
                steps.add(String.format("%d dependent parent(s) addition at %d%%: +$%.2f", parents, combinedRating, parentAdd));
            }
        } else if (combinedRating > 0 && (married || children > 0 || dependentParents > 0)) {
            steps.add("Dependent additions only apply at 30% or higher combined rating");
        }

        if (smcK) {
            total += SMC_K_AMOUNT;
            steps.add(String.format("SMC-K: +$%.2f", SMC_K_AMOUNT));
        }

        total = Math.round(total * 100.0) / 100.0;
        steps.add(String.format("Total monthly estimate: $%.2f", total));

        Map<String, Object> result = new LinkedHashMap<>(base);
        result.put("monthly_estimate", total);
        result.put("steps", steps);
        return result;
    }

    public int combineTwo(int r1, int r2) {
        double combined = 1.0 - (1.0 - r1 / 100.0) * (1.0 - r2 / 100.0);
        return (int) Math.round(combined * 100);
    }

    public int vaRound(double value) {
        return (int) (Math.floor(value / 10.0 + 0.5)) * 10;
    }

    public double estimateCompensation(int rating, boolean married, int children, int dependentParents, boolean smcK) {
        double total = BASE_RATES.getOrDefault(rating, 0.0);
        if (rating >= 30) {
            if (married) total += SPOUSE_ADD.getOrDefault(rating, 0.0);
            total += CHILD_ADD.getOrDefault(rating, 0.0) * children;
            total += PARENT_ADD.getOrDefault(rating, 0.0) * Math.min(dependentParents, 2);
        }
        if (smcK) total += SMC_K_AMOUNT;
        return Math.round(total * 100.0) / 100.0;
    }

    /** Legacy overload for backward compatibility. */
    public double estimateCompensation(int rating) {
        return estimateCompensation(rating, false, 0, 0, false);
    }
}
