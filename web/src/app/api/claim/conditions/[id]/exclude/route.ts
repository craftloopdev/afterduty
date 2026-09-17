import { NextResponse } from "next/server";
import { serverFetch } from "@/lib/api/client";
import { ForbiddenError, UnauthorizedError, UpstreamError } from "@/lib/api/errors";

function mapError(e: unknown): NextResponse {
  if (e instanceof UnauthorizedError) return NextResponse.json({ error: "unauthorized" }, { status: 401 });
  // Spring 403 = not the caller's condition (cross-user write) → forbidden.
  if (e instanceof ForbiddenError) return NextResponse.json({ error: "forbidden" }, { status: 403 });
  // Spring's 404 (unknown/superseded condition) and 400 (bad body) pass through.
  const status = e instanceof UpstreamError ? e.status : 502;
  return NextResponse.json({ error: "upstream" }, { status });
}

/**
 * "Don't include in my claim" toggle (owner-set, reversible): body
 * `{ "excluded": true | false }` → 204. Excluding a VALID condition drops it from
 * the server-authoritative combined rating + pay (RatingController filters it) while
 * keeping it in the conditions list so the web can show it in the "Not filing"
 * section. Owner-only upstream — the cookie→Bearer serverFetch carries no X-View-As.
 * The `id` is validated as an integer before touching the upstream path.
 */
export async function POST(
  request: Request,
  { params }: { params: Promise<{ id: string }> },
) {
  const { id } = await params;
  if (!/^\d+$/.test(id)) {
    return NextResponse.json({ error: "not_found" }, { status: 404 });
  }

  let excluded: unknown;
  try {
    excluded = ((await request.json()) as { excluded?: unknown })?.excluded;
  } catch {
    return NextResponse.json({ error: "bad_request" }, { status: 400 });
  }
  if (typeof excluded !== "boolean") {
    return NextResponse.json({ error: "bad_request" }, { status: 400 });
  }

  try {
    await serverFetch<null>(`/claim/conditions/${id}/exclude`, {
      method: "POST",
      body: { excluded },
    });
    return new NextResponse(null, { status: 204 });
  } catch (e) {
    return mapError(e);
  }
}
