import { NextResponse } from "next/server";
import { loadDocuments } from "@/lib/api/endpoints";
import { ForbiddenError, UnauthorizedError, UpstreamError } from "@/lib/api/errors";

function mapError(e: unknown): NextResponse {
  if (e instanceof UnauthorizedError) return NextResponse.json({ error: "unauthorized" }, { status: 401 });
  if (e instanceof ForbiddenError) return NextResponse.json({ error: "forbidden" }, { status: 403 });
  const status = e instanceof UpstreamError ? e.status : 502;
  return NextResponse.json({ error: "upstream" }, { status });
}

// Client-refreshable documents list. The RSC seeds the page, but a successful
// upload / delete must update the list WITHOUT a manual browser refresh —
// router.refresh() proved unreliable for this in prod, so DocumentsClient
// re-fetches this route (cookie session → Bearer, reusing the exact same
// loadDocuments read the server render uses, so the shape is identical).
export const dynamic = "force-dynamic";

export async function GET() {
  try {
    // loadDocuments now returns { docs, subState }; the client refresh only
    // needs the list (subState is seeded once by the RSC and doesn't change on
    // an upload/delete), so forward just `docs` — the shape stays { docs }.
    const { docs } = await loadDocuments();
    return NextResponse.json({ docs });
  } catch (e) {
    return mapError(e);
  }
}
