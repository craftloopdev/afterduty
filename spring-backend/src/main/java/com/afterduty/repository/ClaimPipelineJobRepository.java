package com.afterduty.repository;

import com.afterduty.model.ClaimPipelineJob;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

@Repository
public interface ClaimPipelineJobRepository extends JpaRepository<ClaimPipelineJob, Long> {

    List<ClaimPipelineJob> findByClaimIdAndStage(Long claimId, String stage);

    /** Count pipeline jobs for any of the given stages — used to detect whether extraction has previously run. */
    long countByClaimIdAndStageIn(Long claimId, Collection<String> stages);

    @Query("SELECT cpj.llmJobId FROM ClaimPipelineJob cpj WHERE cpj.claimId = :claimId AND cpj.stage = :stage")
    List<UUID> findJobIdsByClaimIdAndStage(@Param("claimId") Long claimId, @Param("stage") String stage);

    @Modifying
    @Transactional
    @Query("DELETE FROM ClaimPipelineJob cpj WHERE cpj.claimId = :claimId AND cpj.stage = :stage")
    int deleteByClaimIdAndStage(@Param("claimId") Long claimId, @Param("stage") String stage);

    @Modifying
    @Transactional
    @Query("DELETE FROM ClaimPipelineJob cpj WHERE cpj.claimId = :claimId")
    int deleteByClaimId(@Param("claimId") Long claimId);
}
