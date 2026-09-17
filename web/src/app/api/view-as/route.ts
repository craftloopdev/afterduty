import { cookies } from "next/headers";
import { NextResponse } from "next/server";
import { serverFetch } from "@/lib/api/client";
import { UnauthorizedError } from "@/lib/api/errors";
import { VIEW_AS_COOKIE } from "@/lib/api/transport";
import type { UserResponse } from "@/lib/models/api";

// Viewer-mode selection (P0-8, pinned contract). The selection is an httpOnly
// cookie holding the SHARED CLAIM id; `serverFetch` reads it and forwards
// `X-View-As: <claimId>` on GETs. POST validates the claim against the
// caller's own `me.sharedProfiles` before setting the cookie — the backend
// enforces share access on every read regardless, but validating here gives an
// honest 403 up front instead of a wedged all-403 UI.

// Shorter than a workday: a revoked share or an account switch on a shared
// browser must not leave a stale selection lingering for long. The (app)
// layout also actively clears a selection that no longer matches a share.
const MAX_AGE = 60 * 60 * 4;

/** Enter viewer mode: body `{ claimId }` → 200 `{ ok, claimId, ownerName }`. */
export async function POST(request: Request) {
  let claimId: unknown;
  try {
    claimId = ((await request.json()) as { claimId?: unknown })?.claimId;
  } catch {
    return NextResponse.json({ error: "bad_request" }, { status: 400 });
  }
  if (typeof claimId !== "number" || !Number.isInteger(claimId) || claimId <= 0) {
    return NextResponse.json({ error: "bad_request" }, { status: 400 });
  }

  let me: UserResponse;
  try {
    me = await serverFetch<UserResponse>("/auth/me");
  } catch (e) {
    if (e instanceof UnauthorizedError) {
      return NextResponse.json({ error: "unauthorized" }, { status: 401 });
    }
    return NextResponse.json({ error: "upstream" }, { status: 502 });
  }

  const share = (me.sharedProfiles ?? []).find((p) => p.claimId === claimId && !p.isOwn);
  if (!share) {
    return NextResponse.json({ error: "no_share_access" }, { status: 403 });
  }

  const c = await cookies();
  c.set(VIEW_AS_COOKIE, String(claimId), {
    httpOnly: true,
    secure: process.env.NODE_ENV === "production",
    sameSite: "lax",
    path: "/",
    maxAge: MAX_AGE,
  });
  return NextResponse.json({
    ok: true,
    claimId,
    ownerName: share.ownerName ?? share.ownerEmail ?? null,
  });
}

/** Exit viewer mode: clear the selection cookie. Idempotent. */
export async function DELETE() {
  const c = await cookies();
  c.delete(VIEW_AS_COOKIE);
  return new Response(null, { status: 204 });
}
