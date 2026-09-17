import { beforeEach, describe, expect, it, vi } from "vitest";
import { UnauthorizedError, UpstreamError } from "@/lib/api/errors";

// BFF routes for per-document actions (P1-29/P1-30). serverFetch is the only
// upstream seam for delete/facts; the download route is a raw byte passthrough
// (cookie → Bearer) so it mocks next/headers + global fetch instead.

const serverFetch = vi.fn();
vi.mock("@/lib/api/client", () => ({
  serverFetch: (...args: unknown[]) => serverFetch(...args),
}));

let cookieToken: string | undefined = "tok-123";
vi.mock("next/headers", () => ({
  cookies: () => ({ get: () => (cookieToken ? { value: cookieToken } : undefined) }),
}));

import { DELETE } from "./route";
import { GET as getFacts } from "./facts/route";
import { GET as getDownload } from "./download/route";

const params = (id: string) => ({ params: Promise.resolve({ id }) });
const req = (path = "/api/claim/evidence/12") => new Request(`http://x${path}`);

beforeEach(() => {
  serverFetch.mockReset();
  cookieToken = "tok-123";
  vi.unstubAllGlobals();
});

describe("DELETE /api/claim/evidence/[id]", () => {
  it("forwards the delete and passes Spring's 204 through", async () => {
    serverFetch.mockResolvedValue(null);
    const res = await DELETE(req(), params("12"));
    expect(res.status).toBe(204);
    expect(serverFetch).toHaveBeenCalledWith("/claim/evidence/12", { method: "DELETE" });
  });

  it("404s a non-numeric id without touching upstream", async () => {
    const res = await DELETE(req(), params("12; DROP"));
    expect(res.status).toBe(404);
    expect(serverFetch).not.toHaveBeenCalled();
  });

  it("maps auth loss to 401 and passes upstream statuses through", async () => {
    serverFetch.mockRejectedValueOnce(new UnauthorizedError());
    expect((await DELETE(req(), params("12"))).status).toBe(401);
    serverFetch.mockRejectedValueOnce(new UpstreamError(404, "gone"));
    expect((await DELETE(req(), params("12"))).status).toBe(404);
  });
});

describe("GET /api/claim/evidence/[id]/facts", () => {
  it("proxies the AtomDto list verbatim", async () => {
    const facts = [{ type: "diagnosis", value: "PTSD", source: "a.pdf", confidence: 0.9, date: null }];
    serverFetch.mockResolvedValue(facts);
    const res = await getFacts(req(), params("7"));
    expect(res.status).toBe(200);
    expect(await res.json()).toEqual(facts);
    expect(serverFetch).toHaveBeenCalledWith("/claim/evidence/7/facts", { allow404AsNull: true });
  });

  it("maps an upstream 404 (unknown evidence) to 404", async () => {
    serverFetch.mockResolvedValue(null);
    expect((await getFacts(req(), params("7"))).status).toBe(404);
  });

  it("404s a non-numeric id without touching upstream", async () => {
    expect((await getFacts(req(), params("x"))).status).toBe(404);
    expect(serverFetch).not.toHaveBeenCalled();
  });
});

describe("GET /api/claim/evidence/[id]/download", () => {
  it("streams the file through with the attachment headers", async () => {
    const upstream = vi.fn().mockResolvedValue(
      new Response("PDFBYTES", {
        status: 200,
        headers: {
          "content-type": "application/pdf",
          "content-disposition": 'attachment; filename="dd214.pdf"',
          "x-content-type-options": "nosniff",
        },
      }),
    );
    vi.stubGlobal("fetch", upstream);

    const res = await getDownload(req(), params("12"));
    expect(res.status).toBe(200);
    expect(res.headers.get("content-type")).toBe("application/pdf");
    expect(res.headers.get("content-disposition")).toBe('attachment; filename="dd214.pdf"');
    expect(res.headers.get("x-content-type-options")).toBe("nosniff");
    expect(await res.text()).toBe("PDFBYTES");

    const [url, init] = upstream.mock.calls[0] as [string, RequestInit];
    expect(url).toMatch(/\/claim\/evidence\/12\/download$/);
    expect((init.headers as Record<string, string>).Authorization).toBe("Bearer tok-123");
  });

  it("401s without a session cookie, before any upstream call", async () => {
    cookieToken = undefined;
    const upstream = vi.fn();
    vi.stubGlobal("fetch", upstream);
    expect((await getDownload(req(), params("12"))).status).toBe(401);
    expect(upstream).not.toHaveBeenCalled();
  });

  it("maps an upstream failure to a JSON error with the same status", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(new Response("nope", { status: 404 })));
    const res = await getDownload(req(), params("12"));
    expect(res.status).toBe(404);
    expect(await res.json()).toEqual({ error: "download_failed" });
  });

  it("404s a non-numeric id without touching upstream", async () => {
    const upstream = vi.fn();
    vi.stubGlobal("fetch", upstream);
    expect((await getDownload(req(), params("../7"))).status).toBe(404);
    expect(upstream).not.toHaveBeenCalled();
  });
});
