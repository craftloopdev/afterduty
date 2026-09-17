package com.afterduty.service.llm;

import com.afterduty.model.LlmJob;

import java.util.List;

/**
 * Provider-agnostic async LLM contract.
 *
 * Implementations must offer fire-and-forget semantics: {@link #submit}
 * returns immediately without waiting for completion. {@link #poll} reports
 * the current status without blocking on the model. {@link #fetchResult}
 * returns the cached result once status is SUCCEEDED.
 *
 * The intent is that no method here ever holds an HTTP connection waiting
 * on a model response — eliminating the timeout-failure mode that motivated
 * this refactor.
 *
 * <p>Adding a new vendor = one new implementation + one router entry. The
 * pipeline orchestrators never see the provider; they only call
 * {@link LlmJobService}, which in turn calls these methods via the workers.
 */
public interface LlmAsyncProvider {

    /** Stable identifier, e.g. "anthropic-batch", "vertex-gemini". */
    String providerName();

    /**
     * True when this provider runs jobs through a discounted batch lane
     * (e.g. Anthropic Message Batches at 50% of list price). Cost recording
     * uses this to book calls at the rate actually paid.
     */
    default boolean isBatch() {
        return false;
    }

    /**
     * Submit one or more jobs to the provider. Implementations may bundle
     * the list into a single vendor-side batch (Anthropic) or fan out to
     * independent vendor jobs (Gemini streaming). Returned handles are in
     * the same order as the input jobs.
     */
    List<LlmJobHandle> submit(List<LlmJob> jobs);

    /**
     * Poll the provider for the current status of a vendor-side job ID.
     * For batched providers, the providerJobId may map to multiple LlmJobs;
     * the worker calls this once per distinct providerJobId.
     */
    ProviderJobStatus poll(String providerJobId);

    /**
     * Fetch results for all LlmJobs sharing this providerJobId. Must only
     * be called after {@link #poll} returns {@link ProviderJobStatus#SUCCEEDED}
     * or {@link ProviderJobStatus#FAILED}. Returns one entry per LlmJob in
     * {@code jobs}; entries with FAILED status carry an error in
     * {@link FetchedResult#errorMessage}.
     */
    List<FetchedResult> fetchResults(String providerJobId, List<LlmJob> jobs);

    /** Best-effort cancellation; safe to noop. */
    default void cancel(String providerJobId) {
        // default: no-op
    }

    /** One LlmJob's result post-fetch. */
    final class FetchedResult {
        public final java.util.UUID internalJobId;
        public final boolean succeeded;
        public final LlmJobResult result;       // non-null when succeeded == true
        public final String errorMessage;       // non-null when succeeded == false

        private FetchedResult(java.util.UUID internalJobId, boolean succeeded,
                              LlmJobResult result, String errorMessage) {
            this.internalJobId = internalJobId;
            this.succeeded = succeeded;
            this.result = result;
            this.errorMessage = errorMessage;
        }

        public static FetchedResult success(java.util.UUID id, LlmJobResult r) {
            return new FetchedResult(id, true, r, null);
        }

        public static FetchedResult failure(java.util.UUID id, String error) {
            return new FetchedResult(id, false, null, error);
        }
    }
}
