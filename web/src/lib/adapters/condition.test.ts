import { describe, it, expect } from "vitest";
import { toCondition, toConfidencePct, toRating, weakestLegOf } from "./condition";
import type { ConditionResponse } from "@/lib/models/api";

describe("toConfidencePct", () => {
  it("scales 0..1 to 0..100 and rounds", () => {
    expect(toConfidencePct(0.95)).toBe(95);
    expect(toConfidencePct(0.736)).toBe(74);
    expect(toConfidencePct(1)).toBe(100);
  });
  it("treats >1 as an already-percent value and clamps", () => {
    expect(toConfidencePct(95)).toBe(95);
    expect(toConfidencePct(150)).toBe(100);
  });
  it("missing confidence is NULL, never 0% (P1-5) — incl. the primitive-double 0.0", () => {
    expect(toConfidencePct(undefined)).toBeNull();
    expect(toConfidencePct(null)).toBeNull();
    expect(toConfidencePct(NaN)).toBeNull();
    expect(toConfidencePct(0)).toBeNull(); // DTO's primitive double serializes "missing" as 0.0
    expect(toConfidencePct(-0.5)).toBeNull();
  });
});

describe("toRating (null-vs-0, P1-5)", () => {
  it("null/absent → null (not yet rated)", () => {
    expect(toRating(null)).toBeNull();
    expect(toRating(undefined)).toBeNull();
    expect(toRating(NaN)).toBeNull();
  });
  it("a true 0 passes through — a 0% grant is a real VA outcome", () => {
    expect(toRating(0)).toBe(0);
    expect(toRating(70)).toBe(70);
  });
});

const base: ConditionResponse = {
  id: 1,
  name: "PTSD",
  vasrdCode: "9411",
  bodySystem: "Mental Disorders",
  estimatedRating: 70,
  confidence: 0.95,
  triadDiagnosis: { status: "strong", evidence: ["dx note", ""] },
  triadInService: { status: "strong", evidence: ["combat"] },
  triadNexus: { status: "strong", evidence: ["DBQ link"] },
};

describe("toCondition", () => {
  it("marks ready when all three legs are strong, and maps core fields", () => {
    const c = toCondition(base);
    expect(c.ready).toBe(true);
    expect(c.rating).toBe(70);
    expect(c.confidence).toBe(95);
    expect(c.system).toBe("Mental Disorders");
    expect(c.vasrdCode).toBe("9411");
    expect(c.weakestLeg).toBeNull();
    // evidence strings filtered (empty removed)
    expect(c.legs.dx.items).toEqual(["dx note"]);
  });

  it("carries a null rating through as null and a true 0 as 0 (P1-5)", () => {
    expect(toCondition({ ...base, estimatedRating: null }).rating).toBeNull();
    expect(toCondition({ ...base, estimatedRating: undefined }).rating).toBeNull();
    expect(toCondition({ ...base, estimatedRating: 0 }).rating).toBe(0);
  });

  it("hides confidence entirely when the pipeline sent none (P1-5)", () => {
    expect(toCondition({ ...base, confidence: null }).confidence).toBeNull();
    expect(toCondition({ ...base, confidence: 0 }).confidence).toBeNull();
  });

  it("carries the rating-honesty note through (trimmed), null when absent/blank", () => {
    const note = "Estimate — needs a pulmonary function test (PFT/FEV-1) to confirm.";
    expect(toCondition({ ...base, ratingEvidenceNote: `  ${note}  ` }).ratingEvidenceNote).toBe(note);
    expect(toCondition({ ...base }).ratingEvidenceNote).toBeNull();
    expect(toCondition({ ...base, ratingEvidenceNote: null }).ratingEvidenceNote).toBeNull();
    expect(toCondition({ ...base, ratingEvidenceNote: "   " }).ratingEvidenceNote).toBeNull();
  });

  it("threads the deterministic pyramiding-group fields through (additive)", () => {
    const c = toCondition({
      ...base,
      pyramidGroup: "Mental Health (§4.130)",
      pyramidPrimary: true,
      pyramidGroupRating: 70,
    });
    expect(c.pyramidGroup).toBe("Mental Health (§4.130)");
    expect(c.pyramidPrimary).toBe(true);
    expect(c.pyramidGroupRating).toBe(70);

    const absorbed = toCondition({
      ...base,
      pyramidGroup: "Mental Health (§4.130)",
      pyramidPrimary: false,
      pyramidGroupRating: 70,
      pyramidReason: "Rated together … the strongest counts; these don't add.",
    });
    expect(absorbed.pyramidPrimary).toBe(false);
    expect(absorbed.pyramidReason).toContain("don't add");
  });

  it("defaults the pyramiding fields to ungrouped when absent (legacy/flag-off rows)", () => {
    const c = toCondition(base);
    expect(c.pyramidGroup).toBeNull();
    expect(c.pyramidPrimary).toBe(false);
    expect(c.pyramidReason).toBeNull();
    expect(c.pyramidGroupRating).toBeNull();
    // A blank group string is treated as ungrouped.
    expect(toCondition({ ...base, pyramidGroup: "  " }).pyramidGroup).toBeNull();
  });

  it("is not ready and reports the weakest leg when a leg is non-strong", () => {
    const c = toCondition({
      ...base,
      triadNexus: { status: "missing", evidence: [] },
    });
    expect(c.ready).toBe(false);
    expect(c.triad.nx).toBe("missing");
    expect(c.weakestLeg).toBe("nx");
  });

  it("picks the WEAKEST leg, not the first non-strong one (P1-5)", () => {
    // dx partial, is MISSING — the old first-non-strong logic said "dx".
    const c = toCondition({
      ...base,
      triadDiagnosis: { status: "moderate", evidence: [] },
      triadInService: { status: "missing", evidence: [] },
    });
    expect(c.weakestLeg).toBe("is");
  });

  it("breaks weakest-leg ties in dx→is→nx order", () => {
    const c = toCondition({
      ...base,
      triadInService: { status: "weak", evidence: [] },
      triadNexus: { status: "weak", evidence: [] },
    });
    expect(c.weakestLeg).toBe("is");
  });

  it("presumptive quirk: a populated basis counts even when the flag is false", () => {
    const c = toCondition({ ...base, presumptive: false, presumptiveBasis: "PACT Act" });
    expect(c.presumptive).toBe("PACT Act");
  });

  it("not presumptive when neither flag nor basis present", () => {
    const c = toCondition({ ...base, presumptive: false, presumptiveBasis: null });
    expect(c.presumptive).toBeNull();
  });

  // P1-2 defensive web half: presumption covers the nexus regardless of what
  // the pipeline scored — the nexus must never drive a "strengthen" callout.
  it("presumptive: the nexus leg is NEVER the weakest, whatever the pipeline scored", () => {
    const missingNx = { ...base, presumptiveBasis: "PACT Act", triadNexus: { status: "missing", evidence: [] } };
    expect(toCondition(missingNx).weakestLeg).toBeNull(); // dx/is strong → no callout at all

    // …and a genuinely weak dx/is leg still surfaces.
    const c = toCondition({
      ...missingNx,
      triadInService: { status: "weak", evidence: [] },
    });
    expect(c.weakestLeg).toBe("is");
  });

  it("non-presumptive weakestLegOf still ranks nexus", () => {
    expect(weakestLegOf({ dx: "strong", is: "partial", nx: "missing" }, false)).toBe("nx");
    expect(weakestLegOf({ dx: "strong", is: "partial", nx: "missing" }, true)).toBe("is");
  });

  it("falls back for missing system / vasrd", () => {
    const c = toCondition({ id: 2, name: "X" });
    expect(c.system).toBe("Unclassified");
    expect(c.vasrdCode).toBeNull();
    expect(c.rating).toBeNull();
    expect(c.confidence).toBeNull();
    // no triad → all missing
    expect(c.triad).toEqual({ dx: "missing", is: "missing", nx: "missing" });
  });
});
