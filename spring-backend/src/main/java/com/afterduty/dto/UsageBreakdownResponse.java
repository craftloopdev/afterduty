package com.afterduty.dto;

import java.time.Instant;
import java.util.List;

/**
 * "Analyze my usage" — per-feature breakdown of a user's AI spend for the
 * current billing period (GET /api/usage/breakdown). Reads the AiCallLog
 * ledger for the caller's own userId only. Period and effective limit mirror
 * {@link UsageResponse}/UsageService.getCurrentUsage exactly.
 *
 * <p>Invariant: the sum of {@code byFeature[].cents} equals {@code spentCents}
 * (rounding is reconciled at the total, not per feature).
 */
public class UsageBreakdownResponse {

    private Instant periodStart;
    private Instant periodEnd;
    /** Next reset — the 1st of next month, i.e. the period end. */
    private Instant resetAt;
    /** Effective cap in cents (800 Pro / 150 free-a1), or -1 when unlimited/comped. */
    private int limitCents;
    /** Total spend for the period in cents (rounded half-up). */
    private int spentCents;
    /** max(0, limit - spent); 0 for unlimited users. */
    private int remainingCents;
    private boolean atLimit;
    private boolean unlimited;
    /** Non-zero features only, sorted by cents descending. */
    private List<FeatureUsage> byFeature;

    public UsageBreakdownResponse() {}

    public UsageBreakdownResponse(Instant periodStart, Instant periodEnd, Instant resetAt,
                                  int limitCents, int spentCents, int remainingCents,
                                  boolean atLimit, boolean unlimited,
                                  List<FeatureUsage> byFeature) {
        this.periodStart = periodStart;
        this.periodEnd = periodEnd;
        this.resetAt = resetAt;
        this.limitCents = limitCents;
        this.spentCents = spentCents;
        this.remainingCents = remainingCents;
        this.atLimit = atLimit;
        this.unlimited = unlimited;
        this.byFeature = byFeature;
    }

    public Instant getPeriodStart() { return periodStart; }
    public void setPeriodStart(Instant v) { this.periodStart = v; }
    public Instant getPeriodEnd() { return periodEnd; }
    public void setPeriodEnd(Instant v) { this.periodEnd = v; }
    public Instant getResetAt() { return resetAt; }
    public void setResetAt(Instant v) { this.resetAt = v; }
    public int getLimitCents() { return limitCents; }
    public void setLimitCents(int v) { this.limitCents = v; }
    public int getSpentCents() { return spentCents; }
    public void setSpentCents(int v) { this.spentCents = v; }
    public int getRemainingCents() { return remainingCents; }
    public void setRemainingCents(int v) { this.remainingCents = v; }
    public boolean isAtLimit() { return atLimit; }
    public void setAtLimit(boolean v) { this.atLimit = v; }
    public boolean isUnlimited() { return unlimited; }
    public void setUnlimited(boolean v) { this.unlimited = v; }
    public List<FeatureUsage> getByFeature() { return byFeature; }
    public void setByFeature(List<FeatureUsage> v) { this.byFeature = v; }

    /** One AiCallLog callType's spend + call count for the period. */
    public static class FeatureUsage {
        /** The raw callType: extraction | synthesis | gap_analysis | chat. */
        private String feature;
        /** Human label for the feature. */
        private String label;
        private int cents;
        private int calls;

        public FeatureUsage() {}

        public FeatureUsage(String feature, String label, int cents, int calls) {
            this.feature = feature;
            this.label = label;
            this.cents = cents;
            this.calls = calls;
        }

        public String getFeature() { return feature; }
        public void setFeature(String v) { this.feature = v; }
        public String getLabel() { return label; }
        public void setLabel(String v) { this.label = v; }
        public int getCents() { return cents; }
        public void setCents(int v) { this.cents = v; }
        public int getCalls() { return calls; }
        public void setCalls(int v) { this.calls = v; }
    }
}
