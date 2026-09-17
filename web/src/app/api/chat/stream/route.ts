import { cookies } from "next/headers";
import { NextResponse } from "next/server";
import { SESSION_COOKIE } from "@/lib/constants";
import { detectUsageLimit, usageLimitBody } from "@/components/chat/usageLimit";

// Cookie-bearing route that reads the request body + streams an SSE response —
// it must run per-request on Node and never be cached or buffered.
export const dynamic = "force-dynamic";
export const runtime = "nodejs";

const API_BASE = process.env.SS_API_BASE_URL ?? "http://localhost:8080/api";

/**
 * SSE passthrough for chat streaming (spec §F.3). Unlike `serverFetch`, this
 * route does NOT buffer: it hand-rolls the upstream fetch so the raw
 * `text/event-stream` body streams straight to the browser. The httpOnly
 * cp_session cookie becomes a Bearer here (cookie→Bearer); outbound headers are
 * an allowlist (client.ts security convention) — we never forward inbound
 * client headers.
 *
 * Pre-stream gating failures (401/402/403/409) come back as plain JSON so the
 * client's pre-send gate stays machine-mappable; a 200 is passed through as a
 * raw ReadableStream with no-transform headers (defeats proxy buffering).
 */
export async function POST(request: Request) {
  const token = (await cookies()).get(SESSION_COOKIE)?.value;
  if (!token) return NextResponse.json({ error: "unauthorized" }, { status: 401 });

  let bodyText: string;
  try {
    bodyText = JSON.stringify((await request.json()) ?? {});
  } catch {
    bodyText = "{}";
  }

  let upstream: Response;
  try {
    upstream = await fetch(`${API_BASE}/claim/chat/stream`, {
      method: "POST",
      headers: {
        Authorization: `Bearer ${token}`,
        Accept: "text/event-stream",
        "Content-Type": "application/json",
      },
      body: bodyText,
      cache: "no-store",
      // @ts-expect-error — `duplex` is required by Node fetch for streaming bodies.
      duplex: "half",
    });
  } catch {
    return NextResponse.json({ error: "upstream" }, { status: 502 });
  }

  // Non-200 → uniform JSON statuses, mirroring app/api/chat/route.ts mapError so
  // the pre-send gate (402 → upgrade, 409 → fallback-to-POST) stays consistent.
  // The monthly usage cap is inspected FIRST (P1-10): today's backend sends a
  // 402 whose BODY carries USAGE_LIMIT_REACHED (and may move to a bare 429) —
  // it must never collapse into subscription_required for a paying user.
  if (!upstream.ok || !upstream.body) {
    let body: unknown = null;
    try {
      body = JSON.parse(await upstream.text());
    } catch {
      body = null;
    }
    const cap = detectUsageLimit(upstream.status, body);
    if (cap) return NextResponse.json(usageLimitBody(cap.resumesAt), { status: 429 });
    return mapUpstreamError(upstream.status);
  }

  return new Response(upstream.body, {
    status: 200,
    headers: {
      "Content-Type": "text/event-stream",
      "Cache-Control": "no-cache, no-transform",
      Connection: "keep-alive",
      "X-Accel-Buffering": "no",
    },
  });
}

function mapUpstreamError(status: number): NextResponse {
  if (status === 401) return NextResponse.json({ error: "unauthorized" }, { status: 401 });
  if (status === 402) return NextResponse.json({ error: "subscription_required" }, { status: 402 });
  if (status === 403) return NextResponse.json({ error: "forbidden" }, { status: 403 });
  if (status === 409) return NextResponse.json({ error: "streaming_disabled" }, { status: 409 });
  if (status === 429) return NextResponse.json({ error: "rate_limited" }, { status: 429 });
  return NextResponse.json({ error: "upstream" }, { status: status >= 500 ? 502 : status });
}
