package com.afterduty.eval;

import com.afterduty.config.PromptVersionRegistry;
import com.afterduty.model.Claim;
import com.afterduty.repository.AtomRepository;
import com.afterduty.repository.ClaimRepository;
import com.afterduty.repository.EvidenceItemRepository;
import com.afterduty.repository.IdentifiedConditionRepository;
import com.afterduty.service.extraction.ExtractionStateMachine;
import com.afterduty.service.gap.GapStateMachine;
import com.afterduty.service.llm.FakeLlmAsyncProvider;
import com.afterduty.service.llm.LlmJobPoller;
import com.afterduty.service.llm.LlmJobSubmitter;
import com.afterduty.service.synthesis.PyramidingRules;
import com.afterduty.service.synthesis.SynthesisStateMachine;
import com.afterduty.service.synthesis.VasrdDecisionEngine;
import com.afterduty.service.VaMathService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Increment 8 OFFLINE gate (spec §2). Parameterized over every {@code active}
 * golden case: drives each through the REAL pipeline on the fake substrate and
 * asserts the §2.4 invariants via {@link DeterministicScorer} at 100% AND that each
 * case's {@link EndStateDigest} equals the checked-in snapshot entry.
 *
 * <p>Runs in the default {@code ./gradlew test}/{@code check} suite (tags
 * {@code eval-offline} + {@code regression}). A pipeline behaviour change surfaces
 * as a digest diff that must be regenerated deliberately
 * ({@code ./gradlew evalSnapshot -PevalRunId=...}), never silently absorbed.
 *
 * <p>Snapshot write mode: with {@code -Deval.snapshot.write=true} the test records
 * the computed digests and the prompt versions into
 * {@code eval.snapshot.dir/offline-summary.json} instead of asserting equality
 * (the {@code evalSnapshot} gradle task drives this).
 */
@Tag("eval-offline")
@Tag("regression")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DataJpaTest
@Import({
        com.afterduty.service.llm.LlmProviderRouterTestConfig.class,
        com.afterduty.service.llm.LlmJobService.class,
        com.afterduty.service.llm.LlmJobSubmitter.class,
        com.afterduty.service.llm.LlmJobPoller.class,
        // extraction
        ExtractionStateMachine.class,
        com.afterduty.service.rag.RagExtractionTestConfig.class,
        com.afterduty.service.DocumentStorageService.class,
        com.afterduty.service.FakeGcsStorageTestConfig.class,
        com.afterduty.service.extraction.DiagnosisExtractorService.class,
        com.afterduty.service.extraction.MedicationExtractorService.class,
        com.afterduty.service.extraction.ServiceRecordExtractorService.class,
        com.afterduty.service.extraction.GeminiExtractionService.class,
        com.afterduty.service.extraction.EventSegmentationAgent.class,
        com.afterduty.service.extraction.EventExtractionAgent.class,
        com.afterduty.service.extraction.SinglePassExtractionService.class,
        com.afterduty.config.LlmRoutingProperties.class,
        // synthesis
        SynthesisStateMachine.class, com.afterduty.service.DomainCorrectionsService.class,
        com.afterduty.service.synthesis.ConditionIdentificationAgent.class,
        com.afterduty.service.synthesis.DuplicateConditionMerger.class,
        com.afterduty.service.synthesis.RatingAgent.class,
        com.afterduty.service.synthesis.SynthesisVerificationAgent.class,
        com.afterduty.service.synthesis.EnhancedSynthesisOrchestrator.class,
        com.afterduty.service.synthesis.ConditionGenerationService.class,
        com.afterduty.service.synthesis.VasrdDecisionEngine.class,
        com.afterduty.service.synthesis.PyramidingRules.class,
        com.afterduty.service.AnalysisScheduler.class,
        com.afterduty.service.VaMathService.class,
        // gap
        GapStateMachine.class,
        com.afterduty.service.gap.EvidenceGapAnalyzer.class,
        com.afterduty.service.gap.GapValidationAgent.class,
        com.afterduty.service.gap.WhatIfScenarioGenerator.class,
        com.afterduty.service.gap.UserGapStateService.class
})
@TestPropertySource(properties = {
        "va-claim.llm.submitter.batch-size=50",
        "va-claim.llm.submitter.poll-ms=9999999",
        "va-claim.llm.submitter.initial-delay-ms=9999999",
        "va-claim.llm.poller.poll-ms=9999999",
        "va-claim.llm.poller.initial-delay-ms=9999999",
        "va-claim.llm.orphan-deadline-min=30",
        // P1-9 — the what-if stage defaults OFF in prod (rendered nowhere); this
        // suite exercises it, so turn it on explicitly.
        "va-claim.gap.whatif-enabled=true",
        "va-claim.extraction.single-pass=true",
        "va-claim.analysis.incremental=true",
        "va-claim.rag.enabled=false"
})
class OfflineGoldenPipelineTest {

    @Autowired ExtractionStateMachine extraction;
    @Autowired SynthesisStateMachine synthesis;
    @Autowired GapStateMachine gap;
    @Autowired LlmJobSubmitter submitter;
    @Autowired LlmJobPoller poller;
    @Autowired ClaimRepository claimRepository;
    @Autowired AtomRepository atomRepository;
    @Autowired IdentifiedConditionRepository conditionRepository;
    @Autowired EvidenceItemRepository evidenceItemRepository;
    @Autowired FakeLlmAsyncProvider fake;
    @Autowired PyramidingRules pyramidingRules;
    @Autowired VaMathService vaMathService;
    @Autowired VasrdDecisionEngine vasrdDecisionEngine;

    private final GoldenCaseLoader loader = new GoldenCaseLoader();

    /** Per-run accumulator: case id → final digest, for the snapshot writer. */
    private static final Map<String, String> COMPUTED_DIGESTS = new LinkedHashMap<>();
    /** Per-run accumulator: one-line summary per case, for the offline-summary log. */
    private static final List<String> SUMMARY_LINES = new ArrayList<>();

    static List<GoldenCaseLoader.LoadedCase> activeCases() {
        return new GoldenCaseLoader().loadActive();
    }

    @BeforeEach
    void reset() {
        fake.reset();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("activeCases")
    void offlineGate(GoldenCaseLoader.LoadedCase loaded) {
        DeterministicScorer scorer = new DeterministicScorer(pyramidingRules, vaMathService, vasrdDecisionEngine);
        GoldenCaseSeeder seeder = new GoldenCaseSeeder(
                claimRepository, evidenceItemRepository, conditionRepository, fake, loader);
        PipelineDriver driver = new PipelineDriver(
                extraction, synthesis, gap, submitter, poller,
                claimRepository, atomRepository, conditionRepository, evidenceItemRepository, fake);

        Map<String, Integer> deterministicBands = deterministicMidBands(loaded.expectation());
        seeder.registerCanned(loaded, deterministicBands);

        Claim claim = seeder.createClaim(7L);

        Map<String, Long> priorJobCounts = new HashMap<>();
        PipelineEndState lastEnd = null;
        List<String> caseFailures = new ArrayList<>();

        for (GoldenCase.Phase phase : loaded.caseFile().phases()) {
            // capture cumulative job counts BEFORE this phase for delta scoring
            if (lastEnd != null) {
                priorJobCounts.put("synthesis_rate", lastEnd.distinctJobs("synthesis_rate"));
                priorJobCounts.put("gap_evidence", lastEnd.distinctJobs("gap_evidence"));
            }

            seeder.seedPhaseEvidence(claim, loaded, phase.name());
            PipelineEndState end = driver.runPhase(claim);
            lastEnd = end;

            GoldenExpectation.PhaseExpectation expect =
                    loaded.expectation().phases().get(phase.name());
            if (expect == null) {
                caseFailures.add("no expectation for phase '" + phase.name() + "'");
                continue;
            }
            DeterministicScorer.PhaseScore score =
                    scorer.score(loaded.id(), phase.name(), expect, end, priorJobCounts);
            if (!score.passed()) {
                score.failures().forEach(f -> caseFailures.add("[" + phase.name() + "] " + f));
            }
        }

        // Digest of the FINAL phase's end state.
        String digest = new EndStateDigest().digest(lastEnd);
        COMPUTED_DIGESTS.put(loaded.id(), digest);
        SUMMARY_LINES.add(String.format("%-10s %-28s %s  active=%d  %s",
                loaded.id(), loaded.caseFile().slug(),
                caseFailures.isEmpty() ? "PASS" : "FAIL",
                lastEnd.activeCount(), digest));

        boolean writeMode = Boolean.parseBoolean(System.getProperty("eval.snapshot.write", "false"));

        if (!caseFailures.isEmpty()) {
            fail("offline invariants failed for " + loaded.id() + ":\n  "
                    + String.join("\n  ", caseFailures));
        }

        if (!writeMode) {
            String expectedDigest = snapshotDigest(loaded.id());
            assertTrue(expectedDigest != null,
                    "no snapshot entry for " + loaded.id() + " — regenerate with "
                            + "`./gradlew evalSnapshot -PevalRunId=<live-run-id>`");
            assertTrue(digest.equals(expectedDigest),
                    "end-state digest changed for " + loaded.id() + ":\n  expected " + expectedDigest
                            + "\n  actual   " + digest
                            + "\nIf this is an intended pipeline change, regenerate the snapshot with "
                            + "`./gradlew evalSnapshot -PevalRunId=<live-run-id>`.");
        }
    }

    @AfterAll
    void emitSummaryAndMaybeWriteSnapshot() throws IOException {
        System.out.println("\n=== Offline golden pipeline summary ===");
        SUMMARY_LINES.forEach(System.out::println);

        boolean writeMode = Boolean.parseBoolean(System.getProperty("eval.snapshot.write", "false"));
        if (writeMode) {
            writeSnapshot();
        }
    }

    // ------------------------------------------------------------------ snapshot io

    private String snapshotDigest(String caseId) {
        // Private-tier cases keep their snapshot beside their corpus root (their
        // digests are derived from PHI content and never enter the repository).
        String privateRoot = GoldenCaseLoader.privateRoot();
        if (privateRoot != null) {
            String hit = digestFrom("file:" + privateRoot + "/snapshots/offline-summary.json", caseId);
            if (hit != null) return hit;
        }
        return digestFrom(GoldenCaseLoader.GOLDEN_ROOT + "/snapshots/offline-summary.json", caseId);
    }

    private String digestFrom(String path, String caseId) {
        @SuppressWarnings("unchecked")
        Map<String, Object> snapshot = loader.readJson(path, Map.class);
        if (snapshot == null) return null;
        Object cases = snapshot.get("cases");
        if (cases instanceof Map<?, ?> m) {
            Object v = m.get(caseId);
            return v == null ? null : v.toString();
        }
        return null;
    }

    /**
     * Technical half of the snapshot/live-run linkage (review fix minor #3). When the
     * named run is committed under {@code eval.runs.dir/<runId>/report.json}, its
     * recorded {@code prompt_versions} MUST equal the constants this regeneration is
     * about to stamp — proving the snapshot acknowledges a live run that scored the SAME
     * prompts. A conscious {@code -PevalWaiver} (eval.waiver) skips this check; a missing
     * run dir is already rejected upstream by the gradle task's doFirst.
     */
    @SuppressWarnings("unchecked")
    private void verifyRunLinkage(String runId) {
        if (System.getProperty("eval.waiver") != null) {
            System.out.println("evalSnapshot: live-run linkage WAIVED (eval.waiver set) — skipping "
                    + "prompt_versions cross-check for run id " + runId);
            return;
        }
        String runsDir = System.getProperty("eval.runs.dir");
        if (runsDir == null) return;   // not invoked via the gradle task — nothing to cross-check
        Path reportJson = Path.of(runsDir, runId, "report.json");
        if (!Files.exists(reportJson)) {
            // The gradle doFirst already enforced existence-or-waiver; if we reach here
            // without it (direct -Deval.snapshot.write run), don't hard-fail the unit JVM.
            System.out.println("evalSnapshot: no committed run at " + reportJson
                    + " — skipping prompt_versions cross-check (relying on the gradle task gate).");
            return;
        }
        try {
            Map<String, Object> report = new com.fasterxml.jackson.databind.ObjectMapper()
                    .readValue(reportJson.toFile(), Map.class);
            // The committed report.json's prompt-version block: the spec (§4) names it
            // snake_case ("prompt_versions"), but EvalReportWriter currently serializes
            // the RunReport record's camelCase component name ("promptVersions"). Accept
            // either so the linkage cross-check works regardless of which the run used.
            Object pv = report.get("prompt_versions");
            if (pv == null) {
                pv = report.get("promptVersions");
            }
            if (!(pv instanceof Map<?, ?> recorded)) {
                throw new IllegalStateException("committed run " + runId
                        + " has no prompt_versions/promptVersions block to cross-check against");
            }
            Map<String, String> expected = Map.of(
                    "extraction_prompt_version", PromptVersionRegistry.EXTRACTION_PROMPT_VERSION,
                    "extraction_schema_version", PromptVersionRegistry.EXTRACTION_SCHEMA_VERSION,
                    "rating_prompt_version", PromptVersionRegistry.RATING_PROMPT_VERSION,
                    "identify_prompt_version", PromptVersionRegistry.IDENTIFY_PROMPT_VERSION);
            List<String> mismatches = new ArrayList<>();
            expected.forEach((k, want) -> {
                Object got = recorded.get(k);
                if (got != null && !want.equals(String.valueOf(got))) {
                    mismatches.add(k + ": run scored '" + got + "' but constants are '" + want + "'");
                }
            });
            if (!mismatches.isEmpty()) {
                throw new IllegalStateException("evalSnapshot: the named live run " + runId
                        + " scored DIFFERENT prompt versions than the constants being stamped:\n  "
                        + String.join("\n  ", mismatches)
                        + "\nRun the live tier on the current prompts, or pass -PevalWaiver=\"<reason>\".");
            }
            System.out.println("evalSnapshot: prompt_versions cross-check OK against committed run " + runId);
        } catch (IOException e) {
            throw new IllegalStateException("evalSnapshot: could not read committed run report "
                    + reportJson + ": " + e.getMessage(), e);
        }
    }

    private void writeSnapshot() throws IOException {
        String dir = System.getProperty("eval.snapshot.dir",
                "src/test/resources/golden/snapshots");
        String runId = System.getProperty("eval.run.id", "UNSET");

        // Build-ENFORCED prompt-version linkage (review fix minor #3): when the named
        // live run is committed (its report.json exists), the about-to-be-written
        // constants MUST match that run's recorded prompt_versions — otherwise the
        // snapshot would stamp a run id that scored a DIFFERENT prompt. A conscious
        // waiver (-PevalWaiver, surfaced as eval.waiver) skips this technical check; a
        // missing run dir was already rejected by the gradle task's doFirst.
        verifyRunLinkage(runId);

        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        sb.append("  \"generated_at\": \"").append(java.time.Instant.now()).append("\",\n");
        sb.append("  \"eval_run_id\": \"").append(runId).append("\",\n");
        sb.append("  \"prompt_versions\": {\n");
        sb.append("    \"extraction_prompt_version\": \"")
                .append(PromptVersionRegistry.EXTRACTION_PROMPT_VERSION).append("\",\n");
        sb.append("    \"extraction_schema_version\": \"")
                .append(PromptVersionRegistry.EXTRACTION_SCHEMA_VERSION).append("\",\n");
        sb.append("    \"rating_prompt_version\": \"")
                .append(PromptVersionRegistry.RATING_PROMPT_VERSION).append("\",\n");
        sb.append("    \"identify_prompt_version\": \"")
                .append(PromptVersionRegistry.IDENTIFY_PROMPT_VERSION).append("\",\n");
        sb.append("    \"judge_prompt_version\": \"1\"\n");
        sb.append("  },\n");
        sb.append("  \"cases\": {\n");
        List<String> entries = new ArrayList<>();
        COMPUTED_DIGESTS.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(e -> entries.add("    \"" + e.getKey() + "\": \"" + e.getValue() + "\""));
        sb.append(String.join(",\n", entries)).append("\n");
        sb.append("  }\n");
        sb.append("}\n");

        Path out = Path.of(dir, "offline-summary.json");
        Files.createDirectories(out.getParent());
        Files.writeString(out, sb.toString());
        System.out.println("Wrote offline snapshot (" + COMPUTED_DIGESTS.size() + " cases) to " + out);
    }

    // ------------------------------------------------------------------ helpers

    /**
     * Midpoint rating for each DC flagged {@code deterministic_rating_expected}, used
     * to surface the deterministic value via canned rate (the committed rate stage
     * always fans out; the scorer separately cross-checks the real engine).
     */
    private Map<String, Integer> deterministicMidBands(GoldenExpectation expectation) {
        Map<String, Integer> out = new LinkedHashMap<>();
        if (expectation == null || expectation.phases() == null) return out;
        for (GoldenExpectation.PhaseExpectation pe : expectation.phases().values()) {
            if (pe.conditions() == null) continue;
            for (GoldenExpectation.ConditionExpectation ce : pe.conditions()) {
                if (Boolean.TRUE.equals(ce.deterministicRatingExpected()) && ce.ratingBand() != null) {
                    out.put(ce.vasrdCode(), (ce.ratingBand().min() + ce.ratingBand().max()) / 2);
                }
            }
        }
        return out;
    }
}
