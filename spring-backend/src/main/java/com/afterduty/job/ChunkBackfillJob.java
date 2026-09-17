package com.afterduty.job;

import com.afterduty.model.Chunk;
import com.afterduty.repository.ChunkRepository;
import com.afterduty.service.rag.EvidenceEmbeddingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * Backfill embeddings for existing claims + self-heal missed afterCommit hooks
 * (Increment 7, spec §B.4).
 *
 * <p>Two passes per tick, both bounded by {@code batch-size}:
 * <ol>
 *   <li><b>Chunking</b> — processed evidence with zero chunks → {@code embedEvidence}.
 *       Backfills old claims AND recovers any document whose afterCommit hook was
 *       missed (JVM restart, rag toggled on later).</li>
 *   <li><b>Embedding retry</b> — chunks stuck {@code pending} older than 5 minutes →
 *       re-embed.</li>
 * </ol>
 * Quiet when there is nothing to do (DEBUG). Steady-state cost: one cheap COUNT per minute.
 */
@Component
public class ChunkBackfillJob {

    private static final Logger log = LoggerFactory.getLogger(ChunkBackfillJob.class);

    /** Pending chunks younger than this are still mid-flight in the hook — leave them. */
    private static final long PENDING_STALE_MINUTES = 5;

    private final ChunkRepository chunkRepository;
    private final EvidenceEmbeddingService evidenceEmbeddingService;

    @Value("${va-claim.rag.enabled:true}")
    private boolean ragEnabled;

    @Value("${va-claim.rag.backfill.enabled:true}")
    private boolean backfillEnabled;

    @Value("${va-claim.rag.backfill.batch-size:10}")
    private int batchSize;

    public ChunkBackfillJob(ChunkRepository chunkRepository,
                            EvidenceEmbeddingService evidenceEmbeddingService) {
        this.chunkRepository = chunkRepository;
        this.evidenceEmbeddingService = evidenceEmbeddingService;
    }

    @Scheduled(fixedDelayString = "${va-claim.rag.backfill.poll-ms:60000}")
    public void run() {
        if (!ragEnabled || !backfillEnabled) return;

        int chunked = pass1ChunkMissing();
        int retried = pass2RetryPending();

        if (chunked > 0 || retried > 0) {
            log.info("ChunkBackfillJob: chunked {} evidence doc(s), retried {} pending chunk(s)",
                    chunked, retried);
        } else {
            log.debug("ChunkBackfillJob: nothing to do");
        }
    }

    /** Pass 1 — processed evidence with no chunks yet. */
    int pass1ChunkMissing() {
        List<Long> evidenceIds = chunkRepository.findProcessedEvidenceIdsWithoutChunks(batchSize);
        for (Long id : evidenceIds) {
            try {
                evidenceEmbeddingService.embedEvidence(id);
            } catch (Exception e) {
                log.warn("ChunkBackfillJob: chunking evidence {} failed: {}", id, e.getMessage());
            }
        }
        return evidenceIds.size();
    }

    /** Pass 2 — pending chunks older than the stale cutoff. */
    int pass2RetryPending() {
        Instant cutoff = Instant.now().minus(PENDING_STALE_MINUTES, ChronoUnit.MINUTES);
        List<Chunk> stale = chunkRepository.findByEmbeddingStatusAndCreatedAtBeforeOrderByCreatedAtAsc(
                "pending", cutoff, PageRequest.of(0, batchSize));
        if (stale.isEmpty()) return 0;
        try {
            evidenceEmbeddingService.embedChunks(stale);
        } catch (Exception e) {
            log.warn("ChunkBackfillJob: re-embedding {} pending chunk(s) failed: {}",
                    stale.size(), e.getMessage());
        }
        return stale.size();
    }
}
