package com.afterduty.repository;

import com.afterduty.model.Atom;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.List;

public interface AtomRepository extends JpaRepository<Atom, Long> {
    List<Atom> findByClaimId(Long claimId);
    List<Atom> findByEvidenceIdOrderByCreatedAtAsc(Long evidenceId);
    long countByClaimId(Long claimId);
    long countByEvidenceId(Long evidenceId);
    void deleteByClaimId(Long claimId);
    void deleteByEvidenceId(Long evidenceId);

    @Query("select max(a.createdAt) from Atom a where a.claimId = ?1")
    Instant findLatestCreatedAtByClaimId(Long claimId);

    @Query("select count(a) from Atom a where a.claimId = ?1 and a.createdAt > ?2")
    long countByClaimIdAndCreatedAtAfter(Long claimId, Instant after);

    // -------------------------------------------------------------------------
    // Live-atom (non-superseded) variants — Mission 5a incremental extraction.
    //
    // When a document is re-extracted, its prior atoms are marked
    // {@code superseded_by} (the replacing extract run's marker) instead of
    // deleted, so chat history / citations into prior runs stay valid. Every
    // path that builds analysis/chat context, drives the scheduler trigger, or
    // surfaces an atom count to the veteran must read ONLY live atoms — else a
    // re-extraction would double-count facts or feed the model stale + new
    // copies of the same evidence. These are the live-only mirrors of the
    // readers above; the unfiltered findByClaimId/countByClaimId remain for the
    // few writer/maintenance paths (delete-by-evidence, full-claim teardown)
    // that legitimately need every row.
    // -------------------------------------------------------------------------

    List<Atom> findByClaimIdAndSupersededByIsNull(Long claimId);

    List<Atom> findByEvidenceIdAndSupersededByIsNullOrderByCreatedAtAsc(Long evidenceId);

    long countByClaimIdAndSupersededByIsNull(Long claimId);

    long countByEvidenceIdAndSupersededByIsNull(Long evidenceId);

    @Query("select max(a.createdAt) from Atom a where a.claimId = ?1 and a.supersededBy is null")
    Instant findLatestCreatedAtByClaimIdAndSupersededByIsNull(Long claimId);

    /**
     * Mark every still-live atom for one piece of evidence as superseded by the
     * given marker, in a single bulk UPDATE. Called when that document is being
     * re-extracted: the prior generation's atoms are retired BEFORE the new ones
     * are persisted, atomically in the same transaction, so no reader ever sees
     * both generations at once. Already-superseded atoms are left untouched
     * (their original marker is preserved). Returns the number of rows retired.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update Atom a set a.supersededBy = ?2 where a.evidenceId = ?1 and a.supersededBy is null")
    int supersedeLiveAtomsForEvidence(Long evidenceId, Long marker);
}
