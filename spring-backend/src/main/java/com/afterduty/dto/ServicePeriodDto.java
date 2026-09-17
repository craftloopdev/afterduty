package com.afterduty.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.Objects;

/**
 * One period of military service, surfaced on {@code GET /api/auth/profile} as
 * {@code servicePeriods[]}. Veterans often have several (Active duty, then
 * National Guard or Reserve), so this is a list — one entry per distinct
 * document-level service record derived deterministically from already-extracted
 * atoms ({@code source:"documents"}), plus the manual ServiceProfile row when
 * the veteran filled one ({@code source:"manual"}).
 *
 * <p>Wire contract (pinned — the profile card consumes exactly these):
 * <pre>
 * { "branch": string, "component": "active"|"guard"|"reserve"|null,
 *   "startDate": "YYYY-MM-DD"|null, "endDate": "YYYY-MM-DD"|null,
 *   "mos": string|null, "rank": string|null, "source": "documents"|"manual" }
 * </pre>
 *
 * <p><b>Reconciliation (2026-07-05).</b> When {@code va-claim.service-history.reconcile}
 * is ON (default), {@code ServiceHistoryReconciler} collapses the raw per-document
 * periods into ONE conclusion per real enlistment, and each entry additionally
 * carries {@code sources[]} (the contributing raw records — receipts for the
 * drill-down), {@code reasoning} (deterministic trace of how the fields were
 * resolved), and {@code totalYears} (this conclusion's own span). These three are
 * ADDITIVE + NULLABLE and emitted only when present ({@code NON_NULL}), so the
 * pinned legacy fields above are untouched and existing readers are unaffected.
 * Flag OFF ⇒ today's exact-dedupe behavior and these three stay null/omitted.
 */
// ALWAYS overrides the app-wide non_null jackson default: the pinned wire
// contract types every field as X|null, so null keys must stay on the wire.
@JsonInclude(JsonInclude.Include.ALWAYS)
public class ServicePeriodDto {

    public static final String SOURCE_DOCUMENTS = "documents";
    public static final String SOURCE_MANUAL = "manual";

    public static final String COMPONENT_ACTIVE = "active";
    public static final String COMPONENT_GUARD = "guard";
    public static final String COMPONENT_RESERVE = "reserve";

    private String branch;
    private String component;
    private String startDate;
    private String endDate;
    private String mos;
    private String rank;
    private String source;

    // --- Additive reconciliation fields (2026-07-05). NON_NULL each so they are
    // absent on the wire unless populated, keeping the pinned legacy contract
    // above byte-for-byte for existing readers. ---

    /** Contributing raw records behind this conclusion; null when unreconciled. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private List<ServiceSourceDto> sources;

    /** Deterministic explanation of how this conclusion was resolved; null when unreconciled. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String reasoning;

    /** This conclusion's own span in whole years (endYear − startYear, min 0);
     *  null when unreconciled or dates unknown. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Integer totalYears;

    /**
     * Stable cluster identity (Service History P3, 2026-07-05). A deterministic
     * key that re-attaches a persisted veteran override / LLM resolution to the
     * SAME reconciled conclusion after re-derivation (new uploads, re-extraction).
     * Formula + stability guarantees live on {@code ServiceHistoryReconciler}.
     * ADDITIVE + NON_NULL: emitted only on reconciled conclusions, absent on the
     * legacy exact-dedupe path (flag OFF) and older readers ignore it. The web
     * round-trips this value in an override request so the correction lands on the
     * right enlistment. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String clusterKey;

    public ServicePeriodDto() {
    }

    public ServicePeriodDto(String branch, String component, String startDate, String endDate,
                            String mos, String rank, String source) {
        this.branch = branch;
        this.component = component;
        this.startDate = startDate;
        this.endDate = endDate;
        this.mos = mos;
        this.rank = rank;
        this.source = source;
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

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public List<ServiceSourceDto> getSources() {
        return sources;
    }

    public void setSources(List<ServiceSourceDto> sources) {
        this.sources = sources;
    }

    public String getReasoning() {
        return reasoning;
    }

    public void setReasoning(String reasoning) {
        this.reasoning = reasoning;
    }

    public Integer getTotalYears() {
        return totalYears;
    }

    public void setTotalYears(Integer totalYears) {
        this.totalYears = totalYears;
    }

    public String getClusterKey() {
        return clusterKey;
    }

    public void setClusterKey(String clusterKey) {
        this.clusterKey = clusterKey;
    }

    /**
     * Dedupe identity: branch (case-insensitive) + start + end. Re-uploads of
     * the same DD-214 produce the same key; a manual row matching a derived
     * period collapses onto it.
     */
    public String dedupeKey() {
        String b = branch == null ? "" : branch.strip().toLowerCase(java.util.Locale.ROOT);
        return b + "|" + (startDate == null ? "" : startDate) + "|" + (endDate == null ? "" : endDate);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ServicePeriodDto that = (ServicePeriodDto) o;
        return Objects.equals(branch, that.branch) &&
                Objects.equals(component, that.component) &&
                Objects.equals(startDate, that.startDate) &&
                Objects.equals(endDate, that.endDate) &&
                Objects.equals(mos, that.mos) &&
                Objects.equals(rank, that.rank) &&
                Objects.equals(source, that.source) &&
                Objects.equals(sources, that.sources) &&
                Objects.equals(reasoning, that.reasoning) &&
                Objects.equals(totalYears, that.totalYears) &&
                Objects.equals(clusterKey, that.clusterKey);
    }

    @Override
    public int hashCode() {
        return Objects.hash(branch, component, startDate, endDate, mos, rank, source,
                sources, reasoning, totalYears, clusterKey);
    }

    @Override
    public String toString() {
        return "ServicePeriodDto(" +
                "branch=" + branch +
                ", component=" + component +
                ", startDate=" + startDate +
                ", endDate=" + endDate +
                ", mos=" + mos +
                ", rank=" + rank +
                ", source=" + source +
                ", sources=" + sources +
                ", reasoning=" + reasoning +
                ", totalYears=" + totalYears +
                ", clusterKey=" + clusterKey +
                ')';
    }
}
