import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

// BFF proxy routes for dual-channel factor-2 recovery (auth program P1.5). Both
// are PRE-SESSION raw passthroughs (no cookie): they forward the client IP and
// return Spring's body/status VERBATIM so the page can branch on the generic 400
// and the 409 {code:"recovery_needs_support"}. /start additionally stays
// anti-enumeration-shaped even on a network blip (a flat 200 {ok:true}).

import { POST as startRoute } from "./start/route";
import { POST as verifyRoute } from "./verify/route";

function jsonReq(body: unknown, headers: Record<string, string> = {}): Request {
  return new Request("http://x/api/auth/recovery", {
    method: "POST",
    headers: { "Content-Type": "application/json", ...headers },
    body: JSON.stringify(body),
  });
}

beforeEach(() => vi.unstubAllGlobals());
afterEach(() => vi.restoreAllMocks());

describe("POST /api/auth/recovery/start (pre-session, anti-enumeration)", () => {
  it("forwards the identifier + client IP and returns Spring's 200 verbatim", async () => {
    const upstream = vi
      .fn()
      .mockResolvedValue(new Response(JSON.stringify({ ok: true }), { status: 200 }));
    vi.stubGlobal("fetch", upstream);

    const res = await startRoute(
      jsonReq({ identifier: "vet@example.com" }, { "x-forwarded-for": "9.9.9.9" }),
    );

    expect(res.status).toBe(200);
    expect(await res.json()).toEqual({ ok: true });
    const [url, init] = upstream.mock.calls[0] as [string, RequestInit];
    expect(url).toMatch(/\/auth\/recovery\/start$/);
    expect((init.headers as Record<string, string>)["X-Forwarded-For"]).toBe("9.9.9.9");
    // Pre-session: never a session cookie → Bearer.
    expect((init.headers as Record<string, string>).Authorization).toBeUndefined();
  });

  it("still returns a flat 200 {ok:true} on an upstream network error (anti-enum)", async () => {
    vi.stubGlobal("fetch", vi.fn().mockRejectedValue(new Error("down")));
    const res = await startRoute(jsonReq({ identifier: "x" }));
    expect(res.status).toBe(200);
    expect(await res.json()).toEqual({ ok: true });
  });
});

describe("POST /api/auth/recovery/verify (pre-session passthrough)", () => {
  it("passes the { custom_token } success body straight back", async () => {
    const upstream = vi.fn().mockResolvedValue(
      new Response(JSON.stringify({ custom_token: "ct", revokedFactors: ["passkey"] }), {
        status: 200,
      }),
    );
    vi.stubGlobal("fetch", upstream);

    const res = await verifyRoute(
      jsonReq({ email: "v@e.com", emailCode: "246810", phoneIdToken: "tok" }),
    );

    expect(res.status).toBe(200);
    expect(await res.json()).toEqual({ custom_token: "ct", revokedFactors: ["passkey"] });
    const [url] = upstream.mock.calls[0] as [string, RequestInit];
    expect(url).toMatch(/\/auth\/recovery\/verify$/);
  });

  it("returns the 409 {code:recovery_needs_support} verbatim", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue(
        new Response(JSON.stringify({ code: "recovery_needs_support" }), { status: 409 }),
      ),
    );

    const res = await verifyRoute(jsonReq({ email: "v@e.com", emailCode: "1", phoneIdToken: "t" }));
    expect(res.status).toBe(409);
    expect(await res.json()).toEqual({ code: "recovery_needs_support" });
  });

  it("returns the generic 400 {detail} verbatim", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue(
        new Response(JSON.stringify({ detail: "Recovery failed." }), { status: 400 }),
      ),
    );

    const res = await verifyRoute(jsonReq({ email: "v@e.com", emailCode: "0", phoneIdToken: "t" }));
    expect(res.status).toBe(400);
    expect(await res.json()).toEqual({ detail: "Recovery failed." });
  });

  it("returns 502 on an upstream network error", async () => {
    vi.stubGlobal("fetch", vi.fn().mockRejectedValue(new Error("down")));
    const res = await verifyRoute(jsonReq({ email: "v@e.com", emailCode: "1", phoneIdToken: "t" }));
    expect(res.status).toBe(502);
  });
});
