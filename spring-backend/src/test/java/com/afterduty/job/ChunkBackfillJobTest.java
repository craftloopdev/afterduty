package com.afterduty.job;

import com.afterduty.model.Chunk;
import com.afterduty.repository.ChunkRepository;
import com.afterduty.service.rag.EvidenceEmbeddingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Backfill job coverage (spec §B.4, §H.1): Pass 1 chunks processed evidence that has no
 * chunks; Pass 2 re-embeds stale {@code pending} chunks; the job is quiet (no work) when
 * both passes are empty. Guarded by the rag/backfill flags.
 */
@Tag("regression")
class ChunkBackfillJobTest {

    private ChunkRepository chunkRepo;
    private EvidenceEmbeddingService embeddingService;
    private ChunkBackfillJob job;

    @BeforeEach
    void setup() throws Exception {
        chunkRepo = mock(ChunkRepository.class);
        embeddingService = mock(EvidenceEmbeddingService.class);
        job = new ChunkBackfillJob(chunkRepo, embeddingService);
        setField(job, "ragEnabled", true);
        setField(job, "backfillEnabled", true);
        setField(job, "batchSize", 10);
    }

    @Test
    void pass1_chunksProcessedEvidenceWithoutChunks() {
        when(chunkRepo.findProcessedEvidenceIdsWithoutChunks(10)).thenReturn(List.of(1L, 2L, 3L));
        when(chunkRepo.findByEmbeddingStatusAndCreatedAtBeforeOrderByCreatedAtAsc(
                eq("pending"), any(Instant.class), any(Pageable.class))).thenReturn(List.of());

        job.run();

        verify(embeddingService).embedEvidence(1L);
        verify(embeddingService).embedEvidence(2L);
        verify(embeddingService).embedEvidence(3L);
    }

    @Test
    void pass2_reEmbedsStalePendingChunks() {
        when(chunkRepo.findProcessedEvidenceIdsWithoutChunks(anyInt())).thenReturn(List.of());
        Chunk stale = Chunk.builder().scope("evidence").source("a").content("text")
                .embeddingStatus("pending").build();
        stale.setId(7L);
        when(chunkRepo.findByEmbeddingStatusAndCreatedAtBeforeOrderByCreatedAtAsc(
                eq("pending"), any(Instant.class), any(Pageable.class))).thenReturn(List.of(stale));

        int retried = job.pass2RetryPending();

        assertThat(retried).isEqualTo(1);
        verify(embeddingService).embedChunks(List.of(stale));
    }

    @Test
    void quietWhenNothingToDo() {
        when(chunkRepo.findProcessedEvidenceIdsWithoutChunks(anyInt())).thenReturn(List.of());
        when(chunkRepo.findByEmbeddingStatusAndCreatedAtBeforeOrderByCreatedAtAsc(
                any(), any(Instant.class), any(Pageable.class))).thenReturn(List.of());

        job.run();

        verify(embeddingService, never()).embedEvidence(any());
        verify(embeddingService, never()).embedChunks(any());
    }

    @Test
    void disabledFlags_skipEntirely() throws Exception {
        setField(job, "ragEnabled", false);
        job.run();
        verify(chunkRepo, never()).findProcessedEvidenceIdsWithoutChunks(anyInt());

        setField(job, "ragEnabled", true);
        setField(job, "backfillEnabled", false);
        job.run();
        verify(chunkRepo, never()).findProcessedEvidenceIdsWithoutChunks(anyInt());
    }

    @Test
    void pass1_perEvidenceFailureDoesNotAbortBatch() {
        when(chunkRepo.findProcessedEvidenceIdsWithoutChunks(10)).thenReturn(List.of(1L, 2L));
        org.mockito.Mockito.doThrow(new RuntimeException("boom"))
                .when(embeddingService).embedEvidence(1L);

        int chunked = job.pass1ChunkMissing();

        // The batch size is still reported; the second doc still got processed.
        assertThat(chunked).isEqualTo(2);
        verify(embeddingService).embedEvidence(2L);
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field f = ChunkBackfillJob.class.getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }
}
