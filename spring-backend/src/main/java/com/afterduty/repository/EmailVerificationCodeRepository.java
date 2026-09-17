package com.afterduty.repository;

import com.afterduty.model.EmailVerificationCode;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Spring Data repo for {@link EmailVerificationCode} — the §3.4 / §3.6 queries
 * ported from VolunTails' {@code _mint_and_send_email_code} /
 * {@code _verify_email_code_row}.
 *
 * <p>Postgres-only locking ({@code pg_advisory_xact_lock}, {@code SELECT … FOR
 * UPDATE}) is handled in {@code EmailCodeService} with dialect-conditional native
 * SQL so this repo stays portable to H2 (tests).
 */
@Repository
public interface EmailVerificationCodeRepository extends JpaRepository<EmailVerificationCode, Long> {

    /** Newest row for this email — backs the resend cooldown read. */
    Optional<EmailVerificationCode> findFirstByEmailOrderByCreatedAtDesc(String email);

    /** Per-email hourly cap: count rows for this email since {@code since}. */
    long countByEmailAndCreatedAtGreaterThanEqual(String email, Instant since);

    /** Per-IP hourly cap: count rows from this IP since {@code since}. */
    long countByRequestIpAndCreatedAtGreaterThanEqual(String requestIp, Instant since);

    /**
     * Single-live-code burn: mark every unconsumed row for this email consumed so
     * an attacker can't stack multiple live codes (keeps brute force at
     * ≤5 guesses / 10 min / email).
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update EmailVerificationCode e set e.consumedAt = :now "
            + "where e.email = :email and e.consumedAt is null")
    int burnUnconsumedForEmail(@Param("email") String email, @Param("now") Instant now);

    /** Opportunistic sweep of long-dead rows (no scheduler exists; sweep at write). */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from EmailVerificationCode e where e.expiresAt < :cutoff")
    int deleteExpiredBefore(@Param("cutoff") Instant cutoff);

    /**
     * Verify lookup (§3.6): the latest unconsumed code for {@code email} whose
     * purpose matches the requested flow OR is NULL (legacy grace). Returns a list
     * so the caller takes {@code .get(0)} after a {@link Limit#of(int)} of 1;
     * Postgres {@code FOR UPDATE} is layered on top in the service via native SQL.
     */
    @Query("select e from EmailVerificationCode e "
            + "where e.email = :email and e.consumedAt is null "
            + "and (e.purpose = :purpose or e.purpose is null) "
            + "order by e.createdAt desc")
    List<EmailVerificationCode> findLiveForVerify(@Param("email") String email,
                                                  @Param("purpose") String purpose,
                                                  Limit limit);
}
