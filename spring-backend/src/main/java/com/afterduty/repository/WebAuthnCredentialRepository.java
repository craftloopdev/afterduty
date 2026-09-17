package com.afterduty.repository;

import com.afterduty.model.WebAuthnCredential;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Spring Data repo for {@link WebAuthnCredential} (auth program P1.3). Backs the
 * RP's {@code CredentialRepositoryV2}. Lookups are by credential id (assertion
 * verify), by user handle (assertion resolve), and by user id (register
 * excludeCredentials + the manage list).
 */
@Repository
public interface WebAuthnCredentialRepository extends JpaRepository<WebAuthnCredential, Long> {

    /** Assertion verify: the (unique) credential row for this credential id. */
    Optional<WebAuthnCredential> findByCredentialId(String credentialId);

    /** Assertion resolve: all credentials sharing a user handle. */
    List<WebAuthnCredential> findByUserHandle(String userHandle);

    /** Register excludeCredentials + the manage list: a user's credentials. */
    List<WebAuthnCredential> findByUserId(Long userId);

    /** Manage: fetch one of a user's credentials by row id (ownership-scoped). */
    Optional<WebAuthnCredential> findByIdAndUserId(Long id, Long userId);

    boolean existsByCredentialId(String credentialId);

    /**
     * Recovery (auth program P1.5): delete ALL of a user's passkeys in one
     * statement so a fresh factor-2 recovery forces re-enrollment of every
     * passkey. A passkey carries only public material and is HARD-deleted
     * (there is no {@code revoked_at} column — that's the WebAuthn design), so
     * this is the revoke-all path the recovery contract calls. Returns the row
     * count deleted (0 if the user had none).
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("DELETE FROM WebAuthnCredential c WHERE c.userId = :userId")
    int deleteByUserId(@Param("userId") Long userId);
}
