package com.afterduty.dto;

import java.util.Map;
import java.util.Objects;

public class EvidenceResponse {
    private Long id;
    private String sourceType;
    private String filename;
    private String aiClassification;
    private Map<String, Object> aiExtractedData;
    private String aiSummary;
    private String processingStatus;
    private String processingMessage;
    private String createdAt;
    private String rawContent;

    public EvidenceResponse() {
    }

    public EvidenceResponse(Long id, String sourceType, String filename, String aiClassification,
                            Map<String, Object> aiExtractedData, String aiSummary, String processingStatus,
                            String createdAt, String rawContent) {
        this.id = id;
        this.sourceType = sourceType;
        this.filename = filename;
        this.aiClassification = aiClassification;
        this.aiExtractedData = aiExtractedData;
        this.aiSummary = aiSummary;
        this.processingStatus = processingStatus;
        this.createdAt = createdAt;
        this.rawContent = rawContent;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getSourceType() {
        return sourceType;
    }

    public void setSourceType(String sourceType) {
        this.sourceType = sourceType;
    }

    public String getFilename() {
        return filename;
    }

    public void setFilename(String filename) {
        this.filename = filename;
    }

    public String getAiClassification() {
        return aiClassification;
    }

    public void setAiClassification(String aiClassification) {
        this.aiClassification = aiClassification;
    }

    public Map<String, Object> getAiExtractedData() {
        return aiExtractedData;
    }

    public void setAiExtractedData(Map<String, Object> aiExtractedData) {
        this.aiExtractedData = aiExtractedData;
    }

    public String getAiSummary() {
        return aiSummary;
    }

    public void setAiSummary(String aiSummary) {
        this.aiSummary = aiSummary;
    }

    public String getProcessingStatus() {
        return processingStatus;
    }

    public void setProcessingStatus(String processingStatus) {
        this.processingStatus = processingStatus;
    }

    public String getProcessingMessage() {
        return processingMessage;
    }

    public void setProcessingMessage(String processingMessage) {
        this.processingMessage = processingMessage;
    }

    public String getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(String createdAt) {
        this.createdAt = createdAt;
    }

    public String getRawContent() {
        return rawContent;
    }

    public void setRawContent(String rawContent) {
        this.rawContent = rawContent;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private Long id;
        private String sourceType;
        private String filename;
        private String aiClassification;
        private Map<String, Object> aiExtractedData;
        private String aiSummary;
        private String processingStatus;
        private String processingMessage;
        private String createdAt;
        private String rawContent;

        public Builder id(Long id) {
            this.id = id;
            return this;
        }

        public Builder sourceType(String sourceType) {
            this.sourceType = sourceType;
            return this;
        }

        public Builder filename(String filename) {
            this.filename = filename;
            return this;
        }

        public Builder aiClassification(String aiClassification) {
            this.aiClassification = aiClassification;
            return this;
        }

        public Builder aiExtractedData(Map<String, Object> aiExtractedData) {
            this.aiExtractedData = aiExtractedData;
            return this;
        }

        public Builder aiSummary(String aiSummary) {
            this.aiSummary = aiSummary;
            return this;
        }

        public Builder processingStatus(String processingStatus) {
            this.processingStatus = processingStatus;
            return this;
        }

        public Builder processingMessage(String processingMessage) {
            this.processingMessage = processingMessage;
            return this;
        }

        public Builder createdAt(String createdAt) {
            this.createdAt = createdAt;
            return this;
        }

        public Builder rawContent(String rawContent) {
            this.rawContent = rawContent;
            return this;
        }

        public EvidenceResponse build() {
            EvidenceResponse r = new EvidenceResponse();
            r.id = id; r.sourceType = sourceType; r.filename = filename;
            r.aiClassification = aiClassification; r.aiExtractedData = aiExtractedData;
            r.aiSummary = aiSummary; r.processingStatus = processingStatus;
            r.processingMessage = processingMessage; r.createdAt = createdAt;
            r.rawContent = rawContent;
            return r;
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        EvidenceResponse that = (EvidenceResponse) o;
        return Objects.equals(id, that.id) &&
                Objects.equals(sourceType, that.sourceType) &&
                Objects.equals(filename, that.filename) &&
                Objects.equals(aiClassification, that.aiClassification) &&
                Objects.equals(aiExtractedData, that.aiExtractedData) &&
                Objects.equals(aiSummary, that.aiSummary) &&
                Objects.equals(processingStatus, that.processingStatus) &&
                Objects.equals(createdAt, that.createdAt) &&
                Objects.equals(rawContent, that.rawContent);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, sourceType, filename, aiClassification, aiExtractedData,
                aiSummary, processingStatus, createdAt, rawContent);
    }

    @Override
    public String toString() {
        return "EvidenceResponse(" +
                "id=" + id +
                ", sourceType=" + sourceType +
                ", filename=" + filename +
                ", aiClassification=" + aiClassification +
                ", aiExtractedData=" + aiExtractedData +
                ", aiSummary=" + aiSummary +
                ", processingStatus=" + processingStatus +
                ", createdAt=" + createdAt +
                ", rawContent=" + rawContent +
                ')';
    }
}
