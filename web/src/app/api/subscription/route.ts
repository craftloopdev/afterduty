import { NextResponse } from "next/server";
import { serverFetch } from "@/lib/api/client";
import { ForbiddenError, UnauthorizedError, UpstreamError } from "@/lib/api/errors";

function mapError(e: unknown): NextResponse {
  if (e instanceof UnauthorizedError) return NextResponse.json({ error: "unauthorized" }, { status: 401 });
  if (e instanceof ForbiddenError) return NextResponse.json({ error: "forbidden" }, { status: 403 });
  const status = e instanceof UpstreamError ? e.status : 502;
  return NextResponse.json({ error: "upstream" }, { status });
}

// Returns a Stripe URL ({ url }) for checkout or the billing portal; the client
// redirects to it. The backend owns Stripe + webhooks.
export async function POST(request: Request) {
  let body: Record<string, unknown> = {};
  try {
    body = (await request.json()) ?? {};
  } catch {
    body = {};
  }
  const action = body.action;
  try {
    if (action === "checkout") {
      const tier = body.tier === "annual" ? "annual" : "monthly";
      const res = await serverFetch<{ url?: string }>("/subscription/checkout", {
        method: "POST",
        body: { tier },
      });
      return NextResponse.json(res ?? {});
    }
    if (action === "portal") {
      const res = await serverFetch<{ url?: string }>("/subscription/portal", {
        method: "POST",
        body: {},
      });
      return NextResponse.json(res ?? {});
    }
    return NextResponse.json({ error: "invalid action" }, { status: 400 });
  } catch (e) {
    return mapError(e);
  }
}
