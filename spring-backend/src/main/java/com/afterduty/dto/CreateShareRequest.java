package com.afterduty.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Request body for POST /api/shares.
 */
public class CreateShareRequest {

    @NotBlank
    @Email
    @Size(max = 254) // RFC 5321 max email length
    private String viewerEmail;
    private boolean canViewAnalysis;
    private boolean canUploadDocs;

    public CreateShareRequest() {
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
}
