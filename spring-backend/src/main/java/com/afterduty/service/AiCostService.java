package com.afterduty.service;

import com.afterduty.model.AiCallLog;
import com.afterduty.repository.AiCallLogRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;

@Service
public class AiCostService {

    private static final Logger log = LoggerFactory.getLogger(AiCostService.class);

    private final AiCallLogRepository repository;

    // Pricing per 1M tokens (list prices verified June 2026 — see
    // docs/architecture/2026-06-10-agentic-analysis-design.md, Part 1/2).
    // Thinking tokens bill at the output rate.
    private static final Map<String, BigDecimal[]> PRICING = Map.ofEntries(
            // [input_per_1M, output_per_1M, thinking_per_1M]
            Map.entry("gemini-3.1-pro-preview", new BigDecimal[]{bd("2.00"), bd("12.00"), bd("12.00")}),
            Map.entry("gemini-3-flash-preview", new BigDecimal[]{bd("0.50"), bd("3.00"), bd("3.00")}),
            Map.entry("gemini-3.1-flash-lite", new BigDecimal[]{bd("0.25"), bd("1.50"), bd("1.50")}),
            Map.entry("gemini-2.5-pro", new BigDecimal[]{bd("1.25"), bd("10.00"), bd("10.00")}),
            Map.entry("gemini-2.5-flash", new BigDecimal[]{bd("0.30"), bd("2.50"), bd("2.50")}),
            Map.entry("claude-fable-5", new BigDecimal[]{bd("10.00"), bd("50.00"), bd("50.00")}),
            Map.entry("claude-opus-5", new BigDecimal[]{bd("5.00"), bd("25.00"), bd("25.00")}),
            Map.entry("claude-opus-4-8", new BigDecimal[]{bd("5.00"), bd("25.00"), bd("25.00")}),
            Map.entry("claude-opus-4-7", new BigDecimal[]{bd("5.00"), bd("25.00"), bd("25.00")}),
            Map.entry("claude-opus-4-6", new BigDecimal[]{bd("5.00"), bd("25.00"), bd("25.00")}),
            // Sonnet 5 list price ($2/$10 intro through 2026-08-31 not modeled —
            // the ledger books the conservative post-intro rate).
            Map.entry("claude-sonnet-5", new BigDecimal[]{bd("3.00"), bd("15.00"), bd("15.00")}),
            Map.entry("claude-sonnet-4-6", new BigDecimal[]{bd("3.00"), bd("15.00"), bd("15.00")}),
            Map.entry("claude-haiku-4-5", new BigDecimal[]{bd("1.00"), bd("5.00"), bd("5.00")}),
            // Increment 7 — RAG embeddings. gemini-embedding-001 bills input-only;
            // there are no output/thinking tokens (research-gcp §1.5). Embedding spend
            // is a rounding error, but the ledger stays honest (Increment 0 ethos).
            Map.entry("gemini-embedding-001", new BigDecimal[]{bd("0.15"), bd("0.00"), bd("0.00")})
    );

    /** Batch lanes (e.g. Anthropic Message Batches) run at 50% of list price. */
    private static final BigDecimal BATCH_DISCOUNT = new BigDecimal("0.5");

    /**
     * Prompt-cache multipliers on the <em>input</em> rate (research §5; verified
     * against platform.claude.com/docs prompt-caching):
     * <ul>
     *   <li><b>read = 0.1×</b> — cached-prefix reads bill at one tenth of input.</li>
     *   <li><b>write = 1.25×</b> — the 5-minute-TTL cache write premium. (The 1-hour
     *       TTL write is 2×; we cache at 5-min TTL on these lanes, so 1.25×.)</li>
     * </ul>
     * Anthropic reports cache read/write counts SEPARATELY from {@code input_tokens}
     * (which EXCLUDES them), so the three input-side costs are additive, never
     * double-counted. Both multipliers STACK with the batch 0.5× and Vertex-regional
     * 1.1× lane multipliers (the batch discount applies to cache tokens too).
     */
    private static final BigDecimal CACHE_READ_MULTIPLIER = new BigDecimal("0.1");
    private static final BigDecimal CACHE_WRITE_MULTIPLIER = new BigDecimal("1.25");

    /**
     * Claude on Vertex regional/multi-region endpoints bills +10% over the global
     * endpoint (4.5-generation and newer). We run the `us` multi-region for
     * HIPAA-minded data residency (2026-08-01 decision; formerly us-east5), so
     * vertex-anthropic calls carry this premium; the global endpoint and the
     * direct-API anthropic-batch lane do not.
     */
    private static final BigDecimal REGIONAL_PREMIUM = new BigDecimal("1.10");

    private static final BigDecimal ONE_MILLION = new BigDecimal("1000000");

    /** Mirrors va-claim.vertex.claude-region so the ledger prices the lane actually used. */
    private final String claudeRegion;

    public AiCostService(AiCallLogRepository repository) {
        this(repository, "us");
    }

    @org.springframework.beans.factory.annotation.Autowired
    public AiCostService(AiCallLogRepository repository,
                         @org.springframework.beans.factory.annotation.Value("${va-claim.vertex.claude-region:us}") String claudeRegion) {
        this.repository = repository;
        this.claudeRegion = claudeRegion;
    }

    public AiCallLog recordCall(AiCallLog callLog) {
        computeCosts(callLog);

        callLog = repository.save(callLog);

        log.info("AI call logged: provider={}, model={}, type={}, tokens={}/{}/{}, cost=${}, latency={}ms",
                callLog.getProvider(), callLog.getModelName(), callLog.getCallType(),
                callLog.getInputTokens(), callLog.getOutputTokens(), callLog.getThinkingTokens(),
                callLog.getTotalCost(), callLog.getLatencyMs());

        // Alert if daily cost exceeds threshold
        checkCostAlerts(callLog);

        return callLog;
    }

    public AiCallLog recordError(AiCallLog callLog, String errorMessage) {
        callLog.setStatus("error");
        callLog.setErrorMessage(errorMessage);
        computeCosts(callLog);
        return repository.save(callLog);
    }

    private void computeCosts(AiCallLog callLog) {
        BigDecimal[] rates = PRICING.getOrDefault(callLog.getModelName(),
                new BigDecimal[]{bd("5.00"), bd("15.00"), bd("15.00")});

        // Calls that ran through a batch lane bill at half the list rate.
        BigDecimal laneMultiplier = Boolean.TRUE.equals(callLog.getIsBatch())
                ? BATCH_DISCOUNT
                : BigDecimal.ONE;

        // Claude on a Vertex regional endpoint (data residency) bills +10% over global.
        if (com.afterduty.service.llm.VertexAnthropicProvider.NAME.equals(callLog.getProvider())
                && !"global".equals(claudeRegion)) {
            laneMultiplier = laneMultiplier.multiply(REGIONAL_PREMIUM);
        }

        // Regular (uncached) input tokens at the (lane-adjusted) list input rate.
        BigDecimal regularInputCost = computeTokenCost(callLog.getInputTokens(), rates[0].multiply(laneMultiplier));

        // Prompt-cache costs ride the INPUT rate, scaled by the cache multipliers,
        // and STACK with the same lane multiplier (batch 0.5× / regional 1.1×):
        //   read  = input_rate × 0.1  × lane
        //   write = input_rate × 1.25 × lane
        // Anthropic's input_tokens already EXCLUDE these, so summing is additive,
        // never double-counted. Folded into input_cost so the column + total still
        // reflect the full input-side spend with no schema/contract change.
        BigDecimal cacheReadCost = computeTokenCost(callLog.getCacheReadTokens(),
                rates[0].multiply(CACHE_READ_MULTIPLIER).multiply(laneMultiplier));
        BigDecimal cacheWriteCost = computeTokenCost(callLog.getCacheWriteTokens(),
                rates[0].multiply(CACHE_WRITE_MULTIPLIER).multiply(laneMultiplier));

        BigDecimal inputCost = regularInputCost.add(cacheReadCost).add(cacheWriteCost);
        BigDecimal outputCost = computeTokenCost(callLog.getOutputTokens(), rates[1].multiply(laneMultiplier));
        BigDecimal thinkingCost = computeTokenCost(callLog.getThinkingTokens(), rates[2].multiply(laneMultiplier));

        callLog.setInputCost(inputCost);
        callLog.setOutputCost(outputCost);
        callLog.setThinkingCost(thinkingCost);
        callLog.setTotalCost(inputCost.add(outputCost).add(thinkingCost));
    }

    private BigDecimal computeTokenCost(Long tokens, BigDecimal ratePerMillion) {
        if (tokens == null || tokens == 0) return BigDecimal.ZERO;
        return new BigDecimal(tokens)
                .multiply(ratePerMillion)
                .divide(ONE_MILLION, 8, RoundingMode.HALF_UP);
    }

    private void checkCostAlerts(AiCallLog callLog) {
        BigDecimal dailyCost = repository.totalCostSince(Instant.now().minus(1, ChronoUnit.DAYS));
        if (dailyCost.compareTo(bd("5.00")) > 0) {
            log.warn("COST ALERT: Daily AI spend is ${} (threshold: $5.00)", dailyCost);
        }
        if (dailyCost.compareTo(bd("20.00")) > 0) {
            log.error("COST ALERT CRITICAL: Daily AI spend is ${} (threshold: $20.00)", dailyCost);
        }
    }

    private static BigDecimal bd(String val) {
        return new BigDecimal(val);
    }
}
