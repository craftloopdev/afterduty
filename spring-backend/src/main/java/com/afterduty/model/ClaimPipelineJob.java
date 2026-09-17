package com.afterduty.model;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

/**
 * Join row connecting a claim's current pipeline stage to the LlmJobs that
 * stage spawned. A fan-out stage (e.g. RATING with one job per condition)
 * writes N rows here, all sharing the same {@code stage}; the state machine
 * advances when every row's job is SUCCEEDED.
 */
@Entity
@Table(name = "claim_pipeline_jobs", indexes = {
        @Index(name = "idx_cpj_claim_stage", columnList = "claim_id,stage"),
        @Index(name = "idx_cpj_llm_job_id", columnList = "llm_job_id")
})
public class ClaimPipelineJob {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "claim_id", nullable = false)
    private Long claimId;

    /** Pipeline stage label, e.g. "synthesis_rating", "gap_evidence". Must match an enum value in the relevant state machine. */
    @Column(nullable = false, length = 64)
    private String stage;

    @Column(name = "llm_job_id", nullable = false, columnDefinition = "uuid")
    private UUID llmJobId;

    @Column(name = "condition_id")
    private Long conditionId;

    @Column(name = "evidence_id")
    private Long evidenceId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    public ClaimPipelineJob() {
    }

    public ClaimPipelineJob(Long claimId, String stage, UUID llmJobId, Long conditionId, Long evidenceId) {
        this.claimId = claimId;
        this.stage = stage;
        this.llmJobId = llmJobId;
        this.conditionId = conditionId;
        this.evidenceId = evidenceId;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getClaimId() { return claimId; }
    public void setClaimId(Long claimId) { this.claimId = claimId; }
    public String getStage() { return stage; }
    public void setStage(String stage) { this.stage = stage; }
    public UUID getLlmJobId() { return llmJobId; }
    public void setLlmJobId(UUID llmJobId) { this.llmJobId = llmJobId; }
    public Long getConditionId() { return conditionId; }
    public void setConditionId(Long conditionId) { this.conditionId = conditionId; }
    public Long getEvidenceId() { return evidenceId; }
    public void setEvidenceId(Long evidenceId) { this.evidenceId = evidenceId; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
