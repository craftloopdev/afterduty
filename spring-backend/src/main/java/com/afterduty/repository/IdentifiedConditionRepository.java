package com.afterduty.repository;

import com.afterduty.model.IdentifiedCondition;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Repository for IdentifiedCondition. Used by the state machines to fan out
 * per-condition LLM jobs and to read/write gap + whatif results.
 */
@Repository
public interface IdentifiedConditionRepository extends JpaRepository<IdentifiedCondition, Long> {

    List<IdentifiedCondition> findByClaimId(Long claimId);

    /**
     * Mission 5b — active-generation reader. After a synthesis run completes it
     * marks the PRIOR generation's conditions {@code superseded_by} (kept for
     * citation history), so every analysis/gap/chat/count path must read only the
     * live generation. This is the live-only mirror of {@link #findByClaimId}; the
     * unfiltered variant remains for the teardown/maintenance paths that
     * legitimately need every generation's rows. The {@code superseded_by IS NULL}
     * filter is a no-op when nothing was ever superseded (flag OFF), so callers can
     * switch to it unconditionally.
     */
    List<IdentifiedCondition> findByClaimIdAndSupersededByIsNull(Long claimId);

    /**
     * Mission 5b — in-run (pending) generation reader. While a synthesis run is
     * mid-flight (RATING/VERIFYING) the new generation's rows are written with a
     * PENDING marker in {@code superseded_by} so external readers (which filter
     * {@code superseded_by IS NULL}) still see the PRIOR generation — there is no
     * window where a veteran sees a half-rated new generation. The synthesis
     * stages themselves operate on the pending rows via this finder; at COMPLETE
     * they are flipped to null (activated) in the same transaction that retires
     * the prior generation. Used only with the incremental flag ON.
     */
    List<IdentifiedCondition> findByClaimIdAndSupersededBy(Long claimId, Long supersededBy);

    void deleteByClaimId(Long claimId);
}
