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
 * The per-document extracted facts (P1-30 "What we found"). Proxies Spring's
 * GET /claim/evidence/{id}/facts — the LIVE (non-superseded) AtomDto list:
 * [{ type, value, source, confidence, date }]. The body passes through
 * verbatim; rendering rules (e.g. never show `confidence`) live client-side.
 */
export async function GET(
  _request: Request,
  { params }: { params: Promise<{ id: string }> },
) {
  const { id } = await params;
  if (!/^\d+$/.test(id)) {
    return NextResponse.json({ error: "not_found" }, { status: 404 });
  }
  try {
    const facts = await serverFetch<unknown[] | null>(`/claim/evidence/${id}/facts`, {
      allow404AsNull: true,
    });
    if (facts === null) {
      return NextResponse.json({ error: "not_found" }, { status: 404 });
    }
    return NextResponse.json(facts);
  } catch (e) {
    return mapError(e);
  }
}
