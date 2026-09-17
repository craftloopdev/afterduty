import { beforeEach, describe, expect, it, vi } from "vitest";

// BFF proxy for POST /api/auth/email-code/request (passwordless-otp-auth-spec
// §2.1 / §2.3). The route is PRE-AUTH for a sign-in code, but the SAME route
// serves purpose=attach (phone-first dual-verify) and purpose=stepup, and the
// backend only honours those purposes when the request carries a Bearer —
// an unauthenticated attach/stepup request is fail-safed down to SIGNIN
// (EmailCodeAuthController.request). So when a session cookie is present the
// proxy must convert it to Authorization: Bearer, exactly like the attach
// proxy does; otherwise the emailed code is minted as SIGNIN and /attach
// (which only accepts ATTACH rows) rejects it as "Incorrect or expired code".

let cookieToken: string | undefined;
vi.mock("next/headers", () => ({
  cookies: () => ({ get: () => (cookieToken ? { value: cookieToken } : undefined) }),
}));

import { POST } from "./route";

function stubUpstream(status: number, body: unknown): void {
  vi.stubGlobal(
    "fetch",
    vi.fn(() =>
      Promise.resolve(new Response(body == null ? null : JSON.stringify(body), { status })),
    ),
  );
}

function upstreamCall(): { url: string; init: RequestInit } {
  const calls = vi.mocked(fetch).mock.calls;
  const [url, init] = calls[calls.length - 1];
  return { url: String(url), init: init as RequestInit };
}

function req(body: unknown, headers: Record<string, string> = {}): Request {
  return new Request("http://x/api/auth/email-code/request", {
    method: "POST",
    headers: { "Content-Type": "application/json", ...headers },
    body: JSON.stringify(body),
  });
}

beforeEach(() => {
  cookieToken = undefined;
  vi.unstubAllGlobals();
});

describe("POST /api/auth/email-code/request", () => {
  it("converts a session cookie to Bearer so purpose=attach reaches the backend authed", async () => {
    cookieToken = "tok-123";
    stubUpstream(200, { ok: true });

    const res = await POST(req({ email: "vet@example.com", purpose: "attach" }));

    expect(res.status).toBe(200);
    const { url, init } = upstreamCall();
    expect(url).toMatch(/\/auth\/email-code\/request$/);
    expect((init.headers as Record<string, string>).Authorization).toBe("Bearer tok-123");
    expect(JSON.parse(String(init.body))).toEqual({ email: "vet@example.com", purpose: "attach" });
  });

  it("stays pre-auth for a sign-in code: no cookie → no Authorization header", async () => {
    stubUpstream(200, { ok: true });

    const res = await POST(req({ email: "vet@example.com" }));

    expect(res.status).toBe(200);
    expect((upstreamCall().init.headers as Record<string, string>).Authorization).toBeUndefined();
  });

  it("forwards the client IP headers the backend's per-IP cap relies on", async () => {
    stubUpstream(200, { ok: true });

    await POST(req({ email: "vet@example.com" }, { "x-forwarded-for": "1.2.3.4, 5.6.7.8" }));

    const headers = upstreamCall().init.headers as Record<string, string>;
    expect(headers["X-Forwarded-For"]).toBe("1.2.3.4, 5.6.7.8");
  });

  it("passes the backend's user-facing {detail} and status straight back", async () => {
    stubUpstream(429, { detail: "Too many codes requested. Try again later." });

    const res = await POST(req({ email: "vet@example.com" }));

    expect(res.status).toBe(429);
    expect(await res.json()).toEqual({ detail: "Too many codes requested. Try again later." });
  });
});
