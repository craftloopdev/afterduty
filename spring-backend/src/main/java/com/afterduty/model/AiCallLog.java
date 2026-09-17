package com.afterduty.model;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "ai_call_logs", indexes = {
        @Index(name = "idx_ai_call_claim_id", columnList = "claim_id"),
        @Index(name = "idx_ai_call_created_at", columnList = "created_at"),
        @Index(name = "idx_ai_call_model", columnList = "model_name")
})
public class AiCallLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "claim_id")
    private Long claimId;

    @Column(name = "evidence_id")
    private Long evidenceId;

    @Column(name = "user_id")
    private Long userId;

    @Column(name = "call_type", nullable = false)
    private String callType; // extraction, synthesis, gap_analysis, chat

    @Column(name = "provider", nullable = false)
    private String provider; // gemini, claude

    @Column(name = "model_name", nullable = false)
    private String modelName;

    @Column(name = "input_tokens")
    private Long inputTokens;

    @Column(name = "output_tokens")
    private Long outputTokens;

    @Column(name = "thinking_tokens")
    private Long thinkingTokens;

    @Column(name = "cache_read_tokens")
    private Long cacheReadTokens;

    @Column(name = "cache_write_tokens")
    private Long cacheWriteTokens;

    @Column(name = "input_cost", precision = 12, scale = 8)
    private BigDecimal inputCost;

    @Column(name = "output_cost", precision = 12, scale = 8)
    private BigDecimal outputCost;

    @Column(name = "thinking_cost", precision = 12, scale = 8)
    private BigDecimal thinkingCost;

    @Column(name = "total_cost", precision = 12, scale = 8)
    private BigDecimal totalCost;

    @Column(name = "latency_ms")
    private Long latencyMs;

    /**
     * True when the call ran through a 50%-off batch lane (e.g. the Anthropic
     * Message Batches API). Drives the batch discount in AiCostService.
     */
    @Column(name = "is_batch")
    private Boolean isBatch = false;

    @Column(name = "status", nullable = false)
    private String status = "success"; // success, error

    @Column(name = "error_message", columnDefinition = "text")
    private String errorMessage;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    /**
     * Groups multiple HTTP round-trips for one logical async LLM operation
     * (submit + poll + retrieve). One LlmJob → potentially multiple AiCallLog
     * rows; queries can SUM across this id to get total cost per logical call.
     */
    @Column(name = "llm_job_id", columnDefinition = "uuid")
    private UUID llmJobId;

    public AiCallLog() {}

    // Getters and setters

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getClaimId() { return claimId; }
    public void setClaimId(Long claimId) { this.claimId = claimId; }

    public Long getEvidenceId() { return evidenceId; }
    public void setEvidenceId(Long evidenceId) { this.evidenceId = evidenceId; }

    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }

    public String getCallType() { return callType; }
    public void setCallType(String callType) { this.callType = callType; }

    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }

    public String getModelName() { return modelName; }
    public void setModelName(String modelName) { this.modelName = modelName; }

    public Long getInputTokens() { return inputTokens; }
    public void setInputTokens(Long inputTokens) { this.inputTokens = inputTokens; }

    public Long getOutputTokens() { return outputTokens; }
    public void setOutputTokens(Long outputTokens) { this.outputTokens = outputTokens; }

    public Long getThinkingTokens() { return thinkingTokens; }
    public void setThinkingTokens(Long thinkingTokens) { this.thinkingTokens = thinkingTokens; }

    public Long getCacheReadTokens() { return cacheReadTokens; }
    public void setCacheReadTokens(Long cacheReadTokens) { this.cacheReadTokens = cacheReadTokens; }

    public Long getCacheWriteTokens() { return cacheWriteTokens; }
    public void setCacheWriteTokens(Long cacheWriteTokens) { this.cacheWriteTokens = cacheWriteTokens; }

    public BigDecimal getInputCost() { return inputCost; }
    public void setInputCost(BigDecimal inputCost) { this.inputCost = inputCost; }

    public BigDecimal getOutputCost() { return outputCost; }
    public void setOutputCost(BigDecimal outputCost) { this.outputCost = outputCost; }

    public BigDecimal getThinkingCost() { return thinkingCost; }
    public void setThinkingCost(BigDecimal thinkingCost) { this.thinkingCost = thinkingCost; }

    public BigDecimal getTotalCost() { return totalCost; }
    public void setTotalCost(BigDecimal totalCost) { this.totalCost = totalCost; }

    public Long getLatencyMs() { return latencyMs; }
    public void setLatencyMs(Long latencyMs) { this.latencyMs = latencyMs; }

    public Boolean getIsBatch() { return isBatch; }
    public void setIsBatch(Boolean isBatch) { this.isBatch = isBatch; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public UUID getLlmJobId() { return llmJobId; }
    public void setLlmJobId(UUID llmJobId) { this.llmJobId = llmJobId; }

    public static AiCallLogBuilder builder() { return new AiCallLogBuilder(); }

    public static class AiCallLogBuilder {
        private final AiCallLog log = new AiCallLog();

        public AiCallLogBuilder claimId(Long v) { log.claimId = v; return this; }
        public AiCallLogBuilder evidenceId(Long v) { log.evidenceId = v; return this; }
        public AiCallLogBuilder userId(Long v) { log.userId = v; return this; }
        public AiCallLogBuilder callType(String v) { log.callType = v; return this; }
        public AiCallLogBuilder provider(String v) { log.provider = v; return this; }
        public AiCallLogBuilder modelName(String v) { log.modelName = v; return this; }
        public AiCallLogBuilder inputTokens(Long v) { log.inputTokens = v; return this; }
        public AiCallLogBuilder outputTokens(Long v) { log.outputTokens = v; return this; }
        public AiCallLogBuilder thinkingTokens(Long v) { log.thinkingTokens = v; return this; }
        public AiCallLogBuilder cacheReadTokens(Long v) { log.cacheReadTokens = v; return this; }
        public AiCallLogBuilder cacheWriteTokens(Long v) { log.cacheWriteTokens = v; return this; }
        public AiCallLogBuilder latencyMs(Long v) { log.latencyMs = v; return this; }
        public AiCallLogBuilder isBatch(Boolean v) { log.isBatch = v; return this; }
        public AiCallLogBuilder status(String v) { log.status = v; return this; }
        public AiCallLogBuilder errorMessage(String v) { log.errorMessage = v; return this; }
        public AiCallLogBuilder llmJobId(UUID v) { log.llmJobId = v; return this; }

        public AiCallLog build() { return log; }
    }
}
