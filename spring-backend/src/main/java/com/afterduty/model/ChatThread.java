package com.afterduty.model;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.Objects;

/**
 * Represents a persistent chat thread between a viewer (VSO/attorney) and
 * a specific claim. Each viewer gets exactly one thread per claim.
 *
 * <p>Phase A: entity + repository only. IntakeMessage.threadId FK is added
 * in Phase D.
 */
@Entity
@Table(
        name = "chat_threads",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_chat_threads_viewer_claim",
                columnNames = {"viewer_user_id", "claim_id"}
        )
)
public class ChatThread {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "viewer_user_id", nullable = false)
    private Long viewerUserId;

    @Column(name = "claim_id", nullable = false)
    private Long claimId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "viewer_user_id", insertable = false, updatable = false)
    private User viewer;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "claim_id", insertable = false, updatable = false)
    private Claim claim;

    public ChatThread() {
    }

    public ChatThread(Long id, Long viewerUserId, Long claimId, Instant createdAt,
                      User viewer, Claim claim) {
        this.id = id;
        this.viewerUserId = viewerUserId;
        this.claimId = claimId;
        this.createdAt = createdAt;
        this.viewer = viewer;
        this.claim = claim;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getViewerUserId() {
        return viewerUserId;
    }

    public void setViewerUserId(Long viewerUserId) {
        this.viewerUserId = viewerUserId;
    }

    public Long getClaimId() {
        return claimId;
    }

    public void setClaimId(Long claimId) {
        this.claimId = claimId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public User getViewer() {
        return viewer;
    }

    public void setViewer(User viewer) {
        this.viewer = viewer;
    }

    public Claim getClaim() {
        return claim;
    }

    public void setClaim(Claim claim) {
        this.claim = claim;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ChatThread that = (ChatThread) o;
        return id != null && Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }

    @Override
    public String toString() {
        return "ChatThread(" +
                "id=" + id +
                ", viewerUserId=" + viewerUserId +
                ", claimId=" + claimId +
                ", createdAt=" + createdAt +
                ')';
    }

    public static ChatThreadBuilder builder() {
        return new ChatThreadBuilder();
    }

    public static class ChatThreadBuilder {
        private Long id;
        private Long viewerUserId;
        private Long claimId;
        private Instant createdAt = Instant.now();
        private User viewer;
        private Claim claim;

        ChatThreadBuilder() {
        }

        public ChatThreadBuilder id(Long id) {
            this.id = id;
            return this;
        }

        public ChatThreadBuilder viewerUserId(Long viewerUserId) {
            this.viewerUserId = viewerUserId;
            return this;
        }

        public ChatThreadBuilder claimId(Long claimId) {
            this.claimId = claimId;
            return this;
        }

        public ChatThreadBuilder createdAt(Instant createdAt) {
            this.createdAt = createdAt;
            return this;
        }

        public ChatThreadBuilder viewer(User viewer) {
            this.viewer = viewer;
            return this;
        }

        public ChatThreadBuilder claim(Claim claim) {
            this.claim = claim;
            return this;
        }

        public ChatThread build() {
            return new ChatThread(id, viewerUserId, claimId, createdAt, viewer, claim);
        }
    }
}
