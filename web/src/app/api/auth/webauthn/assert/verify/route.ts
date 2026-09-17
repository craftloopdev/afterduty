import { NextResponse } from "next/server";

// BFF proxy for the WebAuthn AUTHENTICATE verify endpoint (auth-program-plan
// P1.3, PINNED ceremony contract). PRE-SESSION: no cookie required. Body
// `{ credential:<assertion> }`; the backend verifies the assertion (signature,
// single-use challenge consume + TTL, exact origin, rpId hash, sign_count
// monotonicity, UV policy, credential ownership) via its WebAuthn library, then
// on success mints the app session and returns `{ custom_token, credentialId }`
// — the SAME custom_token shape email-code verify returns, so the web driver
// reuses signInWithCustomTokenAndEstablish. We forward the client IP and pass
// the upstream body/status back verbatim (a failed assertion → 4xx {detail}).

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
    upstream = await fetch(`${API_BASE}/auth/webauthn/assert/verify`, {
      method: "POST",
      headers,
      body: JSON.stringify(body),
      cache: "no-store",
    });
  } catch {
    return NextResponse.json({ detail: "Couldn't complete passkey sign-in." }, { status: 502 });
  }

  const text = await upstream.text();
  return new NextResponse(text || null, {
    status: upstream.status,
    headers: { "Content-Type": "application/json" },
  });
}
