package com.afterduty.exception;

/**
 * Thrown by {@code RecoveryService} when a factor-2 recovery is attempted for a
 * SINGLE-CHANNEL legacy account — one that is missing an email OR a phone, so the
 * dual-channel recovery ceremony (a FRESH email code AND a fresh phone proof)
 * physically cannot be satisfied (auth program P1.5). Rendered by
 * {@code RestExceptionHandler} as the pinned recovery contract:
 *
 * <pre>409 { "code": "recovery_needs_support" }</pre>
 *
 * <p>The client branches on {@code code} to route the veteran to the support-hold
 * path (a human-assisted recovery) rather than showing a generic error. The
 * {@code code} is stable and machine-readable; no PII/why-detail is carried.
 *
 * <p><b>Anti-enumeration note:</b> this is only reachable on the VERIFY step,
 * after the caller has already proven a fresh email code AND phone possession —
 * i.e. they demonstrably own the account. It never fires on {@code start} (which
 * is always 200) so it can't be used to probe which accounts are single-channel.
 */
public class RecoveryNeedsSupportException extends RuntimeException {

    /** Frozen wire code the client branches on to reach the support-hold path. */
    public static final String CODE = "recovery_needs_support";

    public RecoveryNeedsSupportException() {
        super(CODE);
    }
}
