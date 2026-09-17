package com.afterduty.dto;

import java.util.Map;
import java.util.Objects;

public class MessageResponse {
    private Long id;
    private String role;
    private String content;
    private Map<String, Object> extractedData;
    private String createdAt;

    public MessageResponse() {
    }

    public MessageResponse(Long id, String role, String content, Map<String, Object> extractedData, String createdAt) {
        this.id = id;
        this.role = role;
        this.content = content;
        this.extractedData = extractedData;
        this.createdAt = createdAt;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public Map<String, Object> getExtractedData() {
        return extractedData;
    }

    public void setExtractedData(Map<String, Object> extractedData) {
        this.extractedData = extractedData;
    }

    public String getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(String createdAt) {
        this.createdAt = createdAt;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private Long id;
        private String role;
        private String content;
        private Map<String, Object> extractedData;
        private String createdAt;

        public Builder id(Long id) {
            this.id = id;
            return this;
        }

        public Builder role(String role) {
            this.role = role;
            return this;
        }

        public Builder content(String content) {
            this.content = content;
            return this;
        }

        public Builder extractedData(Map<String, Object> extractedData) {
            this.extractedData = extractedData;
            return this;
        }

        public Builder createdAt(String createdAt) {
            this.createdAt = createdAt;
            return this;
        }

        public MessageResponse build() {
            return new MessageResponse(id, role, content, extractedData, createdAt);
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        MessageResponse that = (MessageResponse) o;
        return Objects.equals(id, that.id) &&
                Objects.equals(role, that.role) &&
                Objects.equals(content, that.content) &&
                Objects.equals(extractedData, that.extractedData) &&
                Objects.equals(createdAt, that.createdAt);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, role, content, extractedData, createdAt);
    }

    @Override
    public String toString() {
        return "MessageResponse(" +
                "id=" + id +
                ", role=" + role +
                ", content=" + content +
                ", extractedData=" + extractedData +
                ", createdAt=" + createdAt +
                ')';
    }
}
