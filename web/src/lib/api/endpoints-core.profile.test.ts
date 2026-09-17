import { describe, it, expect } from "vitest";
import { makeEndpoints } from "./endpoints-core";
import type { ApiFetch, FetchOpts } from "./transport";

// Profile-page loader contract (2026-07-03): synthetic emails never reach the
// VM, the name is displayName-or-empty (never a uid), and the service section
// is a list of periods (pinned /auth/profile additive contract).

const UID = "dB34I0uAgdXTBTL7bDq5kzrAb0M2";

function fakeFetch(routes: Record<string, unknown>): ApiFetch {
  return (async <T,>(path: string): Promise<T> =>
    (path in routes ? routes[path] : null) as T) as ApiFetch;
}

describe("loadProfilePage — synthetic email / uid hygiene", () => {
  it("nulls a synthetic @firebase.local email and leaves the name empty (never a uid)", async () => {
    const api = makeEndpoints(
      fakeFetch({
        "/auth/me": { id: 1, name: UID, email: `${UID}@firebase.local` },
        "/subscription/status": { active: false },
      }),
    );
    const vm = await api.loadProfilePage();
    expect(vm.email).toBeNull();
    expect(vm.name).toBe(""); // the view renders "Welcome", not the uid
    expect(JSON.stringify(vm)).not.toContain(UID);
  });

  it("prefers preferredName once set", async () => {
    const api = makeEndpoints(
      fakeFetch({
        "/auth/me": { id: 1, name: UID, email: `${UID}@firebase.local`, preferredName: "Griff" },
        "/subscription/status": { active: false },
      }),
    );
    const vm = await api.loadProfilePage();
    expect(vm.name).toBe("Griff");
    expect(vm.initial).toBe("G");
  });

  it("keeps a real email verbatim", async () => {
    const api = makeEndpoints(
      fakeFetch({
        "/auth/me": { id: 1, name: "Test Vet", email: "vet@example.com" },
        "/subscription/status": { active: false },
      }),
    );
    const vm = await api.loadProfilePage();
    expect(vm.email).toBe("vet@example.com");
    expect(vm.name).toBe("Test Vet");
  });

  it("tolerates the new contract's omitted-email shape (key absent)", async () => {
    const api = makeEndpoints(
      fakeFetch({
        "/auth/me": { id: 1, name: UID },
        "/subscription/status": { active: false },
      }),
    );
    const vm = await api.loadProfilePage();
    expect(vm.email).toBeNull();
    expect(vm.name).toBe("");
  });
});

describe("loadProfilePage — service periods (pinned /auth/profile contract)", () => {
  const PERIODS_PROFILE = {
    branch: "Army",
    servicePeriods: [
      {
        branch: "Army",
        component: "active",
        startDate: "2003-01-15",
        endDate: "2007-01-20",
        mos: "11B",
        rank: "SGT",
        source: "documents",
      },
      {
        branch: "Army National Guard",
        component: "guard",
        startDate: "2008-03-01",
        endDate: null,
        mos: null,
        rank: null,
        source: "documents",
      },
      {
        branch: "Army",
        component: null,
        startDate: null,
        endDate: null,
        mos: "11B",
        rank: null,
        source: "manual",
      },
    ],
  };

  it("maps periods and orders newest-first with null starts last", async () => {
    const api = makeEndpoints(
      fakeFetch({
        "/auth/me": { id: 1, email: "vet@example.com", name: "Test Vet" },
        "/auth/profile": PERIODS_PROFILE,
        "/subscription/status": { active: false },
      }),
    );
    const vm = await api.loadProfilePage();
    expect(vm.servicePeriods).toHaveLength(3);
    expect(vm.servicePeriods[0]).toMatchObject({
      branch: "Army National Guard",
      component: "guard",
      startDate: "2008-03-01",
      endDate: null,
      source: "documents",
    });
    expect(vm.servicePeriods[1]).toMatchObject({
      branch: "Army",
      component: "active",
      startDate: "2003-01-15",
      mos: "11B",
      rank: "SGT",
    });
    expect(vm.servicePeriods[2]).toMatchObject({ startDate: null, source: "manual" });
  });

  it("collapses unknown component/source values to the safe defaults and drops branchless rows", async () => {
    const api = makeEndpoints(
      fakeFetch({
        "/auth/me": { id: 1, email: "vet@example.com" },
        "/auth/profile": {
          servicePeriods: [
            { branch: "Navy", component: "flotilla", source: "psychic" },
            { branch: "  ", component: "active", source: "documents" }, // unrenderable
          ],
        },
        "/subscription/status": { active: false },
      }),
    );
    const vm = await api.loadProfilePage();
    expect(vm.servicePeriods).toEqual([
      {
        branch: "Navy",
        component: null,
        startDate: null,
        endDate: null,
        mos: null,
        rank: null,
        source: "documents",
        sources: null,
        reasoning: null,
        totalYears: null,
        clusterKey: null,
      },
    ]);
  });

  it("synthesizes ONE manual period from the legacy single-branch row when servicePeriods is absent (deploy skew)", async () => {
    const api = makeEndpoints(
      fakeFetch({
        "/auth/me": { id: 1, email: "vet@example.com" },
        "/auth/profile": {
          branch: "U.S. Army",
          serviceStart: "2003-06-01",
          serviceEnd: "2012-06-01",
          mos: "11B",
        },
        "/subscription/status": { active: false },
      }),
    );
    const vm = await api.loadProfilePage();
    expect(vm.servicePeriods).toEqual([
      {
        branch: "U.S. Army",
        component: null,
        startDate: "2003-06-01",
        endDate: "2012-06-01",
        mos: "11B",
        rank: null,
        source: "manual",
        sources: null,
        reasoning: null,
        totalYears: null,
        clusterKey: null,
      },
    ]);
  });

  it("returns an empty list (empty state) when there is no profile at all", async () => {
    const api = makeEndpoints(
      fakeFetch({
        "/auth/me": { id: 1, email: "vet@example.com" },
        "/subscription/status": { active: false },
      }),
    );
    const vm = await api.loadProfilePage();
    expect(vm.servicePeriods).toEqual([]);
    expect(vm.branch).toBeNull();
  });

  it("falls back to the newest period's branch for the header subline when the legacy field is empty", async () => {
    const api = makeEndpoints(
      fakeFetch({
        "/auth/me": { id: 1, email: "vet@example.com" },
        "/auth/profile": {
          servicePeriods: [
            { branch: "Air Force", component: "active", startDate: "2010-01-01", endDate: "2014-01-01", source: "documents" },
          ],
        },
        "/subscription/status": { active: false },
      }),
    );
    const vm = await api.loadProfilePage();
    expect(vm.branch).toBe("Air Force");
  });
});

describe("patchPreferredName — pinned PATCH /auth/me contract", () => {
  it("PATCHes { preferredName } to /auth/me and returns the ack", async () => {
    const calls: Array<{ path: string; opts?: FetchOpts }> = [];
    const spying: ApiFetch = (async <T,>(path: string, opts?: FetchOpts): Promise<T> => {
      calls.push({ path, opts });
      return { ok: true, preferredName: "Griff" } as T;
    }) as ApiFetch;
    const api = makeEndpoints(spying);
    const res = await api.patchPreferredName("Griff");
    expect(res).toEqual({ ok: true, preferredName: "Griff" });
    expect(calls).toEqual([
      { path: "/auth/me", opts: { method: "PATCH", body: { preferredName: "Griff" } } },
    ]);
    // Account-level mutation: no viewAs may ride along (the transport strips
    // it from writes anyway — this pins that the loader never sets one).
    expect(calls[0].opts?.viewAs).toBeUndefined();
  });
});

describe("loadHomeVM — nameless flag (arms the What-should-we-call-you card)", () => {
  const BASE = {
    "/claim/conditions": [],
    "/claim/gaps": { gaps: [] },
    "/usage": {},
    "/subscription/status": { active: false },
  };

  it("true for a uid-only phone sign-in (synthetic email)", async () => {
    const api = makeEndpoints(
      fakeFetch({ ...BASE, "/auth/me": { id: 1, name: UID, email: `${UID}@firebase.local` } }),
    );
    expect((await api.loadHomeVM()).nameless).toBe(true);
  });

  it("false once any usable name exists (preferredName or a real email)", async () => {
    const named = makeEndpoints(
      fakeFetch({ ...BASE, "/auth/me": { id: 1, name: UID, preferredName: "Griff" } }),
    );
    expect((await named.loadHomeVM()).nameless).toBe(false);

    const emailed = makeEndpoints(
      fakeFetch({ ...BASE, "/auth/me": { id: 1, name: UID, email: "griff@example.com" } }),
    );
    expect((await emailed.loadHomeVM()).nameless).toBe(false);
  });
});
