-- V20260612__chunks_vasrd_kb.sql
-- Increment 7 — Chat Rebuild, backend-infra (spec §A). Adds the hybrid-retrieval
-- substrate: the `chunks` table (evidence + KB passages, scope-discriminated), the
-- structured `vasrd_records` (deterministic Part-4 rating tiers), and the
-- `kb_sections` nightly-diff watermark.
--
-- NOTE: Flyway is not currently configured in this project; ddl-auto=update
-- creates/updates the schema from JPA entities. This file exists so a future
-- Flyway switch has the canonical migration to apply. The Chunk / VasrdRecord /
-- KbSection entities have been added in lockstep so ddl-auto=update creates these
-- tables (minus the vector/tsv columns — see below) on next boot.
--
-- OWNERSHIP OF embedding/tsv: the `chunks.embedding vector(768)` and `chunks.tsv
-- tsvector` columns (plus their HNSW / GIN indexes and the `vector` extension) are
-- NOT mapped by the Chunk entity and are NOT created by ddl-auto. They are created,
-- idempotently and degrading gracefully, by config/PgVectorBootstrap on
-- ApplicationReadyEvent. Hibernate therefore neither creates nor fights them, and a
-- Postgres without the `vector` extension (or a non-PG test datasource) still boots —
-- retrieval simply degrades to full-text, then to naive ILIKE. The CREATE EXTENSION /
-- vector / tsv statements are reproduced here for documentation only.
--
-- pgvector PRIVILEGE NOTE (smoke risk §I.5): `CREATE EXTENSION vector` may require
-- cloudsqlsuperuser on Cloud SQL. If the app role cannot create it, run it once
-- manually; PgVectorBootstrap degrades to tsvector-only until then, by design.

-- ---------------------------------------------------------------------------
-- chunks — one table for both corpora, discriminated by `scope`.
-- ---------------------------------------------------------------------------
CREATE TABLE chunks (
    id               BIGSERIAL PRIMARY KEY,
    scope            VARCHAR(255) NOT NULL,          -- 'evidence' | 'kb'
    claim_id         BIGINT,                          -- required when scope=evidence; NULL for kb
    evidence_id      BIGINT,                          -- FK → evidence_items(id) ON DELETE CASCADE
    kb_source        VARCHAR(255),                    -- 'vasrd' | 'presumptives' (later: 'm21-1','dbq')
    cfr_section      VARCHAR(255),                    -- e.g. '4.71a', '3.309' — KB only
    doc_type         VARCHAR(255),                    -- evidence: aiClassification; kb: 'regulation'
    doc_date         VARCHAR(255),                    -- evidence: best-effort document_date else null
    source           VARCHAR(255) NOT NULL,           -- evidence: filename; kb: 'ecfr'
    as_of_date       DATE,                            -- KB: the eCFR point-in-time date (citation freshness)
    section_path     VARCHAR(255),                    -- contextual header (doc title / CFR heading)
    content          TEXT NOT NULL,                   -- chunk text incl. contextual header
    token_count      INTEGER,                         -- approx chars/4
    embedding_status VARCHAR(255) NOT NULL DEFAULT 'pending',  -- pending | embedded | skipped
    created_at       TIMESTAMP NOT NULL,
    CONSTRAINT fk_chunk_evidence FOREIGN KEY (evidence_id)
        REFERENCES evidence_items(id) ON DELETE CASCADE
);

CREATE INDEX idx_chunks_scope_claim    ON chunks (scope, claim_id);
CREATE INDEX idx_chunks_scope_kbsource ON chunks (scope, kb_source);
CREATE INDEX idx_chunks_evidence       ON chunks (evidence_id);
CREATE INDEX idx_chunks_scope_cfr      ON chunks (scope, cfr_section);

-- Owned by PgVectorBootstrap (documentation only; see header):
CREATE EXTENSION IF NOT EXISTS vector;
ALTER TABLE chunks ADD COLUMN IF NOT EXISTS embedding vector(768);
CREATE INDEX IF NOT EXISTS idx_chunks_embedding_hnsw
    ON chunks USING hnsw (embedding vector_cosine_ops);
ALTER TABLE chunks ADD COLUMN IF NOT EXISTS tsv tsvector
    GENERATED ALWAYS AS (to_tsvector('english', coalesce(content,''))) STORED;
CREATE INDEX IF NOT EXISTS idx_chunks_tsv ON chunks USING gin (tsv);

-- ---------------------------------------------------------------------------
-- vasrd_records — deterministic Part-4 rating tiers (one row per dc_code+tier).
-- ---------------------------------------------------------------------------
CREATE TABLE vasrd_records (
    id            BIGSERIAL PRIMARY KEY,
    dc_code       VARCHAR(255) NOT NULL,    -- 4-digit diagnostic code, e.g. '5260'
    title         TEXT,                      -- condition name
    body_system   VARCHAR(255),              -- e.g. 'musculoskeletal'
    cfr_section   VARCHAR(255) NOT NULL,     -- e.g. '4.71a'
    rating_pct    INTEGER,                   -- null for note-only rows
    criteria_text TEXT,
    as_of_date    DATE NOT NULL,             -- eCFR issue date of the ingest
    display_order INTEGER                    -- preserves schedule order within a DC
);

CREATE INDEX idx_vasrd_dc_code     ON vasrd_records (dc_code);
CREATE INDEX idx_vasrd_cfr_section ON vasrd_records (cfr_section);

-- ---------------------------------------------------------------------------
-- kb_sections — nightly-diff watermark per eCFR section.
-- ---------------------------------------------------------------------------
CREATE TABLE kb_sections (
    id                 BIGSERIAL PRIMARY KEY,
    part               INTEGER NOT NULL,        -- 3 or 4
    section_identifier VARCHAR(255) NOT NULL,   -- e.g. '4.71a'
    last_issue_date    DATE,                     -- latest eCFR /versions issue_date ingested
    content_hash       VARCHAR(255),             -- SHA-256 of the section XML
    last_ingested_at   TIMESTAMP,
    CONSTRAINT uq_kb_section_part_id UNIQUE (part, section_identifier)
);
