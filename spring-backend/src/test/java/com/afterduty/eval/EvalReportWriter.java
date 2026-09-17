package com.afterduty.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Writes a live-eval run directory (spec §4): {@code report.md} (human-readable),
 * {@code report.json} (machine-readable), {@code scores.csv} (spreadsheet-friendly,
 * one row per case × metric). Test scope; the gradle {@code evalLive} task supplies
 * {@code eval.report.dir} and {@code eval.git.sha}.
 *
 * <p>Pure I/O over the {@link RunReport} value type — unit-testable with fake
 * scores (no live models), per spec §8.5.
 */
public final class EvalReportWriter {

    private final ObjectMapper mapper = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    /** Top-level run report (spec §4 report.json fields). */
    public record RunReport(
            String runId,
            String gitSha,
            String startedAt,
            Map<String, Object> routing,
            Map<String, String> promptVersions,
            List<CaseReport> cases,
            Aggregate aggregate,
            Cost cost,
            String baseline,
            String verdict
    ) {}

    public record CaseReport(
            String caseId,
            String status,                       // scored | skipped | timeout | judge_error
            Map<String, Double> deterministic,   // recall, precision, rating_band, gap_structural...
            JudgeScore judge,
            double costUsd
    ) {}

    public record Aggregate(Map<String, Double> means, int hardFailCount) {}

    public record Cost(double pipelineUsd, double judgeUsd, double totalUsd, String abortedReason) {}

    /** Write the three artifacts into {@code reportDir/runId/}. Returns that dir. */
    public Path write(Path reportDir, RunReport report) {
        Path runDir = reportDir.resolve(report.runId());
        try {
            Files.createDirectories(runDir);
            Files.writeString(runDir.resolve("report.json"),
                    mapper.writeValueAsString(report), StandardCharsets.UTF_8);
            Files.writeString(runDir.resolve("report.md"), markdown(report), StandardCharsets.UTF_8);
            Files.writeString(runDir.resolve("scores.csv"), csv(report), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("failed to write eval report to " + runDir, e);
        }
        return runDir;
    }

    // ------------------------------------------------------------------ markdown

    private String markdown(RunReport r) {
        StringBuilder sb = new StringBuilder();
        sb.append("# Eval run ").append(r.runId()).append("\n\n");
        sb.append("- **Verdict:** ").append(r.verdict()).append("\n");
        sb.append("- **git sha:** ").append(r.gitSha()).append("\n");
        sb.append("- **started:** ").append(r.startedAt()).append("\n");
        if (r.baseline() != null) sb.append("- **baseline:** ").append(r.baseline()).append("\n");
        sb.append("- **cost:** $").append(fmt(r.cost().totalUsd()))
                .append(" (pipeline $").append(fmt(r.cost().pipelineUsd()))
                .append(", judge $").append(fmt(r.cost().judgeUsd())).append(")");
        if (r.cost().abortedReason() != null) sb.append(" — ABORTED: ").append(r.cost().abortedReason());
        sb.append("\n\n");

        long unreviewed = r.cases() == null ? 0 : r.cases().size();
        sb.append("> NOTE: all ").append(unreviewed).append(" case expectations are currently ")
                .append("`unreviewed` — `gap_completeness` and `rating_band_accuracy` are PROVISIONAL ")
                .append("until VSO calibration (spec §6).\n\n");

        sb.append("## Aggregate\n\n");
        if (r.aggregate() != null) {
            r.aggregate().means().forEach((k, v) -> sb.append("- ").append(k).append(": ").append(fmt(v)).append("\n"));
            sb.append("- hard_fail_count: ").append(r.aggregate().hardFailCount()).append("\n");
        }
        sb.append("\n## Per-case\n\n");
        sb.append("| case | status | recall | precision | rating_band | gap | grounding | plain_lang | hard_fail | $ |\n");
        sb.append("|---|---|---|---|---|---|---|---|---|---|\n");
        if (r.cases() != null) {
            for (CaseReport c : r.cases()) {
                Map<String, Double> d = c.deterministic() == null ? Map.of() : c.deterministic();
                Map<String, Double> js = c.judge() == null || c.judge().scores() == null ? Map.of() : c.judge().scores();
                boolean hf = c.judge() != null && c.judge().anyHardFail();
                sb.append("| ").append(c.caseId())
                        .append(" | ").append(c.status())
                        .append(" | ").append(fmt(d.get("condition_recall")))
                        .append(" | ").append(fmt(d.get("condition_precision")))
                        .append(" | ").append(fmt(d.get("rating_band_accuracy")))
                        .append(" | ").append(fmt(js.get("gap_completeness")))
                        .append(" | ").append(fmt(js.get("grounding")))
                        .append(" | ").append(fmt(js.get("plain_language")))
                        .append(" | ").append(hf ? "**YES**" : "no")
                        .append(" | ").append(fmt(c.costUsd()))
                        .append(" |\n");
            }
        }
        // Hard-fail quotes
        sb.append("\n## Hard-fail violations\n\n");
        boolean anyViolation = false;
        if (r.cases() != null) {
            for (CaseReport c : r.cases()) {
                if (c.judge() != null && c.judge().violations() != null) {
                    for (JudgeScore.Violation v : c.judge().violations()) {
                        anyViolation = true;
                        sb.append("- [").append(c.caseId()).append("/").append(v.where()).append("] ")
                                .append(v.line()).append(": \"").append(v.quote()).append("\"\n");
                    }
                }
            }
        }
        if (!anyViolation) sb.append("_none_\n");
        return sb.toString();
    }

    // ------------------------------------------------------------------ csv

    private String csv(RunReport r) {
        StringBuilder sb = new StringBuilder("case_id,metric,value\n");
        if (r.cases() != null) {
            for (CaseReport c : r.cases()) {
                if (c.deterministic() != null) {
                    c.deterministic().forEach((k, v) ->
                            sb.append(c.caseId()).append(",").append(k).append(",").append(fmt(v)).append("\n"));
                }
                if (c.judge() != null && c.judge().scores() != null) {
                    c.judge().scores().forEach((k, v) ->
                            sb.append(c.caseId()).append(",judge_").append(k).append(",").append(fmt(v)).append("\n"));
                }
                sb.append(c.caseId()).append(",cost_usd,").append(fmt(c.costUsd())).append("\n");
            }
        }
        return sb.toString();
    }

    private static String fmt(Double v) {
        return v == null ? "" : String.format("%.3f", v);
    }
    private static String fmt(double v) {
        return String.format("%.3f", v);
    }
}
