package com.afterduty.service.llm;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Provider-agnostic result. {@code text} is the assembled assistant reply
 * (concatenated text content blocks). {@code raw} is the parsed provider
 * response — callers that need tool-use blocks or stop_reason can dig in.
 */
public final class LlmJobResult {

    private final String text;
    private final JsonNode raw;
    private final long inputTokens;
    private final long outputTokens;
    private final long thinkingTokens;
    // Anthropic prompt-cache usage (Increment 6 / Mission 6a). 0 for providers
    // that don't report them (Gemini) and for uncached Claude calls. NOTE: the
    // Anthropic usage block reports cache reads/writes SEPARATELY from
    // input_tokens — input_tokens EXCLUDES the cache-read and cache-write counts —
    // so these are additive, never double-counted (see AiCostService).
    private final long cacheReadTokens;
    private final long cacheWriteTokens;
    private final String provider;
    private final String modelName;

    /** Legacy constructor: no cache usage (preserved for callers/tests pre-Mission-6a). */
    public LlmJobResult(String text, JsonNode raw, long inputTokens, long outputTokens,
                        long thinkingTokens, String provider, String modelName) {
        this(text, raw, inputTokens, outputTokens, thinkingTokens, 0L, 0L, provider, modelName);
    }

    public LlmJobResult(String text, JsonNode raw, long inputTokens, long outputTokens,
                        long thinkingTokens, long cacheReadTokens, long cacheWriteTokens,
                        String provider, String modelName) {
        this.text = text;
        this.raw = raw;
        this.inputTokens = inputTokens;
        this.outputTokens = outputTokens;
        this.thinkingTokens = thinkingTokens;
        this.cacheReadTokens = cacheReadTokens;
        this.cacheWriteTokens = cacheWriteTokens;
        this.provider = provider;
        this.modelName = modelName;
    }

    public String getText() { return text; }
    public JsonNode getRaw() { return raw; }
    public long getInputTokens() { return inputTokens; }
    public long getOutputTokens() { return outputTokens; }
    public long getThinkingTokens() { return thinkingTokens; }
    public long getCacheReadTokens() { return cacheReadTokens; }
    public long getCacheWriteTokens() { return cacheWriteTokens; }
    public String getProvider() { return provider; }
    public String getModelName() { return modelName; }
}
