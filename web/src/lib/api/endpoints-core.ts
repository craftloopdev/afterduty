// The endpoint loaders, parameterized on the injected `ApiFetch` transport
// (capacitor-ios-spec §A.2). ALL loader bodies live here, lifted verbatim from
// the old `endpoints.ts`. This module is build-target-agnostic and client-safe:
//   - NO `server-only` (would break the native export build)
//   - NO React `cache` import (server-only API — §A.2 critical detail)
// The web entry (`endpoints.ts`) calls `makeEndpoints(serverFetch)` and re-applies
// `cache()` to the per-render-dedupe reads; the native entry will call
// `makeEndpoints(directFetch)`.

import type { ApiFetch } from "./transport";
import { ForbiddenError, SubscriptionRequiredError, UnauthorizedError } from "./errors";
import { composeHomeVM } from "@/lib/adapters/home";
import { toCondition } from "@/lib/adapters/condition";
import { openSteps, toSteps } from "@/lib/adapters/gaps";
import {
  EMPTY_ANALYSIS_UPDATES,
  groupNotificationsByDay,
  toAnalysisUpdates,
  toNotificationsEnvelope,
  type AnalysisUpdatesVM,
  type NotificationsEnvelope,
  type TimelineDayVM,
} from "@/lib/notifications";
import { toDoc } from "@/lib/adapters/evidence";
import { toShare } from "@/lib/adapters/share";
import { toMessage } from "@/lib/adapters/message";
import { displayName, isSyntheticEmail } from "@/lib/format";
import type {
  CondVM,
  ConditionsPageData,
  DocVM,
  HomeVM,
  MessageVM,
  NextStepsVM,
  PlanVM,
  ProfileVM,
  ScenarioVM,
  ServicePeriodVM,
  ServiceSourceVM,
  ShareVM,
  StepVM,
  SubscriptionState,
  SubscriptionVM,
  UsageBreakdownVM,
} from "@/lib/models/vm";
import type {
  CombinedRatingResult,
  CombinedRatingSummary,
  ConditionResponse,
  EvidenceResponse,
  GapRaw,
  MessageResponse,
  ServicePeriodRaw,
  ServiceProfileResponse,
  ShareDto,
  SubscriptionFeature,
  SubscriptionPlan,
  SubscriptionStatus,
  UsageResponse,
  UserResponse,
} from "@/lib/models/api";

/**
 * Tri-state subscription read. A backend OUTAGE must never look like "free" —
 * showing a paying user the sales paywall risks a double charge. So we
 * distinguish three cases:
 *   - `{ state: "pro" }`  active subscription
 *   - `{ state: "free" }` authenticated, no active subscription (incl. 404)
 *   - `{ state: "error" }` upstream/network failure — honest unknown
 * A 401 is rethrown so the layout's auth gate handles it (it is not "free").
 */
export type SubscriptionResult =
  | { state: "pro" | "free"; status: SubscriptionStatus | null }
  | { state: "error"; status: null };

/**
 * Documents page payload: the evidence grid plus the tri-state `subState`. The
 * subscription is carried alongside the docs so the page's "Describe what you
 * remember → Ask AI" card can show the honest Pro affordance for a CONFIRMED
 * free user (never on "error") — the same rule the dissolved AddModal used.
 */
export interface DocumentsData {
  docs: DocVM[];
  subState: SubscriptionState;
}

/**
 * Normalized read of `GET /claim/gaps`. The endpoint historically returned a
 * bare array; it now wraps in `{ gaps, gapAnalysisPending }` where
 * `gapAnalysisPending=true` means a re-analysis activated before its gap
 * re-check finished (conditions with no gap data yet) — the UI must render
 * "re-checking" instead of a false "all caught up". Both shapes (and both key
 * styles) are accepted so a web/backend deploy skew never blanks the page.
 */
export interface GapsEnvelope {
  gaps: GapRaw[];
  gapAnalysisPending: boolean;
}

function toGapsEnvelope(raw: unknown): GapsEnvelope {
  if (Array.isArray(raw)) return { gaps: raw as GapRaw[], gapAnalysisPending: false };
  if (raw && typeof raw === "object") {
    const o = raw as { gaps?: unknown; gapAnalysisPending?: unknown; gap_analysis_pending?: unknown };
    return {
      gaps: Array.isArray(o.gaps) ? (o.gaps as GapRaw[]) : [],
      gapAnalysisPending: o.gapAnalysisPending === true || o.gap_analysis_pending === true,
    };
  }
  return { gaps: [], gapAnalysisPending: false };
}

// Graduated into `models/vm.ts`; re-exported here so existing importers of the
// loader seam keep working.
export type { ConditionsPageData } from "@/lib/models/vm";

const DEFAULT_PLANS: PlanVM[] = [
  { tier: "monthly", price: 11.99, period: "month", label: "Monthly" },
  { tier: "annual", price: 119.99, period: "year", label: "Annual" },
];
const DEFAULT_FEATURES = [
  { key: "extract", name: "AI evidence extraction", desc: "Pulls every relevant fact from your records." },
  { key: "synthesis", name: "Condition synthesis", desc: "Builds your conditions and scores each on the VA triad." },
  { key: "gaps", name: "Gap analysis", desc: "Finds the highest-value evidence to strengthen your claim." },
];

/** Identity wrapper — the default (native/core) per-render-dedupe strategy. */
type CacheWrap = <F extends (...args: never[]) => unknown>(fn: F) => F;
const identityCache: CacheWrap = (fn) => fn;

/**
 * Normalize the /auth/profile service periods (pinned contract) into VMs —
 * deterministic mapping only, no derivation. Defensive throughout: a period
 * without a branch isn't renderable and is dropped; an unknown component or
 * source collapses to the safe value. When the backend predates the field
 * (absent/empty), the legacy single-branch ServiceProfile row is synthesized
 * into ONE manual period so today's data keeps rendering in the new UI.
 * Ordered newest-first (null start sorts last) regardless of upstream order.
 */
function toServicePeriods(profile: ServiceProfileResponse | null): ServicePeriodVM[] {
  const cleanDate = (d?: string | null): string | null =>
    typeof d === "string" && d.trim() ? d.trim() : null;
  const cleanStr = (s?: string | null): string | null => s?.trim() || null;

  const mapped: ServicePeriodVM[] = (profile?.servicePeriods ?? []).flatMap(
    (p: ServicePeriodRaw) => {
      const branch = cleanStr(p.branch);
      if (!branch) return [];
      // Reconciliation receipts (2026-07-05): map the contributing raw records
      // when the backend sent them. Absent/empty ⇒ null (unreconciled data), so
      // the Service History drill-down degrades to "reconciled from your
      // documents" rather than an empty facts list.
      const sources: ServiceSourceVM[] | null =
        Array.isArray(p.sources) && p.sources.length
          ? p.sources.map((s) => ({
              evidenceId: typeof s.evidenceId === "number" ? s.evidenceId : null,
              docType: cleanStr(s.docType),
              authorityRank: typeof s.authorityRank === "number" ? s.authorityRank : null,
              rawBranch: cleanStr(s.rawBranch),
              rawStart: cleanDate(s.rawStart),
              rawEnd: cleanDate(s.rawEnd),
              rawMos: cleanStr(s.rawMos),
              rawRank: cleanStr(s.rawRank),
            }))
          : null;
      return [
        {
          branch,
          component:
            p.component === "active" || p.component === "guard" || p.component === "reserve"
              ? p.component
              : null,
          startDate: cleanDate(p.startDate),
          endDate: cleanDate(p.endDate),
          mos: cleanStr(p.mos),
          rank: cleanStr(p.rank),
          source: p.source === "manual" ? "manual" : "documents",
          sources,
          reasoning: cleanStr(p.reasoning),
          totalYears: typeof p.totalYears === "number" ? p.totalYears : null,
          clusterKey: cleanStr(p.clusterKey),
        },
      ];
    },
  );

  // Deploy-skew fallback: an older backend without servicePeriods still has
  // the manual single-branch row — surface it as one manual period.
  const legacyBranch = cleanStr(profile?.branch);
  if (!mapped.length && legacyBranch) {
    mapped.push({
      branch: legacyBranch,
      component: null,
      startDate: cleanDate(profile?.serviceStart),
      endDate: cleanDate(profile?.serviceEnd),
      mos: cleanStr(profile?.mos),
      rank: null,
      source: "manual",
      sources: null,
      reasoning: null,
      totalYears: null,
      clusterKey: null,
    });
  }

  return mapped.sort((a, b) => {
    if (a.startDate && b.startDate) return b.startDate.localeCompare(a.startDate);
    if (a.startDate) return -1;
    if (b.startDate) return 1;
    return 0;
  });
}

/**
 * Builds the full loader surface against an injected transport. The web and
 * native entries differ only in which `apiFetch` they pass and the `cacheWrap`
 * strategy: web passes React `cache` so getMe/getConditions/getGaps dedupe
 * across the layout-and-page reads within a single render (the cookie BFF
 * issued each upstream once before this refactor — preserved here); native uses
 * the identity default (each screen mounts one loader, no cross-render dedupe
 * needed — §A.2). `cache` MUST be injected by the caller, never imported here.
 */
export function makeEndpoints(apiFetch: ApiFetch, cacheWrap: CacheWrap = identityCache) {
  const getMe = cacheWrap((): Promise<UserResponse> => apiFetch<UserResponse>("/auth/me"));

  const getConditions = cacheWrap(
    async (): Promise<ConditionResponse[]> =>
      (await apiFetch<ConditionResponse[]>("/claim/conditions", { allow404AsNull: true })) ?? [],
  );

  const getGapsEnvelope = cacheWrap(
    async (): Promise<GapsEnvelope> =>
      toGapsEnvelope(await apiFetch<unknown>("/claim/gaps", { allow404AsNull: true })),
  );

  const getGaps = async (): Promise<GapRaw[]> => (await getGapsEnvelope()).gaps;

  /**
   * Viewer-mode read guard (P0-8). When an X-View-As selection is active,
   * analysis reads (conditions/gaps/combined-rating) 403 for a docs-only share
   * or when the claim OWNER's Pro lapsed — that is an access boundary to
   * disclose honestly, not a crash. Owner reads never 403 these endpoints (the
   * backend's own-claim path skips scope checks entirely), so mapping
   * Forbidden/402 → `{ blocked: true }` can never mask an owner-side error.
   * 401 and upstream failures propagate unchanged.
   */
  async function analysisRead<T>(read: () => Promise<T>): Promise<{ value: T | null; blocked: boolean }> {
    try {
      return { value: await read(), blocked: false };
    } catch (e) {
      if (e instanceof ForbiddenError || e instanceof SubscriptionRequiredError) {
        return { value: null, blocked: true };
      }
      throw e;
    }
  }

  /**
   * True when analysis reads on the CURRENT target claim are blocked (403/402).
   * The shells use this to drive the viewer banner's owner-Pro dependency
   * notice. Deduped with the pages' own conditions read wherever `cacheWrap`
   * is React `cache` (web), so it costs no extra upstream call.
   */
  async function probeAnalysisBlocked(): Promise<boolean> {
    return (await analysisRead(getConditions)).blocked;
  }

  /**
   * The claim journal (GET /notifications — deterministic diff-at-flip rows).
   * `limit` is part of the cache key, so Home's and Timeline's reads dedupe
   * independently. Throws on failure — each consumer decides whether the
   * journal is supplementary (Home degrades) or the content itself (Timeline
   * surfaces the error; an empty timeline must never be a lie).
   */
  const getNotifications = cacheWrap(
    async (limit: number): Promise<NotificationsEnvelope> =>
      toNotificationsEnvelope(
        await apiFetch<unknown>(`/notifications?limit=${limit}`, { allow404AsNull: true }),
      ),
  );

  /**
   * Unread-journal view for Home (what-changed digest card) + Conditions
   * ("Updated" pills). The journal is supplementary on those screens, so any
   * non-auth failure degrades to the empty VM — it must never blank Home.
   */
  async function loadAnalysisUpdates(): Promise<AnalysisUpdatesVM> {
    try {
      return toAnalysisUpdates(await getNotifications(50));
    } catch (e) {
      if (e instanceof UnauthorizedError) throw e;
      return EMPTY_ANALYSIS_UPDATES;
    }
  }

  /** The full journal for /timeline, grouped by day. Failures propagate. */
  async function loadTimeline(): Promise<{ days: TimelineDayVM[]; unreadCount: number }> {
    const envelope = await getNotifications(200);
    return { days: groupNotificationsByDay(envelope.notifications), unreadCount: envelope.unreadCount };
  }

  async function getUsage(): Promise<UsageResponse | null> {
    try {
      return await apiFetch<UsageResponse>("/usage");
    } catch (e) {
      // At the usage cap the endpoint may 402 — degrade, don't blank the page.
      if (e instanceof SubscriptionRequiredError) return { atLimit: true };
      return null;
    }
  }

  async function getSubscriptionResult(): Promise<SubscriptionResult> {
    let status: SubscriptionStatus | null;
    try {
      status = await apiFetch<SubscriptionStatus>("/subscription/status", {
        allow404AsNull: true,
      });
    } catch (e) {
      // Session expired: let the layout's auth gate redirect, don't mislabel.
      if (e instanceof UnauthorizedError) throw e;
      // 402 / upstream 5xx / network → honest unknown.
      return { state: "error", status: null };
    }
    return { state: status?.active ? "pro" : "free", status };
  }

  /**
   * Convenience read used where only the raw status (or null) is needed. An
   * outage collapses to `null` here, so prefer `getSubscriptionResult()` wherever
   * the UI must distinguish "free" from "unknown".
   */
  async function getSubscription(): Promise<SubscriptionStatus | null> {
    try {
      return await apiFetch<SubscriptionStatus>("/subscription/status", {
        allow404AsNull: true,
      });
    } catch {
      return null;
    }
  }

  /** Server-authoritative combined rating + monthly pay for a set of ratings. */
  function calculateCombined(ratings: number[]): Promise<CombinedRatingResult> {
    return apiFetch<CombinedRatingResult>("/scenarios/calculate", {
      method: "POST",
      body: ratings,
    });
  }

  /**
   * GET /claim/combined-rating?scope=all|ready (pinned C1 contract) — the ONLY
   * source of the hero/pay-card numbers. All math (pyramiding plan, bilateral,
   * VA rounding, dollars) is server-side. An empty claim is a 200 with
   * combinedRating 0; a thrown fetch or a 404 (route deploy skew) resolves to
   * null so the caller renders "estimate unavailable", never $0. 401 propagates
   * to the auth gate.
   */
  const getCombinedRating = cacheWrap(
    (scope: "all" | "ready"): Promise<CombinedRatingSummary | null> =>
      apiFetch<CombinedRatingSummary>(`/claim/combined-rating?scope=${scope}`, {
        allow404AsNull: true,
      }),
  );

  async function getCombinedRatingOrNull(
    scope: "all" | "ready",
  ): Promise<CombinedRatingSummary | null> {
    try {
      return await getCombinedRating(scope);
    } catch (e) {
      if (e instanceof UnauthorizedError) throw e;
      return null;
    }
  }

  /** Orchestrates all Home reads (parallel) and composes the view-model. */
  async function loadHomeVM(): Promise<HomeVM> {
    const [me, condRead, gapsRead, usage, sub, ratingReady, ratingAll] = await Promise.all([
      getMe(),
      analysisRead(getConditions),
      analysisRead(getGapsEnvelope),
      getUsage(),
      getSubscriptionResult(),
      getCombinedRatingOrNull("ready"),
      getCombinedRatingOrNull("all"),
    ]);
    const conditions = condRead.value ?? [];
    const gapsEnvelope = gapsRead.value ?? { gaps: [], gapAnalysisPending: false };
    const gaps = gapsEnvelope.gaps;

    // Unavailable only when there ARE conditions but a scope read failed —
    // a brand-new/empty claim's zeros are legitimate (and Home doesn't render
    // the hero for it anyway).
    const estimateUnavailable =
      conditions.length > 0 && (ratingReady === null || ratingAll === null);

    return {
      ...composeHomeVM({
        me,
        conditions,
        gaps,
        usage,
        subState: sub.state,
        ratingReady,
        ratingAll,
        estimateUnavailable,
        gapAnalysisPending: gapsEnvelope.gapAnalysisPending,
      }),
      // Viewer mode (P0-8): the viewed share doesn't grant analysis (or the
      // owner's Pro lapsed) — Home must disclose, not render a fake-empty claim.
      analysisBlocked: condRead.blocked || gapsRead.blocked,
      // No usable display name (uid-only phone sign-in) — arms Home's
      // dismissible "What should we call you?" card. `me` is always the
      // signed-in account (never the viewed share's owner).
      nameless: displayName(me) === "",
    };
  }

  /** All conditions as view-models, for the Conditions list. */
  async function loadConditions(): Promise<CondVM[]> {
    return (await getConditions()).map(toCondition);
  }

  /**
   * Conditions page read WITH the subscription context its empty state needs
   * (P1-16): a FREE user who already uploaded documents must see "your
   * documents are stored — analysis is Pro", never the "Add your evidence"
   * dead loop. Tri-state rule: `subState === "error"` is an honest unknown and
   * must NEVER select the free copy. `getMe` is supplementary here (it only
   * feeds `documentsCount`), so a non-auth failure degrades to 0 rather than
   * blanking the page; a 401 still propagates to the auth gate.
   */
  async function loadConditionsPage(): Promise<ConditionsPageData> {
    const [condRead, sub, me] = await Promise.all([
      analysisRead(getConditions),
      getSubscriptionResult(),
      getMe().catch((e) => {
        if (e instanceof UnauthorizedError) throw e;
        return null;
      }),
    ]);
    return {
      conditions: (condRead.value ?? []).map(toCondition),
      subState: sub.state,
      documentsCount: me?.activeClaim ? Number(me.activeClaim.evidenceCount ?? 0) : 0,
      analysisBlocked: condRead.blocked,
    };
  }

  /** One condition + its highest-priority related gap, for the detail page. */
  async function loadConditionDetail(
    id: number,
  ): Promise<{ cond: CondVM; relatedStep: StepVM | null } | null> {
    // Viewer mode: an analysis-blocked share resolves to "not found" (the
    // viewer shouldn't hold condition links it can't open), never a crash.
    const [condRead, gapsRead] = await Promise.all([
      analysisRead(getConditions),
      analysisRead(getGaps),
    ]);
    const conditions = condRead.value ?? [];
    const gaps = gapsRead.value ?? [];
    const raw = conditions.find((c) => c.id === id);
    if (!raw) return null;
    return {
      cond: toCondition(raw),
      // Only an OPEN gap is a "next step" — resolved/dismissed don't resurface.
      relatedStep: openSteps(toSteps(gaps.filter((g) => g.condId === id)))[0] ?? null,
    };
  }

  /** Subscription state + plans/features for the paywall. */
  async function loadUsageBreakdown(): Promise<UsageBreakdownVM | null> {
    // Supplementary — an outage/402 must never blank the Upgrade page. All
    // dollars are server-computed cents; the view only divides by 100.
    try {
      return await apiFetch<UsageBreakdownVM>("/usage/breakdown");
    } catch {
      return null;
    }
  }

  async function loadSubscription(): Promise<SubscriptionVM> {
    const result = await getSubscriptionResult();
    const s = result.status;
    // The backend emits `plans` as an OBJECT keyed by tier ({monthly:{…},annual:{…}}),
    // not an array — so `?? []` (which only guards null/undefined) is not enough and a
    // bare `.map` would throw, tripping the route error boundary. Coerce non-arrays to
    // [] so we fall through to DEFAULT_PLANS/DEFAULT_FEATURES rather than crash.
    const asArr = <T,>(v: unknown): T[] => (Array.isArray(v) ? (v as T[]) : []);
    const plans: PlanVM[] = asArr<SubscriptionPlan>(s?.plans).map((p) => ({
      tier: p.tier,
      price: p.price,
      period: p.billing_period ?? "month",
      label: p.billing_period === "year" ? "Annual" : "Monthly",
    }));
    const features = asArr<SubscriptionFeature>(s?.features).map((f) => ({
      key: f.key,
      name: f.name,
      desc: f.desc ?? "",
    }));
    return {
      state: result.state,
      active: result.state === "pro",
      currentTier: s?.current_tier ?? null,
      expiresAt: s?.expires_at ?? null,
      plans: plans.length ? plans : DEFAULT_PLANS,
      features: features.length ? features : DEFAULT_FEATURES,
    };
  }

  async function getProfile(): Promise<ServiceProfileResponse | null> {
    return apiFetch<ServiceProfileResponse>("/auth/profile", { allow404AsNull: true });
  }

  /** Combined profile view-model (account + service summary + Pro state). */
  async function loadProfilePage(): Promise<ProfileVM> {
    const [me, profile, sub] = await Promise.all([getMe(), getProfile(), getSubscriptionResult()]);
    // The display name — "" when nothing usable exists (uid-only phone
    // sign-in). NEVER fall back to `me.email` here: a synthetic
    // "<uid>@firebase.local" is exactly how the raw uid reached the header.
    const name = displayName(me);
    // Real email only — the synthetic placeholder must never reach a client
    // even if an older backend still sends it (the new contract omits it).
    const email = me.email && !isSyntheticEmail(me.email) ? me.email : null;
    const servicePeriods = toServicePeriods(profile);
    const year = (d?: string | null) => {
      if (!d) return null;
      const y = new Date(d).getFullYear();
      return Number.isFinite(y) ? y : null;
    };
    const start = year(profile?.serviceStart);
    const end = year(profile?.serviceEnd);
    const service = start || end ? `${start ?? "—"} – ${end ?? "Present"}` : null;
    // Billing rail (P1-21) — defensive: an absent/unknown `source` (older
    // backend, deploy skew) is null, never a guessed source. "portal" (web
    // billing) is named without the vendor literal — §H.4 native release guard.
    const src = sub.status?.source;
    const billingSource =
      src === "apple" || src === "google" ? src : typeof src === "string" && src ? "portal" : null;
    return {
      name,
      email,
      initial: (name.trim()[0] ?? "U").toUpperCase(),
      branch: profile?.branch?.trim() || servicePeriods[0]?.branch || null,
      service,
      mos: profile?.mos?.trim() || null,
      servicePeriods,
      isPro: sub.state === "pro",
      subState: sub.state,
      planTier: sub.status?.current_tier ?? null,
      planExpiresAt: sub.status?.expires_at ?? null,
      billingSource,
      role: me.role ?? null,
    };
  }

  /**
   * PATCH /auth/me { preferredName } (pinned contract): trimmed 1..60 chars,
   * 200 → { ok, preferredName }. Account-level mutation — the transport strips
   * X-View-As from writes, so it can never ride a viewer-mode selection.
   */
  function patchPreferredName(
    preferredName: string,
  ): Promise<{ ok?: boolean; preferredName?: string } | null> {
    return apiFetch<{ ok?: boolean; preferredName?: string } | null>("/auth/me", {
      method: "PATCH",
      body: { preferredName },
    });
  }

  /** Chat history (AI Q&A about the claim). */
  async function loadMessages(): Promise<MessageVM[]> {
    const list =
      (await apiFetch<MessageResponse[]>("/claim/messages", { allow404AsNull: true })) ?? [];
    return list.map(toMessage);
  }

  /** The veteran's outgoing shares (VSO/attorney invites). */
  async function loadShares(): Promise<ShareVM[]> {
    const list = (await apiFetch<ShareDto[]>("/shares", { allow404AsNull: true })) ?? [];
    return list.map((s) => toShare(s));
  }

  /**
   * Source documents (evidence) as view-models, for the Documents grid, plus the
   * tri-state `subState`. The subscription read was already needed for the
   * per-doc "stored, not AI-read" free badge (P0-3); it is surfaced here too so
   * the Documents page can carry the same honest Pro affordance the dissolved
   * AddModal used on its "Describe what you remember → Ask AI" card (P2-1) —
   * "error" is NOT "free", so an outage never flashes a Pro upsell.
   */
  async function loadDocuments(): Promise<DocumentsData> {
    const [list, sub] = await Promise.all([
      apiFetch<EvidenceResponse[]>("/claim/evidence", { allow404AsNull: true }),
      getSubscriptionResult(),
    ]);
    const free = sub.state === "free";
    return {
      docs: (list ?? []).map((e) => toDoc(e, { free })),
      subState: sub.state,
    };
  }

  /**
   * Next Steps + Scenarios: prioritized gaps and two server-computed scenarios.
   * Carries `subState`/`documentsCount` so the Steps empty state can be
   * subscription-aware (P1-16) — same tri-state rule as `loadConditionsPage`.
   */
  async function loadNextSteps(): Promise<NextStepsVM> {
    const [me, condRead, gapsRead, sub] = await Promise.all([
      getMe(),
      analysisRead(getConditions),
      analysisRead(getGapsEnvelope),
      getSubscriptionResult(),
    ]);
    const conditions = condRead.value ?? [];
    const gapsEnvelope = gapsRead.value ?? { gaps: [], gapAnalysisPending: false };
    const gaps = gapsEnvelope.gaps;
    const conds = conditions.map(toCondition);
    // ALL steps (open + resolved/dismissed) go to the panel — it renders the
    // closed ones in a collapsed "Done / dismissed" section with Undo. Counts
    // and scenarios reason over OPEN steps only (P1-6).
    const steps = toSteps(gaps);
    const open = openSteps(steps);
    // Rating can be null ("not yet rated") — only real positive ratings feed the calc.
    const positiveRatings = (cs: CondVM[]): number[] =>
      cs.map((c) => c.rating).filter((r): r is number => r != null && r > 0);
    const ready = conds.filter((c) => c.ready);
    const readyRatings = positiveRatings(ready);

    // Needs-work conditions tied to a high-priority OPEN gap — what "closing the gaps" adds.
    const highGapCondIds = new Set(open.filter((s) => s.priority === "high").map((s) => s.condId));
    const gapConds = conds.filter((c) => !c.ready && highGapCondIds.has(c.id));
    const withGapsRatings = [...readyRatings, ...positiveRatings(gapConds)];

    // A failed calc must surface as "estimate unavailable", not a silent $0.
    // Only ratings-bearing scenarios can fail; an empty set is a legitimate zero.
    const zero: CombinedRatingResult = { combined_rating: 0, monthly_estimate: 0 };
    let estimateUnavailable = false;
    const calcOrFlag = async (ratingsIn: number[]): Promise<CombinedRatingResult> => {
      if (!ratingsIn.length) return zero;
      try {
        return await calculateCombined(ratingsIn);
      } catch {
        estimateUnavailable = true;
        return zero;
      }
    };
    const [base, withGaps] = await Promise.all([
      calcOrFlag(readyRatings),
      calcOrFlag(withGapsRatings),
    ]);

    const basePay = base.monthly_estimate ?? 0;
    const scenarios: ScenarioVM[] = [
      {
        // Evidence-strength framing, not filing advice: every condition is
        // legally claimable today (P0-10).
        label: `Your ${ready.length} strongest condition${ready.length === 1 ? "" : "s"} today`,
        conditionNames: ready.map((c) => c.name),
        combinedRating: base.combined_rating ?? 0,
        monthlyPay: basePay,
        deltaPay: 0,
        badge: `${base.combined_rating ?? 0}%`,
        alt: false,
      },
    ];
    if (gapConds.length) {
      const withPay = withGaps.monthly_estimate ?? 0;
      scenarios.push({
        label: `+ Close ${gapConds.length} high-priority gap${gapConds.length === 1 ? "" : "s"}`,
        conditionNames: [...ready, ...gapConds].map((c) => c.name),
        combinedRating: withGaps.combined_rating ?? 0,
        monthlyPay: withPay,
        deltaPay: Math.max(0, withPay - basePay),
        badge: `${withGaps.combined_rating ?? 0}%`,
        alt: true,
      });
    }

    const claim = me.activeClaim ?? null;
    const isPro = sub.state === "pro";
    return {
      steps,
      highCount: open.filter((s) => s.priority === "high").length,
      medCount: open.filter((s) => s.priority === "medium").length,
      readyCount: ready.length,
      scenarios,
      estimateUnavailable,
      gapAnalysisPending: gapsEnvelope.gapAnalysisPending,
      subState: sub.state,
      documentsCount: claim ? Number(claim.evidenceCount ?? 0) : 0,
      // Arms the Steps-page polling banner; Pro-gated because a free user's
      // pipeline never advances (polling could never complete).
      pipelineActive:
        isPro &&
        !!claim &&
        ((!!claim.analysisStage && claim.status !== "ERROR") || gapsEnvelope.gapAnalysisPending),
      isPro,
      analysisBlocked: condRead.blocked || gapsRead.blocked,
    };
  }

  return {
    getMe,
    getConditions,
    getGaps,
    getNotifications,
    loadAnalysisUpdates,
    loadTimeline,
    getUsage,
    getSubscriptionResult,
    getSubscription,
    probeAnalysisBlocked,
    calculateCombined,
    getCombinedRating,
    loadHomeVM,
    loadConditions,
    loadConditionsPage,
    loadConditionDetail,
    loadSubscription,
    getProfile,
    loadProfilePage,
    patchPreferredName,
    loadMessages,
    loadShares,
    loadDocuments,
    loadNextSteps,
    loadUsageBreakdown,
  };
}
