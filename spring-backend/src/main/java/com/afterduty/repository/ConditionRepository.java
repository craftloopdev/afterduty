package com.afterduty.repository;

import com.afterduty.model.IdentifiedCondition;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface ConditionRepository extends JpaRepository<IdentifiedCondition, Long> {
    List<IdentifiedCondition> findByClaimId(Long claimId);

    /**
     * Mission 5b — active-generation reader. The live-only mirror of
     * {@link #findByClaimId}: returns only conditions whose {@code superseded_by}
     * is null (the current generation). Every user-facing reader (home counts,
     * conditions/gaps APIs, chat grounding) uses this so a re-analysis transition
     * never shows a veteran the prior generation's rows. A no-op filter when
     * nothing was superseded (incremental flag OFF).
     *
     * <p>P1-14 — additionally post-filters chat-suppressed identities: a condition
     * whose {@code identity_fingerprint} has an unlifted row in
     * {@code condition_suppressions} (the veteran removed it via chat) stays
     * excluded even when a later identify run re-emits it with
     * {@code superseded_by = null} — deletions no longer resurrect on the next
     * upload. Deterministic SQL against the suppression table; the identify
     * prompt is NOT involved. Fingerprint-less rows (legacy/flag OFF) are
     * unaffected by the subquery; their suppression is carried by the
     * {@code SUPPRESSED_MARKER} sentinel in {@code superseded_by} instead
     * (see ChatAgent).
     */
    @Query("""
            select c from IdentifiedCondition c
            where c.claimId = :claimId
              and c.supersededBy is null
              and (c.identityFingerprint is null or not exists (
                  select 1 from ConditionSuppression s
                  where s.claimId = :claimId
                    and s.liftedAt is null
                    and s.identityFingerprint = c.identityFingerprint))
            """)
    List<IdentifiedCondition> findByClaimIdAndSupersededByIsNull(@Param("claimId") Long claimId);

    void deleteByClaimId(Long claimId);

    /**
     * Owner-scoped lookup (VCP-AUTHZ-02): only conditions whose claim belongs to {@code userId}.
     * Traverses the {@code claim} relationship to {@code Claim.userId}. Use this instead of the
     * inherited {@code findAllById} whenever the caller supplies condition ids, so a user cannot
     * resolve another veteran's conditions.
     */
    List<IdentifiedCondition> findByIdInAndClaim_UserId(List<Long> ids, Long userId);
}
