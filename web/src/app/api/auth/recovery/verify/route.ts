import { NextResponse } from "next/server";

// BFF proxy for dual-channel factor-2 RECOVERY — VERIFY (auth program P1.5, the
// pinned C contract). PRE-SESSION: no session cookie required — the backend
// re-proves possession with a FRESH email code AND a FRESH phone ID token (both
// verified server-side) before it mints a session or revokes any credential. On
// success it returns { custom_token, revokedFactors } (same custom_token shape
// sign-in returns); the browser exchanges the token for a session client-side.
//
// The backend's body/status is passed back verbatim so the page can branch on:
//   400 {detail}  — generic factor failure (bad email code / bad phone proof)
//   409 {code:"recovery_needs_support"} — single-channel legacy → support-hold
// NO codes/tokens are ever logged here. We forward the real client IP for parity.

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
  const xff = request.headers.get("x-forwarded-for");
  if (xff) headers["X-Forwarded-For"] = xff;
  const realIp = request.headers.get("x-real-ip");
  if (realIp) headers["X-Real-IP"] = realIp;

  let upstream: Response;
  try {
    upstream = await fetch(`${API_BASE}/auth/recovery/verify`, {
      method: "POST",
      headers,
      body: JSON.stringify(body),
      cache: "no-store",
    });
  } catch {
    return NextResponse.json(
      { detail: "Couldn't finish recovery. Please try again." },
      { status: 502 },
    );
  }

  const text = await upstream.text();
  return new NextResponse(text || null, {
    status: upstream.status,
    headers: { "Content-Type": "application/json" },
  });
}
