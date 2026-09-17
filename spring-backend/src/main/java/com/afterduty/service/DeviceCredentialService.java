package com.afterduty.service;

import com.afterduty.model.DeviceCredential;
import com.afterduty.model.User;
import com.afterduty.repository.DeviceCredentialRepository;
import com.afterduty.repository.UserRepository;
import com.afterduty.service.webauthn.WebAuthnSessionMinter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

/**
 * Enroll / exchange / list / revoke of device-bound login secrets for native
 * biometric unlock (auth program P1.4 — the B2 device-bound-token contract).
 *
 * <p><b>Design mirrors {@link StepUpService}:</b> a 256-bit {@code SecureRandom}
 * secret is generated at enroll, its SHA-256 hash is stored bound to the user id,
 * and the plaintext is returned ONCE (base64url) as the {@code deviceSecret}. The
 * device holds it in the secure enclave gated behind a fresh biometric prompt;
 * exchange presents it, and validation is a CONSTANT-TIME hash compare that also
 * checks the row is not revoked. A successful exchange mints a Firebase custom
 * token (the same shape the email-code / passkey verify returns) via the shared
 * {@link WebAuthnSessionMinter} — for the credential's OWNING uid ONLY.
 *
 * <p>This service owns the crypto and the row lifecycle; the controller owns the
 * HTTP wiring, the {@code Bearer}/step-up gating, and the audit calls.
 */
@Service
public class DeviceCredentialService {

    private static final Logger log = LoggerFactory.getLogger(DeviceCredentialService.class);

    /** 32 random bytes = 256 bits of entropy in the device secret. */
    private static final int SECRET_BYTES = 32;

    private static final SecureRandom RNG = new SecureRandom();
    private static final Base64.Encoder B64URL = Base64.getUrlEncoder().withoutPadding();

    private final DeviceCredentialRepository repo;
    private final UserRepository userRepository;
    private final WebAuthnSessionMinter sessionMinter;

    public DeviceCredentialService(DeviceCredentialRepository repo,
                                   UserRepository userRepository,
                                   WebAuthnSessionMinter sessionMinter) {
        this.repo = repo;
        this.userRepository = userRepository;
        this.sessionMinter = sessionMinter;
    }

    /** Enroll result — the plaintext device secret (returned ONCE) + the new row id. */
    public record EnrollResult(String deviceSecret, Long deviceCredentialId) {
    }

    /** A device credential summary for the manage list (never exposes the hash). */
    public record CredentialSummary(Long id, String deviceName, String platform,
                                    Instant createdAt, Instant lastUsedAt) {
    }

    // ---- enroll --------------------------------------------------------------

    /**
     * Mint a fresh 256-bit device secret for {@code user}, store ONLY its SHA-256
     * hash bound to the user, and return the plaintext ONCE (base64url). Call only
     * from an authenticated (OTP/passkey) session.
     *
     * @param platform normalized to {@code ios} | {@code android}
     */
    @Transactional
    public EnrollResult enroll(User user, String deviceName, String platform) {
        String plaintext = generateSecret();

        DeviceCredential row = new DeviceCredential();
        row.setUserId(user.getId());
        row.setDeviceSecretHash(sha256Hex(plaintext));
        row.setDeviceName(cleanDeviceName(deviceName));
        row.setPlatform(normalizePlatform(platform));
        row.setCreatedAt(Instant.now());
        DeviceCredential saved = repo.saveAndFlush(row);

        return new EnrollResult(plaintext, saved.getId());
    }

    // ---- exchange ------------------------------------------------------------

    /** Result of a successful exchange — the minted custom token + owning user id. */
    public record ExchangeResult(String customToken, Long userId) {
    }

    /**
     * Validate a presented {@code deviceSecret} against the row identified by
     * {@code deviceCredentialId}, and on success mint a Firebase custom token for
     * the row's OWNING uid and bump {@code last_used_at}.
     *
     * <p>Every failure path returns {@link Optional#empty()} with no distinguishing
     * signal (the controller maps that to a generic failure + audit). A revoked row
     * never exchanges. A {@code deviceCredentialId} that belongs to another user's
     * secret cannot succeed: the presented secret must hash to the row's stored hash
     * (constant-time compare), and a secret only exists on its owning device.
     */
    @Transactional
    public Optional<ExchangeResult> exchange(Long deviceCredentialId, String deviceSecret) {
        if (deviceCredentialId == null || deviceSecret == null || deviceSecret.isBlank()) {
            return Optional.empty();
        }

        Optional<DeviceCredential> found = repo.findById(deviceCredentialId);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        DeviceCredential row = found.get();

        // Revoked rows never exchange.
        if (row.getRevokedAt() != null) {
            return Optional.empty();
        }

        // Constant-time compare of the stored hash vs the presented secret's hash —
        // never String.equals. A mismatched (or cross-credential) secret fails here.
        byte[] stored = row.getDeviceSecretHash().getBytes(StandardCharsets.UTF_8);
        byte[] given = sha256Hex(deviceSecret.trim()).getBytes(StandardCharsets.UTF_8);
        if (!MessageDigest.isEqual(stored, given)) {
            return Optional.empty();
        }

        // Resolve the OWNING user's uid — never a client-supplied subject.
        User owner = userRepository.findById(row.getUserId()).orElse(null);
        if (owner == null || owner.getFirebaseUid() == null || owner.getFirebaseUid().isBlank()) {
            return Optional.empty();
        }

        String customToken = sessionMinter.mintCustomToken(owner.getFirebaseUid());

        row.setLastUsedAt(Instant.now());
        repo.saveAndFlush(row);

        return Optional.of(new ExchangeResult(customToken, owner.getId()));
    }

    // ---- manage --------------------------------------------------------------

    /** A user's LIVE (non-revoked) device credentials, for the manage list. */
    @Transactional(readOnly = true)
    public List<CredentialSummary> listActive(Long userId) {
        return repo.findByUserIdAndRevokedAtIsNull(userId).stream()
                .map(c -> new CredentialSummary(c.getId(), c.getDeviceName(), c.getPlatform(),
                        c.getCreatedAt(), c.getLastUsedAt()))
                .toList();
    }

    /**
     * Revoke one of {@code userId}'s device credentials by row id (ownership-scoped).
     * Idempotent: an already-revoked row keeps its original {@code revoked_at}.
     *
     * @return the revoked row if it existed and belonged to the user; empty otherwise
     */
    @Transactional
    public Optional<DeviceCredential> revoke(Long id, Long userId) {
        Optional<DeviceCredential> found = repo.findByIdAndUserId(id, userId);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        DeviceCredential row = found.get();
        if (row.getRevokedAt() == null) {
            row.setRevokedAt(Instant.now());
            repo.saveAndFlush(row);
        }
        return Optional.of(row);
    }

    // ---- helpers -------------------------------------------------------------

    /** Normalize the client platform to the {@code ios}|{@code android} vocabulary. */
    static String normalizePlatform(String platform) {
        if (platform == null) return DeviceCredential.PLATFORM_IOS;
        String p = platform.trim().toLowerCase();
        return DeviceCredential.PLATFORM_ANDROID.equals(p)
                ? DeviceCredential.PLATFORM_ANDROID
                : DeviceCredential.PLATFORM_IOS;
    }

    /** Trim + clamp the device name; blank ⇒ null. */
    static String cleanDeviceName(String name) {
        if (name == null) return null;
        String n = name.strip();
        if (n.isEmpty()) return null;
        return n.length() > 120 ? n.substring(0, 120) : n;
    }

    /** URL-safe (base64url, unpadded) 32-byte secure-random secret (256-bit). */
    static String generateSecret() {
        byte[] buf = new byte[SECRET_BYTES];
        RNG.nextBytes(buf);
        return B64URL.encodeToString(buf);
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
