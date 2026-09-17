import { cookies } from "next/headers";
import { NextResponse } from "next/server";
import { SESSION_COOKIE } from "@/lib/constants";

// BFF proxy for the step-up verify endpoint (PINNED step-up contract). AUTHED:
// the caller is already signed in and is proving a FRESH second factor for a
// sensitive action. House cookie→Bearer pattern: convert the httpOnly cp_session
// cookie into `Authorization: Bearer` exactly like every other authed BFF call,
// then forward to Spring.
//
//   POST /api/auth/step-up/verify
//   body { factor:"otp", channel:"email"|"phone", code:"NNNNNN" }
//        | { factor:"otp", channel:"phone", idToken:"<fresh Firebase phone id token>" }
//   → 200 { stepUpToken, expiresInSec } | 400 bad code | 429 rate-limited
//
// The backend's body/status is passed back verbatim. We forward the real client
// IP (X-Forwarded-For / X-Real-IP) so Spring's per-user/per-IP step-up caps see
// the true origin (LB-aware parsing is server-side, same as the email-code
// request proxy). NO codes/tokens are ever logged here.

const API_BASE = process.env.SS_API_BASE_URL ?? "http://localhost:8080/api";

export async function POST(request: Request) {
  const token = (await cookies()).get(SESSION_COOKIE)?.value;
  if (!token) {
    return NextResponse.json({ detail: "Not signed in." }, { status: 401 });
  }

  let body: unknown = {};
  try {
    body = (await request.json()) ?? {};
  } catch {
    body = {};
  }

  const headers: Record<string, string> = {
    Authorization: `Bearer ${token}`,
    "Content-Type": "application/json",
    Accept: "application/json",
  };
  const xff = request.headers.get("x-forwarded-for");
  if (xff) headers["X-Forwarded-For"] = xff;
  const realIp = request.headers.get("x-real-ip");
  if (realIp) headers["X-Real-IP"] = realIp;

  let upstream: Response;
  try {
    upstream = await fetch(`${API_BASE}/auth/step-up/verify`, {
      method: "POST",
      headers,
      body: JSON.stringify(body),
      cache: "no-store",
    });
  } catch {
    return NextResponse.json(
      { detail: "Couldn't confirm it's you. Please try again." },
      { status: 502 },
    );
  }

  const text = await upstream.text();
  return new NextResponse(text || null, {
    status: upstream.status,
    headers: { "Content-Type": "application/json" },
  });
}
