import { NextResponse } from "next/server";
import { serverFetch } from "@/lib/api/client";
import { ForbiddenError, UnauthorizedError, UpstreamError } from "@/lib/api/errors";

function mapError(e: unknown): NextResponse {
  if (e instanceof UnauthorizedError) return NextResponse.json({ error: "unauthorized" }, { status: 401 });
  if (e instanceof ForbiddenError) return NextResponse.json({ error: "forbidden" }, { status: 403 });
  const status = e instanceof UpstreamError ? e.status : 502;
  return NextResponse.json({ error: "upstream" }, { status });
}

// Pipeline jobs snapshot (extraction/synthesis/gap-analysis states) for the
// client-side PipelinePulse poller. Cookie session → Bearer, same as every
// other BFF route; the response is Spring's body verbatim.
export async function GET() {
  try {
    const jobs = await serverFetch<Record<string, unknown>>("/claim/jobs", {
      allow404AsNull: true,
    });
    return NextResponse.json(jobs ?? {});
  } catch (e) {
    return mapError(e);
  }
}
