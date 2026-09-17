package com.afterduty.model;

import jakarta.persistence.*;

import java.time.Instant;

/**
 * P1-14 — durable record of a chat-initiated condition removal (soft-delete).
 *
 * <p>Before this table, {@code delete_condition} hard-deleted the row: there was no
 * undo, and the next identify run simply re-emitted the condition from the same
 * evidence — deletions resurrected on the next upload. A suppression row is the
 * veteran's durable "this does not apply to me" intent, keyed NOT by condition id
 * (ids churn every generation) but by the condition's stable
 * {@code identity_fingerprint} (see V20260611__condition_generation_fingerprints.sql),
 * mirroring how {@code user_gap_state} keys gap intent.
 *
 * <p>Writers/readers:
 * <ul>
 *   <li>ChatAgent {@code delete_condition} inserts a row and hides the live row
 *       behind {@code SUPPRESSED_MARKER} (see ChatAgent);</li>
 *   <li>{@code ConditionRepository.findByClaimIdAndSupersededByIsNull} — the ONE
 *       active-generation read every user-facing surface uses — post-filters any
 *       condition whose fingerprint has an unlifted suppression, so a re-identified
 *       condition in a later generation never resurfaces;</li>
 *   <li>ChatAgent {@code restore_condition} lifts the row ({@code lifted_at}) — undo.</li>
 * </ul>
 *
 * <p>No LLM is involved anywhere in this filtering — it is a deterministic
 * fingerprint match; the identify prompt is deliberately NOT changed.
 */
@Entity
@Table(name = "condition_suppressions",
        indexes = @Index(name = "idx_condition_suppressions_claim", columnList = "claim_id"))
public class ConditionSuppression {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "claim_id", nullable = false)
    private Long claimId;

    /** The condition row that was suppressed (for undo; ids churn across generations). */
    @Column(name = "condition_id")
    private Long conditionId;

    /**
     * The suppressed condition's stable identity fingerprint — the resurrect-proof
     * match key. Nullable: legacy/flag-off rows have no fingerprint; suppressing one
     * still hides the row itself, it just can't block a later re-identification.
     */
    @Column(name = "identity_fingerprint")
    private String identityFingerprint;

    /** Human-readable snapshot for the audit trail / undo copy. */
    @Column(name = "condition_name", length = 512)
    private String conditionName;

    @Column(columnDefinition = "text")
    private String reason;

    @Column(name = "created_by_user_id")
    private Long createdByUserId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /** Non-null once undone via restore_condition; a lifted row filters nothing. */
    @Column(name = "lifted_at")
    private Instant liftedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getClaimId() { return claimId; }
    public void setClaimId(Long claimId) { this.claimId = claimId; }

    public Long getConditionId() { return conditionId; }
    public void setConditionId(Long conditionId) { this.conditionId = conditionId; }

    public String getIdentityFingerprint() { return identityFingerprint; }
    public void setIdentityFingerprint(String identityFingerprint) { this.identityFingerprint = identityFingerprint; }

    public String getConditionName() { return conditionName; }
    public void setConditionName(String conditionName) { this.conditionName = conditionName; }

    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }

    public Long getCreatedByUserId() { return createdByUserId; }
    public void setCreatedByUserId(Long createdByUserId) { this.createdByUserId = createdByUserId; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getLiftedAt() { return liftedAt; }
    public void setLiftedAt(Instant liftedAt) { this.liftedAt = liftedAt; }
}
