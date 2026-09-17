package com.afterduty.model;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.Objects;

/**
 * A veteran's correction to ONE reconciled service-history conclusion (Service
 * History P3, design 2026-07-05). The reconciler derives conclusions on read, so
 * a correction can't be a mutation on a conclusion row — there is none. Instead
 * we persist the override keyed by {@code (userId, clusterKey)}, where
 * {@code clusterKey} is the reconciler's STABLE cluster identity, and the
 * reconciler re-applies it at TOP authority after re-deriving. Because the key is
 * stable across re-derives for the same real enlistment, the correction survives
 * new uploads / re-extraction and re-attaches to the right conclusion.
 *
 * <p>Only the set fields override — every field is nullable, and a null field
 * means "leave the reconciled value". This lets a veteran fix just the end date
 * without clobbering the branch the documents got right.
 *
 * <p>SECURITY: {@code userId} is the resolved authenticated principal (never
 * client data). {@code clusterKey} is data. The upsert/read paths filter on
 * {@code userId} so one account can never touch another's overrides.
 *
 * <p>Plain columns, {@code ddl-auto} adds the table (no migration framework),
 * matching {@code ServiceProfile} / {@code IdentifiedCondition}. A unique
 * constraint on {@code (user_id, cluster_key)} enforces one override per
 * enlistment per user (the repo upserts into it).
 */
@Entity
@Table(name = "service_history_overrides",
        uniqueConstraints = @UniqueConstraint(name = "uq_svc_override_user_cluster",
                columnNames = {"user_id", "cluster_key"}))
public class ServiceHistoryOverride {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "cluster_key", nullable = false)
    private String clusterKey;

    // Overridden fields — only the non-null ones win over the documents.
    private String branch;

    private String component;

    @Column(name = "start_date")
    private String startDate;

    @Column(name = "end_date")
    private String endDate;

    private String mos;

    private String rank;

    @Column(name = "created_at")
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    public ServiceHistoryOverride() {
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

    public String getBranch() {
        return branch;
    }

    public void setBranch(String branch) {
        this.branch = branch;
    }

    public String getComponent() {
        return component;
    }

    public void setComponent(String component) {
        this.component = component;
    }

    public String getStartDate() {
        return startDate;
    }

    public void setStartDate(String startDate) {
        this.startDate = startDate;
    }

    public String getEndDate() {
        return endDate;
    }

    public void setEndDate(String endDate) {
        this.endDate = endDate;
    }

    public String getMos() {
        return mos;
    }

    public void setMos(String mos) {
        this.mos = mos;
    }

    public String getRank() {
        return rank;
    }

    public void setRank(String rank) {
        this.rank = rank;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }

    /** True when at least one overridable field is set — an empty override
     *  contributes nothing and should be treated as a clear, not an upsert. */
    public boolean hasAnyField() {
        return branch != null || component != null || startDate != null
                || endDate != null || mos != null || rank != null;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ServiceHistoryOverride that = (ServiceHistoryOverride) o;
        return Objects.equals(id, that.id) &&
                Objects.equals(userId, that.userId) &&
                Objects.equals(clusterKey, that.clusterKey) &&
                Objects.equals(branch, that.branch) &&
                Objects.equals(component, that.component) &&
                Objects.equals(startDate, that.startDate) &&
                Objects.equals(endDate, that.endDate) &&
                Objects.equals(mos, that.mos) &&
                Objects.equals(rank, that.rank);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, userId, clusterKey, branch, component, startDate, endDate, mos, rank);
    }

    @Override
    public String toString() {
        return "ServiceHistoryOverride(" +
                "id=" + id +
                ", userId=" + userId +
                ", clusterKey=" + clusterKey +
                ", branch=" + branch +
                ", component=" + component +
                ", startDate=" + startDate +
                ", endDate=" + endDate +
                ", mos=" + mos +
                ", rank=" + rank +
                ')';
    }
}
