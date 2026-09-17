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
 * A short-TTL (300s), single-use server-side record of an in-flight WebAuthn
 * ceremony (auth program P1.3).
 *
 * <p>The finish step ({@code register/verify}, {@code assert/verify}) MUST be
 * bound to the exact options the server issued in the options step. Rather than
 * store just the raw challenge, we persist the WHOLE serialized options object
 * ({@link #requestJson}) — {@code PublicKeyCredentialCreationOptions} JSON for
 * register, the {@code AssertionRequest} JSON for assert — because the Yubico
 * verifier needs the original request to verify against (challenge, rpId, UV,
 * allowCredentials, etc.).
 *
 * <p>Security model:
 * <ul>
 *   <li><b>Single-use</b> — the row is DELETED on consume; a replayed finish
 *       finds nothing.</li>
 *   <li><b>300s TTL</b> — {@link #expiresAt} = createdAt + 300s.</li>
 *   <li><b>Bound to a user (or NULL for the decoy)</b> — {@link #userId} is the
 *       resolved user for register + a real assert; NULL for the anti-enumeration
 *       decoy assert (an unknown identifier still returns valid options).</li>
 * </ul>
 *
 * <p>Portable column types only; created by {@code ddl-auto: update} in lockstep
 * with {@code V20260704_2__webauthn_credentials.sql}.
 */
@Entity
@Table(
        name = "webauthn_challenges",
        indexes = {
                @Index(name = "uq_webauthn_challenge", columnList = "challenge", unique = true),
                @Index(name = "idx_webauthn_challenge_expires", columnList = "expires_at")
        }
)
public class WebAuthnChallenge {

    public static final String CEREMONY_REGISTER = "register";
    public static final String CEREMONY_ASSERT = "assert";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** The base64url challenge — the single-use lookup key for the finish step. */
    @Column(name = "challenge", nullable = false, length = 255)
    private String challenge;

    /** register | assert. */
    @Column(name = "ceremony", nullable = false, length = 16)
    private String ceremony;

    /** Resolved user; NULL for the anti-enumeration decoy assert. */
    @Column(name = "user_id")
    private Long userId;

    /** Serialized options (creation options for register / AssertionRequest for assert). */
    @Column(name = "request_json", nullable = false, columnDefinition = "TEXT")
    private String requestJson;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /** createdAt + 300s. */
    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    public WebAuthnChallenge() {
    }

    public Long getId() {
        return id;
    }

    public String getChallenge() {
        return challenge;
    }

    public void setChallenge(String challenge) {
        this.challenge = challenge;
    }

    public String getCeremony() {
        return ceremony;
    }

    public void setCeremony(String ceremony) {
        this.ceremony = ceremony;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public String getRequestJson() {
        return requestJson;
    }

    public void setRequestJson(String requestJson) {
        this.requestJson = requestJson;
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
}
