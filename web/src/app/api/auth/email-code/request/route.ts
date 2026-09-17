import { cookies } from "next/headers";
import { NextResponse } from "next/server";
import { SESSION_COOKIE } from "@/lib/constants";

// BFF proxy for the passwordless email-code REQUEST endpoint
// (passwordless-otp-auth-spec §2.1, §7). PRE-AUTH for a sign-in code: no
// session cookie required. But the same route mints purpose=attach (the
// phone-first dual-verify second step, §2.3) and purpose=stepup codes, and the
// backend honours those purposes ONLY when the call carries a Bearer — an
// unauthenticated attach/stepup request is fail-safed down to SIGNIN, and
// /attach then rejects the emailed code because it only redeems ATTACH rows.
// So when the httpOnly cp_session cookie is present we convert it to
// Authorization: Bearer exactly like the attach proxy does. The backend's
// auth on this path is best-effort (SecurityConfig): a stale token is ignored,
// never a 401, so a sign-in request with an expired cookie still works.
//
// We forward the inbound X-Forwarded-For (and X-Real-IP) so the backend's
// per-IP hourly cap derives the real client IP (XFF second-to-last entry behind
// GCP's LB). We do NOT forward arbitrary client headers (BFF allowlist), and we
// pass the backend's {detail}/{ok} body straight back so the page can surface
// the already-user-facing 400/429 copy.

const API_BASE = process.env.SS_API_BASE_URL ?? "http://localhost:8080/api";

export async function POST(request: Request) {
  let body: unknown = {};
  try {
    body = (await request.json()) ?? {};
  } catch {
    body = {};
  }

  const headers: Record<string, string> = {
    "Content-Type": "application/json",
    Accept: "application/json",
  };
  const token = (await cookies()).get(SESSION_COOKIE)?.value;
  if (token) headers.Authorization = `Bearer ${token}`;
  // Forward the real client IP for the backend's per-IP cap (LB-aware parsing is
  // server-side in Spring). The leftmost XFF entry is attacker-controlled — the
  // backend never trusts it.
  const xff = request.headers.get("x-forwarded-for");
  if (xff) headers["X-Forwarded-For"] = xff;
  const realIp = request.headers.get("x-real-ip");
  if (realIp) headers["X-Real-IP"] = realIp;

  let upstream: Response;
  try {
    upstream = await fetch(`${API_BASE}/auth/email-code/request`, {
      method: "POST",
      headers,
      body: JSON.stringify(body),
      cache: "no-store",
    });
  } catch {
    return NextResponse.json({ detail: "Email could not be sent. Please try again." }, { status: 502 });
  }

  const text = await upstream.text();
  return new NextResponse(text || null, {
    status: upstream.status,
    headers: { "Content-Type": "application/json" },
  });
}
