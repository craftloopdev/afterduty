package com.afterduty.service.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit coverage for {@link ClaudeSystemRenderer} — the single source of truth for
 * the Claude {@code system} field shape across both providers (raw-JSON batch and
 * typed-SDK Vertex). Verifies:
 * <ul>
 *   <li>legacy flat-system byte-parity (no structured prompt);</li>
 *   <li>flag-off byte-parity even WITH a structured prompt (no array, no cache_control);</li>
 *   <li>the cache-aware array form with exactly one cache_control breakpoint on the corpus;</li>
 *   <li>breakpoint-presence detection and corpus extraction used by the tripwire.</li>
 * </ul>
 */
@Tag("regression")
class ClaudeSystemRendererTest {

    private final ObjectMapper om = new ObjectMapper();

    private JsonNode payload(Map<String, Object> m) {
        return om.valueToTree(m);
    }

    private Map<String, Object> structured(List<String> systemBlocks, String corpus) {
        Map<String, Object> sp = new LinkedHashMap<>();
        sp.put("systemBlocks", systemBlocks);
        if (corpus != null) sp.put("cachedCorpus", corpus);
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("structuredPrompt", sp);
        return p;
    }

    // -------------------------------------------------------------------------
    // Legacy flat path — byte-identical to today
    // -------------------------------------------------------------------------

    @Test
    void flatSystem_whenNoStructuredPrompt_returnsPlainStringNode() {
        JsonNode p = payload(Map.of("systemPrompt", "You are a verifier."));
        JsonNode sys = ClaudeSystemRenderer.renderSystem(p, true, om);
        assertNotNull(sys);
        assertTrue(sys.isTextual(), "flat path must be a plain string, not an array");
        assertEquals("You are a verifier.", sys.asText());
    }

    @Test
    void blankSystem_rendersNull_soFieldIsOmitted() {
        JsonNode p = payload(Map.of("systemPrompt", ""));
        assertNull(ClaudeSystemRenderer.renderSystem(p, true, om));
        assertNull(ClaudeSystemRenderer.renderSystem(payload(Map.of()), true, om));
    }

    // -------------------------------------------------------------------------
    // Flag OFF — structured prompt present but caching disabled => flat string
    // -------------------------------------------------------------------------

    @Test
    void flagOff_withStructuredPrompt_flattensToPlainStringNoCacheControl() {
        JsonNode p = payload(structured(List.of("Block A", "Block B"), "BIG CORPUS"));
        JsonNode sys = ClaudeSystemRenderer.renderSystem(p, /*cachingEnabled*/ false, om);
        assertNotNull(sys);
        assertTrue(sys.isTextual(), "flag-off must collapse to a plain string");
        assertEquals("Block A\n\nBlock B\n\nBIG CORPUS", sys.asText());
        assertFalse(sys.toString().contains("cache_control"),
                "flag-off must never emit cache_control");
    }

    @Test
    void emitsCacheBreakpoint_falseWhenFlagOff() {
        JsonNode p = payload(structured(List.of("sys"), "corpus"));
        assertFalse(ClaudeSystemRenderer.emitsCacheBreakpoint(p, false));
        assertTrue(ClaudeSystemRenderer.emitsCacheBreakpoint(p, true));
    }

    // -------------------------------------------------------------------------
    // Cache-aware array form
    // -------------------------------------------------------------------------

    @Test
    void cachingOn_structuredPrompt_rendersArrayWithOneBreakpointOnCorpus() {
        JsonNode p = payload(structured(List.of("Stable system rules.", "VASRD slice."), "ATOM CORPUS"));
        JsonNode sys = ClaudeSystemRenderer.renderSystem(p, true, om);
        assertNotNull(sys);
        assertTrue(sys.isArray(), "cache-aware path must be the system array form");
        assertEquals(3, sys.size());

        // System blocks first, plain text, no cache_control.
        assertEquals("text", sys.get(0).path("type").asText());
        assertEquals("Stable system rules.", sys.get(0).path("text").asText());
        assertTrue(sys.get(0).path("cache_control").isMissingNode());
        assertEquals("VASRD slice.", sys.get(1).path("text").asText());
        assertTrue(sys.get(1).path("cache_control").isMissingNode());

        // Corpus last, carrying the single ephemeral breakpoint.
        assertEquals("ATOM CORPUS", sys.get(2).path("text").asText());
        assertEquals("ephemeral", sys.get(2).path("cache_control").path("type").asText());

        // Exactly ONE breakpoint across the whole array.
        long breakpoints = countCacheControl(sys);
        assertEquals(1, breakpoints, "exactly one cache_control breakpoint expected");
    }

    @Test
    void cachingOn_structuredPromptWithNoCorpus_rendersArrayWithoutBreakpoint() {
        JsonNode p = payload(structured(List.of("Only stable blocks."), null));
        JsonNode sys = ClaudeSystemRenderer.renderSystem(p, true, om);
        assertNotNull(sys);
        assertTrue(sys.isArray());
        assertEquals(1, sys.size());
        assertEquals(0, countCacheControl(sys), "no corpus => no breakpoint");
        assertFalse(ClaudeSystemRenderer.emitsCacheBreakpoint(p, true));
    }

    @Test
    void cachedCorpusText_extractsCorpusForTripwire() {
        JsonNode p = payload(structured(List.of("sys"), "THE CORPUS"));
        assertEquals("THE CORPUS", ClaudeSystemRenderer.cachedCorpusText(p));
        assertEquals("", ClaudeSystemRenderer.cachedCorpusText(payload(Map.of("systemPrompt", "x"))));
    }

    @Test
    void emptyStructuredPrompt_collapsesToNull_notEmptyArray() {
        // Neither blocks nor corpus: the API rejects an empty system array, so we
        // must render null (field omitted) instead.
        JsonNode p = payload(structured(List.of(), null));
        assertNull(ClaudeSystemRenderer.renderSystem(p, true, om));
    }

    // -------------------------------------------------------------------------
    // prefixCacheKey collision regression (review minor #3): block boundaries
    // must contribute to the key — ["A B","C"] and ["A","B C"] flatten to the
    // same text but render DIFFERENT system arrays on the wire, so they must
    // never share a prefix-cache key (a collision would cross-prime distinct
    // prefixes and the tail jobs would pay full writes while we believed they
    // were 0.1x reads).
    // -------------------------------------------------------------------------

    @org.junit.jupiter.api.Test
    void prefixCacheKey_blockBoundariesAreCollisionSafe() {
        JsonNode a = payload(structured(java.util.List.of("A B", "C"), "D"));
        JsonNode b = payload(structured(java.util.List.of("A", "B C"), "D"));
        String keyA = ClaudeSystemRenderer.prefixCacheKey(a, true);
        String keyB = ClaudeSystemRenderer.prefixCacheKey(b, true);
        assertNotNull(keyA);
        assertNotNull(keyB);
        assertNotEquals(keyA, keyB,
                "different block boundaries render different wire bytes and must hash differently");
        // Identical structure → identical key (the property priming depends on).
        assertEquals(keyA, ClaudeSystemRenderer.prefixCacheKey(
                payload(structured(java.util.List.of("A B", "C"), "D")), true));
        // Flag-off → no key, never grouped.
        assertNull(ClaudeSystemRenderer.prefixCacheKey(a, false));
    }

    private static long countCacheControl(JsonNode array) {
        long n = 0;
        for (JsonNode block : array) {
            if (!block.path("cache_control").isMissingNode()) n++;
        }
        return n;
    }
}
