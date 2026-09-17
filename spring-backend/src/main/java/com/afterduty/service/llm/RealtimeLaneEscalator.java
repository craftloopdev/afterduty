package com.afterduty.service.llm;

import com.afterduty.model.IdentifiedCondition;
import com.afterduty.model.LlmJob;
import com.afterduty.repository.ClaimPipelineJobRepository;
import com.afterduty.repository.IdentifiedConditionRepository;
import com.afterduty.service.synthesis.ConditionGenerationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

/**
 * Phase B item B2, report §5 item 3 — route by dirty-count: when a RE-RUN's
 * dirty-condition count is ≤ {@code realtime-dirty-threshold} (default 3), the
 * run's rate/verify/gap jobs must ride the REALTIME Claude lane
 * ({@code vertex-anthropic}), never {@code anthropic-batch} — a veteran's fresh
 * nexus-letter upload must not vanish into a 24-hour batch SLA. Both backend
 * proposals agreed on this rule verbatim; the FULL-run path (first analysis, or a
 * re-run that dirties more than the threshold) keeps its configured lanes.
 *
 * <p>This is the previously-uncalled lane-decision hook made real: the request-
 * level {@code preferredProvider} override exists but nothing pipeline-side could
 * use it, because at rate-job submit time the run's total dirty count is not yet
 * known (jobs are submitted one-by-one inside the classification loop). The
 * decision therefore lives at SUBMITTER time: {@link LlmJobSubmitter} consults
 * this component just before handing QUEUED jobs to a provider — by then the
 * whole run's rate fan-out is committed, so the dirty count is exact.
 *
 * <p>Signals (all read-only, derived from committed pipeline state):
 * <ul>
 *   <li><b>dirty count</b> — the {@code synthesis_rate} pipeline-job rows for the
 *       claim. The classification loop creates exactly one per DIRTY condition
 *       (clean carry-forwards submit none) and they persist until the NEXT run's
 *       rate stage clears them, so the same count serves the rate, verify, and
 *       gap stages of one run.</li>
 *   <li><b>re-run detection</b> — during RATING/VERIFYING a re-run has BOTH a
 *       pending generation (PENDING_MARKER rows) and a still-active prior one;
 *       after the flip (gap stage) a prior run's rows carry real superseded
 *       pointers (replacement id or tombstone). A first-ever analysis shows
 *       neither ⇒ full-run path ⇒ configured lanes.</li>
 * </ul>
 *
 * <p>Escalation only ever moves a job OFF {@code anthropic-batch} onto
 * {@code vertex-anthropic} with the model unchanged (same model id, realtime
 * endpoint) — it never touches jobs already on a realtime lane, other purposes,
 * or claims mid-full-run. Rollback lever: set
 * {@code va-claim.llm.realtime-dirty-threshold} (REALTIME_DIRTY_THRESHOLD) to 0.
 */
@Component
public class RealtimeLaneEscalator {

    private static final Logger log = LoggerFactory.getLogger(RealtimeLaneEscalator.class);

    /** The per-condition fan-out purposes the ≤N-dirty realtime rule covers. */
    static final Set<String> SCOPED_PURPOSES = Set.of(
            "synthesis_rate", "synthesis_verify",
            "gap_evidence", "gap_validation", "gap_whatif");

    private final ClaimPipelineJobRepository pipelineJobRepository;
    private final IdentifiedConditionRepository conditionRepository;

    /**
     * A re-run whose dirty count is ≤ this rides the realtime lane. 0 (or
     * negative) disables escalation entirely — the rollback lever.
     */
    @Value("${va-claim.llm.realtime-dirty-threshold:3}")
    private int dirtyThreshold = 3;

    public RealtimeLaneEscalator(ClaimPipelineJobRepository pipelineJobRepository,
                                 IdentifiedConditionRepository conditionRepository) {
        this.pipelineJobRepository = pipelineJobRepository;
        this.conditionRepository = conditionRepository;
    }

    /**
     * Escalate a QUEUED job to the realtime Claude lane when the small-re-run rule
     * applies. Mutates the (managed) job row's provider in place; the caller's
     * transaction persists it. Returns true when escalated.
     */
    public boolean maybeEscalate(LlmJob job) {
        if (dirtyThreshold <= 0) return false;
        if (!AnthropicBatchProvider.NAME.equals(job.getProvider())) return false;
        if (job.getPurpose() == null || !SCOPED_PURPOSES.contains(job.getPurpose())) return false;
        Long claimId = job.getClaimId();
        if (claimId == null) return false;

        if (!isRerun(claimId)) return false; // full-run path keeps its configured lanes

        int dirty = pipelineJobRepository.findByClaimIdAndStage(claimId, "synthesis_rate").size();
        if (dirty > dirtyThreshold) return false;

        job.setProvider(VertexAnthropicProvider.NAME);
        log.info("[lane] claim {} re-run has {} dirty condition(s) (≤{}) — escalating {} job {} "
                        + "from {} to realtime {}",
                claimId, dirty, dirtyThreshold, job.getPurpose(), job.getId(),
                AnthropicBatchProvider.NAME, VertexAnthropicProvider.NAME);
        return true;
    }

    /**
     * A claim is mid-RE-RUN (not a first-ever analysis) when either a pending new
     * generation coexists with a still-active prior one (rate/verify window), or a
     * completed flip left real superseded pointers behind (gap window and beyond).
     * Condition counts per claim are small; one fetch and an in-memory scan keeps
     * this free of new repository surface.
     */
    private boolean isRerun(Long claimId) {
        List<IdentifiedCondition> all = conditionRepository.findByClaimId(claimId);
        boolean pending = false;
        boolean active = false;
        boolean realSuperseded = false;
        for (IdentifiedCondition c : all) {
            Long s = c.getSupersededBy();
            if (s == null) {
                active = true;
            } else if (s == ConditionGenerationService.PENDING_MARKER) {
                pending = true;
            } else {
                realSuperseded = true; // replacement pointer or tombstone
            }
        }
        return (pending && active) || (!pending && realSuperseded);
    }

    /** Test seam (Spring injects via @Value in production). */
    void setDirtyThreshold(int dirtyThreshold) {
        this.dirtyThreshold = dirtyThreshold;
    }
}
