import { cookies } from "next/headers";
import { NextResponse } from "next/server";
import { SESSION_COOKIE } from "@/lib/constants";

// BFF proxy for the passwordless email-code ATTACH endpoint
// (passwordless-otp-auth-spec §2.3, §7). AUTHED: a phone-first new user attaches
// a verified email to their CURRENT Firebase uid. We convert the httpOnly
// cp_session cookie → Authorization: Bearer exactly like every other authed BFF
// call, then forward. The backend's {detail}/{status} body is passed back
// verbatim (409 = email owned by another account; 400 = generic verify failure).

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

  let upstream: Response;
  try {
    upstream = await fetch(`${API_BASE}/auth/email-code/attach`, {
      method: "POST",
      headers: {
        Authorization: `Bearer ${token}`,
        "Content-Type": "application/json",
        Accept: "application/json",
      },
      body: JSON.stringify(body),
      cache: "no-store",
    });
  } catch {
    return NextResponse.json({ detail: "Could not verify your email. Please try again." }, { status: 502 });
  }

  const text = await upstream.text();
  return new NextResponse(text || null, {
    status: upstream.status,
    headers: { "Content-Type": "application/json" },
  });
}
