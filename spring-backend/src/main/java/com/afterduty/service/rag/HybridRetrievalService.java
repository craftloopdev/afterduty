package com.afterduty.service.rag;

import com.afterduty.config.PgVectorBootstrap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;

import java.sql.Date;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Hybrid retrieval — pgvector ANN + tsvector full-text fused with RRF in one SQL
 * statement per call (Increment 7, spec §D). This is the contract the chat agent's
 * grounding tools code against.
 *
 * <p><b>Per-claim isolation (hard requirement, §D.2):</b> {@code searchEvidence} binds
 * {@code claim_id} in BOTH arms (asserted non-null at entry); {@code searchKb} binds
 * {@code claim_id IS NULL} so a coding error cannot leak evidence into KB results.
 * Evidence and KB are NEVER fused in one SQL call.
 *
 * <p><b>Degraded modes (§D.3):</b> no vector column (or a per-query embedding failure)
 * ⇒ tsvector-only ({@code ts_rank_cd}); no tsvector (non-PG datasource, i.e. tests)
 * ⇒ naive {@code ILIKE} scan, capped — exists only so local boots don't NPE.
 */
@Service
public class HybridRetrievalService {

    private static final Logger log = LoggerFactory.getLogger(HybridRetrievalService.class);

    /** Rate-limit the per-query embedding-failure WARN (§D.3) — one per window. */
    private static final long WARN_WINDOW_MS = 60_000L;

    private final JdbcTemplate jdbcTemplate;
    private final EmbeddingProvider embeddingProvider;
    private final PgVectorBootstrap pgVectorBootstrap;

    @Value("${va-claim.rag.retrieval.top-k:12}")
    private int topK;

    @Value("${va-claim.rag.retrieval.candidate-k:40}")
    private int candidateK;

    @Value("${va-claim.rag.retrieval.rrf-k:60}")
    private int rrfK;

    private final AtomicLong lastEmbedWarn = new AtomicLong(0);

    public HybridRetrievalService(JdbcTemplate jdbcTemplate,
                                  EmbeddingProvider embeddingProvider,
                                  PgVectorBootstrap pgVectorBootstrap) {
        this.jdbcTemplate = jdbcTemplate;
        this.embeddingProvider = embeddingProvider;
        this.pgVectorBootstrap = pgVectorBootstrap;
    }

    // -------------------------------------------------------------------------
    // Public API — the frozen contract (§D, §I.1)
    // -------------------------------------------------------------------------

    /** Search the veteran's own document chunks for a claim. {@code claimId} must be non-null. */
    public List<RetrievedChunk> searchEvidence(Long claimId, String query, int k) {
        if (claimId == null) {
            throw new IllegalArgumentException("searchEvidence requires a non-null claimId");
        }
        if (query == null || query.isBlank()) return List.of();
        int limit = clampK(k);
        return search("evidence", claimId, null, query, limit);
    }

    /** Search the global KB (38 CFR). {@code claimId} is always NULL on this path. */
    public List<RetrievedChunk> searchKb(String query, String kbSourceOrNull, int k) {
        if (query == null || query.isBlank()) return List.of();
        int limit = clampK(k);
        return search("kb", null, kbSourceOrNull, query, limit);
    }

    private int clampK(int k) {
        if (k <= 0) return topK;
        return Math.min(k, topK);
    }

    // -------------------------------------------------------------------------
    // Branch selection
    // -------------------------------------------------------------------------

    private List<RetrievedChunk> search(String scope, Long claimId, String kbSource,
                                        String query, int limit) {
        // Branch 1: hybrid (vector + tsvector) when the vector column exists and the
        // query embedding succeeds.
        if (pgVectorBootstrap.isVectorAvailable()) {
            float[] queryVec = tryEmbedQuery(query);
            if (queryVec != null) {
                return hybridSearch(scope, claimId, kbSource, query, queryVec, limit);
            }
            // embedding failed → fall through to tsvector-only
        }
        // Branch 2: tsvector-only.
        if (pgVectorBootstrap.isFullTextAvailable()) {
            log.debug("retrieval: tsvector-only branch (scope={})", scope);
            return textOnlySearch(scope, claimId, kbSource, query, limit);
        }
        // Branch 3: naive ILIKE (non-PG datasource — tests/dev only).
        log.debug("retrieval: naive ILIKE branch (scope={})", scope);
        return ilikeSearch(scope, claimId, kbSource, query, limit);
    }

    private float[] tryEmbedQuery(String query) {
        try {
            return embeddingProvider.embed(query, EmbeddingProvider.TaskType.RETRIEVAL_QUERY);
        } catch (RuntimeException e) {
            long now = System.currentTimeMillis();
            long prev = lastEmbedWarn.get();
            if (now - prev > WARN_WINDOW_MS && lastEmbedWarn.compareAndSet(prev, now)) {
                log.warn("query embedding unavailable — retrieval degrades to full-text: {}",
                        e.getMessage());
            }
            return null;
        }
    }

    // -------------------------------------------------------------------------
    // Branch 1 — the ONE hybrid RRF SQL (§D.1)
    // -------------------------------------------------------------------------

    private List<RetrievedChunk> hybridSearch(String scope, Long claimId, String kbSource,
                                              String query, float[] queryVec, int limit) {
        String vectorLiteral = EvidenceEmbeddingService.toVectorLiteral(queryVec);
        String kbFilter = kbSource != null ? " AND kb_source = ? " : "";

        // pgvector cosine ANN + websearch_to_tsquery full-text, fused with RRF
        // (k = rrf-k). claim_id bound in BOTH arms (NULL for KB). The vec arm filters
        // embedding IS NOT NULL; the txt arm joins websearch_to_tsquery('english', ?).
        String sql = """
                WITH vec AS (
                    SELECT id, ROW_NUMBER() OVER (ORDER BY embedding <=> CAST(? AS vector)) AS r
                    FROM chunks
                    WHERE scope = ? AND (claim_id = ? OR ? IS NULL)
                      AND embedding IS NOT NULL
                """ + kbFilter + """
                    ORDER BY embedding <=> CAST(? AS vector)
                    LIMIT ?
                ),
                txt AS (
                    SELECT id, ROW_NUMBER() OVER (ORDER BY ts_rank_cd(tsv, q) DESC) AS r
                    FROM chunks, websearch_to_tsquery('english', ?) q
                    WHERE scope = ? AND (claim_id = ? OR ? IS NULL)
                      AND tsv @@ q
                """ + kbFilter + """
                    LIMIT ?
                )
                SELECT c.*, (COALESCE(1.0/(? + f.vr), 0) + COALESCE(1.0/(? + f.tr), 0)) AS rrf_score
                FROM chunks c
                JOIN (SELECT COALESCE(vec.id, txt.id) AS id, vec.r AS vr, txt.r AS tr
                      FROM vec FULL OUTER JOIN txt ON vec.id = txt.id) f ON f.id = c.id
                ORDER BY rrf_score DESC
                LIMIT ?
                """;

        List<Object> args = new ArrayList<>();
        // vec CTE
        args.add(vectorLiteral);                 // <=> CAST(? AS vector) in ROW_NUMBER
        args.add(scope);                         // scope = ?
        args.add(claimId);                       // claim_id = ?
        args.add(claimId);                       // ? IS NULL
        if (kbSource != null) args.add(kbSource); // kb_source = ?
        args.add(vectorLiteral);                 // ORDER BY embedding <=> CAST(? AS vector)
        args.add(candidateK);                    // LIMIT candidate-k
        // txt CTE
        args.add(query);                         // websearch_to_tsquery('english', ?)
        args.add(scope);                         // scope = ?
        args.add(claimId);                       // claim_id = ?
        args.add(claimId);                       // ? IS NULL
        if (kbSource != null) args.add(kbSource); // kb_source = ?
        args.add(candidateK);                    // LIMIT candidate-k
        // RRF scoring + final limit
        args.add(rrfK);                          // 1.0/(? + vec.r)
        args.add(rrfK);                          // 1.0/(? + txt.r)
        args.add(limit);                         // final LIMIT top-k

        return jdbcTemplate.query(sql, CHUNK_ROW_MAPPER, args.toArray());
    }

    // -------------------------------------------------------------------------
    // Branch 2 — tsvector-only (§D.3)
    // -------------------------------------------------------------------------

    private List<RetrievedChunk> textOnlySearch(String scope, Long claimId, String kbSource,
                                                String query, int limit) {
        String kbFilter = kbSource != null ? " AND kb_source = ? " : "";
        String sql = """
                SELECT c.*, ts_rank_cd(c.tsv, q) AS rrf_score
                FROM chunks c, websearch_to_tsquery('english', ?) q
                WHERE c.scope = ? AND (c.claim_id = ? OR ? IS NULL)
                  AND c.tsv @@ q
                """ + kbFilter + """
                ORDER BY rrf_score DESC
                LIMIT ?
                """;
        List<Object> args = new ArrayList<>();
        args.add(query);
        args.add(scope);
        args.add(claimId);
        args.add(claimId);
        if (kbSource != null) args.add(kbSource);
        args.add(limit);
        return jdbcTemplate.query(sql, CHUNK_ROW_MAPPER, args.toArray());
    }

    // -------------------------------------------------------------------------
    // Branch 3 — naive ILIKE (§D.3, tests/dev only)
    // -------------------------------------------------------------------------

    private List<RetrievedChunk> ilikeSearch(String scope, Long claimId, String kbSource,
                                             String query, int limit) {
        String kbFilter = kbSource != null ? " AND kb_source = ? " : "";
        String sql = """
                SELECT c.*, 0.0 AS rrf_score
                FROM chunks c
                WHERE c.scope = ? AND (c.claim_id = ? OR ? IS NULL)
                  AND c.content ILIKE ?
                """ + kbFilter + """
                ORDER BY c.id DESC
                LIMIT ?
                """;
        List<Object> args = new ArrayList<>();
        args.add(scope);
        args.add(claimId);
        args.add(claimId);
        args.add("%" + query.strip() + "%");
        if (kbSource != null) args.add(kbSource);
        args.add(limit);
        return jdbcTemplate.query(sql, CHUNK_ROW_MAPPER, args.toArray());
    }

    // -------------------------------------------------------------------------
    // Row mapping
    // -------------------------------------------------------------------------

    private static final RowMapper<RetrievedChunk> CHUNK_ROW_MAPPER = (rs, rowNum) -> {
        Date asOf = rs.getDate("as_of_date");
        return new RetrievedChunk(
                rs.getLong("id"),
                rs.getString("scope"),
                (Long) rs.getObject("claim_id"),
                (Long) rs.getObject("evidence_id"),
                rs.getString("kb_source"),
                rs.getString("cfr_section"),
                rs.getString("source"),
                rs.getString("doc_type"),
                rs.getString("doc_date"),
                asOf != null ? asOf.toLocalDate() : null,
                rs.getString("section_path"),
                rs.getString("content"),
                rs.getDouble("rrf_score")
        );
    };
}
