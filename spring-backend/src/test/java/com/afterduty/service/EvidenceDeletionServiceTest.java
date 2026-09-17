package com.afterduty.service;

import com.afterduty.model.Atom;
import com.afterduty.model.Chunk;
import com.afterduty.model.Claim;
import com.afterduty.model.EvidenceItem;
import com.afterduty.model.MedicalEvent;
import com.afterduty.repository.AtomRepository;
import com.afterduty.repository.ChunkRepository;
import com.afterduty.repository.ClaimRepository;
import com.afterduty.repository.EvidenceRepository;
import com.afterduty.repository.MedicalEventRepository;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Deleting a document must remove everything derived from it, and must do so on
 * the real request path — a controller with NO ambient transaction. Every other
 * {@code @DataJpaTest} runs inside a test-managed transaction, which is exactly
 * what hid the production failure: a derived {@code deleteByEvidenceId} outside a
 * transaction throws {@code InvalidDataAccessApiUsageException}. The key test here
 * disables that ambient transaction, so the service must open its own.
 */
@Tag("regression")
@DataJpaTest
@Import(EvidenceDeletionService.class)
class EvidenceDeletionServiceTest {

    @Autowired EvidenceDeletionService service;
    @Autowired ClaimRepository claimRepository;
    @Autowired EvidenceRepository evidenceRepository;
    @Autowired AtomRepository atomRepository;
    @Autowired MedicalEventRepository medicalEventRepository;
    @Autowired ChunkRepository chunkRepository;

    private record Seed(Claim claim, EvidenceItem evidence) {}

    /** A claim with one analyzed document: two atoms, one medical event, one RAG chunk. */
    private Seed seedAnalyzedDocument() {
        Claim claim = claimRepository.save(Claim.builder()
                .userId(7L).status(Claim.ClaimStatus.EXTRACTING).build());
        claim.setLastSynthesisAt(Instant.now());
        claim.setLastGapAnalysisAt(Instant.now());
        claim = claimRepository.save(claim);

        EvidenceItem evidence = evidenceRepository.save(EvidenceItem.builder()
                .claimId(claim.getId()).sourceType("pdf").filename("dbq.pdf")
                .processingStatus("processed").build());
        seedDerivedRows(claim, evidence);
        return new Seed(claim, evidence);
    }

    private void seedDerivedRows(Claim claim, EvidenceItem evidence) {
        atomRepository.save(Atom.builder().claimId(claim.getId()).evidenceId(evidence.getId())
                .type("diagnosis").value("PTSD").source(evidence.getFilename()).confidence(0.9).build());
        atomRepository.save(Atom.builder().claimId(claim.getId()).evidenceId(evidence.getId())
                .type("medication").value("Sertraline 100mg").source(evidence.getFilename()).confidence(0.9).build());

        MedicalEvent event = new MedicalEvent();
        event.setClaimId(claim.getId());
        event.setEvidenceId(evidence.getId());
        event.setEventDate("2023-06-01");
        event.setEventType("primary_care");
        event.setSummary("Annual physical");
        medicalEventRepository.save(event);

        chunkRepository.save(Chunk.builder().scope("evidence").claimId(claim.getId())
                .evidenceId(evidence.getId()).source(evidence.getFilename())
                .content("PTSD diagnosed; sertraline prescribed").tokenCount(6)
                .embeddingStatus("pending").build());
    }

    private long chunksFor(Long evidenceId) {
        return chunkRepository.findAll().stream()
                .filter(c -> evidenceId.equals(c.getEvidenceId())).count();
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void deleteEvidenceRows_withNoAmbientTransaction_removesEverythingAndCommits() {
        Seed s = seedAnalyzedDocument();
        Long evidenceId = s.evidence().getId();
        assertEquals(2, atomRepository.countByEvidenceId(evidenceId));
        assertEquals(1, medicalEventRepository.countByEvidenceId(evidenceId));
        assertEquals(1, chunksFor(evidenceId));

        // The whole point: the controller calls this with no transaction of its own.
        assertDoesNotThrow(() -> service.deleteEvidenceRows(s.claim(), s.evidence()));

        assertEquals(0, atomRepository.countByEvidenceId(evidenceId), "atoms removed");
        assertEquals(0, medicalEventRepository.countByEvidenceId(evidenceId), "medical events removed");
        assertEquals(0, chunksFor(evidenceId), "RAG chunks removed");
        assertTrue(evidenceRepository.findById(evidenceId).isEmpty(), "evidence row removed");

        Claim reloaded = claimRepository.findById(s.claim().getId()).orElseThrow();
        assertNull(reloaded.getLastSynthesisAt(), "synthesis re-run is queued");
        assertNull(reloaded.getLastGapAnalysisAt(), "gap re-run is queued");
    }

    @Test
    void deleteEvidenceRows_leavesSiblingDocumentsUntouched() {
        Seed s = seedAnalyzedDocument();
        EvidenceItem sibling = evidenceRepository.save(EvidenceItem.builder()
                .claimId(s.claim().getId()).sourceType("pdf").filename("smr.pdf")
                .processingStatus("processed").build());
        seedDerivedRows(s.claim(), sibling);

        service.deleteEvidenceRows(s.claim(), s.evidence());

        assertEquals(2, atomRepository.countByEvidenceId(sibling.getId()));
        assertEquals(1, medicalEventRepository.countByEvidenceId(sibling.getId()));
        assertEquals(1, chunksFor(sibling.getId()));
        assertTrue(evidenceRepository.findById(sibling.getId()).isPresent());
    }
}
