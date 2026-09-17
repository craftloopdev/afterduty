package com.afterduty.service.rag;

import java.util.List;

/**
 * The embedding test seam (Increment 7, spec §B.1).
 *
 * <p>Produces 768-dim, L2-normalized vectors for the hybrid-retrieval dense arm.
 * The real implementation ({@link VertexEmbeddingProviderImpl}) calls
 * gemini-embedding-001 over Vertex {@code :predict} with ADC; tests swap in a
 * deterministic {@code FakeEmbeddingProvider}.
 */
public interface EmbeddingProvider {

    /** Vertex {@code task_type} — documents vs. queries embed asymmetrically. */
    enum TaskType {
        RETRIEVAL_DOCUMENT,
        RETRIEVAL_QUERY
    }

    /**
     * 768-dim, L2-normalized embedding of {@code text}.
     *
     * @throws EmbeddingUnavailableException when the lane is down (rag disabled,
     *         pgvector unavailable, or the Vertex call exhausted its retries).
     */
    float[] embed(String text, TaskType task);

    /** Batched {@link #embed} — one returned vector per input, in order. */
    List<float[]> embedBatch(List<String> texts, TaskType task);

    /** False when rag is disabled or pgvector is unavailable (no vector column to write). */
    boolean isAvailable();
}
