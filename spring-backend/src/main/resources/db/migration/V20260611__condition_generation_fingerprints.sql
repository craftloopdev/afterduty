-- V20260611__condition_generation_fingerprints.sql
-- Mission 5b — analysis delta: condition generations with atomic supersede,
-- stable identity, and dirty-scope carry-forward. Adds two SHA-256 fingerprint
-- columns to identified_conditions:
--
--   identity_fingerprint  Normalized (vasrd code | body system/category |
--                         connection theory | laterality) — laterality parsed
--                         from the condition name so bilateral / left / right /
--                         a dual-site pairing stay DISTINCT across generations
--                         (same DC must not collapse). Links an old generation's
--                         condition to its replacement in the new generation
--                         (superseded_by points the old row at the matching new
--                         row), so user-facing references survive re-analysis.
--
--   evidence_fingerprint  SHA-256 of the sorted live atom ids/values feeding the
--                         condition's rating prompt + prompt/model versions. A
--                         new-generation condition whose identity AND evidence
--                         fingerprints both match a prior completed active
--                         condition is CLEAN: its rating/verification/gap outputs
--                         are carried forward and it submits NO rate/gap jobs.
--
-- Both are computed in ConditionGenerationService and written when a condition
-- row is persisted during a run. The existing (unused) superseded_by column is
-- now honored: when a run COMPLETEs, the prior generation's active rows are
-- marked superseded in the SAME transaction that activates the new generation,
-- so no reader ever sees zero or two generations.
--
-- NOTE: Flyway is not currently configured in this project; ddl-auto=update
-- creates/updates the schema from JPA entities. This file exists so a future
-- Flyway switch has the canonical migration to apply. The IdentifiedCondition
-- entity has been updated in lockstep so ddl-auto=update adds both columns on
-- next boot.

ALTER TABLE identified_conditions
    ADD COLUMN identity_fingerprint VARCHAR(255) NULL;

ALTER TABLE identified_conditions
    ADD COLUMN evidence_fingerprint VARCHAR(255) NULL;

-- Existing rows deliberately stay NULL: they were written before generations
-- existed, so they have no fingerprints to match against. The first incremental
-- synthesis run after this change computes fingerprints for the new generation;
-- because the prior generation's rows have NULL identity_fingerprint they can
-- never carry-forward-match, so that first run re-rates everything once (safe —
-- it can only redo work, never under-rate) and supersedes the old rows. After
-- that, unchanged conditions carry forward.
--
-- ROLLBACK: the feature is gated by va-claim.analysis.incremental
-- (INCREMENTAL_ANALYSIS). With the flag OFF the columns are never read or
-- written, conditions are appended without supersede (today's exact semantics),
-- and the active-generation readers' `superseded_by IS NULL` filter is a no-op
-- because nothing is ever superseded. The columns can remain in place
-- harmlessly; dropping them is optional and only safe with the flag OFF.
