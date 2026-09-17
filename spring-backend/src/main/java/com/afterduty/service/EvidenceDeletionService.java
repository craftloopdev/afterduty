package com.afterduty.service;

import com.afterduty.model.Claim;
import com.afterduty.model.EvidenceItem;
import com.afterduty.repository.AtomRepository;
import com.afterduty.repository.ChunkRepository;
import com.afterduty.repository.ClaimRepository;
import com.afterduty.repository.EvidenceRepository;
import com.afterduty.repository.MedicalEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Removes one document's rows as a unit: its atoms, its medical events, its RAG
 * chunks, and the evidence row itself, then flags the claim so the next scheduler
 * tick re-runs synthesis and gap analysis without the document's facts.
 *
 * <p>Why a service: the controller used to call {@code atomRepository.deleteByEvidenceId}
 * directly. A Spring Data <em>derived</em> delete loads each entity and calls
 * {@code EntityManager.remove}, which requires an active transaction — and a
 * controller method has none. Every delete of an analyzed document therefore
 * failed with {@code InvalidDataAccessApiUsageException: No EntityManager with
 * actual transaction available} (observed 2026-09-13, five attempts on one
 * document), while a never-analyzed document (no atoms to remove) deleted fine —
 * which is why it looked intermittent.
 *
 * <p>Medical events and chunks were never deleted before, so a removed document's
 * facts kept feeding synthesis and chat retrieval. They go with it now.
 */
@Service
public class EvidenceDeletionService {

    private static final Logger log = LoggerFactory.getLogger(EvidenceDeletionService.class);

    private final AtomRepository atomRepository;
    private final MedicalEventRepository medicalEventRepository;
    private final ChunkRepository chunkRepository;
    private final EvidenceRepository evidenceRepository;
    private final ClaimRepository claimRepository;

    public EvidenceDeletionService(AtomRepository atomRepository,
                                   MedicalEventRepository medicalEventRepository,
                                   ChunkRepository chunkRepository,
                                   EvidenceRepository evidenceRepository,
                                   ClaimRepository claimRepository) {
        this.atomRepository = atomRepository;
        this.medicalEventRepository = medicalEventRepository;
        this.chunkRepository = chunkRepository;
        this.evidenceRepository = evidenceRepository;
        this.claimRepository = claimRepository;
    }

    /**
     * Deletes the document's DB rows atomically and marks the claim for re-analysis.
     * The caller removes the stored file only after this returns, so a failure here
     * never leaves a row pointing at a missing object.
     */
    @Transactional
    public void deleteEvidenceRows(Claim claim, EvidenceItem evidence) {
        Long evidenceId = evidence.getId();
        atomRepository.deleteByEvidenceId(evidenceId);
        medicalEventRepository.deleteByEvidenceId(evidenceId);
        chunkRepository.deleteByEvidenceId(evidenceId);
        evidenceRepository.delete(evidence);

        // Evidence set changed — force the scheduler to re-run synthesis and gap
        // analysis on the next tick (AnalysisScheduler treats a null timestamp as
        // "never run").
        claim.setLastSynthesisAt(null);
        claim.setLastGapAnalysisAt(null);
        claimRepository.save(claim);
        log.info("Deleted evidence {} and its derived rows for claim {}", evidenceId, claim.getId());
    }
}
