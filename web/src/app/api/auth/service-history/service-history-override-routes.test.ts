import { beforeEach, describe, expect, it, vi } from "vitest";
import { UnauthorizedError, UpstreamError } from "@/lib/api/errors";

// BFF proxy routes for the veteran service-history override (Service History P3
// Part A). Both AUTHED via serverFetch (cookie→Bearer; viewer header stripped from
// mutations), so a VSO can never correct the veteran's history. POST upserts, the
// [clusterKey] DELETE clears.

const serverFetch = vi.fn();
vi.mock("@/lib/api/client", () => ({ serverFetch: (...a: unknown[]) => serverFetch(...a) }));

import { POST as upsert } from "./override/route";
import { DELETE as clear } from "./override/[clusterKey]/route";

function jsonReq(body: unknown): Request {
  return new Request("http://x/api/auth/service-history/override", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(body),
  });
}

beforeEach(() => {
  serverFetch.mockReset();
});

describe("POST /auth/service-history/override (upsert)", () => {
  it("forwards the body to Spring and returns its response", async () => {
    serverFetch.mockResolvedValue({ ok: true, servicePeriods: [] });
    const res = await upsert(jsonReq({ clusterKey: "Navy|active|2001", endDate: "2010-06-01" }));

    expect(res.status).toBe(200);
    expect(await res.json()).toEqual({ ok: true, servicePeriods: [] });
    const [path, opts] = serverFetch.mock.calls[0] as [string, { method: string; body: unknown }];
    expect(path).toBe("/auth/service-history/override");
    expect(opts.method).toBe("POST");
    expect(opts.body).toMatchObject({ clusterKey: "Navy|active|2001", endDate: "2010-06-01" });
  });

  it("returns 400 on an unparseable body", async () => {
    const bad = new Request("http://x", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: "{not json",
    });
    const res = await upsert(bad);
    expect(res.status).toBe(400);
    expect(serverFetch).not.toHaveBeenCalled();
  });

  it("maps an unauthorized upstream to 401", async () => {
    serverFetch.mockRejectedValue(new UnauthorizedError());
    const res = await upsert(jsonReq({ clusterKey: "x", rank: "CPO" }));
    expect(res.status).toBe(401);
  });

  it("propagates an upstream 400 (validation) as the same status", async () => {
    serverFetch.mockRejectedValue(new UpstreamError(400, "bad"));
    const res = await upsert(jsonReq({ clusterKey: "x" }));
    expect(res.status).toBe(400);
  });
});

describe("DELETE /auth/service-history/override/[clusterKey] (clear)", () => {
  it("url-encodes the clusterKey and forwards a DELETE", async () => {
    serverFetch.mockResolvedValue({ ok: true, servicePeriods: [] });
    const res = await clear(new Request("http://x", { method: "DELETE" }), {
      params: Promise.resolve({ clusterKey: "Navy|active|2001" }),
    });

    expect(res.status).toBe(200);
    const [path, opts] = serverFetch.mock.calls[0] as [string, { method: string }];
    expect(path).toBe("/auth/service-history/override/Navy%7Cactive%7C2001");
    expect(opts.method).toBe("DELETE");
  });

  it("maps an unauthorized upstream to 401", async () => {
    serverFetch.mockRejectedValue(new UnauthorizedError());
    const res = await clear(new Request("http://x", { method: "DELETE" }), {
      params: Promise.resolve({ clusterKey: "k" }),
    });
    expect(res.status).toBe(401);
  });
});
