-- V20260702__user_gap_state.sql
-- P1-6 — durable, user-set gap status that survives re-analysis.
--
-- Every gap run wholesale-replaces each condition's `gaps` JSON, destroying
-- anything the veteran had marked resolved/dismissed and resurrecting gaps
-- they said do not apply. This table is the durable record of that user
-- intent, keyed NOT by condition id (ids change every generation) but by the
-- condition's stable identity_fingerprint (see
-- V20260611__condition_generation_fingerprints.sql) plus the gap's
-- (gap_type, triad_leg) pair — the same gap re-proposed for the same
-- claimable condition in a later generation lands on the same key.
--
-- Writers/readers:
--   * PATCH /api/claim/gaps/{condId}/{gapIndex}/status upserts a row AND
--     stamps the status into the live gap JSON (UserGapStateService.upsert).
--   * GapStateMachine re-applies rows onto freshly-written gap JSON after
--     every run (UserGapStateService.reapply).
--   * status='dismissed' rows are injected into the gap-analysis prompt as
--     do-not-re-propose context (EvidenceGapAnalyzer).
--
-- NULL semantics: triad_leg is nullable (not every gap targets a triad leg).
-- SQL UNIQUE treats NULLs as distinct, so the constraint below does NOT stop
-- duplicate null-leg rows at the DB layer; the service-level upsert (find via
-- a dedicated IsNull lookup, then save) is the guard.
--
-- NOTE: Flyway is not currently configured in this project; ddl-auto=update
-- creates/updates the schema from JPA entities. This file exists so a future
-- Flyway switch has the canonical migration to apply. The UserGapState entity
-- declares the same table/constraint/index in lockstep, so ddl-auto=update
-- creates this table on next boot. BIGSERIAL and TIMESTAMP WITH TIME ZONE are
-- accepted by both Postgres and H2 (MODE=PostgreSQL).

CREATE TABLE IF NOT EXISTS user_gap_state (
    id                   BIGSERIAL PRIMARY KEY,
    claim_id             BIGINT       NOT NULL REFERENCES claims(id),
    identity_fingerprint VARCHAR(255) NOT NULL,
    gap_type             VARCHAR(255) NOT NULL,
    triad_leg            VARCHAR(255),
    status               VARCHAR(32)  NOT NULL,
    note                 TEXT,
    updated_at           TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uq_user_gap_state_key
        UNIQUE (claim_id, identity_fingerprint, gap_type, triad_leg)
);

-- Reapply/dismissal reads are per-claim (then filtered by fingerprint/status
-- in memory or via the composite in the unique constraint above).
CREATE INDEX IF NOT EXISTS idx_user_gap_state_claim
    ON user_gap_state (claim_id);
