package com.afterduty.service.llm;

import com.afterduty.model.LlmJob;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Liveness of the in-memory Gemini provider — the 2026-09-12 claim-24 wedge.
 *
 * <p>Each submission runs on its own virtual thread and reports back through an
 * in-memory {@code AsyncCall}. Two things went wrong at once: seventeen documents
 * were base64-inlined concurrently and exhausted the heap, and the
 * {@code OutOfMemoryError} (an {@code Error}, not an {@code Exception}) escaped the
 * thread's catch block, so the call was never marked done — {@code poll()} answered
 * IN_PROGRESS forever and the extraction stage could not end. These tests pin the
 * two guarantees that prevent it: a dying thread surfaces as FAILED, and only a
 * bounded number of inline payloads are ever in flight at once.
 *
 * <p>No Spring, no credentials, no HTTP: the mocked job fails or blocks before the
 * request is built, which is exactly the region these guarantees cover.
 */
@Tag("regression")
class VertexGeminiAsyncProviderImplTest {

    private static LlmJob jobThatThrows(Throwable t) {
        LlmJob job = mock(LlmJob.class);
        when(job.getId()).thenReturn(UUID.randomUUID());
        when(job.getRequestPayload()).thenThrow(t);
        return job;
    }

    private static ProviderJobStatus awaitTerminal(VertexGeminiAsyncProviderImpl provider,
                                                   String providerJobId) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            ProviderJobStatus s = provider.poll(providerJobId);
            if (s != ProviderJobStatus.IN_PROGRESS) return s;
            Thread.sleep(10);
        }
        return provider.poll(providerJobId);
    }

    @Test
    void aThreadKilledByAnErrorSurfacesAsFailedNotInProgressForever() throws Exception {
        VertexGeminiAsyncProviderImpl provider = new VertexGeminiAsyncProviderImpl();
        LlmJob job = jobThatThrows(new OutOfMemoryError("Java heap space"));

        String providerJobId = provider.submit(List.of(job)).get(0).getProviderJobId();

        assertThat(awaitTerminal(provider, providerJobId)).isEqualTo(ProviderJobStatus.FAILED);
        var results = provider.fetchResults(providerJobId, List.of(job));
        assertThat(results).hasSize(1);
        assertThat(results.get(0).succeeded).isFalse();
        assertThat(results.get(0).errorMessage).contains("OutOfMemoryError");
    }

    @Test
    void anOrdinaryExceptionStillSurfacesAsFailed() throws Exception {
        VertexGeminiAsyncProviderImpl provider = new VertexGeminiAsyncProviderImpl();
        LlmJob job = jobThatThrows(new IllegalStateException("bad payload"));

        String providerJobId = provider.submit(List.of(job)).get(0).getProviderJobId();

        assertThat(awaitTerminal(provider, providerJobId)).isEqualTo(ProviderJobStatus.FAILED);
        assertThat(provider.fetchResults(providerJobId, List.of(job)).get(0).errorMessage)
                .contains("bad payload");
    }

    @Test
    void inlinePayloadsInFlightAreBoundedByMaxConcurrent() throws Exception {
        VertexGeminiAsyncProviderImpl provider = new VertexGeminiAsyncProviderImpl();
        provider.setMaxConcurrent(1);

        // Job 1 holds the only permit until we release it; job 2 must not even
        // read its payload (the step that materialises the inline bytes) before then.
        CountDownLatch releaseFirst = new CountDownLatch(1);
        AtomicBoolean secondStarted = new AtomicBoolean(false);

        LlmJob first = mock(LlmJob.class);
        when(first.getId()).thenReturn(UUID.randomUUID());
        when(first.getRequestPayload()).thenAnswer(inv -> {
            releaseFirst.await(5, TimeUnit.SECONDS);
            throw new IllegalStateException("first done");
        });
        LlmJob second = mock(LlmJob.class);
        when(second.getId()).thenReturn(UUID.randomUUID());
        when(second.getRequestPayload()).thenAnswer(inv -> {
            secondStarted.set(true);
            throw new IllegalStateException("second done");
        });

        var handles = provider.submit(List.of(first, second));

        Thread.sleep(300);
        assertThat(secondStarted).as("second job must wait for a permit").isFalse();
        assertThat(provider.availableInlinePermits()).isZero();

        releaseFirst.countDown();
        assertThat(awaitTerminal(provider, handles.get(0).getProviderJobId())).isEqualTo(ProviderJobStatus.FAILED);
        assertThat(awaitTerminal(provider, handles.get(1).getProviderJobId())).isEqualTo(ProviderJobStatus.FAILED);
        assertThat(secondStarted).isTrue();
        // Every permit is returned even though both calls ended in failure.
        assertThat(provider.availableInlinePermits()).isEqualTo(1);
    }
}
