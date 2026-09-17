package com.afterduty.service.llm;

/**
 * Marker interface for the Anthropic Message Batches async provider.
 *
 * <p>Extracting this as an interface (rather than a concrete class) allows tests to assert
 * {@code fake instanceof AnthropicBatchProvider == false} at compile time without a Java
 * "incompatible types" error. The production implementation is {@link AnthropicBatchProviderImpl}.
 *
 * <p>Orchestrators must never reference this interface or its implementation directly.
 * They route requests through {@link LlmJobService}, which resolves the provider via
 * {@link LlmProviderRouter}.
 */
public interface AnthropicBatchProvider extends LlmAsyncProvider {

    /** Stable provider name used by {@link LlmProviderRouter}'s purpose-defaults table. */
    String NAME = "anthropic-batch";

    /** Anthropic Message Batches bill at 50% of list price. */
    @Override
    default boolean isBatch() {
        return true;
    }
}
