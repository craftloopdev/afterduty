package com.afterduty.eval;

import java.math.BigDecimal;
import java.util.function.Supplier;

/**
 * Spend-cap enforcement for the live tier (spec §3.3). The live runner books every
 * pipeline and judge call as a real {@code AiCallLog} row (via the real
 * {@code AiCostService} wired by {@link LiveEvalProviderConfig}); this guard is the
 * pure decision unit that sums those logged costs and decides whether the run has
 * exceeded {@code va-claim.eval.live.max-spend-usd} (default $15, judge included).
 *
 * <p>It is intentionally decoupled from the live Spring context — the cost total is
 * injected as a {@link Supplier} (in the live runner, bound to
 * {@code AiCallLogRepository.totalCostSince(runStart)} so it covers BOTH pipeline
 * rows and the {@code callType="eval_judge"} rows). That makes the abort logic
 * fully offline-testable with a canned cost supplier (spec §8 "Offline tests for:
 * spend-cap abort logic"), with zero live calls.
 *
 * <p>Protocol (spec §3.3): the runner calls {@link #check()} before each case and
 * after each pipeline stage tick-batch. The first time the running total meets or
 * exceeds the cap, the guard {@code trips} — the runner then aborts the remaining
 * cases, marks them {@code skipped}, and finishes the report with
 * {@code aborted_reason = "spend_cap"}. Once tripped it stays tripped (a refund/
 * rounding wobble must not "un-abort" a run).
 */
public final class EvalSpendGuard {

    /** The aborted-reason stamped on the report's cost block when the cap trips. */
    public static final String ABORTED_REASON = "spend_cap";

    private final BigDecimal capUsd;
    private final Supplier<BigDecimal> totalSpentUsd;
    private boolean tripped;

    /**
     * @param capUsd        the run's hard cap (USD); from {@code EvalProperties.Live.maxSpendUsd}
     * @param totalSpentUsd supplies the run's running total spend (pipeline + judge rows)
     */
    public EvalSpendGuard(double capUsd, Supplier<BigDecimal> totalSpentUsd) {
        this(BigDecimal.valueOf(capUsd), totalSpentUsd);
    }

    public EvalSpendGuard(BigDecimal capUsd, Supplier<BigDecimal> totalSpentUsd) {
        if (capUsd == null || capUsd.signum() <= 0) {
            throw new IllegalArgumentException("eval spend cap must be > 0, was: " + capUsd);
        }
        this.capUsd = capUsd;
        this.totalSpentUsd = totalSpentUsd;
    }

    /** The configured cap (USD). */
    public BigDecimal capUsd() {
        return capUsd;
    }

    /** Current logged total (USD), null-coalesced to zero (an empty ledger sums to 0). */
    public BigDecimal spentUsd() {
        BigDecimal v = totalSpentUsd.get();
        return v == null ? BigDecimal.ZERO : v;
    }

    /** Remaining headroom (USD); never below zero once the cap is reached. */
    public BigDecimal remainingUsd() {
        BigDecimal r = capUsd.subtract(spentUsd());
        return r.signum() < 0 ? BigDecimal.ZERO : r;
    }

    /** True once the cap has been reached on any prior {@link #check()} — latched. */
    public boolean isTripped() {
        return tripped;
    }

    /**
     * Re-evaluate against the live ledger. Returns {@code true} when the run must
     * abort (running total ≥ cap). Latches: once tripped, stays tripped so a later
     * cost refund/rounding wobble cannot un-abort a run mid-flight.
     */
    public boolean check() {
        if (tripped) {
            return true;
        }
        // total ≥ cap ⇒ trip (inclusive: spending exactly the cap is "at the cap").
        if (spentUsd().compareTo(capUsd) >= 0) {
            tripped = true;
        }
        return tripped;
    }

    /**
     * Convenience for the runner's per-case gate: {@code true} if there is still
     * headroom to attempt another case (i.e. the cap is not yet reached).
     */
    public boolean canAttemptAnotherCase() {
        return !check();
    }
}
