package com.afterduty.repository;

import com.afterduty.model.EvidenceItem;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface EvidenceRepository extends JpaRepository<EvidenceItem, Long> {
    List<EvidenceItem> findByClaimIdOrderByCreatedAt(Long claimId);
    Optional<EvidenceItem> findByIdAndClaimId(Long id, Long claimId);
    long countByClaimId(Long claimId);
    boolean existsByClaimIdAndFileHash(Long claimId, String fileHash);
    long countByClaimIdAndProcessingStatusIn(Long claimId, List<String> statuses);

    /** Used by UsageResetJob to find evidence deferred at the monthly cap. */
    List<EvidenceItem> findByProcessingStatus(String processingStatus);
}
