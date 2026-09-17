import { NextResponse } from "next/server";
import { serverFetch } from "@/lib/api/client";
import { ForbiddenError, UnauthorizedError, UpstreamError } from "@/lib/api/errors";

function mapError(e: unknown): NextResponse {
  if (e instanceof UnauthorizedError) return NextResponse.json({ error: "unauthorized" }, { status: 401 });
  if (e instanceof ForbiddenError) return NextResponse.json({ error: "forbidden" }, { status: 403 });
  const status = e instanceof UpstreamError ? e.status : 502;
  return NextResponse.json({ error: "upstream" }, { status });
}

/**
 * Mark journal notifications read: `{ "ids": [n, ...] }` or `{ "all": true }`.
 * The body is REBUILT from the validated fields (never passed through raw), so
 * only the documented shape ever reaches Spring. → `200 { "updated": n }`.
 */
export async function POST(request: Request) {
  let raw: unknown;
  try {
    raw = await request.json();
  } catch {
    return NextResponse.json({ error: "bad_request" }, { status: 400 });
  }
  const o = (raw ?? {}) as { ids?: unknown; all?: unknown };
  const ids = Array.isArray(o.ids)
    ? o.ids.filter((n): n is number => typeof n === "number" && Number.isFinite(n))
    : null;
  const body = o.all === true ? { all: true } : ids && ids.length ? { ids } : null;
  if (!body) return NextResponse.json({ error: "bad_request" }, { status: 400 });

  try {
    const res = await serverFetch<{ updated?: number }>("/notifications/mark-read", {
      method: "POST",
      body,
    });
    return NextResponse.json(res ?? { updated: 0 });
  } catch (e) {
    return mapError(e);
  }
}
