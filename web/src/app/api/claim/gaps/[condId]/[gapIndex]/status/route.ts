import { NextResponse } from "next/server";
import { serverFetch } from "@/lib/api/client";
import { ForbiddenError, UnauthorizedError, UpstreamError } from "@/lib/api/errors";

function mapError(e: unknown): NextResponse {
  if (e instanceof UnauthorizedError) return NextResponse.json({ error: "unauthorized" }, { status: 401 });
  if (e instanceof ForbiddenError) return NextResponse.json({ error: "forbidden" }, { status: 403 });
  // Spring's 404 (unknown condition/index, or not the caller's) passes through.
  const status = e instanceof UpstreamError ? e.status : 502;
  return NextResponse.json({ error: "upstream" }, { status });
}

const STATUSES = new Set(["open", "resolved", "dismissed"]);

/**
 * Persist a gap's resolution status (durable `user_gap_state`, P1-6):
 * body `{ "status": "resolved" | "dismissed" | "open" }` → `200 { ok, status }`.
 * Params are validated as integers before touching the upstream path.
 */
export async function PATCH(
  request: Request,
  { params }: { params: Promise<{ condId: string; gapIndex: string }> },
) {
  const { condId, gapIndex } = await params;
  if (!/^\d+$/.test(condId) || !/^\d+$/.test(gapIndex)) {
    return NextResponse.json({ error: "not_found" }, { status: 404 });
  }

  let status: unknown;
  try {
    status = ((await request.json()) as { status?: unknown })?.status;
  } catch {
    return NextResponse.json({ error: "bad_request" }, { status: 400 });
  }
  if (typeof status !== "string" || !STATUSES.has(status)) {
    return NextResponse.json({ error: "bad_request" }, { status: 400 });
  }

  try {
    const res = await serverFetch<{ ok?: boolean; status?: string }>(
      `/claim/gaps/${condId}/${gapIndex}/status`,
      { method: "PATCH", body: { status } },
    );
    return NextResponse.json(res ?? { ok: true, status });
  } catch (e) {
    return mapError(e);
  }
}
