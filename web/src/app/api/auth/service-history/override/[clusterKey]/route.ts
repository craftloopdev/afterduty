import { NextResponse } from "next/server";
import { serverFetch } from "@/lib/api/client";
import { ForbiddenError, UnauthorizedError, UpstreamError } from "@/lib/api/errors";

// BFF proxy for CLEARING a veteran service-history override (Service History P3
// Part A). AUTHED, account-level, owner-only + idempotent: `serverFetch` forwards
// only the cookie→Bearer (viewer header stripped from mutations), and the backend
// scopes the delete to the resolved owner + this clusterKey — one account can
// never clear another's override. Backend returns `{ ok, servicePeriods }`; the
// client refetches. `clusterKey` is opaque data, passed through url-encoded.

function mapError(e: unknown): NextResponse {
  if (e instanceof UnauthorizedError)
    return NextResponse.json({ error: "unauthorized" }, { status: 401 });
  if (e instanceof ForbiddenError) return NextResponse.json({ error: "forbidden" }, { status: 403 });
  const status = e instanceof UpstreamError ? e.status : 502;
  return NextResponse.json({ error: "upstream" }, { status });
}

export async function DELETE(
  _request: Request,
  { params }: { params: Promise<{ clusterKey: string }> },
) {
  const { clusterKey } = await params;
  try {
    const res = await serverFetch<unknown>(
      `/auth/service-history/override/${encodeURIComponent(clusterKey)}`,
      { method: "DELETE" },
    );
    return NextResponse.json(res ?? { ok: true });
  } catch (e) {
    return mapError(e);
  }
}
