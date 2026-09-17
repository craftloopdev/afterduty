// The native transport (capacitor-ios-spec §A.2 / §B.3). `DirectApiClient` is the
// mirror of the web `serverFetch`: instead of reading the httpOnly cookie and
// hopping through the BFF, it talks straight to Spring with a Bearer ID token
// sourced from the native Firebase SDK (plugin keychain). It is CLIENT-SAFE —
// NO `server-only`, NO `next/headers`, NO React `cache` — so it survives the
// static export build (§A.2 critical detail).
//
// Header allowlist + status→error mapping are shared verbatim with the BFF via
// `transport.ts`, so 401/402/403/404-null/204/!ok and the tri-state subscription
// semantics map BYTE-IDENTICALLY on both targets (§A.2). The only differences
// are the token source and the absence of the cookie/BFF hop.

import { UnauthorizedError } from "./errors";
import { runStepUp, STEP_UP_HEADER } from "@/lib/auth/step-up";
import {
  buildHeaders,
  mapResponse,
  readStepUpChallenge,
  withAmbientViewAs,
  type ApiFetch,
  type FetchOpts,
} from "./transport";

/** Produces a fresh Firebase ID token. `forceRefresh` bypasses the cached token
 *  (used for the single 401 retry — §B.3). */
export type TokenProvider = (opts?: { forceRefresh?: boolean }) => Promise<string>;

/** Invoked when a request is 401 even after a force-refreshed token: the session
 *  is genuinely dead, so the driver signs out and bounces to /login (§B.3). */
export type SignOutHandler = () => void | Promise<void>;

// Native `NEXT_PUBLIC_API_BASE` (e.g. https://api.afterduty.app/api) is inlined
// at build time. There is no cookie/BFF hop natively, so this is the real origin.
const API_BASE = process.env.NEXT_PUBLIC_API_BASE ?? "";

// Every native request is time-bounded so a stalled fetch or a wedged
// Firebase getIdToken can NEVER hang a screen forever (App Store 2.1(a): the
// post-login load "loaded indefinitely"). A timeout surfaces as a normal error
// → the screen shows its error/retry state instead of an infinite blank/skeleton.
const FETCH_TIMEOUT_MS = 25_000; // generous: covers slow reads, catches true hangs
const TOKEN_TIMEOUT_MS = 12_000; // a stalled getIdToken must not gate the UI

function withTimeout<T>(p: Promise<T>, ms: number, label: string): Promise<T> {
  return new Promise<T>((resolve, reject) => {
    const timer = setTimeout(
      () => reject(new Error(`${label} timed out after ${ms}ms`)),
      ms,
    );
    p.then(
      (v) => {
        clearTimeout(timer);
        resolve(v);
      },
      (e) => {
        clearTimeout(timer);
        reject(e);
      },
    );
  });
}

let tokenProvider: TokenProvider | null = null;
let onAuthLost: SignOutHandler | null = null;

// Viewer-mode selection (P0-8) — the native mirror of the web's httpOnly
// cp_view_as cookie. The claim switcher sets it; every subsequent GET carries
// X-View-As. Module-level (not persisted): a relaunch always starts on the
// user's own claim, and sign-out must clear it (cross-account hygiene).
let viewAsClaimId: number | null = null;

/** Select a shared claim to view (null = back to the user's own claim). */
export function setViewAs(claimId: number | null): void {
  viewAsClaimId = claimId;
}

/** The currently selected shared claim id, or null when on the own claim. */
export function getViewAs(): number | null {
  return viewAsClaimId;
}

/**
 * Wire the token source (and optional sign-out-on-auth-loss handler) for the
 * native transport. The AuthDriver calls this once at boot so `directFetch`
 * never imports the Capacitor plugin directly (keeps this module testable and
 * the plugin off the web bundle).
 */
export function configureDirectApi(provider: TokenProvider, signOut?: SignOutHandler): void {
  tokenProvider = provider;
  onAuthLost = signOut ?? null;
}

async function requireToken(opts?: { forceRefresh?: boolean }): Promise<string> {
  if (!tokenProvider) throw new UnauthorizedError("Native token provider not configured");
  // Bound the token fetch: a wedged native getIdToken() would otherwise hang the
  // request before it even starts (no fetch to abort). Timing out here lets the
  // caller's loader show an error+retry rather than an infinite load.
  const token = await withTimeout(tokenProvider(opts), TOKEN_TIMEOUT_MS, "auth token");
  if (!token) throw new UnauthorizedError();
  return token;
}

/** The native API origin (e.g. https://api.afterduty.app/api). Exposed so the
 *  mutations facade can build raw-`Response` calls (multipart/SSE) against it. */
export function apiBase(): string {
  return API_BASE;
}

/** Resolve the current Bearer token for raw-`Response` mutations (multipart
 *  upload, SSE chat) that bypass the JSON `mapResponse` path but still need the
 *  native ID token. Throws `UnauthorizedError` if no session. */
export function bearer(opts?: { forceRefresh?: boolean }): Promise<string> {
  return requireToken(opts);
}

async function rawFetch(
  path: string,
  token: string,
  opts: FetchOpts,
  extra: Record<string, string> = {},
): Promise<Response> {
  // Abort a stalled request so it can't hang the UI indefinitely (§2.1a). The
  // AbortError propagates as a normal rejection → the screen's error/retry path.
  const ctrl = new AbortController();
  const timer = setTimeout(() => ctrl.abort(), FETCH_TIMEOUT_MS);
  try {
    return await fetch(`${API_BASE}${path}`, {
      method: opts.method ?? "GET",
      headers: { ...buildHeaders(token, opts), ...extra },
      body: opts.body !== undefined ? JSON.stringify(opts.body) : undefined,
      signal: ctrl.signal,
    });
  } finally {
    clearTimeout(timer);
  }
}

/**
 * The native `ApiFetch`. Mirrors `serverFetch` exactly, with two extra retry
 * rules — both client-side because native runs in the WebView:
 *   - §B.3: a 401 is retried ONCE with a force-refreshed token (the cached token
 *     may have just expired); a second 401 → `onAuthLost()` then `UnauthorizedError`.
 *   - P1.2: a `403 {code:"step_up_required"}` runs the step-up OTP ceremony and
 *     retries ONCE with the `X-Step-Up` header; a still-403 surfaces the mapped
 *     error (StepUpRequiredError/ForbiddenError) so the caller isn't stuck.
 */
export const directFetch: ApiFetch = async <T>(path: string, opts: FetchOpts = {}): Promise<T> => {
  // Same read-only viewer rule as the web BFF: ambient selection rides GETs
  // only; any viewAs is stripped from mutations (P0-8).
  opts = withAmbientViewAs(opts, viewAsClaimId);
  let res = await rawFetch(path, await requireToken(), opts);

  if (res.status === 401) {
    // Token may have just rotated — force-refresh once before giving up (§B.3).
    res = await rawFetch(path, await requireToken({ forceRefresh: true }), opts);
    if (res.status === 401) {
      await onAuthLost?.();
      throw new UnauthorizedError();
    }
  }

  if (res.status === 403) {
    // Is this a step-up challenge (vs a hard forbidden)? Peek a CLONE so the
    // original body stays intact for `mapResponse` if it's NOT a step-up.
    const challenge = await readStepUpChallenge(res.clone());
    if (challenge) {
      // Run the ceremony (or reuse a cached token) and retry ONCE with the header.
      const token = await runStepUp(challenge);
      res = await rawFetch(path, await requireToken(), opts, { [STEP_UP_HEADER]: token });
    }
  }

  return mapResponse<T>(res, path, opts);
};
