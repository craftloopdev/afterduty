-- V20260704_2__webauthn_credentials.sql
-- Auth program Phase 1 — P1.3 (WEB PASSKEYS / WebAuthn).
-- See docs/architecture/auth/2026-07-04-auth-program-plan.md §3 (P1.3) and the
-- pinned REGISTER / AUTHENTICATE / MANAGE ceremony contract.
--
-- NOTE: Flyway is not currently configured in this project; ddl-auto=update
-- creates/updates the schema from the JPA entities. This file is the canonical
-- migration for a future Flyway switch. The WebAuthnCredential and
-- WebAuthnChallenge entities declare the same tables/columns/indexes in
-- lockstep, so ddl-auto=update creates them on next boot. BIGSERIAL / BIGINT /
-- VARCHAR / TEXT / TIMESTAMP WITH TIME ZONE are accepted by both Postgres and
-- H2 (MODE=PostgreSQL).

-- =====================================================================
-- webauthn_credentials — a passkey enrolled on an account.
--
-- The RP verifier (Yubico webauthn-server-core) validates every ceremony; this
-- table is the CredentialRepositoryV2 backing store. It holds ONLY the public
-- material needed to verify future assertions:
--   * credential_id  — the authenticator's credential id (base64url), unique.
--   * user_handle     — the opaque per-user WebAuthn handle (base64url). NOT the
--                       email/phone — a non-PII value derived from the internal
--                       user id, so the wire never carries an identifier.
--   * public_key_cose — the COSE-encoded public key (base64url). NEVER exposed.
--   * signature_count — last accepted authenticator sign counter; a NON-monotonic
--                       assertion (regression) is rejected as a cloned authenticator.
-- No private key or secret is ever stored (WebAuthn's whole point).
-- =====================================================================
CREATE TABLE IF NOT EXISTS webauthn_credentials (
    id               BIGSERIAL PRIMARY KEY,
    -- Owning app user (hard FK-shaped; the app deletes these rows in
    -- UserDeletionService's FK-safe order — soft reference keeps this migration
    -- standalone, matching the auth_audit_log convention).
    user_id          BIGINT       NOT NULL,
    -- Authenticator credential id, base64url. Globally unique (a credential id
    -- belongs to exactly one account).
    credential_id    VARCHAR(512) NOT NULL,
    -- Opaque per-user WebAuthn user handle, base64url. All of a user's
    -- credentials share the same handle (assertion resolves user by handle).
    user_handle      VARCHAR(128) NOT NULL,
    -- COSE public key, base64url. Public material only; never returned to a client.
    public_key_cose  TEXT         NOT NULL,
    -- Last accepted authenticator signature counter (monotonic; regression rejected).
    signature_count  BIGINT       NOT NULL DEFAULT 0,
    -- Comma-separated AuthenticatorTransport hints (e.g. "internal,hybrid"),
    -- nullable. Used only to derive a friendly deviceHint — never trusted for auth.
    transports       VARCHAR(128),
    -- Authenticator AAGUID (hex), nullable. Used only for a coarse deviceHint.
    aaguid           VARCHAR(64),
    -- User-chosen label ("MacBook Touch ID"); nullable. 1..60 chars enforced in app.
    nickname         VARCHAR(60),
    created_at       TIMESTAMP WITH TIME ZONE NOT NULL,
    -- Bumped on every successful assertion (manage view surfaces it).
    last_used_at     TIMESTAMP WITH TIME ZONE
);

-- Assertion verify looks a credential up by its (unique) credential id.
CREATE UNIQUE INDEX IF NOT EXISTS uq_webauthn_credential_id
    ON webauthn_credentials (credential_id);

-- Register/options excludeCredentials + manage list are by user; assertion
-- resolves the user's credentials by user_handle.
CREATE INDEX IF NOT EXISTS idx_webauthn_cred_user
    ON webauthn_credentials (user_id);
CREATE INDEX IF NOT EXISTS idx_webauthn_cred_user_handle
    ON webauthn_credentials (user_handle);

-- =====================================================================
-- webauthn_challenges — short-TTL (300s) server-side store of the in-flight
-- ceremony, so the RP can bind the finish step to the exact options it issued.
--
-- We persist the WHOLE serialized options object (creation options for register,
-- the AssertionRequest for authenticate), not just the raw challenge, because
-- finishRegistration/finishAssertion need the original request to verify against.
-- Single-use: the row is DELETED on consume (a replayed finish finds nothing).
-- The decoy assert path (unknown identifier) writes a row with user_id NULL and
-- ceremony=assert so timing/shape don't reveal enrollment.
-- =====================================================================
CREATE TABLE IF NOT EXISTS webauthn_challenges (
    id            BIGSERIAL PRIMARY KEY,
    -- The base64url challenge — the single-use lookup key for the finish step.
    challenge     VARCHAR(255) NOT NULL,
    -- register | assert.
    ceremony      VARCHAR(16)  NOT NULL,
    -- The resolved user this ceremony is bound to; NULL for the anti-enumeration
    -- decoy assert (unknown identifier still gets a valid options object).
    user_id       BIGINT,
    -- Serialized options: PublicKeyCredentialCreationOptions JSON for register,
    -- AssertionRequest JSON for assert. Round-tripped via the lib's fromJson.
    request_json  TEXT         NOT NULL,
    created_at    TIMESTAMP WITH TIME ZONE NOT NULL,
    -- created_at + 300s.
    expires_at    TIMESTAMP WITH TIME ZONE NOT NULL
);

-- Finish looks the ceremony up by its (unique live) challenge.
CREATE UNIQUE INDEX IF NOT EXISTS uq_webauthn_challenge
    ON webauthn_challenges (challenge);

-- Opportunistic sweep of long-dead rows is by expiry.
CREATE INDEX IF NOT EXISTS idx_webauthn_challenge_expires
    ON webauthn_challenges (expires_at);
