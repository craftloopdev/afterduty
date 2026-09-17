package com.afterduty.repository;

import com.afterduty.model.ServiceHistoryOverride;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

public interface ServiceHistoryOverrideRepository extends JpaRepository<ServiceHistoryOverride, Long> {

    /** All of ONE user's overrides — the reconciler loads these to re-apply
     *  corrections at top authority. Filtered by userId (never cross-user). */
    List<ServiceHistoryOverride> findByUserId(Long userId);

    /** One override for (user, cluster) — the upsert path reads then updates. */
    Optional<ServiceHistoryOverride> findByUserIdAndClusterKey(Long userId, String clusterKey);

    /** Clear ONE user's override for a cluster (the DELETE endpoint). Scoped to
     *  userId so it can only ever remove the owner's row. */
    @Transactional
    @Modifying
    @Query("delete from ServiceHistoryOverride o where o.userId = ?1 and o.clusterKey = ?2")
    int deleteByUserIdAndClusterKey(Long userId, String clusterKey);
}
