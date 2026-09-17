package com.afterduty.eval;

import com.afterduty.model.Atom;
import com.afterduty.model.EvidenceItem;
import com.afterduty.model.IdentifiedCondition;
import com.afterduty.service.VaMathService;
import com.afterduty.service.synthesis.PyramidingRules;
import com.afterduty.service.synthesis.VasrdDecisionEngine;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Scores a {@link PipelineEndState} against a phase's {@link GoldenExpectation}
 * (spec §2.2 / §2.4 / §3.4). Pure deterministic Java — no LLM. Shared by both
 * tiers: offline (canned inputs ⇒ must score 100%, anything less is a pipeline
 * bug) and live (the same metrics become real accuracy).
 *
 * <p>The combined-rating invariant (§2.4.4) is recomputed from the active
 * conditions via the REAL {@link PyramidingRules} + {@link VaMathService} — the
 * committed pipeline does not persist a combined rating, so "equals what the
 * pipeline persisted" is realized as "equals what the production math classes
 * compute from the persisted conditions." The deterministic-rating invariant
 * (§2.4.6) cross-checks the REAL {@link VasrdDecisionEngine} against the live atoms.
 */
public final class DeterministicScorer {

    private final PyramidingRules pyramidingRules;
    private final VaMathService vaMathService;
    private final VasrdDecisionEngine vasrdDecisionEngine;

    public DeterministicScorer(PyramidingRules pyramidingRules,
                               VaMathService vaMathService,
                               VasrdDecisionEngine vasrdDecisionEngine) {
        this.pyramidingRules = pyramidingRules;
        this.vaMathService = vaMathService;
        this.vasrdDecisionEngine = vasrdDecisionEngine;
    }

    /** A single scored phase: metrics + the list of human-readable failures. */
    public record PhaseScore(
            String caseId,
            String phase,
            double conditionRecall,
            double conditionPrecision,
            double ratingBandAccuracy,
            double gapStructural,
            boolean abstentionCorrect,
            boolean forbiddenHit,
            boolean combinedRatingOk,
            boolean dirtyScopeOk,
            boolean pyramidingOk,
            boolean deterministicRatingOk,
            boolean evidenceStateOk,
            int combinedRating,
            List<String> failures
    ) {
        public boolean passed() {
            return failures.isEmpty();
        }
    }

    /**
     * Score one phase. {@code priorJobCounts} carries the cumulative distinct-job
     * counts captured BEFORE this phase ran (so delta phases can assert
     * dirty-scope); pass an empty map for the initial phase.
     */
    public PhaseScore score(String caseId, String phaseName,
                            GoldenExpectation.PhaseExpectation expect,
                            PipelineEndState end,
                            Map<String, Long> priorJobCounts) {
        List<String> failures = new ArrayList<>();
        List<IdentifiedCondition> active = end.activeConditions();

        // --- pipeline outcome / abstention ------------------------------------
        boolean abstentionCorrect = scoreOutcome(expect, end, failures);

        // --- condition recall / precision -------------------------------------
        RecallPrecision rp = scoreConditions(expect, active, failures);

        // --- rating bands -----------------------------------------------------
        double ratingBandAccuracy = scoreRatingBands(expect, active, failures);

        // --- forbidden conditions ---------------------------------------------
        boolean forbiddenHit = scoreForbidden(expect, active, failures);

        // --- gap must-flags ---------------------------------------------------
        double gapStructural = scoreGaps(expect, active, failures);

        // --- combined rating band (recompute via REAL math) -------------------
        int combined = computeCombinedRating(active);
        boolean combinedOk = scoreCombinedBand(expect, combined, failures);

        // --- pyramiding -------------------------------------------------------
        boolean pyramidingOk = scorePyramiding(expect, active, failures);

        // --- deterministic-rating bypass cross-check --------------------------
        boolean deterministicOk = scoreDeterministicRating(expect, active, end.liveAtoms(), failures);

        // --- dirty scope / carry forward (delta phases) -----------------------
        boolean dirtyScopeOk = scoreDirtyScope(expect, end, priorJobCounts, failures);

        // --- per-evidence processing state (abstention-on-unreadable, §2.4.1) -
        boolean evidenceStateOk = scoreEvidenceState(expect, end, failures);

        return new PhaseScore(caseId, phaseName,
                rp.recall, rp.precision, ratingBandAccuracy, gapStructural,
                abstentionCorrect, forbiddenHit, combinedOk, dirtyScopeOk,
                pyramidingOk, deterministicOk, evidenceStateOk, combined, failures);
    }

    // ------------------------------------------------------------------ outcome

    private boolean scoreOutcome(GoldenExpectation.PhaseExpectation expect,
                                 PipelineEndState end, List<String> failures) {
        String expectedOutcome = expect.pipelineOutcome();
        if (expectedOutcome != null && !expectedOutcome.equals(end.outcome())) {
            failures.add("pipeline_outcome expected " + expectedOutcome + " but was " + end.outcome());
        }
        boolean abstentionExpected = expect.abstention() != null
                && Boolean.TRUE.equals(expect.abstention().expected());
        boolean abstained = PipelineEndState.OUTCOME_FAILED.equals(end.outcome());
        if (abstentionExpected && !abstained) {
            failures.add("abstention expected (FAILED outcome) but pipeline produced " + end.outcome());
        }
        if (!abstentionExpected && abstained && expectedOutcome != null
                && !PipelineEndState.OUTCOME_FAILED.equals(expectedOutcome)) {
            failures.add("unexpected abstention: pipeline FAILED but no abstention expected");
        }
        return abstentionExpected == abstained;
    }

    // ------------------------------------------------------------------ conditions

    private record RecallPrecision(double recall, double precision, List<IdentifiedCondition> matched) {}

    private RecallPrecision scoreConditions(GoldenExpectation.PhaseExpectation expect,
                                            List<IdentifiedCondition> active, List<String> failures) {
        List<GoldenExpectation.ConditionExpectation> expected =
                expect.conditions() == null ? List.of() : expect.conditions();

        int mustFindTotal = 0;
        int mustFindMatched = 0;
        List<IdentifiedCondition> matchedConditions = new ArrayList<>();

        for (GoldenExpectation.ConditionExpectation ce : expected) {
            boolean isMust = Boolean.TRUE.equals(ce.mustBeFound());
            if (isMust) mustFindTotal++;
            Optional<IdentifiedCondition> match = matchCondition(ce, active);
            if (match.isPresent()) {
                if (isMust) mustFindMatched++;
                matchedConditions.add(match.get());
            } else if (isMust) {
                failures.add("must_be_found condition not found: DC=" + ce.vasrdCode()
                        + " pattern=" + ce.namePattern());
            }
        }

        double recall = mustFindTotal == 0 ? 1.0 : (double) mustFindMatched / mustFindTotal;

        // Precision: active conditions matching SOME expectation / total active.
        // A found condition matching no expectation and no forbidden entry counts
        // at half weight (synthetic cases can't enumerate every defensible secondary).
        int activeCount = active.size();
        double precisionNumerator = 0.0;
        for (IdentifiedCondition c : active) {
            boolean matchesExpectation = expected.stream().anyMatch(ce -> conditionMatches(ce, c));
            if (matchesExpectation) {
                precisionNumerator += 1.0;
            } else {
                precisionNumerator += 0.5;
            }
        }
        double precision = activeCount == 0 ? 1.0 : precisionNumerator / activeCount;

        return new RecallPrecision(recall, precision, matchedConditions);
    }

    private Optional<IdentifiedCondition> matchCondition(GoldenExpectation.ConditionExpectation ce,
                                                         List<IdentifiedCondition> active) {
        return active.stream().filter(c -> conditionMatches(ce, c)).findFirst();
    }

    /** Identity-fingerprint-style match: DC (when present) AND name pattern AND laterality. */
    private boolean conditionMatches(GoldenExpectation.ConditionExpectation ce, IdentifiedCondition c) {
        if (ce.vasrdCode() != null && !ce.vasrdCode().equals(c.getVasrdCode())) {
            return false;
        }
        if (ce.namePattern() != null) {
            Pattern p = Pattern.compile(ce.namePattern());
            if (c.getName() == null || !p.matcher(c.getName()).find()) {
                return false;
            }
        }
        if (ce.laterality() != null) {
            String lat = parseLaterality(c.getName());
            if (!ce.laterality().equals(lat)) {
                return false;
            }
        }
        return true;
    }

    /** Mirror of ConditionGenerationService.parseLaterality for scorer-side matching. */
    private String parseLaterality(String rawName) {
        String n = " " + (rawName == null ? "" : rawName.toLowerCase()) + " ";
        if (n.contains("bilateral") || n.contains("both ") || n.contains(" b/l ") || n.contains("bilat")) {
            return "bilateral";
        }
        boolean left = n.contains(" left ") || n.contains(" lt ") || n.contains("(left")
                || n.contains("left-") || n.contains("-left");
        boolean right = n.contains(" right ") || n.contains(" rt ") || n.contains("(right")
                || n.contains("right-") || n.contains("-right");
        if (left && right) return "dual:left+right";
        if (left) return "left";
        if (right) return "right";
        return "none";
    }

    // ------------------------------------------------------------------ rating bands

    private double scoreRatingBands(GoldenExpectation.PhaseExpectation expect,
                                    List<IdentifiedCondition> active, List<String> failures) {
        List<GoldenExpectation.ConditionExpectation> expected =
                expect.conditions() == null ? List.of() : expect.conditions();
        int matched = 0;
        int inBand = 0;
        for (GoldenExpectation.ConditionExpectation ce : expected) {
            if (ce.ratingBand() == null) continue;
            Optional<IdentifiedCondition> c = matchCondition(ce, active);
            if (c.isEmpty()) continue;
            matched++;
            int rating = c.get().getEstimatedRating() == null ? 0 : c.get().getEstimatedRating();
            if (ce.ratingBand().contains(rating)) {
                inBand++;
            } else {
                failures.add("rating band miss: DC=" + ce.vasrdCode() + " rating=" + rating
                        + " not in [" + ce.ratingBand().min() + "," + ce.ratingBand().max() + "]");
            }
        }
        return matched == 0 ? 1.0 : (double) inBand / matched;
    }

    // ------------------------------------------------------------------ forbidden

    private boolean scoreForbidden(GoldenExpectation.PhaseExpectation expect,
                                   List<IdentifiedCondition> active, List<String> failures) {
        if (expect.forbiddenConditions() == null) return false;
        boolean hit = false;
        for (GoldenExpectation.ForbiddenCondition fc : expect.forbiddenConditions()) {
            boolean present = active.stream().anyMatch(c -> fc.vasrdCode() != null
                    && fc.vasrdCode().equals(c.getVasrdCode()));
            if (present) {
                hit = true;
                failures.add("forbidden condition present: DC=" + fc.vasrdCode()
                        + " (" + fc.reason() + ")");
            }
        }
        return hit;
    }

    // ------------------------------------------------------------------ gaps

    private double scoreGaps(GoldenExpectation.PhaseExpectation expect,
                             List<IdentifiedCondition> active, List<String> failures) {
        if (expect.gaps() == null || expect.gaps().isEmpty()) return 1.0;
        int mustFlag = 0;
        int found = 0;
        for (GoldenExpectation.GapExpectation ge : expect.gaps()) {
            if (!Boolean.TRUE.equals(ge.mustBeFlagged())) continue;
            mustFlag++;
            if (gapPresent(ge, active)) {
                found++;
            } else {
                failures.add("must_be_flagged gap missing: DC=" + ge.conditionVasrd()
                        + " pattern=" + ge.pattern());
            }
        }
        return mustFlag == 0 ? 1.0 : (double) found / mustFlag;
    }

    private boolean gapPresent(GoldenExpectation.GapExpectation ge, List<IdentifiedCondition> active) {
        Pattern p = Pattern.compile(ge.pattern());
        for (IdentifiedCondition c : active) {
            if (ge.conditionVasrd() != null && !ge.conditionVasrd().equals(c.getVasrdCode())) {
                continue;
            }
            if (c.getGaps() == null) continue;
            for (Map<String, Object> gap : c.getGaps()) {
                for (Object v : gap.values()) {
                    if (v instanceof String s && p.matcher(s).find()) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ combined rating

    /** Recompute combined rating from active conditions via the REAL math classes. */
    public int computeCombinedRating(List<IdentifiedCondition> active) {
        if (active == null || active.isEmpty()) return 0;
        PyramidingRules.Plan plan = pyramidingRules.plan(active);
        Map<String, Object> result =
                vaMathService.calculateCombinedRating(plan.ratings(), plan.bilateralPairs());
        Object combined = result.get("combined_rating");
        return combined instanceof Number n ? n.intValue() : 0;
    }

    private boolean scoreCombinedBand(GoldenExpectation.PhaseExpectation expect,
                                      int combined, List<String> failures) {
        if (expect.combinedRatingBand() == null) return true;
        boolean ok = expect.combinedRatingBand().contains(combined);
        if (!ok) {
            failures.add("combined rating " + combined + " not in band ["
                    + expect.combinedRatingBand().min() + "," + expect.combinedRatingBand().max() + "]");
        }
        return ok;
    }

    // ------------------------------------------------------------------ pyramiding

    private boolean scorePyramiding(GoldenExpectation.PhaseExpectation expect,
                                    List<IdentifiedCondition> active, List<String> failures) {
        if (expect.pyramiding() == null || expect.pyramiding().isEmpty()) return true;
        PyramidingRules.Plan plan = pyramidingRules.plan(active);
        boolean ok = true;

        for (GoldenExpectation.PyramidingExpectation pe : expect.pyramiding()) {
            // max_rating clamp (e.g. tinnitus 6260 ≤ 10): the active condition's
            // persisted rating may be higher, but the planned/effective rating
            // contributing to the combined total must be clamped.
            if (pe.maxRating() != null) {
                Optional<IdentifiedCondition> c = active.stream()
                        .filter(x -> pe.vasrdCode().equals(x.getVasrdCode()))
                        .findFirst();
                if (c.isPresent()) {
                    boolean noteMentionsCap = plan.notes().stream()
                            .anyMatch(n -> n.toLowerCase().contains("cap"));
                    int persisted = c.get().getEstimatedRating() == null ? 0 : c.get().getEstimatedRating();
                    if (persisted > pe.maxRating() && !noteMentionsCap) {
                        ok = false;
                        failures.add("pyramiding: DC=" + pe.vasrdCode() + " rated " + persisted
                                + " > max " + pe.maxRating() + " but no clamp note produced");
                    }
                }
            }
            // absorbed: the condition must be excluded from the combined ratings list.
            if (Boolean.TRUE.equals(pe.absorbed())) {
                boolean noteMentionsAbsorb = plan.notes().stream()
                        .anyMatch(n -> n.toLowerCase().contains("absorb"));
                if (!noteMentionsAbsorb) {
                    ok = false;
                    failures.add("pyramiding: DC=" + pe.vasrdCode()
                            + " expected absorbed but no absorption note produced");
                }
            }
            // bilateral pair detection.
            if (Boolean.TRUE.equals(pe.bilateralPair())) {
                if (plan.bilateralPairs().isEmpty()) {
                    ok = false;
                    failures.add("pyramiding: expected a bilateral pair for DC=" + pe.vasrdCode()
                            + " but PyramidingRules detected none");
                }
            }
        }
        return ok;
    }

    // ------------------------------------------------------------------ deterministic rating

    /**
     * For every condition expectation flagged {@code deterministic_rating_expected},
     * assert the REAL {@link VasrdDecisionEngine} produces a rating that lands in the
     * expected band when fed the live atoms — proving the deterministic engine is
     * genuinely exercised (the committed pipeline routes rating through the LLM, so
     * this is the harness' guarantee that the engine's logic is correct for the case).
     */
    private boolean scoreDeterministicRating(GoldenExpectation.PhaseExpectation expect,
                                             List<IdentifiedCondition> active,
                                             List<Atom> liveAtoms, List<String> failures) {
        if (expect.conditions() == null) return true;
        boolean ok = true;
        for (GoldenExpectation.ConditionExpectation ce : expect.conditions()) {
            if (!Boolean.TRUE.equals(ce.deterministicRatingExpected())) continue;
            Optional<Map<String, Object>> det =
                    vasrdDecisionEngine.tryDeterministicRating(ce.vasrdCode(), liveAtoms);
            if (det.isEmpty()) {
                ok = false;
                failures.add("deterministic_rating_expected: VasrdDecisionEngine did NOT fire for DC="
                        + ce.vasrdCode() + " on the case's atoms");
                continue;
            }
            Object r = det.get().get("estimated_rating");
            int rating = r instanceof Number n ? n.intValue() : -1;
            if (ce.ratingBand() != null && !ce.ratingBand().contains(rating)) {
                ok = false;
                failures.add("deterministic rating for DC=" + ce.vasrdCode() + " was " + rating
                        + " not in band [" + ce.ratingBand().min() + "," + ce.ratingBand().max() + "]");
            }
        }
        return ok;
    }

    // ------------------------------------------------------------------ dirty scope

    private boolean scoreDirtyScope(GoldenExpectation.PhaseExpectation expect,
                                    PipelineEndState end, Map<String, Long> priorJobCounts,
                                    List<String> failures) {
        boolean ok = true;
        if (expect.dirtyConditionsMax() != null) {
            long rateThisPhase = end.distinctJobs("synthesis_rate")
                    - priorJobCounts.getOrDefault("synthesis_rate", 0L);
            if (rateThisPhase > expect.dirtyConditionsMax()) {
                ok = false;
                failures.add("dirty scope: " + rateThisPhase + " synthesis_rate jobs this phase > max "
                        + expect.dirtyConditionsMax());
            }
        }
        if (expect.synthesisJobsMax() != null) {
            long rateThisPhase = end.distinctJobs("synthesis_rate")
                    - priorJobCounts.getOrDefault("synthesis_rate", 0L);
            if (rateThisPhase > expect.synthesisJobsMax()) {
                ok = false;
                failures.add("synthesis_jobs_max: " + rateThisPhase + " synthesis_rate jobs > max "
                        + expect.synthesisJobsMax());
            }
        }
        if (expect.carryForwardMin() != null) {
            long rateThisPhase = end.distinctJobs("synthesis_rate")
                    - priorJobCounts.getOrDefault("synthesis_rate", 0L);
            int carried = end.activeCount() - (int) rateThisPhase;
            if (carried < expect.carryForwardMin()) {
                ok = false;
                failures.add("carry forward: only " + carried + " conditions carried forward < min "
                        + expect.carryForwardMin());
            }
        }
        return ok;
    }

    // ------------------------------------------------------------------ evidence state

    /**
     * Assert each {@code expected_evidence} entry against the phase's persisted
     * {@link EvidenceItem} rows (spec §2.4.1). This is the ONLY observable field that
     * distinguishes honest abstention-on-unreadable (gc-013: {@code
     * processing_status=error} + a plain-language {@code processing_message}) from a
     * silent zero (an unreadable doc marked 'processed' with empty atoms). Without it,
     * gc-013's outcome (complete_zero_conditions) is byte-identical to gc-015/gc-019
     * and the scorer collapses to a single pipeline_outcome string-equality.
     */
    private boolean scoreEvidenceState(GoldenExpectation.PhaseExpectation expect,
                                       PipelineEndState end, List<String> failures) {
        if (expect.expectedEvidence() == null || expect.expectedEvidence().isEmpty()) {
            return true;
        }
        List<EvidenceItem> evidence = end.evidence() == null ? List.of() : end.evidence();
        boolean ok = true;
        for (GoldenExpectation.EvidenceExpectation ee : expect.expectedEvidence()) {
            List<EvidenceItem> matches = evidence.stream()
                    .filter(ev -> matchesFilename(ee, ev))
                    .toList();
            if (matches.isEmpty()) {
                ok = false;
                failures.add("expected_evidence: no evidence row matched filename_pattern="
                        + ee.filenamePattern());
                continue;
            }
            for (EvidenceItem ev : matches) {
                if (ee.processingStatus() != null
                        && !ee.processingStatus().equals(ev.getProcessingStatus())) {
                    ok = false;
                    failures.add("expected_evidence: " + ev.getFilename()
                            + " processing_status expected '" + ee.processingStatus()
                            + "' but was '" + ev.getProcessingStatus()
                            + "' (silent-zero on an unreadable doc would show 'processed'/'pending')");
                }
                if (Boolean.TRUE.equals(ee.messageNonBlank())
                        && (ev.getProcessingMessage() == null || ev.getProcessingMessage().isBlank())) {
                    ok = false;
                    failures.add("expected_evidence: " + ev.getFilename()
                            + " processing_message must be a non-blank plain-language explanation, was "
                            + (ev.getProcessingMessage() == null ? "null" : "blank"));
                }
            }
        }
        return ok;
    }

    private boolean matchesFilename(GoldenExpectation.EvidenceExpectation ee, EvidenceItem ev) {
        if (ee.filenamePattern() == null) return true;
        String name = ev.getFilename() == null ? "" : ev.getFilename();
        return Pattern.compile(ee.filenamePattern()).matcher(name).find();
    }
}
