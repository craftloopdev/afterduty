package com.afterduty.service.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;

/**
 * Renders the Anthropic Messages-API {@code system} field for the two Claude
 * providers ({@link AnthropicBatchProviderImpl} raw-JSON and
 * {@link VertexAnthropicProviderImpl} typed-SDK), keeping the byte shape
 * identical across both transports — only the carrier differs.
 *
 * <h2>Two shapes, one source of truth ({@code request_payload})</h2>
 * <ul>
 *   <li><b>Legacy / flag-off / no structured prompt</b> → the flat
 *       {@code systemPrompt} string. This is byte-for-byte today's request, so
 *       the cache flag being off (or a null {@code structuredPrompt}) emits
 *       exactly the shapes that ship today.</li>
 *   <li><b>Structured prompt, caching ON</b> → the <em>array</em> form the
 *       Messages API accepts for {@code system}:
 *       <pre>
 *       [ {type:"text", text:&lt;systemBlock_0&gt;},
 *         ...,
 *         {type:"text", text:&lt;cachedCorpus&gt;, cache_control:{type:"ephemeral"}} ]
 *       </pre>
 *       The single {@code cache_control} breakpoint sits on the corpus block
 *       (the atom corpus / KB rubric) so reads bill at 0.1× and the volatile
 *       per-request tail — which rides {@code messages}/{@code userMessage}
 *       <em>after</em> the system field — never invalidates the cached prefix.
 *       At most one breakpoint is emitted here (well inside the API's max of 4).</li>
 * </ul>
 *
 * <p>When a structured prompt is present but the flag is <b>off</b>, the blocks
 * are flattened back into a single plain string (system blocks then corpus,
 * blank-line joined) so flag-off output carries no {@code cache_control} and no
 * array wrapper — preserving today's exact bytes for the no-cache lane while
 * still sending the same text. A structured prompt with no {@code cachedCorpus}
 * and caching on still renders the array form (stable system blocks, no
 * breakpoint) so the shape is honest about intent; with a single block it is
 * indistinguishable in effect from the flat string.
 */
final class ClaudeSystemRenderer {

    private ClaudeSystemRenderer() {}

    /**
     * Resolves the structured prompt node out of a stored {@code request_payload}.
     * Returns null when absent (legacy flat path).
     */
    static JsonNode structuredPromptNode(JsonNode payload) {
        JsonNode sp = payload.path("structuredPrompt");
        return (sp.isObject() && !sp.isEmpty()) ? sp : null;
    }

    /**
     * True if this payload would render a {@code cache_control} breakpoint given
     * the flag — i.e. caching is on, a structured prompt exists, and it carries a
     * non-blank cached corpus. Drives the tripwire's "we asked for a cache" gate.
     */
    static boolean emitsCacheBreakpoint(JsonNode payload, boolean cachingEnabled) {
        if (!cachingEnabled) return false;
        JsonNode sp = structuredPromptNode(payload);
        if (sp == null) return false;
        String corpus = sp.path("cachedCorpus").asText("");
        return !corpus.isBlank();
    }

    /** The cached-corpus text for this payload, or "" when there is none. */
    static String cachedCorpusText(JsonNode payload) {
        JsonNode sp = structuredPromptNode(payload);
        if (sp == null) return "";
        return sp.path("cachedCorpus").asText("");
    }

    /**
     * A stable identifier for the cacheable prefix this request would emit, or
     * {@code null} when it emits no {@code cache_control} breakpoint (flag off, no
     * structured prompt, or no corpus). The Anthropic cache is a prefix match over
     * the rendered {@code system} bytes up to and including the breakpoint, so the
     * key is the SHA-256 of exactly those bytes: the stable system blocks plus the
     * cached corpus, joined the same way the array form renders them. Two requests
     * with byte-equal cached prefixes share a key; a single byte difference (a leaked
     * timestamp, a different corpus) yields a different key.
     *
     * <p>Used by {@link VertexAnthropicProviderImpl#fetchResults} to detect same-prefix
     * job groups so it can prime the cache with one job before fanning out the rest at
     * 0.1× reads (Mission 6b, requirement 4). Returning null (no breakpoint) means the
     * job is never grouped/primed — it runs in the normal parallel fan-out.
     */
    static String prefixCacheKey(JsonNode payload, boolean cachingEnabled) {
        if (!emitsCacheBreakpoint(payload, cachingEnabled)) return null;
        JsonNode sp = structuredPromptNode(payload);
        StringBuilder prefix = new StringBuilder();
        for (JsonNode b : sp.path("systemBlocks")) {
            String t = b.asText("");
            if (!t.isBlank()) prefix.append(t).append('\0');   // NUL separator: not in prompt text
        }
        prefix.append(sp.path("cachedCorpus").asText(""));
        return sha256(prefix.toString());
    }

    private static String sha256(String s) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(s.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) hex.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            return hex.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            // SHA-256 is guaranteed present on every JVM; fall back to identity-ish.
            return Integer.toHexString(s.hashCode());
        }
    }

    /**
     * The flat fallback system string for the legacy / flag-off path. When a
     * structured prompt is present it is flattened (system blocks then corpus,
     * blank-line joined); otherwise the plain {@code systemPrompt} is returned.
     */
    static String flatSystem(JsonNode payload) {
        JsonNode sp = structuredPromptNode(payload);
        if (sp == null) {
            return payload.path("systemPrompt").asText("");
        }
        List<String> parts = new ArrayList<>();
        for (JsonNode b : sp.path("systemBlocks")) {
            String t = b.asText("");
            if (!t.isBlank()) parts.add(t);
        }
        String corpus = sp.path("cachedCorpus").asText("");
        if (!corpus.isBlank()) parts.add(corpus);
        return String.join("\n\n", parts);
    }

    /**
     * Renders the {@code system} value for a Claude request as a Jackson node.
     *
     * <p>Returns:
     * <ul>
     *   <li>{@code null} when there is no system content at all (caller omits the
     *       {@code system} field entirely — same as today's "blank system" path);</li>
     *   <li>a textual node (plain string) for the legacy / flag-off path;</li>
     *   <li>an array node (the cache-aware shape) when caching is on and a
     *       structured prompt is present.</li>
     * </ul>
     *
     * @param payload          the stored {@code request_payload}
     * @param cachingEnabled   the {@code va-claim.llm.prompt-caching} flag
     * @param nodeFactory      a Jackson node factory (provider's own ObjectMapper)
     */
    static JsonNode renderSystem(JsonNode payload, boolean cachingEnabled,
                                 com.fasterxml.jackson.databind.ObjectMapper nodeFactory) {
        JsonNode sp = structuredPromptNode(payload);

        // No structured prompt, or caching disabled: emit today's exact bytes —
        // the flat system string (or omit it when blank).
        if (sp == null || !cachingEnabled) {
            String flat = flatSystem(payload);
            return flat.isBlank() ? null : nodeFactory.getNodeFactory().textNode(flat);
        }

        // Caching on + structured prompt: the Messages-API system array form.
        ArrayNode arr = nodeFactory.createArrayNode();
        for (JsonNode b : sp.path("systemBlocks")) {
            String t = b.asText("");
            if (t.isBlank()) continue;
            ObjectNode block = arr.addObject();
            block.put("type", "text");
            block.put("text", t);
        }
        String corpus = sp.path("cachedCorpus").asText("");
        if (!corpus.isBlank()) {
            ObjectNode block = arr.addObject();
            block.put("type", "text");
            block.put("text", corpus);
            ObjectNode cc = block.putObject("cache_control");
            cc.put("type", "ephemeral");
        }
        // Defensive: a structured prompt with neither blocks nor corpus collapses
        // to "no system" rather than an empty array (which the API rejects).
        return arr.isEmpty() ? null : arr;
    }
}
