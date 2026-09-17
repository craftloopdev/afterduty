package com.afterduty.service.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.afterduty.model.LlmJob;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;

/**
 * Async LLM provider backed by Anthropic's Message Batches API. Submits all
 * jobs in a list as one batch (one HTTP POST), gets back a single batch_id,
 * and lets the poller worker check status without holding any connection.
 *
 * <p>Pricing: 50% off vs the synchronous Messages API. SLA: typically minutes
 * for small batches, max 24h.
 *
 * <p>API reference: docs.anthropic.com/en/api/creating-message-batches
 *
 * <p>Implements the {@link AnthropicBatchProvider} interface so that tests can
 * assert {@code fakeProvider instanceof AnthropicBatchProvider == false} without
 * a Java compile error.
 */
@Component
public class AnthropicBatchProviderImpl implements AnthropicBatchProvider {

    private static final Logger log = LoggerFactory.getLogger(AnthropicBatchProviderImpl.class);

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .build();

    @Value("${va-claim.claude.api-key:}")
    private String apiKey;

    @Value("${va-claim.claude.base-url:https://api.anthropic.com}")
    private String baseUrl;

    @Value("${va-claim.claude.anthropic-version:2023-06-01}")
    private String anthropicVersion;

    /**
     * Master cache-plumbing flag (Increment 6 / Mission 6a). When OFF, the
     * structured-prompt array shape is never emitted — requests are byte-identical
     * to today's flat-system bytes regardless of whether a structured prompt was
     * attached. Default ON.
     */
    @Value("${va-claim.llm.prompt-caching:true}")
    private boolean promptCachingEnabled;

    @Override
    public String providerName() { return NAME; }

    @Override
    public List<LlmJobHandle> submit(List<LlmJob> jobs) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("ANTHROPIC_API_KEY is not configured");
        }
        if (jobs == null || jobs.isEmpty()) {
            return List.of();
        }
        try {
            List<Map<String, Object>> requests = new ArrayList<>(jobs.size());
            for (LlmJob job : jobs) {
                requests.add(Map.of(
                        "custom_id", job.getId().toString(),
                        "params", buildParams(job)
                ));
            }
            String body = objectMapper.writeValueAsString(Map.of("requests", requests));

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/v1/messages/batches"))
                    .header("x-api-key", apiKey)
                    .header("anthropic-version", anthropicVersion)
                    .header("content-type", "application/json")
                    .timeout(Duration.ofSeconds(60))   // submit only — not waiting for the model
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();

            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                throw new RuntimeException("Anthropic batch create failed " + resp.statusCode() + ": "
                        + resp.body().substring(0, Math.min(500, resp.body().length())));
            }
            JsonNode root = objectMapper.readTree(resp.body());
            String batchId = root.path("id").asText();
            if (batchId.isBlank()) {
                throw new RuntimeException("Anthropic batch response missing id: " + resp.body());
            }
            log.info("Submitted Anthropic batch {} with {} requests", batchId, jobs.size());

            List<LlmJobHandle> handles = new ArrayList<>(jobs.size());
            for (LlmJob job : jobs) {
                handles.add(new LlmJobHandle(job.getId(), batchId));
            }
            return handles;
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Failed to submit Anthropic batch: " + e.getMessage(), e);
        }
    }

    @Override
    public ProviderJobStatus poll(String providerJobId) {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/v1/messages/batches/" + providerJobId))
                    .header("x-api-key", apiKey)
                    .header("anthropic-version", anthropicVersion)
                    .timeout(Duration.ofSeconds(30))
                    .GET()
                    .build();
            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                log.warn("Anthropic batch poll {} returned {}: {}", providerJobId, resp.statusCode(),
                        resp.body().substring(0, Math.min(300, resp.body().length())));
                return ProviderJobStatus.IN_PROGRESS;
            }
            JsonNode root = objectMapper.readTree(resp.body());
            String processingStatus = root.path("processing_status").asText();
            return switch (processingStatus) {
                case "ended" -> {
                    JsonNode counts = root.path("request_counts");
                    long errored = counts.path("errored").asLong(0);
                    long expired = counts.path("expired").asLong(0);
                    long canceled = counts.path("canceled").asLong(0);
                    long succeeded = counts.path("succeeded").asLong(0);
                    if (succeeded == 0 && (errored + expired + canceled) > 0) {
                        yield ProviderJobStatus.FAILED;
                    }
                    yield ProviderJobStatus.SUCCEEDED;
                }
                case "canceling" -> ProviderJobStatus.IN_PROGRESS;
                default -> ProviderJobStatus.IN_PROGRESS;
            };
        } catch (Exception e) {
            log.warn("Anthropic batch poll {} threw: {} — treating as in-progress", providerJobId, e.getMessage());
            return ProviderJobStatus.IN_PROGRESS;
        }
    }

    @Override
    public List<FetchedResult> fetchResults(String providerJobId, List<LlmJob> jobs) {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/v1/messages/batches/" + providerJobId + "/results"))
                    .header("x-api-key", apiKey)
                    .header("anthropic-version", anthropicVersion)
                    .timeout(Duration.ofSeconds(120))  // result download, not model wait
                    .GET()
                    .build();
            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                throw new RuntimeException("Anthropic results fetch " + providerJobId + " returned "
                        + resp.statusCode() + ": " + resp.body());
            }
            // JSONL: one JSON object per line
            Map<UUID, FetchedResult> byId = new HashMap<>();
            for (String line : resp.body().split("\n")) {
                if (line.isBlank()) continue;
                JsonNode entry = objectMapper.readTree(line);
                String customId = entry.path("custom_id").asText();
                UUID jobId;
                try {
                    jobId = UUID.fromString(customId);
                } catch (IllegalArgumentException ex) {
                    log.warn("Unrecognized custom_id in batch results: {}", customId);
                    continue;
                }
                JsonNode result = entry.path("result");
                String type = result.path("type").asText();
                if ("succeeded".equals(type)) {
                    JsonNode message = result.path("message");
                    LlmJobResult r = parseMessage(message);
                    byId.put(jobId, FetchedResult.success(jobId, r));
                } else {
                    String err = result.path("error").path("message").asText("Anthropic batch entry " + type);
                    byId.put(jobId, FetchedResult.failure(jobId, err));
                }
            }
            // Preserve input order
            List<FetchedResult> ordered = new ArrayList<>(jobs.size());
            for (LlmJob j : jobs) {
                FetchedResult r = byId.get(j.getId());
                if (r == null) {
                    ordered.add(FetchedResult.failure(j.getId(), "no entry in batch results"));
                } else {
                    ordered.add(r);
                }
            }
            return ordered;
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Failed to fetch Anthropic batch results: " + e.getMessage(), e);
        }
    }

    @Override
    public void cancel(String providerJobId) {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/v1/messages/batches/" + providerJobId + "/cancel"))
                    .header("x-api-key", apiKey)
                    .header("anthropic-version", anthropicVersion)
                    .timeout(Duration.ofSeconds(15))
                    .POST(HttpRequest.BodyPublishers.noBody())
                    .build();
            httpClient.send(req, HttpResponse.BodyHandlers.discarding());
        } catch (Exception e) {
            log.warn("Cancel of Anthropic batch {} failed: {}", providerJobId, e.getMessage());
        }
    }

    /** Extracts text + token counts from a single batch entry's message body. */
    LlmJobResult parseMessage(JsonNode message) {
        StringBuilder text = new StringBuilder();
        JsonNode content = message.path("content");
        if (content.isArray()) {
            for (JsonNode block : content) {
                if ("text".equals(block.path("type").asText())) {
                    text.append(block.path("text").asText());
                }
            }
        }
        JsonNode usage = message.path("usage");
        // Anthropic reports cache reads/writes separately from input_tokens
        // (input_tokens EXCLUDES them); capture both so the ledger can price them.
        long cacheRead = usage.path("cache_read_input_tokens").asLong(0);
        long cacheWrite = usage.path("cache_creation_input_tokens").asLong(0);
        return new LlmJobResult(
                text.toString(),
                message,
                usage.path("input_tokens").asLong(0),
                usage.path("output_tokens").asLong(0),
                0L,
                cacheRead,
                cacheWrite,
                NAME,
                message.path("model").asText("")
        );
    }

    /** Builds the per-request {@code params} object Anthropic expects inside a batch entry. */
    Map<String, Object> buildParams(LlmJob job) throws Exception {
        // request_payload was stored at submit-time by LlmJobService; deserialize and re-pack
        JsonNode payload = objectMapper.readTree(job.getRequestPayload());
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("model", job.getModelName());
        params.put("max_tokens", payload.path("maxTokens").asInt(16_000));
        // system: legacy flat string OR (caching ON + structured prompt) the
        // Messages-API array form carrying the cache_control breakpoint. Renderer
        // returns null when there's no system content, in which case we omit the
        // field exactly as before. JsonNode goes through convertValue so the
        // outgoing JSON bytes match the typed-SDK lane's bytes.
        JsonNode systemNode = ClaudeSystemRenderer.renderSystem(payload, promptCachingEnabled, objectMapper);
        if (systemNode != null) {
            params.put("system", objectMapper.convertValue(systemNode, Object.class));
        }
        params.put("messages", objectMapper.convertValue(payload.path("messages"), List.class));
        if (payload.has("tools") && payload.path("tools").isArray() && !payload.path("tools").isEmpty()) {
            params.put("tools", objectMapper.convertValue(payload.path("tools"), List.class));
        }
        int thinkingBudget = payload.path("thinkingBudget").asInt(0);
        if (thinkingBudget > 0) {
            params.put("thinking", Map.of("type", "adaptive"));
            params.put("output_config", Map.of("effort", effortFor(thinkingBudget)));
        }
        return params;
    }

    private static String effortFor(int budgetTokens) {
        if (budgetTokens < 4_000) return "low";
        if (budgetTokens < 12_000) return "medium";
        return "high";
    }
}
