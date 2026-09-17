import { describe, it, expect } from "vitest";
import { makeEndpoints } from "./endpoints-core";
import { UnauthorizedError } from "./errors";
import type { ApiFetch } from "./transport";

// P1-16: Conditions/Steps empty states need the subscription context, so
// `loadNextSteps` carries `subState`/`documentsCount` and the new
// `loadConditionsPage` bundles the list with them. Tri-state rule throughout:
// an outage is "error", NEVER "free" (no upsell flashed at a paying user).

function fakeFetch(routes: Record<string, unknown>): ApiFetch {
  return (async <T,>(path: string): Promise<T> =>
    (path in routes ? routes[path] : null) as T) as ApiFetch;
}

const ME = {
  id: 1,
  email: "vet@example.com",
  activeClaim: { id: 9, status: "EXTRACTING", conditionCount: 0, evidenceCount: 4 },
};
const strong = { status: "strong", evidence: ["e"] };
const COND = {
  id: 5,
  name: "PTSD",
  estimatedRating: 70,
  confidence: 0.9,
  triadDiagnosis: strong,
  triadInService: strong,
  triadNexus: strong,
};

const BASE_ROUTES = {
  "/auth/me": ME,
  "/claim/conditions": [] as unknown[],
  "/claim/gaps": { gaps: [] },
  "/scenarios/calculate": { combined_rating: 0, monthly_estimate: 0 },
};

function withSub(sub: unknown) {
  return fakeFetch({ ...BASE_ROUTES, "/subscription/status": sub });
}

function failingSub(): ApiFetch {
  return (async <T,>(path: string): Promise<T> => {
    if (path === "/subscription/status") throw new Error("upstream 502");
    return (path in BASE_ROUTES ? (BASE_ROUTES as Record<string, unknown>)[path] : null) as T;
  }) as ApiFetch;
}

describe("loadNextSteps subscription context (P1-16)", () => {
  it("exposes subState 'free' + the claim's documentsCount", async () => {
    const vm = await makeEndpoints(withSub({ active: false })).loadNextSteps();
    expect(vm.subState).toBe("free");
    expect(vm.documentsCount).toBe(4);
  });

  it("exposes subState 'pro' for an active subscription", async () => {
    const vm = await makeEndpoints(withSub({ active: true })).loadNextSteps();
    expect(vm.subState).toBe("pro");
  });

  it("maps a subscription outage to 'error' — NEVER 'free' (tri-state)", async () => {
    const vm = await makeEndpoints(failingSub()).loadNextSteps();
    expect(vm.subState).toBe("error");
  });
});

describe("loadConditionsPage (P1-16)", () => {
  it("bundles conditions with subState and documentsCount", async () => {
    const api = makeEndpoints(
      fakeFetch({
        ...BASE_ROUTES,
        "/claim/conditions": [COND],
        "/subscription/status": { active: false },
      }),
    );
    const page = await api.loadConditionsPage();
    expect(page.conditions).toHaveLength(1);
    expect(page.conditions[0].name).toBe("PTSD");
    expect(page.subState).toBe("free");
    expect(page.documentsCount).toBe(4);
  });

  it("maps a subscription outage to 'error' (tri-state honest unknown)", async () => {
    const page = await makeEndpoints(failingSub()).loadConditionsPage();
    expect(page.subState).toBe("error");
  });

  it("degrades a non-auth /auth/me failure to documentsCount 0 — the page still loads", async () => {
    const failingMe: ApiFetch = (async <T,>(path: string): Promise<T> => {
      if (path === "/auth/me") throw new Error("upstream 502");
      if (path === "/subscription/status") return { active: false } as T;
      return (path in BASE_ROUTES ? (BASE_ROUTES as Record<string, unknown>)[path] : null) as T;
    }) as ApiFetch;
    const page = await makeEndpoints(failingMe).loadConditionsPage();
    expect(page.documentsCount).toBe(0);
    expect(page.subState).toBe("free");
  });

  it("propagates a 401 from /auth/me to the auth gate (not silently degraded)", async () => {
    const unauthorized: ApiFetch = (async <T,>(path: string): Promise<T> => {
      if (path === "/auth/me") throw new UnauthorizedError();
      if (path === "/subscription/status") return { active: false } as T;
      return (path in BASE_ROUTES ? (BASE_ROUTES as Record<string, unknown>)[path] : null) as T;
    }) as ApiFetch;
    await expect(makeEndpoints(unauthorized).loadConditionsPage()).rejects.toBeInstanceOf(
      UnauthorizedError,
    );
  });
});
