package com.afterduty.service.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.auth.oauth2.GoogleCredentials;
import com.afterduty.model.LlmJob;
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
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;

/**
 * Async LLM provider for Google Gemini on Vertex AI.
 *
 * <p>Vertex's true Batch Prediction API has a 24h SLA and requires GCS bucket
 * I/O — too slow for an interactive pipeline. Instead, this provider spawns
 * a {@link Thread#ofVirtual virtual thread} per submission that calls Gemini
 * with streaming enabled. Streaming has no read timeout: each token chunk
 * resets the deadline, so the call can run as long as the model needs.
 * Virtual threads are essentially free, so holding one per in-flight call is
 * cheap.
 *
 * <p>Results are buffered in an in-memory map keyed by job UUID. If the JVM
 * restarts mid-call, the in-memory result is lost — but
 * {@code LlmJobPoller}'s orphan-recovery path detects {@code SUBMITTED} rows
 * past their deadline and requeues them. No data loss across restarts.
 *
 * <p>Implements the {@link VertexGeminiAsyncProvider} interface so that tests can
 * assert {@code fakeProvider instanceof VertexGeminiAsyncProvider == false} without
 * a Java compile error.
 */
@Component
public class VertexGeminiAsyncProviderImpl implements VertexGeminiAsyncProvider {

    private static final Logger log = LoggerFactory.getLogger(VertexGeminiAsyncProviderImpl.class);

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .build();

    private final Map<UUID, AsyncCall> inflight = new ConcurrentHashMap<>();

    @Value("${va-claim.gemini.project-id:}")
    private String projectId;

    @Value("${va-claim.gemini.location:global}")
    private String location;

    @Value("${va-claim.gemini.model:gemini-3.1-pro-preview}")
    private String defaultModelName;

    @Value("${va-claim.gemini.thinking-budget:10240}")
    private int defaultThinkingBudget;

    /**
     * Upper bound on inline payloads materialised at once. Each call base64-inlines
     * its whole document; seventeen at once (two of them ~12 MB PDFs) exhausted a
     * 512 MB heap on 2026-09-12. Permits are taken inside the worker thread, so
     * {@link #submit} stays non-blocking and waiting jobs report IN_PROGRESS.
     */
    @Value("${va-claim.gemini.max-concurrent:4}")
    private int maxConcurrent = 4;
    private volatile Semaphore inlinePermits = new Semaphore(4);

    /** Test seam — rebuilds the permit pool. Not for production use. */
    void setMaxConcurrent(int n) {
        this.maxConcurrent = n;
        this.inlinePermits = new Semaphore(n);
    }

    /** Test seam — permits not currently held by a worker thread. */
    int availableInlinePermits() {
        return inlinePermits.availablePermits();
    }

    private GoogleCredentials credentials;

    @PostConstruct
    public void init() {
        inlinePermits = new Semaphore(Math.max(1, maxConcurrent));
        try {
            credentials = GoogleCredentials.getApplicationDefault()
                    .createScoped("https://www.googleapis.com/auth/cloud-platform");
            log.info("VertexGeminiAsyncProviderImpl ready (project={}, location={})", projectId, location);
        } catch (Exception e) {
            log.error("Vertex/Gemini credentials init failed: {}", e.getMessage(), e);
        }
    }

    @Override
    public String providerName() { return NAME; }

    @Override
    public List<LlmJobHandle> submit(List<LlmJob> jobs) {
        List<LlmJobHandle> handles = new ArrayList<>(jobs.size());
        for (LlmJob job : jobs) {
            // For Gemini we use the internal job UUID as the providerJobId — there is no
            // server-side batch identifier, each call is independent.
            String providerJobId = job.getId().toString();
            AsyncCall call = new AsyncCall();
            inflight.put(job.getId(), call);
            Thread.ofVirtual().name("gemini-async-" + job.getId()).start(() -> runOne(job, call));
            handles.add(new LlmJobHandle(job.getId(), providerJobId));
        }
        return handles;
    }

    @Override
    public ProviderJobStatus poll(String providerJobId) {
        UUID id;
        try { id = UUID.fromString(providerJobId); } catch (Exception e) { return ProviderJobStatus.FAILED; }
        AsyncCall call = inflight.get(id);
        if (call == null) {
            // Lost (JVM restart). Caller (LlmJobPoller's orphan path) will requeue.
            return ProviderJobStatus.FAILED;
        }
        if (!call.done) return ProviderJobStatus.IN_PROGRESS;
        return call.error != null ? ProviderJobStatus.FAILED : ProviderJobStatus.SUCCEEDED;
    }

    @Override
    public List<FetchedResult> fetchResults(String providerJobId, List<LlmJob> jobs) {
        List<FetchedResult> out = new ArrayList<>(jobs.size());
        for (LlmJob job : jobs) {
            AsyncCall call = inflight.remove(job.getId());
            if (call == null) {
                out.add(FetchedResult.failure(job.getId(), "in-memory result lost (JVM restart?)"));
                continue;
            }
            if (call.error != null) {
                out.add(FetchedResult.failure(job.getId(), call.error));
            } else {
                out.add(FetchedResult.success(job.getId(), call.result));
            }
        }
        return out;
    }

    @Override
    public void cancel(String providerJobId) {
        // Best-effort: drop the in-memory record. The virtual thread will finish on its own
        // but its result will be discarded since the row is no longer SUBMITTED.
        try { inflight.remove(UUID.fromString(providerJobId)); } catch (Exception ignored) {}
    }

    /**
     * Worker-thread entry. Two guarantees, both learned on 2026-09-12 when three
     * extraction threads died in an {@link OutOfMemoryError} and the stage never ended:
     * <ul>
     *   <li>The call is ALWAYS marked done. An {@code Error} is not an {@code Exception};
     *       the old {@code catch (Exception)} let it escape, {@code done} stayed false,
     *       and {@link #poll} answered IN_PROGRESS forever.</li>
     *   <li>At most {@code maxConcurrent} inline payloads exist at once. The permit is
     *       taken here, on the worker, so {@link #submit} never blocks and a waiting job
     *       honestly reports IN_PROGRESS.</li>
     * </ul>
     */
    private void runOne(LlmJob job, AsyncCall call) {
        Semaphore permits = inlinePermits;
        boolean acquired = false;
        try {
            permits.acquire();
            acquired = true;
            execute(job, call);
        } catch (Throwable t) {
            String reason = t.getClass().getSimpleName()
                    + (t.getMessage() != null ? ": " + t.getMessage() : "");
            log.error("Gemini async call {} died: {}", job.getId(), reason);
            call.failWith(reason);
        } finally {
            if (acquired) permits.release();
            if (!call.done) call.failWith("Gemini call ended without a result");
        }
    }

    private void execute(LlmJob job, AsyncCall call) {
        try {
            JsonNode payload = objectMapper.readTree(job.getRequestPayload());
            String systemPrompt = payload.path("systemPrompt").asText("");
            // Gemini ignores the Claude cache_control breakpoint (Gemini 3.x caches
            // implicitly server-side). When a request carries only the cache-aware
            // structured prompt (no flat systemPrompt), flatten its blocks + corpus
            // into the system text so the prompt content is preserved — exactly the
            // "concatenate blocks into the prompt as today" contract (Mission 6a).
            if (systemPrompt.isBlank() && ClaudeSystemRenderer.structuredPromptNode(payload) != null) {
                systemPrompt = ClaudeSystemRenderer.flatSystem(payload);
            }
            String userMessage = payload.path("userMessage").asText("");
            int maxTokens = payload.path("maxTokens").asInt(65536);
            int thinkingBudget = payload.path("thinkingBudget").asInt(defaultThinkingBudget);
            String modelName = job.getModelName() != null && !job.getModelName().isBlank()
                    ? job.getModelName() : defaultModelName;

            Map<String, Object> body = new LinkedHashMap<>();
            if (!systemPrompt.isBlank()) {
                body.put("systemInstruction", Map.of("parts", List.of(Map.of("text", systemPrompt))));
            }
            // Optional multimodal inline-data parts (Mission B): for PDF/image
            // documents the bytes ride alongside the text as Gemini inlineData
            // parts on the single user turn. Null/absent for the legacy text path.
            List<Map<String, Object>> inlineParts = new ArrayList<>();
            if (payload.path("inlineData").isArray()) {
                for (JsonNode part : payload.path("inlineData")) {
                    String mimeType = part.path("mimeType").asText("application/octet-stream");
                    String data = part.path("data").asText("");
                    if (!data.isEmpty()) {
                        inlineParts.add(Map.of("inlineData",
                                Map.of("mimeType", mimeType, "data", data)));
                    }
                }
            }

            // Prefer messages[] if provided; otherwise wrap userMessage as a single user turn.
            if (payload.path("messages").isArray() && !payload.path("messages").isEmpty()
                    && inlineParts.isEmpty()) {
                List<Map<String, Object>> contents = new ArrayList<>();
                for (JsonNode msg : payload.path("messages")) {
                    String role = msg.path("role").asText("user");
                    Object content = msg.path("content").isTextual()
                            ? msg.path("content").asText()
                            : objectMapper.convertValue(msg.path("content"), Object.class);
                    contents.add(Map.of(
                            "role", "assistant".equals(role) ? "model" : role,
                            "parts", List.of(Map.of("text", content.toString()))
                    ));
                }
                body.put("contents", contents);
            } else {
                // Single user turn. With inline data present, the text part leads
                // and the document parts follow (the order Gemini expects).
                List<Map<String, Object>> parts = new ArrayList<>();
                parts.add(Map.of("text", userMessage));
                parts.addAll(inlineParts);
                body.put("contents", List.of(Map.of("role", "user", "parts", parts)));
            }
            Map<String, Object> genConfig = new LinkedHashMap<>();
            genConfig.put("temperature", 0.2);
            genConfig.put("maxOutputTokens", maxTokens);
            if (thinkingBudget > 0) {
                genConfig.put("thinkingConfig", Map.of("thinkingBudget", thinkingBudget));
            }
            // Optional structured output (Mission B): a responseSchema forces a
            // single JSON object conformant to the schema. responseMimeType must be
            // application/json for the schema to take effect on Vertex Gemini.
            JsonNode schemaNode = payload.path("responseSchema");
            if (schemaNode.isObject() && !schemaNode.isEmpty()) {
                genConfig.put("responseMimeType", "application/json");
                genConfig.put("responseSchema",
                        objectMapper.convertValue(schemaNode, Object.class));
            }
            body.put("generationConfig", genConfig);

            credentials.refreshIfExpired();
            String accessToken = credentials.getAccessToken().getTokenValue();

            // streamGenerateContent uses SSE — each chunk arriving keeps the read clock alive.
            String url = String.format(
                    "https://aiplatform.googleapis.com/v1/projects/%s/locations/%s/publishers/google/models/%s:streamGenerateContent?alt=sse",
                    projectId, location, modelName);

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Authorization", "Bearer " + accessToken)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                    .build();

            HttpResponse<java.io.InputStream> resp =
                    httpClient.send(req, HttpResponse.BodyHandlers.ofInputStream());

            if (resp.statusCode() != 200) {
                String err;
                try (var s = resp.body()) {
                    // Truncate to the BODY's length, not a constant: substring(0, 500)
                    // on a shorter error body throws StringIndexOutOfBoundsException and
                    // replaces the real provider error with "Range [0, 500) out of
                    // bounds" (exactly what masked the 2026-06-12T0746 eval failure).
                    String raw = new String(s.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                    err = raw.substring(0, Math.min(500, raw.length()));
                }
                call.failWith("Gemini stream HTTP " + resp.statusCode() + ": " + err);
                return;
            }

            StringBuilder text = new StringBuilder();
            long inputTokens = 0;
            long outputTokens = 0;
            long thinkingTokens = 0;
            JsonNode lastChunk = null;

            try (var reader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(resp.body(), java.nio.charset.StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.isEmpty() || !line.startsWith("data:")) continue;
                    String json = line.substring(5).trim();
                    if (json.isEmpty() || "[DONE]".equals(json)) continue;
                    JsonNode chunk = objectMapper.readTree(json);
                    lastChunk = chunk;
                    JsonNode candidates = chunk.path("candidates");
                    if (candidates.isArray() && !candidates.isEmpty()) {
                        JsonNode parts = candidates.get(0).path("content").path("parts");
                        for (JsonNode part : parts) {
                            if (part.path("thought").asBoolean(false)) continue;
                            if (part.has("text")) text.append(part.get("text").asText());
                        }
                    }
                    JsonNode usage = chunk.path("usageMetadata");
                    if (!usage.isMissingNode()) {
                        inputTokens = usage.path("promptTokenCount").asLong(inputTokens);
                        outputTokens = usage.path("candidatesTokenCount").asLong(outputTokens);
                        long thoughts = usage.path("thoughtsTokenCount").asLong(0);
                        long total = usage.path("totalTokenCount").asLong(0);
                        if (thoughts > 0) thinkingTokens = thoughts;
                        else if (total > inputTokens + outputTokens) thinkingTokens = total - inputTokens - outputTokens;
                    }
                }
            }

            if (text.length() == 0) {
                call.failWith("Gemini returned no text content");
                return;
            }
            LlmJobResult result = new LlmJobResult(
                    text.toString(), lastChunk, inputTokens, outputTokens, thinkingTokens,
                    NAME, job.getModelName());
            call.completeWith(result);
        } catch (Exception e) {
            log.error("Gemini async call {} failed: {}", job.getId(), e.getMessage(), e);
            call.failWith(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
    }

    /** Tracks the in-memory state of one Gemini streaming call. */
    static final class AsyncCall {
        volatile boolean done;
        volatile LlmJobResult result;
        volatile String error;

        void completeWith(LlmJobResult r) {
            this.result = r;
            this.done = true;
        }

        void failWith(String err) {
            this.error = err;
            this.done = true;
        }
    }
}
