package com.afterduty.service;

import com.afterduty.dto.CreateShareRequest;
import com.afterduty.dto.PatchShareRequest;
import com.afterduty.dto.ProfileSummaryDto;
import com.afterduty.dto.ShareDto;
import com.afterduty.dto.SharePreviewDto;
import com.afterduty.model.Claim;
import com.afterduty.model.ChatThread;
import com.afterduty.model.Share;
import com.afterduty.model.User;
import com.afterduty.repository.ChatThreadRepository;
import com.afterduty.repository.ClaimRepository;
import com.afterduty.repository.ShareRepository;
import com.afterduty.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Business logic for the share lifecycle.
 *
 * <p>Phase B: share creation, listing, patching, revoking, preview, accept,
 * and profiles listing. No Phase C wiring (X-View-As header) yet.
 */
@Service
public class ShareService {

    private static final Logger log = LoggerFactory.getLogger(ShareService.class);

    /** Base of the share-invite accept URL; overridable so a domain move is an env flip, not a code change. */
    @org.springframework.beans.factory.annotation.Value("${share.accept-url-prefix:https://app.afterduty.app/accept-share/}")
    private String acceptUrlPrefix;

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final ShareRepository shareRepository;
    private final ChatThreadRepository chatThreadRepository;
    private final ClaimRepository claimRepository;
    private final UserRepository userRepository;

    public ShareService(ShareRepository shareRepository,
                        ChatThreadRepository chatThreadRepository,
                        ClaimRepository claimRepository,
                        UserRepository userRepository) {
        this.shareRepository = shareRepository;
        this.chatThreadRepository = chatThreadRepository;
        this.claimRepository = claimRepository;
        this.userRepository = userRepository;
    }

    // -------------------------------------------------------------------------
    // createShare
    // -------------------------------------------------------------------------

    @Transactional
    public ShareDto createShare(User owner, CreateShareRequest req) {
        Claim claim = getOrCreateOwnerClaim(owner);
        // Normalize at write so case + whitespace can never cause a missed match
        // at accept time (when comparing against the Firebase-normalized email).
        String normalizedEmail = req.getViewerEmail().trim().toLowerCase(Locale.ROOT);

        Optional<Share> existing = shareRepository
                .findByClaimIdAndViewerEmailIgnoreCaseAndRevokedAtIsNull(
                        claim.getId(), normalizedEmail);

        if (existing.isPresent()) {
            Share current = existing.get();

            if (current.getAcceptedAt() != null) {
                // P2-2: already-accepted viewer — a "re-invite" is a permissions
                // refresh, not a new invitation. The pre-fix code stamped a fresh
                // token onto the accepted row and returned an acceptUrl that could
                // only ever answer 410 already_accepted at preview/accept time
                // (the report's "dead 410 link"). Return the live share instead:
                // status=accepted, no token, no acceptUrl.
                current.setCanViewAnalysis(req.isCanViewAnalysis());
                current.setCanUploadDocs(req.isCanUploadDocs());
                // Drift-proofing: accept clears the token; make sure no stray
                // token can linger on an accepted row.
                current.setInvitationToken(null);
                current.setInvitationExpiresAt(null);
                shareRepository.save(current);
                return toDto(current);
            }

            // P2-2: pending (possibly expired) invite — retire the old row so its
            // single-use link answers 410 share_revoked (a deliberate signal, not
            // a mystery 404), then fall through to issue a brand-new share with a
            // fresh working token. The non-revoked-row invariant is preserved:
            // at most one non-revoked share per (claim, viewerEmail).
            current.setRevokedAt(Instant.now());
            shareRepository.save(current);
        }

        // New share
        Share share = Share.builder()
                .ownerUserId(owner.getId())
                .claimId(claim.getId())
                .viewerEmail(normalizedEmail)
                .canViewAnalysis(req.isCanViewAnalysis())
                .canUploadDocs(req.isCanUploadDocs())
                .invitationToken(generateToken())
                .invitationExpiresAt(Instant.now().plus(7, ChronoUnit.DAYS))
                .build();
        shareRepository.save(share);
        return toDto(share);
    }

    // -------------------------------------------------------------------------
    // listShares
    // -------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<ShareDto> listShares(User owner) {
        return shareRepository.findByOwnerUserIdAndRevokedAtIsNull(owner.getId())
                .stream()
                .map(this::toDto)
                .toList();
    }

    // -------------------------------------------------------------------------
    // patchShare
    // -------------------------------------------------------------------------

    @Transactional
    public ShareDto patchShare(User owner, Long shareId, PatchShareRequest req) {
        Share share = shareRepository.findById(shareId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "share_not_found"));

        if (!share.getOwnerUserId().equals(owner.getId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "not_share_owner");
        }

        if (req.getCanViewAnalysis() != null) {
            share.setCanViewAnalysis(req.getCanViewAnalysis());
        }
        if (req.getCanUploadDocs() != null) {
            share.setCanUploadDocs(req.getCanUploadDocs());
        }

        shareRepository.save(share);
        return toDto(share);
    }

    // -------------------------------------------------------------------------
    // revokeShare
    // -------------------------------------------------------------------------

    @Transactional
    public void revokeShare(User owner, Long shareId) {
        Share share = shareRepository.findById(shareId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "share_not_found"));

        if (!share.getOwnerUserId().equals(owner.getId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "not_share_owner");
        }

        // Idempotent (P2-2): a second DELETE is a no-op that preserves the
        // original revocation timestamp — repeating the call must not move
        // revokedAt forward or error.
        if (share.getRevokedAt() != null) {
            return;
        }

        share.setRevokedAt(Instant.now());
        shareRepository.save(share);
        // Do NOT delete ChatThread (preserves atom provenance per plan.md)
    }

    // -------------------------------------------------------------------------
    // previewShare (public, no auth)
    // -------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public SharePreviewDto previewShare(String token) {
        Share share = shareRepository.findByInvitationToken(token)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "share_not_found"));

        if (share.getRevokedAt() != null) {
            throw new ResponseStatusException(HttpStatus.GONE, "share_revoked");
        }
        if (share.getInvitationExpiresAt() != null
                && share.getInvitationExpiresAt().isBefore(Instant.now())) {
            throw new ResponseStatusException(HttpStatus.GONE, "invitation_expired");
        }
        if (share.getAcceptedAt() != null) {
            throw new ResponseStatusException(HttpStatus.GONE, "already_accepted");
        }

        User owner = userRepository.findById(share.getOwnerUserId())
                .orElseThrow(() -> {
                    log.error("data integrity: share id={} references missing owner user id={}",
                            share.getId(), share.getOwnerUserId());
                    return new ResponseStatusException(
                            HttpStatus.INTERNAL_SERVER_ERROR, "owner_not_found");
                });

        Claim claim = claimRepository.findById(share.getClaimId())
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "claim_not_found"));

        SharePreviewDto dto = new SharePreviewDto();
        dto.setShareId(share.getId());
        dto.setOwnerName(owner.getName());
        dto.setOwnerEmail(owner.getEmail());
        dto.setClaimId(claim.getId());
        dto.setClaimStatus(claim.getStatus() != null ? claim.getStatus().name() : "DRAFT");
        dto.setCanViewAnalysis(share.isCanViewAnalysis());
        dto.setCanUploadDocs(share.isCanUploadDocs());
        dto.setExpiresAt(share.getInvitationExpiresAt());
        return dto;
    }

    // -------------------------------------------------------------------------
    // acceptShare
    // -------------------------------------------------------------------------

    @Transactional
    public ShareDto acceptShare(User viewer, String token) {
        Share share = shareRepository.findByInvitationToken(token)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "share_not_found"));

        if (share.getRevokedAt() != null) {
            throw new ResponseStatusException(HttpStatus.GONE, "share_revoked");
        }
        if (share.getInvitationExpiresAt() != null
                && share.getInvitationExpiresAt().isBefore(Instant.now())) {
            throw new ResponseStatusException(HttpStatus.GONE, "invitation_expired");
        }
        if (share.getAcceptedAt() != null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "already_accepted");
        }

        // Case-insensitive email match. Null-safe on both sides — a null
        // viewerEmail (somehow persisted) or a null Firebase email (rare,
        // but possible for phone-only sign-in) must not throw NPE; they
        // must deny access cleanly.
        String shareEmail = share.getViewerEmail();
        String viewerEmail = viewer.getEmail();
        if (shareEmail == null || viewerEmail == null
                || !shareEmail.equalsIgnoreCase(viewerEmail)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "email_mismatch");
        }

        share.setViewerUserId(viewer.getId());
        share.setAcceptedAt(Instant.now());
        // Clear single-use token
        share.setInvitationToken(null);
        share.setInvitationExpiresAt(null);
        shareRepository.save(share);

        // Upsert ChatThread for (viewer.id, claimId)
        chatThreadRepository.findByViewerUserIdAndClaimId(viewer.getId(), share.getClaimId())
                .orElseGet(() -> {
                    ChatThread thread = ChatThread.builder()
                            .viewerUserId(viewer.getId())
                            .claimId(share.getClaimId())
                            .build();
                    return chatThreadRepository.save(thread);
                });

        return toDto(share);
    }

    // -------------------------------------------------------------------------
    // listProfiles
    // -------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<ProfileSummaryDto> listProfiles(User currentUser) {
        List<ProfileSummaryDto> result = new ArrayList<>();

        // Own claim entry (if user has one)
        claimRepository.findFirstByUserIdAndClaimType(currentUser.getId(), Claim.ClaimType.INITIAL)
                .ifPresent(claim -> {
                    ProfileSummaryDto own = new ProfileSummaryDto();
                    own.setClaimId(claim.getId());
                    own.setOwnerName(currentUser.getName());
                    own.setOwnerEmail(currentUser.getEmail());
                    own.setOwn(true);
                    own.setCanViewAnalysis(true);
                    own.setCanUploadDocs(true);
                    result.add(own);
                });

        // Accepted shared claims (not revoked)
        List<Share> sharedAccess = shareRepository
                .findByViewerUserIdAndAcceptedAtIsNotNullAndRevokedAtIsNull(currentUser.getId());

        for (Share share : sharedAccess) {
            userRepository.findById(share.getOwnerUserId()).ifPresent(owner -> {
                ProfileSummaryDto shared = new ProfileSummaryDto();
                shared.setClaimId(share.getClaimId());
                shared.setOwnerName(owner.getName());
                shared.setOwnerEmail(owner.getEmail());
                shared.setOwn(false);
                shared.setCanViewAnalysis(share.isCanViewAnalysis());
                shared.setCanUploadDocs(share.isCanUploadDocs());
                result.add(shared);
            });
        }

        return result;
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    /**
     * Generates a URL-safe base64 token from 32 bytes of secure random.
     * Produces a ~43-character string with no padding.
     *
     * <p>Uses {@link SecureRandom#nextBytes} (the conventional choice) rather
     * than {@link SecureRandom#generateSeed} — the latter pulls from the
     * blocking entropy source on Linux and is intended for seeding other
     * PRNGs, not for high-frequency token generation.
     */
    private static String generateToken() {
        byte[] buf = new byte[32];
        SECURE_RANDOM.nextBytes(buf);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(buf);
    }

    /**
     * Returns the owner's most recent INITIAL claim, auto-creating one if none
     * exists. This mirrors {@code IntakeController.getOrCreateActiveClaim}.
     */
    private Claim getOrCreateOwnerClaim(User owner) {
        return claimRepository.findFirstByUserIdAndClaimType(owner.getId(), Claim.ClaimType.INITIAL)
                .orElseGet(() -> {
                    Claim claim = Claim.builder()
                            .userId(owner.getId())
                            .claimType(Claim.ClaimType.INITIAL)
                            .status(Claim.ClaimStatus.DRAFT)
                            .build();
                    return claimRepository.save(claim);
                });
    }

    /**
     * Maps a {@link Share} entity to a {@link ShareDto}, computing
     * {@code acceptUrl} from the token if the token is still present.
     */
    private ShareDto toDto(Share share) {
        ShareDto dto = new ShareDto();
        dto.setId(share.getId());
        dto.setClaimId(share.getClaimId());
        dto.setViewerEmail(share.getViewerEmail());
        dto.setCanViewAnalysis(share.isCanViewAnalysis());
        dto.setCanUploadDocs(share.isCanUploadDocs());
        dto.setInvitationToken(share.getInvitationToken());
        if (share.getInvitationToken() != null) {
            dto.setAcceptUrl(acceptUrlPrefix + share.getInvitationToken());
        }
        dto.setInvitationExpiresAt(share.getInvitationExpiresAt());
        dto.setAcceptedAt(share.getAcceptedAt());
        dto.setRevokedAt(share.getRevokedAt());
        dto.setCreatedAt(share.getCreatedAt());
        dto.setStatus(computeStatus(share));
        return dto;
    }

    /**
     * Lifecycle status for the web share list (P2-2): precedence is
     * revoked &gt; accepted &gt; expired &gt; pending. "expired" means the
     * invitation link lapsed before it was accepted; an accepted share never
     * expires (accept clears the token and its expiry).
     */
    static String computeStatus(Share share) {
        if (share.getRevokedAt() != null) {
            return "revoked";
        }
        if (share.getAcceptedAt() != null) {
            return "accepted";
        }
        if (share.getInvitationExpiresAt() != null
                && share.getInvitationExpiresAt().isBefore(Instant.now())) {
            return "expired";
        }
        return "pending";
    }
}
