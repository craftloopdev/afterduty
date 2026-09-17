package com.afterduty.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

/**
 * Append-only security audit event (auth program P1.1; the §3.4 schema in
 * {@code docs/architecture/auth/2026-07-04-auth-program-plan.md}).
 *
 * <p><b>Append-only by contract.</b> Rows are written once via
 * {@code AuthAuditService.record(...)} and NEVER updated or deleted by the
 * application — there is no setter-driven mutation path and the repository
 * exposes no update/delete methods. In production the DB grants for the app role
 * should additionally REVOKE UPDATE/DELETE on this table (owner action).
 *
 * <p><b>detail_json never carries a code, token, secret, or PHI.</b> It is
 * populated only through {@code AuthAuditService.detail()} — a typed builder that
 * accepts a small allowlist of non-sensitive keys — so a caller cannot smuggle a
 * plaintext OTP, step-up token, or health fact into the log.
 *
 * <p>Portable column types only, so the table is identical on H2 (tests) and
 * Postgres (prod). {@code detail_json} is {@code jsonb} on Postgres and the JSON
 * type on H2 (MODE=PostgreSQL); created by {@code ddl-auto: update} in lockstep
 * with {@code V20260704_1__auth_audit_and_stepup.sql}.
 */
@Entity
@Table(
        name = "auth_audit_log",
        indexes = {
                @Index(name = "idx_auth_audit_user_created", columnList = "user_id, created_at"),
                @Index(name = "idx_auth_audit_event_created", columnList = "event_type, created_at")
        }
)
public class AuthAuditLog {

    // ---- event_type vocabulary (P1.1 pinned set) -----------------------------
    public static final String EVENT_OTP_REQUESTED = "OTP_REQUESTED";
    public static final String EVENT_OTP_VERIFIED = "OTP_VERIFIED";
    public static final String EVENT_OTP_FAILED = "OTP_FAILED";
    public static final String EVENT_SIGN_IN = "SIGN_IN";
    public static final String EVENT_ATTACH_CHANNEL = "ATTACH_CHANNEL";
    public static final String EVENT_SIGN_OUT = "SIGN_OUT";
    public static final String EVENT_STEP_UP_REQUESTED = "STEP_UP_REQUESTED";
    public static final String EVENT_STEP_UP_VERIFIED = "STEP_UP_VERIFIED";
    public static final String EVENT_STEP_UP_FAILED = "STEP_UP_FAILED";
    // Passkeys / WebAuthn (P1.3).
    public static final String EVENT_PASSKEY_REGISTERED = "PASSKEY_REGISTERED";
    public static final String EVENT_PASSKEY_LOGIN = "PASSKEY_LOGIN";
    public static final String EVENT_PASSKEY_REVOKED = "PASSKEY_REVOKED";
    // Device-bound token / native biometric unlock (P1.4).
    public static final String EVENT_DEVICE_ENROLLED = "DEVICE_ENROLLED";
    public static final String EVENT_DEVICE_LOGIN = "DEVICE_LOGIN";
    public static final String EVENT_DEVICE_REVOKED = "DEVICE_REVOKED";
    // Factor-2 recovery — dual-channel (fresh email code + fresh phone proof) (P1.5).
    public static final String EVENT_RECOVERY_STARTED = "RECOVERY_STARTED";
    public static final String EVENT_RECOVERY_COMPLETED = "RECOVERY_COMPLETED";
    public static final String EVENT_RECOVERY_FAILED = "RECOVERY_FAILED";

    // ---- channel vocabulary --------------------------------------------------
    public static final String CHANNEL_EMAIL = "email";
    public static final String CHANNEL_PHONE = "phone";
    public static final String CHANNEL_PASSKEY = "passkey";
    public static final String CHANNEL_BIOMETRIC = "biometric";
    public static final String CHANNEL_IDME = "idme";
    public static final String CHANNEL_VA = "va";

    // ---- outcome vocabulary --------------------------------------------------
    public static final String OUTCOME_SUCCESS = "SUCCESS";
    public static final String OUTCOME_FAILURE = "FAILURE";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Nullable — pre-auth events (a request/verify for an email with no account) have none. */
    @Column(name = "user_id")
    private Long userId;

    @Column(name = "event_type", nullable = false, length = 64)
    private String eventType;

    /** email | phone | passkey | biometric | idme | va (nullable). */
    @Column(length = 16)
    private String channel;

    /** SUCCESS | FAILURE. */
    @Column(nullable = false, length = 16)
    private String outcome;

    @Column(length = 45)
    private String ip;

    @Column(name = "user_agent", length = 512)
    private String userAgent;

    /** Soft reference to a future device_credentials row (P1.4). Not a hard FK. */
    @Column(name = "device_credential_id")
    private Long deviceCredentialId;

    /**
     * Allowlisted, non-sensitive JSON context ONLY — NEVER a code/token/secret/PHI.
     * Serialized to a compact JSON string by {@code AuthAuditService}; stored as
     * {@code jsonb} on Postgres. Nullable.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "detail_json")
    private String detailJson;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    public AuthAuditLog() {
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

    public String getEventType() {
        return eventType;
    }

    public void setEventType(String eventType) {
        this.eventType = eventType;
    }

    public String getChannel() {
        return channel;
    }

    public void setChannel(String channel) {
        this.channel = channel;
    }

    public String getOutcome() {
        return outcome;
    }

    public void setOutcome(String outcome) {
        this.outcome = outcome;
    }

    public String getIp() {
        return ip;
    }

    public void setIp(String ip) {
        this.ip = ip;
    }

    public String getUserAgent() {
        return userAgent;
    }

    public void setUserAgent(String userAgent) {
        this.userAgent = userAgent;
    }

    public Long getDeviceCredentialId() {
        return deviceCredentialId;
    }

    public void setDeviceCredentialId(Long deviceCredentialId) {
        this.deviceCredentialId = deviceCredentialId;
    }

    public String getDetailJson() {
        return detailJson;
    }

    public void setDetailJson(String detailJson) {
        this.detailJson = detailJson;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
