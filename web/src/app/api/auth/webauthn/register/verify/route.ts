import { NextResponse } from "next/server";
import { serverFetch } from "@/lib/api/client";
import { ForbiddenError, UnauthorizedError, UpstreamError } from "@/lib/api/errors";

// BFF proxy for the WebAuthn REGISTER verify endpoint (auth-program-plan P1.3,
// PINNED ceremony contract). AUTHED. Body `{ credential:<attestation>,
// nickname? }`; the backend verifies the attestation (challenge single-use +
// TTL, origin, rpId hash) via its WebAuthn library, persists the credential, and
// returns `{ credentialId, nickname }`. Audit PASSKEY_REGISTERED is emitted
// server-side.

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
    const res = await serverFetch<unknown>("/auth/webauthn/register/verify", {
      method: "POST",
      body,
    });
    return NextResponse.json(res ?? { ok: true });
  } catch (e) {
    return mapError(e);
  }
}
