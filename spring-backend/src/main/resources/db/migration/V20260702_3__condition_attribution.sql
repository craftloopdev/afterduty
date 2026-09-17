-- V20260702_3__condition_attribution.sql
-- Phase B item B2 (report §5 "Final recommended architecture" items 2–4) —
-- attributed per-condition evidence fingerprints. Adds three columns to
-- identified_conditions:
--
--   supporting_atom_ids  JSON array of the live atom ids the identify model
--                        cited as supporting THIS condition (the new
--                        supporting_atom_ids field in the identify response,
--                        prompt version 2). Unioned across merged duplicates
--                        (duplicates share an identity fingerprint) and
--                        filtered to ids that exist in the run's live corpus.
--                        Written on every incremental run regardless of the
--                        scoping flag; NULL when the model attributed nothing
--                        usable.
--
--   corpus_fingerprint   The corpus-wide evidence fingerprint of the run that
--                        wrote the row — exactly what evidence_fingerprint
--                        held before per-condition scoping. All rows of one
--                        generation share it. AnalysisScheduler's no-new-facts
--                        short-circuit compares THIS column (falling back to
--                        evidence_fingerprint on legacy rows), so the
--                        duplicate-upload skip keeps working when
--                        evidence_fingerprint becomes per-condition.
--
--   last_full_run_at     When this condition's expensive outputs (rate/verify/
--                        gap) were last computed by a full LLM pass rather
--                        than carried forward. Stamped on DIRTY classification;
--                        copied on clean carry-forward. Drives the 30-day
--                        full-refresh safety valve (va-claim.synthesis.
--                        full-refresh-days) under per-condition fingerprints.
--
-- With va-claim.synthesis.per-condition-fingerprint=false (the DEFAULT — the
-- rollback lever), evidence_fingerprint keeps its corpus-wide value byte-for-
-- byte and dirty/clean behavior is unchanged; the three new columns are
-- write-only bookkeeping that primes the data for a later flag flip. Safety
-- valves with the flag ON: (a) unattributed conditions fall back to the
-- corpus-wide hash (strictly more conservative than a body-system hash — atoms
-- carry no body-system tag); (b) new conditions are always dirty (no prior
-- identity match); (c) any condition whose last_full_run_at is older than 30
-- days (or NULL) goes dirty on the next run; (d) the flag itself.
--
-- NOTE: Flyway is not currently configured in this project; ddl-auto=update
-- creates/updates the schema from JPA entities. This file exists so a future
-- Flyway switch has the canonical migration to apply. The IdentifiedCondition
-- entity has been updated in lockstep so ddl-auto=update adds the columns on
-- next boot.

ALTER TABLE identified_conditions
    ADD COLUMN supporting_atom_ids TEXT NULL;

ALTER TABLE identified_conditions
    ADD COLUMN corpus_fingerprint VARCHAR(255) NULL;

ALTER TABLE identified_conditions
    ADD COLUMN last_full_run_at TIMESTAMP NULL;

-- Existing rows deliberately stay NULL in all three columns: they predate
-- attribution. NULL supporting_atom_ids ⇒ corpus-fallback fingerprint; NULL
-- last_full_run_at ⇒ stale under the 30-day valve, forcing one full re-rate
-- the first time the scoping flag turns ON (safe — it can only redo work).
