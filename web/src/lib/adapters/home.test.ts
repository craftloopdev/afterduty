import { describe, it, expect } from "vitest";
import { composeHomeVM, money, ratingContributors, toRatingScopeVM, type HomeInputs } from "./home";
import type { ConditionResponse, UserResponse } from "@/lib/models/api";
import type { RatingInputVM } from "@/lib/models/vm";

const strongLeg = { status: "strong", evidence: ["e"] };
const readyCond: ConditionResponse = {
  id: 1,
  name: "PTSD",
  bodySystem: "Mental",
  estimatedRating: 70,
  confidence: 0.95,
  presumptiveBasis: "PACT Act",
  triadDiagnosis: strongLeg,
  triadInService: strongLeg,
  triadNexus: strongLeg,
};
const needsWorkCond: ConditionResponse = {
  id: 2,
  name: "Sleep Apnea",
  bodySystem: "Respiratory",
  estimatedRating: 50,
  confidence: 0.55,
  triadDiagnosis: strongLeg,
  triadInService: { status: "weak", evidence: [] },
  triadNexus: { status: "missing", evidence: [] },
};

const me: UserResponse = {
  id: 10,
  email: "sean@example.com",
  name: "Sean OBryan",
  activeClaim: {
    id: 99,
    status: "ANALYZED",
    conditionCount: 2,
    analysisProgressPct: 100,
  },
};

const baseInputs: HomeInputs = {
  me,
  conditions: [readyCond, needsWorkCond],
  gaps: [
    { condId: 2, condName: "Sleep Apnea", type: "Nexus", label: "Missing nexus", impact: "Confirms +50%", priority: "high" },
    { condId: 1, condName: "GERD", type: "Nexus", label: "Confirm presumptive", impact: "Quick win", priority: "low" },
  ],
  usage: { atLimit: false, percentUsed: 30 },
  subState: "pro",
  ratingReady: {
    scope: "ready",
    combinedRating: 70,
    monthlyEstimate: 1716,
    ratesYear: 2026,
    steps: ["70% -> 70%"],
    notes: [],
  },
  ratingAll: {
    scope: "all",
    combinedRating: 90,
    monthlyEstimate: 2297,
    ratesYear: 2026,
    steps: ["70% + 50% -> 85%", "85% rounds to 90%"],
    notes: ["Bilateral factor applied."],
  },
};

describe("composeHomeVM", () => {
  it("derives partitions, presumptive count, steps, pay, and Pro state", () => {
    const vm = composeHomeVM(baseInputs);
    expect(vm.firstName).toBe("Sean");
    expect(vm.hasClaim).toBe(true);
    expect(vm.isNewUser).toBe(false);
    expect(vm.ready.map((c) => c.id)).toEqual([1]);
    expect(vm.needsWork.map((c) => c.id)).toEqual([2]);
    expect(vm.presumptiveCount).toBe(1);
    // Both scopes pass through verbatim — the web never derives a number.
    expect(vm.readyScope).toEqual({
      rating: 70,
      monthly: 1716,
      steps: ["70% -> 70%"],
      combineSteps: [],
      notes: [],
      inputs: [],
      excludedCount: 0,
    });
    expect(vm.allScope.rating).toBe(90);
    expect(vm.allScope.monthly).toBe(2297);
    expect(vm.allScope.steps).toEqual(["70% + 50% -> 85%", "85% rounds to 90%"]);
    expect(vm.allScope.notes).toEqual(["Bilateral factor applied."]);
    expect(vm.ratesYear).toBe(2026);
    expect(vm.topStep?.gap).toBe("Missing nexus");
    expect(vm.topStep?.priority).toBe("high");
    expect(vm.isPro).toBe(true);
    expect(vm.atUsageLimit).toBe(false);
  });

  it("computes per-leg evidence health across all conditions", () => {
    const vm = composeHomeVM(baseInputs);
    const dx = vm.evidenceHealth.find((l) => l.leg === "dx")!;
    const is = vm.evidenceHealth.find((l) => l.leg === "is")!;
    const nx = vm.evidenceHealth.find((l) => l.leg === "nx")!;
    expect(dx.strong).toBe(2);
    expect(is.strong).toBe(1);
    expect(is.partial).toBe(1);
    expect(nx.strong).toBe(1);
    expect(nx.missing).toBe(1);
    expect(dx.total).toBe(2);
  });

  it("drops 'not filing' conditions from every count, evidence health, and the list", () => {
    // A third condition the veteran excluded ("Don't include in my claim"). It is
    // ready + presumptive + all-legs-strong, so if it leaked into any count it
    // would move the numbers — that's exactly what must NOT happen (task #186).
    const excludedCond: ConditionResponse = {
      id: 3,
      name: "Excluded (TDIU strategy)",
      bodySystem: "Mental",
      estimatedRating: 30,
      confidence: 0.9,
      presumptiveBasis: "PACT Act",
      triadDiagnosis: strongLeg,
      triadInService: strongLeg,
      triadNexus: strongLeg,
      excludedFromClaim: true,
    };
    const vm = composeHomeVM({
      ...baseInputs,
      conditions: [readyCond, needsWorkCond, excludedCond],
    });
    // Counts reflect only the two FILED conditions, never the excluded one.
    expect(vm.ready.map((c) => c.id)).toEqual([1]); // not [1, 3]
    expect(vm.needsWork.map((c) => c.id)).toEqual([2]);
    expect(vm.presumptiveCount).toBe(1); // only PTSD — the excluded presumptive is out
    expect(vm.notFilingCount).toBe(1);
    // The Home list holds only what's being filed; the excluded row lives solely
    // in the full /conditions "Not filing" section.
    expect(vm.conditions.map((c) => c.id)).toEqual([1, 2]);
    // Evidence health totals over 2, not 3 — the excluded strong legs don't count.
    const dx = vm.evidenceHealth.find((l) => l.leg === "dx")!;
    const nx = vm.evidenceHealth.find((l) => l.leg === "nx")!;
    expect(dx.total).toBe(2);
    expect(dx.strong).toBe(2);
    expect(nx.strong).toBe(1); // only PTSD strong; the excluded strong nexus is dropped
    expect(nx.missing).toBe(1);
  });

  it("flags a brand-new user (no claim) and degrades gracefully", () => {
    const vm = composeHomeVM({
      me: { id: 1, email: "new@example.com", name: "New User", activeClaim: null },
      conditions: [],
      gaps: [],
      usage: null,
      subState: "free",
      ratingReady: null,
      ratingAll: null,
    });
    expect(vm.isNewUser).toBe(true);
    expect(vm.readyScope).toEqual({ rating: 0, monthly: 0, steps: [], combineSteps: [], notes: [], inputs: [], excludedCount: 0 });
    expect(vm.allScope).toEqual({ rating: 0, monthly: 0, steps: [], combineSteps: [], notes: [], inputs: [], excludedCount: 0 });
    expect(vm.ratesYear).toBeNull();
    expect(vm.isPro).toBe(false);
    expect(vm.topStep).toBeNull();
  });

  it("detects the analyzing state for a PRO user (stage set, progress < 100, no conditions yet)", () => {
    const vm = composeHomeVM({
      ...baseInputs,
      conditions: [],
      subState: "pro",
      me: { ...me, activeClaim: { id: 99, status: "SYNTHESIZING", analysisStage: "synthesis", analysisProgressPct: 40, conditionCount: 0 } },
    });
    expect(vm.isAnalyzing).toBe(true);
    expect(vm.analysisLocked).toBe(false); // a paying user is genuinely analyzing
    expect(vm.isNewUser).toBe(false); // analyzing must not be shadowed by the empty case
    expect(vm.analysisProgressPct).toBe(40);
  });

  // Regression: a free user uploads a DD-214, the backend stamps the claim
  // "extracting" at 5%, but the Pro-gated pipeline never advances. The old VM
  // reported isAnalyzing=true → a forever-5% spinner. It must now report
  // analysisLocked so Home shows an "upgrade to analyze" CTA instead.
  it("locks analysis (no spinner) for a FREE user whose uploaded claim is Pro-gated", () => {
    const vm = composeHomeVM({
      ...baseInputs,
      conditions: [],
      subState: "free",
      me: { ...me, activeClaim: { id: 99, status: "EXTRACTING", analysisStage: "extraction", analysisProgressPct: 5, conditionCount: 0 } },
    });
    expect(vm.analysisLocked).toBe(true);
    expect(vm.isAnalyzing).toBe(false); // free users never see the live progress spinner
    expect(vm.isNewUser).toBe(false); // they uploaded — not a blank new user
    expect(vm.isPro).toBe(false);
  });

  // Beta scheduler no longer writes a fake percent — a stage with a null pct
  // must still read as analyzing, not fall through to the new-user pitch.
  it("still detects analyzing when the backend sends a stage but no percent", () => {
    const vm = composeHomeVM({
      ...baseInputs,
      conditions: [],
      subState: "pro",
      me: { ...me, activeClaim: { id: 99, status: "SYNTHESIZING", analysisStage: "synthesis", analysisProgressPct: null, conditionCount: 0 } },
    });
    expect(vm.isAnalyzing).toBe(true);
    expect(vm.isNewUser).toBe(false);
  });

  // P0-4: a failed run is terminal. It must render as an honest error card —
  // never the eternal analyzing state, never the new-user pitch.
  it("surfaces a claim ERROR as analysisError (not analyzing, not new user)", () => {
    const vm = composeHomeVM({
      ...baseInputs,
      conditions: [],
      subState: "pro",
      me: {
        ...me,
        activeClaim: {
          id: 99,
          status: "ERROR",
          analysisStage: "synthesis", // stale stage must not win over ERROR
          analysisProgressPct: 40,
          conditionCount: 0,
          evidenceCount: 3,
          analysisMessage: "Synthesis failed after 3 attempts.",
        },
      },
    });
    expect(vm.analysisError).toBe(true);
    expect(vm.analysisErrorMessage).toBe("Synthesis failed after 3 attempts.");
    expect(vm.isAnalyzing).toBe(false);
    expect(vm.analysisLocked).toBe(false);
    expect(vm.isNewUser).toBe(false);
  });

  // P0-4: analysis that completes with zero conditions is a real terminal
  // state ("couldn't identify conditions yet"), not a spinner or a new user.
  it("flags noConditionsFound when analysis completed over evidence but found nothing", () => {
    const vm = composeHomeVM({
      ...baseInputs,
      conditions: [],
      gaps: [],
      subState: "pro",
      me: {
        ...me,
        activeClaim: {
          id: 99,
          status: "ANALYZED",
          analysisStage: null, // terminal paths clear the stage
          conditionCount: 0,
          evidenceCount: 2,
          lastAnalyzedAt: "2026-07-01T12:00:00Z",
        },
      },
    });
    expect(vm.noConditionsFound).toBe(true);
    expect(vm.isAnalyzing).toBe(false);
    expect(vm.isNewUser).toBe(false);
    expect(vm.documentsCount).toBe(2);
  });

  it("keeps a genuinely blank user (no evidence, never analyzed) as a new user", () => {
    const vm = composeHomeVM({
      ...baseInputs,
      conditions: [],
      gaps: [],
      subState: "free",
      me: { ...me, activeClaim: { id: 99, status: "DRAFT", conditionCount: 0, evidenceCount: 0 } },
    });
    expect(vm.isNewUser).toBe(true);
    expect(vm.noConditionsFound).toBe(false);
    expect(vm.analysisError).toBe(false);
  });

  // P0-5: a re-run (stage set while conditions exist) must arm the polling
  // banner for Pro users — and never for free users, whose pipeline is gated.
  it("arms pipelineActive for a Pro re-run and keeps it off for free users", () => {
    const rerun = {
      ...me,
      activeClaim: { id: 99, status: "EXTRACTING", analysisStage: "extraction", analysisProgressPct: 5, conditionCount: 2, evidenceCount: 4 },
    };
    const pro = composeHomeVM({ ...baseInputs, subState: "pro", me: rerun });
    expect(pro.pipelineActive).toBe(true);
    expect(pro.isAnalyzing).toBe(false); // conditions exist — dashboard, not takeover

    const free = composeHomeVM({ ...baseInputs, subState: "free", me: rerun });
    expect(free.pipelineActive).toBe(false);
  });

  it("carries gapAnalysisPending through (defensively defaulting to false)", () => {
    expect(composeHomeVM(baseInputs).gapAnalysisPending).toBe(false);
    const vm = composeHomeVM({ ...baseInputs, subState: "pro", gapAnalysisPending: true });
    expect(vm.gapAnalysisPending).toBe(true);
    // A pending gap re-check is active pipeline work — the banner should arm.
    expect(vm.pipelineActive).toBe(true);
  });

  it("carries the tri-state billing truth and never flips 'error' to Pro/free", () => {
    expect(composeHomeVM({ ...baseInputs, subState: "pro" }).subState).toBe("pro");
    expect(composeHomeVM({ ...baseInputs, subState: "pro" }).isPro).toBe(true);

    const free = composeHomeVM({ ...baseInputs, subState: "free" });
    expect(free.subState).toBe("free");
    expect(free.isPro).toBe(false);

    // On unknown error we must NOT report Pro (no double-charge bait), and the
    // state stays "error" so upsell affordances can treat it as neither.
    const err = composeHomeVM({ ...baseInputs, subState: "error" });
    expect(err.subState).toBe("error");
    expect(err.isPro).toBe(false);
  });

  it("flags estimateUnavailable instead of presenting a fake 0% / $0", () => {
    const vm = composeHomeVM({
      ...baseInputs,
      ratingReady: null,
      ratingAll: null,
      estimateUnavailable: true,
    });
    expect(vm.estimateUnavailable).toBe(true);
    // Underlying numbers still default to 0, but the flag tells the UI to hide them.
    expect(vm.readyScope.rating).toBe(0);
    expect(vm.allScope.monthly).toBe(0);
  });

  it("defaults estimateUnavailable to false when the fetch succeeds", () => {
    expect(composeHomeVM(baseInputs).estimateUnavailable).toBe(false);
  });

  it("sanitizes garbage steps/notes arrays defensively", () => {
    const vm = composeHomeVM({
      ...baseInputs,
      ratingAll: {
        combinedRating: 50,
        monthlyEstimate: 1102,
        steps: ["50% -> 50%", "", 3, null] as unknown as string[],
        notes: undefined,
      },
    });
    expect(vm.allScope.steps).toEqual(["50% -> 50%"]);
    expect(vm.allScope.notes).toEqual([]);
  });
});

describe("toRatingScopeVM threads inputs + excludedCount (#184)", () => {
  it("maps inputs (with group) and excludedCount from the summary", () => {
    const vm = toRatingScopeVM({
      scope: "all",
      combinedRating: 90,
      monthlyEstimate: 2297,
      excludedCount: 2,
      inputs: [
        { conditionId: 1, name: "PTSD", rating: 70, counted: true, reason: null, group: "mental health" },
        {
          conditionId: 2,
          name: "MDD",
          rating: 50,
          counted: false,
          reason: "pyramided-into:PTSD",
          group: "mental health",
        },
        { conditionId: 3, name: "GERD", rating: 30, counted: true, reason: null, group: null },
      ],
    });
    expect(vm.excludedCount).toBe(2);
    expect(vm.inputs).toHaveLength(3);
    expect(vm.inputs[0]).toEqual({
      conditionId: 1,
      name: "PTSD",
      rating: 70,
      counted: true,
      reason: null,
      group: "mental health",
    });
    expect(vm.inputs[1].group).toBe("mental health");
    expect(vm.inputs[1].reason).toBe("pyramided-into:PTSD");
    expect(vm.inputs[2].group).toBeNull();
  });

  it("defaults excludedCount to 0 and inputs to [] when absent (older backend)", () => {
    const vm = toRatingScopeVM({ combinedRating: 40, monthlyEstimate: 755 });
    expect(vm.excludedCount).toBe(0);
    expect(vm.inputs).toEqual([]);
  });

  it("null summary → zero-state scope with empty inputs", () => {
    const vm = toRatingScopeVM(null);
    expect(vm.rating).toBe(0);
    expect(vm.inputs).toEqual([]);
    expect(vm.excludedCount).toBe(0);
  });
});

describe("toRatingScopeVM threads combineSteps (#185)", () => {
  it("maps the labeled sequential combine verbatim (numbers + absorbed members)", () => {
    const vm = toRatingScopeVM({
      combinedRating: 90,
      monthlyEstimate: 2297,
      combineSteps: [
        {
          label: "Mental health",
          rating: 70,
          pointsAdded: 70,
          combinedAfter: 70,
          remainingAfter: 30,
          absorbedMembers: ["MDD", "Anxiety"],
          rounding: false,
        },
        {
          label: "Asthma",
          rating: 60,
          pointsAdded: 18,
          combinedAfter: 88,
          remainingAfter: 12,
          absorbedMembers: [],
          rounding: false,
        },
        {
          label: "Rounded to nearest 10",
          rating: null,
          pointsAdded: 0,
          combinedAfter: 90,
          remainingAfter: 12,
          absorbedMembers: [],
          rounding: true,
        },
      ],
    });
    expect(vm.combineSteps).toHaveLength(3);
    expect(vm.combineSteps[0]).toEqual({
      label: "Mental health",
      rating: 70,
      pointsAdded: 70,
      combinedAfter: 70,
      remainingAfter: 30,
      absorbedMembers: ["MDD", "Anxiety"],
      rounding: false,
    });
    // The rounding step keeps rating null.
    expect(vm.combineSteps[2].rating).toBeNull();
    expect(vm.combineSteps[2].rounding).toBe(true);
  });

  it("defaults combineSteps to [] when absent (older backend / divergence fallback)", () => {
    const vm = toRatingScopeVM({ combinedRating: 40, monthlyEstimate: 755 });
    expect(vm.combineSteps).toEqual([]);
  });

  it("drops malformed steps missing a label", () => {
    const vm = toRatingScopeVM({
      combinedRating: 70,
      combineSteps: [
        { rating: 70, combinedAfter: 70 } as never, // no label → dropped
        {
          label: "PTSD",
          rating: 70,
          pointsAdded: 70,
          combinedAfter: 70,
          remainingAfter: 30,
          absorbedMembers: [],
          rounding: false,
        },
      ],
    });
    expect(vm.combineSteps).toHaveLength(1);
    expect(vm.combineSteps[0].label).toBe("PTSD");
  });
});

describe("ratingContributors (#184)", () => {
  const mk = (o: Partial<RatingInputVM>): RatingInputVM => ({
    conditionId: 0,
    name: "",
    rating: 0,
    counted: true,
    reason: null,
    group: null,
    ...o,
  });

  it("folds counted + absorbed members of a group into ONE line with the group %", () => {
    const rows = ratingContributors([
      mk({ conditionId: 1, name: "PTSD", rating: 70, counted: true, group: "mental health" }),
      mk({
        conditionId: 2,
        name: "MDD",
        rating: 50,
        counted: false,
        reason: "pyramided-into:PTSD",
        group: "mental health",
      }),
      mk({ conditionId: 3, name: "GERD", rating: 30, counted: true, group: null }),
    ]);
    expect(rows).toHaveLength(2);
    expect(rows[0]).toMatchObject({
      label: "Mental health",
      percent: 70,
      isGroup: true,
      absorbed: ["MDD"],
    });
    expect(rows[1]).toMatchObject({ label: "GERD", percent: 30, isGroup: false, absorbed: [] });
  });

  it("omits not-ready rows and a group with no counted survivor", () => {
    const rows = ratingContributors([
      mk({ conditionId: 1, name: "PTSD", rating: 70, counted: true, group: null }),
      mk({ conditionId: 2, name: "Tinnitus", rating: 10, counted: false, reason: "not-ready", group: null }),
      // group with only an absorbed member (no counted survivor in scope) — dropped.
      mk({
        conditionId: 3,
        name: "Anxiety",
        rating: 30,
        counted: false,
        reason: "pyramided-into:PTSD",
        group: "mental health",
      }),
    ]);
    expect(rows.map((r) => r.label)).toEqual(["PTSD"]);
  });

  it("a group with multiple counted survivors keeps the highest % and lists both names", () => {
    const rows = ratingContributors([
      mk({ conditionId: 1, name: "Cond A", rating: 40, counted: true, group: "shared" }),
      mk({ conditionId: 2, name: "Cond B", rating: 60, counted: true, group: "shared" }),
    ]);
    expect(rows).toHaveLength(1);
    expect(rows[0]).toMatchObject({ label: "Shared", percent: 60, isGroup: true });
    expect(rows[0].countedNames).toEqual(["Cond A", "Cond B"]);
  });
});

describe("money", () => {
  it("formats USD with thousands separators", () => {
    expect(money(3831)).toBe("$3,831");
    expect(money(0)).toBe("$0");
    expect(money(136.4)).toBe("$136");
  });
});
