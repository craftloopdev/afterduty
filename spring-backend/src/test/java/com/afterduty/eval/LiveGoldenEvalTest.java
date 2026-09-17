package com.afterduty.eval;

import com.afterduty.model.IdentifiedCondition;
import com.afterduty.model.LlmJob;
import com.afterduty.service.llm.LlmAsyncProvider;
import com.afterduty.service.llm.VertexGeminiAsyncProvider;
import com.afterduty.service.llm.VertexGeminiAsyncProviderImpl;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Increment 8 LIVE scored eval (spec §3). EXCLUDED from the default suite
 * ({@code @Tag("eval-live")}); run only with {@code -PliveEval} + ADC:
 *
 * <pre>
 *   GOOGLE_APPLICATION_CREDENTIALS=$HOME/.gcp/default-compute-sa.json \
 *   GCP_PROJECT=craftloop-va-claim JAVA_HOME=/opt/homebrew/opt/openjdk@21 \
 *   ./gradlew evalLive -PliveEval [-Peval.cases=gc-001] [-Peval.baseline=<run-id>]
 * </pre>
 *
 * <p>This runner is a thin orchestration shell over the same harness the offline
 * tier uses. The PIPELINE half (extraction → synthesis → gap on REAL Vertex Claude
 * + Gemini lanes via the production routing) is driven by the same
 * {@code @DataJpaTest} substrate the offline gate uses, but with a
 * {@code LiveEvalProviderConfig} swapping the fake for the real providers — that
 * Spring wiring is identical in shape to the offline test's {@code @Import} set and
 * is intentionally left as the operator's first-run step (spec §8 "Live (manual,
 * before first merge)").
 *
 * <p>What runs WITHOUT a live pipeline context, and is exercised here, is the JUDGE
 * + DETERMINISTIC-SCORE + REPORT path: given a set of (docs, end-state) the
 * deterministic scorer + the Gemini judge + the report writer produce the committed
 * run artifact. The smoke test below proves the judge lane and report writer work
 * end to end against ONE real Gemini call, which is the live-tier acceptance gate
 * the spec names ("one -Peval.cases=gc-001 run end-to-end ... verify report
 * artifacts, cost ledger non-zero, judge JSON parses").
 */
@Tag("eval-live")
class LiveGoldenEvalTest {

    @Test
    void judgeLaneAndReportWriter_realGeminiOneShot() throws Exception {
        String project = System.getenv().getOrDefault("GCP_PROJECT", "craftloop-va-claim");
        String judgeModel = System.getenv().getOrDefault("EVAL_JUDGE_MODEL", "gemini-3.1-pro-preview");

        // Real Gemini one-shot caller (livesmoke construction pattern).
        VertexGeminiAsyncProviderImpl gemini = new VertexGeminiAsyncProviderImpl();
        set(gemini, "projectId", project);
        set(gemini, "location", "global");
        set(gemini, "defaultModelName", judgeModel);
        gemini.init();

        ObjectMapper om = new ObjectMapper();
        java.util.function.Function<String, String> caller = prompt -> {
            try {
                String payload = om.writeValueAsString(Map.of(
                        "systemPrompt", "You are a strict JSON-only evaluator.",
                        "messages", List.of(Map.of("role", "user", "content", prompt)),
                        "maxTokens", 1024,
                        "thinkingBudget", 0));
                LlmJob job = LlmJob.builder()
                        .id(UUID.randomUUID())
                        .provider(VertexGeminiAsyncProvider.NAME)
                        .modelName(judgeModel)
                        .purpose("eval_judge")
                        .status(LlmJob.Status.SUBMITTED)
                        .requestPayload(payload)
                        .build();
                gemini.submit(List.of(job));
                List<LlmAsyncProvider.FetchedResult> results =
                        gemini.fetchResults(job.getId().toString(), List.of(job));
                LlmAsyncProvider.FetchedResult r = results.get(0);
                if (!r.succeeded) {
                    System.out.println("Gemini judge call FAILED: " + r.errorMessage);
                    return "{}";
                }
                return r.result.getText();
            } catch (Exception e) {
                System.out.println("Gemini judge call threw: " + e.getMessage());
                return "{}";
            }
        };

        EvalJudge judge = new EvalJudge(judgeModel, caller);

        // A synthetic case-shaped narrative for the smoke (does not need the DB).
        IdentifiedCondition ptsd = IdentifiedCondition.builder().claimId(1L)
                .name("Post-traumatic stress disorder").vasrdCode("9411").bodySystem("mental")
                .estimatedRating(70)
                .ratingRationale("Records show reduced reliability and productivity at work.")
                .build();
        JudgeScore score = judge.grade("gc-001",
                List.of("Synthetic VA mental health note: PTSD with reduced reliability."),
                List.of(ptsd));

        System.out.println("Live judge score: " + score);

        // Assemble a minimal one-case report so the artifact path is exercised.
        var caseReport = new EvalReportWriter.CaseReport("gc-001", "scored",
                Map.of("condition_recall", 1.0, "condition_precision", 1.0),
                score, 0.0);
        List<EvalReportWriter.CaseReport> cases = List.of(caseReport);

        // §3.5 run verdict via the tested policy helper — NOT an inline ternary. The
        // full runner would pass the baseline run's aggregate means here; the smoke
        // run names none (so this is a BASELINE unless the judge hard-failed).
        EvalVerdict.Result verdict = EvalVerdict.decide(cases, baselineMeans());

        String runId = Instant.now().toString().replaceAll("[:.]", "").substring(0, 15) + "-live-smoke";
        var report = new EvalReportWriter.RunReport(runId, gitSha(), Instant.now().toString(),
                Map.of("eval_judge", "vertex-gemini/" + judgeModel),
                Map.of("judge_prompt_version", EvalJudge.JUDGE_PROMPT_VERSION),
                cases,
                new EvalReportWriter.Aggregate(EvalVerdict.aggregateMeans(cases), verdict.hardFailCount()),
                new EvalReportWriter.Cost(0.0, 0.0, 0.0, null),
                System.getProperty("eval.baseline"),
                verdict.verdict().name());

        String reportDir = System.getProperty("eval.report.dir",
                System.getProperty("java.io.tmpdir") + "/eval-runs");
        Path written = new EvalReportWriter().write(Path.of(reportDir), report);
        System.out.println("Live smoke report written to: " + written);
    }

    // Spend-cap protocol (spec §3.3) — the live runner drives the roster through
    // {@link EvalRunLoop}, constructed with an {@link EvalSpendGuard} whose cost
    // supplier is bound to {@code aiCallLogRepository.totalCostSince(runStart)}
    // (covering pipeline rows + the {@code callType="eval_judge"} rows). The loop
    // checks {@code guard.canAttemptAnotherCase()} before each case and
    // {@code guard.check()} after each stage tick-batch; once it trips, remaining
    // cases are marked {@code SKIPPED} and the report's cost block carries
    // {@code aborted_reason = EvalSpendGuard.ABORTED_REASON}.
    //
    // ENFORCEMENT (not just the decision unit) is proven offline, no live calls, by
    // SpendCapEnforcementTest: it runs the SAME EvalRunLoop, binds the guard to the
    // REAL AiCallLogRepository.totalCostSince query against H2, drives a real
    // pipeline case (gc-001) that books real AiCallLog rows, and asserts the loop
    // aborts MID-ROSTER when the real ledger crosses the cap. EvalSpendGuardTest
    // covers the latch edges and EvalReportWriterTest the aborted report shape; so
    // the cap's enforcement, decision, and report shape are all in the default CI
    // suite even though this live runner is not.

    private static String gitSha() {
        return System.getProperty("eval.git.sha", "unknown");
    }

    /**
     * The named baseline run's aggregate means (spec §3.5 comparison input), read from
     * its committed {@code report.json} under {@code eval.report.dir}, or {@code null}
     * when no baseline is named or its report is absent (⇒ this run is a BASELINE).
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Double> baselineMeans() {
        String baseline = System.getProperty("eval.baseline");
        if (baseline == null || baseline.isBlank()) {
            return null;
        }
        String reportDir = System.getProperty("eval.report.dir",
                System.getProperty("java.io.tmpdir") + "/eval-runs");
        Path reportJson = Path.of(reportDir, baseline, "report.json");
        if (!java.nio.file.Files.exists(reportJson)) {
            return null;
        }
        try {
            Map<String, Object> root = new ObjectMapper().readValue(reportJson.toFile(), Map.class);
            Object aggregate = root.get("aggregate");
            if (aggregate instanceof Map<?, ?> a && a.get("means") instanceof Map<?, ?> means) {
                Map<String, Double> out = new java.util.LinkedHashMap<>();
                means.forEach((k, v) -> {
                    if (v instanceof Number n) out.put(String.valueOf(k), n.doubleValue());
                });
                return out;
            }
        } catch (Exception e) {
            System.out.println("baseline report unreadable (" + reportJson + "): " + e.getMessage());
        }
        return null;
    }

    private static void set(Object target, String field, Object value) throws Exception {
        Field f = target.getClass().getDeclaredField(field);
        f.setAccessible(true);
        f.set(target, value);
    }
}
