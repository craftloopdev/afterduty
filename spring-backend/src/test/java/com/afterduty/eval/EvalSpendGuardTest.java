package com.afterduty.eval;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Offline test for the live-tier spend-cap abort logic ({@link EvalSpendGuard},
 * spec §3.3 / §8 "Offline tests for: spend-cap abort logic"). No live calls — the
 * running cost total is a canned supplier, so the abort DECISION is verified
 * deterministically.
 */
@Tag("regression")
class EvalSpendGuardTest {

    @Test
    void underCap_doesNotTrip() {
        EvalSpendGuard guard = new EvalSpendGuard(15.00, () -> new BigDecimal("4.20"));
        assertFalse(guard.check(), "well under the cap");
        assertFalse(guard.isTripped());
        assertTrue(guard.canAttemptAnotherCase());
        assertEquals(0, new BigDecimal("10.80").compareTo(guard.remainingUsd()), "headroom = cap - spent");
    }

    @Test
    void atCapExactly_trips_inclusiveBoundary() {
        // spending exactly the cap is "at the cap" ⇒ abort (judge spend must stay inside the cap).
        EvalSpendGuard guard = new EvalSpendGuard(15.00, () -> new BigDecimal("15.00"));
        assertTrue(guard.check(), "reaching the cap exactly trips");
        assertFalse(guard.canAttemptAnotherCase());
        assertEquals(0, BigDecimal.ZERO.compareTo(guard.remainingUsd()), "no headroom at the cap");
    }

    @Test
    void overCap_trips() {
        EvalSpendGuard guard = new EvalSpendGuard(15.00, () -> new BigDecimal("15.01"));
        assertTrue(guard.check());
        assertEquals(EvalSpendGuard.ABORTED_REASON, "spend_cap");
    }

    @Test
    void tripIsLatched_aRefundDoesNotUnAbort() {
        // The supplier returns over-cap on the first check, then a lower number (refund/
        // rounding wobble). Once tripped, the run stays aborted — never un-aborts mid-flight.
        AtomicReference<BigDecimal> total = new AtomicReference<>(new BigDecimal("16.00"));
        EvalSpendGuard guard = new EvalSpendGuard(15.00, total::get);

        assertTrue(guard.check(), "trips on the over-cap reading");
        total.set(new BigDecimal("2.00"));               // a later, lower reading
        assertTrue(guard.check(), "stays tripped despite the lower reading");
        assertTrue(guard.isTripped());
        assertFalse(guard.canAttemptAnotherCase());
    }

    @Test
    void crossesCapBetweenChecks_tripsOnTheCrossingCheck() {
        // Models the runner's "check before each case + after each tick-batch" cadence:
        // spend ratchets up as cases run; the guard trips the moment it crosses.
        AtomicReference<BigDecimal> total = new AtomicReference<>(new BigDecimal("5.00"));
        EvalSpendGuard guard = new EvalSpendGuard(15.00, total::get);

        assertFalse(guard.check(), "case 1 — under cap");
        total.set(new BigDecimal("11.00"));
        assertFalse(guard.check(), "case 2 — still under cap");
        total.set(new BigDecimal("15.50"));
        assertTrue(guard.check(), "case 3 — crossed the cap, abort remaining");
    }

    @Test
    void nullLedgerSumIsTreatedAsZero() {
        // An empty AiCallLog ledger (no rows yet) sums to null/zero — must not NPE or trip.
        EvalSpendGuard guard = new EvalSpendGuard(15.00, () -> null);
        assertFalse(guard.check());
        assertEquals(0, BigDecimal.ZERO.compareTo(guard.spentUsd()));
    }

    @Test
    void rejectsNonPositiveCap() {
        assertThrows(IllegalArgumentException.class,
                () -> new EvalSpendGuard(0.0, () -> BigDecimal.ZERO));
        assertThrows(IllegalArgumentException.class,
                () -> new EvalSpendGuard(-1.0, () -> BigDecimal.ZERO));
    }

    /**
     * The full runner contract (spec §3.3) proven offline: when the ledger crosses the
     * cap mid-roster, the guard trips, every REMAINING case is marked {@code skipped},
     * and the report's cost block carries {@code aborted_reason = "spend_cap"}.
     */
    @Test
    void runnerAbortProtocol_skipsRemaining_andStampsAbortedReason() {
        // gc-001 books $6, gc-002 books $10 (cumulative $16 > $15 cap) ⇒ gc-003 skipped.
        AtomicReference<BigDecimal> ledger = new AtomicReference<>(BigDecimal.ZERO);
        EvalSpendGuard guard = new EvalSpendGuard(15.00, ledger::get);

        var perCaseCost = new java.util.LinkedHashMap<String, BigDecimal>();
        perCaseCost.put("gc-001", new BigDecimal("6.00"));
        perCaseCost.put("gc-002", new BigDecimal("10.00"));
        perCaseCost.put("gc-003", new BigDecimal("4.00"));

        var statuses = new java.util.LinkedHashMap<String, String>();
        for (var e : perCaseCost.entrySet()) {
            if (!guard.canAttemptAnotherCase()) {        // checked BEFORE each case
                statuses.put(e.getKey(), "skipped");
                continue;
            }
            // "run" the case: it books its cost into the ledger…
            ledger.updateAndGet(v -> v.add(e.getValue()));
            statuses.put(e.getKey(), "scored");
            guard.check();                                // …then check again after the tick-batch
        }

        assertEquals("scored", statuses.get("gc-001"));
        assertEquals("scored", statuses.get("gc-002"), "still allowed to START at $6 (< cap)");
        assertEquals("skipped", statuses.get("gc-003"), "ledger at $16 ⇒ remaining case skipped");
        assertTrue(guard.isTripped());

        String abortedReason = guard.isTripped() ? EvalSpendGuard.ABORTED_REASON : null;
        assertEquals("spend_cap", abortedReason);
    }
}
