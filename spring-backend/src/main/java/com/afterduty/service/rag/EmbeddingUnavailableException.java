package com.afterduty.service.rag;

/**
 * Thrown by {@link EmbeddingProvider} when the embedding lane is down — rag
 * disabled, pgvector unavailable, or the Vertex call exhausted its bounded retries.
 * Callers degrade: the embedding pipeline leaves chunks {@code pending}/{@code skipped}
 * for the backfill job; {@code HybridRetrievalService} falls back to the tsvector arm.
 */
public class EmbeddingUnavailableException extends RuntimeException {
    public EmbeddingUnavailableException(String message) {
        super(message);
    }

    public EmbeddingUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
