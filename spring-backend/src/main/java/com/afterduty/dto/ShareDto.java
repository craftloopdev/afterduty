package com.afterduty.dto;

import java.time.Instant;

/**
 * Response shape for share rows returned by POST /api/shares, GET /api/shares,
 * PATCH /api/shares/{id}, and POST /api/shares/accept/{token}.
 */
public class ShareDto {

    private Long id;
    private Long claimId;
    private String viewerEmail;
    private boolean canViewAnalysis;
    private boolean canUploadDocs;
    private String invitationToken;
    private String acceptUrl;
    private Instant invitationExpiresAt;
    private Instant acceptedAt;
    private Instant revokedAt;
    private Instant createdAt;
    /** Lifecycle: "pending" | "accepted" | "expired" | "revoked" (P2-2). */
    private String status;

    public ShareDto() {
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getClaimId() {
        return claimId;
    }

    public void setClaimId(Long claimId) {
        this.claimId = claimId;
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

    public String getAcceptUrl() {
        return acceptUrl;
    }

    public void setAcceptUrl(String acceptUrl) {
        this.acceptUrl = acceptUrl;
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

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }
}
