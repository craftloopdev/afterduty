package com.afterduty.service.llm;

import com.afterduty.model.LlmJob;
import com.afterduty.repository.LlmJobRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;

/**
 * Pulls QUEUED LlmJobs and hands them to the chosen provider. Groups jobs by
 * (provider, batch_group_key) so providers that support batched submission
 * (Anthropic) get one HTTP POST per group.
 *
 * <p>Multi-instance safe via PESSIMISTIC_WRITE on the QUEUED finder
 * (translates to {@code SELECT ... FOR UPDATE SKIP LOCKED} on Postgres).
 */
@Service
public class LlmJobSubmitter {

    private static final Logger log = LoggerFactory.getLogger(LlmJobSubmitter.class);

    private final LlmJobRepository repo;
    private final LlmProviderRouter router;

    /**
     * Item B2 (report §5 item 3) — the ≤N-dirty realtime lane rule. Optional
     * (setter-injected) so existing test slices that {@code @Import} only the
     * submitter keep wiring; absent ⇒ jobs ride the provider stamped at submit
     * time, exactly today's behavior. The decision lives HERE (not at submit)
     * because a run's total dirty count is only knowable once the whole rate
     * fan-out is committed — which is guaranteed by the time this scheduled
     * worker can see any of the run's QUEUED rows.
     */
    @org.springframework.lang.Nullable
    private RealtimeLaneEscalator laneEscalator;

    @Value("${va-claim.llm.submitter.batch-size:50}")
    private int batchSize;

    public LlmJobSubmitter(LlmJobRepository repo, LlmProviderRouter router) {
        this.repo = repo;
        this.router = router;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setLaneEscalator(RealtimeLaneEscalator laneEscalator) {
        this.laneEscalator = laneEscalator;
    }

    @Scheduled(fixedDelayString = "${va-claim.llm.submitter.poll-ms:3000}",
               initialDelayString = "${va-claim.llm.submitter.initial-delay-ms:5000}")
    @Transactional
    public void tick() {
        List<LlmJob> queued = repo.findQueuedForSubmit(PageRequest.of(0, batchSize));
        if (queued.isEmpty()) return;

        // Item B2 — a small incremental re-run's rate/verify/gap jobs must never
        // vanish into the 24h batch SLA: re-lane them to realtime BEFORE grouping,
        // so grouping/submission below sees the final provider. A lane-decision
        // failure must never block submission — the job just rides its stamped lane.
        if (laneEscalator != null) {
            for (LlmJob j : queued) {
                try {
                    laneEscalator.maybeEscalate(j);
                } catch (Exception e) {
                    log.warn("Realtime lane escalation failed for job {} — keeping provider {}: {}",
                            j.getId(), j.getProvider(), e.getMessage());
                }
            }
        }

        // Group by (provider, batch_group_key). Null group key = "ungrouped" (each its own group).
        Map<String, List<LlmJob>> groups = new LinkedHashMap<>();
        for (LlmJob j : queued) {
            String key = j.getProvider() + "::" +
                    (j.getBatchGroupKey() == null ? "_one_" + j.getId() : j.getBatchGroupKey());
            groups.computeIfAbsent(key, k -> new ArrayList<>()).add(j);
        }

        for (Map.Entry<String, List<LlmJob>> entry : groups.entrySet()) {
            List<LlmJob> jobs = entry.getValue();
            String providerName = jobs.get(0).getProvider();
            try {
                LlmAsyncProvider provider = router.byName(providerName);
                List<LlmJobHandle> handles = provider.submit(jobs);
                if (handles.size() != jobs.size()) {
                    throw new IllegalStateException("Provider " + providerName + " returned "
                            + handles.size() + " handles for " + jobs.size() + " jobs");
                }
                Instant now = Instant.now();
                for (int i = 0; i < jobs.size(); i++) {
                    LlmJob j = jobs.get(i);
                    j.setProviderJobId(handles.get(i).getProviderJobId());
                    j.setStatus(LlmJob.Status.SUBMITTED);
                    j.setSubmittedAt(now);
                    j.setAttempts(j.getAttempts() + 1);
                }
                repo.saveAll(jobs);
                log.info("Submitted {} job(s) to {} (group key tail: {})",
                        jobs.size(), providerName, entry.getKey().substring(Math.max(0, entry.getKey().length() - 16)));
            } catch (Exception e) {
                log.error("Submission to {} failed for {} job(s): {}", providerName, jobs.size(), e.getMessage(), e);
                Instant now = Instant.now();
                for (LlmJob j : jobs) {
                    j.setAttempts(j.getAttempts() + 1);
                    if (j.getAttempts() >= 3) {
                        j.setStatus(LlmJob.Status.FAILED);
                        j.setErrorMessage("Provider submit failed after " + j.getAttempts()
                                + " attempts: " + e.getMessage());
                        j.setCompletedAt(now);
                    }
                    // else: leave as QUEUED, next tick will retry
                }
                repo.saveAll(jobs);
            }
        }
    }
}
