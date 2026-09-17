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
 * A passkey (WebAuthn credential) enrolled on an account (auth program P1.3).
 *
 * <p>This is the backing store for the RP's {@code CredentialRepositoryV2}. It
 * holds ONLY public material — the verifier (Yubico webauthn-server-core) checks
 * every assertion against it:
 * <ul>
 *   <li>{@link #credentialId} — the authenticator's credential id (base64url),
 *       globally unique (one credential belongs to exactly one account).</li>
 *   <li>{@link #userHandle} — the opaque per-user WebAuthn handle (base64url);
 *       NOT the email/phone. All of a user's credentials share the same handle,
 *       and assertion resolves the user by this handle.</li>
 *   <li>{@link #publicKeyCose} — the COSE-encoded public key (base64url). NEVER
 *       returned to a client.</li>
 *   <li>{@link #signatureCount} — the last accepted authenticator sign counter; a
 *       non-monotonic assertion (regression) is rejected as a cloned authenticator.</li>
 * </ul>
 * There is no private key or secret here — that is the entire point of WebAuthn.
 *
 * <p>Portable column types only (identical on H2 tests / Postgres prod); created
 * by {@code ddl-auto: update} in lockstep with
 * {@code V20260704_2__webauthn_credentials.sql}.
 */
@Entity
@Table(
        name = "webauthn_credentials",
        indexes = {
                @Index(name = "uq_webauthn_credential_id", columnList = "credential_id", unique = true),
                @Index(name = "idx_webauthn_cred_user", columnList = "user_id"),
                @Index(name = "idx_webauthn_cred_user_handle", columnList = "user_handle")
        }
)
public class WebAuthnCredential {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** Authenticator credential id, base64url. Globally unique. */
    @Column(name = "credential_id", nullable = false, length = 512)
    private String credentialId;

    /** Opaque per-user WebAuthn user handle, base64url (non-PII). */
    @Column(name = "user_handle", nullable = false, length = 128)
    private String userHandle;

    /** COSE public key, base64url. Public material only; never returned to a client. */
    @Column(name = "public_key_cose", nullable = false, columnDefinition = "TEXT")
    private String publicKeyCose;

    /** Last accepted authenticator signature counter (monotonic; regression rejected). */
    @Column(name = "signature_count", nullable = false)
    private long signatureCount;

    /** Comma-separated AuthenticatorTransport hints (e.g. "internal,hybrid"), nullable. */
    @Column(name = "transports", length = 128)
    private String transports;

    /** Authenticator AAGUID (hex), nullable — coarse deviceHint only. */
    @Column(name = "aaguid", length = 64)
    private String aaguid;

    /** User-chosen label; nullable. 1..60 chars enforced in the app. */
    @Column(name = "nickname", length = 60)
    private String nickname;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /** Bumped on every successful assertion. */
    @Column(name = "last_used_at")
    private Instant lastUsedAt;

    public WebAuthnCredential() {
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

    public String getCredentialId() {
        return credentialId;
    }

    public void setCredentialId(String credentialId) {
        this.credentialId = credentialId;
    }

    public String getUserHandle() {
        return userHandle;
    }

    public void setUserHandle(String userHandle) {
        this.userHandle = userHandle;
    }

    public String getPublicKeyCose() {
        return publicKeyCose;
    }

    public void setPublicKeyCose(String publicKeyCose) {
        this.publicKeyCose = publicKeyCose;
    }

    public long getSignatureCount() {
        return signatureCount;
    }

    public void setSignatureCount(long signatureCount) {
        this.signatureCount = signatureCount;
    }

    public String getTransports() {
        return transports;
    }

    public void setTransports(String transports) {
        this.transports = transports;
    }

    public String getAaguid() {
        return aaguid;
    }

    public void setAaguid(String aaguid) {
        this.aaguid = aaguid;
    }

    public String getNickname() {
        return nickname;
    }

    public void setNickname(String nickname) {
        this.nickname = nickname;
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
}
