package com.afterduty.model;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.Objects;

/**
 * A PERSISTED LLM adjudication of a genuine service-history conflict (Service
 * History P3 Part B, design 2026-07-05). When the deterministic reconciler finds
 * two sources of EQUAL authority with contradictory dates (or an ambiguous merge)
 * it flags a conflict; a PIPELINE-TIME step (never read time) asks the LLM which
 * value is right and why, and persists the answer here keyed by
 * {@code (userId, clusterKey)}. The reconciler then reads this resolution at READ
 * time (fast, no LLM) and applies it BELOW a veteran override but ABOVE the raw
 * deterministic pick.
 *
 * <p>Kept deliberately narrow: one resolved field ({@code start} | {@code end})
 * with its adjudicated value + human-readable reasoning. That is the only genuine
 * equal-authority contradiction the reconciler surfaces (dates). Overwrite-by-key
 * semantics — one resolution per {@code (userId, clusterKey)} — so a re-run simply
 * replaces it, and stale resolutions are cleared when the cluster's evidence
 * fingerprint changes (see {@code evidenceFingerprint}).
 *
 * <p>Plain columns, {@code ddl-auto} adds the table (no migration framework),
 * matching {@code ServiceProfile} / {@code IdentifiedCondition}.
 */
@Entity
@Table(name = "service_history_resolutions",
        uniqueConstraints = @UniqueConstraint(name = "uq_svc_resolution_user_cluster",
                columnNames = {"user_id", "cluster_key"}))
public class ServiceHistoryResolution {

    /** Resolvable field name — the conflict predicate only produces date conflicts. */
    public static final String FIELD_START = "start";
    public static final String FIELD_END = "end";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "cluster_key", nullable = false)
    private String clusterKey;

    /** Which field the LLM adjudicated ({@code "start"} | {@code "end"}). */
    @Column(name = "resolved_field", nullable = false)
    private String resolvedField;

    /** The value the LLM chose for {@link #resolvedField} (ISO date string). */
    @Column(name = "resolved_value", nullable = false)
    private String resolvedValue;

    @Column(name = "reasoning", columnDefinition = "text")
    private String reasoning;

    /**
     * Fingerprint of the cluster's contributing sources at adjudication time. When
     * the reconciler re-derives and the cluster's current fingerprint differs, the
     * resolution is STALE (the evidence changed) and is ignored — the pipeline
     * re-adjudicates and overwrites. Keeps a persisted LLM answer from lingering
     * after the documents behind it moved.
     */
    @Column(name = "evidence_fingerprint")
    private String evidenceFingerprint;

    @Column(name = "created_at")
    private Instant createdAt;

    public ServiceHistoryResolution() {
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public String getClusterKey() {
        return clusterKey;
    }

    public void setClusterKey(String clusterKey) {
        this.clusterKey = clusterKey;
    }

    public String getResolvedField() {
        return resolvedField;
    }

    public void setResolvedField(String resolvedField) {
        this.resolvedField = resolvedField;
    }

    public String getResolvedValue() {
        return resolvedValue;
    }

    public void setResolvedValue(String resolvedValue) {
        this.resolvedValue = resolvedValue;
    }

    public String getReasoning() {
        return reasoning;
    }

    public void setReasoning(String reasoning) {
        this.reasoning = reasoning;
    }

    public String getEvidenceFingerprint() {
        return evidenceFingerprint;
    }

    public void setEvidenceFingerprint(String evidenceFingerprint) {
        this.evidenceFingerprint = evidenceFingerprint;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ServiceHistoryResolution that = (ServiceHistoryResolution) o;
        return Objects.equals(id, that.id) &&
                Objects.equals(userId, that.userId) &&
                Objects.equals(clusterKey, that.clusterKey) &&
                Objects.equals(resolvedField, that.resolvedField) &&
                Objects.equals(resolvedValue, that.resolvedValue) &&
                Objects.equals(reasoning, that.reasoning) &&
                Objects.equals(evidenceFingerprint, that.evidenceFingerprint);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, userId, clusterKey, resolvedField, resolvedValue, reasoning,
                evidenceFingerprint);
    }

    @Override
    public String toString() {
        return "ServiceHistoryResolution(" +
                "id=" + id +
                ", userId=" + userId +
                ", clusterKey=" + clusterKey +
                ", resolvedField=" + resolvedField +
                ", resolvedValue=" + resolvedValue +
                ", evidenceFingerprint=" + evidenceFingerprint +
                ')';
    }
}
