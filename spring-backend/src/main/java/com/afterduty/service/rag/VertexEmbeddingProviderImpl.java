package com.afterduty.service.rag;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.auth.oauth2.GoogleCredentials;
import com.afterduty.config.PgVectorBootstrap;
import com.afterduty.model.AiCallLog;
import com.afterduty.service.AiCostService;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * gemini-embedding-001 over Vertex {@code :predict} with ADC (Increment 7, spec §B.1).
 *
 * <p>Transport mirrors {@code VertexGeminiAsyncProviderImpl} exactly — plain
 * {@link java.net.http.HttpClient} + {@code GoogleCredentials.getApplicationDefault()
 * .createScoped(cloud-platform)} — so there is <b>no new SDK dependency and no
 * Claude-quota dependency</b>. Vectors are truncated to 768 dims via the model's MRL
 * {@code outputDimensionality} and re-normalized (truncation leaves them non-unit-norm).
 * Every request books one {@link AiCallLog} row via {@link AiCostService}.
 */
@Component
public class VertexEmbeddingProviderImpl implements EmbeddingProvider {

    private static final Logger log = LoggerFactory.getLogger(VertexEmbeddingProviderImpl.class);

    /** predict API instance ceiling per request (spec §B.1). */
    private static final int BATCH_LIMIT = 25;

    /** Bounded retry on 429/503 — reuse the ChatAgent cap; never unbounded. */
    private static final int MAX_429_ATTEMPTS = 3;
    private static final long RETRY_BACKOFF_MS = 5_000L;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .build();

    private final PgVectorBootstrap pgVectorBootstrap;
    private final AiCostService aiCostService;

    @Value("${va-claim.rag.enabled:true}")
    private boolean ragEnabled;

    @Value("${va-claim.vertex.project-id:}")
    private String projectId;

    @Value("${va-claim.rag.embedding.location:us-central1}")
    private String location;

    @Value("${va-claim.rag.embedding.model:gemini-embedding-001}")
    private String model;

    @Value("${va-claim.rag.embedding.dimensions:768}")
    private int dimensions;

    private GoogleCredentials credentials;

    public VertexEmbeddingProviderImpl(PgVectorBootstrap pgVectorBootstrap, AiCostService aiCostService) {
        this.pgVectorBootstrap = pgVectorBootstrap;
        this.aiCostService = aiCostService;
    }

    @PostConstruct
    public void init() {
        try {
            credentials = GoogleCredentials.getApplicationDefault()
                    .createScoped("https://www.googleapis.com/auth/cloud-platform");
            log.info("VertexEmbeddingProviderImpl ready (project={}, location={}, model={}, dim={})",
                    projectId, location, model, dimensions);
        } catch (Exception e) {
            log.error("Vertex embedding credentials init failed: {}", e.getMessage(), e);
        }
    }

    @Override
    public boolean isAvailable() {
        // No vector column to write into ⇒ no point embedding; rag-off short-circuits too.
        return ragEnabled && pgVectorBootstrap.isVectorAvailable();
    }

    @Override
    public float[] embed(String text, TaskType task) {
        return embedBatch(List.of(text), task).get(0);
    }

    @Override
    public List<float[]> embedBatch(List<String> texts, TaskType task) {
        if (!ragEnabled) {
            throw new EmbeddingUnavailableException("rag disabled");
        }
        if (texts == null || texts.isEmpty()) return List.of();

        List<float[]> out = new ArrayList<>(texts.size());
        for (int start = 0; start < texts.size(); start += BATCH_LIMIT) {
            List<String> slice = texts.subList(start, Math.min(texts.size(), start + BATCH_LIMIT));
            out.addAll(embedSlice(slice, task));
        }
        return out;
    }

    private List<float[]> embedSlice(List<String> texts, TaskType task) {
        long startedAt = System.currentTimeMillis();
        String body = buildRequestJson(texts, task);

        RuntimeException last = null;
        for (int attempt = 1; attempt <= MAX_429_ATTEMPTS; attempt++) {
            try {
                List<float[]> vectors = callPredict(body, texts.size());
                bookCost(texts, startedAt);
                return vectors;
            } catch (RetryableEmbeddingException e) {
                last = e;
                if (attempt == MAX_429_ATTEMPTS) break;
                log.warn("Vertex embedding rate-limited/unavailable — backing off {}s (attempt {}/{})",
                        RETRY_BACKOFF_MS / 1000, attempt, MAX_429_ATTEMPTS);
                try {
                    Thread.sleep(RETRY_BACKOFF_MS);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new EmbeddingUnavailableException("interrupted during embedding retry", ie);
                }
            } catch (EmbeddingUnavailableException e) {
                throw e;
            } catch (Exception e) {
                throw new EmbeddingUnavailableException("Vertex embedding failed: " + e.getMessage(), e);
            }
        }
        throw new EmbeddingUnavailableException(
                "Vertex embedding rate-limited/unavailable " + MAX_429_ATTEMPTS + " times", last);
    }

    /** Visible for testing — the exact request JSON shape. */
    String buildRequestJson(List<String> texts, TaskType task) {
        try {
            List<Map<String, Object>> instances = new ArrayList<>(texts.size());
            for (String text : texts) {
                Map<String, Object> instance = new LinkedHashMap<>();
                instance.put("task_type", task.name());
                instance.put("content", text);
                instances.add(instance);
            }
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("instances", instances);
            payload.put("parameters", Map.of("outputDimensionality", dimensions));
            return objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            throw new EmbeddingUnavailableException("failed to build embedding request: " + e.getMessage(), e);
        }
    }

    private List<float[]> callPredict(String body, int expected) throws Exception {
        credentials.refreshIfExpired();
        String accessToken = credentials.getAccessToken().getTokenValue();

        String url = String.format(
                "https://%s-aiplatform.googleapis.com/v1/projects/%s/locations/%s/publishers/google/models/%s:predict",
                location, projectId, location, model);

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(60))
                .header("Authorization", "Bearer " + accessToken)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
        int status = resp.statusCode();
        if (status == 429 || status == 503) {
            throw new RetryableEmbeddingException("Vertex embedding HTTP " + status);
        }
        if (status != 200) {
            String snippet = resp.body() == null ? "" :
                    resp.body().substring(0, Math.min(500, resp.body().length()));
            throw new EmbeddingUnavailableException("Vertex embedding HTTP " + status + ": " + snippet);
        }
        return parseVectors(resp.body(), expected);
    }

    /** Visible for testing — parse + L2-normalize the predicted vectors. */
    List<float[]> parseVectors(String json, int expected) throws Exception {
        JsonNode root = objectMapper.readTree(json);
        JsonNode predictions = root.path("predictions");
        if (!predictions.isArray() || predictions.size() != expected) {
            throw new EmbeddingUnavailableException(
                    "Vertex embedding returned " + predictions.size() + " predictions, expected " + expected);
        }
        List<float[]> out = new ArrayList<>(expected);
        for (JsonNode pred : predictions) {
            // gemini-embedding-001 :predict returns {"embeddings":{"values":[...]}}.
            JsonNode values = pred.path("embeddings").path("values");
            if (!values.isArray()) {
                values = pred.path("embedding").path("values");  // tolerate the alt shape
            }
            float[] vec = new float[values.size()];
            for (int i = 0; i < values.size(); i++) {
                vec[i] = (float) values.get(i).asDouble();
            }
            out.add(normalize(vec));
        }
        return out;
    }

    /** L2-normalize in place (MRL truncation to 768 leaves vectors non-unit-norm). */
    static float[] normalize(float[] vec) {
        double sumSq = 0.0;
        for (float v : vec) sumSq += (double) v * v;
        double norm = Math.sqrt(sumSq);
        if (norm == 0.0) return vec;
        for (int i = 0; i < vec.length; i++) {
            vec[i] = (float) (vec[i] / norm);
        }
        return vec;
    }

    private void bookCost(List<String> texts, long startedAt) {
        try {
            long inputTokens = 0;
            for (String t : texts) inputTokens += ChunkingService.estimateTokens(t);
            AiCallLog logRow = AiCallLog.builder()
                    .callType("embedding")
                    .provider("vertex-gemini")
                    .modelName(model)
                    .inputTokens(inputTokens)
                    .outputTokens(0L)
                    .latencyMs(System.currentTimeMillis() - startedAt)
                    .status("success")
                    .build();
            aiCostService.recordCall(logRow);
        } catch (Exception e) {
            // Ledger booking must never break the embedding path.
            log.debug("embedding cost booking skipped: {}", e.getMessage());
        }
    }

    /** Internal signal: a transient (429/503) failure that should be retried. */
    private static final class RetryableEmbeddingException extends RuntimeException {
        RetryableEmbeddingException(String message) { super(message); }
    }
}
