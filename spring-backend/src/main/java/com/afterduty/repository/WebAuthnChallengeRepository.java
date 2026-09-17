package com.afterduty.repository;

import com.afterduty.model.WebAuthnChallenge;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Optional;

/**
 * Spring Data repo for {@link WebAuthnChallenge} (auth program P1.3). The finish
 * step looks the in-flight ceremony up by its (unique) challenge and DELETES it
 * on consume (single-use). No scheduler exists, so a mint-time opportunistic
 * sweep drops long-dead rows.
 */
@Repository
public interface WebAuthnChallengeRepository extends JpaRepository<WebAuthnChallenge, Long> {

    /** Finish lookup: the (unique live) row for this challenge, if any. */
    Optional<WebAuthnChallenge> findByChallenge(String challenge);

    /** Opportunistic sweep of long-dead rows (no scheduler exists; sweep at mint). */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from WebAuthnChallenge c where c.expiresAt < :cutoff")
    int deleteExpiredBefore(@Param("cutoff") Instant cutoff);
}
