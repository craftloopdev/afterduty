package com.afterduty.job;

import com.afterduty.model.EvidenceItem;
import com.afterduty.repository.EvidenceRepository;
import com.afterduty.service.PipelineService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UsageResetJobTest {

    @Mock EvidenceRepository evidenceRepository;
    @Mock PipelineService pipelineService;
    @InjectMocks UsageResetJob job;

    @Test
    void run_resetsDeferredRowsAndKicksExtraction() {
        EvidenceItem ev = EvidenceItem.builder()
                .id(1L).claimId(7L).processingStatus("deferred_usage_limit").build();
        when(evidenceRepository.findByProcessingStatus("deferred_usage_limit"))
                .thenReturn(List.of(ev));

        job.run();

        assertThat(ev.getProcessingStatus()).isEqualTo("pending");
        verify(evidenceRepository).save(ev);
        verify(pipelineService).processEvidenceForReset(eq(1L));
    }

    @Test
    void run_isNoOpWhenNoDeferred() {
        when(evidenceRepository.findByProcessingStatus("deferred_usage_limit"))
                .thenReturn(List.of());

        job.run();

        verify(pipelineService, never()).processEvidenceForReset(anyLong());
    }
}
