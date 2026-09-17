-- V20260427__usage_cap.sql
-- Add subscription start (placeholder until Stripe is wired) + an index that
-- supports the per-user, per-period cost sum used by UsageService.
--
-- NOTE: Flyway is not currently configured in this project; ddl-auto=update
-- creates/updates the schema from JPA entities. This file exists so a future
-- Flyway switch has the canonical migration to apply. The User entity has been
-- updated in lockstep so ddl-auto=update adds `subscription_started_at` on
-- next boot. The index here is Postgres-only — H2 dev DBs do not need it.
--
-- TIMESTAMP WITH TIME ZONE is the SQL-standard form accepted by both Postgres
-- and H2; TIMESTAMPTZ is Postgres-specific and would break H2.

ALTER TABLE users
    ADD COLUMN subscription_started_at TIMESTAMP WITH TIME ZONE NULL;

UPDATE users
SET subscription_started_at = created_at
WHERE subscription_started_at IS NULL;

CREATE INDEX IF NOT EXISTS idx_ai_call_logs_user_created
    ON ai_call_logs (user_id, created_at DESC);

-- Document the new processing_status value (column is text; no enum change).
COMMENT ON COLUMN evidence_items.processing_status IS
    'pending | processing | extracting | saving | processed | error | deferred_usage_limit';
