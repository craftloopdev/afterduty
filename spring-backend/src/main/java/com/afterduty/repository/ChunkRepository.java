package com.afterduty.repository;

import com.afterduty.model.Chunk;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.List;

/**
 * Derived deletes/counts + backfill finders only (spec §A.1).
 *
 * <p>All hybrid-retrieval SQL (pgvector ANN + tsvector full-text + RRF) lives in
 * {@code HybridRetrievalService}, NOT here — it touches the {@code embedding}/{@code tsv}
 * columns that the {@link Chunk} entity deliberately does not map, so it must be raw
 * native SQL on a {@code JdbcTemplate}.
 */
public interface ChunkRepository extends JpaRepository<Chunk, Long> {

    /** Re-extraction = re-chunk: clear a document's chunks before re-inserting. */
    void deleteByEvidenceId(Long evidenceId);

    /** KB re-ingest of one section: clear before re-inserting. */
    void deleteByKbSourceAndCfrSection(String kbSource, String cfrSection);

    long countByEvidenceId(Long evidenceId);

    /** Backfill Pass 2: oldest pending chunks of a scope, for embedding retry. */
    List<Chunk> findByScopeAndEmbeddingStatusOrderByCreatedAtAsc(
            String scope, String embeddingStatus, Pageable pageable);

    /**
     * Backfill Pass 2: pending chunks older than {@code cutoff} (so freshly-inserted
     * pending rows still mid-flight in the afterCommit hook aren't double-embedded).
     */
    List<Chunk> findByEmbeddingStatusAndCreatedAtBeforeOrderByCreatedAtAsc(
            String embeddingStatus, Instant cutoff, Pageable pageable);

    /**
     * Backfill Pass 1: processed evidence ids that have zero chunks yet. Native
     * NOT EXISTS keeps this a single cheap query — both backfills old claims and
     * self-heals any missed afterCommit hook.
     */
    @Query(value = """
            SELECT e.id FROM evidence_items e
            WHERE e.processing_status = 'processed'
              AND NOT EXISTS (SELECT 1 FROM chunks c WHERE c.evidence_id = e.id)
            ORDER BY e.id
            LIMIT :limit
            """, nativeQuery = true)
    List<Long> findProcessedEvidenceIdsWithoutChunks(int limit);
}
