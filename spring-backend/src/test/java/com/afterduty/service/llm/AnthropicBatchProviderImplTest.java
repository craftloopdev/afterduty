package com.afterduty.service.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.afterduty.model.LlmJob;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Offline coverage for {@link AnthropicBatchProviderImpl} — the raw-JSON batch
 * lane. Exercises {@code buildParams} (the per-request {@code params} object sent
 * inside a Message Batches entry) and {@code parseMessage} (usage extraction)
 * with no network. Asserts the SAME cache-aware {@code system} shape as the typed
 * Vertex lane plus flag-off byte-parity, and that cache_read/cache_creation token
 * counts are captured.
 */
@Tag("regression")
class AnthropicBatchProviderImplTest {

    private final ObjectMapper om = new ObjectMapper();

    private AnthropicBatchProviderImpl newProvider(boolean promptCaching) throws Exception {
        AnthropicBatchProviderImpl p = new AnthropicBatchProviderImpl();
        Field f = AnthropicBatchProviderImpl.class.getDeclaredField("promptCachingEnabled");
        f.setAccessible(true);
        f.setBoolean(p, promptCaching);
        return p;
    }

    private LlmJob jobWithPayload(String model, Map<String, Object> payload) throws Exception {
        return LlmJob.builder()
                .id(UUID.randomUUID())
                .provider(AnthropicBatchProvider.NAME)
                .modelName(model)
                .purpose("gap_evidence")
                .status(LlmJob.Status.QUEUED)
                .requestPayload(om.writeValueAsString(payload))
                .build();
    }

    // -------------------------------------------------------------------------
    // system rendering
    // -------------------------------------------------------------------------

    @Test
    void buildParams_legacyFlatPrompt_emitsPlainStringSystem() throws Exception {
        AnthropicBatchProviderImpl provider = newProvider(true);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("systemPrompt", "You are a gap analyst.");
        payload.put("messages", List.of(Map.of("role", "user", "content", "Find gaps.")));
        payload.put("maxTokens", 8192);

        Map<String, Object> params = provider.buildParams(jobWithPayload("claude-sonnet-4-6", payload));
        assertEquals("You are a gap analyst.", params.get("system"));
        assertEquals("claude-sonnet-4-6", params.get("model"));
        assertEquals(8192, params.get("max_tokens"));
    }

    @Test
    void buildParams_noSystem_omitsSystemKey() throws Exception {
        AnthropicBatchProviderImpl provider = newProvider(true);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("messages", List.of(Map.of("role", "user", "content", "hi")));
        payload.put("maxTokens", 1024);
        Map<String, Object> params = provider.buildParams(jobWithPayload("claude-sonnet-4-6", payload));
        assertFalse(params.containsKey("system"), "blank system must be omitted, not empty");
    }

    @Test
    @SuppressWarnings("unchecked")
    void buildParams_structuredPrompt_cachingOn_emitsSystemArrayWithCacheControl() throws Exception {
        AnthropicBatchProviderImpl provider = newProvider(true);

        Map<String, Object> sp = new LinkedHashMap<>();
        sp.put("systemBlocks", List.of("Stable rules.", "DBQ rubric."));
        sp.put("cachedCorpus", "ATOM CORPUS");

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("structuredPrompt", sp);
        payload.put("messages", List.of(Map.of("role", "user", "content", "Gap #1.")));
        payload.put("maxTokens", 4096);

        Map<String, Object> params = provider.buildParams(jobWithPayload("claude-sonnet-4-6", payload));
        Object system = params.get("system");
        assertTrue(system instanceof List, "cache-aware system must be the array form");
        List<Map<String, Object>> blocks = (List<Map<String, Object>>) system;
        assertEquals(3, blocks.size());
        assertEquals("Stable rules.", blocks.get(0).get("text"));
        assertFalse(blocks.get(0).containsKey("cache_control"));
        assertEquals("ATOM CORPUS", blocks.get(2).get("text"));
        Map<String, Object> cc = (Map<String, Object>) blocks.get(2).get("cache_control");
        assertNotNull(cc);
        assertEquals("ephemeral", cc.get("type"));
        long breakpoints = blocks.stream().filter(b -> b.containsKey("cache_control")).count();
        assertEquals(1, breakpoints, "exactly one breakpoint");
    }

    @Test
    void buildParams_structuredPrompt_flagOff_isFlatStringByteParity() throws Exception {
        AnthropicBatchProviderImpl provider = newProvider(false);

        Map<String, Object> sp = new LinkedHashMap<>();
        sp.put("systemBlocks", List.of("Stable rules.", "DBQ rubric."));
        sp.put("cachedCorpus", "ATOM CORPUS");

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("structuredPrompt", sp);
        payload.put("messages", List.of(Map.of("role", "user", "content", "x")));
        payload.put("maxTokens", 4096);

        Map<String, Object> params = provider.buildParams(jobWithPayload("claude-sonnet-4-6", payload));
        // Flag off → flat string, no array, no cache_control in the serialized body.
        assertEquals("Stable rules.\n\nDBQ rubric.\n\nATOM CORPUS", params.get("system"));
        String body = om.writeValueAsString(params);
        assertFalse(body.contains("cache_control"), "flag-off must never emit cache_control");
    }

    // -------------------------------------------------------------------------
    // usage parsing (cache tokens)
    // -------------------------------------------------------------------------

    @Test
    void parseMessage_capturesCacheReadAndWriteTokens() throws Exception {
        AnthropicBatchProviderImpl provider = newProvider(true);
        JsonNode message = om.readTree("""
                {
                  "model": "claude-sonnet-4-6",
                  "content": [{"type":"text","text":"ok"}],
                  "usage": {
                    "input_tokens": 1200,
                    "output_tokens": 300,
                    "cache_read_input_tokens": 60000,
                    "cache_creation_input_tokens": 0
                  }
                }
                """);
        LlmJobResult r = provider.parseMessage(message);
        assertEquals(1200, r.getInputTokens());
        assertEquals(300, r.getOutputTokens());
        assertEquals(60000, r.getCacheReadTokens());
        assertEquals(0, r.getCacheWriteTokens());
    }

    @Test
    void parseMessage_missingCacheFields_defaultZero() throws Exception {
        AnthropicBatchProviderImpl provider = newProvider(true);
        JsonNode message = om.readTree("""
                {"model":"claude-opus-4-8","content":[{"type":"text","text":"x"}],
                 "usage":{"input_tokens":10,"output_tokens":5}}
                """);
        LlmJobResult r = provider.parseMessage(message);
        assertEquals(0, r.getCacheReadTokens());
        assertEquals(0, r.getCacheWriteTokens());
    }
}
