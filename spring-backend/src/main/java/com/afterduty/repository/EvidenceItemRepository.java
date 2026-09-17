package com.afterduty.repository;

import com.afterduty.model.EvidenceItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Repository for EvidenceItem. Used by ExtractionStateMachine to load evidence
 * items and fan out per-evidence LLM jobs.
 */
@Repository
public interface EvidenceItemRepository extends JpaRepository<EvidenceItem, Long> {

    List<EvidenceItem> findByClaimId(Long claimId);

    List<EvidenceItem> findByClaimIdAndProcessingStatus(Long claimId, String processingStatus);

    long countByClaimIdAndProcessingStatusIn(Long claimId, java.util.Collection<String> statuses);
}
