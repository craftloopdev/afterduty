package com.afterduty.service;

import com.afterduty.config.SecurityConfig;
import com.afterduty.model.Claim;
import com.afterduty.model.Share;
import com.afterduty.model.User;
import com.afterduty.repository.ClaimRepository;
import com.afterduty.repository.ShareRepository;
import com.afterduty.repository.UserRepository;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

@DataJpaTest
@Import({ClaimAccessService.class, SubscriptionAccess.class, com.afterduty.config.SubscriptionProperties.class})
@Tag("regression")
class ClaimAccessServiceResolveIfPresentTest {

    private static final Instant FUTURE = Instant.now().plus(365, ChronoUnit.DAYS);

    @Autowired
    UserRepository userRepository;

    @Autowired
    ClaimRepository claimRepository;

    @Autowired
    ShareRepository shareRepository;

    @Autowired
    ClaimAccessService claimAccessService;

    // -------------------------------------------------------------------------
    // Fixture helpers
    // -------------------------------------------------------------------------

    private User makeUser(boolean hasPro) {
        User u = User.builder()
                .email("user-" + System.nanoTime() + "@test.com")
                .name("Test User")
                .subscriptionExpiresAt(hasPro ? FUTURE : null)
                .build();
        return userRepository.save(u);
    }

    private Claim makeClaim(Long userId) {
        Claim c = Claim.builder().userId(userId).build();
        return claimRepository.save(c);
    }

    private Share makeShare(Long ownerUserId, Long claimId, Long viewerUserId,
                            boolean canViewAnalysis, boolean canUploadDocs,
                            boolean accepted, boolean revoked) {
        Share s = new Share();
        s.setOwnerUserId(ownerUserId);
        s.setClaimId(claimId);
        s.setViewerUserId(viewerUserId);
        s.setViewerEmail("viewer-" + System.nanoTime() + "@test.com");
        s.setCanViewAnalysis(canViewAnalysis);
        s.setCanUploadDocs(canUploadDocs);
        s.setAcceptedAt(accepted ? Instant.now().minusSeconds(60) : null);
        s.setRevokedAt(revoked ? Instant.now().minusSeconds(30) : null);
        return shareRepository.save(s);
    }

    // -------------------------------------------------------------------------
    // T1 — resolveIfPresent: no header returns Optional.empty
    // -------------------------------------------------------------------------

    @Test
    void noHeader_returnsEmpty() {
        User user = makeUser(false);
        MockHttpServletRequest req = new MockHttpServletRequest();
        // No VIEW_AS_CLAIM_ID_ATTRIBUTE set on req
        Optional<ClaimAccess> result = claimAccessService.resolveIfPresent(user, req);
        assertThat(result).isEmpty();
    }

    // -------------------------------------------------------------------------
    // T2 — resolveIfPresent: own claim id returns Optional with isOwner=true
    // -------------------------------------------------------------------------

    @Test
    void ownClaimId_returnsOwnerAccess() {
        User user = makeUser(false);
        Claim claim = makeClaim(user.getId());
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setAttribute(SecurityConfig.VIEW_AS_CLAIM_ID_ATTRIBUTE, claim.getId());
        Optional<ClaimAccess> result = claimAccessService.resolveIfPresent(user, req);
        assertThat(result).isPresent();
        assertThat(result.get().isOwner()).isTrue();
        assertThat(result.get().claimId()).isEqualTo(claim.getId());
    }

    // -------------------------------------------------------------------------
    // T3 — resolveIfPresent: accepted share returns Optional with isOwner=false and correct perms
    // -------------------------------------------------------------------------

    @Test
    void acceptedShare_returnsViewerAccess() {
        User owner = makeUser(true);
        User viewer = makeUser(false);
        Claim claim = makeClaim(owner.getId());
        makeShare(owner.getId(), claim.getId(), viewer.getId(),
                /*canViewAnalysis=*/true, /*canUploadDocs=*/false,
                /*accepted=*/true, /*revoked=*/false);
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setAttribute(SecurityConfig.VIEW_AS_CLAIM_ID_ATTRIBUTE, claim.getId());
        Optional<ClaimAccess> result = claimAccessService.resolveIfPresent(viewer, req);
        assertThat(result).isPresent();
        assertThat(result.get().isOwner()).isFalse();
        assertThat(result.get().canViewAnalysis()).isTrue();
        assertThat(result.get().canUploadDocs()).isFalse();
    }

    // -------------------------------------------------------------------------
    // T4 — resolveIfPresent: no share for viewer throws 403
    // -------------------------------------------------------------------------

    @Test
    void noShare_throws403() {
        User owner = makeUser(true);
        User viewer = makeUser(false);
        Claim claim = makeClaim(owner.getId());
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setAttribute(SecurityConfig.VIEW_AS_CLAIM_ID_ATTRIBUTE, claim.getId());
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> claimAccessService.resolveIfPresent(viewer, req));
        assertThat(ex.getStatusCode().value()).isEqualTo(403);
    }

    // -------------------------------------------------------------------------
    // T5 — resolveIfPresent: revoked share throws 403
    // -------------------------------------------------------------------------

    @Test
    void revokedShare_throws403() {
        User owner = makeUser(true);
        User viewer = makeUser(false);
        Claim claim = makeClaim(owner.getId());
        makeShare(owner.getId(), claim.getId(), viewer.getId(),
                true, false, /*accepted=*/true, /*revoked=*/true);
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setAttribute(SecurityConfig.VIEW_AS_CLAIM_ID_ATTRIBUTE, claim.getId());
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> claimAccessService.resolveIfPresent(viewer, req));
        assertThat(ex.getStatusCode().value()).isEqualTo(403);
    }

    // -------------------------------------------------------------------------
    // T6 — resolveIfPresent: non-numeric header sentinel throws 400
    // -------------------------------------------------------------------------

    @Test
    void sentinelMinusOne_throws400() {
        User user = makeUser(false);
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setAttribute(SecurityConfig.VIEW_AS_CLAIM_ID_ATTRIBUTE, -1L);
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> claimAccessService.resolveIfPresent(user, req));
        assertThat(ex.getStatusCode().value()).isEqualTo(400);
        assertThat(ex.getReason()).contains("invalid_view_as_header");
    }

    // -------------------------------------------------------------------------
    // T7 — resolveIfPresent: zero/negative claimId throws 400
    // -------------------------------------------------------------------------

    @Test
    void zeroClaimId_throws400() {
        User user = makeUser(false);
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setAttribute(SecurityConfig.VIEW_AS_CLAIM_ID_ATTRIBUTE, 0L);
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> claimAccessService.resolveIfPresent(user, req));
        assertThat(ex.getStatusCode().value()).isEqualTo(400);
    }
}
