package com.afterduty.eval;

import com.afterduty.model.Atom;
import com.afterduty.model.Claim;
import com.afterduty.model.IdentifiedCondition;
import com.afterduty.service.VaMathService;
import com.afterduty.service.synthesis.PyramidingRules;
import com.afterduty.service.synthesis.VasrdDecisionEngine;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Mutation sanity (mission requirement): proves the {@link DeterministicScorer}
 * FAILS when the pipeline regresses. Each test takes the gc-001-shaped expectation
 * and feeds a deliberately-broken end-state simulating a specific regression class;
 * the scorer MUST flag it. If any of these passed, the offline gate would be a
 * rubber stamp — so these are the guardrails on the guardrail.
 */
@Tag("regression")
class MutationSanityTest {

    private final DeterministicScorer scorer =
            new DeterministicScorer(new PyramidingRules(), new VaMathService(), new VasrdDecisionEngine());

    private IdentifiedCondition cond(String name, String dc, int rating, String system) {
        return IdentifiedCondition.builder()
                .id((long) Math.abs((name + dc).hashCode()) % 100000)
                .claimId(1L).name(name).vasrdCode(dc).bodySystem(system).estimatedRating(rating).build();
    }

    /** A condition carrying a single gap whose description matches the given text. */
    private IdentifiedCondition condWithGap(String name, String dc, int rating, String system,
                                            String gapText) {
        IdentifiedCondition c = cond(name, dc, rating, system);
        c.setGaps(List.of(Map.of("gap", gapText, "severity", "high")));
        return c;
    }

    private GoldenExpectation.Band band(int lo, int hi) {
        return new GoldenExpectation.Band(lo, hi);
    }

    private GoldenExpectation.ConditionExpectation ce(String pat, String dc, int lo, int hi,
                                                      boolean must, boolean det) {
        return new GoldenExpectation.ConditionExpectation(pat, dc, null, null, band(lo, hi),
                null, must, det ? Boolean.TRUE : null);
    }

    /** The gc-001-shaped expectation: PTSD + tinnitus(det) + knee, no hypertension. */
    private GoldenExpectation.PhaseExpectation gc001Expectation() {
        return new GoldenExpectation.PhaseExpectation(
                "complete",
                List.of(ce("(?i)PTSD|post.?traumatic", "9411", 50, 70, true, false),
                        ce("(?i)tinnitus", "6260", 10, 10, true, true),
                        ce("(?i)knee", "5260", 10, 30, true, false)),
                List.of(new GoldenExpectation.ForbiddenCondition("7101", "hypertension ruled out")),
                band(70, 90),
                List.of(new GoldenExpectation.GapExpectation("9411", "(?i)nexus|medical opinion", true)),
                List.of(new GoldenExpectation.PyramidingExpectation("6260", 10, null, null)),
                new GoldenExpectation.Abstention(false), null,null, null, null);
    }

    private List<Atom> tinnitusAtoms() {
        return List.of(Atom.builder().claimId(1L).type("diagnosis")
                .value("Tinnitus, constant ringing").source("audiology").build());
    }

    private PipelineEndState endState(List<IdentifiedCondition> active, List<Atom> atoms) {
        return new PipelineEndState("complete", new Claim(), active, active, atoms, List.of(), List.of());
    }

    private final List<IdentifiedCondition> healthy = List.of(
            condWithGap("Post-traumatic stress disorder", "9411", 70, "mental",
                    "No nexus medical opinion linking PTSD to the in-service stressor"),
            cond("Tinnitus, bilateral", "6260", 10, "ear"),
            cond("Right knee strain", "5260", 20, "musculoskeletal"));

    @Test
    void baseline_healthyEndStatePasses() {
        var score = scorer.score("gc-001", "initial", gc001Expectation(),
                endState(healthy, tinnitusAtoms()), Map.of());
        assertTrue(score.passed(), "the healthy fixture must pass — failures: " + score.failures());
    }

    @Test
    void mutation_droppedCondition_failsRecall() {
        // regression: the knee condition disappeared.
        var broken = List.of(
                cond("Post-traumatic stress disorder", "9411", 70, "mental"),
                cond("Tinnitus, bilateral", "6260", 10, "ear"));
        var score = scorer.score("gc-001", "initial", gc001Expectation(),
                endState(broken, tinnitusAtoms()), Map.of());
        assertFalse(score.passed(), "a dropped must-find condition must fail the scorer");
        assertTrue(score.conditionRecall() < 1.0);
    }

    @Test
    void mutation_wrongRating_failsBand() {
        // regression: PTSD silently re-rated to 10 (out of the 50-70 band).
        var broken = List.of(
                cond("Post-traumatic stress disorder", "9411", 10, "mental"),
                cond("Tinnitus, bilateral", "6260", 10, "ear"),
                cond("Right knee strain", "5260", 20, "musculoskeletal"));
        var score = scorer.score("gc-001", "initial", gc001Expectation(),
                endState(broken, tinnitusAtoms()), Map.of());
        assertFalse(score.passed(), "a rating outside the band must fail the scorer");
    }

    @Test
    void mutation_forbiddenConditionLeaked_fails() {
        // regression: hypertension (explicitly ruled out) leaked into the active gen.
        var broken = List.of(
                cond("Post-traumatic stress disorder", "9411", 70, "mental"),
                cond("Tinnitus, bilateral", "6260", 10, "ear"),
                cond("Right knee strain", "5260", 20, "musculoskeletal"),
                cond("Hypertension", "7101", 10, "cardiovascular"));
        var score = scorer.score("gc-001", "initial", gc001Expectation(),
                endState(broken, tinnitusAtoms()), Map.of());
        assertFalse(score.passed(), "a leaked forbidden condition must fail the scorer");
        assertTrue(score.forbiddenHit());
    }

    @Test
    void mutation_tinnitusNotClamped_failsPyramiding() {
        // regression: tinnitus rated 30 with no clamp note — pyramiding broke.
        var broken = List.of(
                cond("Post-traumatic stress disorder", "9411", 70, "mental"),
                cond("Tinnitus, bilateral", "6260", 30, "ear"),
                cond("Right knee strain", "5260", 20, "musculoskeletal"));
        var score = scorer.score("gc-001", "initial", gc001Expectation(),
                endState(broken, tinnitusAtoms()), Map.of());
        assertFalse(score.passed(), "tinnitus above its 10% cap must fail the pyramiding invariant");
    }

    @Test
    void mutation_abstentionSilentlyZeroed_failsOutcome() {
        // regression: the silent-zero bug — pipeline returned zero conditions where
        // the expectation requires three. Outcome flips to complete_zero_conditions.
        var endState = new PipelineEndState("complete_zero_conditions", new Claim(),
                List.of(), List.of(), tinnitusAtoms(), List.of(), List.of());
        var score = scorer.score("gc-001", "initial", gc001Expectation(), endState, Map.of());
        assertFalse(score.passed(), "a silent-zero regression must fail (outcome + recall)");
    }

    @Test
    void mutation_deterministicEngineRegressed_fails() {
        // regression: the tinnitus diagnosis atom no longer reaches the engine
        // (e.g. extraction dropped it), so VasrdDecisionEngine can't fire.
        var score = scorer.score("gc-001", "initial", gc001Expectation(),
                endState(healthy, List.of()), Map.of());
        assertFalse(score.passed(), "a deterministic-engine regression must fail the scorer");
        assertFalse(score.deterministicRatingOk());
    }

    /**
     * The gc-013 regression the case was authored to catch (review major #2): an
     * unreadable scan is silently marked 'processed' with empty atoms instead of
     * 'error'. The pipeline still completes zero conditions — byte-identical OUTCOME to
     * the legitimately-empty gc-015 (DD-214) and gc-019 (clean exam) — so before the
     * evidence-state assertion existed, this passed. The expected_evidence assertion is
     * the ONLY thing that distinguishes honest abstention-on-unreadable from a silent
     * zero; this mutation proves it now FAILS.
     */
    @Test
    void mutation_unreadableSilentlyProcessed_failsEvidenceState() {
        var gc013 = new GoldenExpectation.PhaseExpectation("complete_zero_conditions", List.of(),
                null, null, null, null, new GoldenExpectation.Abstention(false),
                List.of(new GoldenExpectation.EvidenceExpectation("(?i)bad.?scan", "error", true)),
                null, null, null);

        // The HONEST end-state passes (error + plain message).
        var honest = new PipelineEndState("complete_zero_conditions", new Claim(),
                List.of(), List.of(), List.of(),
                List.of(evidence("Bad_scan.txt", "error", "We couldn't read this document.")),
                List.of());
        assertTrue(scorer.score("gc-013", "initial", gc013, honest, Map.of()).passed(),
                "abstention-on-unreadable with error+message is honest and must pass");

        // The SILENT-ZERO mutation fails: marked 'processed', no message.
        var silentZero = new PipelineEndState("complete_zero_conditions", new Claim(),
                List.of(), List.of(), List.of(),
                List.of(evidence("Bad_scan.txt", "processed", null)),
                List.of());
        var score = scorer.score("gc-013", "initial", gc013, silentZero, Map.of());
        assertFalse(score.passed(), "a silently-processed unreadable doc must fail the scorer");
        assertFalse(score.evidenceStateOk());
    }

    private com.afterduty.model.EvidenceItem evidence(String filename, String status, String message) {
        var ev = com.afterduty.model.EvidenceItem.builder()
                .claimId(1L).filename(filename).processingStatus(status).build();
        ev.setProcessingMessage(message);
        return ev;
    }
}
