package com.afterduty.eval;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * The live-tier RUN LOOP (spec §3.3) — the piece the adversarial review found
 * missing: the thing that actually drives the roster case-by-case and lets the
 * {@link EvalSpendGuard} abort a runaway run MID-ROSTER.
 *
 * <p>Before Increment 8's review this protocol lived only as prose in
 * {@link LiveGoldenEvalTest}'s javadoc and as a simulated loop inside
 * {@code EvalSpendGuardTest}. The guard's latch logic was unit-tested against a
 * canned {@code BigDecimal} supplier, but nothing bound that supplier to a real
 * cost ledger and nothing drove real cases through a guarded loop — so "abort
 * remaining cases mid-run" could not actually happen. This class is that loop,
 * extracted so BOTH the live runner ({@link LiveGoldenEvalTest}) AND the offline
 * enforcement test ({@code SpendCapEnforcementTest}, which binds the guard to a
 * REAL {@code AiCallLogRepository.totalCostSince} against H2) exercise the exact
 * same code path. The cap is now proven as ENFORCEMENT, not just as a decision
 * unit in isolation.
 *
 * <p>Protocol (spec §3.3), enforced here:
 * <ol>
 *   <li>{@code guard.canAttemptAnotherCase()} is checked BEFORE each case. If the
 *       cap is already reached, the case (and every case after it) is marked
 *       {@link CaseOutcome.Status#SKIPPED} without running.</li>
 *   <li>Otherwise the case body runs; it is expected to book its real
 *       {@code AiCallLog} rows (pipeline + judge) into the ledger the guard reads.</li>
 *   <li>{@code guard.check()} is called AFTER the case's tick-batch so a case that
 *       pushed the running total over the cap trips the latch — and every REMAINING
 *       case is then skipped.</li>
 * </ol>
 *
 * <p>The result records, per case, its terminal status, and — when the guard
 * tripped — stamps {@link #abortedReason()} = {@link EvalSpendGuard#ABORTED_REASON}
 * so the report's cost block carries {@code "aborted_reason": "spend_cap"}.
 */
public final class EvalRunLoop {

    /** Terminal status for one case in the loop. */
    public record CaseOutcome(String caseId, Status status, String detail) {
        public enum Status { SCORED, SKIPPED, TIMEOUT, ERROR }
    }

    /** The full run result: ordered per-case outcomes + the abort reason (or null). */
    public record RunResult(List<CaseOutcome> outcomes, String abortedReason) {
        public CaseOutcome forCase(String caseId) {
            return outcomes.stream().filter(o -> o.caseId().equals(caseId)).findFirst().orElse(null);
        }
        public boolean aborted() {
            return abortedReason != null;
        }
    }

    private final EvalSpendGuard guard;
    private final List<CaseOutcome> outcomes = new ArrayList<>();
    private final Map<String, Status> overrides = new LinkedHashMap<>();
    private String abortedReason;

    /** A per-case status the body can request (e.g. TIMEOUT) without aborting the run. */
    public enum Status { TIMEOUT, ERROR }

    public EvalRunLoop(EvalSpendGuard guard) {
        this.guard = guard;
    }

    /**
     * Run every case id in order under the spend cap. {@code caseBody} runs one case
     * end-to-end (drive pipeline → judge → score → book cost). It may call
     * {@link #markTimeout(String)} / {@link #markError(String)} on the supplied id to
     * record a non-fatal per-case outcome that still counts as "attempted".
     *
     * @return the ordered outcomes + aborted reason (null if the cap never tripped).
     */
    public RunResult run(List<String> caseIds, Consumer<String> caseBody) {
        for (String caseId : caseIds) {
            // §3.3 — check BEFORE each case. Once the cap is reached, this case and
            // all remaining cases are skipped.
            if (!guard.canAttemptAnotherCase()) {
                abortedReason = EvalSpendGuard.ABORTED_REASON;
                outcomes.add(new CaseOutcome(caseId, CaseOutcome.Status.SKIPPED,
                        "spend cap reached before this case"));
                continue;
            }

            overrides.remove(caseId);
            try {
                caseBody.accept(caseId);   // books real AiCallLog rows into the ledger
            } catch (RuntimeException e) {
                overrides.put(caseId, Status.ERROR);
            }

            Status override = overrides.get(caseId);
            if (override == Status.TIMEOUT) {
                outcomes.add(new CaseOutcome(caseId, CaseOutcome.Status.TIMEOUT, "per-case wall-clock budget"));
            } else if (override == Status.ERROR) {
                outcomes.add(new CaseOutcome(caseId, CaseOutcome.Status.ERROR, "case threw"));
            } else {
                outcomes.add(new CaseOutcome(caseId, CaseOutcome.Status.SCORED, null));
            }

            // §3.3 — check AFTER the tick-batch. If this case crossed the cap, latch the
            // guard so the NEXT iteration's canAttemptAnotherCase() skips the remainder.
            if (guard.check() && abortedReason == null) {
                abortedReason = EvalSpendGuard.ABORTED_REASON;
            }
        }
        return new RunResult(List.copyOf(outcomes), abortedReason);
    }

    /** Mark the in-flight case as timed out (per-case budget) — run continues (spec §3.3). */
    public void markTimeout(String caseId) {
        overrides.put(caseId, Status.TIMEOUT);
    }

    /** Mark the in-flight case as errored — run continues. */
    public void markError(String caseId) {
        overrides.put(caseId, Status.ERROR);
    }
}
