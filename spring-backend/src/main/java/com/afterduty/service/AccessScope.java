package com.afterduty.service;

/**
 * Scopes that can be asserted via {@link ClaimAccessService#assertScope}.
 *
 * <ul>
 *   <li>{@code VIEW_DOCS} — always allowed for any resolved access (owner or accepted share)</li>
 *   <li>{@code VIEW_ANALYSIS} — requires {@code canViewAnalysis=true} AND owner has active Pro</li>
 *   <li>{@code UPLOAD_DOCS} — requires {@code canUploadDocs=true}. ADD-only: covers
 *       uploads, quick-add, and reprocess — never deletion (see {@code DELETE_DOCS})</li>
 *   <li>{@code DELETE_DOCS} — owner-only (P1-19 / Phase G1). Deletion was deliberately
 *       split out of {@code UPLOAD_DOCS}: a viewer allowed to add documents must never
 *       be able to destroy the owner's evidence. No share flag grants this scope.</li>
 *   <li>{@code CHAT} — owner requires active Pro; viewer requires {@code canViewAnalysis=true}
 *       AND viewer has active Pro. Viewers get grounding tools only (see ChatAgent)</li>
 * </ul>
 */
public enum AccessScope {
    VIEW_DOCS,
    VIEW_ANALYSIS,
    UPLOAD_DOCS,
    DELETE_DOCS,
    CHAT
}
