package com.afterduty.service;

import com.afterduty.exception.UsageLimitException;
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

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PipelineServiceUsageGuardTest {

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
    void runFullPipeline_blocksWhenAtLimit() {
        Claim claim = Claim.builder().id(10L).userId(1L).build();
        when(claimRepository.findById(10L)).thenReturn(Optional.of(claim));
        doThrow(new UsageLimitException(Instant.parse("2026-05-01T00:00:00Z")))
                .when(usageGuard).assertCapacity(1L);

        assertThatThrownBy(() -> pipeline.runFullPipeline(10L, 1L))
                .isInstanceOf(UsageLimitException.class);
    }

    @Test
    void processEvidence_marksDeferredWhenAtLimit() {
        EvidenceItem evidence = EvidenceItem.builder()
                .id(99L).claimId(10L).processingStatus("pending").build();
        Claim claim = Claim.builder().id(10L).userId(1L).build();
        when(evidenceRepository.findById(99L)).thenReturn(Optional.of(evidence));
        when(claimRepository.findById(10L)).thenReturn(Optional.of(claim));
        doThrow(new UsageLimitException(Instant.parse("2026-05-01T00:00:00Z")))
                .when(usageGuard).assertCapacity(1L);

        pipeline.processEvidence(99L, 1L);

        assertThat(evidence.getProcessingStatus()).isEqualTo("deferred_usage_limit");
    }
}
