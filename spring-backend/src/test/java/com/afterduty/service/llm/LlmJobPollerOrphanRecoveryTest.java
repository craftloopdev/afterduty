package com.afterduty.service.llm;

import com.afterduty.model.LlmJob;
import com.afterduty.repository.LlmJobRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests the orphan-recovery path in {@link LlmJobPoller#recoverOrphans()}.
 *
 * Orphan recovery: any LlmJob stuck in SUBMITTED past the configured deadline
 * is re-queued so the submitter can try again.
 */
@DataJpaTest
@Import({
        LlmProviderRouterTestConfig.class,
        LlmJobService.class,
        LlmJobSubmitter.class,
        LlmJobPoller.class
})
@TestPropertySource(properties = {
        "va-claim.llm.submitter.batch-size=50",
        "va-claim.llm.submitter.poll-ms=9999999",
        "va-claim.llm.submitter.initial-delay-ms=9999999",
        "va-claim.llm.poller.poll-ms=9999999",
        "va-claim.llm.poller.initial-delay-ms=9999999",
        "va-claim.llm.orphan-deadline-min=30"
})
class LlmJobPollerOrphanRecoveryTest {

    /** Configured orphan deadline in minutes — must match property above. */
    static final int ORPHAN_DEADLINE_MIN = 30;

    @Autowired
    LlmJobPoller llmJobPoller;

    @Autowired
    LlmJobRepository llmJobRepository;

    @Autowired
    FakeLlmAsyncProvider fakeProvider;

    @BeforeEach
    void setUp() {
        fakeProvider.reset();
    }

    // -------------------------------------------------------------------------
    // Scenario 25: orphaned SUBMITTED job past deadline is requeued
    // -------------------------------------------------------------------------

    @Test
    void orphanedSubmittedJob_pastDeadline_requeued() {
        // Create a SUBMITTED job whose submitted_at is well past the deadline.
        Instant pastDeadline = Instant.now().minus(ORPHAN_DEADLINE_MIN + 5, ChronoUnit.MINUTES);

        LlmJob orphan = LlmJob.builder()
                .provider("fake-llm")
                .modelName("fake-model-1")
                .purpose("synthesis_identify")
                .status(LlmJob.Status.SUBMITTED)
                .claimId(42L)
                .userId(1L)
                .requestPayload("{\"purpose\":\"synthesis_identify\",\"userMessage\":\"identify\"}")
                .providerJobId("stale-pjid-that-fake-does-not-know")
                .attempts(1)
                .submittedAt(pastDeadline)
                .build();

        LlmJob saved = llmJobRepository.save(orphan);

        // Run the poller. recoverOrphans() fires first in tick().
        // The fake provider does not know the stale provider job id, so pollActive()
        // will fail gracefully. The key assertion is that recoverOrphans() requeued it.
        llmJobPoller.tick();

        LlmJob reloaded = llmJobRepository.findById(saved.getId()).orElseThrow();

        assertEquals(LlmJob.Status.QUEUED, reloaded.getStatus(),
                "Orphaned SUBMITTED job past deadline must be requeued to QUEUED");
        assertNull(reloaded.getProviderJobId(),
                "provider_job_id must be cleared on requeue");
        assertNull(reloaded.getSubmittedAt(),
                "submitted_at must be cleared on requeue");
    }

    // -------------------------------------------------------------------------
    // Scenario 26: live SUBMITTED job below deadline is NOT requeued
    // -------------------------------------------------------------------------

    @Test
    void liveSubmittedJob_belowDeadline_notRequeued() {
        // submitted_at = 1 minute ago — well within the 30-minute orphan deadline.
        Instant recentSubmit = Instant.now().minus(1, ChronoUnit.MINUTES);

        // Register the provider job id in the fake so pollActive() can find it.
        // We need to set the fake to IN_PROGRESS so the job doesn't flip to SUCCEEDED.
        fakeProvider.setStatus("synthesis_identify", ProviderJobStatus.IN_PROGRESS);
        fakeProvider.setResponse("synthesis_identify", "[]");

        // We must have the provider job id registered in the fake's internal map.
        // Easiest: submit through the service so the fake sees the submit call.
        LlmJobService llmJobService = new LlmJobService(
                llmJobRepository,
                // Inline a router that routes everything to the fake:
                new LlmProviderRouter(java.util.List.of(fakeProvider)) {
                    @Override public LlmAsyncProvider resolveProvider(LlmJobRequest req) { return fakeProvider; }
                    @Override public LlmAsyncProvider byName(String n) { return fakeProvider; }
                    @Override public String resolveModel(LlmJobRequest req, LlmAsyncProvider p) { return "fake-model-1"; }
                }
        );
        java.util.UUID jobId = llmJobService.submit(LlmJobRequest.builder()
                .purpose("synthesis_identify").userMessage("identify").claimId(99L).userId(1L).build());

        // Manually override submitted_at and status to simulate a recently-submitted job.
        LlmJob job = llmJobRepository.findById(jobId).orElseThrow();
        job.setStatus(LlmJob.Status.SUBMITTED);
        job.setSubmittedAt(recentSubmit);
        job.setProviderJobId(fakeProvider.submittedJobs().get(0).providerJobId());
        job.setAttempts(1);
        llmJobRepository.save(job);

        llmJobPoller.tick();

        LlmJob reloaded = llmJobRepository.findById(jobId).orElseThrow();
        // The job should stay SUBMITTED or transition to IN_PROGRESS (not QUEUED).
        assertNotEquals(LlmJob.Status.QUEUED, reloaded.getStatus(),
                "A recently-submitted job (within orphan deadline) must NOT be requeued");
        assertNotNull(reloaded.getLastPolledAt(),
                "last_polled_at must be updated when the poller successfully polls the job");
    }

    // -------------------------------------------------------------------------
    // Stall recovery (2026-09-12 claim-24 wedge): a job the provider keeps
    // reporting IN_PROGRESS past the deadline is dead, not slow. Orphan recovery
    // only ever looked at SUBMITTED, so three jobs whose threads died in an
    // OutOfMemoryError sat IN_PROGRESS for hours and the extraction stage never
    // ended. Realtime providers answer in seconds; batch providers legitimately
    // run for hours and must be left alone.
    // -------------------------------------------------------------------------

    private LlmJob inProgressJob(Instant submittedAt, int attempts) {
        return llmJobRepository.save(LlmJob.builder()
                .provider("fake-llm")
                .modelName("fake-model-1")
                .purpose("extraction_doc")
                .status(LlmJob.Status.IN_PROGRESS)
                .claimId(24L)
                .userId(1L)
                .requestPayload("{\"purpose\":\"extraction_doc\",\"userMessage\":\"extract\"}")
                .providerJobId("dead-thread-" + java.util.UUID.randomUUID())
                .attempts(attempts)
                .submittedAt(submittedAt)
                .build());
    }

    @Test
    void stalledInProgressJob_pastDeadline_requeued() {
        LlmJob stalled = inProgressJob(
                Instant.now().minus(ORPHAN_DEADLINE_MIN + 5, ChronoUnit.MINUTES), 1);

        llmJobPoller.tick();

        LlmJob reloaded = llmJobRepository.findById(stalled.getId()).orElseThrow();
        assertEquals(LlmJob.Status.QUEUED, reloaded.getStatus(),
                "An IN_PROGRESS job past the deadline on a realtime provider is dead — requeue it");
        assertNull(reloaded.getProviderJobId(), "provider_job_id must be cleared on requeue");
        assertNull(reloaded.getSubmittedAt(), "submitted_at must be cleared on requeue");
    }

    @Test
    void stalledInProgressJob_exhaustedAttempts_failsInsteadOfLooping() {
        // Requeue → resubmit → stall again would loop forever on a document that
        // reliably kills its thread. The submitter's own retry cap is 3; honour it.
        LlmJob stalled = inProgressJob(
                Instant.now().minus(ORPHAN_DEADLINE_MIN + 5, ChronoUnit.MINUTES), 3);

        llmJobPoller.tick();

        LlmJob reloaded = llmJobRepository.findById(stalled.getId()).orElseThrow();
        assertEquals(LlmJob.Status.FAILED, reloaded.getStatus(),
                "A job that has stalled on its last allowed attempt must FAIL so the stage can end");
        assertNotNull(reloaded.getErrorMessage(), "The failure must say why");
        assertNotNull(reloaded.getCompletedAt(), "A terminal job carries completed_at");
    }

    @Test
    void stalledInProgressJob_belowDeadline_leftAlone() {
        LlmJob live = inProgressJob(Instant.now().minus(1, ChronoUnit.MINUTES), 1);

        llmJobPoller.tick();

        LlmJob reloaded = llmJobRepository.findById(live.getId()).orElseThrow();
        assertNotEquals(LlmJob.Status.QUEUED, reloaded.getStatus(),
                "A recently-submitted IN_PROGRESS job is merely slow, not stalled");
    }

    @Test
    void stalledInProgressJob_onBatchProvider_leftAlone() {
        // Anthropic's Message Batches API has a 24h SLA: IN_PROGRESS for hours is
        // normal there and must never be mistaken for a dead thread.
        fakeProvider.setBatch(true);
        LlmJob batch = inProgressJob(
                Instant.now().minus(ORPHAN_DEADLINE_MIN + 5, ChronoUnit.MINUTES), 1);

        llmJobPoller.tick();

        LlmJob reloaded = llmJobRepository.findById(batch.getId()).orElseThrow();
        assertNotEquals(LlmJob.Status.QUEUED, reloaded.getStatus(),
                "Stall recovery must not touch batch-lane jobs");
        assertNotEquals(LlmJob.Status.FAILED, reloaded.getStatus(),
                "Stall recovery must not fail batch-lane jobs");
    }
}
