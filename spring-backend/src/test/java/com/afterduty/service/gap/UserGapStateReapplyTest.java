package com.afterduty.service.gap;

import com.afterduty.model.*;
import com.afterduty.repository.*;
import com.afterduty.service.llm.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * P1-6 — durable gap state through the gap pipeline.
 *
 * A gap run wholesale-replaces each condition's gaps JSON, which used to
 * destroy the veteran's resolved/dismissed decisions and resurrect gaps they
 * said do not apply. This suite pins the two state-machine halves of the fix:
 *
 *  1. RE-APPLY — user_gap_state rows (keyed by identity fingerprint +
 *     (gap_type, triad_leg)) are stamped back onto the freshly-written gap
 *     JSON when a run persists it, and survive a SECOND full re-run (the
 *     wholesale-rewrite case).
 *  2. PROMPT INJECTION — dismissed entries ride the gap_evidence prompt as
 *     do-not-re-propose context, on the volatile tail only: the cached
 *     prefix (system blocks + atom corpus) stays byte-stable.
 *
 * Strategy mirrors GapWhatIfFlagOffTest: real H2 JPA, FakeLlmAsyncProvider
 * with canned pipeline-shaped JSON, manual advance()/tick() driving. The
 * what-if stage stays at its shipped default (OFF), so validated gaps are the
 * final persisted output.
 */
@DataJpaTest
@Import({
        com.afterduty.service.llm.LlmProviderRouterTestConfig.class,
        com.afterduty.service.llm.LlmJobService.class,
        com.afterduty.service.llm.LlmJobSubmitter.class,
        com.afterduty.service.llm.LlmJobPoller.class,
        GapStateMachine.class,
        EvidenceGapAnalyzer.class,
        GapValidationAgent.class,
        WhatIfScenarioGenerator.class,
        UserGapStateService.class,
        com.afterduty.service.synthesis.ConditionGenerationService.class,
        com.afterduty.service.AnalysisScheduler.class
})
@TestPropertySource(properties = {
        "va-claim.llm.submitter.batch-size=50",
        "va-claim.llm.submitter.poll-ms=9999999",
        "va-claim.llm.submitter.initial-delay-ms=9999999",
        "va-claim.llm.poller.poll-ms=9999999",
        "va-claim.llm.poller.initial-delay-ms=9999999",
        "va-claim.llm.orphan-deadline-min=30"
})
class UserGapStateReapplyTest {

    /** Pipeline-shaped gaps, exactly as EvidenceGapAnalyzer's OUTPUT schema keys them. */
    static final String GAP_EVIDENCE_RESPONSE = """
            [
              {"type":"nexus_letter","title":"Obtain nexus letter","triad_leg":"nexus","priority":"high"},
              {"type":"buddy_statement","title":"Collect buddy statements","triad_leg":null,"priority":"medium"},
              {"type":"treatment_record","title":"Recent treatment records","triad_leg":"severity","priority":"low"}
            ]
            """;

    /**
     * Non-object response → GapValidationAgent's parser keeps the originals,
     * so the evidence-stage gaps above are what the run persists.
     */
    static final String GAP_VALIDATION_RESPONSE = "keep-originals";

    @Autowired GapStateMachine gapStateMachine;
    @Autowired UserGapStateService userGapStateService;
    @Autowired UserGapStateRepository userGapStateRepository;
    @Autowired LlmJobSubmitter llmJobSubmitter;
    @Autowired LlmJobPoller llmJobPoller;
    @Autowired ClaimRepository claimRepository;
    @Autowired IdentifiedConditionRepository conditionRepository;
    @Autowired AtomRepository atomRepository;
    @Autowired ClaimPipelineJobRepository pipelineJobRepository;
    @Autowired LlmJobRepository llmJobRepository;
    @Autowired FakeLlmAsyncProvider fakeProvider;

    private Claim claim;
    private IdentifiedCondition cond;

    @BeforeEach
    void setUp() {
        fakeProvider.reset();
        fakeProvider.setResponse("gap_evidence", GAP_EVIDENCE_RESPONSE);
        fakeProvider.setResponse("gap_validation", GAP_VALIDATION_RESPONSE);

        claim = claimRepository.save(Claim.builder()
                .userId(1L).status(Claim.ClaimStatus.ANALYZED).build());
        atomRepository.save(Atom.builder()
                .claimId(claim.getId()).type("diagnosis").value("PTSD diagnosed")
                .source("VA exam").createdBy("ai:test").build());
        cond = conditionRepository.save(IdentifiedCondition.builder()
                .claimId(claim.getId()).name("PTSD").vasrdCode("9411")
                .estimatedRating(50).build());
    }

    private void runGapToComplete() {
        for (int i = 0; i < 8; i++) {
            Claim cur = claimRepository.findById(claim.getId()).orElseThrow();
            if (i > 0 && cur.getGapState() == null) return;
            gapStateMachine.advance(cur);
            llmJobSubmitter.tick();
            llmJobPoller.tick();
        }
        fail("gap analysis did not complete");
    }

    private void seedUserState(String gapType, String triadLeg, String status) {
        String fp = userGapStateService.fingerprintFor(cond);
        userGapStateRepository.save(new UserGapState(claim.getId(), fp, gapType, triadLeg, status));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> gapOfType(List<Map<String, Object>> gaps, String type) {
        return gaps.stream().filter(g -> type.equals(g.get("type"))).findFirst().orElseThrow();
    }

    @Test
    void freshRun_stampsSavedStatusesOntoNewGapJson() {
        seedUserState("nexus_letter", "nexus", UserGapStateService.STATUS_RESOLVED);
        seedUserState("buddy_statement", null, UserGapStateService.STATUS_DISMISSED);

        runGapToComplete();

        IdentifiedCondition reloaded = conditionRepository.findById(cond.getId()).orElseThrow();
        List<Map<String, Object>> gaps = reloaded.getGaps();
        assertNotNull(gaps);
        assertEquals(3, gaps.size(), "wholesale rewrite persists the run's three gaps");

        assertEquals("resolved", gapOfType(gaps, "nexus_letter").get("status"),
                "(type, triad_leg)-matched resolved status must be stamped onto the fresh JSON");
        assertEquals("dismissed", gapOfType(gaps, "buddy_statement").get("status"),
                "null-leg dismissed status must be stamped onto the fresh JSON");
        assertNull(gapOfType(gaps, "treatment_record").get("status"),
                "a gap with no user decision must NOT get a status stamped");
    }

    @Test
    void reapply_survivesWholesaleRewrite_onSecondFullRun() {
        seedUserState("nexus_letter", "nexus", UserGapStateService.STATUS_RESOLVED);

        runGapToComplete();

        // Simulate a re-analysis: the gap JSON is wiped and the run re-fans-out
        // from scratch (the exact wholesale-replace that used to resurrect
        // resolved gaps).
        IdentifiedCondition mid = conditionRepository.findById(cond.getId()).orElseThrow();
        mid.setGaps(null);
        conditionRepository.save(mid);
        Claim c = claimRepository.findById(claim.getId()).orElseThrow();
        c.setGapState(null);
        c.setGapAnalysisInProgress(false);
        claimRepository.save(c);

        runGapToComplete();

        IdentifiedCondition reloaded = conditionRepository.findById(cond.getId()).orElseThrow();
        assertEquals("resolved", gapOfType(reloaded.getGaps(), "nexus_letter").get("status"),
                "the veteran's resolution must survive a full gap re-run");
    }

    @Test
    void dismissedEntries_injectedIntoGapEvidencePrompt_volatileTailOnly() throws Exception {
        seedUserState("buddy_statement", null, UserGapStateService.STATUS_DISMISSED);
        seedUserState("nexus_letter", "nexus", UserGapStateService.STATUS_RESOLVED);

        gapStateMachine.advance(claim); // NONE → EVIDENCE_GAPS (submits + persists payload)

        List<ClaimPipelineJob> pjobs =
                pipelineJobRepository.findByClaimIdAndStage(claim.getId(), "gap_evidence");
        assertEquals(1, pjobs.size());
        LlmJob job = llmJobRepository.findById(pjobs.get(0).getLlmJobId()).orElseThrow();
        com.fasterxml.jackson.databind.JsonNode payload =
                new com.fasterxml.jackson.databind.ObjectMapper().readTree(job.getRequestPayload());

        String userMessage = payload.get("userMessage").asText();
        assertTrue(userMessage.contains("The veteran has said these do not apply — do not re-propose them:"),
                "dismissed context header must reach the gap_evidence prompt tail");
        assertTrue(userMessage.contains("- buddy_statement"),
                "the dismissed (type) entry must be listed");
        assertFalse(userMessage.contains("- nexus_letter"),
                "RESOLVED entries are not dismissals and must NOT be injected");

        // Cache safety: the block rides the volatile tail (userMessage), never
        // the cached prefix (structuredPrompt systemBlocks / cachedCorpus).
        com.fasterxml.jackson.databind.JsonNode sp = payload.get("structuredPrompt");
        assertNotNull(sp, "caching-on default must still emit the structured prompt");
        assertFalse(sp.toString().contains("do not re-propose"),
                "the cache_control-stable prefix must never carry the per-condition dismissed block");
    }

    @Test
    void noUserState_promptAndJsonUnchanged() {
        gapStateMachine.advance(claim);

        List<ClaimPipelineJob> pjobs =
                pipelineJobRepository.findByClaimIdAndStage(claim.getId(), "gap_evidence");
        LlmJob job = llmJobRepository.findById(pjobs.get(0).getLlmJobId()).orElseThrow();
        assertFalse(job.getRequestPayload().contains("do not re-propose"),
                "no dismissals → no injected block");

        llmJobSubmitter.tick();
        llmJobPoller.tick();
        gapStateMachine.advance(claim); // persists gaps → VALIDATING
        llmJobSubmitter.tick();
        llmJobPoller.tick();
        gapStateMachine.advance(claim); // → COMPLETE (what-if default OFF)

        IdentifiedCondition reloaded = conditionRepository.findById(cond.getId()).orElseThrow();
        for (Map<String, Object> g : reloaded.getGaps()) {
            assertNull(g.get("status"), "no user state → nothing stamped");
        }
    }
}
