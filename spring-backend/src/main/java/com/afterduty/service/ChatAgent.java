package com.afterduty.service;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.Usage;
import com.anthropic.vertex.backends.VertexBackend;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.auth.oauth2.GoogleCredentials;
import com.afterduty.model.AiCallLog;
import com.afterduty.model.Atom;
import com.afterduty.model.Claim;
import com.afterduty.model.ConditionSuppression;
import com.afterduty.model.EvidenceItem;
import com.afterduty.model.IdentifiedCondition;
import com.afterduty.model.IntakeMessage;
import com.afterduty.model.ServiceProfile;
import com.afterduty.model.VasrdRecord;
import com.afterduty.repository.AtomRepository;
import com.afterduty.repository.ClaimRepository;
import com.afterduty.repository.ConditionRepository;
import com.afterduty.repository.ConditionSuppressionRepository;
import com.afterduty.repository.EvidenceRepository;
import com.afterduty.repository.MessageRepository;
import com.afterduty.repository.ServiceProfileRepository;
import com.afterduty.repository.VasrdRecordRepository;
import com.afterduty.service.rag.HybridRetrievalService;
import com.afterduty.service.rag.RetrievedChunk;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Claim-aware chat agent. Reads the full claim state (atoms, conditions, gaps)
 * into Claude (Sonnet 4.6 by default), gives it tools to mutate that state, loops
 * until Claude emits a final text reply. Every tool call goes through repositories
 * so every side effect is persisted and visible on the next turn.
 *
 * <p>Increment 2/3: the transport moved from a direct {@code api.anthropic.com}
 * {@code x-api-key} client on hard-coded {@code claude-opus-4-7} to the
 * {@code anthropic-java-vertex} SDK against the configured Vertex region (default the
 * <b>{@code us} multi-region</b> endpoint — US data-residency decision, 2026-08-01; the only
 * US-jurisdiction home of the post-May-2026 models) with ADC auth (no {@code ANTHROPIC_API_KEY};
 * billing/IAM stay in GCP), with the model read from the {@code va-claim.llm.purposes.chat}
 * routing config (default {@code claude-sonnet-5}).
 * The tool-use loop, the AiCallLog booking, and the bounded 3-attempt retry cap are preserved.
 */
@Service
public class ChatAgent {

    private static final Logger log = LoggerFactory.getLogger(ChatAgent.class);

    private static final int MAX_ITERATIONS = 8;
    private static final int MAX_OUTPUT_TOKENS = 8192;
    private static final int THINKING_BUDGET = 6000;
    private static final int HISTORY_TURNS = 16;  // load the last N messages for context
    private static final int MAX_429_ATTEMPTS = 3;  // bounded rate-limit retries, then fail
    // Non-final so the streaming retry-cap test can zero the backoff; production keeps 30s.
    long retryBackoffMs = 30_000L;

    private final AtomRepository atomRepository;
    private final ConditionRepository conditionRepository;
    private final MessageRepository messageRepository;
    private final AiCostService aiCostService;
    // Increment 7 (§E.1) grounding-tool collaborators. HybridRetrievalService is the
    // frozen retrieval contract (§D); VasrdRecordRepository is the structured Part-4
    // side; VasrdDataService is the JSON fallback when the table is cold/disabled.
    private final HybridRetrievalService hybridRetrievalService;
    private final VasrdRecordRepository vasrdRecordRepository;
    private final VasrdDataService vasrdDataService;
    // Phase E (P1-12/P1-14) collaborators: EvidenceRepository + ClaimRepository feed the
    // read-only get_pipeline_status tool (the /jobs data, rendered for the model);
    // ClaimRepository also flips synthesisNeeded when a rating-write attempt marks a
    // condition dirty; ConditionSuppressionRepository is the durable soft-delete record.
    private final EvidenceRepository evidenceRepository;
    private final ClaimRepository claimRepository;
    private final ConditionSuppressionRepository conditionSuppressionRepository;
    // Increment C — record_service_fact writes the veteran's ServiceProfile so the
    // deterministic presumptive engine (EnhancedSynthesisOrchestrator.reconcilePresumptive-
    // FromEvidence, Increment B) re-derives on the next run. UserRepository is only used to
    // attach the owning User when UPSERTING a brand-new profile row (ServiceProfile.userId
    // is insertable=false — the column is populated from the user relationship, exactly as
    // AuthController's manual-form writer does with .user(user)).
    private final ServiceProfileRepository serviceProfileRepository;
    private final com.afterduty.repository.UserRepository userRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${va-claim.vertex.project-id:}")
    private String projectId;

    @Value("${va-claim.vertex.claude-region:us}")
    private String region;

    // Chat model comes from the routing config (Increment 3) so it flips with the rest of the table.
    @Value("${va-claim.llm.purposes.chat.model:claude-sonnet-5}")
    private String claudeModel;

    /**
     * Mission 6b — when ON (default), the chat system is split into two blocks:
     * [frozen instructions] + [claim-state block under one cache_control breakpoint],
     * so turn 2+ in a session read the (unchanged) state block at 0.1× instead of
     * re-paying for it every message. When OFF, the system is a single flat string
     * byte-identical to today's request. Both transports (vertex + direct) use the
     * same SDK system-array form.
     */
    @Value("${va-claim.llm.prompt-caching:true}")
    boolean promptCachingEnabled = true;

    /**
     * Transport rollback flag, mirroring the per-purpose provider rollback on the
     * pipeline: "vertex" (default — ADC, regional residency) or "anthropic"
     * (direct api.anthropic.com with ANTHROPIC_API_KEY). Exists because Vertex
     * Claude quota grants are outside our control; the direct lane is the same
     * one the legacy pipeline always used.
     */
    @Value("${va-claim.llm.chat-transport:vertex}")
    private String chatTransport;

    @Value("${va-claim.claude.api-key:}")
    private String anthropicApiKey;

    /**
     * Increment 7 (§A.5/§E.1) master RAG switch. When OFF the three grounding tools
     * stay REGISTERED (schema stability keeps the cached prefix byte-identical across
     * the flag) but {@code search_my_file} and the query mode of {@code vasrd_lookup}
     * answer "not available right now"; the structured {@code vasrd_lookup} by dc_code
     * and {@code get_analysis} still work off the DB/state.
     */
    @Value("${va-claim.rag.enabled:true}")
    boolean ragEnabled = true;

    /** Anthropic client (Vertex- or direct-backed); built lazily so a missing credential chain doesn't break startup. */
    private AnthropicClient client;

    public ChatAgent(AtomRepository atomRepository,
                     ConditionRepository conditionRepository,
                     MessageRepository messageRepository,
                     AiCostService aiCostService,
                     HybridRetrievalService hybridRetrievalService,
                     VasrdRecordRepository vasrdRecordRepository,
                     VasrdDataService vasrdDataService,
                     EvidenceRepository evidenceRepository,
                     ClaimRepository claimRepository,
                     ConditionSuppressionRepository conditionSuppressionRepository,
                     ServiceProfileRepository serviceProfileRepository,
                     com.afterduty.repository.UserRepository userRepository) {
        this.atomRepository = atomRepository;
        this.conditionRepository = conditionRepository;
        this.messageRepository = messageRepository;
        this.aiCostService = aiCostService;
        this.hybridRetrievalService = hybridRetrievalService;
        this.vasrdRecordRepository = vasrdRecordRepository;
        this.vasrdDataService = vasrdDataService;
        this.evidenceRepository = evidenceRepository;
        this.claimRepository = claimRepository;
        this.conditionSuppressionRepository = conditionSuppressionRepository;
        this.serviceProfileRepository = serviceProfileRepository;
        this.userRepository = userRepository;
    }

    @PostConstruct
    void init() {
        try {
            this.client = buildClient();
            log.info("ChatAgent ready (transport={}, project={}, region={}, model={})",
                    chatTransport, projectId, region, claudeModel);
        } catch (Exception e) {
            // Defer the failure to the first call (surfaced as a chat error), don't break boot.
            log.error("ChatAgent client init failed (transport={}): {}", chatTransport, e.getMessage(), e);
        }
    }

    private AnthropicClient buildClient() throws Exception {
        if ("anthropic".equals(chatTransport)) {
            if (anthropicApiKey == null || anthropicApiKey.isBlank()) {
                throw new IllegalStateException("chat-transport=anthropic but ANTHROPIC_API_KEY is not configured");
            }
            return AnthropicOkHttpClient.builder().apiKey(anthropicApiKey).build();
        }
        GoogleCredentials credentials = GoogleCredentials.getApplicationDefault()
                .createScoped("https://www.googleapis.com/auth/cloud-platform");
        VertexBackend backend = VertexBackend.builder()
                .googleCredentials(credentials)
                .project(projectId)
                .region(region)
                .build();
        return AnthropicOkHttpClient.builder().backend(backend).build();
    }

    /**
     * Process one veteran message. Returns the assistant's reply text — the
     * caller persists it. Any DB mutations happen inside tool execution.
     *
     * @param veteranMessage the user's message content
     * @param claimId the claim being discussed
     * @param userId the viewer's user id (used for atom provenance)
     * @param userMessageId the id of the already-persisted user IntakeMessage
     *                      (used to set atom.messageId and to derive threadId for history)
     */
    public String handle(String veteranMessage, Long claimId, Long userId, Long userMessageId) {
        return handleStreaming(veteranMessage, claimId, userId, userMessageId, ChatStreamListener.NOOP);
    }

    /**
     * The single agent code path (Increment 7 §F.2). Identical to the legacy
     * {@link #handle} loop except the model round-trip streams: text deltas are pushed to
     * {@code listener.onDelta} as they arrive, a tool-use block start pushes
     * {@code listener.onStatus("tool", name)}, and each round-trip start pushes
     * {@code listener.onStatus("thinking", null)}. The accumulated Message is normalized
     * to the same raw JSON the loop already consumes, so tool execution, the AiCallLog
     * booking, and the iteration cap are byte-for-byte unchanged. {@link ChatStreamListener#NOOP}
     * makes this the non-streaming path with zero behavior drift.
     */
    public String handleStreaming(String veteranMessage, Long claimId, Long userId,
                                  Long userMessageId, ChatStreamListener listener) {
        // Derive threadId from the persisted user message so buildHistory is thread-scoped
        Long threadId = messageRepository.findById(userMessageId)
                .map(m -> m.getThreadId())
                .orElse(null);

        // Mission 6b — split the system into [frozen instructions] + [claim-state
        // block]. The state block is read ONCE here per call so it is identical across
        // every tool-use iteration in this turn (the bytes can't drift mid-turn even if
        // a tool mutates state). When caching is ON these become a system array with the
        // cache_control breakpoint on the state block; when OFF they are joined into the
        // legacy flat string (byte-identical).
        // P1-19 (Phase G1) — viewer tool gating. When the effective principal is NOT
        // the claim owner (a VSO/rep chatting via X-View-As), the mutating tools are
        // NOT in the tool list sent to the model at all — grounding tools only. An
        // execution-time guard in executeTool re-derives ownership independently
        // (defense in depth) so a schema regression alone can never re-enable writes.
        boolean viewerPrincipal = !isOwnerPrincipal(claimId, userId);
        String frozenInstructions = viewerPrincipal
                ? FROZEN_INSTRUCTIONS + "\n\n" + VIEWER_MODE_ADDENDUM
                : FROZEN_INSTRUCTIONS;
        String claimStateBlock = buildClaimStateBlock(claimId);
        List<Map<String, Object>> messages = buildHistory(threadId, veteranMessage);
        List<Map<String, Object>> tools = buildToolSchema(viewerPrincipal);
        // A session is "turn 2+" when there were prior messages before this veteran turn
        // (buildHistory always appends the current turn, so size>1 means prior history).
        boolean priorSessionTurns = messages.size() > 1;

        // P1-13 — accumulate assistant text ACROSS tool-use iterations. Deltas from every
        // iteration are forwarded to the listener as they stream, so the persisted reply
        // must be the same concatenation; rebuilding the buffer per iteration silently
        // dropped every pre-tool-call iteration's text from the transcript.
        StringBuilder aggregateText = new StringBuilder();

        for (int i = 0; i < MAX_ITERATIONS; i++) {
            listener.onStatus("thinking", null);
            JsonNode root = callMessages(frozenInstructions, claimStateBlock, messages, tools,
                    MAX_OUTPUT_TOKENS, THINKING_BUDGET, claimId, userId, listener);

            // Cache tripwire (§F.2): turn 2+ of a session re-sends the identical
            // instructions + claim-state prefix and SHOULD read it back at 0.1×. If the
            // first round-trip of a follow-up turn books zero cache reads while caching is
            // on, a silent invalidator slipped in — WARN once for the operator.
            if (i == 0 && promptCachingEnabled && priorSessionTurns
                    && root.path("usage").path("cache_read_input_tokens").asLong(0) == 0) {
                log.warn("Chat cache miss on a follow-up turn (claim {}): caching is ON and this "
                        + "session has prior turns, but cache_read_input_tokens=0 — the cached "
                        + "system prefix may have a silent invalidator.", claimId);
            }

            String stopReason = root.path("stop_reason").asText("");
            JsonNode content = root.path("content");

            // Capture assistant-role content as a single list the API expects
            List<Map<String, Object>> assistantContent = new ArrayList<>();
            List<JsonNode> toolUses = new ArrayList<>();
            if (content.isArray()) {
                for (JsonNode block : content) {
                    Map<String, Object> blockMap = objectMapper.convertValue(block, new TypeReference<Map<String, Object>>() {});
                    assistantContent.add(blockMap);
                    if ("text".equals(block.path("type").asText())) {
                        // P1-13: straight concatenation, no separator — byte-for-byte what
                        // the delta stream already put on the wire for this turn.
                        aggregateText.append(block.path("text").asText());
                    } else if ("tool_use".equals(block.path("type").asText())) {
                        toolUses.add(block);
                    }
                }
            }

            // Record the assistant turn into the running history
            messages.add(Map.of("role", "assistant", "content", assistantContent));

            if (toolUses.isEmpty() || "end_turn".equals(stopReason)) {
                return aggregateText.length() > 0
                        ? aggregateText.toString().trim()
                        : "I've updated your claim based on what you shared.";
            }

            // Execute each tool call and append tool_result blocks
            List<Map<String, Object>> toolResults = new ArrayList<>();
            for (JsonNode tu : toolUses) {
                String id = tu.path("id").asText();
                String name = tu.path("name").asText();
                Map<String, Object> input = objectMapper.convertValue(
                        tu.path("input"), new TypeReference<Map<String, Object>>() {});
                String result = executeTool(name, input, claimId, userId, userMessageId);
                toolResults.add(Map.of(
                        "type", "tool_result",
                        "tool_use_id", id,
                        "content", result));
            }
            messages.add(Map.of("role", "user", "content", toolResults));
        }

        log.warn("Chat agent hit MAX_ITERATIONS ({}) without end_turn for claim {}", MAX_ITERATIONS, claimId);
        // P1-13: never drop text that already streamed to the client — persist what
        // accumulated, then the continuation note.
        String ranOut = "I made several updates but ran out of room to respond — ask me to continue and I'll finish.";
        return aggregateText.length() > 0
                ? aggregateText.toString().trim() + "\n\n" + ranOut
                : ranOut;
    }

    /* ---------- Vertex Anthropic SDK call (tool-use loop) ---------- */

    /**
     * One Messages round-trip via the Vertex-backed SDK, now <b>streaming</b> (Increment 7
     * §F.2). Builds typed params from the same system/messages/tools/thinking inputs the
     * loop already produces, streams the response (forwarding text deltas + tool-start
     * status to {@code listener}), accumulates the full {@link Message}, then normalizes it
     * into the same raw-API-shaped {@link JsonNode} so the surrounding tool-use loop and
     * {@link #recordChatUsage} are byte-for-byte unchanged.
     *
     * <p><b>Retry-cap nuance (§F.2):</b> the bounded 3-attempt 429 retry applies only while
     * ZERO deltas have been forwarded for the current round-trip — a retried half-streamed
     * answer would duplicate text on the wire. {@code streamOnce} resets a per-attempt delta
     * counter and signals (via {@link StreamFailedAfterDeltaException}) when a failure landed
     * after the first delta, so this loop never retries a partially-streamed reply.
     */
    private JsonNode callMessages(String frozenInstructions, String claimStateBlock,
                                   List<Map<String, Object>> messages,
                                   List<Map<String, Object>> tools, int maxTokens, int thinkingBudget,
                                   Long claimId, Long userId, ChatStreamListener listener) {
        if (client == null) {
            try { this.client = buildClient(); } catch (Exception e) {
                throw new IllegalStateException(
                        "Vertex Anthropic client unavailable — Claude chat will fail: " + e.getMessage(), e);
            }
        }

        MessageCreateParams params = buildParams(frozenInstructions, claimStateBlock,
                messages, tools, maxTokens, thinkingBudget);

        RuntimeException last = null;
        for (int attempt = 1; attempt <= MAX_429_ATTEMPTS; attempt++) {
            long startedAt = System.currentTimeMillis();
            try {
                JsonNode root = streamToRawJson(params, listener);
                recordChatUsage(root, claimId, userId, System.currentTimeMillis() - startedAt);
                return root;
            } catch (StreamFailedAfterDeltaException e) {
                // A failure mid-stream after text was already on the wire is NOT retryable —
                // retrying would duplicate the half-streamed answer. Propagate as agent error.
                throw new RuntimeException(
                        "Claude chat stream failed after partial delivery: " + e.getMessage(), e.getCause());
            } catch (Exception e) {
                last = (e instanceof RuntimeException re) ? re : new RuntimeException(e);
                boolean retryable = isRateLimited(e);
                if (!retryable || attempt == MAX_429_ATTEMPTS) {
                    break;
                }
                log.warn("Vertex Anthropic rate-limited/overloaded for chat — backing off {}s (attempt {}/{})",
                        retryBackoffMs / 1000, attempt, MAX_429_ATTEMPTS);
                // P1-10 — surface the wait: without this status the UI shows "Thinking…"
                // for the entire (up to ~60s of) backoff before an eventual generic error.
                listener.onStatus("rate_limited", null);
                try { if (retryBackoffMs > 0) Thread.sleep(retryBackoffMs); }
                catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; }
            }
        }
        if (last != null && isRateLimited(last)) {
            // P1-10 — typed, so the wire (SSE error event / fallback copy) can classify
            // provider brownout as retryable "rate_limited" instead of a generic agent error.
            throw new ChatRateLimitedException(
                    "Vertex Anthropic rate-limited chat " + MAX_429_ATTEMPTS + " times in a row — giving up", last);
        }
        throw new RuntimeException("Claude chat call failed: "
                + (last == null ? "unknown error" : last.getMessage()), last);
    }

    /**
     * P1-10 — provider brownout/backoff exhaustion: the bounded {@link #MAX_429_ATTEMPTS}
     * retry loop ran out while the provider kept returning 429/503/overloaded. This is a
     * RETRYABLE condition for the client (capacity, not a bug), so the streaming controller
     * maps it to the SSE {@code error {code:"rate_limited"}} event (whose UI copy existed
     * but was unreachable) and the non-streaming path persists capacity-specific fallback
     * copy instead of the generic "AI temporarily unavailable".
     */
    public static class ChatRateLimitedException extends RuntimeException {
        public ChatRateLimitedException(String message, Throwable cause) { super(message, cause); }
    }

    /** Signals a stream failure that occurred AFTER ≥1 delta was forwarded — non-retryable. */
    static final class StreamFailedAfterDeltaException extends RuntimeException {
        StreamFailedAfterDeltaException(Throwable cause) { super(cause == null ? null : cause.getMessage(), cause); }
    }

    /**
     * One streaming round-trip → the raw {@code /v1/messages} JSON the tool-use loop consumes.
     * Thin wrapper over {@link #streamOnce} + {@link #toRawJson}; the overridable seam the
     * streaming unit test scripts (returning hand-built JSON) so it never has to construct full
     * SDK {@link Message}/{@link Usage} objects. The retry/no-retry classification lives in the
     * caller — this method just streams once or throws.
     */
    JsonNode streamToRawJson(MessageCreateParams params, ChatStreamListener listener) {
        return toRawJson(streamOnce(params, listener));
    }

    /**
     * Consumes ONE streaming round-trip and returns the accumulated {@link Message}. Forwards
     * {@code content_block_delta} text to {@code listener.onDelta} and a {@code tool_use}
     * {@code content_block_start} to {@code listener.onStatus("tool", name)}; accumulates with
     * the SDK's {@link com.anthropic.helpers.MessageAccumulator}.
     *
     * <p>If a delta has already been forwarded when the stream throws, the failure is wrapped in
     * {@link StreamFailedAfterDeltaException} so the retry loop won't re-stream a partial answer.
     */
    Message streamOnce(MessageCreateParams params, ChatStreamListener listener) {
        com.anthropic.helpers.MessageAccumulator accumulator =
                com.anthropic.helpers.MessageAccumulator.create();
        boolean[] deltaSeen = {false};
        try (com.anthropic.core.http.StreamResponse<com.anthropic.models.messages.RawMessageStreamEvent> stream =
                     client.messages().createStreaming(params)) {
            stream.stream().forEach(event -> {
                accumulator.accumulate(event);
                dispatchStreamEvent(event, listener, deltaSeen);
            });
        } catch (RuntimeException e) {
            if (deltaSeen[0]) throw new StreamFailedAfterDeltaException(e);
            throw e;
        }
        return accumulator.message();
    }

    /**
     * Routes one raw stream event to the listener: text deltas → {@code onDelta}; a
     * {@code tool_use} block start → {@code onStatus("tool", name)}. Other event types
     * (message_start/stop, thinking deltas, content_block_stop) are not surfaced to the UI.
     */
    void dispatchStreamEvent(com.anthropic.models.messages.RawMessageStreamEvent event,
                             ChatStreamListener listener, boolean[] deltaSeen) {
        if (event.isContentBlockDelta()) {
            var delta = event.asContentBlockDelta().delta();
            if (delta.isText()) {
                String text = delta.asText().text();
                if (text != null && !text.isEmpty()) {
                    deltaSeen[0] = true;
                    listener.onDelta(text);
                }
            }
        } else if (event.isContentBlockStart()) {
            var block = event.asContentBlockStart().contentBlock();
            if (block.isToolUse()) {
                listener.onStatus("tool", block.asToolUse().name());
            }
        }
    }

    /**
     * Builds typed params; complex tool_use/tool_result blocks pass through verbatim via JsonField.
     *
     * <p>Mission 6b — the {@code system} field carries the two blocks. When prompt-caching
     * is ON it is the Messages-API <em>array</em> form
     * {@code [{text:frozen}, {text:claimState, cache_control:{type:ephemeral}}]}, so the
     * conversation messages (the volatile part) ride after the breakpoint and turn 2+ in a
     * session read the unchanged claim-state block at 0.1×. When OFF it is the legacy flat
     * string ({@code frozen + "\n\n" + claimState}), byte-identical to today's request. Both
     * transports (vertex + direct) hit this same path, so the wire shape matches across lanes.
     */
    MessageCreateParams buildParams(String frozenInstructions, String claimStateBlock,
                                            List<Map<String, Object>> messages,
                                            List<Map<String, Object>> tools, int maxTokens, int thinkingBudget) {
        MessageCreateParams.Builder b = MessageCreateParams.builder()
                .model(claudeModel)
                .maxTokens(maxTokens);

        JsonNode systemNode = buildSystemNode(frozenInstructions, claimStateBlock);
        if (systemNode != null) {
            if (systemNode.isTextual()) {
                b.system(systemNode.asText());
            } else {
                b.system(asSystemField(JsonValue.fromJsonNode(systemNode)));
            }
        }
        // messages and tools carry tool_use / tool_result blocks. The SDK validates that the typed
        // `messages` field is set, so feed the raw lists through the typed JsonField setters
        // (JsonValue extends JsonField) — the wire shape is identical to the previous direct-API call.
        b.messages(asMessagesField(JsonValue.from(messages)));
        if (tools != null && !tools.isEmpty()) {
            b.tools(asToolsField(JsonValue.from(tools)));
        }
        if (thinkingBudget > 0) {
            b.putAdditionalBodyProperty("thinking", JsonValue.from(Map.of("type", "adaptive")));
            String effort = thinkingBudget < 4000 ? "low" : thinkingBudget < 12000 ? "medium" : "high";
            b.putAdditionalBodyProperty("output_config", JsonValue.from(Map.of("effort", effort)));
        }
        return b.build();
    }

    /**
     * Renders the chat {@code system} value as a Jackson node, mirroring
     * {@link com.afterduty.service.llm.ClaudeSystemRenderer} so the chat lane's
     * cache-aware shape matches the pipeline lanes' bytes:
     * <ul>
     *   <li>caching OFF → a plain text node, {@code frozen + "\n\n" + claimState}
     *       (today's exact flat bytes); blank → null (system omitted);</li>
     *   <li>caching ON → an array: {@code {text:frozen}} then
     *       {@code {text:claimState, cache_control:{type:ephemeral}}} — exactly one
     *       breakpoint, on the claim-state block.</li>
     * </ul>
     * Visible (package-private) for the byte-stability and flag-off-parity tests.
     */
    JsonNode buildSystemNode(String frozenInstructions, String claimStateBlock) {
        String frozen = frozenInstructions == null ? "" : frozenInstructions;
        String state = claimStateBlock == null ? "" : claimStateBlock;

        if (!promptCachingEnabled) {
            String flat = buildFlatSystem(frozen, state);
            return flat.isBlank() ? null : objectMapper.getNodeFactory().textNode(flat);
        }

        ArrayNode arr = objectMapper.createArrayNode();
        if (!frozen.isBlank()) {
            ObjectNode block = arr.addObject();
            block.put("type", "text");
            block.put("text", frozen);
        }
        if (!state.isBlank()) {
            ObjectNode block = arr.addObject();
            block.put("type", "text");
            block.put("text", state);
            block.putObject("cache_control").put("type", "ephemeral");
        }
        return arr.isEmpty() ? null : arr;
    }

    /** The flat (flag-off) system string: frozen + blank line + state, byte-identical to legacy. */
    private static String buildFlatSystem(String frozen, String state) {
        if (frozen.isBlank()) return state;
        if (state.isBlank()) return frozen;
        return frozen + "\n\n" + state;
    }

    @SuppressWarnings("unchecked")
    private static com.anthropic.core.JsonField<MessageCreateParams.System> asSystemField(JsonValue v) {
        return (com.anthropic.core.JsonField<MessageCreateParams.System>) (com.anthropic.core.JsonField<?>) v;
    }

    @SuppressWarnings("unchecked")
    private static com.anthropic.core.JsonField<List<com.anthropic.models.messages.MessageParam>>
            asMessagesField(JsonValue v) {
        return (com.anthropic.core.JsonField<List<com.anthropic.models.messages.MessageParam>>)
                (com.anthropic.core.JsonField<?>) v;
    }

    @SuppressWarnings("unchecked")
    private static com.anthropic.core.JsonField<List<com.anthropic.models.messages.ToolUnion>>
            asToolsField(JsonValue v) {
        return (com.anthropic.core.JsonField<List<com.anthropic.models.messages.ToolUnion>>)
                (com.anthropic.core.JsonField<?>) v;
    }

    /**
     * Normalizes the typed {@link Message} into the raw {@code /v1/messages} JSON shape the tool-use
     * loop consumes: {@code {model, stop_reason, usage, content:[{type:text,text} | {type:tool_use,id,name,input}]}}.
     */
    private JsonNode toRawJson(Message message) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("model", message.model().asString());
        message.stopReason().ifPresent(sr -> root.put("stop_reason", sr.asString()));

        Usage usage = message.usage();
        ObjectNode usageNode = root.putObject("usage");
        usageNode.put("input_tokens", usage.inputTokens());
        usageNode.put("output_tokens", usage.outputTokens());
        // Prompt-cache usage on the chat path (Increment 6 / Mission 6a). Reported
        // separately from input_tokens; surfaced so recordChatUsage can book them.
        usage.cacheReadInputTokens().ifPresent(v -> usageNode.put("cache_read_input_tokens", v));
        usage.cacheCreationInputTokens().ifPresent(v -> usageNode.put("cache_creation_input_tokens", v));

        ArrayNode content = root.putArray("content");
        for (ContentBlock block : message.content()) {
            if (block.isText()) {
                ObjectNode t = content.addObject();
                t.put("type", "text");
                t.put("text", block.asText().text());
            } else if (block.isToolUse()) {
                var tu = block.asToolUse();
                ObjectNode t = content.addObject();
                t.put("type", "tool_use");
                t.put("id", tu.id());
                t.put("name", tu.name());
                t.set("input", tu._input().convert(JsonNode.class));
            }
            // other block types (thinking, etc.) are not consumed by the loop — skip.
        }
        return root;
    }

    private static boolean isRateLimited(Exception e) {
        String m = e.getMessage();
        if (m == null) return false;
        String lower = m.toLowerCase();
        return lower.contains("429") || lower.contains("rate limit") || lower.contains("overloaded")
                || lower.contains("503");
    }

    /**
     * Books one chat round-trip into the AI cost ledger, mirroring how
     * LlmJobPoller.recordCost builds rows for async pipeline jobs. Chat was
     * previously the one LLM path that wrote no AiCallLog at all, so chat
     * spend was invisible to the per-user usage cap and dashboards.
     * Metering failures must never break the chat itself.
     */
    private void recordChatUsage(JsonNode root, Long claimId, Long userId, long latencyMs) {
        try {
            JsonNode usage = root.path("usage");
            AiCallLog logRow = AiCallLog.builder()
                    .claimId(claimId)
                    .userId(userId)
                    .callType("chat")
                    // Provider reflects the actual transport: the +10% regional premium
                    // in AiCostService keys off vertex-anthropic; the direct lane bills
                    // plain list price.
                    .provider("anthropic".equals(chatTransport)
                            ? "anthropic-direct"
                            : com.afterduty.service.llm.VertexAnthropicProvider.NAME)
                    // Book against the CONFIGURED model string, not the response echo:
                    // Vertex may return a normalized/dated model id that misses the
                    // exact-match AiCostService PRICING key and falls to the unknown
                    // fallback rate. The configured string is what routing priced.
                    .modelName(claudeModel)
                    .inputTokens(usage.path("input_tokens").asLong(0))
                    .outputTokens(usage.path("output_tokens").asLong(0))
                    // Prompt-cache usage (0 when uncached). The chat path is the
                    // prime caching beneficiary (frozen system + case digest read
                    // at 0.1× on follow-up turns), so booking these is what makes
                    // the chat-session cost ledger honest.
                    .cacheReadTokens(usage.path("cache_read_input_tokens").asLong(0))
                    .cacheWriteTokens(usage.path("cache_creation_input_tokens").asLong(0))
                    .latencyMs(latencyMs)
                    .status("success")
                    .build();
            aiCostService.recordCall(logRow);
        } catch (Exception e) {
            log.warn("Failed to record chat cost for claim {}: {}", claimId, e.getMessage());
        }
    }

    /* ---------- system prompt ---------- */

    /**
     * The frozen, claim-independent instructions block. Byte-identical for every
     * veteran and every turn, so it is the stable head of the cached system prefix
     * (Mission 6b). Contains no claim state, no timestamps, no per-request content.
     * Package-visible so the safety-content test can pin the crisis-line block.
     */
    static final String FROZEN_INSTRUCTIONS = """
            You are a VA disability claims assistant talking with a veteran about their active
            claim. You have tools that let you mutate the claim state (atoms, conditions, and
            gap suggestions). You also answer questions grounded in that state.

            SAFETY & BOUNDARIES
            - Crisis first: if the veteran mentions suicide, self-harm, harming others, or
              being in crisis, lead your reply with the Veterans Crisis Line — call or text
              988 then press 1, or text 838255 — available 24/7, before anything else.
            - You are not a doctor or a lawyer. Explain the claims process and what the
              evidence shows; never give medical or legal advice.
            - Never promise or predict an outcome. Ratings and dollar figures here are
              estimates — VA makes the decision.
            - For filing decisions (what to file, when to file, appeals), recommend an
              accredited Veterans Service Officer (VSO); help the veteran prepare, don't
              decide for them.

            HOW TO THINK
            - Read the CURRENT CLAIM STATE below carefully. Never invent atoms, conditions, or
              gaps that are not in it.
            - If the veteran reports new facts (a date, a diagnosis, an exposure, a medication,
              a symptom), call add_atom to persist them — even if they seem minor. Atoms are
              the raw evidence the rest of the pipeline depends on.
            - If the veteran corrects something, use update_atom / update_condition to fix it
              and cite your change in the reply.
            - If the veteran says they already have a piece of evidence that a gap requested,
              call mark_gap_resolved. If they say a gap doesn't apply, call mark_gap_dismissed.
            - Use delete_atom / delete_condition only when the user clearly asks to remove
              something they say is wrong, or when a duplicate needs cleanup. Removing a
              condition is reversible: restore_condition undoes it — tell the veteran that.
            - When the veteran mentions their SERVICE HISTORY — a branch, service dates, an
              MOS/job, a DEPLOYMENT location (Iraq, Afghanistan, Vietnam, Kuwait, ...), or an
              EXPOSURE (burn pit, Agent Orange, herbicide, Gulf War) — call record_service_fact
              to capture it. That fact feeds presumptive eligibility: it can qualify a condition
              for a PACT Act (or Agent Orange / Gulf War) presumptive. But it re-derives a
              PROVISIONAL presumptive, pending VA confirmation of qualifying service — NEVER tell
              the veteran a presumptive is granted or guaranteed.
            - When the veteran DISPUTES a rating or finding, or asks you to re-check after new
              information was captured, call request_reanalysis. This genuinely queues a re-run
              that re-checks conditions, presumptive eligibility, and evidence gaps. Prefer it
              over editing a condition just to force a re-run.
            - Ratings are computed by the analysis pipeline, never set by you. If the
              veteran disputes a rating, explain the rationale in state and call
              request_reanalysis so it is genuinely re-checked. The rating NUMBER changes ONLY
              when new evidence (add_atom, record_service_fact, or an upload) is added — so NEVER
              tell the veteran the number "is being corrected", "will update", or "will
              change" on a bare dispute; tell them exactly what evidence would change it.
              (A re-analysis re-checks a condition's presumptive status and evidence gaps and
              CAN change readiness — but that does NOT move the number by itself, so do not
              imply it will.)
            - Answer questions plainly. If a question asks about a condition or gap, reference
              the exact item in state so the veteran can follow along.
            - Reply in plain conversational English. Explain VA-specific terms the first time.

            TOOL USE RULES
            - Call tools in the same turn as your reasoning — don't narrate what you will do.
            - Tool calls take effect immediately; the next turn will reflect the new state.
            - After all necessary tool calls, emit a short final reply summarizing what
              changed and answering any question the veteran asked.
            - If the veteran asked only a question and nothing to change, skip tools and just
              answer.

            GROUNDING TOOLS
            - search_my_file: search the veteran's own uploaded documents. Use it before
              answering anything about what their records show.
            - vasrd_lookup: exact VA rating-schedule lookup by diagnostic code, or search the
              regulations. Use it before quoting any rating percentage or criteria.
            - get_analysis: the freshest read of the live conditions and gaps (the CURRENT
              CLAIM STATE below is a snapshot from the start of this turn; after a mutation
              tool fires, call get_analysis to see the up-to-date list).
            - get_pipeline_status: read-only status of the analysis pipeline — each
              document's processing state, whether condition or gap analysis is running or
              queued, and when each last completed. Use it when the veteran asks why a new
              document changed nothing, whether processing finished, or what the app is
              doing right now.

            GROUNDING & CITATIONS
            - Every factual statement about regulations must cite its section and freshness,
              formatted as a markdown link with the cite: scheme — for example:
              [38 CFR § 4.71a (as of 2026-06-09)](cite:cfr/4.71a)
            - Every statement about the veteran's own records must cite the document,
              using the [doc:N] ids returned by search_my_file — for example:
              [your C&P exam, 2019-03-14](cite:doc/42)
            - If retrieval returns nothing relevant, say you couldn't find it in their file —
              never invent a citation, a regulation section, or a document.
            - The regulations data is an unofficial eCFR compilation. When asked about exact
              current law, recommend verifying with a Veterans Service Officer (VSO).""";

    /**
     * Builds the per-claim CLAIM STATE block — the cache-breakpoint block (Mission 6b).
     * Its bytes change ONLY when the claim's live atoms/active conditions actually
     * change, so within a session every turn after the first reads it from cache at
     * 0.1×. Cache-key hygiene: deterministic ordering throughout (atoms grouped by a
     * sorted type map then sorted by id; conditions sorted by id) and NO wall-clock
     * timestamps — only the atom's own stored date is rendered. A silent invalidator
     * here (e.g. HashMap iteration order) would make every turn re-pay the full write.
     */
    String buildClaimStateBlock(Long claimId) {
        // Mission 5a: ground chat on LIVE atoms only. Superseded atoms (retired by
        // a later re-extraction of the same document) stay in the table for
        // citation history, but the chat system prompt must reflect the current
        // analysis, not stale duplicates.
        String atomsDump = renderAtoms(atomRepository.findByClaimIdAndSupersededByIsNull(claimId));
        // Mission 5b: ground chat on the ACTIVE generation only. After a re-analysis
        // the prior generation's conditions are superseded (kept for citation
        // history); the chat system prompt must reflect the current generation so
        // the assistant never reasons over stale/duplicate conditions.
        String conditionsDump = renderConditions(conditionRepository.findByClaimIdAndSupersededByIsNull(claimId));

        // Trailing newline preserved so FROZEN_INSTRUCTIONS + "\n\n" + this block is
        // byte-identical to the legacy single text-block system prompt (whose closing
        // delimiter line left a trailing newline after the conditions dump).
        return """
                CURRENT CLAIM STATE
                %s

                %s
                """.formatted(atomsDump, conditionsDump);
    }

    private String renderAtoms(List<Atom> atoms) {
        if (atoms.isEmpty()) return "Atoms: (none yet)";
        // Mission 6b — deterministic order so the claim-state block bytes are stable
        // across turns (the cache prefix). groupingBy() returns a HashMap whose
        // iteration order is unspecified — a silent cache invalidator; group into a
        // sorted TreeMap and sort atoms by id within each type instead. Decimal
        // formatting is pinned to Locale.ROOT so a locale can't perturb the bytes.
        Map<String, List<Atom>> byType = atoms.stream()
                .collect(Collectors.groupingBy(Atom::getType, java.util.TreeMap::new, Collectors.toList()));
        StringBuilder sb = new StringBuilder("Atoms on file (").append(atoms.size()).append("):\n");
        for (Map.Entry<String, List<Atom>> e : byType.entrySet()) {
            sb.append("  ").append(e.getKey()).append(":\n");
            List<Atom> sorted = e.getValue().stream()
                    .sorted(java.util.Comparator.comparing(Atom::getId,
                            java.util.Comparator.nullsLast(java.util.Comparator.naturalOrder())))
                    .toList();
            for (Atom a : sorted) {
                sb.append("    #").append(a.getId()).append(" — ").append(a.getValue());
                if (a.getTimestamp() != null) sb.append(" [").append(a.getTimestamp()).append("]");
                if (a.getConfidence() != null) sb.append(" (conf ").append(String.format(java.util.Locale.ROOT, "%.2f", a.getConfidence())).append(")");
                sb.append("\n");
            }
        }
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private String renderConditions(List<IdentifiedCondition> conditions) {
        if (conditions.isEmpty()) return "Identified Conditions: (none yet — upload documents or run analyze first)";
        // Mission 6b — deterministic order (by id) so the claim-state block bytes are
        // stable across turns; the repository query order is not contractually fixed.
        conditions = conditions.stream()
                .sorted(java.util.Comparator.comparing(IdentifiedCondition::getId,
                        java.util.Comparator.nullsLast(java.util.Comparator.naturalOrder())))
                .toList();
        StringBuilder sb = new StringBuilder("Identified Conditions (").append(conditions.size()).append("):\n");
        for (IdentifiedCondition c : conditions) {
            sb.append("  #").append(c.getId()).append(" ").append(c.getName());
            if (c.getVasrdCode() != null) sb.append(" [VASRD ").append(c.getVasrdCode()).append("]");
            if (c.getEstimatedRating() != null) sb.append(" — rated ").append(c.getEstimatedRating()).append("%");
            if (Boolean.TRUE.equals(c.getIsPresumptive())) sb.append(" — presumptive: ").append(c.getPresumptiveBasis());
            sb.append("\n");

            // P1-12 — render the SAME detail the UI's "Why this number?" panel shows, so
            // the model can never contradict it: the rating rationale, the per-leg triad
            // (status + cited evidence), and each gap's how-to. Everything here comes from
            // the stored analysis (deterministic, no wall clock), so the block's bytes —
            // it sits inside the cached system prefix — change only when the analysis does.
            if (c.getRatingRationale() != null && !c.getRatingRationale().isBlank()) {
                sb.append("    Why this rating: ").append(c.getRatingRationale().strip()).append("\n");
            }
            if (hasTriad(c)) {
                sb.append("    Service-connection triad:\n");
                appendTriadLeg(sb, "diagnosis", c.getTriadDiagnosis());
                appendTriadLeg(sb, "in_service", c.getTriadInService());
                appendTriadLeg(sb, "nexus", c.getTriadNexus());
            }

            List<Map<String, Object>> gaps = (List<Map<String, Object>>) c.getGaps();
            if (gaps != null && !gaps.isEmpty()) {
                sb.append("    Gaps:\n");
                for (int i = 0; i < gaps.size(); i++) {
                    Map<String, Object> g = gaps.get(i);
                    Object status = g.get("status");
                    sb.append("      [").append(i).append("] ");
                    if (status != null) sb.append("(status=").append(status).append(") ");
                    sb.append(String.valueOf(g.getOrDefault("title", g.getOrDefault("description", "unnamed"))));
                    Object leg = g.get("triad_leg");
                    if (leg != null) sb.append(" (leg: ").append(leg).append(")");
                    Object priority = g.get("priority");
                    if (priority != null) sb.append(" (priority: ").append(priority).append(")");
                    sb.append("\n");
                    // P1-12 — the gap how-to the paid UI renders; without it the model
                    // invents its own steps that disagree with the on-screen guidance.
                    appendGapDetail(sb, g, "description", "What's needed: ");
                    appendGapDetail(sb, g, "how_to_get_it", "How to get it: ");
                    appendGapDetail(sb, g, "impact", "Impact: ");
                }
            }
        }
        return sb.toString();
    }

    private static boolean hasTriad(IdentifiedCondition c) {
        return (c.getTriadDiagnosis() != null && !c.getTriadDiagnosis().isEmpty())
                || (c.getTriadInService() != null && !c.getTriadInService().isEmpty())
                || (c.getTriadNexus() != null && !c.getTriadNexus().isEmpty());
    }

    /**
     * One triad leg: {@code status} + the cited {@code evidence} list, exactly as the
     * synthesis stored them ({@code {"status": "STRONG|...", "evidence": [...]}}). Only
     * named keys are read — never map iteration — so JSON key order can't perturb the
     * cached bytes; the evidence list keeps its stored order (stable per analysis).
     */
    private static void appendTriadLeg(StringBuilder sb, String label, Map<String, Object> leg) {
        if (leg == null || leg.isEmpty()) return;
        sb.append("      ").append(label).append(": ");
        Object status = leg.get("status");
        sb.append(status != null ? String.valueOf(status) : "unknown");
        Object evidence = leg.get("evidence");
        if (evidence instanceof List<?> list && !list.isEmpty()) {
            sb.append(" — evidence: ").append(list.stream()
                    .map(String::valueOf)
                    .collect(Collectors.joining("; ")));
        }
        sb.append("\n");
    }

    /** One indented gap-detail line, skipped when the key is absent/blank (cache-stable). */
    private static void appendGapDetail(StringBuilder sb, Map<String, Object> gap, String key, String label) {
        Object v = gap.get(key);
        if (v == null) return;
        String text = String.valueOf(v).strip();
        if (text.isEmpty()) return;
        sb.append("          ").append(label).append(text).append("\n");
    }

    /* ---------- message history ---------- */

    private List<Map<String, Object>> buildHistory(Long threadId, String veteranMessage) {
        List<IntakeMessage> prior = threadId != null
                ? messageRepository.findByThreadIdOrderByCreatedAt(threadId)
                : List.of();
        // Keep only the tail so the agent has fresh context without bloat
        int start = Math.max(0, prior.size() - HISTORY_TURNS);
        List<Map<String, Object>> history = new ArrayList<>();
        for (int i = start; i < prior.size(); i++) {
            IntakeMessage m = prior.get(i);
            String role = "veteran".equals(m.getRole()) ? "user" : "assistant";
            history.add(Map.of("role", role, "content", m.getContent() != null ? m.getContent() : ""));
        }
        history.add(Map.of("role", "user", "content", veteranMessage));
        return history;
    }

    /* ---------- tools ---------- */

    /** P1-19 (Phase G1): tools that mutate claim state — OWNER-ONLY. A viewer
     *  (X-View-As) never sees these in the schema, and {@link #executeTool}
     *  independently re-derives ownership before running them (defense in
     *  depth: a schema regression alone can never re-enable viewer writes). */
    static final Set<String> MUTATING_TOOLS = Set.of(
            "add_atom", "update_atom", "delete_atom",
            "update_condition", "delete_condition", "restore_condition",
            "mark_gap_resolved", "mark_gap_dismissed",
            // Increment C — both write claim/profile state for the OWNER only. Being in
            // this set means (a) they are stripped from a viewer principal's schema and
            // (b) the executeTool execution-time guard re-derives ownership before running
            // them (defense in depth). record_service_fact additionally writes ONLY the
            // authenticated owner's ServiceProfile (never a client-supplied user id).
            "request_reanalysis", "record_service_fact");

    /** Appended to the frozen instructions for viewer sessions only. Stable per
     *  principal type, so prompt caching still works within a session. */
    static final String VIEWER_MODE_ADDENDUM = """
            VIEWER MODE: You are assisting an invited representative (for example a VSO) \
            who is reviewing this veteran's claim, not the veteran themself. You have \
            READ-ONLY access: answer questions and cite evidence, but never offer to \
            record, update, or remove facts, conditions, or next steps — those actions \
            belong to the claim owner. If asked to change something, explain that only \
            the veteran can make changes in their own account.""";

    /** True when {@code userId} owns {@code claimId}. Viewer sessions (X-View-As)
     *  pass the CALLER's user id with the owner's claim id, so this distinguishes
     *  the two without any extra plumbing. Fails closed (unknown claim = not owner). */
    private boolean isOwnerPrincipal(Long claimId, Long userId) {
        if (claimId == null || userId == null) return false;
        return claimRepository.findById(claimId)
                .map(c -> userId.equals(c.getUserId()))
                .orElse(false);
    }

    private List<Map<String, Object>> buildToolSchema(boolean viewerPrincipal) {
        List<Map<String, Object>> all = buildToolSchema();
        if (!viewerPrincipal) return all;
        return all.stream()
                .filter(t -> !MUTATING_TOOLS.contains((String) t.get("name")))
                .toList();
    }

    private List<Map<String, Object>> buildToolSchema() {
        return List.of(
                Map.of(
                        "name", "add_atom",
                        "description", "Record a new atomic fact the veteran just shared (diagnosis, medication, symptom, event, exposure, date, provider, test_result, treatment, statement, service_record).",
                        "input_schema", Map.of(
                                "type", "object",
                                "properties", Map.of(
                                        "atom_type", Map.of("type", "string", "description", "e.g., diagnosis, medication, symptom, event, exposure, date, provider, test_result, treatment, statement, service_record"),
                                        "value", Map.of("type", "string", "description", "The specific fact text"),
                                        "timestamp", Map.of("type", "string", "description", "YYYY-MM-DD date associated with the fact, or null"),
                                        "confidence", Map.of("type", "number", "description", "0-1, how confident based on the user's statement")
                                ),
                                "required", List.of("atom_type", "value")
                        )
                ),
                Map.of(
                        "name", "update_atom",
                        "description", "Correct an existing atom's value, date, or confidence.",
                        "input_schema", Map.of(
                                "type", "object",
                                "properties", Map.of(
                                        "atom_id", Map.of("type", "integer"),
                                        "value", Map.of("type", "string"),
                                        "timestamp", Map.of("type", "string"),
                                        "confidence", Map.of("type", "number")
                                ),
                                "required", List.of("atom_id")
                        )
                ),
                Map.of(
                        "name", "delete_atom",
                        "description", "Remove an atom the veteran says is wrong or does not apply.",
                        "input_schema", Map.of(
                                "type", "object",
                                "properties", Map.of(
                                        "atom_id", Map.of("type", "integer"),
                                        "reason", Map.of("type", "string")
                                ),
                                "required", List.of("atom_id")
                        )
                ),
                // P1-14 — estimated_rating is deliberately ABSENT from this schema: ratings
                // are computed by the deterministic pipeline, never written by the chat
                // model. An attempted rating write is refused and marks the condition
                // dirty so the pipeline re-rates instead.
                Map.of(
                        "name", "update_condition",
                        "description", "Change an identified condition's name, VASRD code, or rating rationale based on what the veteran said. Ratings cannot be set here and editing does NOT trigger a re-run — only NEW evidence (a new atom or an upload) causes the pipeline to re-rate.",
                        "input_schema", Map.of(
                                "type", "object",
                                "properties", Map.of(
                                        "condition_id", Map.of("type", "integer"),
                                        "name", Map.of("type", "string"),
                                        "vasrd_code", Map.of("type", "string"),
                                        "rating_rationale", Map.of("type", "string")
                                ),
                                "required", List.of("condition_id")
                        )
                ),
                Map.of(
                        "name", "delete_condition",
                        "description", "Remove an identified condition the veteran says does not apply. Reversible: the condition is hidden (and stays hidden across future analysis runs); restore_condition undoes it.",
                        "input_schema", Map.of(
                                "type", "object",
                                "properties", Map.of(
                                        "condition_id", Map.of("type", "integer"),
                                        "reason", Map.of("type", "string")
                                ),
                                "required", List.of("condition_id")
                        )
                ),
                Map.of(
                        "name", "restore_condition",
                        "description", "Undo a previous delete_condition — bring a removed condition back into the analysis.",
                        "input_schema", Map.of(
                                "type", "object",
                                "properties", Map.of(
                                        "condition_id", Map.of("type", "integer", "description", "The id of the condition that was removed")
                                ),
                                "required", List.of("condition_id")
                        )
                ),
                Map.of(
                        "name", "mark_gap_resolved",
                        "description", "Mark an evidence gap as resolved — the veteran already has the evidence the gap asked for.",
                        "input_schema", Map.of(
                                "type", "object",
                                "properties", Map.of(
                                        "condition_id", Map.of("type", "integer"),
                                        "gap_index", Map.of("type", "integer", "description", "0-based index into the gaps array for that condition"),
                                        "note", Map.of("type", "string", "description", "What the veteran said about having it")
                                ),
                                "required", List.of("condition_id", "gap_index")
                        )
                ),
                Map.of(
                        "name", "mark_gap_dismissed",
                        "description", "Dismiss an evidence gap as not applicable to this veteran.",
                        "input_schema", Map.of(
                                "type", "object",
                                "properties", Map.of(
                                        "condition_id", Map.of("type", "integer"),
                                        "gap_index", Map.of("type", "integer"),
                                        "reason", Map.of("type", "string")
                                ),
                                "required", List.of("condition_id", "gap_index")
                        )
                ),
                // ---- Increment C: real re-analysis + capture service/exposure facts ----
                // request_reanalysis is the HONEST re-run trigger the model should prefer over
                // relying on the update_condition/estimated_rating dirty-marking side effect: it
                // sets claim.synthesisNeeded (honored by AnalysisScheduler as a real re-run, Increment
                // A) so presumptive eligibility + evidence gaps are re-checked. It writes NO rating or
                // legal flag.
                Map.of(
                        "name", "request_reanalysis",
                        "description", "Queue a re-analysis of the claim: re-check the veteran's conditions, presumptive eligibility, and evidence gaps against the current facts on file. Use when the veteran disputes a finding/rating or asks you to re-check after new information was captured. This does NOT set any rating and does NOT by itself move the rating NUMBER — the number changes only when NEW rating evidence (a new atom or an upload) is added.",
                        "input_schema", Map.of(
                                "type", "object",
                                "properties", Map.of(
                                        "reason", Map.of("type", "string", "description", "Optional: why a re-analysis was requested (e.g. what the veteran disputed or added)")
                                )
                        )
                ),
                // record_service_fact CAPTURES a service/exposure fact into the veteran's
                // ServiceProfile so the deterministic presumptive engine re-derives (and can flip a
                // PROVISIONAL PACT presumptive) on the queued re-run. It NEVER asserts a granted
                // presumptive and NEVER writes a rating.
                Map.of(
                        "name", "record_service_fact",
                        "description", "Record a service-history or exposure fact the veteran shared into their service profile so presumptive eligibility can be re-derived. Provide at least one field. Use this whenever the veteran mentions where/when they served, their branch, their job (MOS), a deployment location, or an exposure (burn pit, Agent Orange, herbicide, Gulf War). Recording a fact re-checks presumptive/gaps and can change readiness, but it NEVER grants a presumptive by itself — any presumptive it enables is PROVISIONAL pending VA confirmation of qualifying service, and the rating NUMBER still changes only with new rating evidence.",
                        "input_schema", Map.of(
                                "type", "object",
                                "properties", Map.of(
                                        "branch", Map.of("type", "string", "description", "Branch of service, e.g. Army, Navy, Air Force, Marines, Coast Guard, Space Force"),
                                        "service_start", Map.of("type", "string", "description", "Service start date, YYYY-MM-DD"),
                                        "service_end", Map.of("type", "string", "description", "Service end date, YYYY-MM-DD"),
                                        "mos", Map.of("type", "string", "description", "Military occupational specialty / job"),
                                        "deployment_location", Map.of("type", "string", "description", "A deployment location, e.g. Iraq, Afghanistan, Vietnam, Kuwait"),
                                        "exposure", Map.of("type", "string", "description", "An exposure, e.g. burn pit, Agent Orange, herbicide, Gulf War")
                                )
                        )
                ),
                // ---- Increment 7 (§E.1) grounding/read tools ----
                Map.of(
                        "name", "search_my_file",
                        "description", "Search the veteran's own uploaded documents (service records, exams, decision letters) for passages relevant to a question. Use before answering anything about what their file shows.",
                        "input_schema", Map.of(
                                "type", "object",
                                "properties", Map.of(
                                        "query", Map.of("type", "string", "description", "What to look for in the veteran's documents"),
                                        "k", Map.of("type", "integer", "description", "Max passages to return (optional)")
                                ),
                                "required", List.of("query")
                        )
                ),
                Map.of(
                        "name", "get_analysis",
                        "description", "Get the freshest read of the veteran's live identified conditions and evidence gaps. The CURRENT CLAIM STATE in the system prompt is a snapshot from the start of this turn; call this after a mutation tool fires (or to be sure you have the latest) to see the up-to-date analysis.",
                        "input_schema", Map.of(
                                "type", "object",
                                "properties", Map.of()
                        )
                ),
                // P1-12 — read-only pipeline visibility (the /jobs data, rendered for the
                // model) so "my new doc changed nothing, why?" is answerable.
                Map.of(
                        "name", "get_pipeline_status",
                        "description", "Read-only status of the analysis pipeline: each uploaded document's processing state (queued/processing/processed/error), whether condition analysis or gap analysis is running or queued, and when each last completed. Use when the veteran asks why a new document changed nothing, whether processing is done, or what the app is doing right now.",
                        "input_schema", Map.of(
                                "type", "object",
                                "properties", Map.of()
                        )
                ),
                Map.of(
                        "name", "vasrd_lookup",
                        "description", "Exact VA rating-schedule lookup by diagnostic code, or search the regulations (rating criteria, presumptive-condition rules). Provide dc_code for a specific diagnostic code's rating tiers, or query to search the regulation text.",
                        "input_schema", Map.of(
                                "type", "object",
                                "properties", Map.of(
                                        "dc_code", Map.of("type", "string", "description", "4-digit diagnostic code, e.g. 5260"),
                                        "query", Map.of("type", "string", "description", "Free-text search over the rating schedule and presumptive rules"),
                                        "k", Map.of("type", "integer", "description", "Max passages to return for a query search (optional)")
                                )
                        )
                )
        );
    }

    @SuppressWarnings("unchecked")
    private String executeTool(String name, Map<String, Object> input, Long claimId, Long userId, Long userMessageId) {
        // P1-19 execution-time guard (independent of the schema filter): a viewer
        // principal must never run a mutating tool, even if one leaks into the
        // schema or the model hallucinates a call.
        if (MUTATING_TOOLS.contains(name) && !isOwnerPrincipal(claimId, userId)) {
            log.warn("[chat] deny mutating tool '{}' claim={} user={} reason=viewer_principal",
                    name, claimId, userId);
            return "This action is only available to the claim owner. You have read-only access "
                    + "to this claim — let the veteran know so they can make the change themselves.";
        }
        try {
            switch (name) {
                case "add_atom" -> {
                    Atom a = new Atom();
                    a.setClaimId(claimId);
                    a.setType(str(input.get("atom_type")));
                    a.setValue(str(input.get("value")));
                    a.setSource("chat");
                    a.setCreatedBy("ai:" + claudeModel);
                    a.setTimestamp(str(input.get("timestamp")));
                    Object c = input.get("confidence");
                    a.setConfidence(c instanceof Number n ? n.doubleValue() : 0.7);
                    a.setMessageId(userMessageId);
                    a.setCreatorUserId(userId);
                    a = atomRepository.save(a);
                    return "Atom #" + a.getId() + " created.";
                }
                case "update_atom" -> {
                    Long id = toLong(input.get("atom_id"));
                    Optional<Atom> opt = atomRepository.findById(id);
                    if (opt.isEmpty()) return "Atom #" + id + " not found.";
                    Atom a = opt.get();
                    if (!Objects.equals(a.getClaimId(), claimId)) return "Atom #" + id + " belongs to a different claim.";
                    if (input.get("value") != null) a.setValue(str(input.get("value")));
                    if (input.get("timestamp") != null) a.setTimestamp(str(input.get("timestamp")));
                    Object c = input.get("confidence");
                    if (c instanceof Number n) a.setConfidence(n.doubleValue());
                    atomRepository.save(a);
                    return "Atom #" + id + " updated.";
                }
                case "delete_atom" -> {
                    Long id = toLong(input.get("atom_id"));
                    Optional<Atom> opt = atomRepository.findById(id);
                    if (opt.isEmpty()) return "Atom #" + id + " not found.";
                    if (!Objects.equals(opt.get().getClaimId(), claimId)) return "Atom #" + id + " belongs to a different claim.";
                    atomRepository.deleteById(id);
                    return "Atom #" + id + " deleted.";
                }
                case "update_condition" -> {
                    Long id = toLong(input.get("condition_id"));
                    Optional<IdentifiedCondition> opt = conditionRepository.findById(id);
                    if (opt.isEmpty()) return "Condition #" + id + " not found.";
                    IdentifiedCondition c = opt.get();
                    if (!Objects.equals(c.getClaimId(), claimId)) return "Condition #" + id + " belongs to a different claim.";
                    String stale = rejectIfNotActive(c, claimId);   // P1-14: no silent stale-row writes
                    if (stale != null) return stale;
                    if (input.get("name") != null) c.setName(str(input.get("name")));
                    if (input.get("vasrd_code") != null) c.setVasrdCode(str(input.get("vasrd_code")));
                    if (input.get("rating_rationale") != null) c.setRatingRationale(str(input.get("rating_rationale")));
                    StringBuilder result = new StringBuilder("Condition #" + id + " updated.");
                    if (input.get("estimated_rating") != null) {
                        // P1-14 — the schema no longer offers estimated_rating, but a model
                        // may still emit it. NEVER write a chat-provided rating (LLMs stay
                        // away from veteran-visible numbers); mark the condition dirty so
                        // the deterministic pipeline re-rates it instead.
                        markConditionDirty(c, claimId);
                        // HONEST COPY: markConditionDirty sets synthesisNeeded, which the
                        // scheduler now honors as a real trigger — so a re-analysis IS
                        // genuinely queued (it re-checks presumptive status + evidence
                        // gaps). But the rating NUMBER itself carries forward unless the
                        // EVIDENCE changes, so we do NOT promise the number will move.
                        result.append(" Note: I can't set a rating myself — the analysis pipeline"
                                + " computes ratings. I've flagged this condition for re-analysis, which"
                                + " re-checks its presumptive status and evidence gaps (use request_reanalysis"
                                + " to re-check the whole claim). The rating NUMBER itself changes when you add"
                                + " new evidence — upload a document or tell me a new fact.");
                    }
                    conditionRepository.save(c);
                    return result.toString();
                }
                case "delete_condition" -> {
                    Long id = toLong(input.get("condition_id"));
                    Optional<IdentifiedCondition> opt = conditionRepository.findById(id);
                    if (opt.isEmpty()) return "Condition #" + id + " not found.";
                    IdentifiedCondition c = opt.get();
                    if (!Objects.equals(c.getClaimId(), claimId)) return "Condition #" + id + " belongs to a different claim.";
                    String stale = rejectIfNotActive(c, claimId);   // P1-14
                    if (stale != null) return stale;
                    return suppressCondition(c, str(input.get("reason")), userId);
                }
                case "restore_condition" -> {
                    return restoreCondition(toLong(input.get("condition_id")), claimId);
                }
                case "mark_gap_resolved" -> {
                    return setGapStatus(input, claimId, "resolved");
                }
                case "mark_gap_dismissed" -> {
                    return setGapStatus(input, claimId, "dismissed");
                }
                // ---- Increment C: real re-analysis + capture service/exposure facts ----
                case "request_reanalysis" -> {
                    return requestReanalysis(claimId, str(input.get("reason")));
                }
                case "record_service_fact" -> {
                    return recordServiceFact(input, claimId, userId);
                }
                // ---- Increment 7 (§E.1) grounding/read tools ----
                case "search_my_file" -> {
                    return searchMyFile(input, claimId);
                }
                case "get_analysis" -> {
                    return getAnalysis(claimId);
                }
                case "get_pipeline_status" -> {
                    return getPipelineStatus(claimId);
                }
                case "vasrd_lookup" -> {
                    return vasrdLookup(input);
                }
                default -> {
                    return "Unknown tool: " + name;
                }
            }
        } catch (Exception e) {
            log.error("Tool {} failed: {}", name, e.getMessage(), e);
            return "Error executing " + name + ": " + e.getMessage();
        }
    }

    @SuppressWarnings("unchecked")
    private String setGapStatus(Map<String, Object> input, Long claimId, String status) {
        Long condId = toLong(input.get("condition_id"));
        Integer idx = toInt(input.get("gap_index"));
        if (condId == null || idx == null) return "Missing condition_id or gap_index.";

        Optional<IdentifiedCondition> opt = conditionRepository.findById(condId);
        if (opt.isEmpty()) return "Condition #" + condId + " not found.";
        IdentifiedCondition c = opt.get();
        if (!Objects.equals(c.getClaimId(), claimId)) return "Condition #" + condId + " belongs to a different claim.";
        String stale = rejectIfNotActive(c, claimId);   // P1-14: gap writes on stale rows were silent no-ops
        if (stale != null) return stale;

        List<Map<String, Object>> gaps = (List<Map<String, Object>>) c.getGaps();
        if (gaps == null || idx < 0 || idx >= gaps.size()) {
            return "No gap at index " + idx + " for condition #" + condId + ".";
        }
        Map<String, Object> gap = new LinkedHashMap<>(gaps.get(idx));
        gap.put("status", status);
        Object note = input.get("note");
        Object reason = input.get("reason");
        if (note != null) gap.put("resolved_note", String.valueOf(note));
        if (reason != null) gap.put("dismissed_reason", String.valueOf(reason));
        gaps.set(idx, gap);
        c.setGaps(gaps);
        conditionRepository.save(c);
        return "Gap " + idx + " on condition #" + condId + " marked " + status + ".";
    }

    /* ---------- Increment C: real re-analysis + capture service/exposure facts ---------- */

    /**
     * request_reanalysis — the honest re-run trigger. Sets {@code claim.synthesisNeeded=true}
     * (consumed once by {@code AnalysisScheduler.shouldRunSynthesis}, Increment A) so the
     * pipeline re-checks conditions, presumptive eligibility, and evidence gaps against the
     * current facts on file. Writes NO rating and NO presumptive/legal flag — that stays with
     * the deterministic engine. The rating NUMBER is never promised to move here; it changes
     * only when NEW rating evidence is added. Preferred over relying on the
     * update_condition/estimated_rating dirty-marking side effect.
     */
    private String requestReanalysis(Long claimId, String reason) {
        Optional<Claim> opt = claimRepository.findById(claimId);
        if (opt.isEmpty()) return "No claim found to re-analyze.";
        Claim claim = opt.get();
        claim.setSynthesisNeeded(true);
        claimRepository.save(claim);
        log.info("[chat] request_reanalysis queued for claim {}{}", claimId,
                reason != null && !reason.isBlank() ? " — reason: " + reason : "");
        return "I've queued a re-analysis of your claim — it will re-check your conditions, "
                + "presumptive eligibility, and evidence gaps. Ratings themselves only change "
                + "when new evidence is added.";
    }

    /**
     * record_service_fact — CAPTURE a service/exposure fact into the veteran's ServiceProfile
     * so the deterministic presumptive engine ({@code
     * EnhancedSynthesisOrchestrator.reconcilePresumptiveFromEvidence}, Increment B) re-derives
     * on the queued re-run and can finalize/flip a PROVISIONAL PACT presumptive. This method:
     * <ul>
     *   <li>UPSERTs the row via {@link ServiceProfileRepository#findByUserId} (or a new row
     *       attached to the owning {@link com.afterduty.model.User}, mirroring
     *       {@code AuthController.createOrUpdateProfile}'s {@code .user(user)} shape — the
     *       {@code user_id} column is insertable=false so it must come from the relationship);</li>
     *   <li>sets branch/dates/mos only when provided, NEVER clobbering an existing non-null
     *       value with null;</li>
     *   <li>APPENDs the deployment as {@code {"location": <value>}} (the exact element shape the
     *       presumptive engine reads via {@code depMap.get("location")}) and the exposure as a
     *       plain string, both de-duplicated;</li>
     *   <li>sets {@code claim.synthesisNeeded=true} so the engine re-derives with the new
     *       profile.</li>
     * </ul>
     * SECURITY: writes ONLY the CURRENT authenticated owner's profile — {@code userId} is the
     * resolved owning principal (the executeTool P1-19 guard already refused a non-owner before
     * reaching here), never a client-supplied id. NEVER asserts a granted presumptive; any
     * presumptive it enables re-derives as PROVISIONAL pending confirmation.
     */
    private String recordServiceFact(Map<String, Object> input, Long claimId, Long userId) {
        String branch = str(input.get("branch"));
        String serviceStart = str(input.get("service_start"));
        String serviceEnd = str(input.get("service_end"));
        String mos = str(input.get("mos"));
        String deployment = str(input.get("deployment_location"));
        String exposure = str(input.get("exposure"));

        boolean anyProvided = notBlank(branch) || notBlank(serviceStart) || notBlank(serviceEnd)
                || notBlank(mos) || notBlank(deployment) || notBlank(exposure);
        if (!anyProvided) {
            return "Tell me a service fact to record — a branch, service dates, an MOS, a "
                    + "deployment location, or an exposure.";
        }
        if (userId == null) {
            return "I couldn't identify whose profile to update, so I didn't record anything.";
        }

        // UPSERT the CURRENT owner's row. New rows attach the User relationship (user_id is
        // insertable=false), exactly like AuthController's manual-form writer.
        Optional<ServiceProfile> existing = serviceProfileRepository.findByUserId(userId);
        ServiceProfile profile;
        if (existing.isPresent()) {
            profile = existing.get();
        } else {
            com.afterduty.model.User owner = userRepository.findById(userId).orElse(null);
            if (owner == null) {
                return "I couldn't find your account to update the service profile.";
            }
            profile = ServiceProfile.builder()
                    .user(owner)
                    .deployments(new ArrayList<>())
                    .exposureRisks(new ArrayList<>())
                    .build();
        }

        // Scalars: set only when provided; never clobber an existing non-null with null/blank.
        List<String> recorded = new ArrayList<>();
        if (notBlank(branch)) { profile.setBranch(branch); recorded.add("branch " + branch); }
        if (notBlank(serviceStart)) { profile.setServiceStart(serviceStart); recorded.add("service start " + serviceStart); }
        if (notBlank(serviceEnd)) { profile.setServiceEnd(serviceEnd); recorded.add("service end " + serviceEnd); }
        if (notBlank(mos)) { profile.setMos(mos); recorded.add("MOS " + mos); }

        // Deployments: append {"location": <value>} (the shape the presumptive engine reads),
        // de-duped case-insensitively on the location value.
        if (notBlank(deployment)) {
            List<Map<String, Object>> deployments = profile.getDeployments() != null
                    ? new ArrayList<>(profile.getDeployments()) : new ArrayList<>();
            if (!containsLocation(deployments, deployment)) {
                Map<String, Object> dep = new LinkedHashMap<>();
                dep.put("location", deployment);
                deployments.add(dep);
                profile.setDeployments(deployments);
                recorded.add("deployment to " + deployment);
            } else {
                recorded.add("deployment to " + deployment + " (already on file)");
            }
        }

        // Exposure risks: plain strings matched verbatim by the engine; de-dup case-insensitively.
        if (notBlank(exposure)) {
            List<String> exposures = profile.getExposureRisks() != null
                    ? new ArrayList<>(profile.getExposureRisks()) : new ArrayList<>();
            if (exposures.stream().noneMatch(e -> e != null && e.equalsIgnoreCase(exposure))) {
                exposures.add(exposure);
                profile.setExposureRisks(exposures);
                recorded.add(exposure + " exposure");
            } else {
                recorded.add(exposure + " exposure (already on file)");
            }
        }

        serviceProfileRepository.save(profile);

        // Re-derive with the new profile: queue ONE re-run (consumed by the scheduler).
        claimRepository.findById(claimId).ifPresent(claim -> {
            claim.setSynthesisNeeded(true);
            claimRepository.save(claim);
        });
        log.info("[chat] record_service_fact for claim {} user {} — {}", claimId, userId, recorded);

        // HONEST COPY: name what was recorded, say a re-analysis is running, and frame any
        // presumptive as something this MAY qualify — PROVISIONAL, pending confirmation — never
        // as granted, and never promising a rating number moves.
        return "Recorded: " + String.join(", ", recorded) + ". Re-analyzing — this may qualify "
                + "conditions for a PACT Act presumptive once your qualifying service is confirmed "
                + "(it stays provisional until then, and the rating number itself changes only when "
                + "new rating evidence is added).";
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    /** True when {@code deployments} already contains an element whose "location" equals
     *  {@code location} case-insensitively (mirrors how the presumptive engine reads the key). */
    private static boolean containsLocation(List<Map<String, Object>> deployments, String location) {
        for (Map<String, Object> dep : deployments) {
            if (dep == null) continue;
            Object loc = dep.get("location");
            if (loc != null && location.equalsIgnoreCase(String.valueOf(loc))) return true;
        }
        return false;
    }

    /* ---------- P1-14 tool safety: stale-row rejection, dirty-marking, soft delete ---------- */

    /**
     * Soft-delete sentinel for {@code identified_conditions.superseded_by}, following the
     * {@code ConditionGenerationService.TOMBSTONE_MARKER (-1)} / {@code PENDING_MARKER (-2)}
     * convention (negative ⇒ can never collide with a real auto-increment id; any non-null
     * value excludes the row from every active-generation reader). {@code -3} = the veteran
     * removed this condition via chat; the paired {@link ConditionSuppression} row keeps it
     * from resurrecting in later generations and makes the removal undoable.
     */
    public static final long SUPPRESSED_MARKER = -3L;

    /**
     * P1-14 — mutation tools used to write straight through {@code findById}, which also
     * finds superseded rows: the write landed on a retired generation's row, invisible on
     * every surface — a silent no-op from the veteran's perspective. Returns a
     * self-correcting error (with the CURRENT list, so the model can retarget in the same
     * turn) for any non-active row; null when the row is live and the write may proceed.
     */
    private String rejectIfNotActive(IdentifiedCondition c, Long claimId) {
        Long superseded = c.getSupersededBy();
        if (superseded == null) return null;
        if (superseded == SUPPRESSED_MARKER) {
            return "Condition #" + c.getId() + " (" + c.getName() + ") was removed earlier in this chat. "
                    + "Use restore_condition to bring it back, or target one of the current conditions:\n"
                    + getAnalysis(claimId);
        }
        return "Condition #" + c.getId() + " (" + c.getName() + ") is from an older analysis — "
                + "that condition was updated. Here is the current list; target the matching current id:\n"
                + getAnalysis(claimId);
    }

    /**
     * P1-14 — a chat-side rating dispute never writes the number; it invalidates the
     * condition's carry-forward fingerprints (nulling {@code evidence_fingerprint} and
     * {@code last_full_run_at} forces DIRTY classification under per-condition scoping)
     * and queues a re-run via {@code synthesisNeeded}, so the deterministic pipeline
     * re-rates from evidence. The caller persists {@code c}.
     */
    private void markConditionDirty(IdentifiedCondition c, Long claimId) {
        c.setEvidenceFingerprint(null);
        c.setLastFullRunAt(null);
        claimRepository.findById(claimId).ifPresent(claim -> {
            claim.setSynthesisNeeded(true);
            claimRepository.save(claim);
        });
    }

    /**
     * P1-14 — soft delete: hide the live row behind {@link #SUPPRESSED_MARKER} (all
     * active-generation readers exclude it immediately) and record a durable
     * {@link ConditionSuppression} keyed by identity fingerprint, which the repository's
     * active read post-filters against — so the next identify run re-emitting the same
     * condition does NOT resurrect it. Fully undoable via restore_condition.
     */
    private String suppressCondition(IdentifiedCondition c, String reason, Long userId) {
        c.setSupersededBy(SUPPRESSED_MARKER);
        conditionRepository.save(c);

        ConditionSuppression s = new ConditionSuppression();
        s.setClaimId(c.getClaimId());
        s.setConditionId(c.getId());
        s.setIdentityFingerprint(c.getIdentityFingerprint());
        s.setConditionName(c.getName());
        s.setReason(reason);
        s.setCreatedByUserId(userId);
        s.setCreatedAt(java.time.Instant.now());
        conditionSuppressionRepository.save(s);

        return "Condition #" + c.getId() + " (" + c.getName() + ") removed. It will stay removed even "
                + "after future analysis runs. This can be undone with restore_condition.";
    }

    /**
     * P1-14 — undo a chat-side condition removal: lift the suppression record, then make
     * exactly ONE row of that identity visible again. If a later analysis run re-identified
     * the condition while it was suppressed (rows exist with {@code superseded_by = null}
     * that the read filter was hiding), the NEWEST of those takes over and older hidden
     * copies are retired onto it; otherwise the originally-suppressed row's marker is
     * cleared. Deterministic id-ordering throughout.
     */
    private String restoreCondition(Long conditionId, Long claimId) {
        if (conditionId == null) return "Missing condition_id.";
        Optional<ConditionSuppression> suppression = conditionSuppressionRepository
                .findFirstByClaimIdAndConditionIdAndLiftedAtIsNullOrderByIdDesc(claimId, conditionId);
        if (suppression.isEmpty()) {
            return "Condition #" + conditionId + " has no removal to undo.";
        }
        ConditionSuppression s = suppression.get();
        s.setLiftedAt(java.time.Instant.now());
        conditionSuppressionRepository.save(s);

        Optional<IdentifiedCondition> opt = conditionRepository.findById(conditionId);
        if (opt.isEmpty() || !Objects.equals(opt.get().getClaimId(), claimId)) {
            // Row is gone (legacy hard-delete era) — the lifted suppression at least stops
            // filtering future generations, so the pipeline can re-identify it.
            return "The removal record was lifted; the condition will reappear after the next analysis run.";
        }
        IdentifiedCondition c = opt.get();

        // Later-generation copies of the same identity that the suppression filter was hiding.
        String fp = c.getIdentityFingerprint();
        List<IdentifiedCondition> hiddenActives = fp == null ? List.of()
                : conditionRepository.findByClaimId(claimId).stream()
                        .filter(x -> fp.equals(x.getIdentityFingerprint()) && x.getSupersededBy() == null)
                        .sorted(java.util.Comparator.comparing(IdentifiedCondition::getId))
                        .toList();
        if (!hiddenActives.isEmpty()) {
            IdentifiedCondition newest = hiddenActives.get(hiddenActives.size() - 1);
            for (IdentifiedCondition older : hiddenActives) {
                if (!Objects.equals(older.getId(), newest.getId())) {
                    older.setSupersededBy(newest.getId());
                    conditionRepository.save(older);
                }
            }
            return "Condition \"" + newest.getName() + "\" restored (now #" + newest.getId()
                    + ", from the latest analysis).";
        }

        if (c.getSupersededBy() != null && c.getSupersededBy() == SUPPRESSED_MARKER) {
            c.setSupersededBy(null);
            conditionRepository.save(c);
        }
        return "Condition #" + c.getId() + " (" + c.getName() + ") restored.";
    }

    /* ---------- Increment 7 grounding tools (§E.1) ---------- */

    private static final String RAG_DISABLED = "Document search is not available right now.";

    /**
     * search_my_file — hybrid retrieval over the veteran's OWN document chunks, scoped to
     * this claim (the retrieval service binds claim_id in both arms, §D.2, so this can
     * never see another claim's evidence). Renders numbered, citable blocks the model
     * turns into [doc:N](cite:doc/N) links.
     */
    private String searchMyFile(Map<String, Object> input, Long claimId) {
        if (!ragEnabled) return RAG_DISABLED;
        String query = str(input.get("query"));
        if (query == null || query.isBlank()) return "Provide a search query.";
        Integer k = toInt(input.get("k"));
        List<RetrievedChunk> hits = hybridRetrievalService.searchEvidence(
                claimId, query, k == null ? 0 : k);
        if (hits.isEmpty()) {
            return "No passages found in the veteran's documents for: " + query;
        }
        StringBuilder sb = new StringBuilder("Found ").append(hits.size())
                .append(" passage(s) in the veteran's documents:\n");
        for (RetrievedChunk c : hits) {
            // [doc:{evidenceId}] {source}{ ({docDate})} — {sectionPath}\n{content}
            sb.append("\n[doc:").append(c.evidenceId()).append("] ").append(c.source());
            if (c.docDate() != null && !c.docDate().isBlank()) {
                sb.append(" (").append(c.docDate()).append(")");
            }
            if (c.sectionPath() != null && !c.sectionPath().isBlank()) {
                sb.append(" — ").append(c.sectionPath());
            }
            sb.append("\n").append(c.content()).append("\n");
        }
        return sb.toString().strip();
    }

    /**
     * get_analysis — fresh read of the LIVE (non-superseded) conditions + gaps, reusing
     * the same renderer the cached claim-state block uses. The system block is a turn-start
     * snapshot; this is the up-to-date read after a mutation tool (or a concurrent pipeline
     * run) changes state mid-turn.
     */
    private String getAnalysis(Long claimId) {
        List<IdentifiedCondition> conditions =
                conditionRepository.findByClaimIdAndSupersededByIsNull(claimId);
        return "Current analysis (live):\n" + renderConditions(conditions);
    }

    /**
     * get_pipeline_status (P1-12) — read-only render of the same data
     * {@code GET /api/claim/jobs} serializes for the cross-screen actions bar: per-document
     * extraction statuses, synthesis/gap-analysis run state with last-completed stamps, the
     * gap-analysis-pending signal (P0-5), and the queued-re-analysis flag — so "my new doc
     * changed nothing, why?" is answerable from state instead of guessed. Tool results are
     * not part of the cached prefix, so timestamps are fine here.
     */
    @SuppressWarnings("unchecked")
    private String getPipelineStatus(Long claimId) {
        Claim claim = claimRepository.findById(claimId).orElse(null);
        if (claim == null) return "No claim found.";

        StringBuilder sb = new StringBuilder("Pipeline status (live):\n");
        sb.append("Claim status: ").append(claim.getStatus() != null ? claim.getStatus().name() : "DRAFT");
        if (claim.getAnalysisMessage() != null && !claim.getAnalysisMessage().isBlank()) {
            sb.append(" — ").append(claim.getAnalysisMessage());
        }
        sb.append("\n");

        List<EvidenceItem> evidence = evidenceRepository.findByClaimIdOrderByCreatedAt(claimId);
        int pending = 0, processing = 0, processed = 0, errored = 0;
        StringBuilder docs = new StringBuilder();
        for (EvidenceItem e : evidence) {
            String status = e.getProcessingStatus() != null ? e.getProcessingStatus() : "pending";
            switch (status) {
                case "pending", "queued" -> pending++;
                case "processing" -> processing++;
                case "processed" -> processed++;
                case "error" -> errored++;
                default -> { }
            }
            docs.append("  - ").append(e.getFilename() != null ? e.getFilename() : "document")
                    .append(": ").append(status);
            if (("error".equals(status) || "deferred_usage_limit".equals(status))
                    && e.getProcessingMessage() != null) {
                docs.append(" (").append(e.getProcessingMessage()).append(")");
            }
            docs.append("\n");
        }
        sb.append("Documents (").append(evidence.size()).append("): ")
                .append(pending).append(" queued, ").append(processing).append(" processing, ")
                .append(processed).append(" processed, ").append(errored).append(" failed\n");
        sb.append(docs);

        sb.append("Condition analysis (synthesis): ")
                .append(Boolean.TRUE.equals(claim.getSynthesisInProgress()) ? "running" : "idle");
        if (claim.getLastSynthesisAt() != null) {
            sb.append("; last completed ").append(claim.getLastSynthesisAt());
        }
        sb.append("\n");
        sb.append("Gap analysis: ")
                .append(Boolean.TRUE.equals(claim.getGapAnalysisInProgress()) ? "running" : "idle");
        if (claim.getLastGapAnalysisAt() != null) {
            sb.append("; last completed ").append(claim.getLastGapAnalysisAt());
        }
        sb.append("\n");

        // P0-5 signal, same predicate family as /jobs: an active condition with null gaps
        // means the generation flipped but its gap analysis hasn't landed yet.
        List<IdentifiedCondition> conditions =
                conditionRepository.findByClaimIdAndSupersededByIsNull(claimId);
        boolean gapAnalysisPending = false;
        int openGaps = 0;
        for (IdentifiedCondition c : conditions) {
            List<Map<String, Object>> gaps = (List<Map<String, Object>>) c.getGaps();
            if (gaps == null) {
                gapAnalysisPending = true;
            } else {
                for (Map<String, Object> g : gaps) {
                    if (com.afterduty.service.gap.UserGapStateService.isOpen(g)) openGaps++;
                }
            }
        }
        if (gapAnalysisPending) {
            sb.append("Gap analysis for the newest conditions has not landed yet (re-checking).\n");
        }
        sb.append("Re-analysis queued (new facts waiting to be analyzed): ")
                .append(Boolean.TRUE.equals(claim.getSynthesisNeeded()) ? "yes" : "no").append("\n");
        sb.append("Totals: ")
                .append(atomRepository.countByClaimIdAndSupersededByIsNull(claimId)).append(" facts on file, ")
                .append(conditions.size()).append(" conditions, ")
                .append(openGaps).append(" open gaps");
        return sb.toString();
    }

    /**
     * vasrd_lookup — structured rating-schedule lookup by diagnostic code (DB-first via
     * VasrdRecordRepository, falling back to the curated JSON when the table is cold), or a
     * regulation-text search over both vasrd + presumptives KB chunks. Every block is stamped
     * with the eCFR "as of" date so the model can cite freshness.
     */
    private String vasrdLookup(Map<String, Object> input) {
        String dcCode = str(input.get("dc_code"));
        if (dcCode != null && !dcCode.isBlank()) {
            return vasrdLookupByCode(dcCode.strip());
        }
        String query = str(input.get("query"));
        if (query == null || query.isBlank()) {
            return "Provide a dc_code (e.g. 5260) or a query.";
        }
        if (!ragEnabled) return RAG_DISABLED;
        Integer k = toInt(input.get("k"));
        // searchKb(query, null, k) → both vasrd and presumptives chunks (claim_id IS NULL).
        List<RetrievedChunk> hits = hybridRetrievalService.searchKb(query, null, k == null ? 0 : k);
        if (hits.isEmpty()) {
            return "No regulation passages found for: " + query;
        }
        StringBuilder sb = new StringBuilder("Regulation passages for \"").append(query).append("\":\n");
        for (RetrievedChunk c : hits) {
            // [38 CFR § {cfr_section}, as of {as_of_date}] {content}
            sb.append("\n[38 CFR § ").append(c.cfrSection() != null ? c.cfrSection() : "?");
            if (c.asOfDate() != null) sb.append(", as of ").append(c.asOfDate());
            sb.append("] ").append(c.content()).append("\n");
        }
        return sb.toString().strip();
    }

    private String vasrdLookupByCode(String dcCode) {
        List<VasrdRecord> records = vasrdRecordRepository.findByDcCodeOrderByDisplayOrder(dcCode);
        if (!records.isEmpty()) {
            VasrdRecord first = records.get(0);
            String section = first.getCfrSection() != null ? first.getCfrSection() : "4.71a";
            String asOf = first.getAsOfDate() != null ? first.getAsOfDate().toString() : "unknown";
            String title = first.getTitle() != null ? first.getTitle() : "";

            // Adversarial-review minor (citation-freshness guarantee, goal #2): the header
            // stamps section + as_of from row 0 only. Post-delete-then-insert-per-section
            // all tiers SHOULD share them, but a partial nightly re-ingest (one section
            // re-ingested while a code also appears under another section) could leave tiers
            // with different cfr_section/as_of_date — making a row-0 header misrepresent the
            // freshness/section the model cites for later tiers. So when the tiers diverge,
            // we stamp each tier individually instead of trusting a single header.
            boolean uniformStamp = records.stream().allMatch(r ->
                    java.util.Objects.equals(r.getCfrSection(), first.getCfrSection())
                    && java.util.Objects.equals(r.getAsOfDate(), first.getAsOfDate()));

            StringBuilder sb = new StringBuilder("DC ").append(dcCode);
            if (!title.isBlank()) sb.append(" — ").append(title);
            if (uniformStamp) {
                sb.append(" (38 CFR § ").append(section).append(", current as of ").append(asOf).append("):\n");
                for (VasrdRecord r : records) {
                    appendTier(sb, r, false);
                }
            } else {
                // Mixed freshness/section across tiers — do NOT assert a single header date.
                sb.append(" (38 CFR § ").append(section)
                        .append("; freshness varies by tier — see per-tier dates):\n");
                for (VasrdRecord r : records) {
                    appendTier(sb, r, true);
                }
            }
            return sb.toString().strip();
        }
        // Cold/disabled table → curated JSON fallback (still signature-compatible).
        Optional<Map<String, Object>> json = vasrdDataService.getByCode(dcCode);
        if (json.isEmpty()) {
            return "No rating-schedule entry found for diagnostic code " + dcCode + ".";
        }
        Map<String, Object> m = json.get();
        StringBuilder sb = new StringBuilder("DC ").append(dcCode);
        Object name = m.get("name");
        if (name != null && !String.valueOf(name).isBlank()) sb.append(" — ").append(name);
        Object section = m.getOrDefault("cfr_section", "4.71a");
        sb.append(" (38 CFR § ").append(section);
        Object asOf = m.get("as_of_date");
        if (asOf != null) sb.append(", current as of ").append(asOf);
        sb.append("):\n");
        Object criteria = m.get("rating_criteria");
        if (criteria != null) sb.append(criteria);
        return sb.toString().strip();
    }

    /**
     * Append one rating tier. When {@code perTierStamp} is true (tiers disagree on
     * freshness/section), each line carries its own "[as of …]" so the model can never
     * cite a single header date for a tier that was ingested at a different time.
     */
    private static void appendTier(StringBuilder sb, VasrdRecord r, boolean perTierStamp) {
        if (r.getRatingPct() != null) {
            sb.append("  ").append(r.getRatingPct()).append("% — ");
        } else {
            sb.append("  Note: ");
        }
        sb.append(r.getCriteriaText() != null ? r.getCriteriaText() : "");
        if (perTierStamp) {
            String tierSection = r.getCfrSection() != null ? r.getCfrSection() : "?";
            String tierAsOf = r.getAsOfDate() != null ? r.getAsOfDate().toString() : "unknown";
            sb.append(" [38 CFR § ").append(tierSection).append(", as of ").append(tierAsOf).append("]");
        }
        sb.append("\n");
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static Long toLong(Object o) {
        if (o instanceof Number n) return n.longValue();
        if (o instanceof String s) try { return Long.parseLong(s); } catch (Exception ignored) {}
        return null;
    }

    private static Integer toInt(Object o) {
        if (o instanceof Number n) return n.intValue();
        if (o instanceof String s) try { return Integer.parseInt(s); } catch (Exception ignored) {}
        return null;
    }
}
