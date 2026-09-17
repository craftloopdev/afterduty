// The mutations facade (capacitor-ios-spec §A.5). Every client-side write that
// today calls `fetch("/api/…")` inline routes through this seam instead, so the
// components stay transport-agnostic. The WEB implementation hits the BFF route
// handlers EXACTLY as the inline fetches did — same method, body, headers, and
// (for the `Response`-returning calls) the same untouched response object, so
// each caller keeps its existing status-branching verbatim. The NATIVE branch
// (selected by the build-time `NATIVE` constant, so the dead branch is
// tree-shaken on web) calls Spring DIRECTLY with a Bearer ID token. Because the
// BFF's `mapUpstreamError`/`mapError` mirror Spring's statuses 1:1 (§A.5), the
// raw statuses the callers branch on (402/409/404/413/…) are identical on both
// targets, so the components don't change beyond using this facade.
//
// Enumeration of the inline `/api/*` writes this replaces (the complete set,
// grep-verified): chat stream (SSE), legacy chat fallback, evidence upload,
// quick-add statement, share create / revoke / preview / accept, account
// delete, and the Stripe subscription action. `/api/session` is intentionally
// NOT here — it is the AuthDriver's concern and has no native equivalent (§A.5).

import { NATIVE } from "@/lib/platform";
import { apiBase, bearer } from "./direct";
import { withStepUp } from "@/lib/auth/step-up";

export type { SubscriptionAction, SubscriptionActionResult } from "@/lib/web-only/stripe-actions";

/** Authed raw-`Response` call to Spring for the native branch. Mirrors the BFF's
 *  outbound allowlist (Authorization + caller-supplied Accept/Content-Type) and
 *  returns the untouched `Response` so callers branch on Spring's raw status —
 *  identical to the BFF passthrough. `getReader()`/`json()` stay the caller's.
 *  `extra` merges the step-up header on a guarded retry (below). */
async function direct(
  path: string,
  init: { method?: string; headers?: Record<string, string>; body?: BodyInit } = {},
  extra: Record<string, string> = {},
): Promise<Response> {
  const token = await bearer();
  return fetch(`${apiBase()}${path}`, {
    method: init.method ?? "GET",
    headers: { Authorization: `Bearer ${token}`, ...(init.headers ?? {}), ...extra },
    body: init.body,
  });
}

/**
 * Run a guarded write through the step-up seam (auth-program-plan P1.2). `send`
 * issues the request with the given extra headers merged in; on a
 * `403 {code:"step_up_required"}` first response, `withStepUp` runs the OTP
 * ceremony and retries ONCE with the `X-Step-Up` header. Non-step-up responses
 * (incl. plain 403) pass straight through — so a caller's existing status
 * branching is unchanged. Applied only to sensitive mutations; the backend's
 * `@RequiresStepUp` guard is the source of truth for which those are.
 */
function guarded(send: (extra: Record<string, string>) => Promise<Response>): Promise<Response> {
  return withStepUp(send);
}

/** A streamable chat send (SSE). Returns the raw `Response`; the caller owns the
 *  `res.body.getReader()` + SSE parse and the 402/409/404 branching. */
export function streamChat(message: string): Promise<Response> {
  if (NATIVE) {
    return direct("/claim/chat/stream", {
      method: "POST",
      headers: { "Content-Type": "application/json", Accept: "text/event-stream" },
      body: JSON.stringify({ message }),
    });
  }
  return fetch("/api/chat/stream", {
    method: "POST",
    headers: { "Content-Type": "application/json", Accept: "text/event-stream" },
    body: JSON.stringify({ message }),
  });
}

/** Legacy non-streaming chat send → refreshed thread. Returns the raw `Response`;
 *  the caller maps the body (array of messages) and branches 402/!ok. On native
 *  the BFF's compose is replicated inline: POST the message, then GET the thread
 *  and synthesize a `Response` whose JSON body is that refreshed list (§A.5). */
export function sendChat(message: string): Promise<Response> {
  if (NATIVE) return sendChatNative(message);
  return fetch("/api/chat", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ message }),
  });
}

async function sendChatNative(message: string): Promise<Response> {
  const posted = await direct("/claim/chat", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ message }),
  });
  // Non-OK pass through verbatim so the caller's 402/!ok branches fire as on web.
  if (!posted.ok) return posted;
  // Refresh the thread; the caller maps the raw MessageResponse[] via toMessage.
  const history = await direct("/claim/messages", { headers: { Accept: "application/json" } });
  const text = history.ok ? await history.text() : "[]";
  return new Response(text || "[]", {
    status: 200,
    headers: { "content-type": "application/json" },
  });
}

/** Multipart evidence upload. Returns the raw `Response`; the caller reads
 *  413/409/401/server-message exactly as before. No manual Content-Type — the
 *  fetch sets the multipart boundary itself. */
export function uploadEvidence(file: File): Promise<Response> {
  const fd = new FormData();
  fd.append("file", file);
  if (NATIVE) return direct("/claim/evidence", { method: "POST", body: fd });
  return fetch("/api/upload", { method: "POST", body: fd });
}

/** Free quick-add text statement (P2-1): a short "what I remember" statement
 *  that enters the evidence pipeline exactly like an upload (source_type
 *  "quick_add"; backend ungated it in truth week). Returns the raw `Response`
 *  (201 + EvidenceResponse body); the caller branches 401/400/!ok. */
export function quickAddStatement(text: string): Promise<Response> {
  if (NATIVE) {
    return direct("/claim/quick-add", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ text }),
    });
  }
  return fetch("/api/claim/quick-add", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ text }),
  });
}

/** Create a share invite. Returns the raw `Response` (201 + ShareDto body).
 *  Sensitive (grants another person access to the claim) → step-up-guarded: a
 *  `403 step_up_required` transparently runs the OTP ceremony and retries once. */
export function createShare(input: {
  viewerEmail: string;
  canViewAnalysis: boolean;
  canUploadDocs: boolean;
}): Promise<Response> {
  const body = JSON.stringify(input);
  if (NATIVE) {
    return guarded((extra) =>
      direct("/shares", { method: "POST", headers: { "Content-Type": "application/json" }, body }, extra),
    );
  }
  return guarded((extra) =>
    fetch("/api/share", {
      method: "POST",
      headers: { "Content-Type": "application/json", ...extra },
      body,
    }),
  );
}

/** Revoke a share by id. Returns the raw `Response` (204). Step-up-guarded. */
export function revokeShare(id: number): Promise<Response> {
  if (NATIVE) return guarded((extra) => direct(`/shares/${id}`, { method: "DELETE" }, extra));
  return guarded((extra) => fetch(`/api/share?id=${id}`, { method: "DELETE", headers: extra }));
}

/** Preview a share invite (pre-login public on Spring). Returns the raw
 *  `Response`; the caller branches 401/404/410/!ok. */
export function previewShare(token: string): Promise<Response> {
  const enc = encodeURIComponent(token);
  if (NATIVE) return direct(`/shares/accept/${enc}`, { headers: { Accept: "application/json" } });
  return fetch(`/api/share/accept/${enc}`);
}

/** Accept a share invite as the signed-in viewer. Returns the raw `Response`;
 *  the caller branches 401/403/409/410/404/!ok. */
export function acceptShare(token: string): Promise<Response> {
  const enc = encodeURIComponent(token);
  if (NATIVE) return direct(`/shares/accept/${enc}`, { method: "POST" });
  return fetch(`/api/share/accept/${enc}`, { method: "POST" });
}

/** Permanently delete the signed-in account. Returns the raw `Response` (204 on
 *  success); the caller runs the sign-out sequence on `res.ok`. Destructive →
 *  step-up-guarded (confirm it's you before the account is erased). */
export function deleteAccount(): Promise<Response> {
  if (NATIVE) return guarded((extra) => direct("/auth/account", { method: "DELETE" }, extra));
  return guarded((extra) => fetch("/api/account", { method: "DELETE", headers: extra }));
}

/** Set the veteran's preferred display name ("What should we call you?") —
 *  PATCH /auth/me { preferredName } (trimmed 1..60 chars; 400 outside; 200 →
 *  { ok, preferredName }). Account-level: X-View-As never applies (the BFF/
 *  direct write path carries no viewer header by construction). Returns the
 *  raw `Response`; callers branch on ok/400/401. */
export function updatePreferredName(preferredName: string): Promise<Response> {
  const body = JSON.stringify({ preferredName });
  if (NATIVE) {
    return guarded((extra) =>
      direct("/auth/me", { method: "PATCH", headers: { "Content-Type": "application/json" }, body }, extra),
    );
  }
  return guarded((extra) =>
    fetch("/api/auth/me", {
      method: "PATCH",
      headers: { "Content-Type": "application/json", ...extra },
      body,
    }),
  );
}

/** Fields the veteran can correct on one reconciled service-history conclusion
 *  (Service History P3 Part A). All optional — only the SET fields override; a
 *  clusterKey selects WHICH of the owner's conclusions to correct. */
export interface ServiceHistoryOverrideInput {
  clusterKey: string;
  branch?: string | null;
  component?: "active" | "guard" | "reserve" | null;
  startDate?: string | null;
  endDate?: string | null;
  mos?: string | null;
  rank?: string | null;
}

/** Upsert a veteran override for one service-history conclusion — POST
 *  /auth/service-history/override { clusterKey, ...fields }. Account-level +
 *  owner-only (the write path carries no viewer header; the backend resolves the
 *  owner from the Bearer principal). Returns the raw `Response` (`200 { ok,
 *  servicePeriods }`, 400 on bad input); the caller refetches on ok. */
export function setServiceHistoryOverride(input: ServiceHistoryOverrideInput): Promise<Response> {
  const body = JSON.stringify(input);
  if (NATIVE) {
    return direct("/auth/service-history/override", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body,
    });
  }
  return fetch("/api/auth/service-history/override", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body,
  });
}

/** Clear a veteran override for one conclusion — DELETE
 *  /auth/service-history/override/{clusterKey}. Owner-scoped + idempotent.
 *  Returns the raw `Response` (`200 { ok, servicePeriods }`). */
export function clearServiceHistoryOverride(clusterKey: string): Promise<Response> {
  const path = `/auth/service-history/override/${encodeURIComponent(clusterKey)}`;
  if (NATIVE) return direct(path, { method: "DELETE" });
  return fetch(`/api${path}`, { method: "DELETE" });
}

/** Mark journal notifications read — `{ ids: [...] }` or `{ all: true }`.
 *  Returns the raw `Response` (`200 { updated }`); the caller branches on ok. */
export function markNotificationsRead(input: { ids: number[] } | { all: true }): Promise<Response> {
  if (NATIVE) {
    return direct("/notifications/mark-read", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(input),
    });
  }
  return fetch("/api/notifications/mark-read", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(input),
  });
}

/** A step's gap-resolution status. "resolved" = Mark done, "dismissed" =
 *  Doesn't apply, "open" = Undo. */
export type GapStatus = "open" | "resolved" | "dismissed";

/** Persist a gap's status (durable `user_gap_state`, P1-6). Returns the raw
 *  `Response` (`200 { ok, status }`, 404 unknown gap); callers do the
 *  optimistic update + rollback on !ok. */
export function setGapStatus(
  condId: number,
  gapIndex: number,
  status: GapStatus,
): Promise<Response> {
  const path = `/claim/gaps/${condId}/${gapIndex}/status`;
  if (NATIVE) {
    return direct(path, {
      method: "PATCH",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ status }),
    });
  }
  return fetch(`/api${path}`, {
    method: "PATCH",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ status }),
  });
}

/** "Don't include in my claim" toggle (owner-set, reversible): POST
 *  /claim/conditions/{id}/exclude { excluded } → 204. Excluding a valid condition
 *  drops it from the server-authoritative combined rating + pay while keeping it in
 *  the list (the "Not filing" section). Returns the raw `Response` (204 / 403 / 404);
 *  callers refetch conditions AND the rating after `res.ok`. Owner-only — no
 *  X-View-As on the write path. */
export function setConditionExcluded(conditionId: number, excluded: boolean): Promise<Response> {
  const body = JSON.stringify({ excluded });
  if (NATIVE) {
    return direct(`/claim/conditions/${conditionId}/exclude`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body,
    });
  }
  return fetch(`/api/claim/conditions/${conditionId}/exclude`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body,
  });
}

// Stripe checkout / billing-portal action. This is web-only by construction
// (native branches to RevenueCat before reaching it — §C.3); re-export the
// WEB-ONLY accessor (not `subscription-actions` directly) so the real Stripe
// module — and its "/api/subscription" / "billing portal" strings — is fully
// tree-shaken from the native export (§H.4 anti-steering bundle audit). Never
// call this natively.
export { requestSubscriptionUrl } from "@/lib/web-only/stripe-actions";

/** Pull the RevenueCat subscriber into the backend immediately after a successful
 *  purchase or restore (§C.2/§C.3) so `/subscription/status` flips without waiting
 *  for the webhook. Native-only — there is no BFF route for this; web never calls
 *  it (Stripe has its own webhook). Returns true on a 2xx, false otherwise so the
 *  caller can still refetch and let `getCustomerInfo` cover a sync lag (§C.4). */
export async function syncRevenueCat(): Promise<boolean> {
  if (!NATIVE) return false;
  try {
    const res = await direct("/subscription/revenuecat/sync", { method: "POST" });
    return res.ok;
  } catch {
    return false;
  }
}
