import { NextResponse } from "next/server";
import { serverFetch } from "@/lib/api/client";
import { ForbiddenError, UnauthorizedError, UpstreamError } from "@/lib/api/errors";

// BFF proxy for the WebAuthn credentials LIST endpoint (auth-program-plan P1.3,
// PINNED MANAGE contract). AUTHED. Returns `{ credentials:[{id, nickname,
// createdAt, lastUsedAt, deviceHint}] }` — display metadata only; the public key
// is NEVER exposed. House cookie→Bearer via serverFetch.

function mapError(e: unknown): NextResponse {
  if (e instanceof UnauthorizedError)
    return NextResponse.json({ error: "unauthorized" }, { status: 401 });
  if (e instanceof ForbiddenError) return NextResponse.json({ error: "forbidden" }, { status: 403 });
  const status = e instanceof UpstreamError ? e.status : 502;
  return NextResponse.json({ error: "upstream" }, { status });
}

export async function GET() {
  try {
    const res = await serverFetch<{ credentials?: unknown } | null>("/auth/webauthn/credentials");
    return NextResponse.json(res ?? { credentials: [] });
  } catch (e) {
    return mapError(e);
  }
}
