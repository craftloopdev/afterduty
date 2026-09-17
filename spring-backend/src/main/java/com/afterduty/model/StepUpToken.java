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
 * A short-TTL (300s), single-user, single-use step-up token (auth program P1.2).
 *
 * <p>Server-side random-token store (NOT HMAC-signed) — the simpler correct
 * option given the app already owns Cloud SQL and the proven hash-at-rest /
 * per-row consume pattern. Only the <b>SHA-256 hash</b> of the opaque token is
 * stored ({@link #tokenHash}); the plaintext is returned to the client once (as
 * the {@code stepUpToken}) and never persisted, so a DB read cannot forge a valid
 * {@code X-Step-Up} header.
 *
 * <p>Security model:
 * <ul>
 *   <li><b>Bound to a user</b> — {@link #userId}; a token is valid only for the
 *       user it was minted for (validated in {@code StepUpService}).</li>
 *   <li><b>300s TTL</b> — {@link #expiresAt} = createdAt + 300s.</li>
 *   <li><b>Single-use</b> — {@link #consumedAt} non-null ⇒ burned (replay-proof).</li>
 *   <li><b>Hashed at rest</b> — {@link #tokenHash} is SHA-256 hex; validation is a
 *       constant-time compare.</li>
 * </ul>
 *
 * <p>Portable column types only (identical on H2 tests / Postgres prod); created
 * by {@code ddl-auto: update} in lockstep with
 * {@code V20260704_1__auth_audit_and_stepup.sql}.
 */
@Entity
@Table(
        name = "step_up_tokens",
        indexes = {
                @Index(name = "idx_step_up_token_hash", columnList = "token_hash"),
                @Index(name = "idx_step_up_token_expires", columnList = "expires_at")
        }
)
public class StepUpToken {

    /** The only step-up factor in Phase 1. */
    public static final String FACTOR_OTP = "otp";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** SHA-256 hex of the opaque token. Plaintext is never stored. */
    @Column(name = "token_hash", nullable = false, length = 64)
    private String tokenHash;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** otp (passkey/biometric added later). */
    @Column(nullable = false, length = 16)
    private String factor;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /** createdAt + 300s. */
    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    /** Non-null ⇒ burned (single-use). */
    @Column(name = "consumed_at")
    private Instant consumedAt;

    public StepUpToken() {
    }

    public Long getId() {
        return id;
    }

    public String getTokenHash() {
        return tokenHash;
    }

    public void setTokenHash(String tokenHash) {
        this.tokenHash = tokenHash;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public String getFactor() {
        return factor;
    }

    public void setFactor(String factor) {
        this.factor = factor;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(Instant expiresAt) {
        this.expiresAt = expiresAt;
    }

    public Instant getConsumedAt() {
        return consumedAt;
    }

    public void setConsumedAt(Instant consumedAt) {
        this.consumedAt = consumedAt;
    }
}
