package com.afterduty.service;

import com.afterduty.config.SecurityConfig;
import com.afterduty.model.Claim;
import com.afterduty.model.Share;
import com.afterduty.model.User;
import com.afterduty.repository.ClaimRepository;
import com.afterduty.repository.ShareRepository;
import com.afterduty.repository.UserRepository;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;

/**
 * Central chokepoint for claim access control in the sharing feature.
 *
 * <p>All endpoints that expose claim data should call {@link #resolve} first
 * to obtain a {@link ClaimAccess}, then call {@link #assertScope} for each
 * operation they intend to perform.
 *
 * <p>Phase A: service + schema. Not yet wired into any controller.
 */
@Service
public class ClaimAccessService {

    private static final Logger log = LoggerFactory.getLogger(ClaimAccessService.class);

    private final ClaimRepository claimRepository;
    private final ShareRepository shareRepository;
    private final UserRepository userRepository;
    private final SubscriptionAccess subscriptionAccess;

    /**
     * Increment 7 (§E.3) rollback lever for the owner-chat-gating change. When
     * {@code true} (default) a claim owner must hold an active Pro subscription to
     * chat — closing the design §4.3a hole where free owners chat for free. Flip to
     * {@code false} (env {@code CHAT_REQUIRE_PRO=false}, no deploy) to restore today's
     * unconditional owner bypass. Has NO effect on the VSO viewer path, which already
     * requires the viewer's own Pro.
     */
    @Value("${va-claim.chat.require-pro:true}")
    boolean chatRequiresPro = true;

    public ClaimAccessService(ClaimRepository claimRepository,
                               ShareRepository shareRepository,
                               UserRepository userRepository,
                               SubscriptionAccess subscriptionAccess) {
        this.claimRepository = claimRepository;
        this.shareRepository = shareRepository;
        this.userRepository = userRepository;
        this.subscriptionAccess = subscriptionAccess;
    }

    /**
     * Shared owner-chat Pro-gate (Increment 7 §E.3). This is the single source of
     * truth for the owner branch of {@code assertScope(CHAT)} so that BOTH chat
     * entry points gate identically:
     *
     * <ul>
     *   <li>the streaming {@code ChatStreamController} (which calls
     *       {@code assertScope(CHAT)} up front), and</li>
     *   <li>the legacy non-streaming {@code POST /api/claim/chat}, whose
     *       {@code IntakeController} own-claim path deliberately skips
     *       {@code assertScope} — closing the bypass via
     *       {@link ChatService#prepareTurn}, which calls this guard.</li>
     * </ul>
     *
     * <p>Without this shared guard a free owner could chat for free by (1) flipping
     * {@code CHAT_STREAMING=false} so the web client falls back to the ungated
     * legacy POST, (2) any pre-delta SSE failure triggering the same fallback, or
     * (3) a direct {@code curl POST /api/claim/chat}. All three now 402 (acceptance
     * §H.4-6). Honors the {@code va-claim.chat.require-pro} rollback flag exactly
     * like the {@code assertScope(CHAT)} owner branch, so flipping the flag restores
     * the old bypass on both endpoints at once.
     *
     * @param owner the claim owner (== the authenticated user on the own-claim path)
     * @throws ResponseStatusException 402 PAYMENT_REQUIRED ({@code subscription_required})
     *                                  when the gate is on and the owner lacks Pro
     */
    public void assertOwnerChatAllowed(User owner) {
        if (chatRequiresPro && !subscriptionAccess.isPro(owner)) {
            log.debug("deny CHAT owner={} reason=owner_pro_required (shared guard)", owner.getId());
            throw new ResponseStatusException(
                    HttpStatus.PAYMENT_REQUIRED, "subscription_required");
        }
    }

    /**
     * Resolves the access level for {@code currentUser} on the claim identified
     * by {@code claimId}.
     *
     * <ol>
     *   <li>If the claim does not exist, throws {@code 404 NOT_FOUND}.</li>
     *   <li>If {@code currentUser} is the claim owner, returns full access
     *       ({@code isOwner=true, canViewAnalysis=true, canUploadDocs=true}).</li>
     *   <li>Otherwise looks for an active (accepted, not revoked) share for the
     *       viewer. If none exists, throws {@code 403 FORBIDDEN}.</li>
     *   <li>Returns a {@link ClaimAccess} reflecting the share's permissions.</li>
     * </ol>
     *
     * @param currentUser the authenticated user making the request
     * @param claimId     the claim being accessed
     * @return resolved {@link ClaimAccess}
     * @throws ResponseStatusException 404 if claim not found; 403 if access denied
     */
    public ClaimAccess resolve(User currentUser, Long claimId) {
        Claim claim = claimRepository.findById(claimId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "claim_not_found"));

        // Owner always has full access — use raw FK to avoid lazy-load hit
        if (claim.getUserId().equals(currentUser.getId())) {
            return new ClaimAccess(claimId, claim.getUserId(), true, true, true);
        }

        // Non-owner: must have an active (accepted + not revoked) share
        Share share = shareRepository
                .findByViewerUserIdAndClaimIdAndAcceptedAtIsNotNullAndRevokedAtIsNull(
                        currentUser.getId(), claimId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.FORBIDDEN, "no_share_access"));

        return new ClaimAccess(
                claimId,
                claim.getUserId(),
                false,
                share.isCanViewAnalysis(),
                share.isCanUploadDocs()
        );
    }

    /**
     * Asserts that {@code currentUser} may perform the operation described by
     * {@code scope} on the claim represented by {@code access}.
     *
     * <ul>
     *   <li>{@code VIEW_DOCS} — always passes (access already resolved).</li>
     *   <li>{@code VIEW_ANALYSIS} — requires {@code canViewAnalysis=true} AND
     *       the claim owner must have an active Pro subscription.</li>
     *   <li>{@code UPLOAD_DOCS} — requires {@code canUploadDocs=true}. ADD-only;
     *       deletion is {@code DELETE_DOCS}.</li>
     *   <li>{@code DELETE_DOCS} — owner-only (P1-19). {@code canUploadDocs} does NOT
     *       grant deletion: a viewer may add evidence, never destroy it → 403 on deny.</li>
     *   <li>{@code CHAT} — owner requires an active Pro subscription (Increment 7
     *       §E.3; gated by {@code va-claim.chat.require-pro}, default true) → 402 on
     *       deny. Non-owner requires {@code canViewAnalysis=true} AND the viewer
     *       ({@code currentUser}) must have an active Pro subscription → 403 on deny.</li>
     * </ul>
     *
     * @param access      resolved access from {@link #resolve}
     * @param scope       the scope being checked
     * @param currentUser the authenticated user making the request
     * @throws ResponseStatusException 402 PAYMENT_REQUIRED if an owner lacks Pro for
     *                                  CHAT; 403 FORBIDDEN if a share-scope check fails
     */
    public void assertScope(ClaimAccess access, AccessScope scope, User currentUser) {
        switch (scope) {
            case VIEW_DOCS:
                // Always allowed once access is resolved
                break;

            case VIEW_ANALYSIS:
                if (!access.canViewAnalysis()) {
                    log.debug("deny VIEW_ANALYSIS claim={} viewer={} reason=share_flag_false",
                            access.claimId(), currentUser.getId());
                    throw new ResponseStatusException(
                            HttpStatus.FORBIDDEN, "view_analysis_not_permitted");
                }
                User owner = loadOwner(access.ownerUserId());
                if (!subscriptionAccess.isPro(owner)) {
                    log.debug("deny VIEW_ANALYSIS claim={} viewer={} reason=owner_pro_inactive",
                            access.claimId(), currentUser.getId());
                    throw new ResponseStatusException(
                            HttpStatus.FORBIDDEN, "owner_subscription_required");
                }
                break;

            case UPLOAD_DOCS:
                if (!access.canUploadDocs()) {
                    log.debug("deny UPLOAD_DOCS claim={} viewer={} reason=share_flag_false",
                            access.claimId(), currentUser.getId());
                    throw new ResponseStatusException(
                            HttpStatus.FORBIDDEN, "upload_docs_not_permitted");
                }
                break;

            case DELETE_DOCS:
                // P1-19 (Phase G1) — deletion is split OUT of the upload scope. A share's
                // canUploadDocs lets a viewer ADD evidence to the owner's claim; it must
                // never let them destroy it (hard delete of the row, its atoms, and the
                // GCS object). No share flag grants deletion — owner only.
                if (!access.isOwner()) {
                    log.debug("deny DELETE_DOCS claim={} viewer={} reason=owner_only",
                            access.claimId(), currentUser.getId());
                    throw new ResponseStatusException(
                            HttpStatus.FORBIDDEN, "delete_docs_owner_only");
                }
                break;

            case CHAT:
                // Increment 7 (§E.3): owner chat is now a paid feature. Previously the
                // owner branch broke out unconditionally — design §4.3a's hole (free
                // owners chatting at our cost). Deny without an active subscription with
                // 402 PAYMENT_REQUIRED (NOT 403): the BFF maps 402 → SubscriptionRequired
                // → paywall, matching IntakeController's /analyze gate. The require-pro
                // flag (default true) is the env-only rollback to the old bypass.
                if (access.isOwner()) {
                    // Delegate to the shared guard so the streaming controller's
                    // assertScope(CHAT) and ChatService.prepareTurn (legacy POST) gate
                    // owners identically. currentUser IS the owner on this branch.
                    assertOwnerChatAllowed(currentUser);
                    break;
                }
                if (!access.canViewAnalysis()) {
                    log.debug("deny CHAT claim={} viewer={} reason=view_analysis_required",
                            access.claimId(), currentUser.getId());
                    throw new ResponseStatusException(
                            HttpStatus.FORBIDDEN, "chat_requires_view_analysis");
                }
                if (!subscriptionAccess.isPro(currentUser)) {
                    log.debug("deny CHAT claim={} viewer={} reason=viewer_pro_required",
                            access.claimId(), currentUser.getId());
                    throw new ResponseStatusException(
                            HttpStatus.FORBIDDEN, "chat_requires_viewer_pro");
                }
                break;

            default:
                // Unknown enum value = programming bug, not an authorization decision.
                // Throwing 403 here would silently mask the missing case as a deny.
                throw new IllegalStateException("Unhandled AccessScope: " + scope);
        }
    }

    /**
     * Resolves the claim access context from the {@code X-View-As} request attribute
     * set by {@link com.afterduty.config.SecurityConfig#authFilter()}.
     *
     * <ul>
     *   <li>If the {@code viewAsClaimId} attribute is absent ({@code null}), returns
     *       {@code Optional.empty()} — the controller should use the caller's own
     *       claim (and may auto-create if none exists).</li>
     *   <li>If the attribute is {@code -1L} (sentinel for a malformed header value)
     *       or any non-positive value, throws {@code 400 BAD_REQUEST}.</li>
     *   <li>Otherwise delegates to {@link #resolve(User, Long)} which throws
     *       {@code 403} or {@code 404} as appropriate.</li>
     * </ul>
     *
     * @param currentUser the authenticated user making the request
     * @param request     the HTTP servlet request carrying the attribute
     * @return {@code Optional.empty()} if the header is absent; a present
     *         {@link ClaimAccess} if it was resolved successfully
     * @throws ResponseStatusException 400 if the header value is malformed;
     *                                  403/404 if access is denied
     */
    public Optional<ClaimAccess> resolveIfPresent(User currentUser, HttpServletRequest request) {
        // Defensive: SecurityConfig is the only writer of this attribute and
        // always sets a Long, but a future filter could collide on the key.
        // Without the instanceof guard, a stray non-Long would surface as a
        // bare ClassCastException 500 with no operational signal.
        Object attr = request.getAttribute(SecurityConfig.VIEW_AS_CLAIM_ID_ATTRIBUTE);
        if (attr != null && !(attr instanceof Long)) {
            log.error("X-View-As request attribute is not a Long (got {}); another filter is colliding on the key",
                    attr.getClass().getName());
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "view_as_attribute_type_mismatch");
        }
        Long viewAsClaimId = (Long) attr;

        if (viewAsClaimId == null) {
            // Header was absent — caller uses its own claim path.
            return Optional.empty();
        }

        if (viewAsClaimId <= 0) {
            // Sentinel -1L (unparseable header) or any non-positive value → 400.
            log.debug("reject X-View-As viewer={} reason=malformed_or_non_positive value={}",
                    currentUser.getId(), viewAsClaimId);
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid_view_as_header");
        }

        // Delegate to resolve() which handles owner vs. share checks.
        return Optional.of(resolve(currentUser, viewAsClaimId));
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private User loadOwner(Long ownerUserId) {
        return userRepository.findById(ownerUserId)
                .orElseThrow(() -> {
                    // FK integrity violation — claim.user_id points at a missing row.
                    // Should not happen given the NOT NULL REFERENCES constraint, but
                    // if it does, the operator needs a breadcrumb.
                    log.error("data integrity: claim owner_user_id={} has no users row",
                            ownerUserId);
                    return new ResponseStatusException(
                            HttpStatus.INTERNAL_SERVER_ERROR, "owner_user_not_found");
                });
    }
}
