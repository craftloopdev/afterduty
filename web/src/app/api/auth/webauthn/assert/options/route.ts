import { NextResponse } from "next/server";

// BFF proxy for the WebAuthn AUTHENTICATE options endpoint (auth-program-plan
// P1.3, PINNED ceremony contract). PRE-SESSION: no cookie required — the browser
// is proving identity to MINT a session. Body `{ identifier }`; the backend
// resolves the user WITHOUT leaking existence (a decoy returns valid options
// with empty allowCredentials so timing/shape never reveal enrollment) and
// stores the challenge server-side (TTL 300s). We forward the real client IP so
// the backend can rate-limit the resolve, and pass the upstream body/status back
// verbatim (the PublicKeyCredentialRequestOptions JSON, challenge base64url).

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
    upstream = await fetch(`${API_BASE}/auth/webauthn/assert/options`, {
      method: "POST",
      headers,
      body: JSON.stringify(body),
      cache: "no-store",
    });
  } catch {
    return NextResponse.json({ detail: "Couldn't start passkey sign-in." }, { status: 502 });
  }

  const text = await upstream.text();
  return new NextResponse(text || null, {
    status: upstream.status,
    headers: { "Content-Type": "application/json" },
  });
}
