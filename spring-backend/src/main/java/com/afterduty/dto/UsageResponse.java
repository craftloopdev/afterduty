package com.afterduty.dto;

import java.time.Instant;

public class UsageResponse {
    private int percentUsed;
    private boolean atLimit;
    private Instant periodStart;
    private Instant periodEnd;

    public UsageResponse() {}
    public UsageResponse(int percentUsed, boolean atLimit, Instant periodStart, Instant periodEnd) {
        this.percentUsed = percentUsed;
        this.atLimit = atLimit;
        this.periodStart = periodStart;
        this.periodEnd = periodEnd;
    }

    public int getPercentUsed() { return percentUsed; }
    public void setPercentUsed(int v) { this.percentUsed = v; }
    public boolean isAtLimit() { return atLimit; }
    public void setAtLimit(boolean v) { this.atLimit = v; }
    public Instant getPeriodStart() { return periodStart; }
    public void setPeriodStart(Instant v) { this.periodStart = v; }
    public Instant getPeriodEnd() { return periodEnd; }
    public void setPeriodEnd(Instant v) { this.periodEnd = v; }
}
