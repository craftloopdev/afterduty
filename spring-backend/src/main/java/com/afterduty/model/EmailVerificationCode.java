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
 * A single-use, expiring 6-digit code emailed for passwordless sign-in (or for
 * attaching a verified email to an authenticated account).
 *
 * <p>Ports VolunTails' {@code EmailVerificationCode} SQLAlchemy model to JPA
 * (see {@code docs/architecture/passwordless-otp-auth-spec.md} §3.1).
 *
 * <p>Security model (the protections, in order of importance):
 * <ul>
 *   <li><b>10-min TTL</b> — {@link #expiresAt} = createdAt + 10min; expired ⇒ generic 400.</li>
 *   <li><b>Single-use</b> — {@link #consumedAt} non-null ⇒ burned (consumed or superseded).</li>
 *   <li><b>Per-code attempt cap</b> — {@link #attempts} burned at 5.</li>
 *   <li><b>Hashed at rest</b> — {@link #codeHash} is the SHA-256 hex of the code; the
 *       plaintext code is NEVER stored, logged, or returned (defense in depth).</li>
 * </ul>
 *
 * <p>{@code email} is NOT unique — multiple sequential codes per address are
 * normal; verify selects the latest unconsumed row. {@link #requestIp} backs a
 * DB-side per-IP rate limit that survives Cloud Run's multi-instance / restart
 * model (an in-memory counter would not). Portable column types only, so the
 * table is identical on H2 (tests) and Postgres (prod); created by
 * {@code ddl-auto: update} at startup (no migration needed for a new table; the
 * equivalent DDL is recorded in {@code db/2026-06-20_email_verification_code.sql}
 * for the security reviewer).
 */
@Entity
@Table(
        name = "email_verification_code",
        indexes = {
                @Index(name = "ix_evc_email_created", columnList = "email, created_at"),
                @Index(name = "ix_evc_ip_created", columnList = "request_ip, created_at"),
                @Index(name = "ix_evc_email_consumed", columnList = "email, consumed_at")
        }
)
public class EmailVerificationCode {

    /** Flow discriminator: a SIGN-IN code and an ATTACH code are NOT interchangeable. */
    public static final String PURPOSE_SIGNIN = "SIGNIN";
    public static final String PURPOSE_ATTACH = "ATTACH";
    /**
     * Step-up (auth program P1.2): a code minted to satisfy a fresh factor
     * re-proof for a sensitive action. Fully separate from SIGNIN/ATTACH — a
     * step-up code can never be redeemed to sign in or attach an email, and
     * vice-versa (purpose scoping in {@code EmailCodeService.verifyAndConsume}).
     */
    public static final String PURPOSE_STEPUP = "STEPUP";
    /**
     * Recovery (auth program P1.5): the email half of the dual-channel factor-2
     * recovery flow. A recovery code can never be redeemed to sign in, attach, or
     * step up (and vice-versa) — purpose scoping in
     * {@code EmailCodeService.verifyAndConsume}. Recovery ALSO requires a fresh
     * phone proof; the email code alone never completes recovery.
     */
    public static final String PURPOSE_RECOVERY = "RECOVERY";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Normalized (trimmed + lower-cased) email. Indexed. */
    @Column(nullable = false, length = 320)
    private String email;

    /** SHA-256 hex of the 6-digit code. Plaintext is never stored. */
    @Column(name = "code_hash", nullable = false, length = 64)
    private String codeHash;

    /**
     * {@code SIGNIN} | {@code ATTACH}. NULL allowed for legacy grace (none exist
     * yet, but the column stays nullable to match the proven pattern, so an
     * in-flight code at deploy time isn't invalidated).
     */
    @Column(length = 16)
    private String purpose;

    /** Wrong-guess counter; cap = 5. */
    @Column(nullable = false)
    private int attempts = 0;

    /** createdAt + 10min. */
    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    /** Non-null ⇒ burned (single-use / superseded). */
    @Column(name = "consumed_at")
    private Instant consumedAt;

    /** Client IP for the per-IP hourly cap (IPv6-sized). */
    @Column(name = "request_ip", length = 45)
    private String requestIp;

    /** Mint time; backs the resend cooldown + the hourly windows. */
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    public EmailVerificationCode() {
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getCodeHash() {
        return codeHash;
    }

    public void setCodeHash(String codeHash) {
        this.codeHash = codeHash;
    }

    public String getPurpose() {
        return purpose;
    }

    public void setPurpose(String purpose) {
        this.purpose = purpose;
    }

    public int getAttempts() {
        return attempts;
    }

    public void setAttempts(int attempts) {
        this.attempts = attempts;
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

    public String getRequestIp() {
        return requestIp;
    }

    public void setRequestIp(String requestIp) {
        this.requestIp = requestIp;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
