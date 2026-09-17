import type {
  UserResponse,
  ConditionResponse,
  GapRaw,
  UsageResponse,
  CombinedRatingSummary,
} from "@/lib/models/api";
import type {
  CombineStepVM,
  EvidenceHealthLeg,
  HomeVM,
  RatingInputVM,
  RatingScopeVM,
  SubscriptionState,
} from "@/lib/models/vm";
import { triadLeg } from "@/lib/theme/tokens";
import { firstName as firstNameOf } from "@/lib/format";
import { toCondition } from "./condition";
import { openSteps, toSteps } from "./gaps";

export interface HomeInputs {
  me: UserResponse;
  conditions: ConditionResponse[];
  gaps: GapRaw[];
  usage: UsageResponse | null;
  /** Tri-state billing truth; "error" must not flash upsell to Pro users. */
  subState: SubscriptionState;
  /** GET /claim/combined-rating?scope=ready — server-authoritative (C1 contract). */
  ratingReady: CombinedRatingSummary | null;
  /** GET /claim/combined-rating?scope=all — server-authoritative (C1 contract). */
  ratingAll: CombinedRatingSummary | null;
  /** A scope fetch failed while conditions exist — show "unavailable", never $0. */
  estimateUnavailable?: boolean;
  /**
   * From the /claim/gaps envelope: a re-analysis activated but its gap
   * re-check hasn't finished. Defensive default false (older backends don't
   * send it).
   */
  gapAnalysisPending?: boolean;
}

/**
 * Normalize one combined-rating scope into its VM. Numbers pass through
 * verbatim (server-computed — kill-list rule); text arrays are sanitized.
 */
export function toRatingScopeVM(s: CombinedRatingSummary | null | undefined): RatingScopeVM {
  const strs = (v: unknown): string[] =>
    Array.isArray(v)
      ? v.filter((x): x is string => typeof x === "string" && x.trim().length > 0)
      : [];
  const inputs: RatingInputVM[] = Array.isArray(s?.inputs)
    ? s.inputs.map((r) => ({
        conditionId: typeof r.conditionId === "number" ? r.conditionId : null,
        name: typeof r.name === "string" ? r.name : "",
        rating: typeof r.rating === "number" ? r.rating : 0,
        counted: r.counted === true,
        reason: typeof r.reason === "string" ? r.reason : null,
        group: typeof r.group === "string" && r.group.trim().length > 0 ? r.group : null,
      }))
    : [];
  // The labeled sequential combine — numbers pass through verbatim (server-computed).
  // Absent (older backend) or a divergence fallback ⇒ [], and the UI reverts to the
  // contributor list + abstract steps. Only well-formed steps with a label survive.
  const combineSteps: CombineStepVM[] = Array.isArray(s?.combineSteps)
    ? s.combineSteps
        .filter((r) => typeof r?.label === "string" && r.label.trim().length > 0)
        .map((r) => ({
          label: r.label as string,
          rating: typeof r.rating === "number" ? r.rating : null,
          pointsAdded: typeof r.pointsAdded === "number" ? r.pointsAdded : 0,
          combinedAfter: typeof r.combinedAfter === "number" ? r.combinedAfter : 0,
          remainingAfter: typeof r.remainingAfter === "number" ? r.remainingAfter : 0,
          absorbedMembers: Array.isArray(r.absorbedMembers)
            ? r.absorbedMembers.filter((x): x is string => typeof x === "string" && x.length > 0)
            : [],
          rounding: r.rounding === true,
        }))
    : [];
  return {
    rating: typeof s?.combinedRating === "number" ? s.combinedRating : 0,
    monthly: typeof s?.monthlyEstimate === "number" ? s.monthlyEstimate : 0,
    steps: strs(s?.steps),
    combineSteps,
    notes: strs(s?.notes),
    inputs,
    excludedCount: typeof s?.excludedCount === "number" ? s.excludedCount : 0,
  };
}

/** Single pure transform that produces everything the Home screen renders. */
export function composeHomeVM({
  me,
  conditions,
  gaps,
  usage,
  subState,
  ratingReady,
  ratingAll,
  estimateUnavailable = false,
  gapAnalysisPending = false,
}: HomeInputs): HomeVM {
  const conds = (conditions ?? []).map(toCondition);
  // "Don't include in my claim" (owner-set, reversible): an excluded condition
  // stays in the FULL list — the /conditions page keeps it in a "Not filing"
  // section — but must never inflate a Home count, the evidence-health card, or
  // the pay math (the server already drops it from the combined rating). Home
  // reflects only what the veteran is actually FILING. `noConditionsFound` /
  // `isNewUser` / pipeline-pending below intentionally still look at `conds`
  // (the raw analysis output), not `filing` — an all-excluded claim is neither
  // "new" nor "no conditions found".
  const filing = conds.filter((c) => !c.excludedFromClaim);
  const notFilingCount = conds.length - filing.length;
  const ready = filing.filter((c) => c.ready);
  const needsWork = filing.filter((c) => !c.ready);
  const presumptiveCount = filing.filter((c) => c.presumptive).length;
  // Home's "Do this next" only promotes OPEN actions — a step the veteran
  // marked done/doesn't-apply must not resurface here (P1-6).
  const steps = openSteps(toSteps(gaps));

  const claim = me.activeClaim ?? null;
  const conditionCount = claim ? Number(claim.conditionCount ?? 0) : 0;
  const hasClaim = !!claim;
  const isPro = subState === "pro";

  // A failed run is terminal — an honest error card, never an eternal spinner.
  const analysisError = hasClaim && claim?.status === "ERROR";

  // A claim mid-pipeline (stage set, <100%, no conditions yet) is "analyzing",
  // NOT a new user — check it first so it doesn't get shadowed by the empty case.
  // The AI pipeline is Pro-gated, so analysis only actually RUNS for Pro users.
  // A null progress still counts as pending: the scheduler no longer writes a
  // fake percent, only the stage.
  const progress = claim?.analysisProgressPct ?? null;
  const pipelinePending =
    hasClaim &&
    !analysisError &&
    !!claim?.analysisStage &&
    (progress ?? 0) < 100 &&
    conds.length === 0;
  // Pro user with a claim genuinely mid-pipeline → show the live analyzing state.
  const isAnalyzing = isPro && pipelinePending;
  // Free user who uploaded evidence (backend stamped "extracting") but whose
  // analysis is Pro-gated and will never advance → show "upgrade to analyze",
  // never a forever-5% spinner.
  const analysisLocked = !isPro && pipelinePending;

  const documentsCount = claim ? Number(claim.evidenceCount ?? 0) : 0;
  // Analysis genuinely finished with zero conditions — a real terminal state
  // the veteran can act on, not "analyzing" and not a blank new user.
  const noConditionsFound =
    hasClaim &&
    !analysisError &&
    !pipelinePending &&
    conds.length === 0 &&
    documentsCount > 0 &&
    (claim?.status === "ANALYZED" || !!claim?.lastAnalyzedAt);

  const isNewUser =
    !hasClaim ||
    (conds.length === 0 &&
      conditionCount === 0 &&
      !isAnalyzing &&
      !analysisLocked &&
      !analysisError &&
      !noConditionsFound);

  // Arms the client-side jobs poller. Pro-gated: a free user's pipeline never
  // advances, so polling it could never complete.
  const pipelineActive =
    isPro && hasClaim && ((!!claim?.analysisStage && !analysisError) || gapAnalysisPending);

  // Evidence health reflects only the conditions being filed — an excluded
  // condition must not tip a leg to "missing" for a claim it isn't part of.
  const evidenceHealth: EvidenceHealthLeg[] = (["dx", "is", "nx"] as const).map((k) => {
    const counts = { strong: 0, partial: 0, missing: 0 };
    filing.forEach((c) => {
      counts[c.triad[k]]++;
    });
    const meta = triadLeg(k);
    return { leg: k, label: meta.label, color: meta.color, ...counts, total: filing.length };
  });

  return {
    firstName: firstNameOf(me) || "there",
    hasClaim,
    isNewUser,
    isAnalyzing,
    analysisLocked,
    analysisProgressPct: progress ?? 0,
    analysisError,
    analysisErrorMessage: analysisError ? claim?.analysisMessage?.trim() || null : null,
    noConditionsFound,
    gapAnalysisPending,
    pipelineActive,
    documentsCount,
    estimateUnavailable,
    readyScope: toRatingScopeVM(ratingReady),
    allScope: toRatingScopeVM(ratingAll),
    ratesYear: ratingAll?.ratesYear ?? ratingReady?.ratesYear ?? null,
    conditions: filing,
    notFilingCount,
    ready,
    needsWork,
    presumptiveCount,
    steps,
    topStep: steps[0] ?? null,
    evidenceHealth,
    isPro,
    subState,
    atUsageLimit: !!usage?.atLimit,
  };
}

export const money = (n: number): string =>
  "$" + Math.round(n).toLocaleString("en-US");

/**
 * One veteran-facing contributor line in the rating breakdown — either a single
 * ungrouped condition or a whole pyramiding group folded into one row. The
 * `percent` is the effective % that enters the VA combine; `absorbed` are the
 * group members that are rated together and DON'T add (shown small beneath).
 */
export interface RatingContributor {
  /** Stable react key — the group label, or the condition id/name for singletons. */
  key: string;
  /** Display label: humanized group ("Mental health") or the condition name. */
  label: string;
  /** Effective % this contributor adds to the combine. */
  percent: number;
  /** True when this row represents a folded pyramiding group (≥1 absorbed member). */
  isGroup: boolean;
  /** Absorbed member names ("MDD", "anxiety") rated together — they don't add. */
  absorbed: string[];
  /** Extra counted survivors that share this group (rare); label lists them. */
  countedNames: string[];
}

/** "mental health" → "Mental health"; leaves already-cased labels sensible. */
function titleishGroup(label: string): string {
  const t = label.trim();
  if (t.length === 0) return t;
  return t.charAt(0).toUpperCase() + t.slice(1);
}

/**
 * Fold a scope's `inputs` into the veteran-facing "what makes up your rating"
 * contributor rows. Counted conditions sharing a non-null `group` collapse into
 * one row (label = humanized group, percent = the group's highest counted %);
 * ungrouped counted conditions are their own row. Absorbed members (pyramided-in)
 * nest under their group's row as `absorbed`. `not-ready` rows are omitted — they
 * aren't part of THIS scope's rating. Order follows first appearance in `inputs`.
 */
export function ratingContributors(inputs: RatingInputVM[]): RatingContributor[] {
  const order: string[] = []; // group keys / singleton keys, in first-seen order
  const groups = new Map<
    string,
    { label: string; percent: number; counted: string[]; absorbed: string[] }
  >();
  const singles: RatingContributor[] = [];
  // Index singleton position within `order` so we can weave them with groups.
  const singleByKey = new Map<string, RatingContributor>();

  for (const row of inputs) {
    const grouped = row.group !== null && row.group.trim().length > 0;
    if (grouped) {
      const gk = `g:${row.group!.toLowerCase().trim()}`;
      let bucket = groups.get(gk);
      if (!bucket) {
        bucket = { label: titleishGroup(row.group!), percent: 0, counted: [], absorbed: [] };
        groups.set(gk, bucket);
        order.push(gk);
      }
      if (row.counted) {
        bucket.counted.push(row.name);
        if (row.rating > bucket.percent) bucket.percent = row.rating;
      } else if (typeof row.reason === "string" && row.reason.startsWith("pyramided-into:")) {
        bucket.absorbed.push(row.name);
      }
      // not-ready grouped rows: ignored (not part of this scope's rating).
      continue;
    }
    // Ungrouped: only COUNTED conditions are contributors.
    if (!row.counted) continue;
    const key = `c:${row.conditionId ?? row.name}`;
    const single: RatingContributor = {
      key,
      label: row.name,
      percent: row.rating,
      isGroup: false,
      absorbed: [],
      countedNames: [row.name],
    };
    singleByKey.set(key, single);
    singles.push(single);
    order.push(key);
  }

  const out: RatingContributor[] = [];
  for (const k of order) {
    if (k.startsWith("g:")) {
      const b = groups.get(k)!;
      // A group with no counted survivor in this scope contributes nothing to the
      // rating — skip it (its absorbed members are already explained in notes).
      if (b.counted.length === 0) continue;
      out.push({
        key: k,
        label: b.label,
        percent: b.percent,
        isGroup: b.absorbed.length > 0 || b.counted.length > 1,
        absorbed: b.absorbed,
        countedNames: b.counted,
      });
    } else {
      const s = singleByKey.get(k);
      if (s) out.push(s);
    }
  }
  return out;
}

/**
 * THE hedge line — the one consistent sentence under every headline estimate
 * (hero rating, pay card). Copy is pinned by P1-4; keep it identical everywhere
 * so the calibration reads as policy, not boilerplate.
 */
export const ESTIMATE_HEDGE =
  "Our estimate from your evidence — VA assigns the actual rating after review and exams.";
