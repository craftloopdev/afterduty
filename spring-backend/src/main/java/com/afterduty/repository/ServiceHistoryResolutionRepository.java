package com.afterduty.repository;

import com.afterduty.model.ServiceHistoryResolution;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ServiceHistoryResolutionRepository extends JpaRepository<ServiceHistoryResolution, Long> {

    /** All of ONE user's persisted LLM resolutions — the reconciler loads these
     *  to apply below any veteran override. Filtered by userId (never cross-user). */
    List<ServiceHistoryResolution> findByUserId(Long userId);

    /** One resolution for (user, cluster) — the pipeline upserts (overwrite-by-key). */
    Optional<ServiceHistoryResolution> findByUserIdAndClusterKey(Long userId, String clusterKey);
}
