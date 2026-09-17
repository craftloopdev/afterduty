package com.afterduty.service.rag;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.afterduty.config.PgVectorBootstrap;
import com.afterduty.service.AiCostService;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Vertex embedding provider request/response mapping (spec §B.1, §H.1). Fully offline:
 * exercises {@code buildRequestJson} (the {@code outputDimensionality:768} request shape
 * and per-instance {@code task_type}), {@code parseVectors} (alt response shapes), and
 * {@code normalize} (L2 unit-norm after MRL truncation). The live :predict call,
 * batch-split-over-the-wire, and bounded 429 retry are covered by the §H.4 live smoke
 * ({@code VertexEmbeddingLiveSmokeTest}); the network transport is not unit-mockable here
 * (private final HttpClient, same as VertexAnthropicProviderImplTest).
 */
@Tag("regression")
class VertexEmbeddingProviderImplTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private VertexEmbeddingProviderImpl newProvider(boolean ragEnabled, boolean vectorAvailable) throws Exception {
        PgVectorBootstrap boot = mock(PgVectorBootstrap.class);
        when(boot.isVectorAvailable()).thenReturn(vectorAvailable);
        AiCostService cost = mock(AiCostService.class);
        VertexEmbeddingProviderImpl p = new VertexEmbeddingProviderImpl(boot, cost);
        setField(p, "ragEnabled", ragEnabled);
        setField(p, "projectId", "test-project");
        setField(p, "location", "us-central1");
        setField(p, "model", "gemini-embedding-001");
        setField(p, "dimensions", 768);
        return p;
    }

    @Test
    void buildRequestJson_hasOutputDimensionality768_andTaskTypePerInstance() throws Exception {
        VertexEmbeddingProviderImpl p = newProvider(true, true);
        String body = p.buildRequestJson(
                List.of("knee pain since 2019", "service treatment record"),
                EmbeddingProvider.TaskType.RETRIEVAL_DOCUMENT);

        JsonNode root = mapper.readTree(body);
        assertThat(root.path("parameters").path("outputDimensionality").asInt()).isEqualTo(768);

        JsonNode instances = root.path("instances");
        assertThat(instances.isArray()).isTrue();
        assertThat(instances.size()).isEqualTo(2);
        assertThat(instances.get(0).path("task_type").asText()).isEqualTo("RETRIEVAL_DOCUMENT");
        assertThat(instances.get(0).path("content").asText()).isEqualTo("knee pain since 2019");
        assertThat(instances.get(1).path("content").asText()).isEqualTo("service treatment record");
    }

    @Test
    void buildRequestJson_queryTaskTypeIsDistinct() throws Exception {
        VertexEmbeddingProviderImpl p = newProvider(true, true);
        String body = p.buildRequestJson(List.of("q"), EmbeddingProvider.TaskType.RETRIEVAL_QUERY);
        JsonNode root = mapper.readTree(body);
        assertThat(root.path("instances").get(0).path("task_type").asText()).isEqualTo("RETRIEVAL_QUERY");
    }

    @Test
    void parseVectors_readsEmbeddingsValues_andL2Normalizes() throws Exception {
        VertexEmbeddingProviderImpl p = newProvider(true, true);
        String json = """
                {"predictions":[{"embeddings":{"values":[3.0,4.0]}}]}
                """;
        List<float[]> vecs = p.parseVectors(json, 1);
        assertThat(vecs).hasSize(1);
        // [3,4] has norm 5 → normalized [0.6, 0.8].
        assertThat(vecs.get(0)[0]).isCloseTo(0.6f, org.assertj.core.data.Offset.offset(1e-5f));
        assertThat(vecs.get(0)[1]).isCloseTo(0.8f, org.assertj.core.data.Offset.offset(1e-5f));
        assertThat(magnitude(vecs.get(0))).isCloseTo(1.0, org.assertj.core.data.Offset.offset(1e-5));
    }

    @Test
    void parseVectors_toleratesAlternateEmbeddingShape() throws Exception {
        VertexEmbeddingProviderImpl p = newProvider(true, true);
        String json = """
                {"predictions":[{"embedding":{"values":[0.0,5.0]}}]}
                """;
        List<float[]> vecs = p.parseVectors(json, 1);
        assertThat(vecs.get(0)[1]).isCloseTo(1.0f, org.assertj.core.data.Offset.offset(1e-5f));
    }

    @Test
    void parseVectors_predictionCountMismatch_throwsUnavailable() throws Exception {
        VertexEmbeddingProviderImpl p = newProvider(true, true);
        String json = """
                {"predictions":[{"embeddings":{"values":[1.0]}}]}
                """;
        assertThatThrownBy(() -> p.parseVectors(json, 2))
                .isInstanceOf(EmbeddingUnavailableException.class);
    }

    @Test
    void normalize_makesUnitVector_andZeroVectorStaysZero() {
        float[] v = VertexEmbeddingProviderImpl.normalize(new float[]{6f, 8f});
        assertThat(magnitude(v)).isCloseTo(1.0, org.assertj.core.data.Offset.offset(1e-5));

        float[] zero = VertexEmbeddingProviderImpl.normalize(new float[]{0f, 0f, 0f});
        assertThat(zero).containsExactly(0f, 0f, 0f);
    }

    @Test
    void isAvailable_falseWhenRagDisabled_orVectorUnavailable() throws Exception {
        assertThat(newProvider(false, true).isAvailable()).isFalse();   // rag off
        assertThat(newProvider(true, false).isAvailable()).isFalse();   // no vector column
        assertThat(newProvider(true, true).isAvailable()).isTrue();
    }

    @Test
    void embedBatch_ragDisabled_throwsUnavailable() throws Exception {
        VertexEmbeddingProviderImpl p = newProvider(false, true);
        assertThatThrownBy(() -> p.embedBatch(List.of("x"), EmbeddingProvider.TaskType.RETRIEVAL_DOCUMENT))
                .isInstanceOf(EmbeddingUnavailableException.class);
    }

    @Test
    void embedBatch_emptyInput_returnsEmpty() throws Exception {
        VertexEmbeddingProviderImpl p = newProvider(true, true);
        assertThat(p.embedBatch(List.of(), EmbeddingProvider.TaskType.RETRIEVAL_DOCUMENT)).isEmpty();
    }

    private static double magnitude(float[] v) {
        double s = 0;
        for (float x : v) s += (double) x * x;
        return Math.sqrt(s);
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field f = VertexEmbeddingProviderImpl.class.getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }
}
