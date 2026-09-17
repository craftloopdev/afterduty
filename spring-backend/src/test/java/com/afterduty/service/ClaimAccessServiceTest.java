package com.afterduty.service;

import com.afterduty.model.Claim;
import com.afterduty.model.Share;
import com.afterduty.model.User;
import com.afterduty.repository.ClaimRepository;
import com.afterduty.repository.ShareRepository;
import com.afterduty.repository.UserRepository;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.web.server.ResponseStatusException;

import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

@DataJpaTest
@Import({ClaimAccessService.class, SubscriptionAccess.class, com.afterduty.config.SubscriptionProperties.class})
@Tag("regression")
class ClaimAccessServiceTest {

    private static final Instant FUTURE = Instant.now().plus(365, ChronoUnit.DAYS);
    private static final Instant PAST   = Instant.now().minus(1, ChronoUnit.DAYS);

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
        s.setRevokedAt(revoked  ? Instant.now().minusSeconds(30) : null);
        return shareRepository.save(s);
    }

    // -------------------------------------------------------------------------
    // T1 — Owner has full access regardless of subscription
    // -------------------------------------------------------------------------

    @Test
    void ownerFullAccessRegardlessOfSubscription() {
        User owner = makeUser(false); // free tier — no Pro
        Claim claim = makeClaim(owner.getId());

        ClaimAccess access = claimAccessService.resolve(owner, claim.getId());

        assertThat(access.isOwner()).isTrue();
        assertThat(access.canViewAnalysis()).isTrue();
        assertThat(access.canUploadDocs()).isTrue();
        assertThat(access.claimId()).isEqualTo(claim.getId());
        assertThat(access.ownerUserId()).isEqualTo(owner.getId());
    }

    // -------------------------------------------------------------------------
    // T2 — Increment 7 §E.3: owner CHAT now requires an active Pro subscription.
    //   Free owner ⇒ 402 subscription_required (was: unconditional bypass).
    //   Owner with Pro ⇒ pass.
    //   require-pro=false ⇒ legacy bypass restored.
    // -------------------------------------------------------------------------

    @Test
    void ownerChatDenied402_whenNoActiveSubscription() {
        User owner = makeUser(false); // free tier — the closed hole
        Claim claim = makeClaim(owner.getId());
        ClaimAccess access = claimAccessService.resolve(owner, claim.getId());

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> claimAccessService.assertScope(access, AccessScope.CHAT, owner));
        assertThat(ex.getStatusCode().value()).isEqualTo(402);
        assertThat(ex.getReason()).isEqualTo("subscription_required");
    }

    @Test
    void ownerChatAllowed_whenActiveSubscription() {
        User owner = makeUser(true); // Pro
        Claim claim = makeClaim(owner.getId());
        ClaimAccess access = claimAccessService.resolve(owner, claim.getId());

        assertDoesNotThrow(() ->
                claimAccessService.assertScope(access, AccessScope.CHAT, owner));
    }

    @Test
    void ownerChatBypass_whenRequireProFlagOff() {
        User owner = makeUser(false); // free tier
        Claim claim = makeClaim(owner.getId());
        ClaimAccess access = claimAccessService.resolve(owner, claim.getId());

        // Rollback lever: flag off restores the pre-Increment-7 unconditional owner bypass.
        claimAccessService.chatRequiresPro = false;
        try {
            assertDoesNotThrow(() ->
                    claimAccessService.assertScope(access, AccessScope.CHAT, owner));
        } finally {
            claimAccessService.chatRequiresPro = true;
        }
    }

    // -------------------------------------------------------------------------
    // T2b — Increment 7 §E.3 shared owner-chat guard (adversarial-review critical).
    //   assertOwnerChatAllowed is the single source of truth invoked by BOTH the
    //   streaming controller's assertScope(CHAT) AND ChatService.prepareTurn (which
    //   closes the legacy POST /api/claim/chat bypass). Tested directly so the guard
    //   keeps its 402 + flag semantics even if assertScope is later refactored.
    // -------------------------------------------------------------------------

    @Test
    void assertOwnerChatAllowed_freeOwner_throws402() {
        User owner = makeUser(false);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> claimAccessService.assertOwnerChatAllowed(owner));
        assertThat(ex.getStatusCode().value()).isEqualTo(402);
        assertThat(ex.getReason()).isEqualTo("subscription_required");
    }

    @Test
    void assertOwnerChatAllowed_proOwner_passes() {
        User owner = makeUser(true);
        assertDoesNotThrow(() -> claimAccessService.assertOwnerChatAllowed(owner));
    }

    @Test
    void assertOwnerChatAllowed_freeOwner_bypassWhenFlagOff() {
        User owner = makeUser(false);
        claimAccessService.chatRequiresPro = false;
        try {
            assertDoesNotThrow(() -> claimAccessService.assertOwnerChatAllowed(owner));
        } finally {
            claimAccessService.chatRequiresPro = true;
        }
    }

    // -------------------------------------------------------------------------
    // T3 — Viewer with accepted share and owner Pro can VIEW_ANALYSIS
    // -------------------------------------------------------------------------

    @Test
    void viewerAcceptedShareOwnerPro_viewAnalysisAllowed() {
        User owner = makeUser(true); // Pro
        User viewer = makeUser(false);
        Claim claim = makeClaim(owner.getId());
        makeShare(owner.getId(), claim.getId(), viewer.getId(),
                /*canViewAnalysis=*/true, /*canUploadDocs=*/false,
                /*accepted=*/true, /*revoked=*/false);

        ClaimAccess access = claimAccessService.resolve(viewer, claim.getId());
        assertThat(access.isOwner()).isFalse();
        assertThat(access.canViewAnalysis()).isTrue();

        assertDoesNotThrow(() ->
                claimAccessService.assertScope(access, AccessScope.VIEW_ANALYSIS, viewer));
    }

    // -------------------------------------------------------------------------
    // T4 — Viewer VIEW_ANALYSIS blocked when owner Pro lapsed
    // -------------------------------------------------------------------------

    @Test
    void viewerViewAnalysisBlocked_whenOwnerProLapsed() {
        User owner = makeUser(false); // Pro lapsed / never had Pro
        User viewer = makeUser(false);
        Claim claim = makeClaim(owner.getId());
        makeShare(owner.getId(), claim.getId(), viewer.getId(),
                true, false, true, false);

        ClaimAccess access = claimAccessService.resolve(viewer, claim.getId());

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> claimAccessService.assertScope(access, AccessScope.VIEW_ANALYSIS, viewer));
        assertThat(ex.getStatusCode().value()).isEqualTo(403);
    }

    // -------------------------------------------------------------------------
    // T5 — Viewer with no share gets 403 from resolve()
    // -------------------------------------------------------------------------

    @Test
    void viewerNoShare_resolveThrows403() {
        User owner = makeUser(true);
        User viewer = makeUser(false);
        Claim claim = makeClaim(owner.getId());
        // No share created

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> claimAccessService.resolve(viewer, claim.getId()));
        assertThat(ex.getStatusCode().value()).isEqualTo(403);
    }

    // -------------------------------------------------------------------------
    // T6 — Viewer with revoked share gets 403 from resolve()
    // -------------------------------------------------------------------------

    @Test
    void viewerRevokedShare_resolveThrows403() {
        User owner = makeUser(true);
        User viewer = makeUser(false);
        Claim claim = makeClaim(owner.getId());
        makeShare(owner.getId(), claim.getId(), viewer.getId(),
                true, false, /*accepted=*/true, /*revoked=*/true);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> claimAccessService.resolve(viewer, claim.getId()));
        assertThat(ex.getStatusCode().value()).isEqualTo(403);
    }

    // -------------------------------------------------------------------------
    // T7 — Viewer with unaccepted (expired) invitation gets 403 from resolve()
    // -------------------------------------------------------------------------

    @Test
    void viewerUnacceptedInvitation_resolveThrows403() {
        User owner = makeUser(true);
        User viewer = makeUser(false);
        Claim claim = makeClaim(owner.getId());
        makeShare(owner.getId(), claim.getId(), viewer.getId(),
                true, false, /*accepted=*/false, /*revoked=*/false);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> claimAccessService.resolve(viewer, claim.getId()));
        assertThat(ex.getStatusCode().value()).isEqualTo(403);
    }

    // -------------------------------------------------------------------------
    // T8 — CHAT scope: viewer own Pro + can_view_analysis=true passes
    // -------------------------------------------------------------------------

    @Test
    void viewerChatAllowed_whenOwnProAndCanViewAnalysis() {
        User owner = makeUser(true); // owner Pro (needed to resolve VIEW_ANALYSIS)
        User viewer = makeUser(true); // viewer Pro
        Claim claim = makeClaim(owner.getId());
        makeShare(owner.getId(), claim.getId(), viewer.getId(),
                true, false, true, false);

        ClaimAccess access = claimAccessService.resolve(viewer, claim.getId());

        assertDoesNotThrow(() ->
                claimAccessService.assertScope(access, AccessScope.CHAT, viewer));
    }

    // -------------------------------------------------------------------------
    // T9 — CHAT denied: viewer has analysis access but no own Pro
    // -------------------------------------------------------------------------

    @Test
    void viewerChatDenied_whenNoOwnPro() {
        User owner = makeUser(true);
        User viewer = makeUser(false); // no Pro
        Claim claim = makeClaim(owner.getId());
        makeShare(owner.getId(), claim.getId(), viewer.getId(),
                true, false, true, false);

        ClaimAccess access = claimAccessService.resolve(viewer, claim.getId());

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> claimAccessService.assertScope(access, AccessScope.CHAT, viewer));
        assertThat(ex.getStatusCode().value()).isEqualTo(403);
    }

    // -------------------------------------------------------------------------
    // T10 — CHAT denied: viewer has own Pro but can_view_analysis=false
    // -------------------------------------------------------------------------

    @Test
    void viewerChatDenied_whenCanViewAnalysisFalse() {
        User owner = makeUser(true);
        User viewer = makeUser(true); // has Pro
        Claim claim = makeClaim(owner.getId());
        makeShare(owner.getId(), claim.getId(), viewer.getId(),
                /*canViewAnalysis=*/false, false, true, false);

        ClaimAccess access = claimAccessService.resolve(viewer, claim.getId());

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> claimAccessService.assertScope(access, AccessScope.CHAT, viewer));
        assertThat(ex.getStatusCode().value()).isEqualTo(403);
    }

    // -------------------------------------------------------------------------
    // T11a — UPLOAD_DOCS denied when can_upload_docs=false
    // -------------------------------------------------------------------------

    @Test
    void uploadDocsDenied_whenCanUploadDocsFalse() {
        User owner = makeUser(false);
        User viewer = makeUser(false);
        Claim claim = makeClaim(owner.getId());
        makeShare(owner.getId(), claim.getId(), viewer.getId(),
                false, /*canUploadDocs=*/false, true, false);

        ClaimAccess access = claimAccessService.resolve(viewer, claim.getId());

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> claimAccessService.assertScope(access, AccessScope.UPLOAD_DOCS, viewer));
        assertThat(ex.getStatusCode().value()).isEqualTo(403);
    }

    // -------------------------------------------------------------------------
    // T11b — UPLOAD_DOCS allowed when can_upload_docs=true
    // -------------------------------------------------------------------------

    @Test
    void uploadDocsAllowed_whenCanUploadDocsTrue() {
        User owner = makeUser(false);
        User viewer = makeUser(false); // no Pro required
        Claim claim = makeClaim(owner.getId());
        makeShare(owner.getId(), claim.getId(), viewer.getId(),
                false, /*canUploadDocs=*/true, true, false);

        ClaimAccess access = claimAccessService.resolve(viewer, claim.getId());

        assertDoesNotThrow(() ->
                claimAccessService.assertScope(access, AccessScope.UPLOAD_DOCS, viewer));
    }

    // -------------------------------------------------------------------------
    // T12 — Migration SQL file exists and contains required table definitions
    // -------------------------------------------------------------------------

    @Test
    void migrationSqlFile_existsAndContainsRequiredDDL() throws Exception {
        // Locate the file relative to the Gradle working directory (spring-backend/)
        Path sqlFile = Path.of("src/main/resources/db/migration/V20260516__shares.sql");
        assertThat(Files.exists(sqlFile))
                .as("V20260516__shares.sql must exist")
                .isTrue();

        String content = Files.readString(sqlFile);
        assertThat(content).contains("CREATE TABLE shares");
        assertThat(content).contains("CREATE TABLE chat_threads");
        assertThat(content).contains("thread_id");
        // Partial/functional indexes are Postgres-only; the file must document this
        assertThat(content).containsIgnoringCase("Postgres");
    }
}
