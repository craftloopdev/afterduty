package com.afterduty.service;

import com.afterduty.exception.ApiException;
import com.afterduty.exception.RecoveryNeedsSupportException;
import com.afterduty.model.AuthAuditLog;
import com.afterduty.model.EmailVerificationCode;
import com.afterduty.model.User;
import com.afterduty.repository.DeviceCredentialRepository;
import com.afterduty.repository.WebAuthnCredentialRepository;
import com.afterduty.service.webauthn.WebAuthnSessionMinter;
import com.afterduty.service.webauthn.WebAuthnUserResolver;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;

/**
 * Dual-channel factor-2 RECOVERY (auth program P1.5 — the pinned C contract).
 *
 * <p>When a veteran can no longer use their passkey / Face ID (lost/replaced
 * device, wiped biometrics), recovery lets them back in by re-proving possession
 * of BOTH original sign-in channels, then clears every saved factor-2 credential
 * so they re-enroll cleanly:
 *
 * <ol>
 *   <li><b>start</b> ({@link #start}) — anti-enumeration: ALWAYS succeeds. For a
 *       known DUAL-channel account it emails a fresh {@code RECOVERY}-purpose code
 *       to the account's email; the phone half is driven client-side (Firebase
 *       Phone Auth) exactly like sign-in/step-up (the backend cannot send SMS).
 *       For an unknown or single-channel account it does nothing — no signal.</li>
 *   <li><b>verify</b> ({@link #verify}) — requires a FRESH verified email code AND
 *       a FRESH phone proof (a Firebase phone ID token whose {@code auth_time} is
 *       within the step-up window and whose uid matches the account). NEITHER
 *       alone suffices. On success it REVOKES ALL of the user's
 *       {@code webauthn_credentials} + {@code device_credentials}, mints a fresh
 *       app session (a Firebase custom token, same shape sign-in returns), and
 *       audits {@code RECOVERY_COMPLETED}.</li>
 * </ol>
 *
 * <p><b>Single-channel legacy accounts</b> (missing an email OR a phone) can never
 * satisfy the dual-channel proof → {@link RecoveryNeedsSupportException}
 * ({@code 409 recovery_needs_support}, the support-hold path). This is only
 * reachable on {@code verify} (after possession is already proven), so it can't be
 * used to enumerate which accounts are single-channel.
 *
 * <p><b>Security:</b> the email code is verified+consumed server-side (constant-time
 * hash, single-use) in the {@code RECOVERY} purpose lane (a recovery code can never
 * be redeemed to sign in / attach / step up, and vice-versa). The phone ID token is
 * verified server-side ({@code verifyIdToken}, project-id bound) AND freshness-gated
 * on {@code auth_time} — a replayed 1-hour session token never satisfies it. No
 * code/token/secret/PHI is logged or written to {@code detail_json}.
 */
@Service
public class RecoveryService {

    private static final Logger log = LoggerFactory.getLogger(RecoveryService.class);

    /**
     * Max age of the phone re-authentication that satisfies recovery — the SAME
     * freshness window step-up uses ({@link StepUpService#STEP_UP_TTL_SEC}). A
     * just-completed phone re-verification advances {@code auth_time}; a stale
     * session token does not, so it cannot be replayed to satisfy recovery.
     */
    static final long PHONE_FRESHNESS_SEC = StepUpService.STEP_UP_TTL_SEC;

    private static final String GENERIC_VERIFY_FAILURE =
            "Recovery failed. Check your email code and try the phone step again.";

    private final WebAuthnUserResolver userResolver;
    private final EmailCodeService emailCodeService;
    private final FirebaseAuthService firebaseAuthService;
    private final WebAuthnSessionMinter sessionMinter;
    private final WebAuthnCredentialRepository webAuthnRepo;
    private final DeviceCredentialRepository deviceRepo;
    private final AuthAuditService audit;

    public RecoveryService(WebAuthnUserResolver userResolver,
                           EmailCodeService emailCodeService,
                           FirebaseAuthService firebaseAuthService,
                           WebAuthnSessionMinter sessionMinter,
                           WebAuthnCredentialRepository webAuthnRepo,
                           DeviceCredentialRepository deviceRepo,
                           AuthAuditService audit) {
        this.userResolver = userResolver;
        this.emailCodeService = emailCodeService;
        this.firebaseAuthService = firebaseAuthService;
        this.sessionMinter = sessionMinter;
        this.webAuthnRepo = webAuthnRepo;
        this.deviceRepo = deviceRepo;
        this.audit = audit;
    }

    /** Result of a completed recovery — the minted session token + owning user id. */
    public record RecoverResult(String customToken, Long userId) {
    }

    // ---- start (anti-enumeration; always "succeeds") -------------------------

    /**
     * Begin recovery for {@code identifier} (an email or an E.164 phone). ALWAYS
     * returns normally so the caller can return a flat {@code 200} — the response
     * never reveals whether the account exists or is dual-channel. For a known
     * DUAL-channel account, emails a fresh {@code RECOVERY} code to the account's
     * email (the phone half is client-driven). Any send/rate-limit failure is
     * swallowed (logged, not surfaced) so timing/shape stay uniform.
     */
    public void start(String identifier, String ip, HttpServletRequest request) {
        Optional<User> found = userResolver.resolve(identifier);
        if (found.isEmpty()) {
            // Unknown identifier — do nothing, but keep the response uniform.
            return;
        }
        User user = found.get();

        // Only a genuine dual-channel account can complete recovery; for anyone
        // else we stay silent here (verify is where the 409 support-hold lives,
        // and only after possession is proven).
        AccountChannels ch = channelsFor(user);
        if (!ch.dualChannel()) {
            return;
        }

        try {
            // Email the RECOVERY-purpose code. mintAndSend never resolves a User
            // (no timing oracle) and normalizes nothing here — the resolved email
            // is already the account's own normalized address.
            emailCodeService.mintAndSend(ch.email(), ip,
                    "Your After Duty recovery code",
                    EmailVerificationCode.PURPOSE_RECOVERY, false);
            audit.record(AuthAuditLog.EVENT_RECOVERY_STARTED, AuthAuditLog.CHANNEL_EMAIL,
                    AuthAuditLog.OUTCOME_SUCCESS, user.getId(), request,
                    AuthAuditService.detail().purpose("recovery"));
        } catch (RuntimeException e) {
            // Rate-limit / send failure must NOT change the observable outcome
            // (anti-enumeration): swallow it. No code/secret is logged.
            log.warn("recovery start email send skipped (swallowed for anti-enumeration)");
        }
    }

    // ---- verify (dual-channel: fresh email code AND fresh phone proof) -------

    /**
     * Complete recovery: require a FRESH verified email code AND a FRESH phone
     * proof for the SAME account — neither alone suffices. On success revoke ALL
     * of the user's passkeys + device credentials, mint a fresh session, and audit
     * {@code RECOVERY_COMPLETED}.
     *
     * @param email        the account email (normalized upstream by the controller)
     * @param emailCode    the 6-digit code emailed by {@link #start}
     * @param phoneIdToken a fresh Firebase phone-verify ID token (auth_time-fresh)
     * @throws RecoveryNeedsSupportException single-channel legacy account (409)
     * @throws ApiException                  generic 400 on any factor failure
     */
    @Transactional
    public RecoverResult verify(String email, String emailCode, String phoneIdToken,
                                HttpServletRequest request) {
        // Resolve the account by its email. An unknown email fails generically
        // (same 400 as a wrong code) — recovery never confirms account existence.
        Optional<User> found = userResolver.resolve(email);
        if (found.isEmpty()) {
            audit.record(AuthAuditLog.EVENT_RECOVERY_FAILED, null,
                    AuthAuditLog.OUTCOME_FAILURE, null, request,
                    AuthAuditService.detail().purpose("recovery").reason("no_account"));
            throw ApiException.badRequest(GENERIC_VERIFY_FAILURE);
        }
        User user = found.get();
        AccountChannels ch = channelsFor(user);

        // FACTOR 1 — the fresh email code (RECOVERY purpose lane; single-use;
        // constant-time hash). verifyAndConsume throws a generic 400 on any
        // failure, so a wrong/expired code fails identically. This is proven
        // FIRST — before the single-channel branch below — so the distinguishable
        // 409 support-hold response can never leak to a caller who has NOT proven
        // control of the account's email channel (anti-enumeration: without a
        // fresh code every path here is the same generic 400).
        //
        // A single-channel account's email is the app User row's email; verify the
        // code against that address even when the phone channel is absent, so the
        // support-hold path is reachable ONLY after possession of the email is
        // proven.
        String recoveryEmail = ch.email() != null ? ch.email() : user.getEmail();
        try {
            emailCodeService.verifyAndConsume(recoveryEmail, emailCode,
                    EmailVerificationCode.PURPOSE_RECOVERY);
        } catch (ApiException e) {
            audit.record(AuthAuditLog.EVENT_RECOVERY_FAILED, AuthAuditLog.CHANNEL_EMAIL,
                    AuthAuditLog.OUTCOME_FAILURE, user.getId(), request,
                    AuthAuditService.detail().purpose("recovery").reason("bad_email_code"));
            throw ApiException.badRequest(GENERIC_VERIFY_FAILURE);
        }

        // Single-channel legacy accounts can never satisfy the dual-channel proof
        // → support-hold. Reached ONLY after the fresh email code above is proven,
        // so the distinguishable 409 cannot be used to enumerate which accounts are
        // single-channel from an unauthenticated caller (they'd get a generic 400
        // at the email-code step without a valid code).
        if (!ch.dualChannel()) {
            audit.record(AuthAuditLog.EVENT_RECOVERY_FAILED, null,
                    AuthAuditLog.OUTCOME_FAILURE, user.getId(), request,
                    AuthAuditService.detail().purpose("recovery").reason("single_channel"));
            throw new RecoveryNeedsSupportException();
        }

        // FACTOR 2 — the fresh phone proof. The ID token must verify, be FRESH
        // (auth_time within the window — a replayed session token is rejected),
        // AND belong to THIS account's uid. The email code alone never got here,
        // and this alone can't either.
        String uid = firebaseAuthService.verifyFreshPhoneUid(phoneIdToken, PHONE_FRESHNESS_SEC);
        boolean phoneOk = uid != null
                && user.getFirebaseUid() != null
                && uid.equals(user.getFirebaseUid());
        if (!phoneOk) {
            audit.record(AuthAuditLog.EVENT_RECOVERY_FAILED, AuthAuditLog.CHANNEL_PHONE,
                    AuthAuditLog.OUTCOME_FAILURE, user.getId(), request,
                    AuthAuditService.detail().purpose("recovery").reason("bad_phone_proof"));
            throw ApiException.badRequest(GENERIC_VERIFY_FAILURE);
        }

        // BOTH factors proven → clear every saved factor-2 credential so the
        // veteran re-enrolls fresh next time. Passkeys are HARD-deleted (public
        // material, no revoked_at); device credentials are SOFT-revoked.
        int passkeysRevoked = webAuthnRepo.deleteByUserId(user.getId());
        int devicesRevoked = deviceRepo.revokeAllForUser(user.getId(), Instant.now());

        // Mint the app session for the OWNING uid ONLY (never a client-supplied
        // subject) — same custom-token shape sign-in / passkey / device return.
        String customToken = sessionMinter.mintCustomToken(user.getFirebaseUid());

        audit.record(AuthAuditLog.EVENT_RECOVERY_COMPLETED, null,
                AuthAuditLog.OUTCOME_SUCCESS, user.getId(), request,
                AuthAuditService.detail().purpose("recovery"));
        log.info("recovery completed for user {} (passkeys removed={}, devices revoked={})",
                user.getId(), passkeysRevoked, devicesRevoked);

        return new RecoverResult(customToken, user.getId());
    }

    // ---- account channel resolution ------------------------------------------

    /**
     * The account's two possession channels. An account is DUAL-channel iff it has
     * both a non-blank email AND a non-blank phone. The email lives on the app
     * {@link User} row; the phone lives ONLY on the Firebase {@code UserRecord},
     * read via {@link FirebaseAuthService#getPhoneNumberForUid} (project-id bound).
     * A phone lookup failure ⇒ no phone ⇒ single-channel (fails safe — never opens
     * recovery for an account we can't fully verify).
     */
    record AccountChannels(String email, String phone) {
        boolean dualChannel() {
            return email != null && !email.isBlank() && phone != null && !phone.isBlank();
        }
    }

    private AccountChannels channelsFor(User user) {
        String phone = firebaseAuthService.getPhoneNumberForUid(user.getFirebaseUid());
        return new AccountChannels(user.getEmail(), phone);
    }
}
