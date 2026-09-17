package com.afterduty.dto;

import java.util.Objects;

public class AnalyzeResponse {
    private String status;
    private String message;
    private long atomCount;
    private long evidenceCount;

    public AnalyzeResponse() {
    }

    public AnalyzeResponse(String status, String message, long atomCount, long evidenceCount) {
        this.status = status;
        this.message = message;
        this.atomCount = atomCount;
        this.evidenceCount = evidenceCount;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public long getAtomCount() {
        return atomCount;
    }

    public void setAtomCount(long atomCount) {
        this.atomCount = atomCount;
    }

    public long getEvidenceCount() {
        return evidenceCount;
    }

    public void setEvidenceCount(long evidenceCount) {
        this.evidenceCount = evidenceCount;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String status;
        private String message;
        private long atomCount;
        private long evidenceCount;

        public Builder status(String status) {
            this.status = status;
            return this;
        }

        public Builder message(String message) {
            this.message = message;
            return this;
        }

        public Builder atomCount(long atomCount) {
            this.atomCount = atomCount;
            return this;
        }

        public Builder evidenceCount(long evidenceCount) {
            this.evidenceCount = evidenceCount;
            return this;
        }

        public AnalyzeResponse build() {
            return new AnalyzeResponse(status, message, atomCount, evidenceCount);
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        AnalyzeResponse that = (AnalyzeResponse) o;
        return atomCount == that.atomCount &&
                evidenceCount == that.evidenceCount &&
                Objects.equals(status, that.status) &&
                Objects.equals(message, that.message);
    }

    @Override
    public int hashCode() {
        return Objects.hash(status, message, atomCount, evidenceCount);
    }

    @Override
    public String toString() {
        return "AnalyzeResponse(" +
                "status=" + status +
                ", message=" + message +
                ", atomCount=" + atomCount +
                ", evidenceCount=" + evidenceCount +
                ')';
    }
}
