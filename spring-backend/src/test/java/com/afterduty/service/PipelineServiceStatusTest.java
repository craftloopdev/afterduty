package com.afterduty.service;

import com.afterduty.model.Claim;
import com.afterduty.model.EvidenceItem;
import com.afterduty.repository.AtomRepository;
import com.afterduty.repository.ClaimRepository;
import com.afterduty.repository.ConditionRepository;
import com.afterduty.repository.EvidenceRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * P0-3 — {@code processEvidence} used to stamp {@code processed} at upload,
 * before (or without ever) any extraction running: every free user's documents
 * showed a green done badge forever. At submit a document may only be
 * {@code queued}; the ExtractionStateMachine owns the {@code processing} /
 * {@code processed} transitions.
 */
@ExtendWith(MockitoExtension.class)
class PipelineServiceStatusTest {

    @Mock UsageGuard usageGuard;
    @Mock ClaimRepository claimRepository;
    @Mock ClaudeSynthesisService claudeSynthesisService;
    @Mock DocumentStorageService documentStorageService;
    @Mock ConditionPostProcessService conditionPostProcessService;
    @Mock PipelineVerifierService pipelineVerifierService;
    @Mock EvidenceRepository evidenceRepository;
    @Mock AtomRepository atomRepository;
    @Mock ConditionRepository conditionRepository;

    @InjectMocks PipelineService pipeline;

    @Test
    void processEvidence_marksQueued_notProcessed() {
        EvidenceItem evidence = EvidenceItem.builder()
                .id(99L).claimId(10L).filename("dd214.pdf").processingStatus("pending").build();
        Claim claim = Claim.builder().id(10L).userId(1L).build();
        when(evidenceRepository.findById(99L)).thenReturn(Optional.of(evidence));
        when(claimRepository.findById(10L)).thenReturn(Optional.of(claim));

        pipeline.processEvidence(99L, 1L);

        assertThat(evidence.getProcessingStatus()).isEqualTo("queued");
        // The machine is armed so the scheduler actually drives extraction.
        assertThat(claim.getExtractionState()).isEqualTo("NONE");
        assertThat(claim.getSynthesisNeeded()).isTrue();
    }

    @Test
    void processEvidence_doesNotClobberExistingExtractionState() {
        EvidenceItem evidence = EvidenceItem.builder()
                .id(99L).claimId(10L).processingStatus("pending").build();
        Claim claim = Claim.builder().id(10L).userId(1L).build();
        claim.setExtractionState("SINGLE_PASS_EXTRACTION");
        when(evidenceRepository.findById(99L)).thenReturn(Optional.of(evidence));
        when(claimRepository.findById(10L)).thenReturn(Optional.of(claim));

        pipeline.processEvidence(99L, 1L);

        assertThat(evidence.getProcessingStatus()).isEqualTo("queued");
        assertThat(claim.getExtractionState()).isEqualTo("SINGLE_PASS_EXTRACTION");
    }
}
