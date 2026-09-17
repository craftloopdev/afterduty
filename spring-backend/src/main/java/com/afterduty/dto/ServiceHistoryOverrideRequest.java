package com.afterduty.dto;

/**
 * Body for {@code POST /api/auth/service-history/override} (Service History P3
 * Part A). The veteran's correction to ONE reconciled conclusion, identified by
 * its STABLE {@code clusterKey} (round-tripped from the conclusion the web is
 * editing). Every overridable field is nullable — only the set ones win over the
 * documents; a null field leaves the reconciled value.
 *
 * <p>SECURITY: {@code clusterKey} is DATA — it selects which of the AUTHENTICATED
 * user's conclusions to correct. The owning userId is the resolved principal, never
 * from the body, so a client can never target another account's rows.
 */
public class ServiceHistoryOverrideRequest {

    private String clusterKey;
    private String branch;
    private String component;
    private String startDate;
    private String endDate;
    private String mos;
    private String rank;

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
}
