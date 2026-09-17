// Typed BFF errors. RSC/route handlers map upstream HTTP statuses to these so
// callers can branch (401 → redirect, 402 → degrade, 403 → forbidden) instead
// of inspecting raw responses.

export class UnauthorizedError extends Error {
  constructor(message = "Not authenticated") {
    super(message);
    this.name = "UnauthorizedError";
  }
}

export class ForbiddenError extends Error {
  constructor(message = "Forbidden") {
    super(message);
    this.name = "ForbiddenError";
  }
}

/**
 * A guarded sensitive endpoint returned `403 {code:"step_up_required"}` — the
 * caller must complete a fresh step-up (OTP today; passkey/biometric later) and
 * retry with the `X-Step-Up` header (PINNED step-up contract).
 *
 * This is DISTINCT from `ForbiddenError` (a hard authorization deny). Client
 * mutations intercept it and run the step-up ceremony transparently; RSC/route
 * reads (which can't show a modal) surface it as this TYPED error so the page can
 * act on it (e.g. render a "confirm it's you" prompt or link to a step-up flow).
 */
export class StepUpRequiredError extends Error {
  constructor(
    /** Factors the backend will accept for this challenge (e.g. ["otp"]). */
    public readonly acceptedFactors: string[],
    public readonly path?: string,
  ) {
    super("step_up_required");
    this.name = "StepUpRequiredError";
  }
}

export class SubscriptionRequiredError extends Error {
  constructor(public readonly path: string) {
    super(`Subscription required: ${path}`);
    this.name = "SubscriptionRequiredError";
  }
}

export class UpstreamError extends Error {
  constructor(public readonly status: number, message: string) {
    super(message);
    this.name = "UpstreamError";
  }
}
