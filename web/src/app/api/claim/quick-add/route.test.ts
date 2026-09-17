import { describe, it, expect, vi, beforeEach } from "vitest";
import { UnauthorizedError, UpstreamError } from "@/lib/api/errors";

const serverFetch = vi.fn();
vi.mock("@/lib/api/client", () => ({
  serverFetch: (...args: unknown[]) => serverFetch(...args),
}));

import { POST } from "./route";

const post = (body: unknown) =>
  POST(
    new Request("http://x/api/claim/quick-add", {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify(body),
    }),
  );

beforeEach(() => {
  serverFetch.mockReset();
});

describe("POST /api/claim/quick-add (P2-1 free statement)", () => {
  it("401s without a session cookie", async () => {
    serverFetch.mockRejectedValue(new UnauthorizedError());
    expect((await post({ text: "my statement" })).status).toBe(401);
  });

  it("proxies the statement and returns 201 with the evidence body", async () => {
    serverFetch.mockResolvedValue({ id: 42, sourceType: "quick_add" });
    const res = await post({ text: "my statement" });
    expect(res.status).toBe(201);
    expect(await res.json()).toEqual({ id: 42, sourceType: "quick_add" });
    expect(serverFetch).toHaveBeenCalledWith("/claim/quick-add", {
      method: "POST",
      body: { text: "my statement" },
    });
  });

  it("400s blank, missing, non-string, or over-long text without calling upstream", async () => {
    expect((await post({ text: "   " })).status).toBe(400);
    expect((await post({})).status).toBe(400);
    expect((await post({ text: 42 })).status).toBe(400);
    expect((await post({ text: "x".repeat(10_001) })).status).toBe(400);
    expect(serverFetch).not.toHaveBeenCalled();
  });

  it("passes Spring's 400 (its own length ceiling) through", async () => {
    serverFetch.mockRejectedValue(new UpstreamError(400, "too long"));
    expect((await post({ text: "ok" })).status).toBe(400);
  });
});
