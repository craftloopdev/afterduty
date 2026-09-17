import { NextResponse } from "next/server";
import { serverFetch } from "@/lib/api/client";
import { ForbiddenError, UnauthorizedError, UpstreamError } from "@/lib/api/errors";

function mapError(e: unknown): NextResponse {
  if (e instanceof UnauthorizedError) return NextResponse.json({ error: "unauthorized" }, { status: 401 });
  if (e instanceof ForbiddenError) return NextResponse.json({ error: "forbidden" }, { status: 403 });
  // Spring's 400 (blank / too-long statement) passes through.
  const status = e instanceof UpstreamError ? e.status : 502;
  return NextResponse.json({ error: "upstream" }, { status });
}

// Mirrors IntakeController.QUICK_ADD_MAX_CHARS — quick-add is for short
// statements; real documents go through /api/upload.
const MAX_CHARS = 10_000;

/**
 * Free quick-add text statement (P2-1): `{ "text": "..." }` → 201 +
 * EvidenceResponse. Ungated on the backend (same pipeline as file uploads,
 * which are free); the paid AI processing stays gated downstream.
 */
export async function POST(request: Request) {
  let text: unknown;
  try {
    text = ((await request.json()) as { text?: unknown })?.text;
  } catch {
    return NextResponse.json({ error: "bad_request" }, { status: 400 });
  }
  if (typeof text !== "string" || !text.trim() || text.length > MAX_CHARS) {
    return NextResponse.json({ error: "bad_request" }, { status: 400 });
  }

  try {
    const res = await serverFetch<Record<string, unknown>>("/claim/quick-add", {
      method: "POST",
      body: { text },
    });
    return NextResponse.json(res ?? { ok: true }, { status: 201 });
  } catch (e) {
    return mapError(e);
  }
}
