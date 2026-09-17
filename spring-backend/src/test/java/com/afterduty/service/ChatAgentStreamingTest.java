package com.afterduty.service;

import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.RawContentBlockDeltaEvent;
import com.anthropic.models.messages.RawMessageStreamEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.afterduty.model.IntakeMessage;
import com.afterduty.repository.AtomRepository;
import com.afterduty.repository.ConditionRepository;
import com.afterduty.repository.MessageRepository;
import com.afterduty.repository.VasrdRecordRepository;
import com.afterduty.service.rag.HybridRetrievalService;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

/**
 * Increment 7 §F.2 — ChatAgent streaming. Verifies (a) the dispatch of raw SDK stream events
 * onto the {@link ChatStreamListener} (text deltas in order, tool-start status, non-text deltas
 * dropped) using the REAL {@link ChatAgent#dispatchStreamEvent}, and (b) the retry-cap nuance:
 * a 429 BEFORE any delta retries (≤3); a failure AFTER the first delta is NOT retried (would
 * duplicate streamed text) and surfaces as an agent error.
 *
 * <p>The round-trip is scripted via a {@code streamToRawJson} override so no live client and no
 * full SDK {@code Message} construction is needed; the override still drives the real
 * dispatcher with constructed raw events.
 */
@Tag("regression")
class ChatAgentStreamingTest {

    private static final ObjectMapper M = new ObjectMapper();

    private final AtomRepository atomRepo = Mockito.mock(AtomRepository.class);
    private final ConditionRepository condRepo = Mockito.mock(ConditionRepository.class);
    private final MessageRepository msgRepo = Mockito.mock(MessageRepository.class);

    private static final class RecordingListener implements ChatStreamListener {
        final List<String> deltas = new ArrayList<>();
        final List<String> statuses = new ArrayList<>();
        @Override public void onDelta(String text) { deltas.add(text); }
        @Override public void onStatus(String phase, String toolName) {
            statuses.add(toolName == null ? phase : phase + ":" + toolName);
        }
    }

    /** ChatAgent whose round-trip is scripted; everything else (loop, dispatch, retry) is real. */
    private static final class ScriptedAgent extends ChatAgent {
        java.util.function.BiFunction<MessageCreateParams, ChatStreamListener,
                com.fasterxml.jackson.databind.JsonNode> script;
        final AtomicInteger streamCalls = new AtomicInteger();
        ScriptedAgent(AtomRepository a, ConditionRepository c, MessageRepository m) {
            super(a, c, m, Mockito.mock(AiCostService.class),
                    Mockito.mock(HybridRetrievalService.class),
                    Mockito.mock(VasrdRecordRepository.class),
                    Mockito.mock(VasrdDataService.class),
                    Mockito.mock(com.afterduty.repository.EvidenceRepository.class),
                    Mockito.mock(com.afterduty.repository.ClaimRepository.class),
                    Mockito.mock(com.afterduty.repository.ConditionSuppressionRepository.class),
                    Mockito.mock(com.afterduty.repository.ServiceProfileRepository.class),
                    Mockito.mock(com.afterduty.repository.UserRepository.class));
        }
        @Override
        com.fasterxml.jackson.databind.JsonNode streamToRawJson(
                MessageCreateParams params, ChatStreamListener listener) {
            streamCalls.incrementAndGet();
            return script.apply(params, listener);
        }
    }

    private ScriptedAgent agent() throws Exception {
        ScriptedAgent a = new ScriptedAgent(atomRepo, condRepo, msgRepo);
        set(a, "retryBackoffMs", 0L);                 // no backoff sleeps in tests
        set(a, "claudeModel", "claude-sonnet-4-6");   // @Value not injected here
        set(a, "client", Mockito.mock(com.anthropic.client.AnthropicClient.class)); // skip buildClient
        when(atomRepo.findByClaimIdAndSupersededByIsNull(anyLong())).thenReturn(List.of());
        when(condRepo.findByClaimIdAndSupersededByIsNull(anyLong())).thenReturn(List.of());
        when(msgRepo.findById(anyLong())).thenReturn(Optional.of(
                IntakeMessage.builder().id(100L).threadId(1L).role("veteran").content("q").build()));
        when(msgRepo.findByThreadIdOrderByCreatedAt(anyLong())).thenReturn(List.of());
        return a;
    }

    private static void set(Object target, String field, Object value) throws Exception {
        Field f = ChatAgent.class.getDeclaredField(field);
        f.setAccessible(true);
        f.set(target, value);
    }

    /** A minimal end_turn raw response with the given final text — matches toRawJson's shape. */
    private static ObjectNode endTurn(String finalText) {
        ObjectNode root = M.createObjectNode();
        root.put("model", "claude-sonnet-4-6");
        root.put("stop_reason", "end_turn");
        ObjectNode usage = root.putObject("usage");
        usage.put("input_tokens", 10);
        usage.put("output_tokens", 5);
        ObjectNode block = root.putArray("content").addObject();
        block.put("type", "text");
        block.put("text", finalText);
        return root;
    }

    /** A tool_use raw response: one text block (streamed pre-tool commentary) + one tool call. */
    private static ObjectNode toolUseTurn(String text, String toolName) {
        ObjectNode root = M.createObjectNode();
        root.put("model", "claude-sonnet-4-6");
        root.put("stop_reason", "tool_use");
        ObjectNode usage = root.putObject("usage");
        usage.put("input_tokens", 10);
        usage.put("output_tokens", 5);
        var content = root.putArray("content");
        ObjectNode t = content.addObject();
        t.put("type", "text");
        t.put("text", text);
        ObjectNode tu = content.addObject();
        tu.put("type", "tool_use");
        tu.put("id", "tu_" + toolName);
        tu.put("name", toolName);
        tu.set("input", M.createObjectNode());
        return root;
    }

    // ---- raw SDK event helpers (drive the REAL dispatchStreamEvent) ----

    private static RawMessageStreamEvent textDeltaEvent(String text) {
        return RawMessageStreamEvent.ofContentBlockDelta(
                RawContentBlockDeltaEvent.builder().index(0).textDelta(text).build());
    }

    private static RawMessageStreamEvent inputJsonDeltaEvent(String json) {
        return RawMessageStreamEvent.ofContentBlockDelta(
                RawContentBlockDeltaEvent.builder().index(0).inputJsonDelta(json).build());
    }

    private static RawMessageStreamEvent toolStartEvent(String toolName) {
        // Deserialize the canonical content_block_start wire shape rather than hand-build the
        // ToolUseBlock (which has many required internal fields); this exercises the real
        // SDK variant resolution the production stream consumes.
        return JsonValue.from(java.util.Map.of(
                "type", "content_block_start",
                "index", 0,
                "content_block", java.util.Map.of(
                        "type", "tool_use",
                        "id", "tu_1",
                        "name", toolName,
                        "input", java.util.Map.of())
        )).convert(RawMessageStreamEvent.class);
    }

    // ---- tests ----

    @Test
    void deltasForwardedInOrder_andFinalTextReturned() throws Exception {
        ScriptedAgent a = agent();
        RecordingListener listener = new RecordingListener();
        a.script = (params, l) -> {
            boolean[] seen = {false};
            a.dispatchStreamEvent(textDeltaEvent("Hello "), l, seen);
            a.dispatchStreamEvent(textDeltaEvent("there"), l, seen);
            return endTurn("Hello there");
        };

        String reply = a.handleStreaming("hi", 7L, 1L, 100L, listener);

        assertThat(listener.deltas).containsExactly("Hello ", "there");
        assertThat(reply).isEqualTo("Hello there");
        assertThat(listener.statuses).contains("thinking");   // emitted at round-trip start
    }

    @Test
    void toolStartEmitsStatusWithToolName() throws Exception {
        ScriptedAgent a = agent();
        RecordingListener listener = new RecordingListener();
        a.script = (params, l) -> {
            boolean[] seen = {false};
            a.dispatchStreamEvent(toolStartEvent("search_my_file"), l, seen);
            a.dispatchStreamEvent(textDeltaEvent("done"), l, seen);
            return endTurn("done");
        };

        a.handleStreaming("hi", 7L, 1L, 100L, listener);
        assertThat(listener.statuses).contains("tool:search_my_file");
    }

    @Test
    void nonTextDelta_doesNotForward() throws Exception {
        ScriptedAgent a = agent();
        RecordingListener listener = new RecordingListener();
        a.script = (params, l) -> {
            boolean[] seen = {false};
            a.dispatchStreamEvent(inputJsonDeltaEvent("{\"q\":"), l, seen);  // tool args, not text
            a.dispatchStreamEvent(textDeltaEvent("answer"), l, seen);
            return endTurn("answer");
        };

        a.handleStreaming("hi", 7L, 1L, 100L, listener);
        assertThat(listener.deltas).containsExactly("answer");
    }

    // P1-13 — the persisted reply must accumulate text ACROSS tool-use iterations. Deltas
    // from every iteration go out on the wire; rebuilding the buffer per iteration made the
    // persisted transcript keep only the LAST iteration's text (earlier streamed text
    // vanished on reload).
    @Test
    void textAccumulatesAcrossThreeIterations_persistedReplyMatchesStreamedDeltas() throws Exception {
        ScriptedAgent a = agent();
        RecordingListener listener = new RecordingListener();
        AtomicInteger iteration = new AtomicInteger();
        a.script = (params, l) -> {
            int n = iteration.incrementAndGet();
            boolean[] seen = {false};
            switch (n) {
                case 1 -> {
                    a.dispatchStreamEvent(textDeltaEvent("Let me check your file. "), l, seen);
                    return toolUseTurn("Let me check your file. ", "get_analysis");
                }
                case 2 -> {
                    a.dispatchStreamEvent(textDeltaEvent("One more look. "), l, seen);
                    return toolUseTurn("One more look. ", "get_analysis");
                }
                default -> {
                    a.dispatchStreamEvent(textDeltaEvent("Your knee is rated 10%."), l, seen);
                    return endTurn("Your knee is rated 10%.");
                }
            }
        };

        String reply = a.handleStreaming("why 10%?", 7L, 1L, 100L, listener);

        // Every iteration's text streamed…
        assertThat(String.join("", listener.deltas))
                .isEqualTo("Let me check your file. One more look. Your knee is rated 10%.");
        // …and the PERSISTED reply is the same concatenation, not just the last chunk.
        assertThat(reply).isEqualTo("Let me check your file. One more look. Your knee is rated 10%.");
        assertThat(a.streamCalls.get()).isEqualTo(3);
    }

    @Test
    void rateLimited_beforeAnyDelta_retriesUpToCapThenSucceeds() throws Exception {
        ScriptedAgent a = agent();
        RecordingListener listener = new RecordingListener();
        AtomicInteger attempt = new AtomicInteger();
        a.script = (params, l) -> {
            int n = attempt.incrementAndGet();
            if (n < 3) throw new RuntimeException("429 rate limit");  // no delta forwarded
            boolean[] seen = {false};
            a.dispatchStreamEvent(textDeltaEvent("ok"), l, seen);
            return endTurn("ok");
        };

        String reply = a.handleStreaming("hi", 7L, 1L, 100L, listener);

        assertThat(reply).isEqualTo("ok");
        assertThat(a.streamCalls.get()).isEqualTo(3);   // retried twice then success (≤3)
        assertThat(listener.deltas).containsExactly("ok");
    }

    // P1-10 — provider brownout exhaustion is typed (ChatRateLimitedException) so the wire
    // can classify it as retryable "rate_limited" instead of the generic agent error, and
    // each backoff emits a "rate_limited" status so the UI stops showing "Thinking…"
    // through the whole (previously ~90s) stall.
    @Test
    void rateLimited_allAttemptsExhausted_throwsTypedException_andEmitsRateLimitedStatus() throws Exception {
        ScriptedAgent a = agent();
        RecordingListener listener = new RecordingListener();
        a.script = (params, l) -> { throw new RuntimeException("429 rate limit"); };

        assertThatThrownBy(() -> a.handleStreaming("hi", 7L, 1L, 100L, listener))
                .isInstanceOf(ChatAgent.ChatRateLimitedException.class)
                .hasMessageContaining("rate-limited");

        assertThat(a.streamCalls.get()).isEqualTo(3);   // bounded: exactly MAX_429_ATTEMPTS
        // One "rate_limited" status per backoff (attempts 1 and 2 back off; 3 gives up).
        assertThat(listener.statuses.stream().filter("rate_limited"::equals).count()).isEqualTo(2);
    }

    // P1-10 — a NON-retryable failure (not a 429/503) must stay a plain RuntimeException:
    // the controller maps only the typed rate-limit exhaustion to "rate_limited".
    @Test
    void nonRateLimitFailure_isNotClassifiedAsRateLimited() throws Exception {
        ScriptedAgent a = agent();
        a.script = (params, l) -> { throw new RuntimeException("500 internal error"); };

        assertThatThrownBy(() -> a.handleStreaming("hi", 7L, 1L, 100L, ChatStreamListener.NOOP))
                .isInstanceOf(RuntimeException.class)
                .isNotInstanceOf(ChatAgent.ChatRateLimitedException.class);
        assertThat(a.streamCalls.get()).isEqualTo(1);   // non-retryable: no retries burned
    }

    @Test
    void failureAfterFirstDelta_isNotRetried_andSurfacesAsAgentError() throws Exception {
        ScriptedAgent a = agent();
        RecordingListener listener = new RecordingListener();
        a.script = (params, l) -> {
            boolean[] seen = {false};
            a.dispatchStreamEvent(textDeltaEvent("half "), l, seen);   // sets the delta-seen guard
            if (seen[0]) {
                throw new ChatAgent.StreamFailedAfterDeltaException(
                        new RuntimeException("connection reset"));
            }
            return endTurn("never");
        };

        assertThatThrownBy(() -> a.handleStreaming("hi", 7L, 1L, 100L, listener))
                .isInstanceOf(RuntimeException.class);
        assertThat(a.streamCalls.get()).isEqualTo(1);            // never retried
        assertThat(listener.deltas).containsExactly("half ");   // the partial delta went out
    }
}
