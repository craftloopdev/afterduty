package com.afterduty.service.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.afterduty.model.LlmJob;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

/**
 * Test-scoped fake implementation of {@link LlmAsyncProvider}.
 *
 * Maintains an in-memory map of purpose → response text. Every call to
 * {@link #submit} is immediately "acknowledged" — the returned handle carries a
 * provider job id that the poller can poll and fetch. {@link #poll} returns
 * SUCCEEDED for any recognized provider job id (or whatever status was forced
 * via {@link #setStatus}). {@link #fetchResults} returns the canned text
 * wrapped in an {@link LlmJobResult}.
 *
 * Usage:
 * <pre>{@code
 *   FakeLlmAsyncProvider fake = new FakeLlmAsyncProvider();
 *   fake.setResponse("synthesis_identify", "[{\"name\":\"PTSD\",\"vasrd_code\":\"9411\"}]");
 *   fake.setResponse("synthesis_duplicate_merger", "[{\"name\":\"PTSD\",\"vasrd_code\":\"9411\"}]");
 * }</pre>
 */
public class FakeLlmAsyncProvider implements LlmAsyncProvider {

    public static final String PROVIDER_NAME = "fake-llm";

    private final ObjectMapper objectMapper = new ObjectMapper();

    /** purpose → canned response text (defaults to "[]" if not set). */
    private final Map<String, String> cannedResponses = new ConcurrentHashMap<>();

    /**
     * evidenceId → canned response text, consulted BEFORE the purpose map. Lets a
     * test give two documents that share the same purpose (e.g. single-pass
     * {@code extraction_doc}) different per-document responses — needed to exercise
     * the "doc A valid + doc B unparseable" partial-failure isolation path.
     */
    private final Map<Long, String> cannedResponsesByEvidence = new ConcurrentHashMap<>();

    /**
     * purpose → responder that derives the response text from the {@link LlmJob}
     * itself (e.g. via {@code job.getConditionId()}). Consulted AFTER the
     * per-evidence override but BEFORE the purpose-keyed canned text — so a test
     * can fan a single purpose (e.g. {@code synthesis_rate}, {@code gap_evidence})
     * out per condition. Introduced for the Increment 8 golden-case seeder, which
     * resolves the job's condition → its VASRD code → the case's per-DC canned file.
     * Additive: every existing test that only uses {@link #setResponse} /
     * {@link #setResponseForEvidence} is unaffected because this map stays empty.
     */
    private final Map<String, Function<LlmJob, String>> responders = new ConcurrentHashMap<>();

    /** purpose → forced status override (defaults to SUCCEEDED). */
    private final Map<String, ProviderJobStatus> forcedStatus = new ConcurrentHashMap<>();

    /** purpose → forced failure message; fetchResults returns FAILED entries. */
    private final Map<String, String> forcedFailures = new ConcurrentHashMap<>();

    /** evidenceId → forced failure message; lets ONE document's job fail while its
     *  siblings succeed (the partial-stage-failure path). */
    private final Map<Long, String> forcedFailuresByEvidence = new ConcurrentHashMap<>();

    /** When true the fake reports itself as a batch lane (24h-SLA semantics). */
    private volatile boolean batch = false;

    /**
     * Records every submit call as a list of (internalJobId, purpose, batchGroupKey).
     * Tests can inspect this to assert what was submitted.
     */
    private final CopyOnWriteArrayList<SubmittedJob> submittedJobs = new CopyOnWriteArrayList<>();

    /** provider job id → list of internalJobIds that share it (batch scenario). */
    private final Map<String, List<UUID>> providerJobToInternals = new ConcurrentHashMap<>();

    /** internalJobId → purpose (for fetchResults). */
    private final Map<UUID, String> internalToPurpose = new ConcurrentHashMap<>();

    /** provider job id → purpose (for poll, derived from first job in the batch). */
    private final Map<String, String> providerJobToPurpose = new ConcurrentHashMap<>();

    private final AtomicInteger pollCount = new AtomicInteger(0);

    // -------------------------------------------------------------------------
    // Configuration helpers
    // -------------------------------------------------------------------------

    public void setResponse(String purpose, String text) {
        cannedResponses.put(purpose, text);
    }

    /**
     * Canned response for a specific evidence id, overriding the purpose-keyed
     * response. Use when two jobs share a purpose but must return different bodies.
     */
    public void setResponseForEvidence(Long evidenceId, String text) {
        cannedResponsesByEvidence.put(evidenceId, text);
    }

    /**
     * Register a per-job responder for a purpose. The responder is invoked at
     * {@link #fetchResults} time with the {@link LlmJob} and returns the response
     * text — letting the caller derive the body from {@code job.getConditionId()}
     * (the rate/gap fan-out pattern). Wins over the purpose-keyed canned text but
     * loses to a per-evidence override (most-specific-wins). A responder returning
     * {@code null} falls through to the purpose-keyed text.
     */
    public void setResponder(String purpose, Function<LlmJob, String> responder) {
        responders.put(purpose, responder);
    }

    /**
     * Force all polls for jobs of the given purpose to return {@code status}.
     * Call {@code setStatus("synthesis_identify", ProviderJobStatus.IN_PROGRESS)} to
     * simulate a still-running job.
     */
    public void setStatus(String purpose, ProviderJobStatus status) {
        forcedStatus.put(purpose, status);
    }

    public void clearStatus(String purpose) {
        forcedStatus.remove(purpose);
    }

    /**
     * Force every job of the given purpose to come back FAILED with the given
     * error message (a terminal provider-side failure, e.g. a bad batch entry).
     */
    public void setFailure(String purpose, String errorMessage) {
        forcedFailures.put(purpose, errorMessage);
    }

    public void setFailureForEvidence(Long evidenceId, String errorMessage) {
        forcedFailuresByEvidence.put(evidenceId, errorMessage);
    }

    public void setBatch(boolean batch) {
        this.batch = batch;
    }

    @Override
    public boolean isBatch() {
        return batch;
    }

    public int pollCount() {
        return pollCount.get();
    }

    /**
     * Returns an immutable snapshot of every job that has been submitted through
     * this provider. Tests use this to assert purpose, conditionId, evidenceId,
     * batchGroupKey without touching internal state.
     */
    public List<SubmittedJob> submittedJobs() {
        return Collections.unmodifiableList(new ArrayList<>(submittedJobs));
    }

    public void reset() {
        cannedResponses.clear();
        cannedResponsesByEvidence.clear();
        responders.clear();
        forcedStatus.clear();
        forcedFailures.clear();
        forcedFailuresByEvidence.clear();
        batch = false;
        submittedJobs.clear();
        providerJobToInternals.clear();
        internalToPurpose.clear();
        providerJobToPurpose.clear();
        pollCount.set(0);
    }

    // -------------------------------------------------------------------------
    // LlmAsyncProvider contract
    // -------------------------------------------------------------------------

    /**
     * Assigns each job a shared provider job id (one per batch group, or one per
     * individual job if no batch key). Returns handles immediately — no I/O.
     */
    @Override
    public List<LlmJobHandle> submit(List<LlmJob> jobs) {
        // Group by batch_group_key so we can assign the same provider job id to
        // all members of a batch (mimicking Anthropic batch behaviour).
        Map<String, String> groupKeyToProviderJobId = new LinkedHashMap<>();

        List<LlmJobHandle> handles = new ArrayList<>(jobs.size());
        for (LlmJob job : jobs) {
            String groupKey = job.getBatchGroupKey() != null
                    ? job.getBatchGroupKey()
                    : "_solo_" + job.getId();

            String providerJobId = groupKeyToProviderJobId.computeIfAbsent(
                    groupKey, k -> "fake-pjid-" + UUID.randomUUID());

            providerJobToInternals
                    .computeIfAbsent(providerJobId, k -> new CopyOnWriteArrayList<>())
                    .add(job.getId());
            internalToPurpose.put(job.getId(), job.getPurpose());
            providerJobToPurpose.putIfAbsent(providerJobId, job.getPurpose());

            submittedJobs.add(new SubmittedJob(
                    job.getId(),
                    job.getPurpose(),
                    job.getBatchGroupKey(),
                    job.getConditionId(),
                    job.getEvidenceId(),
                    providerJobId
            ));

            handles.add(new LlmJobHandle(job.getId(), providerJobId));
        }
        return handles;
    }

    @Override
    public ProviderJobStatus poll(String providerJobId) {
        pollCount.incrementAndGet();
        String purpose = providerJobToPurpose.get(providerJobId);
        if (purpose == null) {
            // Unknown provider job id — treat as failed (used in orphan-recovery test).
            return ProviderJobStatus.FAILED;
        }
        return forcedStatus.getOrDefault(purpose, ProviderJobStatus.SUCCEEDED);
    }

    @Override
    public List<FetchedResult> fetchResults(String providerJobId, List<LlmJob> jobs) {
        List<FetchedResult> results = new ArrayList<>(jobs.size());
        for (LlmJob job : jobs) {
            String purpose = internalToPurpose.getOrDefault(job.getId(), job.getPurpose());
            String failure = job.getEvidenceId() != null
                    ? forcedFailuresByEvidence.getOrDefault(job.getEvidenceId(), forcedFailures.get(purpose))
                    : forcedFailures.get(purpose);
            if (failure != null) {
                results.add(FetchedResult.failure(job.getId(), failure));
                continue;
            }
            // Precedence (most specific first):
            //   1. per-evidence override  (two same-purpose jobs, different bodies)
            //   2. per-job responder       (rate/gap fan-out, keyed off conditionId)
            //   3. purpose-keyed canned text (default "[]")
            String text;
            if (job.getEvidenceId() != null && cannedResponsesByEvidence.containsKey(job.getEvidenceId())) {
                text = cannedResponsesByEvidence.get(job.getEvidenceId());
            } else {
                Function<LlmJob, String> responder = responders.get(purpose);
                String responded = responder != null ? responder.apply(job) : null;
                text = responded != null ? responded : cannedResponses.getOrDefault(purpose, "[]");
            }
            LlmJobResult result = buildResult(text, purpose);
            results.add(FetchedResult.success(job.getId(), result));
        }
        return results;
    }

    @Override
    public void cancel(String providerJobId) {
        providerJobToInternals.remove(providerJobId);
    }

    @Override
    public String providerName() {
        return PROVIDER_NAME;
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private LlmJobResult buildResult(String text, String purpose) {
        JsonNode rawNode;
        try {
            rawNode = objectMapper.readTree(text);
        } catch (Exception e) {
            rawNode = objectMapper.createObjectNode();
        }
        return new LlmJobResult(text, rawNode, 10L, 20L, 0L, PROVIDER_NAME, "fake-model-1");
    }

    // -------------------------------------------------------------------------
    // Value type for test inspection
    // -------------------------------------------------------------------------

    public record SubmittedJob(
            UUID internalJobId,
            String purpose,
            String batchGroupKey,
            Long conditionId,
            Long evidenceId,
            String providerJobId
    ) {}
}
