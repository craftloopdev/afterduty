import { afterEach, describe, expect, it, vi } from "vitest";

// `condHref` / `acceptShareHref` are the single decision point for the two
// dynamic routes that can't static-export (§A.3a). The web build keeps the
// canonical dynamic path; the native build (NEXT_PUBLIC_NATIVE=1) must emit the
// query-param TWIN, because scripts/native-export.mjs stashes the `[id]`/`[token]`
// routes out of `out/` — only the twins exist in the WKWebView bundle. A regress
// here is a native dead-end (the AcceptShare 401→/login→return loop, dim 6/7).

afterEach(() => {
  vi.resetModules();
  vi.unstubAllEnvs();
});

async function loadPlatform() {
  // platform.ts reads `process.env.NEXT_PUBLIC_NATIVE` at module-eval time, so
  // each case stubs the env then imports fresh.
  return import("./platform");
}

describe("acceptShareHref", () => {
  it("web build: canonical dynamic path", async () => {
    vi.stubEnv("NEXT_PUBLIC_NATIVE", "");
    const { acceptShareHref, NATIVE } = await loadPlatform();
    expect(NATIVE).toBe(false);
    expect(acceptShareHref("TOK123")).toBe("/accept-share/TOK123");
  });

  it("native build: query-param twin (the route that exists in out/)", async () => {
    vi.stubEnv("NEXT_PUBLIC_NATIVE", "1");
    const { acceptShareHref, NATIVE } = await loadPlatform();
    expect(NATIVE).toBe(true);
    expect(acceptShareHref("TOK123")).toBe("/accept-share?token=TOK123");
  });
});

describe("condHref", () => {
  it("web build: canonical dynamic path", async () => {
    vi.stubEnv("NEXT_PUBLIC_NATIVE", "");
    const { condHref } = await loadPlatform();
    expect(condHref(99)).toBe("/conditions/99");
  });

  it("native build: query-param twin", async () => {
    vi.stubEnv("NEXT_PUBLIC_NATIVE", "1");
    const { condHref } = await loadPlatform();
    expect(condHref(99)).toBe("/conditions/detail?id=99");
  });
});
