-- V20260702_4__condition_suppressions.sql
-- P1-14 — durable, undoable record of chat-initiated condition removals.
--
-- delete_condition used to hard-delete the identified_conditions row: no undo,
-- and because the identify prompt still sees the same evidence, the very next
-- analysis run re-emitted the condition — deletions resurrected on the next
-- upload. This table records the veteran's "this does not apply to me" intent,
-- keyed by the condition's stable identity_fingerprint (see
-- V20260611__condition_generation_fingerprints.sql) — NOT by condition id,
-- which churns every generation — mirroring user_gap_state (V20260702_2).
--
-- Writers/readers:
--   * ChatAgent delete_condition inserts a row AND hides the live row behind
--     the SUPPRESSED_MARKER sentinel (-3) in identified_conditions.superseded_by.
--   * ConditionRepository.findByClaimIdAndSupersededByIsNull — the single
--     active-generation read used by every user-facing surface (conditions API,
--     /jobs counters, chat grounding, gap analysis) — post-filters any condition
--     whose fingerprint has an unlifted suppression here, so a later generation's
--     re-identified copy never resurfaces. Deterministic SQL; the identify
--     prompt is deliberately NOT changed.
--   * ChatAgent restore_condition stamps lifted_at (undo); a lifted row
--     filters nothing.
--
-- identity_fingerprint is nullable: legacy/flag-off rows carry no fingerprint;
-- suppressing one still hides that row (via the sentinel) but cannot block a
-- later re-identification.
--
-- NOTE: Flyway is not currently configured in this project; ddl-auto=update
-- creates/updates the schema from JPA entities. This file exists so a future
-- Flyway switch has the canonical migration to apply. The ConditionSuppression
-- entity declares the same table/index in lockstep. BIGSERIAL and TIMESTAMP
-- WITH TIME ZONE are accepted by both Postgres and H2 (MODE=PostgreSQL).

CREATE TABLE IF NOT EXISTS condition_suppressions (
    id                   BIGSERIAL PRIMARY KEY,
    claim_id             BIGINT       NOT NULL REFERENCES claims(id),
    condition_id         BIGINT,
    identity_fingerprint VARCHAR(255),
    condition_name       VARCHAR(512),
    reason               TEXT,
    created_by_user_id   BIGINT,
    created_at           TIMESTAMP WITH TIME ZONE NOT NULL,
    lifted_at            TIMESTAMP WITH TIME ZONE
);

-- The post-filter and the undo lookup are both per-claim.
CREATE INDEX IF NOT EXISTS idx_condition_suppressions_claim
    ON condition_suppressions (claim_id);
