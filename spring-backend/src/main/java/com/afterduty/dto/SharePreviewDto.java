package com.afterduty.dto;

import java.time.Instant;

/**
 * Public preview response for GET /api/shares/accept/{token}.
 * Returned without authentication so the viewer can see who is inviting them
 * and what permissions the share grants before they sign in to accept.
 */
public class SharePreviewDto {

    private Long shareId;
    private String ownerName;
    private String ownerEmail;
    private Long claimId;
    private String claimStatus;
    private boolean canViewAnalysis;
    private boolean canUploadDocs;
    private Instant expiresAt;

    public SharePreviewDto() {
    }

    public Long getShareId() {
        return shareId;
    }

    public void setShareId(Long shareId) {
        this.shareId = shareId;
    }

    public String getOwnerName() {
        return ownerName;
    }

    public void setOwnerName(String ownerName) {
        this.ownerName = ownerName;
    }

    public String getOwnerEmail() {
        return ownerEmail;
    }

    public void setOwnerEmail(String ownerEmail) {
        this.ownerEmail = ownerEmail;
    }

    public Long getClaimId() {
        return claimId;
    }

    public void setClaimId(Long claimId) {
        this.claimId = claimId;
    }

    public String getClaimStatus() {
        return claimStatus;
    }

    public void setClaimStatus(String claimStatus) {
        this.claimStatus = claimStatus;
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

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(Instant expiresAt) {
        this.expiresAt = expiresAt;
    }
}
