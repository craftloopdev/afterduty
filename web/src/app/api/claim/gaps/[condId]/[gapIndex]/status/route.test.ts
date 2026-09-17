import { describe, it, expect, vi, beforeEach } from "vitest";
import { UnauthorizedError, UpstreamError } from "@/lib/api/errors";

const serverFetch = vi.fn();
vi.mock("@/lib/api/client", () => ({
  serverFetch: (...args: unknown[]) => serverFetch(...args),
}));

import { PATCH } from "./route";

const patch = (condId: string, gapIndex: string, body: unknown) =>
  PATCH(
    new Request(`http://x/api/claim/gaps/${condId}/${gapIndex}/status`, {
      method: "PATCH",
      headers: { "content-type": "application/json" },
      body: JSON.stringify(body),
    }),
    { params: Promise.resolve({ condId, gapIndex }) },
  );

beforeEach(() => {
  serverFetch.mockReset();
});

describe("PATCH /api/claim/gaps/[condId]/[gapIndex]/status", () => {
  it("401s without a session cookie", async () => {
    serverFetch.mockRejectedValue(new UnauthorizedError());
    expect((await patch("99", "0", { status: "resolved" })).status).toBe(401);
  });

  it("proxies a valid status change and returns the upstream body", async () => {
    serverFetch.mockResolvedValue({ ok: true, status: "resolved" });
    const res = await patch("99", "0", { status: "resolved" });
    expect(res.status).toBe(200);
    expect(await res.json()).toEqual({ ok: true, status: "resolved" });
    expect(serverFetch).toHaveBeenCalledWith("/claim/gaps/99/0/status", {
      method: "PATCH",
      body: { status: "resolved" },
    });
  });

  it("400s an unknown status without calling upstream", async () => {
    expect((await patch("99", "0", { status: "done" })).status).toBe(400);
    expect((await patch("99", "0", {})).status).toBe(400);
    expect(serverFetch).not.toHaveBeenCalled();
  });

  it("404s non-numeric params without calling upstream", async () => {
    expect((await patch("evil", "0", { status: "open" })).status).toBe(404);
    expect((await patch("99", "../x", { status: "open" })).status).toBe(404);
    expect(serverFetch).not.toHaveBeenCalled();
  });

  it("passes Spring's 404 (not the caller's gap) through", async () => {
    serverFetch.mockRejectedValue(new UpstreamError(404, "not found"));
    expect((await patch("99", "7", { status: "dismissed" })).status).toBe(404);
  });
});
