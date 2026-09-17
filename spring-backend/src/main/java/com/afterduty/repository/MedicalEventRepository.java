package com.afterduty.repository;

import com.afterduty.model.MedicalEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface MedicalEventRepository extends JpaRepository<MedicalEvent, Long> {
    List<MedicalEvent> findByClaimId(Long claimId);
    List<MedicalEvent> findByEvidenceId(Long evidenceId);
    List<MedicalEvent> findByClaimIdAndExtracted(Long claimId, Boolean extracted);
    long countByClaimId(Long claimId);
    long countByEvidenceId(Long evidenceId);
    void deleteByEvidenceId(Long evidenceId);
}
