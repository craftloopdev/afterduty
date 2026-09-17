package com.afterduty.repository;

import com.afterduty.model.ConditionSuppression;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ConditionSuppressionRepository extends JpaRepository<ConditionSuppression, Long> {

    /** Active (unlifted) suppressions for a claim — the post-filter set. */
    List<ConditionSuppression> findByClaimIdAndLiftedAtIsNull(Long claimId);

    /** The newest unlifted suppression for one condition row — the undo target. */
    Optional<ConditionSuppression> findFirstByClaimIdAndConditionIdAndLiftedAtIsNullOrderByIdDesc(
            Long claimId, Long conditionId);
}
