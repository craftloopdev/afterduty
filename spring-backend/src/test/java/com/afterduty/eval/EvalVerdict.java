package com.afterduty.eval;

import java.util.List;
import java.util.Map;

/**
 * The run-level pass/fail POLICY (spec §3.5) — the piece the adversarial review found
 * unimplemented: there was no tested function aggregating {@link JudgeScore#anyHardFail()}
 * across cases and deriving the run verdict. The verdict lived only as an inline
 * single-case ternary in {@link LiveGoldenEvalTest} and as a caller-supplied string the
 * report writer round-tripped to disk; so §3.5's hard-fail-dominates rule and the
 * &gt;0.05 / &gt;0.10 REGRESSION thresholds were never exercised. A hard fail on case N
 * could be silently averaged away by whatever future code assembled the report.
 *
 * <p>This helper implements §3.5 verbatim and is consumed by both the live runner
 * ({@link LiveGoldenEvalTest}) and the report writer, replacing the caller-computed
 * string:
 *
 * <ol>
 *   <li><b>Any hard-fail line on ANY case ⇒ {@link Verdict#FAIL}</b>, regardless of
 *       averages. This dominates everything else.</li>
 *   <li>No baseline named ⇒ {@link Verdict#BASELINE} (the first committed run becomes
 *       the baseline).</li>
 *   <li>Otherwise comparative vs the named baseline: any aggregate deterministic metric
 *       dropping &gt; {@value #DETERMINISTIC_REGRESSION_THRESHOLD}, OR the
 *       {@code gap_completeness} mean dropping &gt; {@value #GAP_COMPLETENESS_REGRESSION_THRESHOLD}
 *       ⇒ {@link Verdict#REGRESSION}.</li>
 *   <li>Else {@link Verdict#PASS}.</li>
 * </ol>
 */
public final class EvalVerdict {

    /** §3.5: any aggregate deterministic metric dropping more than this ⇒ REGRESSION. */
    public static final double DETERMINISTIC_REGRESSION_THRESHOLD = 0.05;
    /** §3.5: the gap_completeness mean dropping more than this ⇒ REGRESSION. */
    public static final double GAP_COMPLETENESS_REGRESSION_THRESHOLD = 0.10;

    /**
     * IEEE-754 tolerance so an EXACTLY-at-threshold drop (e.g. 1.00 − 0.95, which
     * computes to 0.05000000000000004) is treated as "at the threshold" (not a
     * regression) rather than tripping on a floating-point artifact. Spec §3.5's
     * thresholds are strict "&gt;", so the boundary must NOT regress.
     */
    private static final double EPSILON = 1e-9;

    /** The metric name (a judge score) governed by the looser gap threshold. */
    public static final String GAP_COMPLETENESS = "gap_completeness";

    public enum Verdict { BASELINE, PASS, REGRESSION, FAIL }

    /** The decision plus the human-readable reasons that produced it. */
    public record Result(Verdict verdict, int hardFailCount, List<String> reasons) {}

    private EvalVerdict() {}

    /**
     * Derive the run verdict from the scored cases and an optional baseline.
     *
     * @param cases         the run's per-case reports (deterministic metrics + judge block)
     * @param baselineMeans the baseline run's aggregate means by metric, or {@code null}
     *                      when no baseline was named (⇒ this run becomes the baseline)
     */
    public static Result decide(List<EvalReportWriter.CaseReport> cases, Map<String, Double> baselineMeans) {
        List<String> reasons = new java.util.ArrayList<>();

        // (1) Hard-fail dominates: any hard fail on any case ⇒ FAIL.
        int hardFailCount = 0;
        for (EvalReportWriter.CaseReport c : cases) {
            if (c.judge() != null && c.judge().anyHardFail()) {
                hardFailCount++;
                reasons.add("hard fail on " + c.caseId()
                        + (c.judge().hardFails() == null ? "" :
                        " (legal_advice=" + bool(c.judge().hardFails().legalAdvice())
                                + ", fabricated_citation=" + bool(c.judge().hardFails().fabricatedCitation()) + ")"));
            }
        }
        if (hardFailCount > 0) {
            return new Result(Verdict.FAIL, hardFailCount, List.copyOf(reasons));
        }

        // (2) No baseline ⇒ this run becomes the baseline.
        if (baselineMeans == null || baselineMeans.isEmpty()) {
            reasons.add("no baseline named — this run is the baseline");
            return new Result(Verdict.BASELINE, 0, List.copyOf(reasons));
        }

        // (3) Comparative: any metric drop past its threshold ⇒ REGRESSION.
        Map<String, Double> means = aggregateMeans(cases);
        boolean regressed = false;
        for (Map.Entry<String, Double> e : baselineMeans.entrySet()) {
            String metric = e.getKey();
            double baseline = e.getValue() == null ? 0.0 : e.getValue();
            Double currentBoxed = means.get(metric);
            if (currentBoxed == null) continue;     // metric not present this run — skip
            double current = currentBoxed;
            double drop = baseline - current;
            double threshold = GAP_COMPLETENESS.equals(metric)
                    ? GAP_COMPLETENESS_REGRESSION_THRESHOLD
                    : DETERMINISTIC_REGRESSION_THRESHOLD;
            if (drop > threshold + EPSILON) {
                regressed = true;
                reasons.add(String.format("%s dropped %.3f (%.3f → %.3f), > %.2f threshold",
                        metric, drop, baseline, current, threshold));
            }
        }
        if (regressed) {
            return new Result(Verdict.REGRESSION, 0, List.copyOf(reasons));
        }

        reasons.add("no hard fails and no metric regression vs baseline");
        return new Result(Verdict.PASS, 0, List.copyOf(reasons));
    }

    /**
     * Aggregate per-case metric means across deterministic metrics AND judge scores,
     * over the cases that carry each metric (so a {@code skipped}/{@code timeout} case
     * missing a metric doesn't drag the mean to zero).
     */
    public static Map<String, Double> aggregateMeans(List<EvalReportWriter.CaseReport> cases) {
        Map<String, double[]> acc = new java.util.LinkedHashMap<>();   // metric → [sum, count]
        for (EvalReportWriter.CaseReport c : cases) {
            if (c.deterministic() != null) {
                c.deterministic().forEach((k, v) -> add(acc, k, v));
            }
            if (c.judge() != null && c.judge().scores() != null) {
                c.judge().scores().forEach((k, v) -> add(acc, k, v));
            }
        }
        Map<String, Double> means = new java.util.LinkedHashMap<>();
        acc.forEach((k, sc) -> means.put(k, sc[1] == 0 ? 0.0 : sc[0] / sc[1]));
        return means;
    }

    private static void add(Map<String, double[]> acc, String metric, Double value) {
        if (value == null) return;
        double[] sc = acc.computeIfAbsent(metric, x -> new double[2]);
        sc[0] += value;
        sc[1] += 1;
    }

    private static boolean bool(Boolean b) {
        return Boolean.TRUE.equals(b);
    }
}
