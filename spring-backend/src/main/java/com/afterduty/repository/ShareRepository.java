package com.afterduty.repository;

import com.afterduty.model.Share;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ShareRepository extends JpaRepository<Share, Long> {

    /**
     * Finds an active (accepted and not revoked) share for a specific viewer
     * on a specific claim. Used by {@code ClaimAccessService.resolve()}.
     */
    Optional<Share> findByViewerUserIdAndClaimIdAndAcceptedAtIsNotNullAndRevokedAtIsNull(
            Long viewerUserId, Long claimId);

    /**
     * Finds a share by its invitation token. Used in Phase B accept flow.
     */
    Optional<Share> findByInvitationToken(String token);

    /**
     * All non-revoked shares issued by a given owner. Used by GET /api/shares.
     */
    List<Share> findByOwnerUserIdAndRevokedAtIsNull(Long ownerUserId);

    /**
     * Finds an existing non-revoked share for a (claimId, viewerEmail) pair,
     * case-insensitively. Used for duplicate-share detection in POST /api/shares.
     */
    Optional<Share> findByClaimIdAndViewerEmailIgnoreCaseAndRevokedAtIsNull(
            Long claimId, String viewerEmail);

    /**
     * All accepted + non-revoked shares for a viewer. Used by GET /api/shares/profiles.
     */
    List<Share> findByViewerUserIdAndAcceptedAtIsNotNullAndRevokedAtIsNull(Long viewerUserId);
}
