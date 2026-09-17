import { describe, it, expect } from "vitest";
import { impactChip, toPriority, toSteps, type GapRawLive } from "./gaps";

// Contract tests against the LIVE /api/claim/gaps shape (P0-1): the backend
// maps pipeline keys (title/description/how_to_get_it/rating_impact) into
// label/why/suggest/impact, humanizes type tokens, and passes through
// status/index/triadLeg/targetRating/estimatedTime/estimatedCostUsd. These
// fixtures carry realistic pipeline-authored content so a key drift on either
// side renders as empty strings here and fails CI — never silently in prod.

describe("toPriority", () => {
  it("maps known priorities and defaults unknown to low", () => {
    expect(toPriority("high")).toBe("high");
    expect(toPriority("HIGH")).toBe("high");
    expect(toPriority("medium")).toBe("medium");
    expect(toPriority("low")).toBe("low");
    expect(toPriority("whatever")).toBe("low");
    expect(toPriority(null)).toBe("low");
  });
});

/** A gap exactly as the live endpoint emits it (pipeline-authored content). */
const sleepApneaNexusGap: GapRawLive = {
  condId: 99,
  condName: "Sleep Apnea",
  type: "Nexus letter",
  label: "Missing nexus letter for sleep apnea",
  why: "Your sleep study confirms diagnosis, but no medical opinion links it to service. (VASRD: 38 CFR 4.97 DC 6847, 50% criterion: requires use of breathing assistance device)",
  suggest: "Ask your treating pulmonologist for a nexus letter that cites the in-service snoring complaints in your STRs.",
  impact: "Confirms the nexus leg and supports the 50% CPAP criterion.",
  priority: "high",
  status: "open",
  index: 0,
  triadLeg: "nexus",
  targetRating: 50,
  estimatedTime: "2–4 weeks",
  estimatedCostUsd: 800,
};

describe("toSteps", () => {
  const gaps: GapRawLive[] = [
    {
      condId: 98,
      condName: "GERD",
      type: "Presumptive documentation",
      label: "Confirm Gulf War presumptive service",
      why: "Your DD-214 shows Southwest Asia service; documenting it flags the presumption. (VASRD: 38 CFR 3.317 functional gastrointestinal disorders)",
      suggest: "Upload the DD-214 page showing your Southwest Asia deployment dates.",
      impact: "Establishes the presumptive nexus without a private opinion.",
      priority: "low",
      status: "open",
      index: 0,
      triadLeg: "nexus",
      targetRating: 30,
      estimatedTime: "same day",
      estimatedCostUsd: 0,
    },
    sleepApneaNexusGap,
    {
      condId: 99,
      condName: "Sleep Apnea",
      type: "Buddy statement",
      label: "In-service symptoms under-documented",
      why: "Only one STR entry mentions snoring; a lay statement strengthens the in-service leg. (VASRD: 38 CFR 4.97 DC 6847)",
      suggest: "Ask a former roommate to complete VA Form 21-10210 describing witnessed apnea episodes.",
      impact: "Strengthens the in-service leg from weak to moderate.",
      priority: "medium",
      status: "open",
      index: 1,
      triadLeg: "in_service",
      targetRating: 50,
      estimatedTime: "1–2 weeks",
      estimatedCostUsd: 0,
    },
    {
      condId: 102,
      condName: "Tinnitus",
      type: "C&P exam request",
      label: "No audiology diagnosis on file",
      why: "Records show ringing complaints but no audiologist diagnosis. (VASRD: 38 CFR 4.87 DC 6260, 10% recurrent tinnitus)",
      suggest: "Request a VA audiology C&P exam, or submit a private audiologist's diagnosis letter.",
      impact: "Completes the diagnosis leg and secures the 10% rating.",
      priority: "high",
      status: "open",
      index: 0,
      triadLeg: "diagnosis",
      targetRating: 10,
      estimatedTime: "30 days",
      estimatedCostUsd: 0,
    },
  ];

  it("sorts high → medium → low and is stable within a priority", () => {
    const steps = toSteps(gaps);
    expect(steps.map((s) => s.gap)).toEqual([
      "Missing nexus letter for sleep apnea",
      "No audiology diagnosis on file",
      "In-service symptoms under-documented",
      "Confirm Gulf War presumptive service",
    ]);
  });

  it("marks high-priority steps as impactStrong and maps fields", () => {
    const [top] = toSteps(gaps);
    expect(top.priority).toBe("high");
    expect(top.impactStrong).toBe(true);
    expect(top.cond).toBe("Sleep Apnea");
    expect(top.type).toBe("Nexus letter");
  });

  it("maps a pipeline-shaped gap to non-empty veteran-facing text (P0-1 contract)", () => {
    const [step] = toSteps([sleepApneaNexusGap]);
    // The four fields P0-1 found silently blank in production. Empty string
    // here means a key drifted between the endpoint and this adapter.
    expect(step.gap).not.toBe("");
    expect(step.why).not.toBe("");
    expect(step.suggest).not.toBe("");
    expect(step.impact).not.toBe("");
    expect(step.why).toContain("(VASRD:");
    expect(step.gap).toBe("Missing nexus letter for sleep apnea");
    expect(step.suggest).toMatch(/nexus letter/i);
  });

  it("passes through the live contract fields", () => {
    const [step] = toSteps([sleepApneaNexusGap]);
    expect(step.status).toBe("open");
    expect(step.gapIndex).toBe(0);
    expect(step.triadLeg).toBe("nexus");
    expect(step.targetRating).toBe(50);
    expect(step.estimatedTime).toBe("2–4 weeks");
    expect(step.estimatedCostUsd).toBe(800);
  });

  it("defaults status to open and nulls absent pass-through fields (legacy payload)", () => {
    const [step] = toSteps([
      { condId: 1, condName: "GERD", type: "Nexus", label: "legacy gap", priority: "low" },
    ]);
    expect(step.status).toBe("open");
    expect(step.gapIndex).toBeNull();
    expect(step.triadLeg).toBeNull();
    expect(step.targetRating).toBeNull();
    expect(step.estimatedTime).toBeNull();
    expect(step.estimatedCostUsd).toBeNull();
  });

  it("handles empty / nullish input", () => {
    expect(toSteps([])).toEqual([]);
    expect(toSteps(undefined)).toEqual([]);
  });
});

describe("impactChip (row chips vs sentence impacts — 2026-07-02 overflow)", () => {
  it("keeps short impacts as-is", () => {
    expect(impactChip("+20%", 30)).toBe("+20%");
    expect(impactChip("10% \u2192 30%", null)).toBe("10% \u2192 30%");
  });

  it("collapses sentence impacts to the deterministic target rating", () => {
    expect(
      impactChip("If endoscopy reveals esophageal stricture, the claim moves from 10% to 30%", 30),
    ).toBe("\u2192 30%");
  });

  it("returns null (no chip) for a sentence with no target rating", () => {
    expect(impactChip("A long sentence about what this evidence would change", null)).toBeNull();
  });

  it("empty impact: no chip without a target, deterministic chip with one", () => {
    expect(impactChip("", null)).toBeNull();
    expect(impactChip("  ", 30)).toBe("\u2192 30%");
  });
});
