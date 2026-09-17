import { describe, it, expect, vi, beforeEach } from "vitest";
import { UnauthorizedError, UpstreamError } from "@/lib/api/errors";

// The route's only upstream seam is serverFetch (cookie → Bearer). Mocking it
// keeps these tests pure route-mapping tests: 401 without a session cookie is
// exactly "serverFetch throws UnauthorizedError" (client.ts throws before any
// network I/O when the cp_session cookie is absent).
const serverFetch = vi.fn();
vi.mock("@/lib/api/client", () => ({
  serverFetch: (...args: unknown[]) => serverFetch(...args),
}));

import { GET } from "./route";
import { POST as markRead } from "./mark-read/route";

beforeEach(() => {
  serverFetch.mockReset();
});

describe("GET /api/notifications", () => {
  it("401s without a session cookie", async () => {
    serverFetch.mockRejectedValue(new UnauthorizedError());
    const res = await GET(new Request("http://x/api/notifications"));
    expect(res.status).toBe(401);
  });

  it("proxies the envelope and forwards ONLY the sanitized query params", async () => {
    const body = { notifications: [{ id: 1 }], unreadCount: 1 };
    serverFetch.mockResolvedValue(body);
    const res = await GET(
      new Request("http://x/api/notifications?limit=25&unreadOnly=true&evil=1"),
    );
    expect(res.status).toBe(200);
    expect(await res.json()).toEqual(body);
    expect(serverFetch).toHaveBeenCalledWith("/notifications?limit=25&unreadOnly=true", {
      allow404AsNull: true,
    });
  });

  it("degrades a 404 (no claim yet) to the empty envelope", async () => {
    serverFetch.mockResolvedValue(null);
    const res = await GET(new Request("http://x/api/notifications"));
    expect(await res.json()).toEqual({ notifications: [], unreadCount: 0 });
  });

  it("drops a non-numeric limit instead of forwarding it", async () => {
    serverFetch.mockResolvedValue({ notifications: [], unreadCount: 0 });
    await GET(new Request("http://x/api/notifications?limit=abc"));
    expect(serverFetch).toHaveBeenCalledWith("/notifications", { allow404AsNull: true });
  });
});

describe("POST /api/notifications/mark-read", () => {
  const post = (body: unknown) =>
    markRead(
      new Request("http://x/api/notifications/mark-read", {
        method: "POST",
        headers: { "content-type": "application/json" },
        body: JSON.stringify(body),
      }),
    );

  it("401s without a session cookie", async () => {
    serverFetch.mockRejectedValue(new UnauthorizedError());
    expect((await post({ ids: [1] })).status).toBe(401);
  });

  it("forwards a REBUILT ids body and returns { updated }", async () => {
    serverFetch.mockResolvedValue({ updated: 2 });
    const res = await post({ ids: [41, 42, "junk"], extra: "never-forwarded" });
    expect(res.status).toBe(200);
    expect(await res.json()).toEqual({ updated: 2 });
    expect(serverFetch).toHaveBeenCalledWith("/notifications/mark-read", {
      method: "POST",
      body: { ids: [41, 42] },
    });
  });

  it("forwards { all: true }", async () => {
    serverFetch.mockResolvedValue({ updated: 9 });
    await post({ all: true });
    expect(serverFetch).toHaveBeenCalledWith("/notifications/mark-read", {
      method: "POST",
      body: { all: true },
    });
  });

  it("400s a body with neither ids nor all (and never calls upstream)", async () => {
    expect((await post({})).status).toBe(400);
    expect((await post({ ids: [] })).status).toBe(400);
    expect(serverFetch).not.toHaveBeenCalled();
  });

  it("passes an upstream failure status through", async () => {
    serverFetch.mockRejectedValue(new UpstreamError(503, "down"));
    expect((await post({ all: true })).status).toBe(503);
  });
});
