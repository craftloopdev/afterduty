package com.afterduty.service;

import com.afterduty.dto.ShareDto;
import com.afterduty.dto.CreateShareRequest;
import com.afterduty.model.Claim;
import com.afterduty.model.Share;
import com.afterduty.model.User;
import com.afterduty.repository.ClaimRepository;
import com.afterduty.repository.ShareRepository;
import com.afterduty.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Phase G backend: P1-19 DELETE_DOCS is owner-only (canUploadDocs never grants
 * deletion) + P2-2 share lifecycle (fresh token on re-invite, status field,
 * idempotent revoke).
 */
@DataJpaTest
@Import({ClaimAccessService.class, ShareService.class, SubscriptionAccess.class, com.afterduty.config.SubscriptionProperties.class})
class ShareLifecycleAndDeleteScopeTest {

    private static final Instant FUTURE = Instant.now().plus(365, ChronoUnit.DAYS);

    @Autowired UserRepository userRepository;
    @Autowired ClaimRepository claimRepository;
    @Autowired ShareRepository shareRepository;
    @Autowired ClaimAccessService claimAccessService;
    @Autowired ShareService shareService;

    private User user(String tag) {
        return userRepository.save(User.builder()
                .email(tag + "-" + System.nanoTime() + "@test.com")
                .name("Test " + tag)
                .subscriptionExpiresAt(FUTURE)
                .build());
    }

    private Claim claim(Long userId) {
        return claimRepository.save(Claim.builder().userId(userId).build());
    }

    private Share acceptedShare(User owner, Claim c, User viewer, boolean uploads) {
        Share s = new Share();
        s.setOwnerUserId(owner.getId());
        s.setClaimId(c.getId());
        s.setViewerUserId(viewer.getId());
        s.setViewerEmail(viewer.getEmail());
        s.setCanViewAnalysis(true);
        s.setCanUploadDocs(uploads);
        s.setAcceptedAt(Instant.now());
        return shareRepository.save(s);
    }

    // ── P1-19: DELETE_DOCS owner-only ────────────────────────────────────────

    @Test
    void viewerWithUploadGrant_cannotDeleteDocs() {
        User owner = user("owner");
        User viewer = user("viewer");
        Claim c = claim(owner.getId());
        acceptedShare(owner, c, viewer, true); // canUploadDocs=true

        ClaimAccess access = claimAccessService.resolve(viewer, c.getId());
        assertDoesNotThrow(() -> claimAccessService.assertScope(access, AccessScope.UPLOAD_DOCS, viewer));
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> claimAccessService.assertScope(access, AccessScope.DELETE_DOCS, viewer));
        assertThat(ex.getStatusCode().value()).isEqualTo(403);
    }

    @Test
    void owner_keepsDeleteDocs() {
        User owner = user("owner");
        Claim c = claim(owner.getId());
        ClaimAccess access = claimAccessService.resolve(owner, c.getId());
        assertDoesNotThrow(() -> claimAccessService.assertScope(access, AccessScope.DELETE_DOCS, owner));
    }

    // ── P2-2: re-invite issues a fresh working token ─────────────────────────

    @Test
    void reInvite_ofPendingShare_retiresOldRowAndIssuesFreshToken() {
        User owner = user("owner");
        Claim c = claim(owner.getId());
        CreateShareRequest req = new CreateShareRequest();
        req.setViewerEmail("rep@vso.org");
        req.setCanViewAnalysis(false);
        req.setCanUploadDocs(false);

        ShareDto first = shareService.createShare(owner, req);
        ShareDto second = shareService.createShare(owner, req);

        assertThat(second.getInvitationToken()).isNotBlank();
        assertThat(second.getInvitationToken()).isNotEqualTo(first.getInvitationToken());
        assertThat(second.getStatus()).isEqualTo("pending");
        // The old row is retired (revoked), not left as a dead 410 mystery link.
        Share old = shareRepository.findById(first.getId()).orElseThrow();
        assertThat(old.getRevokedAt()).isNotNull();
    }

    @Test
    void reInvite_ofAcceptedShare_returnsLiveShareWithoutNewToken() {
        User owner = user("owner");
        User viewer = user("viewer");
        Claim c = claim(owner.getId());
        acceptedShare(owner, c, viewer, false);

        CreateShareRequest req = new CreateShareRequest();
        req.setViewerEmail(viewer.getEmail());
        req.setCanViewAnalysis(true);
        req.setCanUploadDocs(true);

        ShareDto dto = shareService.createShare(owner, req);
        assertThat(dto.getStatus()).isEqualTo("accepted");
        assertThat(dto.getInvitationToken()).isNull();
        assertThat(dto.isCanUploadDocs()).isTrue(); // permissions refreshed in place
    }

    // ── P2-2: status field + idempotent revoke ───────────────────────────────

    @Test
    void computeStatus_precedence() {
        Share s = new Share();
        assertThat(ShareService.computeStatus(s)).isEqualTo("pending");
        s.setInvitationExpiresAt(Instant.now().minus(1, ChronoUnit.DAYS));
        assertThat(ShareService.computeStatus(s)).isEqualTo("expired");
        s.setAcceptedAt(Instant.now());
        assertThat(ShareService.computeStatus(s)).isEqualTo("accepted");
        s.setRevokedAt(Instant.now());
        assertThat(ShareService.computeStatus(s)).isEqualTo("revoked");
    }

    @Test
    void revoke_isIdempotent_andPreservesOriginalTimestamp() {
        User owner = user("owner");
        User viewer = user("viewer");
        Claim c = claim(owner.getId());
        Share s = acceptedShare(owner, c, viewer, false);

        shareService.revokeShare(owner, s.getId());
        Instant firstRevokedAt = shareRepository.findById(s.getId()).orElseThrow().getRevokedAt();
        assertThat(firstRevokedAt).isNotNull();

        assertDoesNotThrow(() -> shareService.revokeShare(owner, s.getId()));
        assertThat(shareRepository.findById(s.getId()).orElseThrow().getRevokedAt())
                .isEqualTo(firstRevokedAt);
    }
}
