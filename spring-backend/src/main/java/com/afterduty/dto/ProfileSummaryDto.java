package com.afterduty.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Per-profile row returned by GET /api/shares/profiles.
 * Each entry represents one claim the current user can access — either
 * their own ({@code isOwn=true}) or via an accepted share ({@code isOwn=false}).
 */
public class ProfileSummaryDto {

    private Long claimId;
    private String ownerName;
    private String ownerEmail;
    private boolean isOwn;
    private boolean canViewAnalysis;
    private boolean canUploadDocs;

    public ProfileSummaryDto() {
    }

    public Long getClaimId() {
        return claimId;
    }

    public void setClaimId(Long claimId) {
        this.claimId = claimId;
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

    @JsonProperty("isOwn")
    public boolean isOwn() {
        return isOwn;
    }

    @JsonProperty("isOwn")
    public void setOwn(boolean own) {
        isOwn = own;
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
}
