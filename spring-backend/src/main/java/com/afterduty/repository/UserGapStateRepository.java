package com.afterduty.repository;

import com.afterduty.model.UserGapState;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * P1-6 — durable user-set gap statuses (see {@link UserGapState}). The
 * two Optional finders together implement the natural-key lookup for the
 * upsert: a NULL {@code triad_leg} needs the dedicated IsNull variant
 * because a derived {@code = NULL} predicate never matches in SQL.
 */
@Repository
public interface UserGapStateRepository extends JpaRepository<UserGapState, Long> {

    List<UserGapState> findByClaimIdAndIdentityFingerprint(Long claimId, String identityFingerprint);

    List<UserGapState> findByClaimIdAndIdentityFingerprintAndStatus(
            Long claimId, String identityFingerprint, String status);

    Optional<UserGapState> findByClaimIdAndIdentityFingerprintAndGapTypeAndTriadLeg(
            Long claimId, String identityFingerprint, String gapType, String triadLeg);

    Optional<UserGapState> findByClaimIdAndIdentityFingerprintAndGapTypeAndTriadLegIsNull(
            Long claimId, String identityFingerprint, String gapType);
}
