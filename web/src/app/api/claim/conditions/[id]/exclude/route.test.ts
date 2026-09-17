import { describe, it, expect, vi, beforeEach } from "vitest";
import { ForbiddenError, UnauthorizedError, UpstreamError } from "@/lib/api/errors";

const serverFetch = vi.fn();
vi.mock("@/lib/api/client", () => ({
  serverFetch: (...args: unknown[]) => serverFetch(...args),
}));

import { POST } from "./route";

const post = (id: string, body: unknown) =>
  POST(
    new Request(`http://x/api/claim/conditions/${id}/exclude`, {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify(body),
    }),
    { params: Promise.resolve({ id }) },
  );

beforeEach(() => {
  serverFetch.mockReset();
});

describe("POST /api/claim/conditions/[id]/exclude", () => {
  it("401s without a session cookie", async () => {
    serverFetch.mockRejectedValue(new UnauthorizedError());
    expect((await post("7", { excluded: true })).status).toBe(401);
  });

  it("proxies a valid exclude and returns 204", async () => {
    serverFetch.mockResolvedValue(null);
    const res = await post("7", { excluded: true });
    expect(res.status).toBe(204);
    expect(serverFetch).toHaveBeenCalledWith("/claim/conditions/7/exclude", {
      method: "POST",
      body: { excluded: true },
    });
  });

  it("proxies a valid include (excluded:false) and returns 204", async () => {
    serverFetch.mockResolvedValue(null);
    const res = await post("7", { excluded: false });
    expect(res.status).toBe(204);
    expect(serverFetch).toHaveBeenCalledWith("/claim/conditions/7/exclude", {
      method: "POST",
      body: { excluded: false },
    });
  });

  it("400s a non-boolean excluded without calling upstream", async () => {
    expect((await post("7", { excluded: "yes" })).status).toBe(400);
    expect((await post("7", {})).status).toBe(400);
    expect(serverFetch).not.toHaveBeenCalled();
  });

  it("404s a non-numeric id without calling upstream", async () => {
    expect((await post("evil", { excluded: true })).status).toBe(404);
    expect((await post("../x", { excluded: true })).status).toBe(404);
    expect(serverFetch).not.toHaveBeenCalled();
  });

  it("maps Spring's 403 (cross-user write) to forbidden", async () => {
    serverFetch.mockRejectedValue(new ForbiddenError());
    expect((await post("7", { excluded: true })).status).toBe(403);
  });

  it("passes Spring's 404 (unknown/superseded condition) through", async () => {
    serverFetch.mockRejectedValue(new UpstreamError(404, "not found"));
    expect((await post("7", { excluded: true })).status).toBe(404);
  });
});
