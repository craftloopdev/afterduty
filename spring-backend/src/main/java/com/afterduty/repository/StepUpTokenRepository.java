package com.afterduty.repository;

import com.afterduty.model.StepUpToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Optional;

/**
 * Spring Data repo for {@link StepUpToken} (auth program P1.2). Lookups are by
 * token hash (never plaintext). Validation + single-use consume live in
 * {@code StepUpService}.
 */
@Repository
public interface StepUpTokenRepository extends JpaRepository<StepUpToken, Long> {

    /** Validation lookup: the (unique) live row for this token hash, if any. */
    Optional<StepUpToken> findByTokenHash(String tokenHash);

    /** Opportunistic sweep of long-dead rows (no scheduler exists; sweep at mint). */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from StepUpToken t where t.expiresAt < :cutoff")
    int deleteExpiredBefore(@Param("cutoff") Instant cutoff);
}
