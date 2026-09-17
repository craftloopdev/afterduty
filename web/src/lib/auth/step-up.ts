"use client";

// The CLIENT step-up seam (auth-program-plan P1.2, client half). Mirrors the
// existing 401-force-refresh-retry seam: when a guarded write returns
// `403 {code:"step_up_required"}`, we DON'T surface an error — we pause, run the
// step-up ceremony (a fresh OTP), then retry the original request ONCE with the
// `X-Step-Up: <token>` header (PINNED step-up contract).
//
// This module is transport-agnostic and UI-agnostic. The React ceremony lives in
// `components/auth/StepUpModal.tsx`; that host REGISTERS an opener here at mount.
// `runStepUp()` calls the registered opener; if no host is mounted (e.g. a page
// that never rendered one), it self-mounts a throwaway one via `createRoot` so a
// mutation is never stuck. Keeping the imperative bridge here (not in the modal)
// means `withStepUp()` has no React import and the two files don't cycle.

import { StepUpRequiredError } from "@/lib/api/errors";

/** The opaque, short-TTL, single-user token returned by the verify endpoint,
 *  with the moment it expires so the cache can self-invalidate. */
interface CachedToken {
  token: string;
  /** epoch ms after which the token is considered stale and re-prompted. */
  expiresAt: number;
}

// A tiny safety margin so we never send a token that expires mid-flight on the
// backend. The contract TTL is 300s; we drop it ~10s early.
const EXPIRY_SKEW_MS = 10_000;

// In-memory only (never persisted): a page reload re-prompts, and sign-out must
// not leave a live step-up token around (`clearStepUpToken()` on the sign-out
// path). Back-to-back guarded calls within the window reuse this and don't
// re-prompt (P1.2 acceptance item).
let cached: CachedToken | null = null;

/** Cache a freshly minted step-up token for `expiresInSec` seconds. */
export function cacheStepUpToken(token: string, expiresInSec: number): void {
  cached = { token, expiresAt: Date.now() + expiresInSec * 1000 - EXPIRY_SKEW_MS };
}

/** The current valid cached token, or null if none / expired. */
export function getCachedStepUpToken(): string | null {
  if (!cached) return null;
  if (Date.now() >= cached.expiresAt) {
    cached = null;
    return null;
  }
  return cached.token;
}

/** Drop any cached step-up token. Call on sign-out (cross-account hygiene) and
 *  whenever the backend rejects a token as expired/invalid. */
export function clearStepUpToken(): void {
  cached = null;
}

/** The result of the step-up ceremony surfaced back to `runStepUp`. */
export interface StepUpResult {
  token: string;
  expiresInSec: number;
}

/** A function that runs the OTP ceremony UI to completion. Resolves with the
 *  minted token, or rejects if the user cancels (→ `StepUpCancelledError`). */
export type StepUpCeremony = (challenge: { acceptedFactors: string[] }) => Promise<StepUpResult>;

/** Thrown when the user dismisses the step-up ceremony without completing it.
 *  Callers get a clean, typed cancel instead of a raw modal-plumbing error. */
export class StepUpCancelledError extends Error {
  constructor() {
    super("Step-up was cancelled.");
    this.name = "StepUpCancelledError";
  }
}

// The registered ceremony opener (set by the mounted StepUpModal host).
let ceremonyOpener: StepUpCeremony | null = null;
// A fallback self-mounter, injected by the modal module so THIS file never
// imports React/react-dom (keeps the mutation seam framework-free + tree-shaken).
let selfMount: (() => Promise<() => void>) | null = null;
// Resolvers waiting for an opener to appear (self-mount registers async via a
// React effect, so `runStepUp` must await the registration, not read it synchronously).
let openerWaiters: Array<() => void> = [];

/** The StepUpModal host registers its opener here on mount and clears it on
 *  unmount. Returns an unregister fn. */
export function registerStepUpOpener(opener: StepUpCeremony): () => void {
  ceremonyOpener = opener;
  const waiters = openerWaiters;
  openerWaiters = [];
  for (const w of waiters) w();
  return () => {
    if (ceremonyOpener === opener) ceremonyOpener = null;
  };
}

/** Resolve once an opener is registered (or immediately if one already is).
 *  Times out so a broken/never-mounting host can't hang a mutation forever. */
function awaitOpener(timeoutMs = 5000): Promise<void> {
  if (ceremonyOpener) return Promise.resolve();
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => {
      openerWaiters = openerWaiters.filter((w) => w !== onReady);
      reject(new StepUpRequiredError(["otp"]));
    }, timeoutMs);
    const onReady = () => {
      clearTimeout(timer);
      resolve();
    };
    openerWaiters.push(onReady);
  });
}

/** The modal module injects a self-mount fn used only when no host is present.
 *  Kept as a setter so `step-up.ts` has zero React imports. */
export function setStepUpSelfMount(fn: () => Promise<() => void>): void {
  selfMount = fn;
}

// Coalesce concurrent step-ups: two guarded calls failing at once must share ONE
// prompt, not stack two modals.
let inFlight: Promise<string> | null = null;

/** Test-only: reset all module state between cases (cache, in-flight ceremony,
 *  registered opener, waiters). Never called in production. */
export function __resetStepUpForTests(): void {
  cached = null;
  inFlight = null;
  ceremonyOpener = null;
  openerWaiters = [];
}

/**
 * Run the step-up ceremony (or reuse a still-valid cached token) and resolve
 * with the token. Opens the registered modal host; if none is mounted, mounts a
 * throwaway one. Concurrent callers share a single ceremony. Rejects with
 * `StepUpCancelledError` if the user cancels.
 */
export async function runStepUp(challenge: { acceptedFactors: string[] }): Promise<string> {
  const existing = getCachedStepUpToken();
  if (existing) return existing;
  if (inFlight) return inFlight;

  inFlight = (async () => {
    let unmount: (() => void) | null = null;
    try {
      if (!ceremonyOpener) {
        if (!selfMount) {
          // No page mounts a StepUpHost — lazy-load the modal module, whose
          // module scope injects the self-mounter (setStepUpSelfMount). The
          // dynamic import keeps this seam free of static React deps; without
          // it a guarded 403 would surface as an error instead of the ceremony.
          await import("@/components/auth/StepUpModal");
        }
        if (!selfMount) {
          // Still nothing (import failed) — a clear typed error beats a hang.
          throw new StepUpRequiredError(challenge.acceptedFactors);
        }
        unmount = await selfMount();
        // The host registers its opener in a React effect (async), so wait for
        // it before invoking — don't read `ceremonyOpener` synchronously.
        await awaitOpener();
      }
      if (!ceremonyOpener) throw new StepUpRequiredError(challenge.acceptedFactors);
      const result = await ceremonyOpener(challenge);
      cacheStepUpToken(result.token, result.expiresInSec);
      return result.token;
    } finally {
      inFlight = null;
      // Defer unmount so the modal's close animation/focus-restore settles.
      if (unmount) queueMicrotask(unmount);
    }
  })();

  return inFlight;
}

/** The header a retried guarded request carries once a token is obtained. */
export const STEP_UP_HEADER = "X-Step-Up";

/**
 * Wrap a client mutation that returns a raw `Response`. If the first attempt is
 * `403 {code:"step_up_required"}`, run the step-up ceremony, then retry ONCE
 * with the `X-Step-Up` header — mirroring the 401-force-refresh-retry seam. The
 * caller's own status branching is unchanged: it sees either the original
 * response (if not a step-up 403) or the retried response.
 *
 * `send(headers)` must issue the request with the given extra headers merged in
 * (empty on the first attempt). On user-cancel, the `StepUpCancelledError`
 * propagates so the caller can show a clean "cancelled" state rather than a
 * generic failure.
 */
export async function withStepUp(
  send: (extraHeaders: Record<string, string>) => Promise<Response>,
): Promise<Response> {
  const first = await send({});
  if (first.status !== 403) return first;

  // Peek the body WITHOUT consuming the caller's response: clone before reading.
  const challenge = await parseStepUpChallenge(first);
  if (!challenge) return first; // a plain 403 — hand it back untouched.

  const token = await runStepUp(challenge);
  const retried = await send({ [STEP_UP_HEADER]: token });
  // If the backend still says step-up (token rejected/expired), drop the cache so
  // the next attempt re-prompts fresh — and hand the response back (single retry).
  if (retried.status === 403) {
    const again = await parseStepUpChallenge(retried);
    if (again) clearStepUpToken();
  }
  return retried;
}

/** Best-effort parse of a `403 {code:"step_up_required"}` body from a Response,
 *  reading a CLONE so the original body stays intact for the caller. */
async function parseStepUpChallenge(
  res: Response,
): Promise<{ acceptedFactors: string[] } | null> {
  let body: unknown;
  try {
    body = await res.clone().json();
  } catch {
    return null;
  }
  if (!body || typeof body !== "object") return null;
  const obj = body as { code?: unknown; acceptedFactors?: unknown };
  if (obj.code !== "step_up_required") return null;
  const factors =
    Array.isArray(obj.acceptedFactors)
      ? obj.acceptedFactors.filter((f): f is string => typeof f === "string")
      : [];
  return { acceptedFactors: factors.length > 0 ? factors : ["otp"] };
}
