package com.afterduty.exception;

import java.util.List;

/**
 * Thrown by {@code StepUpService.requireFresh} when a sensitive endpoint is hit
 * without a valid fresh step-up (auth program P1.2). Rendered by
 * {@code RestExceptionHandler} as the pinned contract:
 *
 * <pre>403 { "code": "step_up_required", "acceptedFactors": ["otp"] }</pre>
 *
 * <p>The accepted-factors list is server-controlled (only {@code otp} in Phase 1;
 * {@code passkey}/{@code biometric} are added by later increments) so the client
 * knows which ceremony to run before retrying.
 */
public class StepUpRequiredException extends RuntimeException {

    /** Frozen wire code the client's ApiClient interceptor branches on. */
    public static final String CODE = "step_up_required";

    private final List<String> acceptedFactors;

    public StepUpRequiredException() {
        this(List.of("otp"));
    }

    public StepUpRequiredException(List<String> acceptedFactors) {
        super("step_up_required");
        this.acceptedFactors = List.copyOf(acceptedFactors);
    }

    public List<String> getAcceptedFactors() {
        return acceptedFactors;
    }
}
