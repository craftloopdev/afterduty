import { describe, it, expect } from "vitest";
import { makeEndpoints } from "./endpoints-core";
import type { ApiFetch } from "./transport";

// A fake transport keyed by path. Paths not in the map resolve to null (the
// 404-as-null shape every loader already tolerates).
function fakeFetch(routes: Record<string, unknown>): ApiFetch {
  return (async <T,>(path: string): Promise<T> =>
    (path in routes ? routes[path] : null) as T) as ApiFetch;
}

const ME = {
  id: 1,
  email: "vet@example.com",
  activeClaim: { id: 9, status: "ANALYZED", conditionCount: 1, evidenceCount: 2 },
};
const PRO_SUB = { active: true };
const FREE_SUB = { active: false };
const strong = { status: "strong", evidence: ["e"] };
const READY_COND = {
  id: 5,
  name: "PTSD",
  estimatedRating: 70,
  confidence: 0.9,
  triadDiagnosis: strong,
  triadInService: strong,
  triadNexus: strong,
};
const CALC = { combined_rating: 70, monthly_estimate: 1716 };
const READY_SUMMARY = {
  scope: "ready",
  combinedRating: 70,
  monthlyEstimate: 1716,
  ratesYear: 2026,
  notes: [],
  steps: ["70% -> 70%"],
  inputs: [{ conditionId: 5, name: "PTSD", rating: 70, counted: true, reason: null }],
};
const ALL_SUMMARY = {
  scope: "all",
  combinedRating: 80,
  monthlyEstimate: 1995,
  ratesYear: 2026,
  notes: ["Anxiety is rated inside your PTSD evaluation — VA won't pay it twice."],
  steps: ["70% + 30% -> 79%", "79% rounds to 80%"],
  inputs: [
    { conditionId: 5, name: "PTSD", rating: 70, counted: true, reason: null },
    { conditionId: 6, name: "Anxiety", rating: 30, counted: false, reason: "pyramided-into:PTSD" },
  ],
};
const COMBINED_ROUTES = {
  "/claim/combined-rating?scope=ready": READY_SUMMARY,
  "/claim/combined-rating?scope=all": ALL_SUMMARY,
};

describe("loadHomeVM — dual combined-rating scopes (P1-4 / C1 contract)", () => {
  const routes = {
    "/auth/me": ME,
    "/claim/conditions": [READY_COND],
    "/claim/gaps": { gaps: [] },
    "/usage": {},
    "/subscription/status": PRO_SUB,
    ...COMBINED_ROUTES,
  };

  it("surfaces both scopes verbatim — numbers, steps, and notes come from the server", async () => {
    const api = makeEndpoints(fakeFetch(routes));
    const vm = await api.loadHomeVM();
    expect(vm.readyScope).toEqual({
      rating: 70,
      monthly: 1716,
      steps: ["70% -> 70%"],
      combineSteps: [],
      notes: [],
      inputs: [
        { conditionId: 5, name: "PTSD", rating: 70, counted: true, reason: null, group: null },
      ],
      excludedCount: 0,
    });
    expect(vm.allScope.rating).toBe(80);
    expect(vm.allScope.monthly).toBe(1995);
    expect(vm.allScope.steps).toEqual(["70% + 30% -> 79%", "79% rounds to 80%"]);
    expect(vm.allScope.notes).toEqual([
      "Anxiety is rated inside your PTSD evaluation — VA won't pay it twice.",
    ]);
    expect(vm.ratesYear).toBe(2026);
    expect(vm.estimateUnavailable).toBe(false);
  });

  it("flags estimateUnavailable when a scope fetch FAILS while conditions exist — never $0", async () => {
    const failing: ApiFetch = (async <T,>(path: string): Promise<T> => {
      if (path.startsWith("/claim/combined-rating")) throw new Error("upstream 502");
      return (path in routes ? (routes as Record<string, unknown>)[path] : null) as T;
    }) as ApiFetch;
    const vm = await makeEndpoints(failing).loadHomeVM();
    expect(vm.estimateUnavailable).toBe(true);
    expect(vm.readyScope.rating).toBe(0); // flagged, so the UI hides these zeros
  });

  it("treats a 404-null (route deploy skew) as unavailable too, when conditions exist", async () => {
    // fakeFetch resolves unknown paths to null — exactly the allow404AsNull shape.
    const { "/claim/combined-rating?scope=ready": _r, "/claim/combined-rating?scope=all": _a, ...noCombined } = routes;
    const vm = await makeEndpoints(fakeFetch(noCombined)).loadHomeVM();
    expect(vm.estimateUnavailable).toBe(true);
  });

  it("keeps an empty claim's zeros legitimate (no conditions → not 'unavailable')", async () => {
    // Per the contract an empty claim is a 200 with combinedRating 0.
    const zero = { combinedRating: 0, monthlyEstimate: 0, notes: [], steps: [], inputs: [] };
    const vm = await makeEndpoints(
      fakeFetch({
        ...routes,
        "/claim/conditions": [],
        "/claim/combined-rating?scope=ready": { ...zero, scope: "ready" },
        "/claim/combined-rating?scope=all": { ...zero, scope: "all" },
      }),
    ).loadHomeVM();
    expect(vm.estimateUnavailable).toBe(false);
    expect(vm.readyScope).toEqual({ rating: 0, monthly: 0, steps: [], combineSteps: [], notes: [], inputs: [], excludedCount: 0 });
  });
});

describe("getGaps envelope handling", () => {
  it("accepts the legacy bare-array shape (gapAnalysisPending defaults false)", async () => {
    const api = makeEndpoints(
      fakeFetch({ "/claim/gaps": [{ condId: 5, condName: "PTSD", label: "Missing nexus" }] }),
    );
    expect(await api.getGaps()).toEqual([{ condId: 5, condName: "PTSD", label: "Missing nexus" }]);
  });

  it("accepts the wrapped envelope and surfaces gapAnalysisPending on Home", async () => {
    const api = makeEndpoints(
      fakeFetch({
        "/auth/me": ME,
        "/claim/conditions": [READY_COND],
        "/claim/gaps": { gapAnalysisPending: true, gaps: [] },
        "/usage": {},
        "/subscription/status": PRO_SUB,
        "/scenarios/calculate": CALC,
      }),
    );
    const vm = await api.loadHomeVM();
    expect(vm.gapAnalysisPending).toBe(true);
    expect(vm.steps).toEqual([]);
  });

  it("accepts the snake_case envelope key defensively", async () => {
    const api = makeEndpoints(
      fakeFetch({
        "/auth/me": ME,
        "/claim/conditions": [READY_COND],
        "/claim/gaps": { gap_analysis_pending: true, gaps: [] },
        "/subscription/status": PRO_SUB,
        "/scenarios/calculate": CALC,
      }),
    );
    const steps = await api.loadNextSteps();
    expect(steps.gapAnalysisPending).toBe(true);
  });

  it("degrades a missing/garbage gaps body to an empty, not-pending envelope", async () => {
    const api = makeEndpoints(fakeFetch({ "/claim/gaps": "oops" }));
    expect(await api.getGaps()).toEqual([]);
  });
});

describe("loadNextSteps", () => {
  const routes = {
    "/auth/me": {
      ...ME,
      activeClaim: { id: 9, status: "EXTRACTING", analysisStage: "extraction", conditionCount: 1, evidenceCount: 3 },
    },
    "/claim/conditions": [READY_COND],
    "/claim/gaps": { gapAnalysisPending: false, gaps: [] },
    "/subscription/status": PRO_SUB,
    "/scenarios/calculate": CALC,
  };

  it("labels the base scenario with evidence-strength framing, not filing advice (P0-10)", async () => {
    const api = makeEndpoints(fakeFetch(routes));
    const vm = await api.loadNextSteps();
    expect(vm.scenarios[0].label).toBe("Your 1 strongest condition today");
    expect(vm.scenarios[0].label).not.toMatch(/file/i);
  });

  it("arms pipelineActive for a Pro user with an active stage", async () => {
    const api = makeEndpoints(fakeFetch(routes));
    const vm = await api.loadNextSteps();
    expect(vm.pipelineActive).toBe(true);
    expect(vm.isPro).toBe(true);
  });

  it("never arms pipelineActive for a free user (their pipeline is gated)", async () => {
    const api = makeEndpoints(fakeFetch({ ...routes, "/subscription/status": FREE_SUB }));
    const vm = await api.loadNextSteps();
    expect(vm.pipelineActive).toBe(false);
    expect(vm.isPro).toBe(false);
  });
});

describe("claim journal loaders", () => {
  const DIGEST = {
    id: 41,
    eventType: "analysis_updated",
    title: "Your analysis was updated",
    body: "Your new evidence updated 1 condition.",
    metadata: { changedConditionIds: [93], gapsClosed: 1, gapsOpened: 0 },
    isRead: false,
    createdAt: "2026-07-02T14:05:00Z",
  };

  it("loadAnalysisUpdates surfaces the latest unread digest + changed ids", async () => {
    const api = makeEndpoints(
      fakeFetch({
        "/notifications?limit=50": { notifications: [DIGEST], unreadCount: 1 },
      }),
    );
    const vm = await api.loadAnalysisUpdates();
    expect(vm.digest?.id).toBe(41);
    expect(vm.changedConditionIds).toEqual([93]);
    expect(vm.hasAny).toBe(true);
  });

  it("loadAnalysisUpdates degrades to empty on a journal outage — Home must not blank", async () => {
    const failing: ApiFetch = (async () => {
      throw new Error("upstream 502");
    }) as ApiFetch;
    const api = makeEndpoints(failing);
    const vm = await api.loadAnalysisUpdates();
    expect(vm).toEqual({ digest: null, digestIds: [], changedConditionIds: [], hasAny: false });
  });

  it("loadTimeline groups the journal by day and PROPAGATES failures (no false empty)", async () => {
    const api = makeEndpoints(
      fakeFetch({
        "/notifications?limit=200": { notifications: [DIGEST], unreadCount: 1 },
      }),
    );
    const vm = await api.loadTimeline();
    expect(vm.days).toHaveLength(1);
    expect(vm.days[0].items[0].id).toBe(41);

    const failing: ApiFetch = (async () => {
      throw new Error("upstream 502");
    }) as ApiFetch;
    await expect(makeEndpoints(failing).loadTimeline()).rejects.toThrow();
  });
});

describe("loadNextSteps — resolved/dismissed steps stay visible but stop counting (P1-6)", () => {
  const GAPS = [
    { condId: 5, condName: "PTSD", label: "Open gap", priority: "high", status: "open", index: 0 },
    { condId: 5, condName: "PTSD", label: "Done gap", priority: "high", status: "resolved", index: 1 },
    { condId: 5, condName: "PTSD", label: "N/A gap", priority: "medium", status: "dismissed", index: 2 },
  ];

  it("keeps all steps for the panel but counts only open ones", async () => {
    const api = makeEndpoints(
      fakeFetch({
        "/auth/me": ME,
        "/claim/conditions": [READY_COND],
        "/claim/gaps": { gaps: GAPS },
        "/subscription/status": PRO_SUB,
        "/scenarios/calculate": CALC,
      }),
    );
    const vm = await api.loadNextSteps();
    expect(vm.steps).toHaveLength(3); // the Done/dismissed section still renders them
    expect(vm.highCount).toBe(1); // …but only the open high counts
    expect(vm.medCount).toBe(0);
  });
});

describe("loadDocuments free-tier honesty (P0-3)", () => {
  const DOCS = [
    { id: 1, sourceType: "upload", filename: "dd214.pdf", processingStatus: "processed" },
    { id: 2, sourceType: "upload", filename: "str.pdf", processingStatus: "queued" },
  ];

  it("shows Pro users the real pipeline statuses and carries subState=pro", async () => {
    const api = makeEndpoints(
      fakeFetch({ "/claim/evidence": DOCS, "/subscription/status": PRO_SUB }),
    );
    const { docs, subState } = await api.loadDocuments();
    expect(docs[0].statusLabel).toBe("Processed");
    expect(docs[1].statusLabel).toBe("Uploaded");
    // P2-1: the page needs subState for the "Describe to AI" Pro affordance.
    expect(subState).toBe("pro");
  });

  it("shows free users 'Stored — AI analysis is a Pro feature', never a false Processed", async () => {
    const api = makeEndpoints(
      fakeFetch({ "/claim/evidence": DOCS, "/subscription/status": FREE_SUB }),
    );
    const { docs, subState } = await api.loadDocuments();
    expect(docs[0].statusLabel).toBe("Stored — AI analysis is a Pro feature");
    expect(docs[1].statusLabel).toBe("Stored — AI analysis is a Pro feature");
    expect(docs.every((d) => !d.processed)).toBe(true);
    expect(subState).toBe("free");
  });

  it("does NOT apply the free framing on a subscription outage (tri-state)", async () => {
    // /subscription/status absent → fake transport returns null for the try
    // path… simulate a hard failure instead: an ApiFetch that throws.
    const failingSub: ApiFetch = (async <T,>(path: string): Promise<T> => {
      if (path === "/subscription/status") throw new Error("upstream 502");
      if (path === "/claim/evidence") return DOCS as T;
      return null as T;
    }) as ApiFetch;
    const api = makeEndpoints(failingSub);
    const { docs, subState } = await api.loadDocuments();
    expect(docs[0].statusLabel).toBe("Processed"); // unknown ≠ free
    expect(subState).toBe("error"); // and the page must NOT show the Pro badge
  });
});
