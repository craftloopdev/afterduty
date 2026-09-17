import { NextResponse } from "next/server";
import { serverFetch } from "@/lib/api/client";
import { ForbiddenError, UnauthorizedError, UpstreamError } from "@/lib/api/errors";

// BFF proxy for PATCH /auth/me (pinned preferredName contract). House
// cookie→Bearer pattern: `serverFetch` converts the httpOnly cp_session cookie
// into the Authorization header. This is an ACCOUNT-LEVEL mutation — the
// transport strips X-View-As from all writes, so a viewer-mode selection can
// never rename someone else's account (defense in depth; the backend ignores
// the header on this route anyway).

function mapError(e: unknown): NextResponse {
  if (e instanceof UnauthorizedError)
    return NextResponse.json({ error: "unauthorized" }, { status: 401 });
  if (e instanceof ForbiddenError) return NextResponse.json({ error: "forbidden" }, { status: 403 });
  const status = e instanceof UpstreamError ? e.status : 502;
  return NextResponse.json({ error: "upstream" }, { status });
}

/**
 * Set the veteran's preferred display name: body `{ "preferredName": string }`,
 * trimmed, 1..60 chars (400 outside) → `200 { ok: true, preferredName }`.
 * Validation runs here too (not just in Spring) so a bad payload never leaves
 * the box, but the backend remains the source of truth.
 */
export async function PATCH(request: Request) {
  let preferredName: unknown;
  try {
    preferredName = ((await request.json()) as { preferredName?: unknown })?.preferredName;
  } catch {
    return NextResponse.json({ error: "bad_request" }, { status: 400 });
  }
  if (typeof preferredName !== "string") {
    return NextResponse.json({ error: "bad_request" }, { status: 400 });
  }
  const trimmed = preferredName.trim();
  if (!trimmed || trimmed.length > 60) {
    return NextResponse.json({ error: "bad_request" }, { status: 400 });
  }

  try {
    const res = await serverFetch<{ ok?: boolean; preferredName?: string } | null>("/auth/me", {
      method: "PATCH",
      body: { preferredName: trimmed },
    });
    return NextResponse.json(res ?? { ok: true, preferredName: trimmed });
  } catch (e) {
    return mapError(e);
  }
}
