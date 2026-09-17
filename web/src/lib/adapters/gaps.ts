import type { GapRaw } from "@/lib/models/api";
import type { Priority, StepVM } from "@/lib/models/vm";

const PRIORITY_RANK: Record<Priority, number> = { high: 0, medium: 1, low: 2 };

// Live `/api/claim/gaps` gap shape (P0-1): the endpoint maps the pipeline's
// persisted keys — label←title, why←description (+" (VASRD: …)" when present),
// suggest←how_to_get_it, impact←rating_impact, type humanized
// ("nexus_letter" → "Nexus letter") — and passes the lifecycle/detail fields
// through. Both shapes now live on the canonical models.
export type GapRawLive = GapRaw;
export type GapStepVM = StepVM;

export function toPriority(p: string | null | undefined): Priority {
  const s = (p ?? "").toLowerCase().trim();
  if (s === "high") return "high";
  if (s === "medium") return "medium";
  return "low";
}

export function toStep(g: GapRawLive, i: number): GapStepVM {
  const priority = toPriority(g.priority);
  return {
    id: `${g.condId ?? "c"}-${(g.type ?? "g").replace(/\s+/g, "")}-${i}`,
    condId: g.condId ?? 0,
    cond: g.condName ?? "",
    type: g.type ?? "Nexus",
    gap: g.label ?? "",
    why: g.why ?? "",
    suggest: g.suggest ?? "",
    impact: g.impact ?? "",
    impactStrong: priority === "high",
    priority,
    status: g.status ?? "open",
    gapIndex: typeof g.index === "number" ? g.index : null,
    triadLeg: g.triadLeg ?? null,
    targetRating: typeof g.targetRating === "number" ? g.targetRating : null,
    estimatedTime: g.estimatedTime ?? null,
    estimatedCostUsd: typeof g.estimatedCostUsd === "number" ? g.estimatedCostUsd : null,
  };
}

/** Map + stable-sort gaps by priority (high → medium → low). */
export function toSteps(gaps: GapRawLive[] | null | undefined): GapStepVM[] {
  const list = Array.isArray(gaps) ? gaps.map(toStep) : [];
  return list
    .map((s, i) => [s, i] as const)
    .sort(([a, ai], [b, bi]) => PRIORITY_RANK[a.priority] - PRIORITY_RANK[b.priority] || ai - bi)
    .map(([s]) => s);
}

/**
 * The single open-gap predicate (P1-6): a step the veteran marked done
 * ("resolved") or not-applicable ("dismissed") is no longer an open action.
 * Unknown/absent statuses count as open so data drift never hides work.
 */
export function isOpenStep(s: Pick<StepVM, "status">): boolean {
  return s.status !== "resolved" && s.status !== "dismissed";
}

/** Only the actionable steps — what Home, counts, and scenarios reason over. */
export function openSteps(steps: GapStepVM[]): GapStepVM[] {
  return steps.filter(isOpenStep);
}

/**
 * Compact chip text for a step's impact in ROW layouts. The pipeline's
 * `rating_impact` is a full sentence ("If endoscopy reveals esophageal
 * stricture, the claim moves from 10% to 30%") — rendered nowrap in a chip it
 * crushed the title column to one word per line and overflowed the card
 * (2026-07-02 screenshots). Rows get: short impact as-is, else the
 * deterministic "→ NN%" from target_rating, else no chip (the step modal
 * carries the full sentence).
 */
export function impactChip(
  impact: string | null | undefined,
  targetRating: number | null | undefined,
): string | null {
  const t = (impact ?? "").trim();
  if (t && t.length <= 18) return t;
  if (typeof targetRating === "number") return `\u2192 ${targetRating}%`;
  return null;
}
