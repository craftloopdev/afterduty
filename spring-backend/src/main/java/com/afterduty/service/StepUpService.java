package com.afterduty.service;

import com.afterduty.config.AuthProperties;
import com.afterduty.exception.StepUpRequiredException;
import com.afterduty.model.AuthAuditLog;
import com.afterduty.model.StepUpToken;
import com.afterduty.model.User;
import com.afterduty.repository.StepUpTokenRepository;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;

/**
 * Mints and validates short-TTL, single-user, single-use step-up tokens, and
 * provides the reusable {@link #requireFresh} guard that sensitive controllers
 * call (auth program P1.2).
 *
 * <p><b>Design:</b> server-side random-token store (not HMAC-signed) — the
 * simpler correct option. A 256-bit random token is generated, its SHA-256 hash
 * is stored bound to the user id with a 300s TTL, and the plaintext is returned
 * ONCE as the {@code stepUpToken}. Callers present it in the {@code X-Step-Up}
 * header; validation is a constant-time hash compare that also checks user
 * binding, expiry, and single-use.
 *
 * <p><b>MFA model (D1):</b> {@link AuthProperties#getMfaModel()} is read in
 * {@link #requireFresh}. Under {@code STRICT} (Model A) a fresh step-up is
 * required for every sensitive action. Under {@code ENROLLMENT_DEVICE_TRUST}
 * (Model B, default) a fresh step-up is required for sensitive actions and new
 * devices; the flag is wired now even though only OTP step-up exists yet.
 */
@Service
public class StepUpService {

    private static final Logger log = LoggerFactory.getLogger(StepUpService.class);

    /** §contract: 300-second TTL. Also the max age of a step-up factor re-proof. */
    public static final int STEP_UP_TTL_SEC = 300;

    /** 32 random bytes = 256 bits of entropy in the opaque token. */
    private static final int TOKEN_BYTES = 32;

    /** Header the guard reads a step-up token from. */
    public static final String STEP_UP_HEADER = "X-Step-Up";

    private static final SecureRandom RNG = new SecureRandom();

    private final StepUpTokenRepository repo;
    private final AuthProperties authProperties;
    private final AuthAuditService audit;

    public StepUpService(StepUpTokenRepository repo,
                         AuthProperties authProperties,
                         AuthAuditService audit) {
        this.repo = repo;
        this.authProperties = authProperties;
        this.audit = audit;
    }

    /** Result of minting a step-up token — the plaintext (returned once) + its TTL. */
    public record MintResult(String stepUpToken, int expiresInSec) {
    }

    // ---- mint ----------------------------------------------------------------

    /**
     * Mint a fresh 300s single-use step-up token bound to {@code userId}. The
     * plaintext is returned once here and never persisted (only its SHA-256 hash
     * is stored). Call ONLY after the factor proof has been verified.
     *
     * @param factor {@link StepUpToken#FACTOR_OTP} (only factor in Phase 1)
     */
    @Transactional
    public MintResult mint(Long userId, String factor) {
        Instant now = Instant.now();

        // Opportunistic sweep of long-dead rows (no scheduler exists).
        repo.deleteExpiredBefore(now.minus(Duration.ofDays(1)));

        String plaintext = generateToken();
        StepUpToken row = new StepUpToken();
        row.setTokenHash(sha256Hex(plaintext));
        row.setUserId(userId);
        row.setFactor(factor);
        row.setCreatedAt(now);
        row.setExpiresAt(now.plus(Duration.ofSeconds(STEP_UP_TTL_SEC)));
        repo.saveAndFlush(row);

        return new MintResult(plaintext, STEP_UP_TTL_SEC);
    }

    // ---- validate + consume --------------------------------------------------

    /**
     * Validate a presented step-up token for {@code userId}: it must exist, be
     * unexpired, unconsumed, and bound to THIS user. On success the token is
     * CONSUMED (single-use — replay-proof) and {@code true} is returned. Any
     * failure returns {@code false} with no distinguishing signal.
     *
     * <p>Constant-time hash compare; user binding is checked so a valid token for
     * user A can never satisfy user B.
     */
    @Transactional
    public boolean validateAndConsume(Long userId, String presentedToken) {
        if (userId == null || presentedToken == null || presentedToken.isBlank()) {
            return false;
        }
        Instant now = Instant.now();

        Optional<StepUpToken> found = repo.findByTokenHash(sha256Hex(presentedToken.trim()));
        if (found.isEmpty()) {
            return false;
        }
        StepUpToken row = found.get();

        // Constant-time compare of the stored hash vs the presented token's hash
        // (defense in depth on top of the indexed lookup — never String.equals).
        byte[] stored = row.getTokenHash().getBytes(StandardCharsets.UTF_8);
        byte[] given = sha256Hex(presentedToken.trim()).getBytes(StandardCharsets.UTF_8);
        if (!MessageDigest.isEqual(stored, given)) {
            return false;
        }

        // User binding: a valid token for another user must NOT satisfy this user.
        if (!userId.equals(row.getUserId())) {
            return false;
        }
        // Expired?
        if (now.isAfter(row.getExpiresAt())) {
            return false;
        }
        // Already used (replay)?
        if (row.getConsumedAt() != null) {
            return false;
        }

        // Consume — single-use is a hard invariant.
        row.setConsumedAt(now);
        repo.saveAndFlush(row);
        return true;
    }

    // ---- reusable guard ------------------------------------------------------

    /**
     * The reusable guard sensitive controllers call. Reads the {@code X-Step-Up}
     * header and, if it is a valid unexpired single-use token for THIS user,
     * returns (consuming it). Otherwise throws {@link StepUpRequiredException}
     * (rendered as {@code 403 {code:"step_up_required", acceptedFactors:["otp"]}}).
     *
     * <p>Under {@code STRICT} (Model A) a fresh step-up is ALWAYS required. Under
     * {@code ENROLLMENT_DEVICE_TRUST} (Model B) a step-up is required for this
     * sensitive action too — device-trust exemptions (skip step-up on a known
     * device) arrive with the passkey/biometric increments (P1.3/P1.4); until
     * then both models require a fresh step-up here, differing only in the audit
     * detail recorded.
     *
     * @throws StepUpRequiredException when no valid {@code X-Step-Up} token is present
     */
    public void requireFresh(User user, HttpServletRequest request) {
        AuthProperties.MfaModel model = authProperties.getMfaModel();
        String presented = request != null ? request.getHeader(STEP_UP_HEADER) : null;

        boolean ok = validateAndConsume(user.getId(), presented);
        if (ok) {
            return;
        }

        // No valid fresh step-up → challenge. Audit the requirement (never the token).
        audit.record(AuthAuditLog.EVENT_STEP_UP_REQUESTED, null,
                AuthAuditLog.OUTCOME_FAILURE, user.getId(), request,
                AuthAuditService.detail().mfaModel(model.name()).reason("no_valid_step_up"));
        throw new StepUpRequiredException();
    }

    // ---- helpers -------------------------------------------------------------

    /** URL-safe-ish opaque token: hex of 32 secure-random bytes (256-bit). */
    static String generateToken() {
        byte[] buf = new byte[TOKEN_BYTES];
        RNG.nextBytes(buf);
        return HexFormat.of().formatHex(buf);
    }

    static String sha256Hex(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
