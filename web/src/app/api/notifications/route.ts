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
 * Claim-journal notifications (the deterministic diff-at-flip rows, P1-8).
 * Cookie session → Bearer, same as every BFF route. Only the two documented
 * query params are forwarded (never the raw inbound query string), and a 404
 * (no claim yet) degrades to the empty envelope so clients render "no updates"
 * instead of an error.
 */
export async function GET(request: Request) {
  const inbound = new URL(request.url).searchParams;
  const qs = new URLSearchParams();
  const limit = inbound.get("limit");
  if (limit && /^\d+$/.test(limit)) qs.set("limit", limit);
  if (inbound.get("unreadOnly") === "true") qs.set("unreadOnly", "true");

  try {
    const body = await serverFetch<Record<string, unknown>>(
      `/notifications${qs.size ? `?${qs}` : ""}`,
      { allow404AsNull: true },
    );
    return NextResponse.json(body ?? { notifications: [], unreadCount: 0 });
  } catch (e) {
    return mapError(e);
  }
}
