package com.afterduty.service.rag;

import com.afterduty.config.PgVectorBootstrap;
import com.afterduty.model.Chunk;
import com.afterduty.model.EvidenceItem;
import com.afterduty.repository.ChunkRepository;
import com.afterduty.repository.EvidenceItemRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Evidence chunk-and-embed lifecycle (spec §B.3, §H.1): delete-then-insert per evidence;
 * {@code pending}→{@code embedded} on success; provider-throw leaves rows {@code pending}
 * (backfill retries); provider-unavailable marks rows {@code skipped}. Pure Mockito —
 * repos + JdbcTemplate mocked, {@link FakeEmbeddingProvider} for the lane.
 */
@Tag("regression")
class EvidenceEmbeddingServiceTest {

    private EvidenceItemRepository evidenceRepo;
    private ChunkRepository chunkRepo;
    private ChunkingService chunking;
    private FakeEmbeddingProvider embedder;
    private PgVectorBootstrap boot;
    private JdbcTemplate jdbc;
    private EvidenceEmbeddingService service;

    @BeforeEach
    void setup() {
        evidenceRepo = mock(EvidenceItemRepository.class);
        chunkRepo = mock(ChunkRepository.class);
        chunking = new ChunkingService(600, 120);
        embedder = new FakeEmbeddingProvider();
        boot = mock(PgVectorBootstrap.class);
        jdbc = mock(JdbcTemplate.class);
        service = new EvidenceEmbeddingService(evidenceRepo, chunkRepo, chunking, embedder, boot, jdbc);

        // chunkRepository.save assigns an id so writeVectors has a target.
        AtomicLong seq = new AtomicLong(100);
        when(chunkRepo.save(any(Chunk.class))).thenAnswer(inv -> {
            Chunk c = inv.getArgument(0);
            if (c.getId() == null) c.setId(seq.incrementAndGet());
            return c;
        });
    }

    private EvidenceItem processedEvidence() {
        EvidenceItem ev = new EvidenceItem();
        ev.setId(5L);
        ev.setClaimId(42L);
        ev.setFilename("knee.pdf");
        ev.setAiClassification("C&P exam");
        ev.setProcessingStatus("processed");
        ev.setRawContent("The veteran has chronic right knee pain with limited flexion since 2019.");
        return ev;
    }

    @Test
    void notProcessed_bailsWithoutChunking() {
        EvidenceItem ev = processedEvidence();
        ev.setProcessingStatus("processing");
        when(evidenceRepo.findById(5L)).thenReturn(Optional.of(ev));

        service.embedEvidence(5L);

        verify(chunkRepo, never()).deleteByEvidenceId(any());
        verify(chunkRepo, never()).save(any());
    }

    @Test
    void processed_deletesPriorChunks_thenInsertsScopedEvidenceChunks() {
        when(evidenceRepo.findById(5L)).thenReturn(Optional.of(processedEvidence()));
        when(boot.isVectorAvailable()).thenReturn(true);  // embedder available

        service.embedEvidence(5L);

        // Delete-then-insert: the prior generation is reaped first.
        verify(chunkRepo).deleteByEvidenceId(5L);

        ArgumentCaptor<Chunk> saved = ArgumentCaptor.forClass(Chunk.class);
        verify(chunkRepo, atLeastOnce()).save(saved.capture());
        Chunk c = saved.getValue();
        assertThat(c.getScope()).isEqualTo("evidence");
        assertThat(c.getClaimId()).isEqualTo(42L);
        assertThat(c.getEvidenceId()).isEqualTo(5L);
        assertThat(c.getSource()).isEqualTo("knee.pdf");
        assertThat(c.getDocType()).isEqualTo("C&P exam");
        assertThat(c.getContent()).contains("knee");
    }

    @Test
    void providerAvailable_writesVectors_andMarksEmbedded() {
        when(evidenceRepo.findById(5L)).thenReturn(Optional.of(processedEvidence()));
        when(boot.isVectorAvailable()).thenReturn(true);

        service.embedEvidence(5L);

        // The dense write goes through the single batchUpdate UPDATE ... SET embedding = CAST(? AS vector).
        verify(jdbc).batchUpdate(anyString(), any(List.class));
        // The document task type was used for embedding.
        assertThat(embedder.tasks).contains(EmbeddingProvider.TaskType.RETRIEVAL_DOCUMENT);
    }

    @Test
    void providerUnavailable_marksChunksSkipped_noEmbedding() {
        when(evidenceRepo.findById(5L)).thenReturn(Optional.of(processedEvidence()));
        when(boot.isVectorAvailable()).thenReturn(false);  // no vector column

        service.embedEvidence(5L);

        ArgumentCaptor<Chunk> saved = ArgumentCaptor.forClass(Chunk.class);
        verify(chunkRepo, atLeastOnce()).save(saved.capture());
        assertThat(saved.getAllValues()).allSatisfy(c ->
                assertThat(c.getEmbeddingStatus()).isEqualTo("skipped"));
        // No embedding calls, no vector write.
        assertThat(embedder.callCount).isZero();
        verify(jdbc, never()).batchUpdate(anyString(), any(List.class));
    }

    @Test
    void providerThrows_leavesChunksPending_forBackfill() {
        when(evidenceRepo.findById(5L)).thenReturn(Optional.of(processedEvidence()));
        when(boot.isVectorAvailable()).thenReturn(true);
        embedder.throwing(true);  // embedBatch throws EmbeddingUnavailableException

        service.embedEvidence(5L);

        ArgumentCaptor<Chunk> saved = ArgumentCaptor.forClass(Chunk.class);
        verify(chunkRepo, atLeastOnce()).save(saved.capture());
        // Rows were inserted as pending; the provider failure left them pending (no write).
        assertThat(saved.getAllValues()).allSatisfy(c ->
                assertThat(c.getEmbeddingStatus()).isEqualTo("pending"));
        verify(jdbc, never()).batchUpdate(anyString(), any(List.class));
    }

    @Test
    void zeroChunks_whenNoText_doesNothingAfterDelete() {
        EvidenceItem ev = processedEvidence();
        ev.setRawContent(null);
        ev.setAiSummary(null);
        when(evidenceRepo.findById(5L)).thenReturn(Optional.of(ev));

        service.embedEvidence(5L);

        verify(chunkRepo).deleteByEvidenceId(5L);
        verify(chunkRepo, never()).save(any());
    }

    @Test
    void embedChunks_providerUnavailable_marksSkipped() {
        when(boot.isVectorAvailable()).thenReturn(false);
        Chunk c = Chunk.builder().scope("evidence").source("a").content("text")
                .embeddingStatus("pending").build();
        c.setId(1L);

        service.embedChunks(List.of(c));

        assertThat(c.getEmbeddingStatus()).isEqualTo("skipped");
        verify(jdbc, never()).batchUpdate(anyString(), any(List.class));
    }

    @Test
    void embedChunks_empty_isNoop() {
        service.embedChunks(List.of());
        verify(jdbc, never()).batchUpdate(anyString(), any(List.class));
    }
}
