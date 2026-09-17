import { describe, it, expect, vi, beforeEach, afterEach } from "vitest";

// P0-8 viewer-mode transport contract: the ambient selection (cp_view_as
// cookie on web; module-level value on native) rides ONLY GETs as X-View-As.
// It must NEVER accompany a mutation — not even an explicit opts.viewAs.

const { cookieStore } = vi.hoisted(() => ({
  cookieStore: { session: "tok" as string | null, viewAs: null as string | null },
}));

vi.mock("server-only", () => ({}));
vi.mock("next/headers", () => ({
  cookies: async () => ({
    get: (name: string) => {
      if (name === "cp_session" && cookieStore.session) return { value: cookieStore.session };
      if (name === "cp_view_as" && cookieStore.viewAs) return { value: cookieStore.viewAs };
      return undefined;
    },
  }),
}));

import { withAmbientViewAs } from "./transport";
import { serverFetch } from "./client";
import { configureDirectApi, directFetch, setViewAs, getViewAs } from "./direct";

const fetchMock = vi.fn();

function ok(body: unknown = {}): Response {
  return new Response(JSON.stringify(body), {
    status: 200,
    headers: { "content-type": "application/json" },
  });
}

function sentHeaders(call = 0): Record<string, string> {
  return fetchMock.mock.calls[call][1].headers as Record<string, string>;
}

beforeEach(() => {
  cookieStore.session = "tok";
  cookieStore.viewAs = null;
  fetchMock.mockReset();
  // A FRESH Response per call — a Response body is single-read.
  fetchMock.mockImplementation(async () => ok());
  vi.stubGlobal("fetch", fetchMock);
});

afterEach(() => {
  setViewAs(null);
  vi.unstubAllGlobals();
});

describe("withAmbientViewAs — the read-only merge rule", () => {
  it("fills viewAs on GETs (and default-GET) from the ambient selection", () => {
    expect(withAmbientViewAs({}, 42).viewAs).toBe(42);
    expect(withAmbientViewAs({ method: "GET" }, 42).viewAs).toBe(42);
  });

  it("keeps an explicit caller viewAs on GETs", () => {
    expect(withAmbientViewAs({ viewAs: 7 }, 42).viewAs).toBe(7);
  });

  it("no ambient selection → opts unchanged", () => {
    expect(withAmbientViewAs({}, null).viewAs).toBeUndefined();
  });

  it("STRIPS viewAs from every mutation — even an explicit one", () => {
    for (const method of ["POST", "PUT", "PATCH", "DELETE"] as const) {
      expect(withAmbientViewAs({ method }, 42).viewAs).toBeUndefined();
      expect(withAmbientViewAs({ method, viewAs: 7 }, 42).viewAs).toBeUndefined();
    }
  });
});

describe("serverFetch — cp_view_as cookie → X-View-As header", () => {
  it("forwards X-View-As on GET when the cookie is set", async () => {
    cookieStore.viewAs = "42";
    await serverFetch("/claim/conditions");
    expect(sentHeaders()["X-View-As"]).toBe("42");
  });

  it("omits X-View-As when the cookie is absent", async () => {
    await serverFetch("/claim/conditions");
    expect(sentHeaders()["X-View-As"]).toBeUndefined();
  });

  it("ignores a non-numeric cookie value (no header)", async () => {
    cookieStore.viewAs = "42abc";
    await serverFetch("/claim/conditions");
    expect(sentHeaders()["X-View-As"]).toBeUndefined();
  });

  it("NEVER forwards X-View-As on mutations, cookie or not", async () => {
    cookieStore.viewAs = "42";
    await serverFetch("/claim/analyze", { method: "POST", body: {} });
    await serverFetch("/claim/evidence/5", { method: "DELETE" });
    await serverFetch("/claim/gaps/1/0/status", { method: "PATCH", body: { status: "resolved" } });
    for (let i = 0; i < 3; i++) expect(sentHeaders(i)["X-View-As"]).toBeUndefined();
  });

  it("strips even an EXPLICIT viewAs from a mutation (defense in depth)", async () => {
    await serverFetch("/claim/analyze", { method: "POST", body: {}, viewAs: 42 });
    expect(sentHeaders()["X-View-As"]).toBeUndefined();
  });
});

describe("directFetch — native module-level selection", () => {
  beforeEach(() => {
    configureDirectApi(async () => "native-tok");
  });

  it("setViewAs(claimId) puts X-View-As on GETs; setViewAs(null) clears it", async () => {
    setViewAs(42);
    expect(getViewAs()).toBe(42);
    await directFetch("/claim/conditions");
    expect(sentHeaders()["X-View-As"]).toBe("42");

    setViewAs(null);
    await directFetch("/claim/conditions");
    expect(sentHeaders(1)["X-View-As"]).toBeUndefined();
  });

  it("NEVER sends X-View-As on mutations while a selection is active", async () => {
    setViewAs(42);
    await directFetch("/claim/analyze", { method: "POST", body: {} });
    expect(sentHeaders()["X-View-As"]).toBeUndefined();
  });
});
