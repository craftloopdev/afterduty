package com.afterduty.eval;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link EvalVerdict} — the run-level pass/fail policy (spec §3.5) the
 * review found unimplemented. Pins the dominance of hard fails, the BASELINE path, and
 * the &gt;0.05 / &gt;0.10 REGRESSION thresholds (inclusive boundaries do NOT regress).
 */
@Tag("regression")
class EvalVerdictTest {

    private EvalReportWriter.CaseReport scored(String id, Map<String, Double> det,
                                               Map<String, Double> judgeScores, boolean hardFail) {
        JudgeScore judge = new JudgeScore(id, "gemini", "1",
                new JudgeScore.HardFails(hardFail, false),
                List.of(), judgeScores, "r");
        return new EvalReportWriter.CaseReport(id, "scored", det, judge, 0.10);
    }

    @Test
    void anyHardFail_dominates_evenWithPerfectMetrics() {
        var cases = List.of(
                scored("gc-001", Map.of("condition_recall", 1.0), Map.of("grounding", 1.0), false),
                scored("gc-002", Map.of("condition_recall", 1.0), Map.of("grounding", 1.0), true));
        // even with a baseline that the metrics beat, a single hard fail ⇒ FAIL.
        var result = EvalVerdict.decide(cases, Map.of("condition_recall", 0.5));
        assertEquals(EvalVerdict.Verdict.FAIL, result.verdict());
        assertEquals(1, result.hardFailCount());
        assertTrue(result.reasons().stream().anyMatch(r -> r.contains("hard fail on gc-002")));
    }

    @Test
    void noBaseline_isBaselineVerdict() {
        var cases = List.of(scored("gc-001", Map.of("condition_recall", 0.9), Map.of(), false));
        assertEquals(EvalVerdict.Verdict.BASELINE, EvalVerdict.decide(cases, null).verdict());
        assertEquals(EvalVerdict.Verdict.BASELINE, EvalVerdict.decide(cases, Map.of()).verdict());
    }

    @Test
    void deterministicDrop_pastThreshold_isRegression() {
        // recall 1.0 → 0.90 = 0.10 drop > 0.05 ⇒ REGRESSION.
        var cases = List.of(scored("gc-001", Map.of("condition_recall", 0.90), Map.of(), false));
        var result = EvalVerdict.decide(cases, Map.of("condition_recall", 1.00));
        assertEquals(EvalVerdict.Verdict.REGRESSION, result.verdict());
        assertTrue(result.reasons().stream().anyMatch(r -> r.contains("condition_recall dropped")));
    }

    @Test
    void deterministicDrop_atThreshold_isNotRegression() {
        // exactly 0.05 drop is NOT > 0.05 ⇒ PASS (strict inequality, spec §3.5).
        var cases = List.of(scored("gc-001", Map.of("condition_recall", 0.95), Map.of(), false));
        assertEquals(EvalVerdict.Verdict.PASS,
                EvalVerdict.decide(cases, Map.of("condition_recall", 1.00)).verdict());
    }

    @Test
    void gapCompleteness_usesLooserTenPercentThreshold() {
        // gap_completeness 0.92 → 0.85 = 0.07 drop. > 0.05 (deterministic) but
        // < 0.10 (gap) ⇒ NOT a regression for gap_completeness specifically.
        var cases = List.of(scored("gc-001", Map.of(), Map.of("gap_completeness", 0.85), false));
        assertEquals(EvalVerdict.Verdict.PASS,
                EvalVerdict.decide(cases, Map.of("gap_completeness", 0.92)).verdict());

        // 0.92 → 0.80 = 0.12 drop > 0.10 ⇒ REGRESSION.
        var worse = List.of(scored("gc-001", Map.of(), Map.of("gap_completeness", 0.80), false));
        assertEquals(EvalVerdict.Verdict.REGRESSION,
                EvalVerdict.decide(worse, Map.of("gap_completeness", 0.92)).verdict());
    }

    @Test
    void improvement_overBaseline_isPass() {
        var cases = List.of(scored("gc-001", Map.of("condition_recall", 1.0), Map.of(), false));
        assertEquals(EvalVerdict.Verdict.PASS,
                EvalVerdict.decide(cases, Map.of("condition_recall", 0.80)).verdict());
    }

    @Test
    void aggregateMeans_averageOnlyOverCasesCarryingTheMetric() {
        var cases = List.of(
                scored("gc-001", Map.of("condition_recall", 1.0), Map.of(), false),
                // a skipped case that carries no metrics must not drag the mean to 0.
                new EvalReportWriter.CaseReport("gc-002", "skipped", null, null, 0.0));
        Map<String, Double> means = EvalVerdict.aggregateMeans(cases);
        assertEquals(1.0, means.get("condition_recall"), 1e-9,
                "the skipped case is excluded from the mean, not counted as 0");
    }
}
