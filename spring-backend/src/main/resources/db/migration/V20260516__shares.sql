-- V20260516__shares.sql
-- Phase A: sharing feature schema — shares, chat_threads tables, and
-- thread_id column on intake_messages.
--
-- NOTE: Flyway is not currently configured in this project; ddl-auto=update
-- creates/updates the schema from JPA entities. This file exists so a future
-- Flyway switch has the canonical migration to apply. The JPA entities have
-- been updated in lockstep.
--
-- H2 compatibility notes:
-- * TIMESTAMP WITH TIME ZONE is the SQL-standard form accepted by both
--   Postgres and H2 (same convention as V20260427). TIMESTAMPTZ is the
--   Postgres-specific short form and would break H2; bare TIMESTAMP loses
--   offset information when persisting JPA Instants.
-- * The partial indexes (WHERE …) and the functional index lower(viewer_email)
--   are Postgres-only and are NOT supported by H2. They are included here as
--   production-only DDL and will not be run in H2 test environments
--   (ddl-auto=create-drop handles schema in tests). Same pattern as the
--   V20260427 partial index.

-- ============================================================
-- 1. chat_threads
-- ============================================================

CREATE TABLE chat_threads (
    id              BIGSERIAL PRIMARY KEY,
    viewer_user_id  BIGINT    NOT NULL REFERENCES users(id),
    claim_id        BIGINT    NOT NULL REFERENCES claims(id),
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_chat_threads_viewer_claim UNIQUE (viewer_user_id, claim_id)
);

CREATE INDEX IF NOT EXISTS idx_chat_threads_viewer_claim
    ON chat_threads (viewer_user_id, claim_id);

-- ============================================================
-- 2. shares
-- ============================================================

CREATE TABLE shares (
    id                    BIGSERIAL PRIMARY KEY,
    owner_user_id         BIGINT         NOT NULL REFERENCES users(id),
    claim_id              BIGINT         NOT NULL REFERENCES claims(id),
    viewer_user_id        BIGINT         REFERENCES users(id),
    viewer_email          VARCHAR(255)   NOT NULL,
    can_view_analysis     BOOLEAN        NOT NULL DEFAULT FALSE,
    can_upload_docs       BOOLEAN        NOT NULL DEFAULT FALSE,
    invitation_token      VARCHAR(64)    UNIQUE,
    invitation_expires_at TIMESTAMP WITH TIME ZONE,
    accepted_at           TIMESTAMP WITH TIME ZONE,
    revoked_at            TIMESTAMP WITH TIME ZONE,
    created_at            TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

-- Postgres-only: partial index for active (accepted, not revoked) shares.
-- H2 does not support partial/filtered indexes (WHERE clause on index).
CREATE INDEX IF NOT EXISTS idx_shares_viewer_claim_active
    ON shares (viewer_user_id, claim_id)
    WHERE revoked_at IS NULL;

-- Postgres-only: partial index for pending-invitation lookup
-- (token is set but the share is not yet accepted or revoked).
-- H2 does not support partial/filtered indexes (WHERE clause on index).
CREATE INDEX IF NOT EXISTS idx_shares_invitation_token_pending
    ON shares (invitation_token)
    WHERE accepted_at IS NULL AND revoked_at IS NULL;

-- Postgres-only: functional index for case-insensitive email lookup.
-- H2 does not support functional indexes using lower().
CREATE INDEX IF NOT EXISTS idx_shares_viewer_email_lower
    ON shares (lower(viewer_email));

-- ============================================================
-- 3. Add thread_id to intake_messages
-- ============================================================

ALTER TABLE intake_messages
    ADD COLUMN thread_id BIGINT REFERENCES chat_threads(id);

-- Backfill: create one chat_thread per (claim, owner_user) pair for all
-- existing intake_messages rows, then point thread_id at it.
-- (Only needed if any intake_messages rows already exist; safe to run on
-- an empty table.)

INSERT INTO chat_threads (viewer_user_id, claim_id, created_at)
SELECT DISTINCT c.user_id, im.claim_id, NOW()
FROM intake_messages im
JOIN claims c ON c.id = im.claim_id
ON CONFLICT (viewer_user_id, claim_id) DO NOTHING;

UPDATE intake_messages im
SET thread_id = ct.id
FROM chat_threads ct
JOIN claims c ON c.id = ct.claim_id
WHERE im.claim_id = ct.claim_id
  AND c.user_id = ct.viewer_user_id
  AND im.thread_id IS NULL;

-- Note: NOT NULL constraint on thread_id is deferred to Phase D when the
-- IntakeMessage entity is updated to populate thread_id on insert. Making
-- it NOT NULL now would break existing insert paths.
