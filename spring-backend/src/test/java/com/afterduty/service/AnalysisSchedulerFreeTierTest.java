package com.afterduty.service;

import com.afterduty.config.SubscriptionProperties;
import com.afterduty.model.Atom;
import com.afterduty.model.Claim;
import com.afterduty.model.IdentifiedCondition;
import com.afterduty.model.User;
import com.afterduty.repository.AtomRepository;
import com.afterduty.repository.ClaimRepository;
import com.afterduty.repository.ConditionRepository;
import com.afterduty.repository.UserRepository;
import com.afterduty.service.extraction.ExtractionStateMachine;
import com.afterduty.service.gap.GapStateMachine;
import com.afterduty.service.synthesis.SynthesisStateMachine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Item D (2026-07-01 review §6, Phase D) — the free-tier A1 gate split, behind
 * {@code va-claim.subscription.free-analysis-tier}:
 *
 * <ul>
 *   <li>flag OFF (default / null props): a free claim never advances at all —
 *       byte-for-byte today's single-gate behavior;</li>
 *   <li>flag "a1", FREE claim: extraction + synthesis tick, the gap stage
 *       NEVER arms (an already in-flight gap run still ticks to completion so
 *       a mid-run downgrade can't wedge the claim);</li>
 *   <li>flag "a1", PRO claim: all three stages tick — Pro unaffected;</li>
 *   <li>flag "a1", FREE claim: synthesis completion WITH conditions is
 *       TERMINAL (P0-4 progress fields cleared, status ANALYZED, lastAnalyzedAt
 *       stamped) because no gap callback will ever fire for it; and
 *       lastGapAnalysisAt is deliberately NOT stamped, so upgrading to Pro
 *       arms the gap run via the standard lastSynthesisAt trigger.</li>
 * </ul>
 */
@DataJpaTest
@Import(AnalysisScheduler.class)
@TestPropertySource(properties = "va-claim.pipeline.quiet-seconds=0")
@Tag("regression")
class AnalysisSchedulerFreeTierTest {

    @Autowired AnalysisScheduler scheduler;
    @Autowired ClaimRepository claimRepository;
    @Autowired UserRepository userRepository;
    @Autowired AtomRepository atomRepository;
    @Autowired ConditionRepository conditionRepository;

    SynthesisStateMachine synthesisMachine;
    GapStateMachine gapMachine;
    ExtractionStateMachine extractionMachine;

    @BeforeEach
    void wireMocks() {
        synthesisMachine = mock(SynthesisStateMachine.class);
        gapMachine = mock(GapStateMachine.class);
        extractionMachine = mock(ExtractionStateMachine.class);
        scheduler.setSynthesisStateMachine(synthesisMachine);
        scheduler.setGapStateMachine(gapMachine);
        scheduler.setExtractionStateMachine(extractionMachine);
        // Scheduler bean is shared across tests in this class: reset the flag
        // to the production default (absent ⇒ off) before every test.
        scheduler.setSubscriptionProperties(null);
    }

    private void flag(String tier) {
        SubscriptionProperties p = new SubscriptionProperties();
        p.setFreeAnalysisTier(tier);
        scheduler.setSubscriptionProperties(p);
    }

    private User freeUser() {
        return userRepository.save(User.builder()
                .email("free@example.com").name("Free Vet").build());
    }

    private User proUser() {
        return userRepository.save(User.builder()
                .email("pro@example.com").name("Pro Vet")
                .subscriptionExpiresAt(Instant.now().plus(365, ChronoUnit.DAYS))
                .build());
    }

    /**
     * A claim in the state every stage's trigger fires on:
     * extractionState=NONE (in-flight → extraction ticks), a live atom newer
     * than lastSynthesisAt with quiet-seconds=0 (→ synthesis ticks), and
     * lastSynthesisAt newer than lastGapAnalysisAt=null (→ gap trigger true).
     */
    private Claim fullyTriggeredClaim(User owner) {
        Claim c = Claim.builder()
                .userId(owner.getId())
                .claimType(Claim.ClaimType.INITIAL)
                .status(Claim.ClaimStatus.EXTRACTING)
                .build();
        c.setExtractionState("NONE");
        c.setLastSynthesisAt(Instant.now().minus(1, ChronoUnit.HOURS));
        c = claimRepository.save(c);
        atomRepository.save(Atom.builder()
                .claimId(c.getId())
                .type("diagnosis").value("PTSD").source("evidence:test")
                .createdBy("ai:test")
                .build());
        return c;
    }

    /* ---------------- flag OFF — exact current behavior ---------------- */

    @Test
    void flagOff_freeClaim_neverAdvancesAnyStage() {
        fullyTriggeredClaim(freeUser());

        scheduler.tick();

        verifyNoInteractions(extractionMachine, synthesisMachine, gapMachine);
    }

    @Test
    void flagExplicitlyOff_freeClaim_neverAdvancesAnyStage() {
        flag("off");
        fullyTriggeredClaim(freeUser());

        scheduler.tick();

        verifyNoInteractions(extractionMachine, synthesisMachine, gapMachine);
    }

    @Test
    void flagOff_proClaim_allThreeStagesAdvance() {
        fullyTriggeredClaim(proUser());

        scheduler.tick();

        verify(extractionMachine).advance(any(Claim.class));
        verify(synthesisMachine).advance(any(Claim.class));
        verify(gapMachine).advance(any(Claim.class));
    }

    /* ---------------- flag a1 — the per-stage split ---------------- */

    @Test
    void flagA1_freeClaim_runsExtractionAndSynthesis_gapNeverArms() {
        flag("a1");
        fullyTriggeredClaim(freeUser());

        scheduler.tick();

        verify(extractionMachine).advance(any(Claim.class));
        verify(synthesisMachine).advance(any(Claim.class));
        verify(gapMachine, never()).advance(any(Claim.class));
    }

    @Test
    void flagA1_proClaim_allThreeStagesAdvance_proUnaffected() {
        flag("a1");
        fullyTriggeredClaim(proUser());

        scheduler.tick();

        verify(extractionMachine).advance(any(Claim.class));
        verify(synthesisMachine).advance(any(Claim.class));
        verify(gapMachine).advance(any(Claim.class));
    }

    @Test
    void flagA1_freeClaim_inFlightGapRun_stillTicksToCompletion() {
        // A Pro user's subscription lapsed mid-gap-run: gapState is set. The
        // run must be driven to completion (the spend is already booked), not
        // frozen forever with gapState wedged.
        flag("a1");
        Claim c = fullyTriggeredClaim(freeUser());
        c.setGapState("VALIDATING");
        claimRepository.save(c);

        scheduler.tick();

        verify(gapMachine).advance(any(Claim.class));
    }

    /* --------- flag a1 — synthesis-complete is terminal for free claims --------- */

    /** A claim as an upload leaves it: status EXTRACTING, stage "extracting", pct 5. */
    private Claim analyzingClaim(User owner) {
        Claim c = Claim.builder()
                .userId(owner.getId())
                .claimType(Claim.ClaimType.INITIAL)
                .status(Claim.ClaimStatus.EXTRACTING)
                .build();
        c.setAnalysisStage("extracting");
        c.setAnalysisProgressPct(5);
        return claimRepository.save(c);
    }

    private void addActiveCondition(Claim c) {
        conditionRepository.save(IdentifiedCondition.builder()
                .claimId(c.getId())
                .name("PTSD")
                .build());
    }

    @Test
    void flagA1_freeSynthesisComplete_withConditions_isTerminal() {
        flag("a1");
        Claim c = analyzingClaim(freeUser());
        addActiveCondition(c);

        scheduler.markSynthesisComplete(c.getId(), "test-model");

        Claim after = claimRepository.findById(c.getId()).orElseThrow();
        assertNull(after.getAnalysisStage(),
                "free A1 claim: no gap callback will ever clear the progress fields — synthesis-complete must");
        assertNull(after.getAnalysisProgressPct());
        assertEquals(Claim.ClaimStatus.ANALYZED, after.getStatus());
        assertNotNull(after.getLastAnalyzedAt());
        assertNotNull(after.getLastSynthesisAt());
        assertNull(after.getLastGapAnalysisAt(),
                "lastGapAnalysisAt must stay null so a later Pro upgrade arms the gap run");
    }

    @Test
    void flagA1_proSynthesisComplete_withConditions_isNotTerminal() {
        flag("a1");
        Claim c = analyzingClaim(proUser());
        addActiveCondition(c);

        scheduler.markSynthesisComplete(c.getId(), "test-model");

        Claim after = claimRepository.findById(c.getId()).orElseThrow();
        assertEquals("extracting", after.getAnalysisStage(),
                "Pro mid-pipeline: the following gap run's completion is the terminal clear");
        assertEquals(5, after.getAnalysisProgressPct());
        assertEquals(Claim.ClaimStatus.EXTRACTING, after.getStatus());
        assertNull(after.getLastAnalyzedAt());
    }

    @Test
    void flagOff_freeSynthesisComplete_withConditions_isNotTerminal() {
        // Exact current behavior with the flag off: subscription state is NOT
        // consulted in markSynthesisComplete (free claims can't reach it today).
        Claim c = analyzingClaim(freeUser());
        addActiveCondition(c);

        scheduler.markSynthesisComplete(c.getId(), "test-model");

        Claim after = claimRepository.findById(c.getId()).orElseThrow();
        assertEquals("extracting", after.getAnalysisStage());
        assertEquals(Claim.ClaimStatus.EXTRACTING, after.getStatus());
        assertNull(after.getLastAnalyzedAt());
    }
}
