package com.afterduty.repository;

import com.afterduty.model.LlmJob;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface LlmJobRepository extends JpaRepository<LlmJob, UUID> {

    /**
     * Picks up to {@code limit} QUEUED jobs and locks them so the submitter
     * worker on a sibling instance can't grab the same row. Postgres uses
     * SELECT ... FOR UPDATE SKIP LOCKED under PESSIMISTIC_WRITE.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT j FROM LlmJob j WHERE j.status = com.afterduty.model.LlmJob.Status.QUEUED " +
           "ORDER BY j.createdAt ASC")
    List<LlmJob> findQueuedForSubmit(org.springframework.data.domain.Pageable pageable);

    @Query("SELECT j FROM LlmJob j WHERE j.status IN " +
           "(com.afterduty.model.LlmJob.Status.SUBMITTED, com.afterduty.model.LlmJob.Status.IN_PROGRESS) " +
           "ORDER BY j.lastPolledAt ASC NULLS FIRST")
    List<LlmJob> findActiveJobs();

    /** Distinct provider job IDs (Anthropic batch IDs etc.) we should poll. */
    @Query("SELECT DISTINCT j.providerJobId FROM LlmJob j WHERE j.status IN " +
           "(com.afterduty.model.LlmJob.Status.SUBMITTED, com.afterduty.model.LlmJob.Status.IN_PROGRESS) " +
           "AND j.providerJobId IS NOT NULL")
    List<String> findActiveProviderJobIds();

    @Query("SELECT j FROM LlmJob j WHERE j.providerJobId = :providerJobId AND j.status IN " +
           "(com.afterduty.model.LlmJob.Status.SUBMITTED, com.afterduty.model.LlmJob.Status.IN_PROGRESS)")
    List<LlmJob> findActiveByProviderJobId(@Param("providerJobId") String providerJobId);

    /**
     * Jobs that were SUBMITTED before {@code deadline} and never made it to a
     * terminal status — assume the worker holding them died, re-queue.
     */
    @Query("SELECT j FROM LlmJob j WHERE j.status = com.afterduty.model.LlmJob.Status.SUBMITTED " +
           "AND j.submittedAt < :deadline")
    List<LlmJob> findOrphanedSubmissions(@Param("deadline") Instant deadline);

    /**
     * Jobs the provider has reported IN_PROGRESS since before {@code deadline}. On a
     * realtime provider that is a dead worker thread; the poller decides per provider.
     */
    @Query("SELECT j FROM LlmJob j WHERE j.status = com.afterduty.model.LlmJob.Status.IN_PROGRESS " +
           "AND j.submittedAt < :deadline")
    List<LlmJob> findStalledInProgress(@Param("deadline") Instant deadline);

    @Query("SELECT COUNT(j) FROM LlmJob j WHERE j.id IN :ids AND j.status = " +
           "com.afterduty.model.LlmJob.Status.SUCCEEDED")
    long countSucceeded(@Param("ids") Collection<UUID> ids);

    @Query("SELECT COUNT(j) FROM LlmJob j WHERE j.id IN :ids AND j.status IN " +
           "(com.afterduty.model.LlmJob.Status.SUCCEEDED, com.afterduty.model.LlmJob.Status.FAILED)")
    long countTerminal(@Param("ids") Collection<UUID> ids);

    Optional<LlmJob> findFirstByProviderJobId(String providerJobId);

    @Modifying
    @Transactional
    @Query("UPDATE LlmJob j SET j.lastPolledAt = :now WHERE j.id IN :ids")
    int touchPolled(@Param("ids") Collection<UUID> ids, @Param("now") Instant now);
}
