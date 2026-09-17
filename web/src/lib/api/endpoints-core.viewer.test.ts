import { describe, it, expect } from "vitest";
import { makeEndpoints } from "./endpoints-core";
import { ForbiddenError, UnauthorizedError, UpstreamError } from "./errors";
import type { ApiFetch } from "./transport";

// P0-8 viewer mode: with an X-View-As selection active, ANALYSIS reads
// (conditions/gaps/combined-rating) 403 when the share is docs-only or the
// claim OWNER's Pro lapsed. That's an access boundary to disclose — the
// composite loaders must degrade with `analysisBlocked`, never crash the page.
// (The backend never 403s these endpoints on the own-claim path, so the flag
// can't fire for an owner.)

const ME = {
  id: 2,
  email: "rep@example.com",
  activeClaim: { id: 1, status: "DRAFT", evidenceCount: 0 },
};

/** Transport where analysis reads 403 (viewer without analysis access). */
function blockedFetch(): ApiFetch {
  return (async <T,>(path: string): Promise<T> => {
    if (path.startsWith("/claim/conditions") || path.startsWith("/claim/gaps") || path.startsWith("/claim/combined-rating")) {
      throw new ForbiddenError(path);
    }
    if (path === "/auth/me") return ME as T;
    if (path === "/subscription/status") return { active: false } as T;
    return null as T;
  }) as ApiFetch;
}

describe("viewer-mode analysis reads degrade honestly (P0-8)", () => {
  it("loadHomeVM: 403 conditions/gaps → analysisBlocked, empty lists, no crash", async () => {
    const api = makeEndpoints(blockedFetch());
    const vm = await api.loadHomeVM();
    expect(vm.analysisBlocked).toBe(true);
    expect(vm.conditions).toEqual([]);
    expect(vm.steps).toEqual([]);
  });

  it("loadConditionsPage: 403 → analysisBlocked + empty conditions", async () => {
    const api = makeEndpoints(blockedFetch());
    const page = await api.loadConditionsPage();
    expect(page.analysisBlocked).toBe(true);
    expect(page.conditions).toEqual([]);
  });

  it("loadNextSteps: 403 → analysisBlocked + empty steps", async () => {
    const api = makeEndpoints(blockedFetch());
    const vm = await api.loadNextSteps();
    expect(vm.analysisBlocked).toBe(true);
    expect(vm.steps).toEqual([]);
    expect(vm.scenarios[0].combinedRating).toBe(0);
  });

  it("loadConditionDetail: 403 → null (not-found), never a crash", async () => {
    const api = makeEndpoints(blockedFetch());
    expect(await api.loadConditionDetail(5)).toBeNull();
  });

  it("owner path stays exact: accessible reads report analysisBlocked=false", async () => {
    const api = makeEndpoints((async <T,>(path: string): Promise<T> => {
      if (path === "/auth/me") return ME as T;
      if (path.startsWith("/claim/conditions")) return [] as T;
      if (path.startsWith("/claim/gaps")) return { gaps: [] } as T;
      if (path === "/subscription/status") return { active: false } as T;
      return null as T;
    }) as ApiFetch);
    expect((await api.loadHomeVM()).analysisBlocked).toBe(false);
    expect((await api.loadConditionsPage()).analysisBlocked).toBe(false);
    expect((await api.loadNextSteps()).analysisBlocked).toBe(false);
  });

  it("401s still propagate to the auth gate (never mapped to blocked)", async () => {
    const api = makeEndpoints((async <T,>(path: string): Promise<T> => {
      if (path.startsWith("/claim/conditions")) throw new UnauthorizedError();
      return null as T;
    }) as ApiFetch);
    await expect(api.probeAnalysisBlocked()).rejects.toBeInstanceOf(UnauthorizedError);
  });
});

describe("probeAnalysisBlocked (shell banner signal)", () => {
  it("true on 403, false on success", async () => {
    expect(await makeEndpoints(blockedFetch()).probeAnalysisBlocked()).toBe(true);
    const open = makeEndpoints((async <T,>(path: string): Promise<T> =>
      (path.startsWith("/claim/conditions") ? ([] as T) : (null as T))) as ApiFetch);
    expect(await open.probeAnalysisBlocked()).toBe(false);
  });

  it("an upstream outage is NOT 'blocked' — it propagates for honest handling", async () => {
    const api = makeEndpoints((async <T,>(path: string): Promise<T> => {
      if (path.startsWith("/claim/conditions")) throw new UpstreamError(503, "down");
      return null as T;
    }) as ApiFetch);
    await expect(api.probeAnalysisBlocked()).rejects.toBeInstanceOf(UpstreamError);
  });
});
