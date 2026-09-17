package com.afterduty.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * A device-bound login secret for native biometric unlock (auth program P1.4 —
 * the B2 device-bound-token contract).
 *
 * <p>Enrolled from an already-authenticated (OTP/passkey) session, this row lets a
 * native app trade a device-held secret — gated behind a fresh biometric prompt on
 * the device — for a fresh app session without re-running OTP. The device holds the
 * plaintext secret in the secure enclave/keystore
 * ({@code @capgo/capacitor-native-biometric}, {@code BIOMETRY_CURRENT_SET}); the
 * server stores ONLY its SHA-256 hash.
 *
 * <p><b>Security invariants (mirrors {@link StepUpToken}/passkey pattern):</b>
 * <ul>
 *   <li><b>Hash at rest only.</b> {@link #deviceSecretHash} is the SHA-256 hex of a
 *       256-bit {@code SecureRandom} secret. The plaintext is returned to the client
 *       ONCE at enroll and never persisted, so a DB read cannot forge an exchange.</li>
 *   <li><b>Constant-time verify.</b> Exchange compares hashes with
 *       {@code MessageDigest.isEqual} — never {@code String.equals}.</li>
 *   <li><b>Revocable.</b> {@link #revokedAt} non-null ⇒ the row can never exchange
 *       again (a revoked device is dead; the user must re-enroll).</li>
 *   <li><b>User-bound.</b> Every exchange mints a session ONLY for {@link #userId}'s
 *       owning uid — never a client-supplied subject.</li>
 * </ul>
 *
 * <p>Portable column types only (identical on H2 tests / Postgres prod); created by
 * {@code ddl-auto: update} in lockstep with
 * {@code V20260704_3__device_credentials.sql}.
 */
@Entity
@Table(
        name = "device_credentials",
        indexes = {
                @Index(name = "idx_device_cred_user", columnList = "user_id"),
                @Index(name = "idx_device_cred_secret_hash", columnList = "device_secret_hash")
        }
)
public class DeviceCredential {

    /** Native platform vocabulary. */
    public static final String PLATFORM_IOS = "ios";
    public static final String PLATFORM_ANDROID = "android";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * Owning app user. Hard-FK-shaped but a soft reference (like the passkey /
     * audit tables) so this migration stays standalone; the app deletes these rows
     * in {@code UserDeletionService}'s FK-safe order.
     */
    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** SHA-256 hex of the 256-bit device secret. Plaintext is never stored. */
    @Column(name = "device_secret_hash", nullable = false, length = 64)
    private String deviceSecretHash;

    /** User-facing device label ("iPhone 15"); nullable. */
    @Column(name = "device_name", length = 120)
    private String deviceName;

    /** ios | android. */
    @Column(name = "platform", nullable = false, length = 16)
    private String platform;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /** Bumped on every successful exchange (manage view surfaces it). */
    @Column(name = "last_used_at")
    private Instant lastUsedAt;

    /** Non-null ⇒ revoked; a revoked row can never exchange again. */
    @Column(name = "revoked_at")
    private Instant revokedAt;

    public DeviceCredential() {
    }

    public Long getId() {
        return id;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public String getDeviceSecretHash() {
        return deviceSecretHash;
    }

    public void setDeviceSecretHash(String deviceSecretHash) {
        this.deviceSecretHash = deviceSecretHash;
    }

    public String getDeviceName() {
        return deviceName;
    }

    public void setDeviceName(String deviceName) {
        this.deviceName = deviceName;
    }

    public String getPlatform() {
        return platform;
    }

    public void setPlatform(String platform) {
        this.platform = platform;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getLastUsedAt() {
        return lastUsedAt;
    }

    public void setLastUsedAt(Instant lastUsedAt) {
        this.lastUsedAt = lastUsedAt;
    }

    public Instant getRevokedAt() {
        return revokedAt;
    }

    public void setRevokedAt(Instant revokedAt) {
        this.revokedAt = revokedAt;
    }
}
