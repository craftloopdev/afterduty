package com.afterduty.service.llm;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.JsonField;
import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.MessageParam;
import com.anthropic.models.messages.ToolUnion;
import com.anthropic.models.messages.Usage;
import com.anthropic.vertex.backends.VertexBackend;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.auth.oauth2.GoogleCredentials;
import com.afterduty.model.LlmJob;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Realtime async LLM provider for Claude models on Vertex AI.
 *
 * <p>Wires the {@code com.anthropic:anthropic-java-vertex} SDK (build.gradle.kts) against the
 * <b>{@code us} multi-region</b> Vertex endpoint ({@code aiplatform.us.rep.googleapis.com}) — the
 * HIPAA-minded US data-residency decision updated 2026-08-01: post-May-2026 Claude models
 * (Sonnet 5, Opus 4.8) are served only from the global/us/eu multi-region endpoints, never from
 * us-east5, and {@code us} keeps ML processing inside US jurisdiction. Multi-region bills the same
 * +10% premium over global that regional endpoints did, which {@link
 * com.afterduty.service.AiCostService} prices in. Authentication uses Application Default
 * Credentials, the same chain {@link VertexGeminiAsyncProviderImpl} uses, so there is no
 * {@code ANTHROPIC_API_KEY}: billing and IAM stay inside GCP. Region comes from
 * {@code va-claim.vertex.claude-region} (default {@code us}; {@code us-east5} remains valid for the
 * older per-version models; never point PHI at {@code global} — no residency guarantee).
 *
 * <h2>Why realtime, not the 24h batch lane</h2>
 * The architecture routes <em>verify</em> (Opus) and <em>chat</em> here as realtime, and Sonnet
 * synthesis/gap stages here too until Vertex-batch caching is confirmed (design Part 4 / Increment 6).
 * {@link #isBatch()} is {@code false} so {@link AiCostService} books list price, not the 50% lane.
 *
 * <h2>Durability (addresses W7 — strictly better than the Gemini provider)</h2>
 * The {@link VertexGeminiAsyncProviderImpl} buffers results in an in-memory map and runs the call on
 * a background virtual thread, so a JVM restart mid-call loses the stream (orphan recovery re-pays).
 * This provider holds <b>no in-memory state at all</b>. The durable result already lives in
 * {@code llm_jobs.response_payload} (written by {@code LlmJobPoller} after fetch), so we lean on that:
 * <ul>
 *   <li>{@link #submit} only mints a handle — no I/O, no background thread.</li>
 *   <li>{@link #poll} always reports SUCCEEDED so the poller proceeds to fetch in the same tick.</li>
 *   <li>{@link #fetchResults} performs the actual Vertex calls on <b>virtual threads joined
 *       before return</b> — synchronous to the poller (nothing outlives the method) but
 *       parallel across jobs. With no same-prefix priming group the poller's open transaction
 *       is held for the slowest single call; with a primed group (see below) it is held for
 *       <em>lead + slowest-tail</em> — two sequential network phases, not one. That doubling is
 *       inherent to priming (the cache must be written before it can be read) and is bounded to
 *       one extra call because all group leads share a single concurrent priming phase. Like
 *       every other provider, fetch already runs inside the poller's transaction across network
 *       I/O; priming holds <b>no DB state</b> of its own, so the only cost is the extra phase's
 *       latency on that already-open transaction, capped at one round-trip.</li>
 * </ul>
 * Because the call is synchronous and stateless, there is no "lost stream" window: a crash before
 * fetch leaves the row {@code SUBMITTED}; orphan recovery requeues it (the poller nulls
 * {@code providerJobId}/{@code submittedAt}, the submitter re-mints a handle) and the next tick
 * simply re-runs the call. The result becomes durable exactly when every other provider's does —
 * when {@code response_payload} is written — so restart semantics are uniform and idempotent.
 *
 * <p>Implements {@link VertexAnthropicProvider} so tests can assert
 * {@code fake instanceof VertexAnthropicProvider == false} at compile time, mirroring the other two.
 */
@Component
public class VertexAnthropicProviderImpl implements VertexAnthropicProvider {

    private static final Logger log = LoggerFactory.getLogger(VertexAnthropicProviderImpl.class);

    /** Bounded 429 / transient retries — never unbounded (W9). */
    private static final int MAX_RETRIES = 3;
    private static final long BASE_BACKOFF_MS = 1_000L;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${va-claim.vertex.project-id:}")
    private String projectId;

    /** `us` multi-region by residency decision (2026-08-01). See class javadoc. */
    @Value("${va-claim.vertex.claude-region:us}")
    private String region;

    @Value("${va-claim.vertex.anthropic.default-model:claude-sonnet-5}")
    private String defaultModelName;

    /**
     * Master cache-plumbing flag (Increment 6 / Mission 6a). OFF → the structured
     * prompt's cache-aware array shape is never emitted; the request is the flat
     * system-string bytes shipped today. Default ON.
     */
    @Value("${va-claim.llm.prompt-caching:true}")
    boolean promptCachingEnabled = true;

    /** Visible for tests; lazily created so a missing ADC chain doesn't break app startup. */
    AnthropicClient client;

    @PostConstruct
    public void init() {
        try {
            this.client = buildClient();
            log.info("VertexAnthropicProviderImpl ready (project={}, region={}, default-model={})",
                    projectId, region, defaultModelName);
        } catch (Exception e) {
            // Mirror the Gemini provider: log and continue. The first call will surface the error
            // as a job FAILED (recorded), not an app boot failure.
            log.error("Vertex Anthropic client init failed: {}", e.getMessage(), e);
        }
    }

    /** Builds the typed Anthropic client backed by the configured Vertex region + ADC. */
    private AnthropicClient buildClient() throws Exception {
        GoogleCredentials credentials = GoogleCredentials.getApplicationDefault()
                .createScoped("https://www.googleapis.com/auth/cloud-platform");
        VertexBackend backend = VertexBackend.builder()
                .googleCredentials(credentials)
                .project(projectId)
                .region(region)
                .build();
        return AnthropicOkHttpClient.builder()
                .backend(backend)
                .build();
    }

    @Override
    public String providerName() { return NAME; }

    @Override
    public List<LlmJobHandle> submit(List<LlmJob> jobs) {
        // No I/O at submit time — like Gemini, the internal job UUID is the providerJobId.
        // The real call happens synchronously in fetchResults() so there's no detached work to lose.
        //
        // Mission 6b — REALTIME CACHE PRIMING is wired HERE, at submission, because this
        // is the one place the same-run fan-out is visible as a SET (the submitter hands
        // us all jobs sharing a (provider, batch_group_key) — e.g. every per-condition rate
        // call for one claim — in a single submit() list). The cache-priming in
        // {@link #fetchResults} only engages when the poller delivers a >1-job list; but the
        // poller groups active jobs by providerJobId ({@code findActiveByProviderJobId}).
        // If every job got its OWN unique providerJobId (the old behaviour), each same-prefix
        // job would be fetched ALONE, fetchResults would always see a singleton, and priming
        // would be dead code (the leads/tails collapse). So same-prefix jobs must SHARE a
        // providerJobId to land in one poll group.
        //
        // We therefore assign a SHARED providerJobId (the group lead's UUID) to every member
        // of a same-prefix group of size > 1; singletons and no-breakpoint jobs keep their own
        // UUID (today's exact shape). The shared id is still a valid UUID, so {@link #poll}'s
        // UUID.fromString guard passes; orphan recovery still re-queues per row; and the
        // {@link LlmAsyncProvider} contract already documents "providerJobId may map to multiple
        // LlmJobs". When caching is OFF, prefixCacheKeyFor() is null for every job → every job
        // is its own group → byte-for-byte today's one-UUID-per-job submission (flag-off purity).
        Map<String, List<LlmJob>> byPrefix = new LinkedHashMap<>();
        List<LlmJob> ungrouped = new ArrayList<>();
        for (LlmJob job : jobs) {
            String key = prefixCacheKeyFor(job);
            if (key == null) {
                ungrouped.add(job);                         // no breakpoint → never shares an id
            } else {
                byPrefix.computeIfAbsent(key, k -> new ArrayList<>()).add(job);
            }
        }

        // Resolve each job's providerJobId: own UUID, unless it's in a >1 same-prefix group,
        // in which case it shares that group's lead UUID so the poller fetches the group together.
        Map<UUID, String> providerJobIdByJob = new HashMap<>();
        for (LlmJob j : ungrouped) {
            providerJobIdByJob.put(j.getId(), j.getId().toString());
        }
        for (List<LlmJob> group : byPrefix.values()) {
            if (group.size() > 1) {
                String shared = group.get(0).getId().toString();   // lead's UUID = the poll-group id
                for (LlmJob j : group) providerJobIdByJob.put(j.getId(), shared);
            } else {
                LlmJob solo = group.get(0);
                providerJobIdByJob.put(solo.getId(), solo.getId().toString());
            }
        }

        List<LlmJobHandle> handles = new ArrayList<>(jobs.size());
        for (LlmJob job : jobs) {
            handles.add(new LlmJobHandle(job.getId(), providerJobIdByJob.get(job.getId())));
        }
        return handles;
    }

    @Override
    public ProviderJobStatus poll(String providerJobId) {
        // Stateless realtime provider: the call hasn't run yet (it runs in fetchResults). A valid
        // job id is always "ready to fetch"; the poller fetches immediately after a non-IN_PROGRESS
        // poll, so report SUCCEEDED to drive the fetch where the synchronous work happens.
        try { UUID.fromString(providerJobId); } catch (Exception e) { return ProviderJobStatus.FAILED; }
        return ProviderJobStatus.SUCCEEDED;
    }

    @Override
    public List<LlmAsyncProvider.FetchedResult> fetchResults(String providerJobId, List<LlmJob> jobs) {
        // Mission 6b — REALTIME CACHE PRIMING. Jobs that share an identical cached
        // prefix (same atom-corpus block, e.g. all rate calls in one run) must not
        // all race as cold writers: the Anthropic cache entry is only readable once
        // the first response has begun, so N concurrent first-callers each pay the
        // full WRITE price (research §5, the fan-out concurrency gotcha). Instead, for
        // each same-prefix group of >1, run ONE job to completion FIRST (it writes the
        // cache), then fan the rest in parallel so they READ it at 0.1×.
        //
        // Scope guards (everything else unchanged):
        //   - only groups whose prefix hash matches AND that emit a breakpoint
        //     (prefixCacheKey != null) are primed; null-key jobs run in the normal
        //     parallel fan-out;
        //   - a group of size 1 has nothing to prime FOR, so it also just runs parallel;
        //   - lead jobs across different groups run concurrently (one sequential
        //     priming phase, not one per group), so added latency is bounded to a
        //     single extra call, not the sum.
        Map<String, List<LlmJob>> groups = new LinkedHashMap<>();
        List<LlmJob> ungrouped = new ArrayList<>();
        for (LlmJob job : jobs) {
            String key = prefixCacheKeyFor(job);
            if (key == null) {
                ungrouped.add(job);
            } else {
                groups.computeIfAbsent(key, k -> new ArrayList<>()).add(job);
            }
        }

        Map<UUID, LlmAsyncProvider.FetchedResult> results = new ConcurrentHashMap<>();

        // Phase 1 — prime each multi-member group's lead job, all leads concurrently.
        List<LlmJob> leads = new ArrayList<>();
        List<LlmJob> tails = new ArrayList<>(ungrouped);
        for (List<LlmJob> group : groups.values()) {
            if (group.size() > 1) {
                leads.add(group.get(0));
                tails.addAll(group.subList(1, group.size()));
            } else {
                tails.addAll(group);   // single-member group: no priming benefit
            }
        }
        if (!leads.isEmpty()) {
            runAllParallel(leads, results);   // joins before returning: cache now warm
        }

        // Phase 2 — everything else (ungrouped + all group tails) in parallel; the
        // tails of primed groups now hit a warm cache and read at 0.1×.
        runAllParallel(tails, results);

        // Reassemble in the poller's input order (the contract every provider keeps).
        List<LlmAsyncProvider.FetchedResult> out = new ArrayList<>(jobs.size());
        for (LlmJob job : jobs) {
            LlmAsyncProvider.FetchedResult r = results.get(job.getId());
            out.add(r != null ? r
                    : LlmAsyncProvider.FetchedResult.failure(job.getId(), "no result produced"));
        }
        return out;
    }

    /** Runs every job on its own virtual thread, joining before return; results go into {@code sink}. */
    private void runAllParallel(List<LlmJob> jobs, Map<UUID, LlmAsyncProvider.FetchedResult> sink) {
        if (jobs.isEmpty()) return;
        List<CompletableFuture<Void>> futures = new ArrayList<>(jobs.size());
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (LlmJob job : jobs) {
                futures.add(CompletableFuture.runAsync(() -> {
                    LlmAsyncProvider.FetchedResult fr;
                    try {
                        CallOutcome outcome = runOne(job);
                        fr = outcome.error != null
                                ? LlmAsyncProvider.FetchedResult.failure(job.getId(), outcome.error)
                                : LlmAsyncProvider.FetchedResult.success(job.getId(), outcome.result);
                    } catch (Exception e) {
                        // runOne is contracted not to throw; last-resort guard so one rogue
                        // job can't poison the join for the whole batch.
                        fr = LlmAsyncProvider.FetchedResult.failure(job.getId(), "unexpected: " + e.getMessage());
                    }
                    sink.put(job.getId(), fr);
                }, pool));
            }
        } // ExecutorService.close() awaits completion of all submitted tasks.
        for (CompletableFuture<Void> f : futures) f.join();
    }

    /**
     * The stable cached-prefix key this job would emit, or null when it emits no
     * cache breakpoint (so it is never primed/grouped). Reads the same flag and
     * renderer as {@link #buildParams}, so the priming decision matches the bytes
     * actually sent. Never throws — a parse failure means "don't prime".
     */
    String prefixCacheKeyFor(LlmJob job) {
        try {
            JsonNode payload = objectMapper.readTree(job.getRequestPayload());
            return ClaudeSystemRenderer.prefixCacheKey(payload, promptCachingEnabled);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Executes one Claude-on-Vertex call with bounded backoff retries. Never throws.
     *
     * <p>Package-private (not private) so the Mission 6b priming test can override it with
     * a recording substrate to make the one-lead-then-fan-out ordering observable without a
     * live Vertex call. Production behaviour is unchanged.
     */
    CallOutcome runOne(LlmJob job) {
        if (client == null) {
            // Try a lazy rebuild (ADC may have become available after a failed boot).
            try { this.client = buildClient(); } catch (Exception e) {
                return CallOutcome.failure("Vertex Anthropic client unavailable: " + e.getMessage());
            }
        }
        RuntimeException last = null;
        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            try {
                MessageCreateParams params = buildParams(job);
                Message message = client.messages().create(params);
                return CallOutcome.success(toResult(message, job));
            } catch (Exception e) {
                last = (e instanceof RuntimeException re) ? re : new RuntimeException(e);
                if (!isRetryable(e) || attempt == MAX_RETRIES) {
                    break;
                }
                long backoff = BASE_BACKOFF_MS * (1L << (attempt - 1)); // 1s, 2s, 4s
                log.warn("Vertex Anthropic call {} retryable failure (attempt {}/{}): {} — backing off {}ms",
                        job.getId(), attempt, MAX_RETRIES, e.getMessage(), backoff);
                try { Thread.sleep(backoff); } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return CallOutcome.failure("interrupted during retry backoff");
                }
            }
        }
        String msg = last == null ? "unknown error" : last.getMessage();
        log.error("Vertex Anthropic call {} failed after {} attempt(s): {}", job.getId(), MAX_RETRIES, msg);
        return CallOutcome.failure(msg == null ? "Vertex Anthropic call failed" : msg);
    }

    /**
     * Translates the provider-agnostic {@code request_payload} (system / userMessage / messages /
     * tools / maxTokens / thinkingBudget) stored by {@link LlmJobService} into Anthropic Messages
     * params. Scalars use the typed builder; the {@code messages}/{@code tools} arrays and the
     * thinking/effort blocks are passed verbatim via {@code putAdditionalBodyProperty}, mirroring
     * how {@link AnthropicBatchProviderImpl#buildParams} re-packs the same payload (so the request
     * shape is identical to the batch lane, just on a different transport).
     */
    MessageCreateParams buildParams(LlmJob job) throws Exception {
        JsonNode payload = objectMapper.readTree(job.getRequestPayload());
        String modelName = (job.getModelName() != null && !job.getModelName().isBlank())
                ? job.getModelName() : defaultModelName;
        int maxTokens = payload.path("maxTokens").asInt(16_000);

        MessageCreateParams.Builder b = MessageCreateParams.builder()
                .model(modelName)
                .maxTokens(maxTokens);

        // system: legacy flat string OR (caching ON + structured prompt) the
        // Messages-API array form carrying the cache_control breakpoint. The flat
        // case uses the typed String setter (today's exact bytes); the array case
        // goes through the JsonField escape hatch (JsonValue is the catch-all
        // JsonField) so the wire shape matches the raw-JSON batch lane exactly.
        JsonNode systemNode = ClaudeSystemRenderer.renderSystem(payload, promptCachingEnabled, objectMapper);
        if (systemNode != null) {
            if (systemNode.isTextual()) {
                b.system(systemNode.asText());
            } else {
                b.system(asSystemField(JsonValue.fromJsonNode(systemNode)));
            }
        }

        // messages[] is the source of truth (LlmJobService always serializes resolveMessages());
        // fall back to a single user turn only if it's somehow absent. The SDK validates that the
        // typed `messages` field is set, so we feed the raw array through the typed JsonField setter
        // (JsonValue extends JsonField) rather than as a free additional body property.
        JsonNode messages = payload.path("messages");
        if (messages.isArray() && !messages.isEmpty()) {
            b.messages(asMessagesField(JsonValue.fromJsonNode(messages)));
        } else {
            b.addUserMessage(payload.path("userMessage").asText(""));
        }

        JsonNode tools = payload.path("tools");
        if (tools.isArray() && !tools.isEmpty()) {
            b.tools(asToolsField(JsonValue.fromJsonNode(tools)));
        }

        // Thinking → adaptive + output_config.effort, exactly as AnthropicBatchProviderImpl maps it.
        int thinkingBudget = payload.path("thinkingBudget").asInt(0);
        if (thinkingBudget > 0) {
            b.putAdditionalBodyProperty("thinking",
                    JsonValue.from(Map.of("type", "adaptive")));
            b.putAdditionalBodyProperty("output_config",
                    JsonValue.from(Map.of("effort", effortFor(thinkingBudget))));
        }

        return b.build();
    }

    /** Maps the typed {@link Message} response back onto the provider-agnostic result shape. */
    private LlmJobResult toResult(Message message, LlmJob job) {
        StringBuilder text = new StringBuilder();
        for (ContentBlock block : message.content()) {
            if (block.isText()) {
                text.append(block.asText().text());
            }
        }
        Usage usage = message.usage();
        long inputTokens = usage.inputTokens();
        long outputTokens = usage.outputTokens();
        // Vertex/Anthropic does not split thinking tokens out of output in the usage block; leave 0.
        long thinkingTokens = 0L;
        // Prompt-cache usage (Increment 6 / Mission 6a). Anthropic reports these
        // SEPARATELY from input_tokens (which EXCLUDES them), so they are additive.
        long cacheReadTokens = usage.cacheReadInputTokens().orElse(0L);
        long cacheWriteTokens = usage.cacheCreationInputTokens().orElse(0L);
        String model = message.model().asString();

        // Build a compact, Jackson-clean raw node (don't reflect the SDK's typed builder).
        // Carries the fields downstream callers may dig into: assembled text, stop_reason, usage.
        com.fasterxml.jackson.databind.node.ObjectNode raw = objectMapper.createObjectNode();
        raw.put("id", message.id());
        raw.put("model", model);
        message.stopReason().ifPresent(sr -> raw.put("stop_reason", sr.asString()));
        raw.put("text", text.toString());
        com.fasterxml.jackson.databind.node.ObjectNode usageNode = raw.putObject("usage");
        usageNode.put("input_tokens", inputTokens);
        usageNode.put("output_tokens", outputTokens);
        usage.cacheReadInputTokens().ifPresent(v -> usageNode.put("cache_read_input_tokens", v));
        usage.cacheCreationInputTokens().ifPresent(v -> usageNode.put("cache_creation_input_tokens", v));

        return new LlmJobResult(text.toString(), raw, inputTokens, outputTokens, thinkingTokens,
                cacheReadTokens, cacheWriteTokens,
                NAME, model.isBlank() ? job.getModelName() : model);
    }

    private static boolean isRetryable(Exception e) {
        String m = e.getMessage();
        if (m == null) return false;
        String lower = m.toLowerCase();
        // 429 rate limit, 503 overloaded, or 5xx — retry with backoff. 4xx (other) is terminal.
        return lower.contains("429") || lower.contains("rate limit")
                || lower.contains("overloaded") || lower.contains("503")
                || lower.contains("500") || lower.contains("502") || lower.contains("504");
    }

    static String effortFor(int budgetTokens) {
        if (budgetTokens < 4_000) return "low";
        if (budgetTokens < 12_000) return "medium";
        return "high";
    }

    // The SDK exposes raw arrays as a generic JsonField; the typed setters want a parameterised
    // JsonField. JsonValue is the catch-all JsonField, so passing the raw array through is safe
    // (the server validates the wire shape) — these casts isolate the one unavoidable unchecked op.
    @SuppressWarnings("unchecked")
    private static JsonField<List<MessageParam>> asMessagesField(JsonValue v) {
        return (JsonField<List<MessageParam>>) (JsonField<?>) v;
    }

    @SuppressWarnings("unchecked")
    private static JsonField<List<ToolUnion>> asToolsField(JsonValue v) {
        return (JsonField<List<ToolUnion>>) (JsonField<?>) v;
    }

    // The cache-aware system array is passed through the typed `system` JsonField
    // setter (JsonValue is the catch-all JsonField) — same escape hatch as
    // messages/tools, so the server validates the wire shape and the bytes match
    // the raw-JSON batch lane.
    @SuppressWarnings("unchecked")
    private static JsonField<com.anthropic.models.messages.MessageCreateParams.System> asSystemField(JsonValue v) {
        return (JsonField<com.anthropic.models.messages.MessageCreateParams.System>) (JsonField<?>) v;
    }

    /** Outcome of one synchronous Vertex call. */
    static final class CallOutcome {
        final LlmJobResult result;  // non-null on success
        final String error;         // non-null on failure

        private CallOutcome(LlmJobResult result, String error) {
            this.result = result;
            this.error = error;
        }
        static CallOutcome success(LlmJobResult r) { return new CallOutcome(r, null); }
        static CallOutcome failure(String e) { return new CallOutcome(null, e); }
    }
}
