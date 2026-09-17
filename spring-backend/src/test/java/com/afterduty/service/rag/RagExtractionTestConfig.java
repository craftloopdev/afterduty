package com.afterduty.service.rag;

import com.afterduty.model.Chunk;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

import java.util.List;

/**
 * Test substrate: a no-op {@link EvidenceEmbeddingService} for extraction slice tests.
 *
 * <p>{@code ExtractionStateMachine} gained a constructor dependency on
 * {@link EvidenceEmbeddingService} (Increment 7 §B.3 afterCommit hook). The extraction
 * test slices exercise the parse/persist path, not embedding, so they wire this no-op
 * instead of the real Vertex-backed bean (which would need a live embedding lane). The
 * afterCommit hook only fires when {@code va-claim.rag.enabled=true}; extraction tests
 * leave it default-on, so calls land here harmlessly. Follows the
 * {@code FakeGcsStorageTestConfig} convention.
 */
@TestConfiguration
public class RagExtractionTestConfig {

    @Bean
    public EvidenceEmbeddingService evidenceEmbeddingService() {
        return new NoOpEvidenceEmbeddingService();
    }

    /** Swallows embed calls — extraction tests don't assert on chunks. */
    static class NoOpEvidenceEmbeddingService extends EvidenceEmbeddingService {
        NoOpEvidenceEmbeddingService() {
            super(null, null, null, null, null, null);
        }

        @Override
        public void embedEvidence(Long evidenceId) {
            // no-op
        }

        @Override
        public void embedChunks(List<Chunk> chunks) {
            // no-op
        }
    }
}
