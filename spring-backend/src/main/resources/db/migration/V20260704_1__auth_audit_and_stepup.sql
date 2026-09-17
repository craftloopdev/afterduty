-- V20260704_1__auth_audit_and_stepup.sql
-- Auth program Phase 1 — P1.1 (auth audit log) + P1.2 (step-up tokens).
-- See docs/architecture/auth/2026-07-04-auth-program-plan.md §3 (P1.1/P1.2) and
-- §3.4 (the canonical audit-log schema this table implements).
--
-- NOTE: Flyway is not currently configured in this project; ddl-auto=update
-- creates/updates the schema from the JPA entities. This file is the canonical
-- migration for a future Flyway switch. The AuthAuditLog and StepUpToken
-- entities declare the same tables/columns/indexes in lockstep, so
-- ddl-auto=update creates them on next boot. BIGSERIAL / BIGINT / VARCHAR /
-- JSONB / TIMESTAMP WITH TIME ZONE are accepted by both Postgres and H2
-- (MODE=PostgreSQL) — jsonb degrades to the H2 JSON type under PostgreSQL mode.

-- =====================================================================
-- auth_audit_log — append-only security event log (P1.1, §3.4 schema).
--
-- Append-only by CONTRACT: the application has NO update or delete code path
-- against this table (the entity is written once via AuthAuditService.record
-- and never mutated). For defense in depth, the DB grants for the application
-- role should REVOKE UPDATE/DELETE on this table in production (owner action;
-- ddl-auto can't express grants). detail_json NEVER carries a code, token,
-- secret, or PHI — AuthAuditService enforces a typed allowlist builder so this
-- is impossible to violate from calling code.
-- =====================================================================
CREATE TABLE IF NOT EXISTS auth_audit_log (
    id                    BIGSERIAL PRIMARY KEY,
    -- Nullable: pre-auth events (an OTP request for an email that has no account
    -- yet, a failed sign-in) legitimately have no user_id.
    user_id               BIGINT,
    event_type            VARCHAR(64)  NOT NULL,
    -- email | phone | passkey | biometric | idme | va (nullable — not every
    -- event has a channel, e.g. a generic SIGN_OUT).
    channel               VARCHAR(16),
    -- SUCCESS | FAILURE.
    outcome               VARCHAR(16)  NOT NULL,
    ip                    VARCHAR(45),
    user_agent            VARCHAR(512),
    -- FK-shaped but intentionally NOT a hard FK: device_credentials arrives in a
    -- later increment (P1.4); a soft reference keeps this migration standalone.
    device_credential_id  BIGINT,
    -- Allowlisted, non-sensitive context only (reason / purpose / credentialType /
    -- factor / mfaModel). NEVER codes, tokens, secrets, or PHI.
    detail_json           JSONB,
    created_at            TIMESTAMP WITH TIME ZONE NOT NULL
);

-- Query-by-user, newest first (per-user audit trail, recovery review).
CREATE INDEX IF NOT EXISTS idx_auth_audit_user_created
    ON auth_audit_log (user_id, created_at);

-- Query-by-event-type over a window (e.g. STEP_UP_FAILED spikes, OTP_FAILED
-- abuse detection).
CREATE INDEX IF NOT EXISTS idx_auth_audit_event_created
    ON auth_audit_log (event_type, created_at);

-- =====================================================================
-- step_up_tokens — server-side store for short-TTL (300s), single-user,
-- single-use step-up tokens (P1.2).
--
-- DESIGN CHOICE: server-side random token store (NOT HMAC-signed). Rationale:
-- the app already owns Cloud SQL and a proven per-row lock/consume pattern
-- (email_verification_code), so a random opaque token in a table is the
-- SIMPLER CORRECT option — it gives single-use revocation and immediate
-- server-side invalidation for free, with no Secret Manager key to provision,
-- rotate, or leak. Only the SHA-256 HASH of the token is stored (the plaintext
-- is returned to the client once and never persisted), so a DB read cannot
-- forge a valid header. Validation is a constant-time hash compare bound to the
-- user id. consumed_at makes the token single-use (replay-proof); expires_at is
-- created_at + 300s.
-- =====================================================================
CREATE TABLE IF NOT EXISTS step_up_tokens (
    id            BIGSERIAL PRIMARY KEY,
    -- SHA-256 hex of the opaque token. Plaintext is NEVER stored.
    token_hash    VARCHAR(64)  NOT NULL,
    user_id       BIGINT       NOT NULL,
    -- otp (passkey/biometric added in later increments).
    factor        VARCHAR(16)  NOT NULL,
    created_at    TIMESTAMP WITH TIME ZONE NOT NULL,
    expires_at    TIMESTAMP WITH TIME ZONE NOT NULL,
    -- Non-null => burned (single-use). Kept for a short window for audit before
    -- the opportunistic sweep deletes it.
    consumed_at   TIMESTAMP WITH TIME ZONE
);

-- Validation lookup is by token_hash (unique live token) then user_id + expiry
-- + consumed checks in the service.
CREATE INDEX IF NOT EXISTS idx_step_up_token_hash
    ON step_up_tokens (token_hash);

-- Opportunistic sweep of long-dead rows is by expiry.
CREATE INDEX IF NOT EXISTS idx_step_up_token_expires
    ON step_up_tokens (expires_at);
