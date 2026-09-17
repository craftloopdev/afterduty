package com.afterduty.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Guarded pgvector / full-text bootstrap (Increment 7, spec §A.3).
 *
 * <p>{@code ddl-auto: update} creates the {@code chunks} table from the {@link
 * com.afterduty.model.Chunk} entity, but Hibernate has no type for {@code vector(768)}
 * or {@code tsvector}, and a {@code columnDefinition} would make boot fail on any Postgres
 * lacking the {@code vector} extension (and on any non-PG test datasource). This component
 * adds those two columns AFTER ddl-auto, idempotently, and — critically — <b>never fails
 * boot</b>: each step is its own try/catch, so the app degrades (hybrid → full-text →
 * naive ILIKE) instead of refusing to start.
 *
 * <p>Runs on {@link ApplicationReadyEvent} (strictly after ddl-auto has created the table).
 * Every statement is {@code IF NOT EXISTS}-guarded, so repeated boots are harmless. Reads of
 * {@link #isVectorAvailable()} / {@link #isFullTextAvailable()} drive the embedding pipeline
 * (skip embedding when no vector column) and {@code HybridRetrievalService} (query branch).
 */
@Component
public class PgVectorBootstrap {

    private static final Logger log = LoggerFactory.getLogger(PgVectorBootstrap.class);

    private final JdbcTemplate jdbcTemplate;

    private volatile boolean vectorAvailable = false;
    private volatile boolean fullTextAvailable = false;

    public PgVectorBootstrap(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void bootstrap() {
        // Step 1 — the extension. Its own try/catch: failure (not installed,
        // insufficient privilege, non-PG datasource) degrades to full-text only
        // with EXACTLY ONE warn. No rethrow, no retry loop.
        try {
            jdbcTemplate.execute("CREATE EXTENSION IF NOT EXISTS vector");
            vectorAvailable = true;
        } catch (Exception e) {
            vectorAvailable = false;
            log.warn("pgvector unavailable — hybrid retrieval degrades to full-text only: {}",
                    e.getMessage());
        }

        // Step 2 — vector column + HNSW index, only when the extension is present.
        // 768 dims sits well inside the 2,000-dim HNSW ceiling. Its own try/catch so a
        // half-provisioned database (extension present, index DDL refused) still leaves
        // the app on the full-text path rather than crashing.
        if (vectorAvailable) {
            try {
                jdbcTemplate.execute(
                        "ALTER TABLE chunks ADD COLUMN IF NOT EXISTS embedding vector(768)");
                jdbcTemplate.execute(
                        "CREATE INDEX IF NOT EXISTS idx_chunks_embedding_hnsw "
                                + "ON chunks USING hnsw (embedding vector_cosine_ops)");
            } catch (Exception e) {
                vectorAvailable = false;
                log.warn("pgvector column/index provisioning failed — hybrid retrieval "
                        + "degrades to full-text only: {}", e.getMessage());
            }
        }

        // Step 3 — the generated tsvector column + GIN index. ALWAYS attempted, vector
        // or not. Its own try/catch with one warn so an H2/local datasource without
        // tsvector support also degrades (to the naive ILIKE scan) instead of failing.
        try {
            jdbcTemplate.execute(
                    "ALTER TABLE chunks ADD COLUMN IF NOT EXISTS tsv tsvector "
                            + "GENERATED ALWAYS AS (to_tsvector('english', coalesce(content,''))) STORED");
            jdbcTemplate.execute(
                    "CREATE INDEX IF NOT EXISTS idx_chunks_tsv ON chunks USING gin (tsv)");
            fullTextAvailable = true;
        } catch (Exception e) {
            fullTextAvailable = false;
            log.warn("tsvector unavailable — full-text retrieval degrades to naive ILIKE: {}",
                    e.getMessage());
        }

        log.info("PgVectorBootstrap ready (vector={}, fullText={})",
                vectorAvailable, fullTextAvailable);
    }

    /** True when {@code chunks.embedding vector(768)} + the HNSW index exist. */
    public boolean isVectorAvailable() { return vectorAvailable; }

    /** True when {@code chunks.tsv tsvector} + the GIN index exist (the websearch_to_tsquery path). */
    public boolean isFullTextAvailable() { return fullTextAvailable; }
}
