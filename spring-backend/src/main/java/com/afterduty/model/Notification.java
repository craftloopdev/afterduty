package com.afterduty.model;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.Objects;

@Entity
@Table(name = "notifications")
public class Notification {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "claim_id")
    private Long claimId;

    @Column(name = "event_type", nullable = false)
    private String eventType;

    @Column(nullable = false)
    private String title;

    @Column(nullable = false, columnDefinition = "text")
    private String body;

    private String severity = "info";

    @Column(name = "condition_id")
    private Long conditionId;

    @Column(name = "metadata_json", columnDefinition = "text")
    private String metadataJson;

    @Column(name = "is_read")
    private Boolean isRead = false;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    public Notification() {
    }

    public Notification(Long id, Long userId, Long claimId, String eventType, String title, String body,
                        String severity, Long conditionId, String metadataJson, Boolean isRead, Instant createdAt) {
        this.id = id;
        this.userId = userId;
        this.claimId = claimId;
        this.eventType = eventType;
        this.title = title;
        this.body = body;
        this.severity = severity;
        this.conditionId = conditionId;
        this.metadataJson = metadataJson;
        this.isRead = isRead;
        this.createdAt = createdAt;
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

    public Long getClaimId() {
        return claimId;
    }

    public void setClaimId(Long claimId) {
        this.claimId = claimId;
    }

    public String getEventType() {
        return eventType;
    }

    public void setEventType(String eventType) {
        this.eventType = eventType;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getBody() {
        return body;
    }

    public void setBody(String body) {
        this.body = body;
    }

    public String getSeverity() {
        return severity;
    }

    public void setSeverity(String severity) {
        this.severity = severity;
    }

    public Long getConditionId() {
        return conditionId;
    }

    public void setConditionId(Long conditionId) {
        this.conditionId = conditionId;
    }

    public String getMetadataJson() {
        return metadataJson;
    }

    public void setMetadataJson(String metadataJson) {
        this.metadataJson = metadataJson;
    }

    public Boolean getIsRead() {
        return isRead;
    }

    public void setIsRead(Boolean isRead) {
        this.isRead = isRead;
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
        Notification that = (Notification) o;
        return Objects.equals(id, that.id) &&
                Objects.equals(userId, that.userId) &&
                Objects.equals(claimId, that.claimId) &&
                Objects.equals(eventType, that.eventType) &&
                Objects.equals(title, that.title) &&
                Objects.equals(body, that.body) &&
                Objects.equals(severity, that.severity) &&
                Objects.equals(conditionId, that.conditionId) &&
                Objects.equals(metadataJson, that.metadataJson) &&
                Objects.equals(isRead, that.isRead) &&
                Objects.equals(createdAt, that.createdAt);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, userId, claimId, eventType, title, body, severity, conditionId, metadataJson, isRead, createdAt);
    }

    @Override
    public String toString() {
        return "Notification(" +
                "id=" + id +
                ", userId=" + userId +
                ", claimId=" + claimId +
                ", eventType=" + eventType +
                ", title=" + title +
                ", body=" + body +
                ", severity=" + severity +
                ", conditionId=" + conditionId +
                ", metadataJson=" + metadataJson +
                ", isRead=" + isRead +
                ", createdAt=" + createdAt +
                ')';
    }

    public static NotificationBuilder builder() {
        return new NotificationBuilder();
    }

    public static class NotificationBuilder {
        private Long id;
        private Long userId;
        private Long claimId;
        private String eventType;
        private String title;
        private String body;
        private String severity = "info";
        private Long conditionId;
        private String metadataJson;
        private Boolean isRead = false;
        private Instant createdAt = Instant.now();

        NotificationBuilder() {
        }

        public NotificationBuilder id(Long id) {
            this.id = id;
            return this;
        }

        public NotificationBuilder userId(Long userId) {
            this.userId = userId;
            return this;
        }

        public NotificationBuilder claimId(Long claimId) {
            this.claimId = claimId;
            return this;
        }

        public NotificationBuilder eventType(String eventType) {
            this.eventType = eventType;
            return this;
        }

        public NotificationBuilder title(String title) {
            this.title = title;
            return this;
        }

        public NotificationBuilder body(String body) {
            this.body = body;
            return this;
        }

        public NotificationBuilder severity(String severity) {
            this.severity = severity;
            return this;
        }

        public NotificationBuilder conditionId(Long conditionId) {
            this.conditionId = conditionId;
            return this;
        }

        public NotificationBuilder metadataJson(String metadataJson) {
            this.metadataJson = metadataJson;
            return this;
        }

        public NotificationBuilder isRead(Boolean isRead) {
            this.isRead = isRead;
            return this;
        }

        public NotificationBuilder createdAt(Instant createdAt) {
            this.createdAt = createdAt;
            return this;
        }

        public Notification build() {
            return new Notification(id, userId, claimId, eventType, title, body, severity, conditionId, metadataJson, isRead, createdAt);
        }
    }
}
