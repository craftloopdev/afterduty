-- V20260704_3__device_credentials.sql
-- Auth program Phase 1 — P1.4 (DEVICE-BOUND TOKEN / native biometric unlock),
-- the B2 device-bound-token contract.
-- See docs/architecture/auth/2026-07-04-auth-program-plan.md §3 (P1.4).
--
-- NOTE: Flyway is not currently configured in this project; ddl-auto=update
-- creates/updates the schema from the JPA entities. This file is the canonical
-- migration for a future Flyway switch. The DeviceCredential entity declares the
-- same table/columns/indexes in lockstep, so ddl-auto=update creates it on next
-- boot. BIGSERIAL / BIGINT / VARCHAR / TIMESTAMP WITH TIME ZONE are accepted by
-- both Postgres and H2 (MODE=PostgreSQL).

-- =====================================================================
-- device_credentials — a device-bound login secret for native biometric unlock.
--
-- Enrolled from an already-authenticated (OTP/passkey) session, this row lets a
-- native app trade a device-held secret — gated behind a FRESH biometric prompt
-- on the device (@capgo/capacitor-native-biometric, BIOMETRY_CURRENT_SET) — for a
-- fresh app session (a Firebase custom token, same shape the email-code / passkey
-- verify returns) without re-running OTP.
--
-- Security:
--   * device_secret_hash — SHA-256 hex of a 256-bit SecureRandom secret. The
--                          PLAINTEXT is returned to the client ONCE at enroll and
--                          is NEVER stored, so a DB read cannot forge an exchange.
--                          Exchange verify is a CONSTANT-TIME hash compare.
--   * revoked_at         — non-null ⇒ dead; a revoked row can never exchange again.
--   * user_id            — every exchange mints a session ONLY for this user's
--                          owning uid (never a client-supplied subject).
-- =====================================================================
CREATE TABLE IF NOT EXISTS device_credentials (
    id                  BIGSERIAL PRIMARY KEY,
    -- Owning app user (hard-FK-shaped; the app deletes these rows in
    -- UserDeletionService's FK-safe order — soft reference keeps this migration
    -- standalone, matching the webauthn_credentials / auth_audit_log convention).
    user_id             BIGINT       NOT NULL,
    -- SHA-256 hex of the 256-bit device secret. Plaintext NEVER stored.
    device_secret_hash  VARCHAR(64)  NOT NULL,
    -- User-facing device label ("iPhone 15"); nullable.
    device_name         VARCHAR(120),
    -- ios | android.
    platform            VARCHAR(16)  NOT NULL,
    created_at          TIMESTAMP WITH TIME ZONE NOT NULL,
    -- Bumped on every successful exchange (manage view surfaces it).
    last_used_at        TIMESTAMP WITH TIME ZONE,
    -- Non-null ⇒ revoked; a revoked row is excluded from every exchange.
    revoked_at          TIMESTAMP WITH TIME ZONE
);

-- Manage list + revoke are by user.
CREATE INDEX IF NOT EXISTS idx_device_cred_user
    ON device_credentials (user_id);

-- Exchange verify looks a credential up by its secret hash (then constant-time
-- compares + checks revoked/user-binding on the result).
CREATE INDEX IF NOT EXISTS idx_device_cred_secret_hash
    ON device_credentials (device_secret_hash);
