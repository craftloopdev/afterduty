import { cookies } from "next/headers";
import { NextResponse } from "next/server";
import { SESSION_COOKIE } from "@/lib/constants";

// Firebase ID tokens live ~60 min; keep the cookie a touch shorter so a stale
// token is dropped before it can 401. The client rotates it via POST on refresh.
const MAX_AGE = 60 * 55;

/** Establish/rotate the session: stores the Firebase ID token in an httpOnly cookie. */
export async function POST(request: Request) {
  let idToken: unknown;
  try {
    idToken = (await request.json())?.idToken;
  } catch {
    idToken = undefined;
  }
  if (typeof idToken !== "string" || idToken.length < 20) {
    return NextResponse.json({ error: "missing idToken" }, { status: 400 });
  }

  const c = await cookies();
  c.set(SESSION_COOKIE, idToken, {
    httpOnly: true,
    secure: process.env.NODE_ENV === "production",
    sameSite: "lax",
    path: "/",
    maxAge: MAX_AGE,
  });
  return NextResponse.json({ ok: true }, { status: 201 });
}

/** Sign out: audit-log upstream, then clear the session cookie. */
export async function DELETE() {
  const c = await cookies();
  // Auth-audit the sign-out (EVENT_SIGN_OUT) while the token is still on hand —
  // best-effort: the local sign-out must never fail because the audit call did.
  // (Wired 2026-08-02: the endpoint had no caller since the Flutter frontend.)
  const token = c.get(SESSION_COOKIE)?.value;
  if (token) {
    try {
      const apiBase = process.env.SS_API_BASE_URL ?? "http://localhost:8080/api";
      await fetch(`${apiBase}/auth/sign-out`, {
        method: "POST",
        headers: { Authorization: `Bearer ${token}` },
        signal: AbortSignal.timeout(3000),
      });
    } catch {
      /* audit-only; ignore */
    }
  }
  c.delete(SESSION_COOKIE);
  return new Response(null, { status: 204 });
}
