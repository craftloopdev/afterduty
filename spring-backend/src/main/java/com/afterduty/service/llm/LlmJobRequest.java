package com.afterduty.service.llm;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Provider-agnostic request shape. Pipeline code builds one of these and
 * hands it to {@link LlmJobService#submit(LlmJobRequest)}; the chosen
 * provider translates it into its own request format.
 *
 * The {@code purpose} string is used for routing (which provider/model)
 * and for accounting (groups together all jobs of the same type).
 */
public final class LlmJobRequest {

    private final String purpose;
    private final String systemPrompt;
    private final String userMessage;
    private final List<Map<String, Object>> messages; // optional multi-turn — null for single-turn
    private final List<Map<String, Object>> tools;     // optional tool defs
    // Optional multimodal attachments sent alongside userMessage as provider
    // inline-data parts (Gemini inlineData {mimeType, data}). Each map is
    // {"mimeType": "application/pdf", "data": "<base64>"}. Null for text-only
    // requests — the legacy text path is byte-identical when this is null.
    private final List<Map<String, Object>> inlineData;
    // Optional provider structured-output schema (Gemini responseSchema). When
    // set, the provider also forces responseMimeType=application/json so the
    // model returns a single schema-conformant JSON object. Null = free-form text.
    private final Map<String, Object> responseSchema;
    // Optional cache-aware structured prompt (Increment 6 / Mission 6a). When set,
    // the Claude providers render the system as the Messages-API array form:
    //   [stable systemBlocks...] + [cachedCorpus with one cache_control breakpoint]
    // and the volatile user tail rides messages/userMessage as before. NULL = the
    // legacy flat systemPrompt path, byte-identical to today's requests. Gemini
    // ignores the cache breakpoint and concatenates the blocks into the prompt.
    private final StructuredPrompt structuredPrompt;
    private final int maxTokens;
    private final int thinkingBudget;
    private final String preferredProvider;            // optional override; null = router decides
    private final String preferredModel;               // optional override; null = router decides
    private final String batchGroupKey;                // optional grouping for batch submission
    private final Long claimId;
    private final Long userId;
    private final Long conditionId;
    private final Long evidenceId;

    private LlmJobRequest(Builder b) {
        this.purpose = b.purpose;
        this.systemPrompt = b.systemPrompt;
        this.userMessage = b.userMessage;
        this.messages = b.messages;
        this.tools = b.tools;
        this.inlineData = b.inlineData;
        this.responseSchema = b.responseSchema;
        this.structuredPrompt = b.structuredPrompt;
        this.maxTokens = b.maxTokens;
        this.thinkingBudget = b.thinkingBudget;
        this.preferredProvider = b.preferredProvider;
        this.preferredModel = b.preferredModel;
        this.batchGroupKey = b.batchGroupKey;
        this.claimId = b.claimId;
        this.userId = b.userId;
        this.conditionId = b.conditionId;
        this.evidenceId = b.evidenceId;
    }

    public String getPurpose() { return purpose; }
    public String getSystemPrompt() { return systemPrompt; }
    public String getUserMessage() { return userMessage; }
    public List<Map<String, Object>> getMessages() { return messages; }
    public List<Map<String, Object>> getTools() { return tools; }
    public List<Map<String, Object>> getInlineData() { return inlineData; }
    public Map<String, Object> getResponseSchema() { return responseSchema; }
    public StructuredPrompt getStructuredPrompt() { return structuredPrompt; }
    public int getMaxTokens() { return maxTokens; }
    public int getThinkingBudget() { return thinkingBudget; }
    public String getPreferredProvider() { return preferredProvider; }
    public String getPreferredModel() { return preferredModel; }
    public String getBatchGroupKey() { return batchGroupKey; }
    public Long getClaimId() { return claimId; }
    public Long getUserId() { return userId; }
    public Long getConditionId() { return conditionId; }
    public Long getEvidenceId() { return evidenceId; }

    /** Returns the messages array as the provider should send it (single-turn synthesised if needed). */
    public List<Map<String, Object>> resolveMessages() {
        if (messages != null) return messages;
        if (userMessage == null) return Collections.emptyList();
        Map<String, Object> turn = new LinkedHashMap<>();
        turn.put("role", "user");
        turn.put("content", userMessage);
        return List.of(turn);
    }

    public static Builder builder() { return new Builder(); }

    public static final class Builder {
        private String purpose;
        private String systemPrompt;
        private String userMessage;
        private List<Map<String, Object>> messages;
        private List<Map<String, Object>> tools;
        private List<Map<String, Object>> inlineData;
        private Map<String, Object> responseSchema;
        private StructuredPrompt structuredPrompt;
        private int maxTokens = 16_000;
        private int thinkingBudget = 0;
        private String preferredProvider;
        private String preferredModel;
        private String batchGroupKey;
        private Long claimId;
        private Long userId;
        private Long conditionId;
        private Long evidenceId;

        public Builder purpose(String v) { this.purpose = v; return this; }
        public Builder systemPrompt(String v) { this.systemPrompt = v; return this; }
        public Builder userMessage(String v) { this.userMessage = v; return this; }
        public Builder messages(List<Map<String, Object>> v) { this.messages = v; return this; }
        public Builder tools(List<Map<String, Object>> v) { this.tools = v; return this; }
        public Builder inlineData(List<Map<String, Object>> v) { this.inlineData = v; return this; }
        public Builder responseSchema(Map<String, Object> v) { this.responseSchema = v; return this; }
        public Builder structuredPrompt(StructuredPrompt v) { this.structuredPrompt = v; return this; }
        public Builder maxTokens(int v) { this.maxTokens = v; return this; }
        public Builder thinkingBudget(int v) { this.thinkingBudget = v; return this; }
        public Builder preferredProvider(String v) { this.preferredProvider = v; return this; }
        public Builder preferredModel(String v) { this.preferredModel = v; return this; }
        public Builder batchGroupKey(String v) { this.batchGroupKey = v; return this; }
        public Builder claimId(Long v) { this.claimId = v; return this; }
        public Builder userId(Long v) { this.userId = v; return this; }
        public Builder conditionId(Long v) { this.conditionId = v; return this; }
        public Builder evidenceId(Long v) { this.evidenceId = v; return this; }

        public LlmJobRequest build() {
            if (purpose == null || purpose.isBlank()) {
                throw new IllegalArgumentException("LlmJobRequest.purpose is required");
            }
            if (messages == null && userMessage == null) {
                throw new IllegalArgumentException("LlmJobRequest needs either messages or userMessage");
            }
            return new LlmJobRequest(this);
        }
    }

    /**
     * Cache-aware structured prompt: stable {@code systemBlocks} followed by an
     * optional {@code cachedCorpus} block that carries the single
     * {@code cache_control {type:"ephemeral"}} breakpoint (Increment 6 — the
     * 0.1×-on-read atom-corpus / KB-rubric lever). The volatile per-request tail
     * (the question / per-condition payload) stays on {@code messages}/
     * {@code userMessage} so it lands <em>after</em> the breakpoint and never
     * invalidates the cached prefix.
     *
     * <p>This carries the <em>intent</em>; the providers do the rendering. The
     * Claude providers emit the Messages-API {@code system} array form with the
     * breakpoint on the corpus block; Gemini concatenates the blocks into the
     * prompt and ignores the breakpoint (it caches implicitly server-side).
     *
     * <p>Cache-key hygiene is the caller's job: keep {@code systemBlocks} and
     * {@code cachedCorpus} free of timestamps/UUIDs and deterministically
     * ordered so the rendered prefix bytes are stable across requests.
     */
    public static final class StructuredPrompt {
        private final List<String> systemBlocks;
        private final String cachedCorpus;

        public StructuredPrompt(List<String> systemBlocks, String cachedCorpus) {
            this.systemBlocks = systemBlocks == null ? List.of() : List.copyOf(systemBlocks);
            this.cachedCorpus = cachedCorpus;
        }

        /** Stable, non-cached system text blocks (rendered before the corpus). */
        public List<String> getSystemBlocks() { return systemBlocks; }

        /**
         * The single cache-breakpoint block (typically the atom corpus or KB
         * rubric). Null/blank → no breakpoint is emitted (structured shape with
         * stable system blocks only).
         */
        public String getCachedCorpus() { return cachedCorpus; }

        /** True when a corpus block exists to carry the cache_control breakpoint. */
        public boolean hasCachedCorpus() {
            return cachedCorpus != null && !cachedCorpus.isBlank();
        }
    }
}
