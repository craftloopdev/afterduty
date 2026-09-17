package com.afterduty.service.llm;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.afterduty.model.LlmJob;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Coverage for {@link CacheInvalidationTripwire} (Mission 6a requirement 5):
 * the silent-cache-invalidator WARN fires only on the realtime lane when a
 * cache_control breakpoint was sent over a cacheable-sized corpus and nothing
 * cached — and stays quiet for small prefixes, batch first-writers, healthy
 * cache hits, flag-off, and no-breakpoint requests; rate-limited per purpose.
 */
@Tag("regression")
class CacheInvalidationTripwireTest {

    private final ObjectMapper om = new ObjectMapper();
    private CacheInvalidationTripwire tripwire;
    private ListAppender<ILoggingEvent> appender;
    private Logger tripwireLogger;

    @BeforeEach
    void setUp() throws Exception {
        tripwire = new CacheInvalidationTripwire();
        setField(tripwire, "promptCachingEnabled", true);
        setField(tripwire, "windowMs", 300_000L);

        tripwireLogger = (Logger) LoggerFactory.getLogger(CacheInvalidationTripwire.class);
        appender = new ListAppender<>();
        appender.start();
        tripwireLogger.addAppender(appender);
        tripwireLogger.setLevel(Level.WARN);
    }

    @AfterEach
    void tearDown() {
        tripwireLogger.detachAppender(appender);
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field f = CacheInvalidationTripwire.class.getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }

    /** A big enough corpus to clear the 2048-token (Sonnet) min cacheable size at ~4 chars/token. */
    private static String bigCorpus() {
        return "x".repeat(2048 * 4 + 100);     // ~2073 est tokens > 2048
    }

    private LlmJob job(String purpose, String model, String corpus) throws Exception {
        Map<String, Object> sp = new LinkedHashMap<>();
        sp.put("systemBlocks", List.of("Stable rules."));
        if (corpus != null) sp.put("cachedCorpus", corpus);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("structuredPrompt", sp);
        payload.put("messages", List.of(Map.of("role", "user", "content", "q")));
        payload.put("maxTokens", 4096);
        return LlmJob.builder()
                .id(UUID.randomUUID())
                .provider(VertexAnthropicProvider.NAME)
                .modelName(model)
                .purpose(purpose)
                .status(LlmJob.Status.SUCCEEDED)
                .requestPayload(om.writeValueAsString(payload))
                .build();
    }

    private LlmJob flatJob(String purpose) throws Exception {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("systemPrompt", "You are a verifier.");
        payload.put("messages", List.of(Map.of("role", "user", "content", "q")));
        payload.put("maxTokens", 4096);
        return LlmJob.builder().id(UUID.randomUUID()).provider(VertexAnthropicProvider.NAME)
                .modelName("claude-sonnet-4-6").purpose(purpose).status(LlmJob.Status.SUCCEEDED)
                .requestPayload(om.writeValueAsString(payload)).build();
    }

    private LlmJobResult result(long cacheRead, long cacheWrite) {
        return new LlmJobResult("ok", null, 1000, 200, 0, cacheRead, cacheWrite,
                VertexAnthropicProvider.NAME, "claude-sonnet-4-6");
    }

    private long warnCount() {
        return appender.list.stream().filter(e -> e.getLevel() == Level.WARN).count();
    }

    private String lastWarn() {
        return appender.list.stream().filter(e -> e.getLevel() == Level.WARN)
                .reduce((a, b) -> b).map(ILoggingEvent::getFormattedMessage).orElse("");
    }

    // -------------------------------------------------------------------------
    // FIRES
    // -------------------------------------------------------------------------

    @Test
    void fires_whenBreakpointSentOverBigCorpusButNothingCached_realtime() throws Exception {
        tripwire.check(job("gap_evidence", "claude-sonnet-4-6", bigCorpus()), result(0, 0), /*isBatch*/ false);
        assertThat(warnCount()).isEqualTo(1);
        assertThat(lastWarn()).contains("CACHE TRIPWIRE").contains("gap_evidence");
    }

    // -------------------------------------------------------------------------
    // STAYS QUIET
    // -------------------------------------------------------------------------

    @Test
    void quiet_whenCacheRead() throws Exception {
        tripwire.check(job("gap_evidence", "claude-sonnet-4-6", bigCorpus()), result(50_000, 0), false);
        assertThat(warnCount()).isZero();
    }

    @Test
    void quiet_whenCacheWritten_firstHealthyWrite() throws Exception {
        tripwire.check(job("gap_evidence", "claude-sonnet-4-6", bigCorpus()), result(0, 50_000), false);
        assertThat(warnCount()).isZero();
    }

    @Test
    void quiet_whenCorpusBelowMinCacheableSize() throws Exception {
        // Tiny corpus → legitimately uncached, never warn.
        tripwire.check(job("gap_evidence", "claude-sonnet-4-6", "small corpus"), result(0, 0), false);
        assertThat(warnCount()).isZero();
    }

    @Test
    void quiet_onBatchLane_bestEffortFirstWriter() throws Exception {
        // Batch caching is best-effort; a cold batch with 0/0 must not spam.
        tripwire.check(job("gap_evidence", "claude-sonnet-4-6", bigCorpus()), result(0, 0), /*isBatch*/ true);
        assertThat(warnCount()).isZero();
    }

    @Test
    void quiet_whenFlagOff() throws Exception {
        setField(tripwire, "promptCachingEnabled", false);
        tripwire.check(job("gap_evidence", "claude-sonnet-4-6", bigCorpus()), result(0, 0), false);
        assertThat(warnCount()).isZero();
    }

    @Test
    void quiet_whenNoBreakpointSent_flatPrompt() throws Exception {
        tripwire.check(flatJob("synthesis_verify"), result(0, 0), false);
        assertThat(warnCount()).isZero();
    }

    @Test
    void quiet_whenStructuredButNoCorpus() throws Exception {
        tripwire.check(job("gap_evidence", "claude-sonnet-4-6", null), result(0, 0), false);
        assertThat(warnCount()).isZero();
    }

    @Test
    void opusMinCacheableIsHigher_corpusBetweenThresholds_quietForOpus() throws Exception {
        // ~3000 est tokens: above Sonnet's 2048 but below Opus's 4096.
        String mid = "x".repeat(3000 * 4);
        tripwire.check(job("synthesis_verify", "claude-opus-4-8", mid), result(0, 0), false);
        assertThat(warnCount()).isZero();   // below Opus min cacheable → no warn
        // Same corpus on Sonnet (lower threshold) DOES fire.
        tripwire.check(job("synthesis_identify", "claude-sonnet-4-6", mid), result(0, 0), false);
        assertThat(warnCount()).isEqualTo(1);
    }

    // -------------------------------------------------------------------------
    // RATE LIMITING
    // -------------------------------------------------------------------------

    @Test
    void rateLimited_burstOfSamePurpose_emitsOneWarn() throws Exception {
        for (int i = 0; i < 12; i++) {
            tripwire.check(job("synthesis_rate", "claude-sonnet-4-6", bigCorpus()), result(0, 0), false);
        }
        assertThat(warnCount()).isEqualTo(1);
    }

    @Test
    void rateLimit_isPerPurpose() throws Exception {
        tripwire.check(job("gap_evidence", "claude-sonnet-4-6", bigCorpus()), result(0, 0), false);
        tripwire.check(job("synthesis_identify", "claude-sonnet-4-6", bigCorpus()), result(0, 0), false);
        assertThat(warnCount()).isEqualTo(2);   // different purposes, both warn
    }

    @Test
    void suppressedCount_isFoldedIntoNextWindow() throws Exception {
        // Window 0 → every call past the first is a new window, so it re-warns and
        // folds the prior suppressed count. Use a tiny window to exercise the fold.
        setField(tripwire, "windowMs", 0L);
        tripwire.check(job("synthesis_rate", "claude-sonnet-4-6", bigCorpus()), result(0, 0), false);
        tripwire.check(job("synthesis_rate", "claude-sonnet-4-6", bigCorpus()), result(0, 0), false);
        assertThat(warnCount()).isEqualTo(2);
    }

    @Test
    void neverThrows_onCorruptPayload() {
        LlmJob bad = LlmJob.builder().id(UUID.randomUUID()).provider(VertexAnthropicProvider.NAME)
                .modelName("claude-sonnet-4-6").purpose("x").status(LlmJob.Status.SUCCEEDED)
                .requestPayload("{not json").build();
        tripwire.check(bad, result(0, 0), false);   // must not throw
        assertThat(warnCount()).isZero();
    }
}
