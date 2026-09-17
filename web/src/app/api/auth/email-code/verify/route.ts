import { NextResponse } from "next/server";

// BFF proxy for the passwordless email-code VERIFY endpoint
// (passwordless-otp-auth-spec §2.2, §7). PRE-AUTH: no session cookie required —
// the backend mints a Firebase custom token for the exact verified email and
// returns { custom_token, isNewUser, hasPhone }. We forward the client IP (for
// symmetry / any verify-side limiting) and pass the backend body straight back
// so the page can surface the generic 400 "Incorrect or expired code".

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
    upstream = await fetch(`${API_BASE}/auth/email-code/verify`, {
      method: "POST",
      headers,
      body: JSON.stringify(body),
      cache: "no-store",
    });
  } catch {
    return NextResponse.json({ detail: "Could not complete sign-in. Please try again." }, { status: 502 });
  }

  const text = await upstream.text();
  return new NextResponse(text || null, {
    status: upstream.status,
    headers: { "Content-Type": "application/json" },
  });
}
