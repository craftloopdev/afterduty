package com.afterduty.service;

/**
 * Immutable result of {@link ClaimAccessService#resolve}. Carries the resolved
 * permissions for the current user on a specific claim.
 *
 * @param claimId          the claim being accessed
 * @param ownerUserId      the user who owns the claim
 * @param isOwner          true if the current user is the claim owner
 * @param canViewAnalysis  true if the current user may view AI analysis output
 * @param canUploadDocs    true if the current user may upload documents
 */
public record ClaimAccess(
        Long claimId,
        Long ownerUserId,
        boolean isOwner,
        boolean canViewAnalysis,
        boolean canUploadDocs
) {
}
