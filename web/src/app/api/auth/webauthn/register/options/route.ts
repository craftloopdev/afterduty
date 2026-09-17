import { NextResponse } from "next/server";
import { serverFetch } from "@/lib/api/client";
import { ForbiddenError, UnauthorizedError, UpstreamError } from "@/lib/api/errors";

// BFF proxy for the WebAuthn REGISTER options endpoint (auth-program-plan P1.3,
// PINNED ceremony contract). AUTHED: enrolling a passkey on the CURRENT account.
// House cookie→Bearer pattern via serverFetch. The backend returns
// PublicKeyCredentialCreationOptions (challenge base64url, rp, user{opaque
// handle}, pubKeyCredParams ES256+RS256, excludeCredentials = the user's
// existing creds, authenticatorSelection, timeout) and stores the challenge
// server-side (TTL 300s, keyed to userId + ceremony=register).

function mapError(e: unknown): NextResponse {
  if (e instanceof UnauthorizedError)
    return NextResponse.json({ error: "unauthorized" }, { status: 401 });
  if (e instanceof ForbiddenError) return NextResponse.json({ error: "forbidden" }, { status: 403 });
  const status = e instanceof UpstreamError ? e.status : 502;
  return NextResponse.json({ error: "upstream" }, { status });
}

export async function POST() {
  try {
    const res = await serverFetch<unknown>("/auth/webauthn/register/options", {
      method: "POST",
      body: {},
    });
    return NextResponse.json(res);
  } catch (e) {
    return mapError(e);
  }
}
