package com.afterduty.job;

import com.afterduty.model.EvidenceItem;
import com.afterduty.repository.EvidenceRepository;
import com.afterduty.service.PipelineService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class UsageResetJob {

    private static final Logger log = LoggerFactory.getLogger(UsageResetJob.class);

    private final EvidenceRepository evidenceRepository;
    private final PipelineService pipelineService;

    public UsageResetJob(EvidenceRepository evidenceRepository, PipelineService pipelineService) {
        this.evidenceRepository = evidenceRepository;
        this.pipelineService = pipelineService;
    }

    /** 00:05 UTC on the 1st of each month. */
    @Scheduled(cron = "0 5 0 1 * *", zone = "UTC")
    public void run() {
        List<EvidenceItem> deferred =
                evidenceRepository.findByProcessingStatus("deferred_usage_limit");
        if (deferred.isEmpty()) {
            log.info("UsageResetJob: nothing to reset");
            return;
        }
        log.info("UsageResetJob: resetting {} deferred evidence rows", deferred.size());
        for (EvidenceItem ev : deferred) {
            ev.setProcessingStatus("pending");
            ev.setProcessingMessage("Re-queued after period reset");
            evidenceRepository.save(ev);
            pipelineService.processEvidenceForReset(ev.getId());
        }
    }
}
