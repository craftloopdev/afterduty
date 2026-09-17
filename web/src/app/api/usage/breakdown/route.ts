import { NextResponse } from "next/server";
import { serverFetch } from "@/lib/api/client";
import { ForbiddenError, UnauthorizedError, UpstreamError } from "@/lib/api/errors";

function mapError(e: unknown): NextResponse {
  if (e instanceof UnauthorizedError) return NextResponse.json({ error: "unauthorized" }, { status: 401 });
  if (e instanceof ForbiddenError) return NextResponse.json({ error: "forbidden" }, { status: 403 });
  const status = e instanceof UpstreamError ? e.status : 502;
  return NextResponse.json({ error: "upstream" }, { status });
}

// "Analyze my usage" — the monthly AI-spend breakdown from the ledger. Cookie
// session → Bearer (house pattern); Spring's body verbatim. Read-only.
export const dynamic = "force-dynamic";

export async function GET() {
  try {
    return NextResponse.json(await serverFetch<Record<string, unknown>>("/usage/breakdown"));
  } catch (e) {
    return mapError(e);
  }
}
