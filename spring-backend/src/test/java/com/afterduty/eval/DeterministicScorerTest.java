package com.afterduty.eval;

import com.afterduty.model.Atom;
import com.afterduty.model.Claim;
import com.afterduty.model.IdentifiedCondition;
import com.afterduty.service.VaMathService;
import com.afterduty.service.synthesis.PyramidingRules;
import com.afterduty.service.synthesis.VasrdDecisionEngine;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link DeterministicScorer} (spec §8.2): recall/precision edge
 * cases (laterality twins, forbidden hit, band boundaries inclusive), the
 * deterministic-rating engine cross-check, and dirty-scope counting — all against
 * hand-built fixture end-states, no Spring context.
 */
@Tag("regression")
class DeterministicScorerTest {

    private final DeterministicScorer scorer =
            new DeterministicScorer(new PyramidingRules(), new VaMathService(), new VasrdDecisionEngine());

    private IdentifiedCondition cond(String name, String dc, int rating, String system) {
        return IdentifiedCondition.builder()
                .id((long) Math.abs(name.hashCode()) % 100000)
                .claimId(1L).name(name).vasrdCode(dc).bodySystem(system)
                .estimatedRating(rating).build();
    }

    private PipelineEndState end(String outcome, List<IdentifiedCondition> active) {
        return new PipelineEndState(outcome, new Claim(), active, active,
                List.of(), List.of(), List.of());
    }

    private GoldenExpectation.Band band(int lo, int hi) {
        return new GoldenExpectation.Band(lo, hi);
    }

    private GoldenExpectation.ConditionExpectation ce(String pat, String dc, int lo, int hi,
                                                      boolean must, String lat) {
        return new GoldenExpectation.ConditionExpectation(pat, dc, null, lat, band(lo, hi),
                null, must, null);
    }

    // ------------------------------------------------------------------ recall

    @Test
    void recall_passesWhenAllMustFoundPresent() {
        var pe = new GoldenExpectation.PhaseExpectation("complete",
                List.of(ce("(?i)PTSD", "9411", 30, 70, true, null)),
                null, null, null, null, new GoldenExpectation.Abstention(false), null,null, null, null);
        var score = scorer.score("t", "initial", pe,
                end("complete", List.of(cond("PTSD", "9411", 50, "mental"))), Map.of());
        assertTrue(score.passed(), "failures: " + score.failures());
        assertTrue(score.conditionRecall() == 1.0);
    }

    @Test
    void recall_failsWhenMustFoundMissing() {
        var pe = new GoldenExpectation.PhaseExpectation("complete",
                List.of(ce("(?i)tinnitus", "6260", 10, 10, true, null)),
                null, null, null, null, new GoldenExpectation.Abstention(false), null,null, null, null);
        var score = scorer.score("t", "initial", pe,
                end("complete", List.of(cond("PTSD", "9411", 50, "mental"))), Map.of());
        assertFalse(score.passed());
        assertTrue(score.failures().stream().anyMatch(f -> f.contains("not found")));
    }

    // ------------------------------------------------------------------ laterality

    @Test
    void laterality_twinsAreDistinguished() {
        // expectation wants the LEFT knee; only a RIGHT knee is present ⇒ miss.
        var pe = new GoldenExpectation.PhaseExpectation("complete",
                List.of(ce("(?i)knee", "5260", 0, 30, true, "left")),
                null, null, null, null, new GoldenExpectation.Abstention(false), null,null, null, null);
        var score = scorer.score("t", "initial", pe,
                end("complete", List.of(cond("Right knee strain", "5260", 10, "musculoskeletal"))),
                Map.of());
        assertFalse(score.passed(), "a right-knee row must not satisfy a left-knee expectation");
    }

    // ------------------------------------------------------------------ band boundaries

    @Test
    void ratingBand_isInclusiveAtBoundaries() {
        var pe = new GoldenExpectation.PhaseExpectation("complete",
                List.of(ce("(?i)PTSD", "9411", 50, 70, true, null)),
                null, null, null, null, new GoldenExpectation.Abstention(false), null,null, null, null);
        // rating == min boundary
        assertTrue(scorer.score("t", "initial", pe,
                end("complete", List.of(cond("PTSD", "9411", 50, "mental"))), Map.of()).passed());
        // rating == max boundary
        assertTrue(scorer.score("t", "initial", pe,
                end("complete", List.of(cond("PTSD", "9411", 70, "mental"))), Map.of()).passed());
        // rating just outside
        assertFalse(scorer.score("t", "initial", pe,
                end("complete", List.of(cond("PTSD", "9411", 80, "mental"))), Map.of()).passed());
    }

    // ------------------------------------------------------------------ forbidden

    @Test
    void forbidden_conditionPresentFails() {
        var pe = new GoldenExpectation.PhaseExpectation("complete",
                List.of(ce("(?i)PTSD", "9411", 30, 70, true, null)),
                List.of(new GoldenExpectation.ForbiddenCondition("7101", "hypertension ruled out")),
                null, null, null, new GoldenExpectation.Abstention(false), null,null, null, null);
        var active = List.of(cond("PTSD", "9411", 50, "mental"),
                cond("Hypertension", "7101", 10, "cardiovascular"));
        var score = scorer.score("t", "initial", pe, end("complete", active), Map.of());
        assertFalse(score.passed());
        assertTrue(score.forbiddenHit());
    }

    // ------------------------------------------------------------------ deterministic engine

    @Test
    void deterministicRating_crossChecksRealEngine() {
        // tinnitus DC 6260 flagged deterministic; live atoms contain a tinnitus
        // diagnosis ⇒ the REAL VasrdDecisionEngine must fire at 10%.
        var det = new GoldenExpectation.ConditionExpectation("(?i)tinnitus", "6260", null, null,
                band(10, 10), null, true, true);
        var pe = new GoldenExpectation.PhaseExpectation("complete", List.of(det),
                null, null, null, null, new GoldenExpectation.Abstention(false), null,null, null, null);
        List<Atom> atoms = List.of(Atom.builder().claimId(1L).type("diagnosis")
                .value("Tinnitus, constant ringing").source("audiology").build());
        var endState = new PipelineEndState("complete", new Claim(),
                List.of(cond("Tinnitus", "6260", 10, "ear")),
                List.of(cond("Tinnitus", "6260", 10, "ear")),
                atoms, List.of(), List.of());
        var score = scorer.score("t", "initial", pe, endState, Map.of());
        assertTrue(score.deterministicRatingOk(), "failures: " + score.failures());
    }

    @Test
    void deterministicRating_failsWhenEngineDoesNotFire() {
        var det = new GoldenExpectation.ConditionExpectation("(?i)tinnitus", "6260", null, null,
                band(10, 10), null, true, true);
        var pe = new GoldenExpectation.PhaseExpectation("complete", List.of(det),
                null, null, null, null, new GoldenExpectation.Abstention(false), null,null, null, null);
        // NO atoms ⇒ engine returns empty ⇒ scorer flags it.
        var endState = new PipelineEndState("complete", new Claim(),
                List.of(cond("Tinnitus", "6260", 10, "ear")),
                List.of(cond("Tinnitus", "6260", 10, "ear")),
                List.of(), List.of(), List.of());
        var score = scorer.score("t", "initial", pe, endState, Map.of());
        assertFalse(score.deterministicRatingOk());
    }

    // ------------------------------------------------------------------ combined rating

    @Test
    void combinedRating_recomputedViaRealMath() {
        // PTSD 70 + knee 20 → exact 76 → rounds to 80; band [70,90] passes.
        var pe = new GoldenExpectation.PhaseExpectation("complete",
                List.of(ce("(?i)PTSD", "9411", 50, 70, true, null),
                        ce("(?i)knee", "5260", 10, 30, true, "right")),
                null, band(70, 90), null, null, new GoldenExpectation.Abstention(false), null,null, null, null);
        var active = List.of(cond("PTSD", "9411", 70, "mental"),
                cond("Right knee strain", "5260", 20, "musculoskeletal"));
        var score = scorer.score("t", "initial", pe, end("complete", active), Map.of());
        assertTrue(score.passed(), "failures: " + score.failures());
        assertTrue(score.combinedRating() == 80, "combined was " + score.combinedRating());
    }

    // ------------------------------------------------------------------ dirty scope

    @Test
    void dirtyScope_countsRateJobsThisPhase() {
        var active = new ArrayList<IdentifiedCondition>(List.of(cond("PTSD", "9411", 50, "mental"),
                cond("Tinnitus", "6260", 10, "ear")));
        // submitted ledger: 3 distinct synthesis_rate jobs cumulatively; prior phase
        // had 2 ⇒ this phase submitted 1.
        var jobs = List.of(
                new com.afterduty.service.llm.FakeLlmAsyncProvider.SubmittedJob(
                        java.util.UUID.randomUUID(), "synthesis_rate", "g", 1L, null, "p1"),
                new com.afterduty.service.llm.FakeLlmAsyncProvider.SubmittedJob(
                        java.util.UUID.randomUUID(), "synthesis_rate", "g", 2L, null, "p2"),
                new com.afterduty.service.llm.FakeLlmAsyncProvider.SubmittedJob(
                        java.util.UUID.randomUUID(), "synthesis_rate", "g", 3L, null, "p3"));
        var endState = new PipelineEndState("complete", new Claim(), active, active,
                List.of(), List.of(), jobs);
        var pe = new GoldenExpectation.PhaseExpectation("complete", List.of(),
                null, null, null, null, new GoldenExpectation.Abstention(false), null,1, null, null);
        var score = scorer.score("t", "delta", pe, endState, Map.of("synthesis_rate", 2L));
        assertTrue(score.dirtyScopeOk(), "1 rate job this phase ≤ dirty max 1; failures: " + score.failures());

        // tighten the max to 0 ⇒ fails.
        var peTight = new GoldenExpectation.PhaseExpectation("complete", List.of(),
                null, null, null, null, new GoldenExpectation.Abstention(false), null,0, null, null);
        var scoreTight = scorer.score("t", "delta", peTight, endState, Map.of("synthesis_rate", 2L));
        assertFalse(scoreTight.dirtyScopeOk());
    }

    // ------------------------------------------------------------------ evidence state

    private com.afterduty.model.EvidenceItem evidence(String filename, String status, String message) {
        var ev = com.afterduty.model.EvidenceItem.builder()
                .claimId(1L).filename(filename).processingStatus(status).build();
        ev.setProcessingMessage(message);
        return ev;
    }

    private PipelineEndState endWithEvidence(String outcome,
                                             List<com.afterduty.model.EvidenceItem> evidence) {
        return new PipelineEndState(outcome, new Claim(), List.of(), List.of(),
                List.of(), evidence, List.of());
    }

    /** The gc-013 shape: an unreadable scan ⇒ processing_status=error + a plain message ⇒ honest. */
    @Test
    void evidenceState_passesWhenUnreadableMarkedError_withMessage() {
        var pe = new GoldenExpectation.PhaseExpectation("complete_zero_conditions", List.of(),
                null, null, null, null, new GoldenExpectation.Abstention(false),
                List.of(new GoldenExpectation.EvidenceExpectation("(?i)bad.?scan", "error", true)),
                null, null, null);
        var end = endWithEvidence("complete_zero_conditions",
                List.of(evidence("Bad_scan.txt", "error", "We couldn't read this document.")));
        var score = scorer.score("gc-013", "initial", pe, end, Map.of());
        assertTrue(score.evidenceStateOk(), "failures: " + score.failures());
        assertTrue(score.passed(), "failures: " + score.failures());
    }

    /**
     * The silent-zero regression the case was supposed to catch: the unreadable doc is
     * marked 'processed' with empty atoms instead of 'error'. The pipeline still completes
     * zero conditions (byte-identical outcome to gc-015/gc-019), so ONLY the evidence-state
     * assertion distinguishes it. It must FAIL.
     */
    @Test
    void evidenceState_failsOnSilentZero_unreadableMarkedProcessed() {
        var pe = new GoldenExpectation.PhaseExpectation("complete_zero_conditions", List.of(),
                null, null, null, null, new GoldenExpectation.Abstention(false),
                List.of(new GoldenExpectation.EvidenceExpectation("(?i)bad.?scan", "error", true)),
                null, null, null);
        var end = endWithEvidence("complete_zero_conditions",
                List.of(evidence("Bad_scan.txt", "processed", null)));   // silent zero
        var score = scorer.score("gc-013", "initial", pe, end, Map.of());
        assertFalse(score.evidenceStateOk(), "a silently-processed unreadable doc must fail");
        assertFalse(score.passed());
        assertTrue(score.failures().stream().anyMatch(f -> f.contains("processing_status expected 'error'")));
    }

    @Test
    void evidenceState_failsWhenNoEvidenceMatchesPattern() {
        var pe = new GoldenExpectation.PhaseExpectation("complete_zero_conditions", List.of(),
                null, null, null, null, new GoldenExpectation.Abstention(false),
                List.of(new GoldenExpectation.EvidenceExpectation("(?i)bad.?scan", "error", true)),
                null, null, null);
        var end = endWithEvidence("complete_zero_conditions",
                List.of(evidence("Clean_exam.txt", "processed", null)));
        var score = scorer.score("gc-013", "initial", pe, end, Map.of());
        assertFalse(score.evidenceStateOk());
        assertTrue(score.failures().stream().anyMatch(f -> f.contains("no evidence row matched")));
    }
}
