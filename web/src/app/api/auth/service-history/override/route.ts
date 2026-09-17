import { NextResponse } from "next/server";
import { serverFetch } from "@/lib/api/client";
import { ForbiddenError, UnauthorizedError, UpstreamError } from "@/lib/api/errors";

// BFF proxy for the veteran service-history override UPSERT (Service History P3
// Part A). AUTHED, account-level, owner-only: `serverFetch` forwards ONLY the
// cookie→Bearer (its `withAmbientViewAs` strips any viewer header from mutations),
// so a VSO viewing a shared claim can never correct the veteran's history — the
// backend resolves the owner from the Bearer principal.
//
// Body `{ clusterKey, branch?, component?, startDate?, endDate?, mos?, rank? }`.
// `clusterKey` selects WHICH of the owner's reconciled conclusions to correct; only
// the SET fields override. Backend returns `{ ok, servicePeriods }` (400 on bad
// input) and the client refetches.

function mapError(e: unknown): NextResponse {
  if (e instanceof UnauthorizedError)
    return NextResponse.json({ error: "unauthorized" }, { status: 401 });
  if (e instanceof ForbiddenError) return NextResponse.json({ error: "forbidden" }, { status: 403 });
  const status = e instanceof UpstreamError ? e.status : 502;
  return NextResponse.json({ error: "upstream" }, { status });
}

export async function POST(request: Request) {
  let body: unknown = {};
  try {
    body = (await request.json()) ?? {};
  } catch {
    return NextResponse.json({ error: "bad_request" }, { status: 400 });
  }

  try {
    const res = await serverFetch<unknown>("/auth/service-history/override", {
      method: "POST",
      body,
    });
    return NextResponse.json(res ?? { ok: true });
  } catch (e) {
    return mapError(e);
  }
}
