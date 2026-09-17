package com.afterduty.dto;

/**
 * Request body for PATCH /api/shares/{id}. All fields nullable for partial update.
 */
public class PatchShareRequest {

    private Boolean canViewAnalysis;
    private Boolean canUploadDocs;

    public PatchShareRequest() {
    }

    public Boolean getCanViewAnalysis() {
        return canViewAnalysis;
    }

    public void setCanViewAnalysis(Boolean canViewAnalysis) {
        this.canViewAnalysis = canViewAnalysis;
    }

    public Boolean getCanUploadDocs() {
        return canUploadDocs;
    }

    public void setCanUploadDocs(Boolean canUploadDocs) {
        this.canUploadDocs = canUploadDocs;
    }
}
