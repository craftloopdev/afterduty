// The transport seam (capacitor-ios-spec §A.2). `ApiFetch` is the single
// injected egress contract every loader in `endpoints-core.ts` is parameterized
// on. Two implementations satisfy it: `serverFetch` (BFF, cookie→Bearer,
// server-only — web) and, later, a native `DirectApiClient` (plugin ID
// token→Bearer). This module is client-safe: NO `server-only`, NO `next/headers`,
// NO React `cache` — so it can be shared by both build targets.

import {
  ForbiddenError,
  StepUpRequiredError,
  SubscriptionRequiredError,
  UnauthorizedError,
  UpstreamError,
} from "./errors";

export interface FetchOpts {
  method?: "GET" | "POST" | "PUT" | "PATCH" | "DELETE";
  body?: unknown;
  /** Forward X-View-As to view a shared claim. */
  viewAs?: number;
  /** Treat a 404 as `null` instead of throwing (e.g. profile/conditions for new users). */
  allow404AsNull?: boolean;
}

/**
 * The httpOnly cookie holding the viewer-mode selection (P0-8): the SHARED
 * CLAIM id being viewed. Set/cleared by the BFF `POST/DELETE /api/view-as`
 * route; read by `serverFetch`, which forwards it as `X-View-As` on reads.
 * Lives here (not `lib/constants`) so both the route and the transport share
 * one client-safe definition.
 */
export const VIEW_AS_COOKIE = "cp_view_as";

/**
 * Merge the AMBIENT viewer-mode selection (web: `cp_view_as` cookie; native:
 * the module-level value in `direct.ts`) into a request's opts. Viewer mode is
 * READ-ONLY at the transport level:
 *   - GET (or method omitted): the ambient claim id fills `viewAs` unless the
 *     caller already set one explicitly.
 *   - Any mutation (POST/PUT/PATCH/DELETE): `viewAs` is STRIPPED — even an
 *     explicit one — so `X-View-As` can never ride a write. The backend
 *     enforces share scopes regardless; this is defense in depth (P1-19).
 */
export function withAmbientViewAs(opts: FetchOpts, ambient: number | null): FetchOpts {
  const method = opts.method ?? "GET";
  if (method !== "GET") {
    if (opts.viewAs == null) return opts;
    const rest = { ...opts };
    delete rest.viewAs;
    return rest;
  }
  if (opts.viewAs != null || ambient == null) return opts;
  return { ...opts, viewAs: ambient };
}

/**
 * The shared transport contract. A `<T>(path, opts?) => Promise<T>` that has
 * already applied the canonical status→error mapping below, so loaders never
 * inspect raw responses.
 */
export type ApiFetch = <T>(path: string, opts?: FetchOpts) => Promise<T>;

/**
 * Build the JSON request headers shared by every transport. Outbound headers are
 * an ALLOWLIST — Authorization + Accept (+ optional Content-Type / X-View-As)
 * only; inbound client headers are never forwarded.
 */
export function buildHeaders(token: string, opts: FetchOpts): Record<string, string> {
  const headers: Record<string, string> = {
    Authorization: `Bearer ${token}`,
    Accept: "application/json",
  };
  if (opts.body !== undefined) headers["Content-Type"] = "application/json";
  if (opts.viewAs != null) headers["X-View-As"] = String(opts.viewAs);
  return headers;
}

/**
 * The canonical status→typed-error mapping, lifted verbatim out of `client.ts`
 * so the BFF and the native DirectApiClient map identically (401/402/403/
 * 404-as-null/204/!ok). Returns the parsed JSON body (or `null` for the
 * null/204/empty cases). Throws the typed errors callers branch on.
 */
export async function mapResponse<T>(res: Response, path: string, opts: FetchOpts): Promise<T> {
  if (res.status === 401) throw new UnauthorizedError();
  if (res.status === 402) throw new SubscriptionRequiredError(path);
  if (res.status === 403) {
    // A guarded sensitive endpoint may reply `403 {code:"step_up_required",
    // acceptedFactors:[…]}` (PINNED step-up contract). Distinguish that from a
    // hard forbidden so client mutations can run the step-up ceremony and RSC
    // reads get a typed `StepUpRequiredError` to act on. Any other 403 stays a
    // plain `ForbiddenError`. Body-read is best-effort — a non-JSON/empty 403
    // falls through to ForbiddenError.
    const su = await readStepUpChallenge(res);
    if (su) throw new StepUpRequiredError(su.acceptedFactors, path);
    throw new ForbiddenError(path);
  }
  if (res.status === 404 && opts.allow404AsNull) return null as T;
  if (res.status === 204) return null as T;
  if (!res.ok) throw new UpstreamError(res.status, `Upstream ${res.status} for ${path}`);

  const text = await res.text();
  return (text ? JSON.parse(text) : null) as T;
}

/** The `403 {code:"step_up_required"}` challenge body (PINNED contract). */
export interface StepUpChallenge {
  acceptedFactors: string[];
}

/**
 * Parse a 403 body and return the step-up challenge iff it carries
 * `code:"step_up_required"`, else `null`. Defensive: a non-JSON or shape-off
 * body is simply "not a step-up challenge" (→ caller treats it as a plain 403).
 * Defaults `acceptedFactors` to `["otp"]` — the only factor available today —
 * when the field is missing/empty so the ceremony still has something to run.
 */
export async function readStepUpChallenge(res: Response): Promise<StepUpChallenge | null> {
  let body: unknown;
  try {
    const text = await res.text();
    body = text ? JSON.parse(text) : null;
  } catch {
    return null;
  }
  if (!body || typeof body !== "object") return null;
  const obj = body as { code?: unknown; acceptedFactors?: unknown };
  if (obj.code !== "step_up_required") return null;
  const factors =
    Array.isArray(obj.acceptedFactors) && obj.acceptedFactors.length > 0
      ? obj.acceptedFactors.filter((f): f is string => typeof f === "string")
      : ["otp"];
  return { acceptedFactors: factors.length > 0 ? factors : ["otp"] };
}
