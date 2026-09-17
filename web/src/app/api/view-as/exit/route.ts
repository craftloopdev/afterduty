import { cookies } from "next/headers";
import { NextResponse } from "next/server";
import { VIEW_AS_COOKIE } from "@/lib/api/transport";

/**
 * Server-side viewer-mode exit (P0-8). RSCs can't mutate cookies during
 * render, so when the `(app)` layout finds a STALE selection — the cp_view_as
 * claim id no longer matches any accepted share (revoked share, or an account
 * switch on a shared browser) — it redirects here to clear the cookie and land
 * back on the caller's own claim. Clearing a UI-selection cookie is the only
 * effect, so a GET is safe (no data mutation; the interactive Exit button uses
 * `DELETE /api/view-as`).
 */
export async function GET(request: Request) {
  const c = await cookies();
  c.delete(VIEW_AS_COOKIE);

  // Same-origin relative destination only — never an open redirect.
  const to = new URL(request.url).searchParams.get("to");
  const dest = to && to.startsWith("/") && !to.startsWith("//") ? to : "/";
  return NextResponse.redirect(new URL(dest, request.url), 303);
}
