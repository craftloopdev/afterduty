import { describe, it, expect, vi, beforeEach } from "vitest";
import { UnauthorizedError, UpstreamError } from "@/lib/api/errors";

// Viewer-mode selection route (P0-8): POST validates the claim against the
// caller's own sharedProfiles and sets the httpOnly cp_view_as cookie; DELETE
// clears it; GET /exit clears it and redirects (the RSC layout's stale-cookie
// escape hatch).

const { jar, serverFetch } = vi.hoisted(() => ({
  jar: {
    sets: [] as Array<{ name: string; value: string; opts: Record<string, unknown> }>,
    deletes: [] as string[],
  },
  serverFetch: vi.fn(),
}));

vi.mock("next/headers", () => ({
  cookies: async () => ({
    set: (name: string, value: string, opts: Record<string, unknown>) =>
      jar.sets.push({ name, value, opts }),
    delete: (name: string) => jar.deletes.push(name),
    get: () => undefined,
  }),
}));
vi.mock("@/lib/api/client", () => ({
  serverFetch: (...args: unknown[]) => serverFetch(...args),
}));

import { POST, DELETE } from "./route";
import { GET as exitGet } from "./exit/route";

const ME = {
  id: 1,
  email: "rep@example.com",
  sharedProfiles: [
    { claimId: 42, ownerName: "Dana Vet", ownerEmail: "dana@x.com", isOwn: false, canViewAnalysis: true },
    { claimId: 7, ownerName: "Me", isOwn: true },
  ],
};

const post = (body: unknown) =>
  POST(
    new Request("http://x/api/view-as", {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify(body),
    }),
  );

beforeEach(() => {
  jar.sets = [];
  jar.deletes = [];
  serverFetch.mockReset();
});

describe("POST /api/view-as", () => {
  it("sets the httpOnly cp_view_as cookie for a claim in sharedProfiles", async () => {
    serverFetch.mockResolvedValue(ME);
    const res = await post({ claimId: 42 });
    expect(res.status).toBe(200);
    expect(await res.json()).toEqual({ ok: true, claimId: 42, ownerName: "Dana Vet" });
    expect(jar.sets).toHaveLength(1);
    const { name, value, opts } = jar.sets[0];
    expect(name).toBe("cp_view_as");
    expect(value).toBe("42");
    expect(opts).toMatchObject({ httpOnly: true, sameSite: "lax", path: "/" });
  });

  it("403s a claim NOT shared with the caller — no cookie", async () => {
    serverFetch.mockResolvedValue(ME);
    const res = await post({ claimId: 999 });
    expect(res.status).toBe(403);
    expect((await res.json()).error).toBe("no_share_access");
    expect(jar.sets).toHaveLength(0);
  });

  it("403s the caller's OWN claim (isOwn) — viewer mode is for shared claims", async () => {
    serverFetch.mockResolvedValue(ME);
    expect((await post({ claimId: 7 })).status).toBe(403);
    expect(jar.sets).toHaveLength(0);
  });

  it("400s a missing/non-integer claimId without calling upstream", async () => {
    expect((await post({})).status).toBe(400);
    expect((await post({ claimId: "42" })).status).toBe(400);
    expect((await post({ claimId: 4.5 })).status).toBe(400);
    expect((await post({ claimId: -1 })).status).toBe(400);
    expect(serverFetch).not.toHaveBeenCalled();
  });

  it("401s without a session", async () => {
    serverFetch.mockRejectedValue(new UnauthorizedError());
    expect((await post({ claimId: 42 })).status).toBe(401);
    expect(jar.sets).toHaveLength(0);
  });

  it("502s on upstream failure — never sets a cookie it can't validate", async () => {
    serverFetch.mockRejectedValue(new UpstreamError(500, "boom"));
    expect((await post({ claimId: 42 })).status).toBe(502);
    expect(jar.sets).toHaveLength(0);
  });
});

describe("DELETE /api/view-as", () => {
  it("clears the cookie and 204s (idempotent)", async () => {
    const res = await DELETE();
    expect(res.status).toBe(204);
    expect(jar.deletes).toEqual(["cp_view_as"]);
  });
});

describe("GET /api/view-as/exit", () => {
  it("clears the cookie and redirects to the sanitized destination", async () => {
    const res = await exitGet(new Request("http://x/api/view-as/exit?to=/documents"));
    expect(res.status).toBe(303);
    expect(res.headers.get("location")).toBe("http://x/documents");
    expect(jar.deletes).toEqual(["cp_view_as"]);
  });

  it("never open-redirects — absolute/protocol-relative destinations fall back to /", async () => {
    for (const to of ["https://evil.com", "//evil.com", "javascript:alert(1)"]) {
      const res = await exitGet(new Request(`http://x/api/view-as/exit?to=${encodeURIComponent(to)}`));
      expect(res.headers.get("location")).toBe("http://x/");
    }
  });
});
