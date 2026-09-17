import type { ConditionResponse, TriadElementRaw } from "@/lib/models/api";
import type { CondVM, LegVM, TriadVM } from "@/lib/models/vm";
import type { TriadLevel } from "@/lib/theme/tokens";
import { toTriadLevel } from "./triad";

function toLeg(raw: TriadElementRaw | null | undefined): LegVM {
  const level = toTriadLevel(raw?.status);
  const items = Array.isArray(raw?.evidence)
    ? raw!.evidence.filter((x): x is string => typeof x === "string" && x.trim().length > 0)
    : [];
  return { level, items };
}

/**
 * Backend confidence is 0..1; older paths sometimes send a percent. Clamp 0..100.
 * Returns NULL for anything that isn't a real confidence — absent, null, NaN,
 * and 0/negative (the DTO's primitive `double` serializes "missing" as 0.0).
 * A missing confidence renders NOTHING — never an alarming "0% confidence" (P1-5).
 */
export function toConfidencePct(c: number | null | undefined): number | null {
  if (typeof c !== "number" || !Number.isFinite(c) || c <= 0) return null;
  const pct = c <= 1 ? c * 100 : c;
  return Math.min(100, Math.round(pct));
}

/**
 * Estimated rating: null/absent/non-finite → null ("Not yet rated"). A true 0
 * passes through — a 0% grant is a REAL VA outcome (service connection), not
 * "not ratable" (P1-5).
 */
export function toRating(r: number | null | undefined): number | null {
  return typeof r === "number" && Number.isFinite(r) ? r : null;
}

/** Weaker sorts first: missing < partial < strong. */
const LEG_RANK: Record<TriadLevel, number> = { missing: 0, partial: 1, strong: 2 };

/**
 * Pick the WEAKEST leg (not the first non-strong one — P1-5); ties break in
 * dx→is→nx order. A presumptive condition's nexus leg is excluded outright:
 * presumption covers the nexus regardless of what the pipeline scored, so it
 * must never drive a "strengthen this" callout (P1-2, defensive web half).
 */
export function weakestLegOf(triad: TriadVM, presumptive: boolean): "dx" | "is" | "nx" | null {
  const legs: ReadonlyArray<"dx" | "is" | "nx"> = presumptive ? ["dx", "is"] : ["dx", "is", "nx"];
  let weakest: "dx" | "is" | "nx" | null = null;
  let rank = LEG_RANK.strong; // "all strong" → null
  for (const k of legs) {
    if (LEG_RANK[triad[k]] < rank) {
      rank = LEG_RANK[triad[k]];
      weakest = k;
    }
  }
  return weakest;
}

export function toCondition(r: ConditionResponse): CondVM {
  const dx = toLeg(r.triadDiagnosis);
  const is = toLeg(r.triadInService);
  const nx = toLeg(r.triadNexus);
  const triad: TriadVM = { dx: dx.level, is: is.level, nx: nx.level };
  const ready = dx.level === "strong" && is.level === "strong" && nx.level === "strong";

  // Presumptive quirk: a populated presumptiveBasis counts as presumptive even
  // if the boolean flag is false (real prod data carries the basis without the
  // flag for some PACT-Act conditions).
  const basis = r.presumptiveBasis?.trim() || "";
  const flagged = r.presumptive ?? r.isPresumptive ?? false;
  const presumptive = flagged || basis.length > 0 ? basis || "Presumptive" : null;

  return {
    id: r.id,
    name: r.name,
    fullName: r.name,
    system: r.bodySystem?.trim() || "Unclassified",
    vasrdCode: r.vasrdCode?.trim() || null,
    rating: toRating(r.estimatedRating),
    confidence: toConfidencePct(r.confidence),
    presumptive,
    triad,
    ready,
    rationale: r.ratingRationale?.trim() || null,
    ratingEvidenceNote: r.ratingEvidenceNote?.trim() || null,
    // Pyramiding grouped view (deterministic backend fields). A blank group is treated
    // as ungrouped; pyramidPrimary defaults false; the effective rating is null unless a
    // real number arrived (a true 0 is meaningless for a group so null is fine here).
    pyramidGroup: r.pyramidGroup?.trim() || null,
    pyramidPrimary: r.pyramidPrimary === true,
    pyramidReason: r.pyramidReason?.trim() || null,
    pyramidGroupRating: toRating(r.pyramidGroupRating),
    // "Don't include in my claim" (owner-set, reversible). Only an explicit true
    // excludes; null/absent (legacy rows) reads as included.
    excludedFromClaim: r.excludedFromClaim === true,
    legs: { dx, is, nx },
    weakestLeg: weakestLegOf(triad, presumptive != null),
  };
}
