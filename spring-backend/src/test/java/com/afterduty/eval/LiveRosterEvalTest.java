package com.afterduty.eval;

import com.afterduty.config.LlmRoutingProperties;
import com.afterduty.config.PromptVersionRegistry;
import com.afterduty.model.Claim;
import com.afterduty.model.EvidenceItem;
import com.afterduty.model.IdentifiedCondition;
import com.afterduty.model.LlmJob;
import com.afterduty.repository.AiCallLogRepository;
import com.afterduty.repository.AtomRepository;
import com.afterduty.repository.ClaimRepository;
import com.afterduty.repository.EvidenceItemRepository;
import com.afterduty.repository.IdentifiedConditionRepository;
import com.afterduty.repository.LlmJobRepository;
import com.afterduty.service.AiCostService;
import com.afterduty.service.VaMathService;
import com.afterduty.service.extraction.ExtractionStateMachine;
import com.afterduty.service.gap.GapStateMachine;
import com.afterduty.service.llm.LlmAsyncProvider;
import com.afterduty.service.llm.LlmJobPoller;
import com.afterduty.service.llm.LlmJobSubmitter;
import com.afterduty.service.llm.VertexGeminiAsyncProvider;
import com.afterduty.service.llm.VertexGeminiAsyncProviderImpl;
import com.afterduty.service.synthesis.PyramidingRules;
import com.afterduty.service.synthesis.SynthesisStateMachine;
import com.afterduty.service.synthesis.VasrdDecisionEngine;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Increment 8 LIVE scored eval — the FULL-ROSTER runner (spec §3) that the committed
 * {@link LiveGoldenEvalTest} left as "the operator's first-run step". Drives every
 * {@code active} golden case through the REAL pipeline on REAL provider lanes, scores
 * deterministically + with the Gemini judge, enforces the spend cap via the same
 * {@link EvalRunLoop}/{@link EvalSpendGuard} the offline enforcement test proves, and
 * writes the committed run directory under {@code docs/qa/evals/runs/<id>/}.
 *
 * <p><b>Provider lanes.</b> Unlike the spec's default "force batch → vertex-anthropic
 * realtime pin", this run pins every Claude purpose to the {@code anthropic-batch}
 * DIRECT lane exactly as the deployed service does (Vertex Claude has zero quota →
 * 429s; the direct Batches API is the only working Claude path). The pin is supplied
 * the documented way — {@code ROUTE_*_PROVIDER=anthropic-batch} env vars binding into
 * {@link LlmRoutingProperties} through Spring relaxed binding — NOT by editing any
 * provider. Gemini (extraction + judge) stays on Vertex, which has quota.
 *
 * <p>Excluded from the default suite ({@code @Tag("eval-live")}); run only with:
 * <pre>
 *   gcloud auth activate-service-account --key-file=$HOME/.gcp/default-compute-sa.json
 *   ANTHROPIC_API_KEY=... GOOGLE_APPLICATION_CREDENTIALS=$HOME/.gcp/default-compute-sa.json \
 *   GCP_PROJECT=craftloop-va-claim \
 *   ROUTE_SYNTHESIS_IDENTIFY_PROVIDER=anthropic-batch ... (all 7 Claude purposes) \
 *   JAVA_HOME=/opt/homebrew/opt/openjdk@21 ./gradlew evalLive -PliveEval [-Peval.cases=gc-001]
 * </pre>
 */
@Tag("eval-live")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DataJpaTest
@Import({
        // --- REAL provider lanes (no LlmProviderRouterTestConfig fake forcer) ---
        // LiveEvalProviderConfig contributes the @Primary LlmProviderRouter + the
        // VertexAnthropic/VertexGemini provider beans + the real AiCostService; we add
        // ONLY the anthropic-batch impl (the lane prod pins Claude to) and the routing
        // properties here so there is exactly one bean of each type.
        LiveEvalProviderConfig.class,
        com.afterduty.service.llm.AnthropicBatchProviderImpl.class,
        com.afterduty.config.LlmRoutingProperties.class,
        com.afterduty.service.llm.LlmJobService.class,
        com.afterduty.service.llm.LlmJobSubmitter.class,
        com.afterduty.service.llm.LlmJobPoller.class,
        // extraction (real Gemini)
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
        // synthesis (real Claude via anthropic-batch)
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
        // gap (real Claude via anthropic-batch)
        GapStateMachine.class,
        com.afterduty.service.gap.EvidenceGapAnalyzer.class,
        com.afterduty.service.gap.GapValidationAgent.class,
        com.afterduty.service.gap.WhatIfScenarioGenerator.class,
        com.afterduty.service.gap.UserGapStateService.class
})
@TestPropertySource(properties = {
        // Scheduler poll intervals parked — the runner ticks submitter/poller manually.
        "va-claim.llm.submitter.batch-size=50",
        "va-claim.llm.submitter.poll-ms=9999999",
        "va-claim.llm.submitter.initial-delay-ms=9999999",
        "va-claim.llm.poller.poll-ms=9999999",
        "va-claim.llm.poller.initial-delay-ms=9999999",
        "va-claim.llm.orphan-deadline-min=120",
        "va-claim.extraction.single-pass=true",
        "va-claim.analysis.incremental=true",
        "va-claim.rag.enabled=false",
        // P1-9 — the what-if stage defaults OFF in prod (rendered nowhere); this
        // suite exercises it, so turn it on explicitly.
        "va-claim.gap.whatif-enabled=true",
        // Real lanes resolve their endpoints/keys from these (env-overridable):
        //   va-claim.claude.api-key    <- ANTHROPIC_API_KEY (anthropic-batch direct lane)
        //   va-claim.vertex.project-id <- GCP_PROJECT (Vertex Anthropic realtime lane)
        //   va-claim.gemini.project-id <- GCP_PROJECT (Vertex Gemini extraction lane).
        //     This bridge is LOAD-BEARING: VertexGeminiAsyncProviderImpl reads
        //     va-claim.gemini.project-id (application.yml default is EMPTY), so without
        //     it an operator who omits GCP_PROJECT from the env gets a phantom run —
        //     extraction URLs hit "projects//locations/..." and every job fails in
        //     milliseconds with zero spend (the 2026-06-12T0746 phantom baseline).
        "va-claim.vertex.project-id=${GCP_PROJECT:craftloop-va-claim}",
        "va-claim.gemini.project-id=${GCP_PROJECT:craftloop-va-claim}",
        "va-claim.claude.api-key=${ANTHROPIC_API_KEY:}"
})
class LiveRosterEvalTest {

    /**
     * Wall-clock tick budget for a live phase. Batch-lane Claude jobs settle in
     * minutes (typically), max 24h. The runner sleeps {@link #POLL_SLEEP_MS} between
     * poller ticks; budget × sleep bounds the per-stage wait. 600 ticks × 5s ≈ 50 min
     * per stage ceiling — generous for the small golden docs, well under a hung run.
     */
    static final int LIVE_TICK_BUDGET = 600;
    static final long POLL_SLEEP_MS = 5_000L;

    @Autowired ExtractionStateMachine extraction;
    @Autowired SynthesisStateMachine synthesis;
    @Autowired GapStateMachine gap;
    @Autowired LlmJobSubmitter submitter;
    @Autowired LlmJobPoller poller;
    @Autowired ClaimRepository claimRepository;
    @Autowired AtomRepository atomRepository;
    @Autowired IdentifiedConditionRepository conditionRepository;
    @Autowired EvidenceItemRepository evidenceItemRepository;
    @Autowired LlmJobRepository llmJobRepository;
    @Autowired AiCallLogRepository aiCallLogRepository;
    @Autowired PyramidingRules pyramidingRules;
    @Autowired VaMathService vaMathService;
    @Autowired VasrdDecisionEngine vasrdDecisionEngine;
    @Autowired LlmRoutingProperties routing;

    private final GoldenCaseLoader loader = new GoldenCaseLoader();
    private final ObjectMapper om = new ObjectMapper();

    @Test
    void liveRoster() throws Exception {
        Instant runStart = Instant.now();
        String runId = runStart.toString().replaceAll("[:.]", "").substring(0, 15)
                + (baseline() == null ? "-baseline" : "-candidate");

        // Spend cap bound to the REAL H2 ledger (pipeline rows + eval_judge rows).
        double cap = maxSpendUsd();
        EvalSpendGuard guard = new EvalSpendGuard(cap, () -> {
            BigDecimal t = aiCallLogRepository.totalCostSince(runStart);
            return t == null ? BigDecimal.ZERO : t;
        });
        EvalRunLoop loop = new EvalRunLoop(guard);

        DeterministicScorer scorer =
                new DeterministicScorer(pyramidingRules, vaMathService, vasrdDecisionEngine);
        EvalJudge judge = new EvalJudge(judgeModel(), buildJudgeCaller());

        List<GoldenCaseLoader.LoadedCase> cases = rosterSubset();
        System.out.println("=== LIVE ROSTER: " + cases.size() + " case(s), cap $" + cap
                + ", judge " + judgeModel() + " ===");
        System.out.println("Resolved routing (purpose -> provider/model):");
        Map<String, Object> routingTable = resolvedRouting();
        routingTable.forEach((k, v) -> System.out.println("  " + k + " -> " + v));

        Map<String, EvalReportWriter.CaseReport> reports = new LinkedHashMap<>();
        List<String> ids = cases.stream().map(GoldenCaseLoader.LoadedCase::id).toList();

        EvalRunLoop.RunResult result = loop.run(ids, caseId -> {
            GoldenCaseLoader.LoadedCase loaded = cases.stream()
                    .filter(c -> c.id().equals(caseId)).findFirst().orElseThrow();
            BigDecimal before = nz(aiCallLogRepository.totalCostSince(runStart));
            try {
                EvalReportWriter.CaseReport cr =
                        runOneCase(loaded, scorer, judge, runStart, before);
                reports.put(caseId, cr);
            } catch (RuntimeException e) {
                System.out.println("case " + caseId + " ERROR: " + e.getMessage());
                BigDecimal spent = nz(aiCallLogRepository.totalCostSince(runStart)).subtract(before);
                reports.put(caseId, new EvalReportWriter.CaseReport(
                        caseId, "error", Map.of(),
                        JudgeScore.error(caseId, judgeModel(), "case errored: " + e.getMessage()),
                        spent.doubleValue()));
                throw e;   // EvalRunLoop records ERROR status
            }
        });

        // Cases the loop skipped (spend cap) get a skipped stub if not already present.
        for (EvalRunLoop.CaseOutcome o : result.outcomes()) {
            reports.computeIfAbsent(o.caseId(), id -> new EvalReportWriter.CaseReport(
                    id, o.status().name().toLowerCase(), Map.of(), null, 0.0));
        }

        List<EvalReportWriter.CaseReport> caseReports =
                ids.stream().map(reports::get).filter(java.util.Objects::nonNull).toList();

        // Verdict (spec §3.5) via the tested policy helper.
        EvalVerdict.Result verdict = EvalVerdict.decide(caseReports, baselineMeans());

        BigDecimal total = nz(aiCallLogRepository.totalCostSince(runStart));
        BigDecimal judgeUsd = nz(aiCallLogRepository.totalCostSince(runStart)); // refined below
        // pipeline vs judge split from the ledger by call type.
        double judgeCost = judgeSpend(runStart);
        double totalCost = total.doubleValue();
        double pipelineCost = Math.max(0.0, totalCost - judgeCost);

        var report = new EvalReportWriter.RunReport(
                runId, gitSha(), runStart.toString(),
                routingTable,
                Map.of(
                        "extraction_prompt_version", PromptVersionRegistry.EXTRACTION_PROMPT_VERSION,
                        "extraction_schema_version", PromptVersionRegistry.EXTRACTION_SCHEMA_VERSION,
                        "rating_prompt_version", PromptVersionRegistry.RATING_PROMPT_VERSION,
                        "judge_prompt_version", EvalJudge.JUDGE_PROMPT_VERSION),
                caseReports,
                new EvalReportWriter.Aggregate(EvalVerdict.aggregateMeans(caseReports), verdict.hardFailCount()),
                new EvalReportWriter.Cost(pipelineCost, judgeCost, totalCost, result.abortedReason()),
                baseline(),
                verdict.verdict().name());

        String reportDir = System.getProperty("eval.report.dir",
                System.getProperty("java.io.tmpdir") + "/eval-runs");
        Path written = new EvalReportWriter().write(Path.of(reportDir), report);
        System.out.println("\n=== LIVE RUN COMPLETE ===");
        System.out.println("run id:   " + runId);
        System.out.println("verdict:  " + verdict.verdict() + " " + verdict.reasons());
        System.out.println("spend:    $" + String.format("%.4f", totalCost)
                + " (pipeline $" + String.format("%.4f", pipelineCost)
                + ", judge $" + String.format("%.4f", judgeCost) + ")"
                + (result.abortedReason() != null ? "  ABORTED: " + result.abortedReason() : ""));
        System.out.println("report:   " + written);
        result.outcomes().forEach(o -> System.out.println("  " + o.caseId() + " : " + o.status()
                + (o.detail() == null ? "" : " (" + o.detail() + ")")));

        // The run's own artifact existence is the acceptance assertion (cost ledger
        // non-zero on a successful pipeline run; judge JSON parsed). A spend-cap abort
        // is a legitimate terminal state, not a test failure.
        assertTrue(java.nio.file.Files.exists(written.resolve("report.json")),
                "report.json must be written");
        assertTrue(java.nio.file.Files.exists(written.resolve("report.md")),
                "report.md must be written");
    }

    // ------------------------------------------------------------------ one case

    private EvalReportWriter.CaseReport runOneCase(GoldenCaseLoader.LoadedCase loaded,
                                                   DeterministicScorer scorer,
                                                   EvalJudge judge,
                                                   Instant runStart,
                                                   BigDecimal costBefore) {
        System.out.println("\n--- " + loaded.id() + " " + loaded.caseFile().slug() + " ---");
        Claim claim = claimRepository.save(Claim.builder()
                .userId(7L).status(Claim.ClaimStatus.DRAFT).build());

        Map<String, Long> priorJobCounts = new java.util.HashMap<>();
        LivePipelineEnd lastEnd = null;
        Map<String, Double> deterministic = new LinkedHashMap<>();
        List<String> caseFailures = new ArrayList<>();

        for (GoldenCase.Phase phase : loaded.caseFile().phases()) {
            if (lastEnd != null) {
                priorJobCounts.put("synthesis_rate", lastEnd.distinctRateJobs);
                priorJobCounts.put("gap_evidence", lastEnd.distinctGapJobs);
            }
            seedPhaseEvidence(claim, loaded, phase.name());
            LivePipelineEnd end = drivePhase(claim);
            lastEnd = end;

            GoldenExpectation.PhaseExpectation expect =
                    loaded.expectation().phases().get(phase.name());
            if (expect == null) {
                caseFailures.add("no expectation for phase '" + phase.name() + "'");
                continue;
            }
            DeterministicScorer.PhaseScore ps =
                    scorer.score(loaded.id(), phase.name(), expect, end.endState, priorJobCounts);
            // Live tier records metrics as ACCURACY (not a 100%-or-bug gate).
            deterministic.put("condition_recall", ps.conditionRecall());
            deterministic.put("condition_precision", ps.conditionPrecision());
            deterministic.put("rating_band_accuracy", ps.ratingBandAccuracy());
            deterministic.put("gap_structural", ps.gapStructural());
            if (!ps.passed()) {
                ps.failures().forEach(f -> caseFailures.add("[" + phase.name() + "] " + f));
            }
        }
        if (!caseFailures.isEmpty()) {
            System.out.println("  deterministic notes: " + caseFailures);
        }

        // Judge on the FINAL phase's active conditions + the case's source docs.
        List<IdentifiedCondition> active = lastEnd == null ? List.of() : lastEnd.active;
        List<String> docs = allDocBodies(loaded);
        JudgeScore js = judge.grade(loaded.id(), docs, active);
        System.out.println("  judge: " + js.scores() + " hardFail=" + js.anyHardFail());

        double costUsd = nz(aiCallLogRepository.totalCostSince(runStart)).subtract(costBefore).doubleValue();
        System.out.println("  active=" + active.size() + "  case cost=$" + String.format("%.4f", costUsd));
        return new EvalReportWriter.CaseReport(loaded.id(), "scored", deterministic, js, costUsd);
    }

    // ------------------------------------------------------------------ live seeding (no canned)

    private void seedPhaseEvidence(Claim claim, GoldenCaseLoader.LoadedCase view, String phaseName) {
        GoldenCase.Phase phase = view.caseFile().phases().stream()
                .filter(p -> phaseName.equals(p.name())).findFirst().orElseThrow();
        for (GoldenCase.Doc doc : phase.docs()) {
            String body = loader.readText(view.basePath(), doc.file());
            evidenceItemRepository.save(EvidenceItem.builder()
                    .claimId(claim.getId())
                    .sourceType("text")
                    .rawContent(body)
                    .filename(doc.filename())
                    .processingStatus("pending")
                    .build());
            // NO setResponseForEvidence — the real Gemini extraction lane does the work.
        }
    }

    private List<String> allDocBodies(GoldenCaseLoader.LoadedCase view) {
        List<String> out = new ArrayList<>();
        for (GoldenCase.Phase phase : view.caseFile().phases()) {
            for (GoldenCase.Doc doc : phase.docs()) {
                out.add(loader.readText(view.basePath(), doc.file()));
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ live drive (batch-aware)

    /** End-of-phase snapshot built from the REAL DB + LlmJob ledger (no fake). */
    private static final class LivePipelineEnd {
        final PipelineEndState endState;
        final List<IdentifiedCondition> active;
        final long distinctRateJobs;
        final long distinctGapJobs;
        LivePipelineEnd(PipelineEndState e, List<IdentifiedCondition> a, long r, long g) {
            this.endState = e; this.active = a; this.distinctRateJobs = r; this.distinctGapJobs = g;
        }
    }

    private LivePipelineEnd drivePhase(Claim claim) {
        runStage("extraction", claim);
        Claim afterExtract = reload(claim);
        boolean extractionErrored = afterExtract.getStatus() == Claim.ClaimStatus.ERROR
                && afterExtract.getExtractionState() == null;

        boolean synthesisErrored = false;
        if (!extractionErrored) {
            resetSynthesis(claim);
            runStage("synthesis", claim);
            synthesisErrored = reload(claim).getStatus() == Claim.ClaimStatus.ERROR;
            if (!synthesisErrored) {
                resetGap(claim);
                runStage("gap", claim);
            }
        }
        return snapshot(claim, extractionErrored || synthesisErrored);
    }

    /** Drive one stage to terminal, sleeping between polls so batch jobs can settle. */
    private void runStage(String stage, Claim claim) {
        for (int i = 0; i < LIVE_TICK_BUDGET; i++) {
            Claim cur = reload(claim);
            boolean started = i > 0;
            if (started && stageState(stage, cur) == null) {
                return;
            }
            advance(stage, cur);
            submitter.tick();
            poller.tick();
            Claim after = reload(claim);
            if ("extraction".equals(stage)
                    && after.getStatus() == Claim.ClaimStatus.ERROR
                    && after.getExtractionState() == null) {
                return; // hard extraction failure
            }
            // If there are active (submitted, not-yet-terminal) jobs, wait before
            // the next poll so we don't spin the batch API. The state machine cannot
            // advance until the provider results land.
            if (!llmJobRepository.findActiveJobs().isEmpty()) {
                sleep(POLL_SLEEP_MS);
            }
        }
        throw new IllegalStateException(stage + " did not reach terminal within "
                + LIVE_TICK_BUDGET + " ticks (~"
                + (LIVE_TICK_BUDGET * POLL_SLEEP_MS / 60000) + " min) for claim " + claim.getId());
    }

    private Object stageState(String stage, Claim c) {
        return switch (stage) {
            case "extraction" -> c.getExtractionState();
            case "synthesis" -> c.getSynthesisState();
            case "gap" -> c.getGapState();
            default -> null;
        };
    }

    private void advance(String stage, Claim c) {
        switch (stage) {
            case "extraction" -> extraction.advance(c);
            case "synthesis" -> synthesis.advance(c);
            case "gap" -> gap.advance(c);
            default -> throw new IllegalArgumentException(stage);
        }
    }

    private void resetSynthesis(Claim claim) {
        Claim c = reload(claim);
        c.setSynthesisState(null);
        c.setSynthesisInProgress(false);
        claimRepository.save(c);
    }

    private void resetGap(Claim claim) {
        Claim c = reload(claim);
        c.setGapState(null);
        c.setGapAnalysisInProgress(false);
        claimRepository.save(c);
    }

    private LivePipelineEnd snapshot(Claim claim, boolean errored) {
        Claim reloaded = reload(claim);
        List<IdentifiedCondition> active =
                conditionRepository.findByClaimIdAndSupersededByIsNull(claim.getId());
        List<IdentifiedCondition> allRows = conditionRepository.findByClaimId(claim.getId());
        var liveAtoms = atomRepository.findByClaimIdAndSupersededByIsNull(claim.getId());
        var evidence = evidenceItemRepository.findByClaimId(claim.getId());

        // Build SubmittedJob entries from the REAL LlmJob ledger so PipelineEndState +
        // DeterministicScorer (dirty-scope) work unchanged without the fake.
        List<com.afterduty.service.llm.FakeLlmAsyncProvider.SubmittedJob> jobs = new ArrayList<>();
        for (LlmJob j : llmJobRepository.findAll()) {
            jobs.add(new com.afterduty.service.llm.FakeLlmAsyncProvider.SubmittedJob(
                    j.getId(), j.getPurpose(), null, j.getConditionId(), null, null));
        }
        long rateJobs = jobs.stream().filter(s -> "synthesis_rate".equals(s.purpose()))
                .map(com.afterduty.service.llm.FakeLlmAsyncProvider.SubmittedJob::internalJobId)
                .distinct().count();
        long gapJobs = jobs.stream().filter(s -> "gap_evidence".equals(s.purpose()))
                .map(com.afterduty.service.llm.FakeLlmAsyncProvider.SubmittedJob::internalJobId)
                .distinct().count();

        String outcome;
        if (errored || reloaded.getStatus() == Claim.ClaimStatus.ERROR) {
            outcome = PipelineEndState.OUTCOME_FAILED;
        } else if (active.isEmpty()) {
            outcome = PipelineEndState.OUTCOME_COMPLETE_ZERO;
        } else {
            outcome = PipelineEndState.OUTCOME_COMPLETE;
        }
        PipelineEndState end = new PipelineEndState(outcome, reloaded, active, allRows, liveAtoms, evidence, jobs);
        return new LivePipelineEnd(end, active, rateJobs, gapJobs);
    }

    private Claim reload(Claim claim) {
        return claimRepository.findById(claim.getId()).orElseThrow();
    }

    // ------------------------------------------------------------------ judge lane

    private java.util.function.Function<String, String> buildJudgeCaller() throws Exception {
        String project = System.getenv().getOrDefault("GCP_PROJECT", "craftloop-va-claim");
        String judgeModel = judgeModel();
        VertexGeminiAsyncProviderImpl gemini = new VertexGeminiAsyncProviderImpl();
        set(gemini, "projectId", project);
        set(gemini, "location", "global");
        set(gemini, "defaultModelName", judgeModel);
        gemini.init();
        return prompt -> {
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
                // submit() only STARTS the virtual-thread call; fetching immediately
                // races it and gets a success-with-null-result (the "r.result is null"
                // NPE every prior run logged — the judge had never actually scored).
                // Poll until the call settles, like LlmJobPoller does in production.
                String providerJobId = job.getId().toString();
                for (int i = 0; i < 120
                        && gemini.poll(providerJobId)
                           == com.afterduty.service.llm.ProviderJobStatus.IN_PROGRESS; i++) {
                    sleep(1_000L);
                }
                List<LlmAsyncProvider.FetchedResult> results =
                        gemini.fetchResults(providerJobId, List.of(job));
                LlmAsyncProvider.FetchedResult r = results.get(0);
                if (!r.succeeded || r.result == null) {
                    System.out.println("judge call failed: "
                            + (r.errorMessage != null ? r.errorMessage : "no result after poll timeout"));
                    return "{}";
                }
                return r.result.getText();
            } catch (Exception e) {
                System.out.println("judge call threw: " + e.getMessage());
                return "{}";
            }
        };
    }

    /** Best-effort judge-only spend split: judge calls book callType eval_judge rows. */
    private double judgeSpend(Instant since) {
        try {
            for (Object[] row : aiCallLogRepository.costByUserAndCallTypeSince(since)) {
                // row shape is impl-specific; guard defensively.
                if (row.length >= 2 && "eval_judge".equals(String.valueOf(row[row.length - 2]))) {
                    Object v = row[row.length - 1];
                    if (v instanceof Number n) return n.doubleValue();
                }
            }
        } catch (Exception ignored) {
            // The judge lane here is a one-shot provider call that may not book an
            // AiCallLog row in this slice; treat judge spend as 0 if not separable.
        }
        return 0.0;
    }

    // ------------------------------------------------------------------ roster / routing / config

    private List<GoldenCaseLoader.LoadedCase> rosterSubset() {
        List<GoldenCaseLoader.LoadedCase> all = loader.loadActive();
        String subset = System.getProperty("eval.cases");
        if (subset == null || subset.isBlank()) return all;
        java.util.Set<String> wanted = new java.util.LinkedHashSet<>(
                List.of(subset.trim().split("\\s*,\\s*")));
        return all.stream().filter(c -> wanted.contains(c.id())).toList();
    }

    private Map<String, Object> resolvedRouting() {
        Map<String, Object> out = new LinkedHashMap<>();
        if (routing != null && routing.getPurposes() != null) {
            routing.getPurposes().forEach((purpose, route) -> {
                if (route == null) return;
                out.put(purpose, (route.getProvider() == null ? "?" : route.getProvider())
                        + "/" + (route.getModel() == null ? "?" : route.getModel()));
            });
        }
        out.put("eval_judge", "vertex-gemini/" + judgeModel());
        return out;
    }

    private Map<String, Double> baselineMeans() {
        String baseline = baseline();
        if (baseline == null || baseline.isBlank()) return null;
        String reportDir = System.getProperty("eval.report.dir",
                System.getProperty("java.io.tmpdir") + "/eval-runs");
        Path reportJson = Path.of(reportDir, baseline, "report.json");
        if (!java.nio.file.Files.exists(reportJson)) return null;
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> root = om.readValue(reportJson.toFile(), Map.class);
            Object agg = root.get("aggregate");
            if (agg instanceof Map<?, ?> a && a.get("means") instanceof Map<?, ?> means) {
                Map<String, Double> r = new LinkedHashMap<>();
                means.forEach((k, v) -> {
                    if (v instanceof Number n) r.put(String.valueOf(k), n.doubleValue());
                });
                return r;
            }
        } catch (Exception e) {
            System.out.println("baseline report unreadable: " + e.getMessage());
        }
        return null;
    }

    private static String baseline() {
        String b = System.getProperty("eval.baseline");
        return b == null || b.isBlank() ? null : b;
    }

    private static double maxSpendUsd() {
        String env = System.getenv("EVAL_MAX_SPEND_USD");
        if (env != null && !env.isBlank()) {
            try { return Double.parseDouble(env.trim()); } catch (NumberFormatException ignored) { }
        }
        return 15.00;
    }

    private static String judgeModel() {
        return System.getenv().getOrDefault("EVAL_JUDGE_MODEL", "gemini-3.1-pro-preview");
    }

    private static String gitSha() {
        return System.getProperty("eval.git.sha", "unknown");
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    private static void set(Object target, String field, Object value) throws Exception {
        Field f = target.getClass().getDeclaredField(field);
        f.setAccessible(true);
        f.set(target, value);
    }
}
