package com.afterduty.service.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.afterduty.model.AiCallLog;
import com.afterduty.model.LlmJob;
import com.afterduty.repository.LlmJobRepository;
import com.afterduty.service.AiCostService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * Polls active provider jobs, fetches results when terminal, and persists
 * payload + cost-tracking AiCallLog entries.
 *
 * <p>Also runs orphan recovery: any LlmJob stuck in SUBMITTED past
 * {@code va-claim.llm.orphan-deadline-min} gets requeued back to QUEUED.
 * This recovers from JVM restarts where the in-memory streaming state of
 * {@link VertexGeminiAsyncProvider} is lost.
 */
@Service
public class LlmJobPoller {

    private static final Logger log = LoggerFactory.getLogger(LlmJobPoller.class);

    private final LlmJobRepository repo;
    private final LlmProviderRouter router;
    private final AiCostService aiCostService;
    private final CacheInvalidationTripwire cacheTripwire;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${va-claim.llm.orphan-deadline-min:30}")
    private int orphanDeadlineMin;

    public LlmJobPoller(LlmJobRepository repo, LlmProviderRouter router, AiCostService aiCostService,
                        @org.springframework.beans.factory.annotation.Autowired(required = false)
                        CacheInvalidationTripwire cacheTripwire) {
        this.repo = repo;
        this.router = router;
        this.aiCostService = aiCostService;
        // Pure observability helper. Optional so @DataJpaTest slices that import
        // the poller without it still load; production component scan wires it.
        this.cacheTripwire = cacheTripwire;
    }

    @Scheduled(fixedDelayString = "${va-claim.llm.poller.poll-ms:15000}",
               initialDelayString = "${va-claim.llm.poller.initial-delay-ms:10000}")
    public void tick() {
        recoverOrphans();
        pollActive();
    }

    @Transactional
    void pollActive() {
        List<String> providerJobIds = repo.findActiveProviderJobIds();
        if (providerJobIds.isEmpty()) return;

        Instant now = Instant.now();
        for (String providerJobId : providerJobIds) {
            List<LlmJob> jobs = repo.findActiveByProviderJobId(providerJobId);
            if (jobs.isEmpty()) continue;
            String providerName = jobs.get(0).getProvider();
            LlmAsyncProvider provider;
            try {
                provider = router.byName(providerName);
            } catch (Exception e) {
                log.error("Unknown provider {} on jobs — skipping", providerName);
                continue;
            }
            try {
                ProviderJobStatus status = provider.poll(providerJobId);
                Set<UUID> ids = new HashSet<>();
                for (LlmJob j : jobs) {
                    ids.add(j.getId());
                    j.setLastPolledAt(now);  // mirror in-memory so saveAll doesn't clobber the JPQL UPDATE
                }
                repo.touchPolled(ids, now);

                if (status == ProviderJobStatus.IN_PROGRESS) {
                    for (LlmJob j : jobs) j.setStatus(LlmJob.Status.IN_PROGRESS);
                    repo.saveAll(jobs);
                    continue;
                }

                List<LlmAsyncProvider.FetchedResult> results = provider.fetchResults(providerJobId, jobs);
                Map<UUID, LlmJob> jobById = new HashMap<>();
                for (LlmJob j : jobs) jobById.put(j.getId(), j);
                for (LlmAsyncProvider.FetchedResult r : results) {
                    LlmJob job = jobById.get(r.internalJobId);
                    if (job == null) continue;
                    if (r.succeeded) {
                        job.setResponsePayload(LlmJobService.serializeResultPayload(r.result, objectMapper));
                        job.setStatus(LlmJob.Status.SUCCEEDED);
                        job.setCompletedAt(now);
                        recordCost(job, r.result, provider.isBatch());
                    } else {
                        job.setStatus(LlmJob.Status.FAILED);
                        job.setErrorMessage(r.errorMessage);
                        job.setCompletedAt(now);
                        recordError(job, r.errorMessage, provider.isBatch());
                    }
                }
                repo.saveAll(jobs);
            } catch (Exception e) {
                log.warn("Poll/fetch failed for provider job {}: {} — leaving for next tick",
                        providerJobId, e.getMessage());
                // Leave SUBMITTED; orphan-recovery will eventually requeue if it's truly stuck.
            }
        }
    }

    @Transactional
    void recoverOrphans() {
        Instant deadline = Instant.now().minus(orphanDeadlineMin, ChronoUnit.MINUTES);
        requeueOrphanedSubmissions(deadline);
        recoverStalledInProgress(deadline);
    }

    /** SUBMITTED past the deadline: the submitter died before the provider acknowledged. */
    private void requeueOrphanedSubmissions(Instant deadline) {
        List<LlmJob> orphans = repo.findOrphanedSubmissions(deadline);
        if (orphans.isEmpty()) return;
        log.warn("Recovering {} orphaned SUBMITTED job(s) past deadline (>{}min)", orphans.size(), orphanDeadlineMin);
        for (LlmJob j : orphans) {
            j.setStatus(LlmJob.Status.QUEUED);
            j.setProviderJobId(null);
            j.setSubmittedAt(null);
        }
        repo.saveAll(orphans);
    }

    /** Mirrors the submitter's retry cap: a job that stalls this many times is dead for good. */
    static final int MAX_STALL_ATTEMPTS = 3;

    /**
     * IN_PROGRESS past the deadline on a realtime provider is a dead worker thread,
     * not a slow call — realtime lanes answer in seconds. On 2026-09-12 three Gemini
     * extraction threads died in an OutOfMemoryError, their in-memory calls were never
     * marked done, the provider kept answering IN_PROGRESS, and orphan recovery (which
     * only looked at SUBMITTED) never saw them, so the extraction stage sat for hours.
     * Batch lanes (24h SLA) legitimately run IN_PROGRESS for hours and are left alone.
     */
    private void recoverStalledInProgress(Instant deadline) {
        List<LlmJob> stalled = repo.findStalledInProgress(deadline);
        if (stalled.isEmpty()) return;
        List<LlmJob> changed = new java.util.ArrayList<>(stalled.size());
        for (LlmJob j : stalled) {
            LlmAsyncProvider provider;
            try {
                provider = router.byName(j.getProvider());
            } catch (Exception e) {
                continue;
            }
            if (provider.isBatch()) continue;
            try {
                if (j.getProviderJobId() != null) provider.cancel(j.getProviderJobId());
            } catch (Exception ignored) {
                // Best-effort: drops the provider's in-memory record if it has one.
            }
            if (j.getAttempts() >= MAX_STALL_ATTEMPTS) {
                j.setStatus(LlmJob.Status.FAILED);
                j.setErrorMessage("Provider call stalled past " + orphanDeadlineMin
                        + " min on attempt " + j.getAttempts() + " — giving up");
                j.setCompletedAt(Instant.now());
                log.error("Job {} ({}) stalled IN_PROGRESS on attempt {} — marking FAILED",
                        j.getId(), j.getPurpose(), j.getAttempts());
            } else {
                j.setStatus(LlmJob.Status.QUEUED);
                j.setProviderJobId(null);
                j.setSubmittedAt(null);
                log.warn("Job {} ({}) stalled IN_PROGRESS past {}min on attempt {} — requeued",
                        j.getId(), j.getPurpose(), orphanDeadlineMin, j.getAttempts());
            }
            changed.add(j);
        }
        if (!changed.isEmpty()) repo.saveAll(changed);
    }

    private void recordCost(LlmJob job, LlmJobResult r, boolean isBatch) {
        try {
            AiCallLog logRow = AiCallLog.builder()
                    .claimId(job.getClaimId())
                    .userId(job.getUserId())
                    .evidenceId(job.getEvidenceId())
                    .callType(job.getPurpose())
                    .provider(job.getProvider())
                    .modelName(job.getModelName())
                    .inputTokens(r.getInputTokens())
                    .outputTokens(r.getOutputTokens())
                    .thinkingTokens(r.getThinkingTokens())
                    // Anthropic prompt-cache usage (0 for Gemini / uncached Claude).
                    // These finally populate the never-written cache columns and
                    // drive cache-aware pricing in AiCostService.
                    .cacheReadTokens(r.getCacheReadTokens())
                    .cacheWriteTokens(r.getCacheWriteTokens())
                    .latencyMs(latencyMs(job))
                    .isBatch(isBatch)
                    .status("success")
                    .llmJobId(job.getId())
                    .build();
            aiCostService.recordCall(logRow);
            // Silent-cache-invalidator tripwire: we sent a cache_control block but
            // the response read/wrote zero cache tokens — and the corpus was big
            // enough to be cacheable. Rate-limited WARN, never for small prefixes
            // or batch-lane first-writers. Optional (null in some slice tests).
            if (cacheTripwire != null) {
                cacheTripwire.check(job, r, isBatch);
            }
        } catch (Exception e) {
            log.warn("Failed to record cost for job {}: {}", job.getId(), e.getMessage());
        }
    }

    private void recordError(LlmJob job, String errorMessage, boolean isBatch) {
        try {
            AiCallLog logRow = AiCallLog.builder()
                    .claimId(job.getClaimId())
                    .userId(job.getUserId())
                    .evidenceId(job.getEvidenceId())
                    .callType(job.getPurpose())
                    .provider(job.getProvider())
                    .modelName(job.getModelName())
                    .latencyMs(latencyMs(job))
                    .isBatch(isBatch)
                    .llmJobId(job.getId())
                    .build();
            aiCostService.recordError(logRow, errorMessage);
        } catch (Exception e) {
            log.warn("Failed to record error for job {}: {}", job.getId(), e.getMessage());
        }
    }

    private static Long latencyMs(LlmJob job) {
        if (job.getSubmittedAt() == null || job.getCompletedAt() == null) return null;
        return job.getCompletedAt().toEpochMilli() - job.getSubmittedAt().toEpochMilli();
    }
}
