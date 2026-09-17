package com.afterduty.eval;

import com.afterduty.model.AiCallLog;
import com.afterduty.model.Claim;
import com.afterduty.repository.AiCallLogRepository;
import com.afterduty.repository.AtomRepository;
import com.afterduty.repository.ClaimRepository;
import com.afterduty.repository.EvidenceItemRepository;
import com.afterduty.repository.IdentifiedConditionRepository;
import com.afterduty.service.AiCostService;
import com.afterduty.service.extraction.ExtractionStateMachine;
import com.afterduty.service.gap.GapStateMachine;
import com.afterduty.service.llm.FakeLlmAsyncProvider;
import com.afterduty.service.llm.LlmJobPoller;
import com.afterduty.service.llm.LlmJobSubmitter;
import com.afterduty.service.synthesis.SynthesisStateMachine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Increment 8 review FIX (major #1): the spend cap, proven as ENFORCEMENT against
 * a REAL run loop and a REAL cost ledger — not just as a decision unit in isolation.
 *
 * <p>The adversarial review found that {@link EvalSpendGuard} was only ever driven
 * by {@code EvalSpendGuardTest}'s simulated loop with a canned {@code BigDecimal}
 * supplier; nothing bound it to {@code AiCallLogRepository.totalCostSince} and no
 * run loop existed to abort, so spec §3.3's "abort remaining cases MID-RUN" could
 * not actually happen. This test closes that gap WITHOUT live calls:
 *
 * <ul>
 *   <li>It drives a REAL golden case (gc-001) through the REAL pipeline
 *       (extraction → synthesis → gap) on the fake substrate via {@link PipelineDriver}
 *       — the same loop body the live runner uses — so the run loop genuinely runs a
 *       pipeline, not a hand-built condition.</li>
 *   <li>It wires a REAL {@link AiCostService} over the H2 {@link AiCallLogRepository}
 *       (overriding the offline no-op cost service) and binds the
 *       {@link EvalSpendGuard} supplier to {@code repo.totalCostSince(runStart)} — the
 *       exact production binding named in spec §3.3, exercised against a real query.</li>
 *   <li>It books real {@code AiCallLog} rows (pipeline + a {@code callType="eval_judge"}
 *       row) so the ledger the guard reads is the same one the live tier writes; then
 *       it ratchets spend across a multi-case roster and asserts the {@link EvalRunLoop}
 *       trips the guard MID-ROSTER and marks every remaining case {@code SKIPPED} with
 *       {@code aborted_reason = "spend_cap"}.</li>
 * </ul>
 */
@Tag("eval-offline")
@Tag("regression")
@DataJpaTest
@Import({
        SpendCapEnforcementTest.RealCostSubstrateConfig.class,
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
class SpendCapEnforcementTest {

    /**
     * Like {@code LlmProviderRouterTestConfig} (fake provider + fake-forcing router)
     * but with a REAL {@link AiCostService} over H2 instead of the offline no-op, so
     * every booked row actually lands in {@link AiCallLogRepository} and
     * {@code totalCostSince} sums real cost — the binding the spend cap depends on.
     */
    @TestConfiguration
    static class RealCostSubstrateConfig {
        @Bean
        @Primary
        public com.afterduty.service.llm.FakeLlmAsyncProvider fakeLlmAsyncProvider() {
            return new com.afterduty.service.llm.FakeLlmAsyncProvider();
        }

        @Bean
        @Primary
        public com.afterduty.service.llm.LlmProviderRouter testLlmProviderRouter(
                com.afterduty.service.llm.FakeLlmAsyncProvider fake) {
            return new com.afterduty.service.llm.LlmProviderRouter(List.of(fake)) {
                @Override
                public com.afterduty.service.llm.LlmAsyncProvider resolveProvider(
                        com.afterduty.service.llm.LlmJobRequest req) {
                    return fake;
                }
                @Override
                public com.afterduty.service.llm.LlmAsyncProvider byName(String providerName) {
                    return fake;
                }
                @Override
                public String resolveModel(com.afterduty.service.llm.LlmJobRequest req,
                                           com.afterduty.service.llm.LlmAsyncProvider provider) {
                    return "fake-model-1";
                }
            };
        }

        @Bean
        @Primary
        public AiCostService realAiCostService(AiCallLogRepository repo) {
            return new AiCostService(repo);
        }
    }

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
    @Autowired AiCallLogRepository aiCallLogRepository;
    @Autowired AiCostService aiCostService;

    private final GoldenCaseLoader loader = new GoldenCaseLoader();

    @BeforeEach
    void reset() {
        fake.reset();
        aiCallLogRepository.deleteAll();
    }

    /**
     * The run loop drives a REAL pipeline case, books real cost rows, and the guard —
     * bound to the REAL {@code totalCostSince} query — stays UNDER an ample cap. Proves
     * the happy path: enforcement wiring runs a real case end-to-end without false-abort.
     */
    @Test
    void runLoop_drivesRealPipeline_booksRealLedger_underCap() {
        GoldenCaseLoader.LoadedCase gc001 = loadCase("gc-001");
        Instant runStart = Instant.now().minusSeconds(1);

        // Guard bound to the REAL repository query (NOT a canned BigDecimal).
        EvalSpendGuard guard = new EvalSpendGuard(15.00,
                () -> aiCallLogRepository.totalCostSince(runStart));
        EvalRunLoop loop = new EvalRunLoop(guard);

        EvalRunLoop.RunResult result = loop.run(List.of("gc-001"),
                caseId -> driveRealCaseAndBookJudge(gc001));

        assertEquals(EvalRunLoop.CaseOutcome.Status.SCORED, result.forCase("gc-001").status(),
                "the real pipeline case ran and scored under the cap");
        assertFalse(result.aborted(), "well under the $15 cap — no abort");
        assertFalse(guard.isTripped());

        // The ledger is REAL: the pipeline booked AiCallLog rows through the real cost
        // service, and our eval_judge row is present. totalCostSince sees them all.
        long judgeRows = aiCallLogRepository.findAll().stream()
                .filter(r -> "eval_judge".equals(r.getCallType())).count();
        assertEquals(1, judgeRows, "the eval_judge row was booked into the real ledger");
        assertTrue(aiCallLogRepository.count() > 1,
                "the real pipeline booked its own AiCallLog rows too");
        assertNotNull(guard.spentUsd(), "the guard read a real BigDecimal total from the repo");
    }

    /**
     * The CORE enforcement proof: a runaway run is actually STOPPED mid-roster. Spend
     * ratchets up as cases run (booked as real {@code AiCallLog} rows); the guard —
     * bound to {@code totalCostSince} — trips the moment the real ledger crosses the
     * cap, and {@link EvalRunLoop} marks every remaining case SKIPPED and stamps
     * {@code aborted_reason = "spend_cap"}. This is the property §3.3 promised that
     * had no test before this fix.
     */
    @Test
    void runLoop_abortsRemainingCases_whenRealLedgerCrossesCap() {
        Instant runStart = Instant.now().minusSeconds(1);
        EvalSpendGuard guard = new EvalSpendGuard(15.00,
                () -> aiCallLogRepository.totalCostSince(runStart));
        EvalRunLoop loop = new EvalRunLoop(guard);

        // Each "case" books a real cost row: $6, then $10 (cumulative $16 > $15 cap),
        // then $4. The guard must trip after case 2 and skip case 3.
        Map<String, BigDecimal> perCaseCost = new LinkedHashMap<>();
        perCaseCost.put("gc-A", new BigDecimal("6.00"));
        perCaseCost.put("gc-B", new BigDecimal("10.00"));
        perCaseCost.put("gc-C", new BigDecimal("4.00"));

        EvalRunLoop.RunResult result = loop.run(List.copyOf(perCaseCost.keySet()),
                caseId -> bookRealCostRow(caseId, perCaseCost.get(caseId)));

        assertEquals(EvalRunLoop.CaseOutcome.Status.SCORED, result.forCase("gc-A").status());
        assertEquals(EvalRunLoop.CaseOutcome.Status.SCORED, result.forCase("gc-B").status(),
                "case B was allowed to START at $6 (< cap), then pushed the ledger to $16");
        assertEquals(EvalRunLoop.CaseOutcome.Status.SKIPPED, result.forCase("gc-C").status(),
                "real ledger at $16 ⇒ remaining case skipped (mid-run abort)");
        assertTrue(result.aborted());
        assertEquals(EvalSpendGuard.ABORTED_REASON, result.abortedReason());
        assertTrue(guard.isTripped());

        // The abort was driven by REAL persisted rows, not a canned number.
        assertEquals(0, new BigDecimal("16.00").compareTo(aiCallLogRepository.totalCostSince(runStart)),
                "the ledger the guard read actually summed to $16 across booked rows");
    }

    // ------------------------------------------------------------------ helpers

    /** Run gc-001's single phase through the real pipeline, then book an eval_judge row. */
    private void driveRealCaseAndBookJudge(GoldenCaseLoader.LoadedCase view) {
        GoldenCaseSeeder seeder = new GoldenCaseSeeder(
                claimRepository, evidenceItemRepository, conditionRepository, fake, loader);
        PipelineDriver driver = new PipelineDriver(
                extraction, synthesis, gap, submitter, poller,
                claimRepository, atomRepository, conditionRepository, evidenceItemRepository, fake);

        seeder.registerCanned(view, Map.of());
        Claim claim = seeder.createClaim(7L);
        for (GoldenCase.Phase phase : view.caseFile().phases()) {
            seeder.seedPhaseEvidence(claim, view, phase.name());
            driver.runPhase(claim);
        }

        // The judge call books its own AiCallLog row inside the cap (spec §3.4) — model
        // it here with a real booked row so the guard's ledger includes judge spend.
        AiCallLog judgeRow = AiCallLog.builder()
                .claimId(claim.getId())
                .callType("eval_judge")
                .provider("vertex-gemini")
                .modelName("gemini-3.1-pro-preview")
                .inputTokens(0L).outputTokens(0L).thinkingTokens(0L)
                .status("success")
                .build();
        aiCostService.recordCall(judgeRow);
    }

    /** Book a single real cost row of the given amount (a stand-in for a case's spend). */
    private void bookRealCostRow(String caseId, BigDecimal cost) {
        AiCallLog row = AiCallLog.builder()
                .callType("eval_pipeline")
                .provider("vertex-anthropic")
                .modelName("claude-opus-4-8")
                .status("success")
                .build();
        // Set the priced total directly so the assertion is exact (the row is otherwise
        // a real persisted AiCallLog the totalCostSince query sums over).
        AiCallLog saved = aiCostService.recordCall(row);
        saved.setTotalCost(cost);
        aiCallLogRepository.save(saved);
    }

    private GoldenCaseLoader.LoadedCase loadCase(String id) {
        return loader.loadActive().stream()
                .filter(c -> id.equals(c.id()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("golden case not found: " + id));
    }
}
