import { NextResponse } from "next/server";
import { serverFetch } from "@/lib/api/client";
import { ForbiddenError, UnauthorizedError, UpstreamError } from "@/lib/api/errors";

function mapError(e: unknown): NextResponse {
  if (e instanceof UnauthorizedError) return NextResponse.json({ error: "unauthorized" }, { status: 401 });
  if (e instanceof ForbiddenError) return NextResponse.json({ error: "forbidden" }, { status: 403 });
  // Spring's 404 (unknown evidence, or not the caller's claim) passes through.
  const status = e instanceof UpstreamError ? e.status : 502;
  return NextResponse.json({ error: "upstream" }, { status });
}

/**
 * Delete one evidence document (P1-29). Spring removes the file + its extracted
 * facts and clears the claim's synthesis/gap timestamps so the analysis re-runs
 * on the next scheduler tick — the client arms the pipeline pulse to show it.
 * Upstream returns 204; we pass that through.
 */
export async function DELETE(
  _request: Request,
  { params }: { params: Promise<{ id: string }> },
) {
  const { id } = await params;
  if (!/^\d+$/.test(id)) {
    return NextResponse.json({ error: "not_found" }, { status: 404 });
  }
  try {
    await serverFetch<null>(`/claim/evidence/${id}`, { method: "DELETE" });
    return new NextResponse(null, { status: 204 });
  } catch (e) {
    return mapError(e);
  }
}
