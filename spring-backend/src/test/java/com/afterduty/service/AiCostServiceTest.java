package com.afterduty.service;

import com.afterduty.model.AiCallLog;
import com.afterduty.repository.AiCallLogRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Verifies the corrected price table (list prices per 1M tokens) and the 50%
 * batch-lane discount. The old table priced Opus 4.7 at $15/$75 (real list:
 * $5/$25) and never applied the Anthropic Message Batches discount, so the
 * cost ledger overstated Claude spend ~6x — mis-calibrating the per-user cap.
 */
class AiCostServiceTest {

    private static final long ONE_MILLION_TOKENS = 1_000_000L;

    AiCallLogRepository repo;
    AiCostService svc;

    @BeforeEach
    void setUp() {
        repo = mock(AiCallLogRepository.class);
        when(repo.save(any(AiCallLog.class))).thenAnswer(inv -> inv.getArgument(0));
        when(repo.totalCostSince(any())).thenReturn(BigDecimal.ZERO);
        svc = new AiCostService(repo);
    }

    // -------------------------------------------------------------------------
    // Corrected list prices
    // -------------------------------------------------------------------------

    @Test
    void opus47_pricedAtCorrectedListPrice() {
        AiCallLog row = svc.recordCall(row("claude-opus-4-7", ONE_MILLION_TOKENS, ONE_MILLION_TOKENS, 0, false));
        assertThat(row.getInputCost()).isEqualByComparingTo("5.00");
        assertThat(row.getOutputCost()).isEqualByComparingTo("25.00");
        assertThat(row.getTotalCost()).isEqualByComparingTo("30.00");
    }

    @Test
    void newModels_pricedPerVerifiedTable() {
        assertModelPricing("claude-opus-4-8", "5.00", "25.00");
        assertModelPricing("claude-fable-5", "10.00", "50.00");
        assertModelPricing("claude-sonnet-4-6", "3.00", "15.00");
        assertModelPricing("claude-haiku-4-5", "1.00", "5.00");
        assertModelPricing("gemini-3-flash-preview", "0.50", "3.00");
        assertModelPricing("gemini-3.1-flash-lite", "0.25", "1.50");
        // unchanged
        assertModelPricing("gemini-3.1-pro-preview", "2.00", "12.00");
    }

    @Test
    void unknownModel_usesFallbackRates() {
        AiCallLog row = svc.recordCall(row("some-future-model", ONE_MILLION_TOKENS, ONE_MILLION_TOKENS, 0, false));
        assertThat(row.getInputCost()).isEqualByComparingTo("5.00");
        assertThat(row.getOutputCost()).isEqualByComparingTo("15.00");
    }

    @Test
    void thinkingTokens_billedAtOutputRate() {
        AiCallLog row = svc.recordCall(row("claude-opus-4-7", 0, 0, ONE_MILLION_TOKENS, false));
        assertThat(row.getThinkingCost()).isEqualByComparingTo("25.00");
        assertThat(row.getTotalCost()).isEqualByComparingTo("25.00");
    }

    // -------------------------------------------------------------------------
    // Batch-lane discount (Anthropic Message Batches = 50% of list)
    // -------------------------------------------------------------------------

    @Test
    void batchCall_billedAtHalfListPrice() {
        AiCallLog row = svc.recordCall(row("claude-opus-4-7", ONE_MILLION_TOKENS, ONE_MILLION_TOKENS, 0, true));
        assertThat(row.getInputCost()).isEqualByComparingTo("2.50");
        assertThat(row.getOutputCost()).isEqualByComparingTo("12.50");
        assertThat(row.getTotalCost()).isEqualByComparingTo("15.00");
    }

    @Test
    void batchDiscount_appliesToThinkingTokensToo() {
        AiCallLog row = svc.recordCall(row("claude-opus-4-7", 0, 0, ONE_MILLION_TOKENS, true));
        assertThat(row.getThinkingCost()).isEqualByComparingTo("12.50");
    }

    @Test
    void nullIsBatch_treatedAsRealtime() {
        // Pre-existing rows (and callers that never set the flag) must bill at
        // full list price, not the discount.
        AiCallLog row = row("claude-sonnet-4-6", ONE_MILLION_TOKENS, 0, 0, false);
        row.setIsBatch(null);
        row = svc.recordCall(row);
        assertThat(row.getInputCost()).isEqualByComparingTo("3.00");
    }

    @Test
    void zeroTokens_costZero() {
        AiCallLog row = svc.recordCall(row("claude-opus-4-8", 0, 0, 0, true));
        assertThat(row.getTotalCost()).isEqualByComparingTo("0");
    }

    // -------------------------------------------------------------------------
    // Regional premium (Claude on a Vertex regional endpoint = +10% over global;
    // us-east5 chosen for data residency)
    // -------------------------------------------------------------------------

    @Test
    void vertexAnthropic_onRegionalEndpoint_carriesTenPercentPremium() {
        AiCostService regional = new AiCostService(repo, "us-east5");
        AiCallLog row = regional.recordCall(vertexAnthropicRow("claude-sonnet-4-6"));
        assertThat(row.getInputCost()).isEqualByComparingTo("3.30");   // 3.00 × 1.10
        assertThat(row.getOutputCost()).isEqualByComparingTo("16.50"); // 15.00 × 1.10
    }

    @Test
    void vertexAnthropic_onGlobalEndpoint_billsListPrice() {
        AiCostService global = new AiCostService(repo, "global");
        AiCallLog row = global.recordCall(vertexAnthropicRow("claude-sonnet-4-6"));
        assertThat(row.getInputCost()).isEqualByComparingTo("3.00");
        assertThat(row.getOutputCost()).isEqualByComparingTo("15.00");
    }

    @Test
    void nonVertexProviders_neverCarryTheRegionalPremium() {
        // The direct-API batch lane is not a Vertex regional endpoint: 50% only.
        AiCostService regional = new AiCostService(repo, "us-east5");
        AiCallLog row = regional.recordCall(AiCallLog.builder()
                .callType("test").provider("anthropic-batch").modelName("claude-sonnet-4-6")
                .inputTokens(ONE_MILLION_TOKENS).outputTokens(ONE_MILLION_TOKENS)
                .thinkingTokens(0L).isBatch(true).build());
        assertThat(row.getInputCost()).isEqualByComparingTo("1.50");
        assertThat(row.getOutputCost()).isEqualByComparingTo("7.50");
    }

    // -------------------------------------------------------------------------
    // Cache-aware pricing (Mission 6a): read = 0.1× input, write = 1.25× input,
    // STACKING with batch 0.5× and Vertex-regional 1.1×. Anthropic input_tokens
    // EXCLUDE cache counts, so all three input-side costs are additive.
    // -------------------------------------------------------------------------

    @Test
    void cacheRead_pricedAtOneTenthInputRate() {
        // Sonnet input list = $3/MTok. 1M cache-read tokens → 3 × 0.1 = $0.30.
        AiCallLog row = svc.recordCall(cacheRow("claude-sonnet-4-6", 0, 0, ONE_MILLION_TOKENS, 0, false));
        assertThat(row.getInputCost()).isEqualByComparingTo("0.30");
        assertThat(row.getTotalCost()).isEqualByComparingTo("0.30");
    }

    @Test
    void cacheWrite_pricedAt1_25xInputRate() {
        // Sonnet input list = $3/MTok. 1M cache-write tokens → 3 × 1.25 = $3.75.
        AiCallLog row = svc.recordCall(cacheRow("claude-sonnet-4-6", 0, 0, 0, ONE_MILLION_TOKENS, false));
        assertThat(row.getInputCost()).isEqualByComparingTo("3.75");
        assertThat(row.getTotalCost()).isEqualByComparingTo("3.75");
    }

    @Test
    void regularInputPlusCacheReadAndWrite_areAdditive() {
        // input $3 (1M) + read $0.30 (1M×0.1) + write $3.75 (1M×1.25) = $7.05.
        AiCallLog row = svc.recordCall(
                cacheRow("claude-sonnet-4-6", ONE_MILLION_TOKENS, 0, ONE_MILLION_TOKENS, ONE_MILLION_TOKENS, false));
        assertThat(row.getInputCost()).isEqualByComparingTo("7.05");
    }

    @Test
    void cacheTokens_stackWithBatchDiscount() {
        // Batch halves everything: read = 3 × 0.1 × 0.5 = $0.15; write = 3 × 1.25 × 0.5 = $1.875.
        AiCallLog readRow = svc.recordCall(cacheRow("claude-sonnet-4-6", 0, 0, ONE_MILLION_TOKENS, 0, true));
        assertThat(readRow.getInputCost()).isEqualByComparingTo("0.15");

        AiCallLog writeRow = svc.recordCall(cacheRow("claude-sonnet-4-6", 0, 0, 0, ONE_MILLION_TOKENS, true));
        assertThat(writeRow.getInputCost()).isEqualByComparingTo("1.875");
    }

    @Test
    void cacheTokens_stackWithVertexRegionalPremium() {
        AiCostService regional = new AiCostService(repo, "us-east5");
        // Regional +10%: read = 3 × 0.1 × 1.1 = $0.33; write = 3 × 1.25 × 1.1 = $4.125.
        AiCallLog readRow = regional.recordCall(vertexCacheRow("claude-sonnet-4-6", ONE_MILLION_TOKENS, 0));
        assertThat(readRow.getInputCost()).isEqualByComparingTo("0.33");

        AiCallLog writeRow = regional.recordCall(vertexCacheRow("claude-sonnet-4-6", 0, ONE_MILLION_TOKENS));
        assertThat(writeRow.getInputCost()).isEqualByComparingTo("4.125");
    }

    @Test
    void zeroCacheTokens_doNotChangeInputCost() {
        // The legacy no-cache path must be unaffected: pure $3 input.
        AiCallLog row = svc.recordCall(cacheRow("claude-sonnet-4-6", ONE_MILLION_TOKENS, 0, 0, 0, false));
        assertThat(row.getInputCost()).isEqualByComparingTo("3.00");
    }

    /** Row with explicit cache read/write token counts. */
    private AiCallLog cacheRow(String model, long inputTokens, long outputTokens,
                               long cacheReadTokens, long cacheWriteTokens, boolean isBatch) {
        return AiCallLog.builder()
                .callType("test").provider("test").modelName(model)
                .inputTokens(inputTokens).outputTokens(outputTokens).thinkingTokens(0L)
                .cacheReadTokens(cacheReadTokens).cacheWriteTokens(cacheWriteTokens)
                .isBatch(isBatch).build();
    }

    /** Vertex-anthropic row with cache tokens (carries the regional premium). */
    private AiCallLog vertexCacheRow(String model, long cacheReadTokens, long cacheWriteTokens) {
        return AiCallLog.builder()
                .callType("test").provider(com.afterduty.service.llm.VertexAnthropicProvider.NAME)
                .modelName(model)
                .inputTokens(0L).outputTokens(0L).thinkingTokens(0L)
                .cacheReadTokens(cacheReadTokens).cacheWriteTokens(cacheWriteTokens)
                .isBatch(false).build();
    }

    private AiCallLog vertexAnthropicRow(String model) {
        return AiCallLog.builder()
                .callType("test")
                .provider(com.afterduty.service.llm.VertexAnthropicProvider.NAME)
                .modelName(model)
                .inputTokens(ONE_MILLION_TOKENS)
                .outputTokens(ONE_MILLION_TOKENS)
                .thinkingTokens(0L)
                .isBatch(false)
                .build();
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private void assertModelPricing(String model, String expectedInputPerMTok, String expectedOutputPerMTok) {
        AiCallLog row = svc.recordCall(row(model, ONE_MILLION_TOKENS, ONE_MILLION_TOKENS, 0, false));
        assertThat(row.getInputCost())
                .as("input $/MTok for %s", model)
                .isEqualByComparingTo(expectedInputPerMTok);
        assertThat(row.getOutputCost())
                .as("output $/MTok for %s", model)
                .isEqualByComparingTo(expectedOutputPerMTok);
    }

    private AiCallLog row(String model, long inputTokens, long outputTokens, long thinkingTokens, boolean isBatch) {
        return AiCallLog.builder()
                .callType("test")
                .provider("test")
                .modelName(model)
                .inputTokens(inputTokens)
                .outputTokens(outputTokens)
                .thinkingTokens(thinkingTokens)
                .isBatch(isBatch)
                .build();
    }
}
