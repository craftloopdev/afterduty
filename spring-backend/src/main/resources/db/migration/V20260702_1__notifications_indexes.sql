-- V20260702__notifications_indexes.sql
-- Phase B item B1: the notifications journal gets its first writers (diff-at-flip
-- in ConditionGenerationService) and readers (GET /api/notifications, ordered
-- created_at DESC and filtered user_id / is_read). Canonical DDL + read-path
-- indexes.
--
-- NOTE: Flyway is not currently configured in this project; ddl-auto=update
-- creates/updates the schema from JPA entities (the notifications table already
-- exists in deployed environments via the Notification entity). This file exists
-- so a future Flyway switch has the canonical migration to apply — hence
-- IF NOT EXISTS throughout. Same convention as V20260516__shares.sql.
--
-- H2 compatibility notes:
-- * TIMESTAMP WITH TIME ZONE is the SQL-standard form accepted by both
--   Postgres and H2 (same convention as V20260427/V20260516).
-- * The partial unread index (WHERE …) is Postgres-only and NOT supported by
--   H2; production-only DDL (tests use ddl-auto=create-drop). Same pattern as
--   the V20260516 partial indexes.

CREATE TABLE IF NOT EXISTS notifications (
    id            BIGSERIAL     PRIMARY KEY,
    user_id       BIGINT        NOT NULL,
    claim_id      BIGINT,
    event_type    VARCHAR(255)  NOT NULL,
    title         VARCHAR(255)  NOT NULL,
    body          TEXT          NOT NULL,
    severity      VARCHAR(255),
    condition_id  BIGINT,
    metadata_json TEXT,
    is_read       BOOLEAN       DEFAULT FALSE,
    created_at    TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

-- The list read: WHERE user_id = ? ORDER BY created_at DESC, id DESC LIMIT n.
CREATE INDEX IF NOT EXISTS idx_notifications_user_created
    ON notifications (user_id, created_at DESC, id DESC);

-- Postgres-only: partial index for the unread count / unreadOnly filter.
CREATE INDEX IF NOT EXISTS idx_notifications_user_unread
    ON notifications (user_id)
    WHERE is_read = FALSE;
