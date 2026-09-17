package com.afterduty.eval;

import com.afterduty.model.Atom;
import com.afterduty.model.Claim;
import com.afterduty.model.EvidenceItem;
import com.afterduty.model.IdentifiedCondition;
import com.afterduty.service.llm.FakeLlmAsyncProvider;

import java.util.List;

/**
 * Immutable snapshot of the pipeline's state after a phase reaches a terminal
 * state (spec §2.2). The {@link DeterministicScorer} and {@link EndStateDigest}
 * both read exclusively from this — nothing reaches back into the DB.
 *
 * @param outcome           overall outcome label: {@code complete},
 *                          {@code complete_zero_conditions}, or {@code failed}
 * @param claim             reloaded claim after the phase
 * @param activeConditions  the ONE active generation (superseded_by IS NULL)
 * @param allConditionRows  every condition row (active + superseded) for supersede
 *                          atomicity assertions
 * @param liveAtoms         live (non-superseded) atoms feeding the rating prompt
 * @param evidence          the claim's evidence items (processing status checks)
 * @param submittedJobs     the fake's full submit ledger across the WHOLE run so
 *                          far (cumulative — phase deltas are computed by diffing
 *                          against a pre-phase snapshot count)
 */
public record PipelineEndState(
        String outcome,
        Claim claim,
        List<IdentifiedCondition> activeConditions,
        List<IdentifiedCondition> allConditionRows,
        List<Atom> liveAtoms,
        List<EvidenceItem> evidence,
        List<FakeLlmAsyncProvider.SubmittedJob> submittedJobs
) {

    public static final String OUTCOME_COMPLETE = "complete";
    public static final String OUTCOME_COMPLETE_ZERO = "complete_zero_conditions";
    public static final String OUTCOME_FAILED = "failed";

    /** Distinct LLM jobs of a purpose submitted across the whole run (by internal job id). */
    public long distinctJobs(String purpose) {
        return submittedJobs.stream()
                .filter(j -> purpose.equals(j.purpose()))
                .map(FakeLlmAsyncProvider.SubmittedJob::internalJobId)
                .distinct().count();
    }

    /** Distinct LLM jobs of a purpose submitted DURING this phase (since a prior count). */
    public long distinctJobsSince(String purpose, long priorCount) {
        return distinctJobs(purpose) - priorCount;
    }

    public int activeCount() {
        return activeConditions == null ? 0 : activeConditions.size();
    }
}
