import { describe, it, expect, vi, beforeEach, afterEach } from "vitest";

// BFF cap mapping (P1-10): BOTH chat routes hand-inspect the upstream error
// body so the monthly usage cap (today a 402 whose body carries
// USAGE_LIMIT_REACHED; tomorrow possibly a bare 429) surfaces to the client as
// a uniform 429 { code: "USAGE_LIMIT_REACHED", resumesAt? } — and NEVER
// collapses into subscription_required for a paying user.

const { cookieStore, serverFetch } = vi.hoisted(() => ({
  cookieStore: { token: "tok" as string | null },
  serverFetch: vi.fn(),
}));
vi.mock("next/headers", () => ({
  cookies: async () => ({
    get: () => (cookieStore.token ? { value: cookieStore.token } : undefined),
  }),
}));
vi.mock("@/lib/api/client", () => ({
  serverFetch: (...args: unknown[]) => serverFetch(...args),
}));

import { POST as chatPost } from "./route";
import { POST as streamPost } from "./stream/route";

const fetchMock = vi.fn();

beforeEach(() => {
  cookieStore.token = "tok";
  serverFetch.mockReset();
  fetchMock.mockReset();
  vi.stubGlobal("fetch", fetchMock);
});

afterEach(() => {
  vi.unstubAllGlobals();
});

function req(body: unknown): Request {
  return new Request("http://x/api/chat", {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify(body),
  });
}

function upstream(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "content-type": "application/json" },
  });
}

const CAP_BODY = {
  error: "usage_limit_reached",
  code: "USAGE_LIMIT_REACHED",
  resumesAt: "2026-08-01T00:00:00Z",
};

describe("POST /api/chat (non-streaming fallback)", () => {
  it("401s without a session cookie, before any upstream call", async () => {
    cookieStore.token = null;
    const res = await chatPost(req({ message: "hi" }));
    expect(res.status).toBe(401);
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it("400s an empty message", async () => {
    expect((await chatPost(req({ message: "  " }))).status).toBe(400);
  });

  it("maps today's 402+USAGE_LIMIT_REACHED body to the uniform 429 cap shape", async () => {
    fetchMock.mockResolvedValue(
      upstream(402, { code: "USAGE_LIMIT_REACHED", resumesAt: "2026-08-01T00:00:00Z" }),
    );
    const res = await chatPost(req({ message: "hi" }));
    expect(res.status).toBe(429);
    expect(await res.json()).toEqual(CAP_BODY);
  });

  it("maps a bare upstream 429 to the uniform cap shape", async () => {
    fetchMock.mockResolvedValue(upstream(429, {}));
    const res = await chatPost(req({ message: "hi" }));
    expect(res.status).toBe(429);
    expect(await res.json()).toMatchObject({ code: "USAGE_LIMIT_REACHED" });
  });

  it("keeps a PLAIN 402 as subscription_required", async () => {
    fetchMock.mockResolvedValue(upstream(402, { error: "payment required" }));
    const res = await chatPost(req({ message: "hi" }));
    expect(res.status).toBe(402);
    expect(await res.json()).toEqual({ error: "subscription_required" });
  });

  it("returns the refreshed thread on success", async () => {
    fetchMock.mockResolvedValue(upstream(200, { ok: true }));
    serverFetch.mockResolvedValue([{ id: 7, role: "USER", content: "hi" }]);
    const res = await chatPost(req({ message: "hi" }));
    expect(res.status).toBe(200);
    expect(await res.json()).toEqual([
      { id: "7", role: "user", content: "hi" },
    ]);
    expect(serverFetch).toHaveBeenCalledWith("/claim/messages", { allow404AsNull: true });
    // The send itself went out cookie→Bearer with allowlisted headers.
    expect(fetchMock).toHaveBeenCalledWith(
      expect.stringContaining("/claim/chat"),
      expect.objectContaining({
        method: "POST",
        headers: expect.objectContaining({ Authorization: "Bearer tok" }),
      }),
    );
  });

  it("502s a network failure to upstream", async () => {
    fetchMock.mockRejectedValue(new Error("boom"));
    expect((await chatPost(req({ message: "hi" }))).status).toBe(502);
  });
});

describe("POST /api/chat/stream (SSE passthrough)", () => {
  it("401s without a session cookie", async () => {
    cookieStore.token = null;
    const res = await streamPost(req({ message: "hi" }));
    expect(res.status).toBe(401);
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it("maps today's 402+USAGE_LIMIT_REACHED body to the uniform 429 cap shape", async () => {
    fetchMock.mockResolvedValue(upstream(402, CAP_BODY));
    const res = await streamPost(req({ message: "hi" }));
    expect(res.status).toBe(429);
    expect(await res.json()).toEqual(CAP_BODY);
  });

  it("keeps a PLAIN 402 as subscription_required and a 409 as streaming_disabled", async () => {
    fetchMock.mockResolvedValue(upstream(402, { error: "nope" }));
    const gate = await streamPost(req({ message: "hi" }));
    expect(gate.status).toBe(402);
    expect(await gate.json()).toEqual({ error: "subscription_required" });

    fetchMock.mockResolvedValue(upstream(409, {}));
    const conflict = await streamPost(req({ message: "hi" }));
    expect(conflict.status).toBe(409);
    expect(await conflict.json()).toEqual({ error: "streaming_disabled" });
  });

  it("passes a 200 SSE body straight through with no-transform headers", async () => {
    const body = new Response("event: ack\ndata: {}\n\n", {
      status: 200,
      headers: { "content-type": "text/event-stream" },
    });
    fetchMock.mockResolvedValue(body);
    const res = await streamPost(req({ message: "hi" }));
    expect(res.status).toBe(200);
    expect(res.headers.get("Content-Type")).toBe("text/event-stream");
    expect(res.headers.get("Cache-Control")).toContain("no-transform");
    expect(await res.text()).toContain("event: ack");
  });
});
