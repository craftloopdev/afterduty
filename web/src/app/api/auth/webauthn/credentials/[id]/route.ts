import { cookies } from "next/headers";
import { NextResponse } from "next/server";
import { serverFetch } from "@/lib/api/client";
import { SESSION_COOKIE } from "@/lib/constants";
import { ForbiddenError, UnauthorizedError, UpstreamError } from "@/lib/api/errors";

// The step-up token header (PINNED step-up contract). Inlined here rather than
// imported from the `"use client"` step-up module so this server route pulls no
// client-only graph; the literal is the single source of truth on the wire.
const STEP_UP_HEADER = "X-Step-Up";

// BFF proxy for a single WebAuthn credential (auth-program-plan P1.3, PINNED
// MANAGE contract). AUTHED. `id` is opaque (server-issued base64url) — passed
// through url-encoded, never parsed here.
//
//   PATCH  { nickname }  → rename (not sensitive; house serverFetch).
//   DELETE               → revoke, STEP-UP GUARDED. Removing a factor is
//                          sensitive, so Spring may reply `403
//                          {code:"step_up_required"}` until an `X-Step-Up` token
//                          is presented. The client's `withStepUp` seam runs the
//                          ceremony and retries; for that to work this route must
//                          NOT swallow the step-up 403 — it forwards the inbound
//                          X-Step-Up header and returns Spring's body/status
//                          VERBATIM (a raw cookie→Bearer passthrough, like the
//                          step-up/verify and email-code/attach proxies). Using
//                          serverFetch here would throw StepUpRequiredError and
//                          hide the challenge from the client seam.

const API_BASE = process.env.SS_API_BASE_URL ?? "http://localhost:8080/api";

function mapError(e: unknown): NextResponse {
  if (e instanceof UnauthorizedError)
    return NextResponse.json({ error: "unauthorized" }, { status: 401 });
  if (e instanceof ForbiddenError) return NextResponse.json({ error: "forbidden" }, { status: 403 });
  const status = e instanceof UpstreamError ? e.status : 502;
  return NextResponse.json({ error: "upstream" }, { status });
}

export async function PATCH(request: Request, { params }: { params: Promise<{ id: string }> }) {
  const { id } = await params;
  let nickname: unknown;
  try {
    nickname = ((await request.json()) as { nickname?: unknown })?.nickname;
  } catch {
    return NextResponse.json({ error: "bad_request" }, { status: 400 });
  }
  if (typeof nickname !== "string") {
    return NextResponse.json({ error: "bad_request" }, { status: 400 });
  }
  const trimmed = nickname.trim();
  if (!trimmed || trimmed.length > 60) {
    return NextResponse.json({ error: "bad_request" }, { status: 400 });
  }

  try {
    const res = await serverFetch<unknown>(
      `/auth/webauthn/credentials/${encodeURIComponent(id)}`,
      { method: "PATCH", body: { nickname: trimmed } },
    );
    return NextResponse.json(res ?? { ok: true, nickname: trimmed });
  } catch (e) {
    return mapError(e);
  }
}

export async function DELETE(request: Request, { params }: { params: Promise<{ id: string }> }) {
  const { id } = await params;
  const token = (await cookies()).get(SESSION_COOKIE)?.value;
  if (!token) {
    return NextResponse.json({ error: "unauthorized" }, { status: 401 });
  }

  const headers: Record<string, string> = {
    Authorization: `Bearer ${token}`,
    Accept: "application/json",
  };
  // Forward the step-up token from the client's retry (the ONLY inbound header
  // we relay — everything else stays an allowlist). Spring consumes it to satisfy
  // the @RequiresStepUp guard.
  const stepUp = request.headers.get(STEP_UP_HEADER);
  if (stepUp) headers[STEP_UP_HEADER] = stepUp;

  let upstream: Response;
  try {
    upstream = await fetch(
      `${API_BASE}/auth/webauthn/credentials/${encodeURIComponent(id)}`,
      { method: "DELETE", headers, cache: "no-store" },
    );
  } catch {
    return NextResponse.json({ error: "upstream" }, { status: 502 });
  }

  // Pass Spring's status + body back VERBATIM so the client `withStepUp` seam
  // sees a genuine `403 {code:"step_up_required"}` on the first attempt and a
  // 204 on the guarded retry.
  const text = await upstream.text();
  return new NextResponse(text || null, {
    status: upstream.status,
    headers: { "Content-Type": "application/json" },
  });
}
