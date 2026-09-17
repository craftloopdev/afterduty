package com.afterduty.eval;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit coverage for {@link EvalRunLoop} — the live-tier run loop (spec §3.3) the
 * review found missing. These tests use a canned cost supplier (no DB, no live
 * calls) to pin the loop's protocol in isolation; {@code SpendCapEnforcementTest}
 * separately proves the SAME loop against a real {@code AiCallLogRepository} ledger.
 */
@Tag("regression")
class EvalRunLoopTest {

    @Test
    void allCasesScored_whenUnderCap_noAbort() {
        EvalSpendGuard guard = new EvalSpendGuard(15.00, () -> new BigDecimal("1.00"));
        EvalRunLoop loop = new EvalRunLoop(guard);
        List<String> ran = new ArrayList<>();

        EvalRunLoop.RunResult result = loop.run(List.of("gc-001", "gc-002", "gc-003"), ran::add);

        assertEquals(List.of("gc-001", "gc-002", "gc-003"), ran, "every case ran in order");
        result.outcomes().forEach(o ->
                assertEquals(EvalRunLoop.CaseOutcome.Status.SCORED, o.status()));
        assertFalse(result.aborted());
        assertNull(result.abortedReason());
    }

    @Test
    void abortsMidRoster_andSkipsRemainder_whenLedgerCrosses() {
        // Spend ratchets as each case "runs": $5 → $11 → $20. The cap ($15) is crossed
        // by case 3's spend, so case 3 STARTS (ledger was $11 < cap before it), then the
        // post-case check trips, so case 4 is skipped.
        AtomicReference<BigDecimal> ledger = new AtomicReference<>(new BigDecimal("0.00"));
        BigDecimal[] perCase = {
                new BigDecimal("5.00"), new BigDecimal("6.00"),
                new BigDecimal("9.00"), new BigDecimal("3.00")
        };
        EvalSpendGuard guard = new EvalSpendGuard(15.00, ledger::get);
        EvalRunLoop loop = new EvalRunLoop(guard);

        int[] idx = {0};
        EvalRunLoop.RunResult result =
                loop.run(List.of("gc-001", "gc-002", "gc-003", "gc-004"),
                        id -> ledger.updateAndGet(v -> v.add(perCase[idx[0]++])));

        assertEquals(EvalRunLoop.CaseOutcome.Status.SCORED, result.forCase("gc-001").status());
        assertEquals(EvalRunLoop.CaseOutcome.Status.SCORED, result.forCase("gc-002").status());
        assertEquals(EvalRunLoop.CaseOutcome.Status.SCORED, result.forCase("gc-003").status(),
                "case 3 started while the ledger was $11 (< cap), then pushed it to $20");
        assertEquals(EvalRunLoop.CaseOutcome.Status.SKIPPED, result.forCase("gc-004").status(),
                "ledger at $20 ⇒ case 4 skipped (mid-run abort)");
        assertTrue(result.aborted());
        assertEquals(EvalSpendGuard.ABORTED_REASON, result.abortedReason());
    }

    @Test
    void perCaseTimeout_isRecorded_butRunContinues() {
        EvalSpendGuard guard = new EvalSpendGuard(15.00, () -> BigDecimal.ZERO);
        EvalRunLoop loop = new EvalRunLoop(guard);

        EvalRunLoop.RunResult result = loop.run(List.of("gc-001", "gc-002"), id -> {
            if (id.equals("gc-001")) loop.markTimeout(id);
        });

        assertEquals(EvalRunLoop.CaseOutcome.Status.TIMEOUT, result.forCase("gc-001").status(),
                "a timed-out case is recorded TIMEOUT, not aborted");
        assertEquals(EvalRunLoop.CaseOutcome.Status.SCORED, result.forCase("gc-002").status(),
                "the run continues past a per-case timeout (spec §3.3)");
        assertFalse(result.aborted());
    }

    @Test
    void caseBodyThrowing_isRecordedAsError_runContinues() {
        EvalSpendGuard guard = new EvalSpendGuard(15.00, () -> BigDecimal.ZERO);
        EvalRunLoop loop = new EvalRunLoop(guard);

        EvalRunLoop.RunResult result = loop.run(List.of("gc-001", "gc-002"), id -> {
            if (id.equals("gc-001")) throw new RuntimeException("boom");
        });

        assertEquals(EvalRunLoop.CaseOutcome.Status.ERROR, result.forCase("gc-001").status());
        assertEquals(EvalRunLoop.CaseOutcome.Status.SCORED, result.forCase("gc-002").status());
        assertFalse(result.aborted(), "a thrown case does not abort the whole run");
    }

    @Test
    void allRemainingSkipped_whenCapAlreadyReachedBeforeFirstCase() {
        // The guard is already tripped before the loop starts ⇒ no case runs.
        EvalSpendGuard guard = new EvalSpendGuard(15.00, () -> new BigDecimal("20.00"));
        EvalRunLoop loop = new EvalRunLoop(guard);
        List<String> ran = new ArrayList<>();

        EvalRunLoop.RunResult result = loop.run(List.of("gc-001", "gc-002"), ran::add);

        assertTrue(ran.isEmpty(), "no case body ran — cap already reached");
        result.outcomes().forEach(o ->
                assertEquals(EvalRunLoop.CaseOutcome.Status.SKIPPED, o.status()));
        assertEquals(EvalSpendGuard.ABORTED_REASON, result.abortedReason());
    }
}
