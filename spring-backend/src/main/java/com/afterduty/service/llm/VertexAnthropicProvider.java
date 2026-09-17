package com.afterduty.service.llm;

/**
 * Marker interface for the Claude-on-Vertex-AI <em>realtime</em> async provider.
 *
 * <p>Unlike {@link AnthropicBatchProvider} (direct {@code api.anthropic.com} Message Batches,
 * 50% off, ≤24h SLA), this provider talks to Claude through the <b>Vertex AI global endpoint</b>
 * using ADC (the same credential chain {@link VertexGeminiAsyncProvider} uses) — no
 * {@code ANTHROPIC_API_KEY}, billing/IAM stay in GCP. It runs jobs at list price, in real time,
 * so {@link #isBatch()} stays {@code false}.
 *
 * <p>Extracting this as an interface (rather than referencing the concrete class) keeps the same
 * abstraction discipline as the other two providers: orchestrators never reference it; they route
 * through {@link LlmJobService} → {@link LlmProviderRouter}. The production implementation is
 * {@link VertexAnthropicProviderImpl}.
 */
public interface VertexAnthropicProvider extends LlmAsyncProvider {

    /** Stable provider name used by {@link LlmProviderRouter}'s purpose-defaults table. */
    String NAME = "vertex-anthropic";

    /** Realtime Vertex calls bill at list price — not the 50% batch lane. */
    @Override
    default boolean isBatch() {
        return false;
    }
}
