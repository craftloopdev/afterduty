package com.afterduty.repository;

import com.afterduty.model.DeviceCredential;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Spring Data repo for {@link DeviceCredential} (auth program P1.4, the B2
 * device-bound-token contract). Lookups are by secret hash (exchange verify),
 * by user id (the manage list), and by row id scoped to a user (revoke).
 */
@Repository
public interface DeviceCredentialRepository extends JpaRepository<DeviceCredential, Long> {

    /**
     * Exchange verify: the device credential row for this secret hash. The
     * caller still does a constant-time hash compare + revoked/expiry checks on
     * the result (the indexed lookup is convenience, not the security boundary).
     */
    Optional<DeviceCredential> findByDeviceSecretHash(String deviceSecretHash);

    /** Manage list: a user's device credentials (both live and revoked). */
    List<DeviceCredential> findByUserId(Long userId);

    /** Manage list (live only): a user's non-revoked device credentials. */
    List<DeviceCredential> findByUserIdAndRevokedAtIsNull(Long userId);

    /** Revoke: fetch one of a user's device credentials by row id (ownership-scoped). */
    Optional<DeviceCredential> findByIdAndUserId(Long id, Long userId);

    /**
     * Recovery (auth program P1.5): revoke ALL of a user's still-live device
     * credentials in one statement (soft revoke — set {@code revoked_at}) so a
     * fresh factor-2 recovery forces biometric re-enrollment on every device.
     * Only touches non-revoked rows so an already-revoked row keeps its original
     * {@code revoked_at}. Returns the row count revoked (0 if none were live).
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE DeviceCredential d SET d.revokedAt = :now "
            + "WHERE d.userId = :userId AND d.revokedAt IS NULL")
    int revokeAllForUser(@Param("userId") Long userId, @Param("now") Instant now);
}
