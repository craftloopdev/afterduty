package com.afterduty.repository;

import com.afterduty.model.Claim;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

public interface ClaimRepository extends JpaRepository<Claim, Long> {
    List<Claim> findByUserIdOrderByCreatedAtDesc(Long userId);
    Optional<Claim> findByIdAndUserId(Long id, Long userId);
    Optional<Claim> findFirstByUserIdAndClaimType(Long userId, Claim.ClaimType claimType);

    /**
     * Atomically claim the synthesis lock for a claim. Returns 1 if we got
     * the lock, 0 if someone else already has it. Use in the scheduler so
     * two Cloud Run instances don't double-run synthesis for the same claim.
     */
    @Transactional
    @Modifying
    @Query("update Claim c set c.synthesisInProgress = true where c.id = ?1 and (c.synthesisInProgress is null or c.synthesisInProgress = false)")
    int tryAcquireSynthesisLock(Long claimId);

    @Transactional
    @Modifying
    @Query("update Claim c set c.gapAnalysisInProgress = true where c.id = ?1 and (c.gapAnalysisInProgress is null or c.gapAnalysisInProgress = false)")
    int tryAcquireGapAnalysisLock(Long claimId);
}
