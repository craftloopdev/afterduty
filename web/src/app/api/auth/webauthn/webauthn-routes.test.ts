import { beforeEach, describe, expect, it, vi } from "vitest";
import { StepUpRequiredError, UnauthorizedError, UpstreamError } from "@/lib/api/errors";

// BFF proxy routes for WebAuthn (auth-program-plan P1.3). Two upstream styles:
//   - assert/* : PRE-SESSION raw passthrough (no cookie) — forwards client IP,
//                returns Spring's body/status VERBATIM (mints the session).
//   - register/*, credentials GET/PATCH : AUTHED via serverFetch.
//   - credentials/[id] DELETE : AUTHED raw passthrough that MUST forward the
//                inbound X-Step-Up header and return the step-up 403 verbatim so
//                the client's withStepUp seam can run the ceremony.

const serverFetch = vi.fn();
vi.mock("@/lib/api/client", () => ({ serverFetch: (...a: unknown[]) => serverFetch(...a) }));

let cookieToken: string | undefined = "tok-123";
vi.mock("next/headers", () => ({
  cookies: () => ({ get: () => (cookieToken ? { value: cookieToken } : undefined) }),
}));

import { POST as assertOptions } from "./assert/options/route";
import { POST as assertVerify } from "./assert/verify/route";
import { POST as registerVerify } from "./register/verify/route";
import { GET as listCreds } from "./credentials/route";
import { PATCH as patchCred, DELETE as deleteCred } from "./credentials/[id]/route";

const params = (id: string) => ({ params: Promise.resolve({ id }) });

function jsonReq(body: unknown, headers: Record<string, string> = {}): Request {
  return new Request("http://x/api/auth/webauthn", {
    method: "POST",
    headers: { "Content-Type": "application/json", ...headers },
    body: JSON.stringify(body),
  });
}

beforeEach(() => {
  serverFetch.mockReset();
  cookieToken = "tok-123";
  vi.unstubAllGlobals();
});

describe("POST /assert/options (pre-session passthrough)", () => {
  it("forwards the identifier + client IP and returns Spring's options verbatim", async () => {
    const upstream = vi.fn().mockResolvedValue(
      new Response(JSON.stringify({ challenge: "abc", allowCredentials: [] }), { status: 200 }),
    );
    vi.stubGlobal("fetch", upstream);

    const res = await assertOptions(
      jsonReq({ identifier: "vet@example.com" }, { "x-forwarded-for": "9.9.9.9" }),
    );

    expect(res.status).toBe(200);
    expect(await res.json()).toEqual({ challenge: "abc", allowCredentials: [] });
    const [url, init] = upstream.mock.calls[0] as [string, RequestInit];
    expect(url).toMatch(/\/auth\/webauthn\/assert\/options$/);
    expect((init.headers as Record<string, string>)["X-Forwarded-For"]).toBe("9.9.9.9");
    // Pre-session: NO Authorization header (identity is being established).
    expect((init.headers as Record<string, string>).Authorization).toBeUndefined();
  });

  it("returns 502 on an upstream network error", async () => {
    vi.stubGlobal("fetch", vi.fn().mockRejectedValue(new Error("down")));
    const res = await assertOptions(jsonReq({ identifier: "x" }));
    expect(res.status).toBe(502);
  });
});

describe("POST /assert/verify (pre-session passthrough)", () => {
  it("passes the { custom_token } body straight back on success", async () => {
    const upstream = vi.fn().mockResolvedValue(
      new Response(JSON.stringify({ custom_token: "CT", credentialId: "c1" }), { status: 200 }),
    );
    vi.stubGlobal("fetch", upstream);

    const res = await assertVerify(jsonReq({ credential: { id: "c1" } }));
    expect(res.status).toBe(200);
    expect(await res.json()).toEqual({ custom_token: "CT", credentialId: "c1" });
  });

  it("passes a 4xx (failed assertion) through verbatim", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue(new Response(JSON.stringify({ detail: "bad" }), { status: 400 })),
    );
    const res = await assertVerify(jsonReq({ credential: {} }));
    expect(res.status).toBe(400);
    expect(await res.json()).toEqual({ detail: "bad" });
  });
});

describe("POST /register/verify (authed via serverFetch)", () => {
  it("proxies the attestation and returns Spring's { credentialId }", async () => {
    serverFetch.mockResolvedValue({ credentialId: "new", nickname: "Laptop" });
    const res = await registerVerify(jsonReq({ credential: { id: "new" }, nickname: "Laptop" }));
    expect(res.status).toBe(200);
    expect(await res.json()).toEqual({ credentialId: "new", nickname: "Laptop" });
    expect(serverFetch).toHaveBeenCalledWith(
      "/auth/webauthn/register/verify",
      expect.objectContaining({ method: "POST" }),
    );
  });

  it("maps auth loss to 401", async () => {
    serverFetch.mockRejectedValue(new UnauthorizedError());
    expect((await registerVerify(jsonReq({ credential: {} }))).status).toBe(401);
  });
});

describe("GET /credentials (authed)", () => {
  it("returns the credentials array (never the public key)", async () => {
    serverFetch.mockResolvedValue({
      credentials: [{ id: "a", nickname: "Phone", createdAt: "d", lastUsedAt: null, deviceHint: "This device" }],
    });
    const res = await listCreds();
    expect(res.status).toBe(200);
    expect((await res.json()).credentials).toHaveLength(1);
  });

  it("defaults to an empty list when upstream returns null", async () => {
    serverFetch.mockResolvedValue(null);
    expect(await (await listCreds()).json()).toEqual({ credentials: [] });
  });
});

describe("PATCH /credentials/[id] (rename)", () => {
  it("trims + forwards the nickname", async () => {
    serverFetch.mockResolvedValue({ ok: true, nickname: "Work" });
    const res = await patchCred(jsonReq({ nickname: "  Work  " }), params("cred-1"));
    expect(res.status).toBe(200);
    expect(serverFetch).toHaveBeenCalledWith(
      "/auth/webauthn/credentials/cred-1",
      expect.objectContaining({ method: "PATCH", body: { nickname: "Work" } }),
    );
  });

  it("400s an empty nickname without touching upstream", async () => {
    const res = await patchCred(jsonReq({ nickname: "   " }), params("cred-1"));
    expect(res.status).toBe(400);
    expect(serverFetch).not.toHaveBeenCalled();
  });
});

describe("DELETE /credentials/[id] (step-up-guarded passthrough)", () => {
  it("401s without a session cookie before any upstream call", async () => {
    cookieToken = undefined;
    const upstream = vi.fn();
    vi.stubGlobal("fetch", upstream);
    const req = new Request("http://x", { method: "DELETE" });
    expect((await deleteCred(req, params("cred-1"))).status).toBe(401);
    expect(upstream).not.toHaveBeenCalled();
  });

  it("forwards the inbound X-Step-Up header and cookie→Bearer to Spring", async () => {
    const upstream = vi.fn().mockResolvedValue(new Response(null, { status: 204 }));
    vi.stubGlobal("fetch", upstream);
    const req = new Request("http://x", { method: "DELETE", headers: { "X-Step-Up": "STEP-TOK" } });

    const res = await deleteCred(req, params("cred-1"));
    expect(res.status).toBe(204);
    const [url, init] = upstream.mock.calls[0] as [string, RequestInit];
    expect(url).toMatch(/\/auth\/webauthn\/credentials\/cred-1$/);
    const h = init.headers as Record<string, string>;
    expect(h.Authorization).toBe("Bearer tok-123");
    expect(h["X-Step-Up"]).toBe("STEP-TOK");
  });

  it("returns the step-up 403 body VERBATIM so the client seam can run the ceremony", async () => {
    // This is the load-bearing behavior: using serverFetch here would throw
    // StepUpRequiredError and hide the challenge — the raw passthrough must NOT.
    const upstream = vi.fn().mockResolvedValue(
      new Response(JSON.stringify({ code: "step_up_required", acceptedFactors: ["otp"] }), {
        status: 403,
      }),
    );
    vi.stubGlobal("fetch", upstream);
    const req = new Request("http://x", { method: "DELETE" });

    const res = await deleteCred(req, params("cred-1"));
    expect(res.status).toBe(403);
    expect(await res.json()).toEqual({ code: "step_up_required", acceptedFactors: ["otp"] });
  });
});

// Guard the intent: serverFetch's mapResponse throws these on the AUTHED routes,
// so this import stays referenced (documents why the DELETE route bypasses it).
void StepUpRequiredError;
void UpstreamError;
