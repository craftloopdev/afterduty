import { NextResponse } from "next/server";

// BFF proxy for dual-channel factor-2 RECOVERY — START (auth program P1.5, the
// pinned C contract). PRE-SESSION: no session cookie (the veteran can't sign in —
// that's the point). Anti-enumeration: the backend ALWAYS returns 200 {ok:true}
// whether or not the account exists, so this proxy just forwards and passes the
// body back verbatim. We forward the real client IP so the backend's per-IP
// email-code cap derives the true origin (XFF second-to-last entry behind GCP's
// LB, parsed server-side). We do NOT forward arbitrary client headers.

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
    upstream = await fetch(`${API_BASE}/auth/recovery/start`, {
      method: "POST",
      headers,
      body: JSON.stringify(body),
      cache: "no-store",
    });
  } catch {
    // Even the fallback stays anti-enumeration-shaped: a flat ok so a network
    // blip never reveals whether the account exists.
    return NextResponse.json({ ok: true }, { status: 200 });
  }

  const text = await upstream.text();
  return new NextResponse(text || null, {
    status: upstream.status,
    headers: { "Content-Type": "application/json" },
  });
}
