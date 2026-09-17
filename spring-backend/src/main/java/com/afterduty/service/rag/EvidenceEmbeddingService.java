package com.afterduty.service.rag;

import com.afterduty.config.PgVectorBootstrap;
import com.afterduty.model.Chunk;
import com.afterduty.model.EvidenceItem;
import com.afterduty.repository.ChunkRepository;
import com.afterduty.repository.EvidenceItemRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * Chunks one processed document and embeds its chunks (Increment 7, spec §B.3).
 *
 * <p>Chunks are derived data: re-extraction = re-chunk (delete-then-insert per
 * evidence; the supersede machinery is NOT used here). The Vertex HTTP embed call
 * must NOT run inside the {@code @Transactional} extraction parse — the
 * {@code ExtractionStateMachine} afterCommit hook (and the backfill job) call this on
 * a virtual thread.
 */
@Service
public class EvidenceEmbeddingService {

    private static final Logger log = LoggerFactory.getLogger(EvidenceEmbeddingService.class);

    private final EvidenceItemRepository evidenceItemRepository;
    private final ChunkRepository chunkRepository;
    private final ChunkingService chunkingService;
    private final EmbeddingProvider embeddingProvider;
    private final PgVectorBootstrap pgVectorBootstrap;
    private final JdbcTemplate jdbcTemplate;

    public EvidenceEmbeddingService(EvidenceItemRepository evidenceItemRepository,
                                    ChunkRepository chunkRepository,
                                    ChunkingService chunkingService,
                                    EmbeddingProvider embeddingProvider,
                                    PgVectorBootstrap pgVectorBootstrap,
                                    JdbcTemplate jdbcTemplate) {
        this.evidenceItemRepository = evidenceItemRepository;
        this.chunkRepository = chunkRepository;
        this.chunkingService = chunkingService;
        this.embeddingProvider = embeddingProvider;
        this.pgVectorBootstrap = pgVectorBootstrap;
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * (Re)chunk and embed a processed document. Bails unless processed. Idempotent:
     * deletes prior chunks first. On provider failure leaves rows {@code pending} for
     * the backfill job; when the provider is unavailable, marks them {@code skipped}
     * (tsvector-only retrieval still works over {@code content}).
     */
    @Transactional
    public void embedEvidence(Long evidenceId) {
        EvidenceItem evidence = evidenceItemRepository.findById(evidenceId).orElse(null);
        if (evidence == null) {
            log.debug("embedEvidence: evidence {} not found", evidenceId);
            return;
        }
        if (!"processed".equals(evidence.getProcessingStatus())) {
            log.debug("embedEvidence: evidence {} not processed (status={}) — skipping",
                    evidenceId, evidence.getProcessingStatus());
            return;
        }

        // Derived data: drop the prior generation, then re-chunk.
        chunkRepository.deleteByEvidenceId(evidenceId);

        List<ChunkingService.ChunkText> texts = chunkingService.chunkEvidence(evidence);
        if (texts.isEmpty()) {
            log.debug("embedEvidence: evidence {} produced zero chunks", evidenceId);
            return;
        }

        String docDate = chunkingService.evidenceDocDate(evidence);
        String sectionPath = chunkingService.evidenceHeader(evidence).strip();
        boolean providerAvailable = embeddingProvider.isAvailable();

        List<Chunk> saved = new ArrayList<>(texts.size());
        for (ChunkingService.ChunkText ct : texts) {
            Chunk chunk = Chunk.builder()
                    .scope("evidence")
                    .claimId(evidence.getClaimId())
                    .evidenceId(evidenceId)
                    .docType(evidence.getAiClassification())
                    .docDate(docDate)
                    .source(evidence.getFilename() != null ? evidence.getFilename() : "document")
                    .sectionPath(sectionPath)
                    .content(ct.content())
                    .tokenCount(ct.tokenCount())
                    .embeddingStatus(providerAvailable ? "pending" : "skipped")
                    .build();
            saved.add(chunkRepository.save(chunk));
        }

        if (!providerAvailable) {
            log.debug("embedEvidence: provider unavailable — {} chunks marked skipped for evidence {}",
                    saved.size(), evidenceId);
            return;
        }

        embedChunks(saved);
    }

    /**
     * Embed already-persisted chunks and write vectors. On provider failure the rows
     * are left {@code pending} (the backfill job retries). Shared by the backfill
     * Pass 2 retry.
     */
    public void embedChunks(List<Chunk> chunks) {
        if (chunks.isEmpty()) return;
        if (!pgVectorBootstrap.isVectorAvailable()) {
            markStatus(chunks, "skipped");
            return;
        }
        try {
            List<String> contents = chunks.stream().map(Chunk::getContent).toList();
            List<float[]> vectors = embeddingProvider.embedBatch(
                    contents, EmbeddingProvider.TaskType.RETRIEVAL_DOCUMENT);
            writeVectors(chunks, vectors);
        } catch (EmbeddingUnavailableException e) {
            // Leave rows pending; the backfill job retries.
            log.debug("embedChunks: provider failure, {} chunks left pending: {}",
                    chunks.size(), e.getMessage());
        }
    }

    /**
     * One {@code batchUpdate} writing {@code embedding} + {@code embedding_status='embedded'}.
     * The vector rides as the pgvector text literal {@code "[a,b,...]"} + {@code CAST(? AS vector)}.
     */
    private void writeVectors(List<Chunk> chunks, List<float[]> vectors) {
        List<Object[]> args = new ArrayList<>(chunks.size());
        for (int i = 0; i < chunks.size(); i++) {
            args.add(new Object[]{toVectorLiteral(vectors.get(i)), chunks.get(i).getId()});
        }
        jdbcTemplate.batchUpdate(
                "UPDATE chunks SET embedding = CAST(? AS vector), embedding_status = 'embedded' WHERE id = ?",
                args);
    }

    private void markStatus(List<Chunk> chunks, String status) {
        for (Chunk c : chunks) {
            c.setEmbeddingStatus(status);
            chunkRepository.save(c);
        }
    }

    /** pgvector text format: {@code "[0.1,0.2,...]"}. */
    static String toVectorLiteral(float[] vec) {
        StringBuilder sb = new StringBuilder(vec.length * 8 + 2);
        sb.append('[');
        for (int i = 0; i < vec.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(vec[i]);
        }
        return sb.append(']').toString();
    }
}
