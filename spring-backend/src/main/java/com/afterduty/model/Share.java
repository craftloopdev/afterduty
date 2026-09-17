package com.afterduty.model;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.Objects;

/**
 * Represents a share invitation from a claim owner to a viewer (VSO/attorney).
 * A share is "active" when {@code acceptedAt IS NOT NULL AND revokedAt IS NULL}.
 */
@Entity
@Table(name = "shares")
public class Share {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "owner_user_id", nullable = false)
    private Long ownerUserId;

    @Column(name = "claim_id", nullable = false)
    private Long claimId;

    /** Null until the invitation is accepted. */
    @Column(name = "viewer_user_id")
    private Long viewerUserId;

    @Column(name = "viewer_email", nullable = false, length = 255)
    private String viewerEmail;

    @Column(name = "can_view_analysis", nullable = false)
    private boolean canViewAnalysis = false;

    @Column(name = "can_upload_docs", nullable = false)
    private boolean canUploadDocs = false;

    @Column(name = "invitation_token", length = 64, unique = true)
    private String invitationToken;

    @Column(name = "invitation_expires_at")
    private Instant invitationExpiresAt;

    @Column(name = "accepted_at")
    private Instant acceptedAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "owner_user_id", insertable = false, updatable = false)
    private User owner;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "viewer_user_id", insertable = false, updatable = false)
    private User viewer;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "claim_id", insertable = false, updatable = false)
    private Claim claim;

    public Share() {
    }

    public Share(Long id, Long ownerUserId, Long claimId, Long viewerUserId,
                 String viewerEmail, boolean canViewAnalysis, boolean canUploadDocs,
                 String invitationToken, Instant invitationExpiresAt,
                 Instant acceptedAt, Instant revokedAt, Instant createdAt,
                 User owner, User viewer, Claim claim) {
        this.id = id;
        this.ownerUserId = ownerUserId;
        this.claimId = claimId;
        this.viewerUserId = viewerUserId;
        this.viewerEmail = viewerEmail;
        this.canViewAnalysis = canViewAnalysis;
        this.canUploadDocs = canUploadDocs;
        this.invitationToken = invitationToken;
        this.invitationExpiresAt = invitationExpiresAt;
        this.acceptedAt = acceptedAt;
        this.revokedAt = revokedAt;
        this.createdAt = createdAt;
        this.owner = owner;
        this.viewer = viewer;
        this.claim = claim;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getOwnerUserId() {
        return ownerUserId;
    }

    public void setOwnerUserId(Long ownerUserId) {
        this.ownerUserId = ownerUserId;
    }

    public Long getClaimId() {
        return claimId;
    }

    public void setClaimId(Long claimId) {
        this.claimId = claimId;
    }

    public Long getViewerUserId() {
        return viewerUserId;
    }

    public void setViewerUserId(Long viewerUserId) {
        this.viewerUserId = viewerUserId;
    }

    public String getViewerEmail() {
        return viewerEmail;
    }

    public void setViewerEmail(String viewerEmail) {
        this.viewerEmail = viewerEmail;
    }

    public boolean isCanViewAnalysis() {
        return canViewAnalysis;
    }

    public void setCanViewAnalysis(boolean canViewAnalysis) {
        this.canViewAnalysis = canViewAnalysis;
    }

    public boolean isCanUploadDocs() {
        return canUploadDocs;
    }

    public void setCanUploadDocs(boolean canUploadDocs) {
        this.canUploadDocs = canUploadDocs;
    }

    public String getInvitationToken() {
        return invitationToken;
    }

    public void setInvitationToken(String invitationToken) {
        this.invitationToken = invitationToken;
    }

    public Instant getInvitationExpiresAt() {
        return invitationExpiresAt;
    }

    public void setInvitationExpiresAt(Instant invitationExpiresAt) {
        this.invitationExpiresAt = invitationExpiresAt;
    }

    public Instant getAcceptedAt() {
        return acceptedAt;
    }

    public void setAcceptedAt(Instant acceptedAt) {
        this.acceptedAt = acceptedAt;
    }

    public Instant getRevokedAt() {
        return revokedAt;
    }

    public void setRevokedAt(Instant revokedAt) {
        this.revokedAt = revokedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public User getOwner() {
        return owner;
    }

    public void setOwner(User owner) {
        this.owner = owner;
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
        Share that = (Share) o;
        return id != null && Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }

    @Override
    public String toString() {
        return "Share(" +
                "id=" + id +
                ", ownerUserId=" + ownerUserId +
                ", claimId=" + claimId +
                ", viewerUserId=" + viewerUserId +
                ", viewerEmail=" + viewerEmail +
                ", canViewAnalysis=" + canViewAnalysis +
                ", canUploadDocs=" + canUploadDocs +
                ", acceptedAt=" + acceptedAt +
                ", revokedAt=" + revokedAt +
                ", createdAt=" + createdAt +
                ')';
    }

    public static ShareBuilder builder() {
        return new ShareBuilder();
    }

    public static class ShareBuilder {
        private Long id;
        private Long ownerUserId;
        private Long claimId;
        private Long viewerUserId;
        private String viewerEmail;
        private boolean canViewAnalysis = false;
        private boolean canUploadDocs = false;
        private String invitationToken;
        private Instant invitationExpiresAt;
        private Instant acceptedAt;
        private Instant revokedAt;
        private Instant createdAt = Instant.now();
        private User owner;
        private User viewer;
        private Claim claim;

        ShareBuilder() {
        }

        public ShareBuilder id(Long id) {
            this.id = id;
            return this;
        }

        public ShareBuilder ownerUserId(Long ownerUserId) {
            this.ownerUserId = ownerUserId;
            return this;
        }

        public ShareBuilder claimId(Long claimId) {
            this.claimId = claimId;
            return this;
        }

        public ShareBuilder viewerUserId(Long viewerUserId) {
            this.viewerUserId = viewerUserId;
            return this;
        }

        public ShareBuilder viewerEmail(String viewerEmail) {
            this.viewerEmail = viewerEmail;
            return this;
        }

        public ShareBuilder canViewAnalysis(boolean canViewAnalysis) {
            this.canViewAnalysis = canViewAnalysis;
            return this;
        }

        public ShareBuilder canUploadDocs(boolean canUploadDocs) {
            this.canUploadDocs = canUploadDocs;
            return this;
        }

        public ShareBuilder invitationToken(String invitationToken) {
            this.invitationToken = invitationToken;
            return this;
        }

        public ShareBuilder invitationExpiresAt(Instant invitationExpiresAt) {
            this.invitationExpiresAt = invitationExpiresAt;
            return this;
        }

        public ShareBuilder acceptedAt(Instant acceptedAt) {
            this.acceptedAt = acceptedAt;
            return this;
        }

        public ShareBuilder revokedAt(Instant revokedAt) {
            this.revokedAt = revokedAt;
            return this;
        }

        public ShareBuilder createdAt(Instant createdAt) {
            this.createdAt = createdAt;
            return this;
        }

        public ShareBuilder owner(User owner) {
            this.owner = owner;
            return this;
        }

        public ShareBuilder viewer(User viewer) {
            this.viewer = viewer;
            return this;
        }

        public ShareBuilder claim(Claim claim) {
            this.claim = claim;
            return this;
        }

        public Share build() {
            return new Share(id, ownerUserId, claimId, viewerUserId, viewerEmail,
                    canViewAnalysis, canUploadDocs, invitationToken, invitationExpiresAt,
                    acceptedAt, revokedAt, createdAt, owner, viewer, claim);
        }
    }
}
