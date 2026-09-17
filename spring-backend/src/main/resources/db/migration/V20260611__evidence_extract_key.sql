-- V20260611__evidence_extract_key.sql
-- Mission 5a — extraction delta. Add the content-addressed extract_key to
-- evidence_items so the single-pass extraction fan-out can skip documents whose
-- bytes, extraction prompt version, output schema version, and routed model id
-- are all unchanged since their last successful extraction. The key is a
-- SHA-256 hex digest of (file_hash-or-text-content-hash, prompt_version,
-- schema_version, extraction_doc model id), computed in
-- SinglePassExtractionService.computeExtractKey and written ONLY after a
-- document's parse+persist succeeds.
--
-- NOTE: Flyway is not currently configured in this project; ddl-auto=update
-- creates/updates the schema from JPA entities. This file exists so a future
-- Flyway switch has the canonical migration to apply. The EvidenceItem entity
-- has been updated in lockstep so ddl-auto=update adds `extract_key` on next
-- boot.

ALTER TABLE evidence_items
    ADD COLUMN extract_key VARCHAR(255) NULL;

-- Existing rows deliberately stay NULL: a null extract_key reads as "never
-- successfully keyed", so the first incremental run re-extracts every existing
-- document once (superseding its prior atoms and writing the key), after which
-- unchanged documents are skipped. This one-time re-extraction is the safe
-- migration path — it can never under-extract, only redo work once.
--
-- ROLLBACK: the feature is gated by va-claim.analysis.incremental
-- (INCREMENTAL_ANALYSIS). With the flag OFF the column is never read or written
-- and behavior reverts to full re-extract-everything; the column can remain in
-- place harmlessly. Dropping it is optional and only safe with the flag OFF.
