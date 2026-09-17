package com.afterduty.eval;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit test for {@link EvalReportWriter} (spec §8.5): fake scores produce a run dir
 * with all three artifacts; the aborted-run shape is preserved.
 */
@Tag("regression")
class EvalReportWriterTest {

    private final EvalReportWriter writer = new EvalReportWriter();

    private EvalReportWriter.CaseReport sampleCase(String id, boolean hardFail) {
        JudgeScore judge = new JudgeScore(id, "gemini-3.1-pro-preview", "1",
                new JudgeScore.HardFails(hardFail, false),
                hardFail ? List.of(new JudgeScore.Violation("no_legal_advice",
                        "You should definitely appeal.", "gap:9411")) : List.of(),
                Map.of("gap_completeness", 0.75, "grounding", 0.9, "plain_language", 0.85),
                "ok");
        return new EvalReportWriter.CaseReport(id, "scored",
                Map.of("condition_recall", 1.0, "condition_precision", 0.95,
                        "rating_band_accuracy", 1.0, "gap_structural", 1.0),
                judge, 0.42);
    }

    @Test
    void writesAllThreeArtifacts(@TempDir Path dir) throws Exception {
        var report = new EvalReportWriter.RunReport("20260620-170400-baseline", "abc1234",
                "2026-06-20T17:04:00Z", Map.of("synthesis_identify", "vertex-anthropic/claude"),
                Map.of("rating_prompt_version", "v1"),
                List.of(sampleCase("gc-001", false), sampleCase("gc-002", false)),
                new EvalReportWriter.Aggregate(Map.of("condition_recall", 1.0), 0),
                new EvalReportWriter.Cost(0.84, 0.10, 0.94, null),
                null, "BASELINE");

        Path runDir = writer.write(dir, report);
        assertTrue(Files.exists(runDir.resolve("report.json")));
        assertTrue(Files.exists(runDir.resolve("report.md")));
        assertTrue(Files.exists(runDir.resolve("scores.csv")));

        String md = Files.readString(runDir.resolve("report.md"));
        assertTrue(md.contains("BASELINE"), "verdict in markdown");
        assertTrue(md.contains("gc-001") && md.contains("gc-002"), "both cases in the table");

        String csv = Files.readString(runDir.resolve("scores.csv"));
        assertTrue(csv.startsWith("case_id,metric,value"), "csv header");
        assertTrue(csv.contains("gc-001,condition_recall,1.000"));
        assertTrue(csv.contains("gc-001,judge_grounding,0.900"));
    }

    @Test
    void abortedRunShapeIsPreserved(@TempDir Path dir) throws Exception {
        var report = new EvalReportWriter.RunReport("aborted-run", "def5678",
                "2026-06-20T18:00:00Z", Map.of(), Map.of(),
                List.of(new EvalReportWriter.CaseReport("gc-001", "skipped", Map.of(), null, 0.0)),
                new EvalReportWriter.Aggregate(Map.of(), 0),
                new EvalReportWriter.Cost(15.01, 0.0, 15.01, "spend_cap"),
                null, "FAIL");
        Path runDir = writer.write(dir, report);
        String json = Files.readString(runDir.resolve("report.json"));
        assertTrue(json.contains("spend_cap"), "aborted_reason recorded in json");
        String md = Files.readString(runDir.resolve("report.md"));
        assertTrue(md.contains("ABORTED: spend_cap"));
    }

    @Test
    void hardFailViolationsAreQuoted(@TempDir Path dir) throws Exception {
        var report = new EvalReportWriter.RunReport("hf-run", "ghi9012",
                "2026-06-20T19:00:00Z", Map.of(), Map.of(),
                List.of(sampleCase("gc-009", true)),
                new EvalReportWriter.Aggregate(Map.of(), 1),
                new EvalReportWriter.Cost(0.4, 0.05, 0.45, null), "base-1", "FAIL");
        Path runDir = writer.write(dir, report);
        String md = Files.readString(runDir.resolve("report.md"));
        assertTrue(md.contains("You should definitely appeal."), "violating sentence quoted");
        assertTrue(md.contains("no_legal_advice"));
    }
}
