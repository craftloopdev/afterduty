package com.afterduty.dto;

import java.time.Instant;
import java.util.Objects;

public class ClaimResponse {
    private Long id;
    private String claimType;
    private String status;
    private boolean synthesisNeeded;
    private long evidenceCount;
    private long conditionCount;
    private long atomCount;
    private String createdAt;
    private String updatedAt;
    private String analysisMessage;
    private String analysisStage;
    private Integer analysisProgressPct;
    private Instant lastAnalyzedAt;

    public String getAnalysisMessage() { return analysisMessage; }
    public void setAnalysisMessage(String analysisMessage) { this.analysisMessage = analysisMessage; }

    public String getAnalysisStage() { return analysisStage; }
    public void setAnalysisStage(String analysisStage) { this.analysisStage = analysisStage; }

    public Integer getAnalysisProgressPct() { return analysisProgressPct; }
    public void setAnalysisProgressPct(Integer analysisProgressPct) { this.analysisProgressPct = analysisProgressPct; }

    public Instant getLastAnalyzedAt() { return lastAnalyzedAt; }
    public void setLastAnalyzedAt(Instant lastAnalyzedAt) { this.lastAnalyzedAt = lastAnalyzedAt; }

    public ClaimResponse() {
    }

    public ClaimResponse(Long id, String claimType, String status, boolean synthesisNeeded,
                         long evidenceCount, long conditionCount, long atomCount,
                         String createdAt, String updatedAt) {
        this.id = id;
        this.claimType = claimType;
        this.status = status;
        this.synthesisNeeded = synthesisNeeded;
        this.evidenceCount = evidenceCount;
        this.conditionCount = conditionCount;
        this.atomCount = atomCount;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getClaimType() {
        return claimType;
    }

    public void setClaimType(String claimType) {
        this.claimType = claimType;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public boolean isSynthesisNeeded() {
        return synthesisNeeded;
    }

    public void setSynthesisNeeded(boolean synthesisNeeded) {
        this.synthesisNeeded = synthesisNeeded;
    }

    public long getEvidenceCount() {
        return evidenceCount;
    }

    public void setEvidenceCount(long evidenceCount) {
        this.evidenceCount = evidenceCount;
    }

    public long getConditionCount() {
        return conditionCount;
    }

    public void setConditionCount(long conditionCount) {
        this.conditionCount = conditionCount;
    }

    public long getAtomCount() {
        return atomCount;
    }

    public void setAtomCount(long atomCount) {
        this.atomCount = atomCount;
    }

    public String getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(String createdAt) {
        this.createdAt = createdAt;
    }

    public String getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(String updatedAt) {
        this.updatedAt = updatedAt;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private Long id;
        private String claimType;
        private String status;
        private boolean synthesisNeeded;
        private long evidenceCount;
        private long conditionCount;
        private long atomCount;
        private String createdAt;
        private String updatedAt;

        public Builder id(Long id) {
            this.id = id;
            return this;
        }

        public Builder claimType(String claimType) {
            this.claimType = claimType;
            return this;
        }

        public Builder status(String status) {
            this.status = status;
            return this;
        }

        public Builder synthesisNeeded(boolean synthesisNeeded) {
            this.synthesisNeeded = synthesisNeeded;
            return this;
        }

        public Builder evidenceCount(long evidenceCount) {
            this.evidenceCount = evidenceCount;
            return this;
        }

        public Builder conditionCount(long conditionCount) {
            this.conditionCount = conditionCount;
            return this;
        }

        public Builder atomCount(long atomCount) {
            this.atomCount = atomCount;
            return this;
        }

        public Builder createdAt(String createdAt) {
            this.createdAt = createdAt;
            return this;
        }

        public Builder updatedAt(String updatedAt) {
            this.updatedAt = updatedAt;
            return this;
        }

        private String analysisMessage;
        private String analysisStage;
        private Integer analysisProgressPct;
        private Instant lastAnalyzedAt;

        public Builder analysisMessage(String v) { this.analysisMessage = v; return this; }
        public Builder analysisStage(String v) { this.analysisStage = v; return this; }
        public Builder analysisProgressPct(Integer v) { this.analysisProgressPct = v; return this; }
        public Builder lastAnalyzedAt(Instant v) { this.lastAnalyzedAt = v; return this; }

        public ClaimResponse build() {
            ClaimResponse r = new ClaimResponse(id, claimType, status, synthesisNeeded, evidenceCount, conditionCount, atomCount, createdAt, updatedAt);
            r.setAnalysisMessage(analysisMessage);
            r.setAnalysisStage(analysisStage);
            r.setAnalysisProgressPct(analysisProgressPct);
            r.setLastAnalyzedAt(lastAnalyzedAt);
            return r;
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ClaimResponse that = (ClaimResponse) o;
        return synthesisNeeded == that.synthesisNeeded &&
                evidenceCount == that.evidenceCount &&
                conditionCount == that.conditionCount &&
                atomCount == that.atomCount &&
                Objects.equals(id, that.id) &&
                Objects.equals(claimType, that.claimType) &&
                Objects.equals(status, that.status) &&
                Objects.equals(createdAt, that.createdAt) &&
                Objects.equals(updatedAt, that.updatedAt);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, claimType, status, synthesisNeeded, evidenceCount, conditionCount, atomCount, createdAt, updatedAt);
    }

    @Override
    public String toString() {
        return "ClaimResponse(" +
                "id=" + id +
                ", claimType=" + claimType +
                ", status=" + status +
                ", synthesisNeeded=" + synthesisNeeded +
                ", evidenceCount=" + evidenceCount +
                ", conditionCount=" + conditionCount +
                ", atomCount=" + atomCount +
                ", createdAt=" + createdAt +
                ", updatedAt=" + updatedAt +
                ')';
    }
}
