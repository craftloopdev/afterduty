package com.afterduty.repository;

import com.afterduty.model.PipelineMetrics;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PipelineMetricsRepository extends JpaRepository<PipelineMetrics, Long> {
    List<PipelineMetrics> findByClaimId(Long claimId);
    List<PipelineMetrics> findByClaimIdOrderByRunTimestampDesc(Long claimId);
}
