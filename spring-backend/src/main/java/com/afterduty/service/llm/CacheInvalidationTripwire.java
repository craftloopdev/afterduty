package com.afterduty.service.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Silent-cache-invalidator tripwire (Increment 6 / Mission 6a, requirement 5).
 *
 * <p>When a Claude request carried a {@code cache_control} breakpoint on its
 * corpus block but the response reports <b>both</b>
 * {@code cache_read_input_tokens == 0} and {@code cache_creation_input_tokens == 0},
 * <em>and</em> that corpus was plausibly above the model's minimum cacheable
 * prefix, something silently invalidated the prefix (a timestamp/UUID leaked in,
 * unsorted JSON, a tool swap) — the 0.1× read lever is being paid for and not
 * delivered. We emit a single rate-limited WARN naming the purpose.
 *
 * <h2>What we deliberately do NOT warn on</h2>
 * <ul>
 *   <li><b>Caching flag off / no breakpoint sent.</b> Nothing was requested, so a
 *       zero-cache response is correct, not a signal.</li>
 *   <li><b>Small prefixes.</b> Below the model family's minimum cacheable size
 *       (~2048 tokens Sonnet-class, ~4096 Opus/Haiku-class; estimated at ~4
 *       chars/token from the corpus length), the API legitimately doesn't cache —
 *       warning here would be pure noise. This is the dominant false-positive
 *       guard.</li>
 *   <li><b>Batch-lane first-writers / best-effort batch.</b> On the anthropic-batch
 *       direct lane caching is best-effort within a batch; a first writer in a
 *       batch can legitimately get no cache hit and no durable write. We never
 *       warn on the batch lane so a normal cold batch doesn't spam.</li>
 * </ul>
 *
 * <h2>Rate limiting</h2>
 * One WARN per purpose per window ({@code va-claim.llm.cache-tripwire.window-ms},
 * default 5 min) so a fan-out burst (12 rate calls, 24 gap calls) that all miss
 * the cache produces one line, not 36. A suppressed-count is folded into the next
 * emitted line for that purpose.
 */
@Component
public class CacheInvalidationTripwire {

    private static final Logger log = LoggerFactory.getLogger(CacheInvalidationTripwire.class);

    /** ~4 chars/token is the rule-of-thumb the design uses for cacheable-size estimates. */
    private static final int CHARS_PER_TOKEN = 4;

    private final ObjectMapper objectMapper = new ObjectMapper();

    /** Master flag — when caching is off no breakpoints are sent, so the tripwire is inert. */
    @Value("${va-claim.llm.prompt-caching:true}")
    private boolean promptCachingEnabled;

    /** Per-purpose WARN rate-limit window. */
    @Value("${va-claim.llm.cache-tripwire.window-ms:300000}")
    private long windowMs;

    /** Last-warned epoch-ms and suppressed-count per purpose, for rate limiting. */
    private final ConcurrentHashMap<String, AtomicLong> lastWarnAt = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicLong> suppressed = new ConcurrentHashMap<>();

    /**
     * Checks one completed Claude call. No-op for Gemini, flag-off, the batch lane,
     * requests that carried no breakpoint, and small prefixes. Never throws —
     * metering/observability must not break the pipeline.
     */
    public void check(com.afterduty.model.LlmJob job, LlmJobResult result, boolean isBatch) {
        try {
            if (!promptCachingEnabled) return;        // nothing was cached on purpose
            if (isBatch) return;                       // batch caching is best-effort — never warn
            if (result == null) return;
            // Explicit null guard BEFORE we read the payload. A null job, a null
            // request_payload, or a null model would otherwise reach the parse/branch
            // logic below and — because the whole method is wrapped in catch(Exception)
            // — be silently swallowed into the debug branch, disabling the tripwire with
            // no signal. (readTree(null) is also Jackson-config-dependent: MissingNode on
            // some overloads, NPE on others.) Treat "no payload to inspect" as "nothing
            // to check", not as a silent miss: there is no breakpoint to have invalidated.
            if (job == null || job.getRequestPayload() == null || job.getRequestPayload().isBlank()
                    || job.getModelName() == null) {
                return;
            }

            // Only Claude requests carry cache_control. The renderer is the single
            // source of truth for "did this request emit a breakpoint?".
            JsonNode payload = objectMapper.readTree(job.getRequestPayload());
            if (!ClaudeSystemRenderer.emitsCacheBreakpoint(payload, true)) return;

            // The cache landed (read OR write > 0) → healthy, nothing to flag.
            if (result.getCacheReadTokens() > 0 || result.getCacheWriteTokens() > 0) return;

            // Below the model's min cacheable prefix the API silently doesn't cache —
            // that is expected, not a silent invalidation. Skip.
            String corpus = ClaudeSystemRenderer.cachedCorpusText(payload);
            int estTokens = corpus.length() / CHARS_PER_TOKEN;
            int minCacheable = minCacheableTokens(job.getModelName());
            if (estTokens < minCacheable) return;

            // Genuine silent-invalidator signal: we sent a breakpoint over a
            // cacheable-sized corpus on the realtime lane, and nothing cached.
            maybeWarn(job, estTokens, minCacheable);
        } catch (Exception e) {
            // Best-effort observability; swallow.
            log.debug("Cache tripwire check skipped for job {}: {}", safeId(job), e.getMessage());
        }
    }

    private void maybeWarn(com.afterduty.model.LlmJob job, int estTokens, int minCacheable) {
        String purpose = job.getPurpose() == null ? "unknown" : job.getPurpose();
        long now = System.currentTimeMillis();
        AtomicLong last = lastWarnAt.computeIfAbsent(purpose, k -> new AtomicLong(0L));
        long prev = last.get();
        if (now - prev < windowMs) {
            suppressed.computeIfAbsent(purpose, k -> new AtomicLong(0L)).incrementAndGet();
            return;
        }
        if (!last.compareAndSet(prev, now)) {
            // Lost the race to another thread that just warned for this purpose.
            suppressed.computeIfAbsent(purpose, k -> new AtomicLong(0L)).incrementAndGet();
            return;
        }
        long folded = suppressed.computeIfAbsent(purpose, k -> new AtomicLong(0L)).getAndSet(0L);
        log.warn("CACHE TRIPWIRE: purpose={} model={} sent a cache_control block over ~{} tok "
                        + "(>= {} min cacheable) but the response cached 0 read / 0 write — likely a "
                        + "silent prefix invalidator (timestamp/UUID/unsorted JSON/tool swap).{}",
                purpose, job.getModelName(), estTokens, minCacheable,
                folded > 0 ? " (" + folded + " similar suppressed in the last window)" : "");
    }

    /**
     * Minimum cacheable prefix in tokens by model family (research §5):
     * ~4096 for Opus/Haiku-class, ~2048 for Sonnet-class. Default to the larger
     * (more conservative — fewer false positives) for unknown models.
     */
    static int minCacheableTokens(String modelName) {
        if (modelName == null) return 4096;
        String m = modelName.toLowerCase();
        if (m.contains("sonnet")) return 2048;
        if (m.contains("opus") || m.contains("haiku") || m.contains("fable")) return 4096;
        return 4096;
    }

    private static String safeId(com.afterduty.model.LlmJob job) {
        return job == null || job.getId() == null ? "?" : job.getId().toString();
    }
}
