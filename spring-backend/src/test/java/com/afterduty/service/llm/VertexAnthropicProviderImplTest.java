package com.afterduty.service.llm;

import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.MessageCreateParams;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.afterduty.model.LlmJob;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit test for {@link VertexAnthropicProviderImpl}'s request mapping. Exercises the
 * {@code buildParams} helper directly (no network, no SDK transport, no ADC) and asserts the
 * Anthropic Messages request shape derived from a stored {@code request_payload}:
 * model, max_tokens, system, messages, tools, and the thinking/effort mapping.
 *
 * <p>This is the request-shape coverage required by the increment: the SDK's typed
 * {@link MessageCreateParams} is built but never sent, so the test is fully offline and tagged
 * regression so it runs in the pre-prod suite.
 */
@Tag("regression")
class VertexAnthropicProviderImplTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    private VertexAnthropicProviderImpl newProvider(String defaultModel) throws Exception {
        return newProvider(defaultModel, true);
    }

    private VertexAnthropicProviderImpl newProvider(String defaultModel, boolean promptCaching) throws Exception {
        VertexAnthropicProviderImpl p = new VertexAnthropicProviderImpl();
        setField(p, "defaultModelName", defaultModel);
        setField(p, "region", "global");
        setField(p, "projectId", "test-project");
        setField(p, "promptCachingEnabled", promptCaching);
        return p;
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field f = VertexAnthropicProviderImpl.class.getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }

    /** Builds a QUEUED LlmJob carrying the same request_payload shape LlmJobService serializes. */
    private LlmJob jobWithPayload(String model, Map<String, Object> payload) throws Exception {
        return LlmJob.builder()
                .id(UUID.randomUUID())
                .provider(VertexAnthropicProvider.NAME)
                .modelName(model)
                .purpose("synthesis_verify")
                .status(LlmJob.Status.QUEUED)
                .requestPayload(objectMapper.writeValueAsString(payload))
                .build();
    }

    @Test
    void buildParams_mapsModelMaxTokensSystemAndMessages() throws Exception {
        VertexAnthropicProviderImpl provider = newProvider("claude-sonnet-4-6");

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("systemPrompt", "You are a VA rating verifier.");
        payload.put("messages", List.of(Map.of("role", "user", "content", "Verify condition #1.")));
        payload.put("maxTokens", 8192);
        payload.put("thinkingBudget", 0);

        LlmJob job = jobWithPayload("claude-opus-4-8", payload);
        MessageCreateParams params = provider.buildParams(job);

        // model comes from the job, not the provider default
        assertEquals("claude-opus-4-8", params.model().asString());
        assertEquals(8192L, params.maxTokens());
        assertTrue(params.system().isPresent());
        assertEquals("You are a VA rating verifier.", params.system().get().asString());

        // messages passed through verbatim on the typed `messages` field (raw JSON preserved)
        List<JsonNode> messages = messagesAsJson(params);
        assertFalse(messages.isEmpty(), "messages must be set on the request");
        assertEquals("user", messages.get(0).path("role").asText());
        assertEquals("Verify condition #1.", messages.get(0).path("content").asText());

        // no thinking when budget is 0
        assertNull(bodyProp(params, "thinking"), "thinking must be absent when budget == 0");
        assertNull(bodyProp(params, "output_config"));
    }

    @Test
    void buildParams_usesProviderDefaultModel_whenJobModelBlank() throws Exception {
        VertexAnthropicProviderImpl provider = newProvider("claude-sonnet-4-6");

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("messages", List.of(Map.of("role", "user", "content", "hi")));
        payload.put("maxTokens", 1024);

        LlmJob job = jobWithPayload("", payload);   // blank model → provider default
        MessageCreateParams params = provider.buildParams(job);

        assertEquals("claude-sonnet-4-6", params.model().asString());
        assertEquals(1024L, params.maxTokens());
    }

    @Test
    void buildParams_mapsToolsAndThinkingEffort() throws Exception {
        VertexAnthropicProviderImpl provider = newProvider("claude-sonnet-4-6");

        Map<String, Object> tool = Map.of(
                "name", "vasrd_lookup",
                "description", "look up a diagnostic code",
                "input_schema", Map.of("type", "object"));

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("messages", List.of(Map.of("role", "user", "content", "rate it")));
        payload.put("tools", List.of(tool));
        payload.put("maxTokens", 4096);
        payload.put("thinkingBudget", 8000);   // medium tier (4k..12k)

        LlmJob job = jobWithPayload("claude-sonnet-4-6", payload);
        MessageCreateParams params = provider.buildParams(job);

        List<JsonNode> tools = toolsAsJson(params);
        assertFalse(tools.isEmpty(), "tools must be set on the request");
        assertEquals("vasrd_lookup", tools.get(0).path("name").asText());

        JsonNode thinking = bodyProp(params, "thinking");
        assertNotNull(thinking, "thinking must be present when budget > 0");
        assertEquals("adaptive", thinking.path("type").asText());

        JsonNode outputConfig = bodyProp(params, "output_config");
        assertNotNull(outputConfig);
        assertEquals("medium", outputConfig.path("effort").asText());
    }

    // -------------------------------------------------------------------------
    // Cache-aware structured prompt (Mission 6a) — system array shape + flag-off parity
    // -------------------------------------------------------------------------

    @Test
    void buildParams_structuredPrompt_cachingOn_rendersSystemArrayWithCacheControl() throws Exception {
        VertexAnthropicProviderImpl provider = newProvider("claude-sonnet-4-6", true);

        Map<String, Object> sp = new LinkedHashMap<>();
        sp.put("systemBlocks", List.of("Stable rules.", "VASRD slice."));
        sp.put("cachedCorpus", "ATOM CORPUS BLOCK");

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("structuredPrompt", sp);
        payload.put("messages", List.of(Map.of("role", "user", "content", "Rate condition #1.")));
        payload.put("maxTokens", 4096);
        payload.put("thinkingBudget", 0);

        LlmJob job = jobWithPayload("claude-sonnet-4-6", payload);
        MessageCreateParams params = provider.buildParams(job);

        // system is the typed System field carrying a raw array (not a string).
        List<JsonNode> blocks = systemAsArray(params);
        assertEquals(3, blocks.size());
        assertEquals("Stable rules.", blocks.get(0).path("text").asText());
        assertTrue(blocks.get(0).path("cache_control").isMissingNode());
        assertEquals("VASRD slice.", blocks.get(1).path("text").asText());
        // exactly one ephemeral breakpoint, on the corpus block (last)
        assertEquals("ATOM CORPUS BLOCK", blocks.get(2).path("text").asText());
        assertEquals("ephemeral", blocks.get(2).path("cache_control").path("type").asText());
        long breakpoints = blocks.stream().filter(b -> !b.path("cache_control").isMissingNode()).count();
        assertEquals(1, breakpoints);
    }

    @Test
    void buildParams_structuredPrompt_flagOff_isFlatStringByteParity() throws Exception {
        VertexAnthropicProviderImpl provider = newProvider("claude-sonnet-4-6", /*promptCaching*/ false);

        Map<String, Object> sp = new LinkedHashMap<>();
        sp.put("systemBlocks", List.of("Stable rules.", "VASRD slice."));
        sp.put("cachedCorpus", "ATOM CORPUS BLOCK");

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("structuredPrompt", sp);
        payload.put("messages", List.of(Map.of("role", "user", "content", "Rate it.")));
        payload.put("maxTokens", 4096);

        LlmJob job = jobWithPayload("claude-sonnet-4-6", payload);
        MessageCreateParams params = provider.buildParams(job);

        // Flag off → the system is a plain typed string (today's exact bytes), no
        // array wrapper, no cache_control anywhere.
        assertTrue(params.system().isPresent());
        assertEquals("Stable rules.\n\nVASRD slice.\n\nATOM CORPUS BLOCK",
                params.system().get().asString());
        assertFalse(params.toString().contains("cache_control"),
                "flag-off must never emit cache_control");
    }

    @Test
    void buildParams_legacyFlatPrompt_unchanged_isStringRegardlessOfFlag() throws Exception {
        for (boolean caching : new boolean[]{true, false}) {
            VertexAnthropicProviderImpl provider = newProvider("claude-sonnet-4-6", caching);
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("systemPrompt", "You are a VA rating verifier.");
            payload.put("messages", List.of(Map.of("role", "user", "content", "Verify #1.")));
            payload.put("maxTokens", 8192);

            LlmJob job = jobWithPayload("claude-opus-4-8", payload);
            MessageCreateParams params = provider.buildParams(job);

            assertTrue(params.system().isPresent(), "caching=" + caching);
            assertEquals("You are a VA rating verifier.", params.system().get().asString(),
                    "legacy flat prompt must be a plain string regardless of flag (caching=" + caching + ")");
        }
    }

    @Test
    void effortFor_bucketsBudgetCorrectly() {
        assertEquals("low", VertexAnthropicProviderImpl.effortFor(2_000));
        assertEquals("medium", VertexAnthropicProviderImpl.effortFor(8_000));
        assertEquals("high", VertexAnthropicProviderImpl.effortFor(20_000));
    }

    @Test
    void submit_isNonBlocking_andUsesJobIdAsProviderJobId() throws Exception {
        VertexAnthropicProviderImpl provider = newProvider("claude-sonnet-4-6");
        LlmJob job = jobWithPayload("claude-sonnet-4-6",
                Map.of("messages", List.of(Map.of("role", "user", "content", "x")), "maxTokens", 64));

        List<LlmJobHandle> handles = provider.submit(List.of(job));
        assertEquals(1, handles.size());
        assertEquals(job.getId(), handles.get(0).getInternalJobId());
        assertEquals(job.getId().toString(), handles.get(0).getProviderJobId());
    }

    @Test
    void isBatch_isFalse_realtimeProvider() throws Exception {
        assertFalse(newProvider("claude-sonnet-4-6").isBatch());
        assertEquals("vertex-anthropic", newProvider("claude-sonnet-4-6").providerName());
    }

    /** Reads an additional body property as a JsonNode for assertions; null if absent. */
    private JsonNode bodyProp(MessageCreateParams params, String name) {
        JsonValue v = params._additionalBodyProperties().get(name);
        return v == null ? null : v.convert(JsonNode.class);
    }

    /** Reads the typed `messages` field back to raw JSON (we fed it a raw JsonValue array). */
    private List<JsonNode> messagesAsJson(MessageCreateParams params) {
        return params._messages().asArray()
                .orElseThrow(() -> new AssertionError("messages field is not a raw array"))
                .stream().map(v -> v.convert(JsonNode.class)).toList();
    }

    /** Reads the typed `tools` field back to raw JSON. */
    private List<JsonNode> toolsAsJson(MessageCreateParams params) {
        return params._tools().asArray()
                .orElseThrow(() -> new AssertionError("tools field is not a raw array"))
                .stream().map(v -> v.convert(JsonNode.class)).toList();
    }

    /** Reads the typed `system` field back as a raw array (the cache-aware shape). */
    private List<JsonNode> systemAsArray(MessageCreateParams params) {
        return params._system().asArray()
                .orElseThrow(() -> new AssertionError("system field is not a raw array"))
                .stream().map(v -> v.convert(JsonNode.class)).toList();
    }

    // -------------------------------------------------------------------------
    // Realtime priming grouping (review CRITICAL): same-prefix jobs must SHARE a
    // providerJobId at submit() so the poller fetches them as ONE group and the
    // lead-then-fan priming in fetchResults actually engages. Unique-per-job ids
    // made priming dead code.
    // -------------------------------------------------------------------------

    private Map<String, Object> structuredPayload(String corpus, String volatileTail) {
        Map<String, Object> sp = new LinkedHashMap<>();
        sp.put("systemBlocks", List.of("Stable rules."));
        sp.put("cachedCorpus", corpus);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("structuredPrompt", sp);
        payload.put("messages", List.of(Map.of("role", "user", "content", volatileTail)));
        payload.put("maxTokens", 1024);
        return payload;
    }

    @Test
    void submit_groupsSamePrefixJobsUnderOneSharedProviderJobId() throws Exception {
        VertexAnthropicProviderImpl provider = newProvider("claude-sonnet-4-6", true);

        LlmJob x1 = jobWithPayload("claude-sonnet-4-6", structuredPayload("CORPUS X", "rate condition 1"));
        LlmJob x2 = jobWithPayload("claude-sonnet-4-6", structuredPayload("CORPUS X", "rate condition 2"));
        LlmJob x3 = jobWithPayload("claude-sonnet-4-6", structuredPayload("CORPUS X", "rate condition 3"));
        LlmJob y1 = jobWithPayload("claude-sonnet-4-6", structuredPayload("CORPUS Y", "gap condition 1"));
        LlmJob y2 = jobWithPayload("claude-sonnet-4-6", structuredPayload("CORPUS Y", "gap condition 2"));
        Map<String, Object> legacy = new LinkedHashMap<>();
        legacy.put("systemPrompt", "flat");
        legacy.put("messages", List.of(Map.of("role", "user", "content", "legacy")));
        LlmJob flat = jobWithPayload("claude-sonnet-4-6", legacy);

        List<LlmJobHandle> handles = provider.submit(List.of(x1, x2, x3, y1, y2, flat));
        Map<UUID, String> byJob = new HashMap<>();
        for (LlmJobHandle h : handles) byJob.put(h.getInternalJobId(), h.getProviderJobId());

        // Same prefix → one shared id (the group lead's UUID).
        assertEquals(byJob.get(x1.getId()), byJob.get(x2.getId()));
        assertEquals(byJob.get(x1.getId()), byJob.get(x3.getId()));
        assertEquals(x1.getId().toString(), byJob.get(x1.getId()),
                "shared id must be the lead's UUID so poll()'s UUID guard passes");
        assertEquals(byJob.get(y1.getId()), byJob.get(y2.getId()));
        // Different prefixes never share; legacy keeps its own UUID.
        assertNotEquals(byJob.get(x1.getId()), byJob.get(y1.getId()));
        assertEquals(flat.getId().toString(), byJob.get(flat.getId()));
        assertNotEquals(byJob.get(x1.getId()), byJob.get(flat.getId()));
    }

    @Test
    void submit_flagOff_everyJobKeepsItsOwnProviderJobId() throws Exception {
        VertexAnthropicProviderImpl provider = newProvider("claude-sonnet-4-6", /*promptCaching*/ false);

        LlmJob a = jobWithPayload("claude-sonnet-4-6", structuredPayload("CORPUS X", "one"));
        LlmJob b = jobWithPayload("claude-sonnet-4-6", structuredPayload("CORPUS X", "two"));

        List<LlmJobHandle> handles = provider.submit(List.of(a, b));
        Map<UUID, String> byJob = new HashMap<>();
        for (LlmJobHandle h : handles) byJob.put(h.getInternalJobId(), h.getProviderJobId());

        assertEquals(a.getId().toString(), byJob.get(a.getId()));
        assertEquals(b.getId().toString(), byJob.get(b.getId()));
        assertNotEquals(byJob.get(a.getId()), byJob.get(b.getId()),
                "flag-off must reproduce today's one-UUID-per-job submission exactly");
    }

}
