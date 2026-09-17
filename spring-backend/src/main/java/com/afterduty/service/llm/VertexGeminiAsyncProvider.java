package com.afterduty.service.llm;

/**
 * Marker interface for the Vertex AI Gemini streaming async provider.
 *
 * <p>Extracting this as an interface (rather than a concrete class) allows tests to assert
 * {@code fake instanceof VertexGeminiAsyncProvider == false} at compile time without a Java
 * "incompatible types" error. The production implementation is {@link VertexGeminiAsyncProviderImpl}.
 *
 * <p>Orchestrators must never reference this interface or its implementation directly.
 * They route requests through {@link LlmJobService}, which resolves the provider via
 * {@link LlmProviderRouter}.
 */
public interface VertexGeminiAsyncProvider extends LlmAsyncProvider {

    /** Stable provider name used by {@link LlmProviderRouter}'s purpose-defaults table. */
    String NAME = "vertex-gemini";
}
