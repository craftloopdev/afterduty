import { cookies } from "next/headers";
import { NextResponse } from "next/server";
import { serverFetch } from "@/lib/api/client";
import { toMessage } from "@/lib/adapters/message";
import { detectUsageLimit, usageLimitBody } from "@/components/chat/usageLimit";
import { SESSION_COOKIE } from "@/lib/constants";
import {
  ForbiddenError,
  SubscriptionRequiredError,
  UnauthorizedError,
  UpstreamError,
} from "@/lib/api/errors";
import type { MessageResponse } from "@/lib/models/api";

const API_BASE = process.env.SS_API_BASE_URL ?? "http://localhost:8080/api";

function mapError(e: unknown): NextResponse {
  if (e instanceof UnauthorizedError) return NextResponse.json({ error: "unauthorized" }, { status: 401 });
  if (e instanceof SubscriptionRequiredError) return NextResponse.json({ error: "subscription_required" }, { status: 402 });
  if (e instanceof ForbiddenError) return NextResponse.json({ error: "forbidden" }, { status: 403 });
  const status = e instanceof UpstreamError ? e.status : 502;
  return NextResponse.json({ error: "upstream" }, { status });
}

async function history() {
  const list = (await serverFetch<MessageResponse[]>("/claim/messages", { allow404AsNull: true })) ?? [];
  return list.map(toMessage);
}

/**
 * Send a message, then return the refreshed thread.
 * This is the NON-STREAMING fallback path (spec §F.4): the client first tries
 * the SSE route (/api/chat/stream) and falls back here on 409/404/pre-delta
 * failure. The dead GET handler was removed per review Orphan #1 — history is
 * loaded server-side via loadMessages() in the page.
 *
 * The send itself is hand-rolled (cookie→Bearer, allowlist headers — same
 * convention as ../stream/route.ts) rather than `serverFetch`, because the
 * monthly usage cap (P1-10) requires inspecting the upstream error BODY:
 * today's backend 402 carries USAGE_LIMIT_REACHED (+ resumesAt), which
 * serverFetch's typed-error mapping would collapse into subscription_required.
 */
export async function POST(request: Request) {
  let body: Record<string, unknown> = {};
  try {
    body = (await request.json()) ?? {};
  } catch {
    body = {};
  }
  const message = String(body.message ?? "").trim();
  if (!message) return NextResponse.json({ error: "empty message" }, { status: 400 });

  const token = (await cookies()).get(SESSION_COOKIE)?.value;
  if (!token) return NextResponse.json({ error: "unauthorized" }, { status: 401 });

  let upstream: Response;
  try {
    upstream = await fetch(`${API_BASE}/claim/chat`, {
      method: "POST",
      headers: {
        Authorization: `Bearer ${token}`,
        Accept: "application/json",
        "Content-Type": "application/json",
      },
      body: JSON.stringify({ message }),
      cache: "no-store",
    });
  } catch {
    return NextResponse.json({ error: "upstream" }, { status: 502 });
  }

  if (!upstream.ok) {
    let errBody: unknown = null;
    try {
      errBody = JSON.parse(await upstream.text());
    } catch {
      errBody = null;
    }
    const cap = detectUsageLimit(upstream.status, errBody);
    if (cap) return NextResponse.json(usageLimitBody(cap.resumesAt), { status: 429 });
    if (upstream.status === 401) return NextResponse.json({ error: "unauthorized" }, { status: 401 });
    if (upstream.status === 402) return NextResponse.json({ error: "subscription_required" }, { status: 402 });
    if (upstream.status === 403) return NextResponse.json({ error: "forbidden" }, { status: 403 });
    return NextResponse.json({ error: "upstream" }, { status: upstream.status });
  }

  try {
    return NextResponse.json(await history());
  } catch (e) {
    return mapError(e);
  }
}
