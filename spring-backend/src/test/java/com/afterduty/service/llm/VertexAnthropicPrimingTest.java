package com.afterduty.service.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.afterduty.model.LlmJob;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Mission 6b — REALTIME PRIMING coverage for {@link VertexAnthropicProviderImpl#fetchResults}.
 * When multiple jobs in one fetch share an identical cached prefix, one job must run to
 * completion FIRST (writing the cache) before the rest fan out in parallel (reading it at
 * 0.1×). Observed here via a test substrate: {@code runOne} is overridden to record start/
 * end ordering and to gate the tails on the lead's completion.
 */
@Tag("regression")
class VertexAnthropicPrimingTest {

    private final ObjectMapper om = new ObjectMapper();

    /** A job whose request_payload carries a structuredPrompt with the given corpus. */
    private LlmJob jobWithCorpus(String corpus) throws Exception {
        ObjectNode payload = om.createObjectNode();
        ObjectNode sp = payload.putObject("structuredPrompt");
        sp.putArray("systemBlocks").add("Stable system rules.");
        if (corpus != null) sp.put("cachedCorpus", corpus);
        payload.set("messages", om.valueToTree(
                List.of(Map.of("role", "user", "content", "rate it"))));
        payload.put("maxTokens", 1024);
        return LlmJob.builder()
                .id(UUID.randomUUID())
                .provider(VertexAnthropicProvider.NAME)
                .modelName("claude-sonnet-4-6")
                .purpose("synthesis_rate")
                .status(LlmJob.Status.QUEUED)
                .requestPayload(om.writeValueAsString(payload))
                .build();
    }

    /** A job with NO structured prompt (flat) — emits no breakpoint, never primed. */
    private LlmJob flatJob() throws Exception {
        ObjectNode payload = om.createObjectNode();
        payload.put("systemPrompt", "flat system");
        payload.set("messages", om.valueToTree(
                List.of(Map.of("role", "user", "content", "x"))));
        payload.put("maxTokens", 256);
        return LlmJob.builder()
                .id(UUID.randomUUID())
                .provider(VertexAnthropicProvider.NAME)
                .modelName("claude-sonnet-4-6")
                .purpose("synthesis_verify")
                .status(LlmJob.Status.QUEUED)
                .requestPayload(om.writeValueAsString(payload))
                .build();
    }

    /**
     * A provider whose {@code runOne} records START/END order and tracks the peak number
     * of concurrently in-flight calls, so the test can prove a same-prefix group's LEAD
     * ran ALONE first (peak == 1 while it ran) before the tails fanned out, whereas
     * un-primed jobs overlap (peak >= 2). Each call sleeps briefly so genuine parallelism
     * is observable.
     */
    private static final class RecordingProvider extends VertexAnthropicProviderImpl {
        private final ObjectMapper om = new ObjectMapper();
        final ConcurrentLinkedQueue<UUID> startOrder = new ConcurrentLinkedQueue<>();
        final ConcurrentLinkedQueue<UUID> endOrder = new ConcurrentLinkedQueue<>();
        final AtomicInteger inFlight = new AtomicInteger();
        final AtomicInteger peakInFlight = new AtomicInteger();
        final CountDownLatch firstStarted = new CountDownLatch(1);

        @Override
        CallOutcome runOne(LlmJob job) {
            startOrder.add(job.getId());
            firstStarted.countDown();
            int now = inFlight.incrementAndGet();
            peakInFlight.accumulateAndGet(now, Math::max);
            try { Thread.sleep(40); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
            inFlight.decrementAndGet();
            endOrder.add(job.getId());
            return CallOutcome.success(new LlmJobResult(
                    "ok", om.createObjectNode(), 10, 5, 0, 0, 0,
                    VertexAnthropicProvider.NAME, job.getModelName()));
        }
    }

    private RecordingProvider provider(boolean caching) throws Exception {
        RecordingProvider p = new RecordingProvider();
        for (String field : List.of("region", "projectId", "defaultModelName")) {
            Field f = VertexAnthropicProviderImpl.class.getDeclaredField(field);
            f.setAccessible(true);
            f.set(p, field.equals("region") ? "global"
                    : field.equals("projectId") ? "test-project" : "claude-sonnet-4-6");
        }
        Field caf = VertexAnthropicProviderImpl.class.getDeclaredField("promptCachingEnabled");
        caf.setAccessible(true);
        caf.set(p, caching);
        return p;
    }

    @Test
    void samePrefixGroup_runsOneLeadFirst_thenFansRestParallel() throws Exception {
        RecordingProvider p = provider(true);

        // Three jobs share the SAME corpus → same prefix hash → one group of 3.
        LlmJob a = jobWithCorpus("IDENTICAL ATOM CORPUS BLOCK");
        LlmJob b = jobWithCorpus("IDENTICAL ATOM CORPUS BLOCK");
        LlmJob c = jobWithCorpus("IDENTICAL ATOM CORPUS BLOCK");
        List<LlmJob> jobs = List.of(a, b, c);

        // Same-prefix sanity: all three resolve to one key.
        assertEquals(p.prefixCacheKeyFor(a), p.prefixCacheKeyFor(b));
        assertEquals(p.prefixCacheKeyFor(b), p.prefixCacheKeyFor(c));

        List<LlmAsyncProvider.FetchedResult> out = p.fetchResults("pid", jobs);

        assertEquals(3, out.size());
        out.forEach(r -> assertTrue(r.succeeded, "all jobs must succeed"));
        // Output order preserved (poller contract).
        assertEquals(a.getId(), out.get(0).internalJobId);
        assertEquals(c.getId(), out.get(2).internalJobId);

        // The lead ran ALONE first: it must START first and FINISH before any tail starts
        // (Phase 1 joins before Phase 2). So the first end == the first start, and only ONE
        // job had started by the time the lead finished.
        List<UUID> starts = new ArrayList<>(p.startOrder);
        List<UUID> ends = new ArrayList<>(p.endOrder);
        UUID lead = starts.get(0);
        assertEquals(lead, ends.get(0),
                "the priming lead must be the first to complete (Phase 1 joins before Phase 2)");
        // The two tails fan out in parallel after the lead → peak concurrency reaches 2,
        // but never 3 (the lead never overlapped the tails).
        assertEquals(2, p.peakInFlight.get(),
                "the lead runs alone, then the 2 tails fan out in parallel (peak == 2, never 3)");
    }

    @Test
    void differentPrefixes_notPrimed_runAllParallel() throws Exception {
        RecordingProvider p = provider(true);

        // Distinct corpora → distinct keys → no multi-member group → straight parallel.
        LlmJob a = jobWithCorpus("CORPUS ONE ......................................................");
        LlmJob b = jobWithCorpus("CORPUS TWO ......................................................");
        assertNotEquals(p.prefixCacheKeyFor(a), p.prefixCacheKeyFor(b));

        List<LlmAsyncProvider.FetchedResult> out = p.fetchResults("pid", List.of(a, b));
        assertEquals(2, out.size());
        // Both ran concurrently → peak in-flight reaches 2 (no staggering).
        assertEquals(2, p.peakInFlight.get(),
                "distinct-prefix jobs must not be staggered — they run fully parallel");
    }

    @Test
    void flagOff_noBreakpoint_runsAllParallel_noPriming() throws Exception {
        RecordingProvider p = provider(false);   // caching off → prefixCacheKey null for all

        LlmJob a = jobWithCorpus("IDENTICAL ATOM CORPUS BLOCK");
        LlmJob b = jobWithCorpus("IDENTICAL ATOM CORPUS BLOCK");
        assertNull(p.prefixCacheKeyFor(a), "flag-off emits no breakpoint, so no prefix key");

        List<LlmAsyncProvider.FetchedResult> out = p.fetchResults("pid", List.of(a, b));
        assertEquals(2, out.size());
        assertEquals(2, p.peakInFlight.get(),
                "with caching off there is no priming — jobs run fully parallel");
    }

    @Test
    void flatJobsAndOneGroup_groupPrimed_flatRunParallel() throws Exception {
        RecordingProvider p = provider(true);
        // A flat job (no breakpoint) plus a same-prefix pair. Only the pair is primed.
        LlmJob flat = flatJob();
        LlmJob a = jobWithCorpus("SHARED CORPUS XXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX");
        LlmJob b = jobWithCorpus("SHARED CORPUS XXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX");
        assertNull(p.prefixCacheKeyFor(flat));
        assertEquals(p.prefixCacheKeyFor(a), p.prefixCacheKeyFor(b));

        List<LlmAsyncProvider.FetchedResult> out = p.fetchResults("pid", List.of(flat, a, b));
        assertEquals(3, out.size());
        out.forEach(r -> assertTrue(r.succeeded));
        assertTrue(p.firstStarted.await(2, TimeUnit.SECONDS));
        // The group's lead runs alone in Phase 1; then Phase 2 runs the flat job + the
        // group tail in parallel (peak == 2). The lead never overlapped its tail.
        List<UUID> ends = new ArrayList<>(p.endOrder);
        assertEquals(2, p.peakInFlight.get(),
                "the primed group's lead runs alone first; flat + tail then run in parallel (peak == 2)");
        // The first job to finish is the group lead (a), proving it completed before its tail (b).
        assertEquals(a.getId(), ends.get(0),
                "the same-prefix group lead must complete before its tail starts");
    }
}
