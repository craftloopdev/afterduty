package com.afterduty.model;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

/**
 * Durable record of one async LLM call. The orchestrators submit a job, get
 * back this row's id, and later read the response when status = SUCCEEDED.
 *
 * Provider field decouples the abstraction from any one vendor — adding a new
 * provider means a new {@code provider} value plus an LlmAsyncProvider impl;
 * the rest of the pipeline is unchanged.
 */
@Entity
@Table(name = "llm_jobs", indexes = {
        @Index(name = "idx_llm_jobs_status", columnList = "status"),
        @Index(name = "idx_llm_jobs_provider_status", columnList = "provider,status"),
        @Index(name = "idx_llm_jobs_provider_job_id", columnList = "provider_job_id"),
        @Index(name = "idx_llm_jobs_claim_id", columnList = "claim_id"),
        @Index(name = "idx_llm_jobs_batch_group_key", columnList = "batch_group_key")
})
public class LlmJob {

    public enum Status {
        QUEUED,        // persisted, waiting for submitter to push to provider
        SUBMITTED,     // submitter pushed; provider acknowledged
        IN_PROGRESS,   // provider says still running
        SUCCEEDED,     // result fetched and stored in response_payload
        FAILED         // terminal failure; error_message populated
    }

    @Id
    @Column(columnDefinition = "uuid")
    private UUID id;

    @Column(nullable = false, length = 64)
    private String provider;

    @Column(name = "model_name", nullable = false, length = 128)
    private String modelName;

    /** Logical purpose, e.g. "synthesis_identify", "gap_evidence", "extraction_diagnosis". */
    @Column(nullable = false, length = 64)
    private String purpose;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private Status status = Status.QUEUED;

    @Column(name = "claim_id")
    private Long claimId;

    @Column(name = "user_id")
    private Long userId;

    @Column(name = "condition_id")
    private Long conditionId;

    @Column(name = "evidence_id")
    private Long evidenceId;

    /** Full prompt + params; jsonb in postgres, clob elsewhere. Used to re-submit on orphan recovery. */
    @Column(name = "request_payload", columnDefinition = "text", nullable = false)
    private String requestPayload;

    /** Provider-side identifier (Anthropic batch id, internal stream uuid, etc.). */
    @Column(name = "provider_job_id", length = 256)
    private String providerJobId;

    /** Raw provider response when SUCCEEDED. */
    @Column(name = "response_payload", columnDefinition = "text")
    private String responsePayload;

    /** Optional grouping key — jobs with the same key submitted in one provider batch. */
    @Column(name = "batch_group_key", length = 128)
    private String batchGroupKey;

    @Column(name = "error_message", columnDefinition = "text")
    private String errorMessage;

    @Column(nullable = false)
    private int attempts = 0;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "submitted_at")
    private Instant submittedAt;

    @Column(name = "last_polled_at")
    private Instant lastPolledAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    public LlmJob() {
    }

    @PrePersist
    public void prePersist() {
        if (id == null) id = UUID.randomUUID();
        if (createdAt == null) createdAt = Instant.now();
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private final LlmJob job = new LlmJob();

        public Builder id(UUID id) { job.id = id; return this; }
        public Builder provider(String v) { job.provider = v; return this; }
        public Builder modelName(String v) { job.modelName = v; return this; }
        public Builder purpose(String v) { job.purpose = v; return this; }
        public Builder status(Status v) { job.status = v; return this; }
        public Builder claimId(Long v) { job.claimId = v; return this; }
        public Builder userId(Long v) { job.userId = v; return this; }
        public Builder conditionId(Long v) { job.conditionId = v; return this; }
        public Builder evidenceId(Long v) { job.evidenceId = v; return this; }
        public Builder requestPayload(String v) { job.requestPayload = v; return this; }
        public Builder providerJobId(String v) { job.providerJobId = v; return this; }
        public Builder responsePayload(String v) { job.responsePayload = v; return this; }
        public Builder batchGroupKey(String v) { job.batchGroupKey = v; return this; }
        public Builder errorMessage(String v) { job.errorMessage = v; return this; }
        public Builder attempts(int v) { job.attempts = v; return this; }
        public Builder createdAt(Instant v) { job.createdAt = v; return this; }
        public Builder submittedAt(Instant v) { job.submittedAt = v; return this; }
        public Builder lastPolledAt(Instant v) { job.lastPolledAt = v; return this; }
        public Builder completedAt(Instant v) { job.completedAt = v; return this; }
        public LlmJob build() { return job; }
    }

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }
    public String getModelName() { return modelName; }
    public void setModelName(String modelName) { this.modelName = modelName; }
    public String getPurpose() { return purpose; }
    public void setPurpose(String purpose) { this.purpose = purpose; }
    public Status getStatus() { return status; }
    public void setStatus(Status status) { this.status = status; }
    public Long getClaimId() { return claimId; }
    public void setClaimId(Long claimId) { this.claimId = claimId; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public Long getConditionId() { return conditionId; }
    public void setConditionId(Long conditionId) { this.conditionId = conditionId; }
    public Long getEvidenceId() { return evidenceId; }
    public void setEvidenceId(Long evidenceId) { this.evidenceId = evidenceId; }
    public String getRequestPayload() { return requestPayload; }
    public void setRequestPayload(String requestPayload) { this.requestPayload = requestPayload; }
    public String getProviderJobId() { return providerJobId; }
    public void setProviderJobId(String providerJobId) { this.providerJobId = providerJobId; }
    public String getResponsePayload() { return responsePayload; }
    public void setResponsePayload(String responsePayload) { this.responsePayload = responsePayload; }
    public String getBatchGroupKey() { return batchGroupKey; }
    public void setBatchGroupKey(String batchGroupKey) { this.batchGroupKey = batchGroupKey; }
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
    public int getAttempts() { return attempts; }
    public void setAttempts(int attempts) { this.attempts = attempts; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getSubmittedAt() { return submittedAt; }
    public void setSubmittedAt(Instant submittedAt) { this.submittedAt = submittedAt; }
    public Instant getLastPolledAt() { return lastPolledAt; }
    public void setLastPolledAt(Instant lastPolledAt) { this.lastPolledAt = lastPolledAt; }
    public Instant getCompletedAt() { return completedAt; }
    public void setCompletedAt(Instant completedAt) { this.completedAt = completedAt; }
}
