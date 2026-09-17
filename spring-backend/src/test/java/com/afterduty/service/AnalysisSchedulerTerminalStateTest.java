package com.afterduty.service;

import com.afterduty.model.Claim;
import com.afterduty.model.IdentifiedCondition;
import com.afterduty.repository.ClaimRepository;
import com.afterduty.repository.ConditionRepository;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;

import static org.junit.jupiter.api.Assertions.*;

/**
 * P0-4 — the production scheduler path historically never cleared the
 * upload-time progress fields (analysisStage="extracting", analysisProgressPct=5),
 * so Home rendered a frozen "analyzing — 5%" forever after completion, failure,
 * and zero-conditions completion alike. Every terminal callback must clear them:
 * <ul>
 *   <li>gap-analysis completion → stage/pct cleared, status ANALYZED,
 *       lastAnalyzedAt stamped (the honest terminal-success state);</li>
 *   <li>synthesis/gap failure → stage/pct cleared, status ERROR + message kept
 *       (rendered instead of the eternal spinner);</li>
 *   <li>synthesis completion with ZERO conditions → terminal (gap analysis has
 *       nothing to run on, so nothing later would clear the fields);</li>
 *   <li>synthesis completion WITH conditions → NOT terminal (the following gap
 *       run's completion clears).</li>
 * </ul>
 */
@DataJpaTest
@Import(AnalysisScheduler.class)
@Tag("regression")
class AnalysisSchedulerTerminalStateTest {

    @Autowired
    AnalysisScheduler scheduler;

    @Autowired
    ClaimRepository claimRepository;

    @Autowired
    ConditionRepository conditionRepository;

    /** A claim as an upload leaves it: status EXTRACTING, stage "extracting", pct 5. */
    private Claim analyzingClaim() {
        Claim c = Claim.builder()
                .userId(1L)
                .claimType(Claim.ClaimType.INITIAL)
                .status(Claim.ClaimStatus.EXTRACTING)
                .build();
        c.setAnalysisStage("extracting");
        c.setAnalysisProgressPct(5);
        return claimRepository.save(c);
    }

    private Claim reload(Claim c) {
        return claimRepository.findById(c.getId()).orElseThrow();
    }

    @Test
    void gapAnalysisComplete_clearsProgress_landsAnalyzedWithTimestamp() {
        Claim c = analyzingClaim();

        scheduler.markGapAnalysisComplete(c.getId(), "test-model");

        Claim after = reload(c);
        assertNull(after.getAnalysisStage(), "terminal success must clear the frozen stage");
        assertNull(after.getAnalysisProgressPct(), "terminal success must clear the frozen pct");
        assertEquals(Claim.ClaimStatus.ANALYZED, after.getStatus());
        assertNull(after.getAnalysisMessage());
        assertNotNull(after.getLastAnalyzedAt(),
                "clients distinguish 'finished, zero conditions' from 'never analyzed' by lastAnalyzedAt");
        assertNotNull(after.getLastGapAnalysisAt());
        assertNull(after.getGapState());
    }

    @Test
    void synthesisFailed_clearsProgress_keepsErrorAndMessage() {
        Claim c = analyzingClaim();

        scheduler.markSynthesisFailed(c.getId(), "synthesis blew up");

        Claim after = reload(c);
        assertNull(after.getAnalysisStage(), "failure must clear the frozen stage — no eternal spinner");
        assertNull(after.getAnalysisProgressPct());
        assertEquals(Claim.ClaimStatus.ERROR, after.getStatus());
        assertEquals("synthesis blew up", after.getAnalysisMessage());
        assertNull(after.getLastAnalyzedAt(), "a failed run is not a completed analysis");
    }

    @Test
    void gapAnalysisFailed_clearsProgress_keepsErrorAndMessage() {
        Claim c = analyzingClaim();

        scheduler.markGapAnalysisFailed(c.getId(), "gap analysis blew up");

        Claim after = reload(c);
        assertNull(after.getAnalysisStage());
        assertNull(after.getAnalysisProgressPct());
        assertEquals(Claim.ClaimStatus.ERROR, after.getStatus());
        assertEquals("gap analysis blew up", after.getAnalysisMessage());
    }

    @Test
    void synthesisComplete_zeroConditions_isTerminal() {
        Claim c = analyzingClaim();

        scheduler.markSynthesisComplete(c.getId(), "test-model");

        Claim after = reload(c);
        assertNull(after.getAnalysisStage(),
                "zero-conditions completion is terminal — nothing later clears the fields");
        assertNull(after.getAnalysisProgressPct());
        assertEquals(Claim.ClaimStatus.ANALYZED, after.getStatus());
        assertNotNull(after.getLastAnalyzedAt());
    }

    @Test
    void synthesisComplete_withActiveConditions_isNotTerminal() {
        Claim c = analyzingClaim();
        conditionRepository.save(IdentifiedCondition.builder()
                .claimId(c.getId())
                .name("PTSD")
                .build());

        scheduler.markSynthesisComplete(c.getId(), "test-model");

        Claim after = reload(c);
        assertEquals("extracting", after.getAnalysisStage(),
                "mid-pipeline: the following gap run's completion is the terminal clear");
        assertEquals(5, after.getAnalysisProgressPct());
        assertEquals(Claim.ClaimStatus.EXTRACTING, after.getStatus());
        assertNull(after.getLastAnalyzedAt());
        assertNotNull(after.getLastSynthesisAt());
    }

    @Test
    void laterSuccess_recoversFromPriorError() {
        Claim c = analyzingClaim();
        scheduler.markSynthesisFailed(c.getId(), "first run failed");

        scheduler.markGapAnalysisComplete(c.getId(), "test-model");

        Claim after = reload(c);
        assertEquals(Claim.ClaimStatus.ANALYZED, after.getStatus());
        assertNull(after.getAnalysisMessage(), "a later success must clear the stale error message");
        assertNull(after.getAnalysisStage());
    }
}
