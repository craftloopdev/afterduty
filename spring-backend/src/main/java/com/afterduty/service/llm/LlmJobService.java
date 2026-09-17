package com.afterduty.service.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.afterduty.model.LlmJob;
import com.afterduty.repository.LlmJobRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.util.*;

/**
 * The only LLM-facing API the pipeline orchestrators use. They build an
 * {@link LlmJobRequest}, call {@link #submit}, and later poll
 * {@link #getResult} on subsequent state-machine ticks until a result lands
 * (or the job FAILS).
 *
 * <p>This class never blocks on the model. {@link #submit} writes a QUEUED
 * row and returns. The {@code LlmJobSubmitter} scheduled worker takes it
 * from there; results are persisted by {@code LlmJobPoller}.
 */
@Service
public class LlmJobService {

    private final LlmJobRepository llmJobRepository;
    private final LlmProviderRouter router;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public LlmJobService(LlmJobRepository llmJobRepository, LlmProviderRouter router) {
        this.llmJobRepository = llmJobRepository;
        this.router = router;
    }

    /**
     * Persists a QUEUED job and returns its UUID.
     *
     * <p>After persisting, the provider's {@code submit()} is called with the saved
     * job so that the provider can pre-register the job internally (e.g. assign a
     * {@code providerJobId} in its in-memory state). The job's DB status and
     * {@code providerJobId} column are deliberately left as QUEUED / null — the
     * {@link LlmJobSubmitter} scheduled worker is still responsible for the
     * official QUEUED → SUBMITTED transition and persisting the {@code providerJobId}.
     *
     * <p>The pre-register call enables test scenarios where the same
     * {@code FakeLlmAsyncProvider} instance must recognise the job for polling
     * even before the submitter tick has run (e.g.
     * {@code LlmJobPollerOrphanRecoveryTest#liveSubmittedJob_belowDeadline_notRequeued}).
     * For real providers the extra call is a no-op at the HTTP layer because
     * the provider returns handles that are immediately discarded here.
     */
    @Transactional
    public UUID submit(LlmJobRequest request) {
        LlmAsyncProvider provider = router.resolveProvider(request);
        String modelName = router.resolveModel(request, provider);

        LlmJob job = LlmJob.builder()
                .provider(provider.providerName())
                .modelName(modelName)
                .purpose(request.getPurpose())
                .status(LlmJob.Status.QUEUED)
                .claimId(request.getClaimId())
                .userId(request.getUserId())
                .conditionId(request.getConditionId())
                .evidenceId(request.getEvidenceId())
                .batchGroupKey(request.getBatchGroupKey())
                .requestPayload(serializeRequest(request))
                .build();
        LlmJob saved = llmJobRepository.save(job);

        // Pre-register with the provider so its in-memory state reflects the job.
        // DB status/providerJobId are intentionally not updated here — the submitter
        // owns the QUEUED → SUBMITTED transition.
        try {
            provider.submit(List.of(saved));
        } catch (Exception ignored) {
            // Pre-registration failure must not break submit(); the submitter will
            // retry on its next tick.
        }

        return saved.getId();
    }

    /** Submits multiple requests; convenience for fan-out stages. */
    @Transactional
    public List<UUID> submitBatch(List<LlmJobRequest> requests) {
        List<UUID> ids = new ArrayList<>(requests.size());
        for (LlmJobRequest r : requests) ids.add(submit(r));
        return ids;
    }

    /**
     * Returns the result if SUCCEEDED. Empty if still QUEUED/SUBMITTED/IN_PROGRESS.
     * Throws {@link LlmJobFailedException} if FAILED.
     */
    public Optional<LlmJobResult> getResult(UUID jobId) {
        LlmJob job = llmJobRepository.findById(jobId)
                .orElseThrow(() -> new IllegalStateException("LlmJob " + jobId + " not found"));
        return switch (job.getStatus()) {
            case QUEUED, SUBMITTED, IN_PROGRESS -> Optional.empty();
            case FAILED -> throw new LlmJobFailedException(job.getId(), job.getErrorMessage());
            case SUCCEEDED -> Optional.of(deserializeResult(job));
        };
    }

    /** True if every job in {@code jobIds} is in a terminal state (SUCCEEDED or FAILED). */
    public boolean allTerminal(Collection<UUID> jobIds) {
        if (jobIds == null || jobIds.isEmpty()) return true;
        return llmJobRepository.countTerminal(jobIds) == jobIds.size();
    }

    /** True if every job in {@code jobIds} is SUCCEEDED. */
    public boolean allSucceeded(Collection<UUID> jobIds) {
        if (jobIds == null || jobIds.isEmpty()) return true;
        return llmJobRepository.countSucceeded(jobIds) == jobIds.size();
    }

    public LlmJob.Status getStatus(UUID jobId) {
        return llmJobRepository.findById(jobId)
                .map(LlmJob::getStatus)
                .orElseThrow(() -> new IllegalStateException("LlmJob " + jobId + " not found"));
    }

    private String serializeRequest(LlmJobRequest req) {
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("purpose", req.getPurpose());
            body.put("systemPrompt", req.getSystemPrompt());
            body.put("userMessage", req.getUserMessage());
            body.put("messages", req.resolveMessages());
            body.put("tools", req.getTools());
            // Multimodal inline-data parts + structured-output schema (Mission B).
            // Both are optional and null for the legacy text path, so existing
            // serialized payloads are byte-identical (jackson omits null with the
            // app's non_null inclusion, but persist them explicitly only when set).
            if (req.getInlineData() != null) body.put("inlineData", req.getInlineData());
            if (req.getResponseSchema() != null) body.put("responseSchema", req.getResponseSchema());
            // Cache-aware structured prompt (Increment 6 / Mission 6a). Persisted
            // only when set, so legacy flat-prompt payloads remain byte-identical:
            //   structuredPrompt: { systemBlocks: [...], cachedCorpus: "..."? }
            // Restored on the provider side from request_payload exactly like
            // inlineData/responseSchema were in Increment 4.
            LlmJobRequest.StructuredPrompt sp = req.getStructuredPrompt();
            if (sp != null) {
                Map<String, Object> spBody = new LinkedHashMap<>();
                spBody.put("systemBlocks", sp.getSystemBlocks());
                if (sp.hasCachedCorpus()) spBody.put("cachedCorpus", sp.getCachedCorpus());
                body.put("structuredPrompt", spBody);
            }
            body.put("maxTokens", req.getMaxTokens());
            body.put("thinkingBudget", req.getThinkingBudget());
            return objectMapper.writeValueAsString(body);
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize LlmJobRequest: " + e.getMessage(), e);
        }
    }

    private LlmJobResult deserializeResult(LlmJob job) {
        try {
            var raw = objectMapper.readTree(job.getResponsePayload());
            String text = raw.path("text").asText("");
            return new LlmJobResult(
                    text,
                    raw.path("raw"),
                    raw.path("inputTokens").asLong(0),
                    raw.path("outputTokens").asLong(0),
                    raw.path("thinkingTokens").asLong(0),
                    raw.path("cacheReadTokens").asLong(0),
                    raw.path("cacheWriteTokens").asLong(0),
                    job.getProvider(),
                    job.getModelName()
            );
        } catch (IOException e) {
            throw new RuntimeException("Corrupt LlmJob response payload: " + e.getMessage(), e);
        }
    }

    public static String serializeResultPayload(LlmJobResult r, ObjectMapper om) {
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("text", r.getText());
            body.put("raw", r.getRaw());
            body.put("inputTokens", r.getInputTokens());
            body.put("outputTokens", r.getOutputTokens());
            body.put("thinkingTokens", r.getThinkingTokens());
            // Persist Anthropic prompt-cache usage so the poller can book it into
            // the AiCallLog cache columns after restore (0 for Gemini / uncached).
            body.put("cacheReadTokens", r.getCacheReadTokens());
            body.put("cacheWriteTokens", r.getCacheWriteTokens());
            return om.writeValueAsString(body);
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize LlmJobResult: " + e.getMessage(), e);
        }
    }

    /** Thrown by {@link #getResult} when the underlying job is in FAILED state. */
    public static final class LlmJobFailedException extends RuntimeException {
        private final UUID jobId;
        public LlmJobFailedException(UUID jobId, String message) {
            super("LlmJob " + jobId + " FAILED: " + message);
            this.jobId = jobId;
        }
        public UUID getJobId() { return jobId; }
    }
}
