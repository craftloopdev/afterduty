// View-models — the shapes the UI consumes. Adapters turn raw DTOs into these.
import type { TriadLevel } from "@/lib/theme/tokens";
import type { IconName } from "@/components/ui/Icon";

export type Priority = "high" | "medium" | "low";

export interface MessageVM {
  id: string;
  role: "user" | "assistant";
  content: string;
  /** ISO timestamp from the backend, when known. */
  createdAt?: string;
  /** Optimistic message not yet acknowledged by the server. */
  pending?: boolean;
  /** Send failed — the bubble keeps the text and offers a retry. */
  failed?: boolean;
}

export interface PlanVM {
  tier: string; // "monthly" | "annual"
  price: number;
  period: string; // "month" | "year"
  label: string;
}

export interface FeatureVM {
  key: string;
  name: string;
  desc: string;
}

/**
 * Tri-state billing truth. `"error"` means the subscription status fetch
 * failed (upstream/network) — it is NOT "free": we must never show a paying
 * user the sales paywall (double-charge risk), and never flash upsell to them.
 */
export type SubscriptionState = "pro" | "free" | "error";

export interface SubscriptionVM {
  /** Source of truth. `active` is derived (`state === "pro"`) for back-compat. */
  state: SubscriptionState;
  active: boolean;
  currentTier: string | null;
  /** ISO renewal/expiry date when the backend sent one; null otherwise. */
  expiresAt: string | null;
  plans: PlanVM[];
  features: FeatureVM[];
}

/** One feature's slice of the monthly AI budget (GET /api/usage/breakdown). */
export interface UsageFeatureVM {
  feature: string;
  label: string;
  cents: number;
  calls: number;
}

/** "Analyze my usage" — how the monthly AI budget was spent, from the ledger. */
export interface UsageBreakdownVM {
  periodStart: string;
  periodEnd: string;
  resetAt: string;
  /** Effective cap in cents (-1 = unlimited/comped). */
  limitCents: number;
  spentCents: number;
  remainingCents: number;
  atLimit: boolean;
  unlimited: boolean;
  byFeature: UsageFeatureVM[];
}

/**
 * One contributing raw record behind a reconciled service period — the
 * "receipts" the Service History drill-down modal lists under Facts. Derived
 * from the backend's `ServiceSourceDto`; every field is nullable (self-statement
 * rows have no `evidenceId`, undated records have null raw dates).
 */
export interface ServiceSourceVM {
  /** Owning evidence id, or null for the manual/self-statement row. */
  evidenceId: number | null;
  /** Document type this source was classified as (e.g. "DD-214"), or "manual". */
  docType: string | null;
  /** VA-style authority rank of `docType` (higher wins); null when unknown. */
  authorityRank: number | null;
  /** Raw field values exactly as this record carried them, pre-reconciliation. */
  rawBranch: string | null;
  rawStart: string | null;
  rawEnd: string | null;
  rawMos: string | null;
  rawRank: string | null;
}

/**
 * One period of military service (pinned /auth/profile contract). Derived
 * deterministically from already-extracted document facts (source
 * "documents") or from the veteran's manually-entered ServiceProfile row
 * (source "manual"). Ordered newest-first; null start sorts last.
 *
 * When the backend reconciles duplicate records into one conclusion per
 * enlistment, the additive `sources`/`reasoning`/`totalYears` fields carry the
 * drill-down "receipts" (how the merge resolved). All three are NULLABLE — null
 * on older/unreconciled data, and the UI degrades gracefully.
 */
export interface ServicePeriodVM {
  branch: string; // "Army", "Army National Guard", …
  component: "active" | "guard" | "reserve" | null;
  /** ISO dates when known. endDate null with a startDate = still serving ("Present"). */
  startDate: string | null;
  endDate: string | null;
  mos: string | null;
  rank: string | null;
  source: "documents" | "manual";
  /** Contributing raw records behind this conclusion; null when unreconciled. */
  sources: ServiceSourceVM[] | null;
  /** Deterministic explanation of how this conclusion resolved; null when unreconciled. */
  reasoning: string | null;
  /** This conclusion's own span in whole years; null when unreconciled or dateless. */
  totalYears: number | null;
  /**
   * Stable cluster key identifying this reconciled enlistment (Service History
   * P3). Round-tripped in a veteran-override request so the correction re-attaches
   * to the right conclusion. null on older/unreconciled data — the modal then hides
   * the "correct it" affordance (there is no stable key to target).
   */
  clusterKey: string | null;
}

export interface ProfileVM {
  /**
   * The human display name (preferredName > real name > real-email local
   * part). EMPTY STRING when nothing usable exists — the view renders
   * "Welcome" + the set-name affordance, NEVER a uid or a synthetic email.
   */
  name: string;
  /** Real email only — null when absent or a synthetic "@firebase.local" placeholder. */
  email: string | null;
  initial: string;
  branch: string | null;
  service: string | null;
  mos: string | null;
  /** Service periods, newest-first (pinned contract; [] = nothing derived yet). */
  servicePeriods: ServicePeriodVM[];
  isPro: boolean;
  /** Tri-state billing truth; drives the Plan row (Pro/Free vs honest unknown). */
  subState: SubscriptionState;
  /** Current plan tier label when Pro (e.g. "monthly"), null otherwise. */
  planTier: string | null;
  /** ISO renewal/expiry date when known, for the "renews/expires" hint. */
  planExpiresAt: string | null;
  /**
   * Which billing rail owns the subscription (P1-21) — routes "Manage
   * subscription" to the right portal (Apple/Google subscribers must be sent
   * to their store, not Stripe). Null on free users, legacy rows, or when the
   * backend omitted the field.
   */
  billingSource: "portal" | "apple" | "google" | null;
  role: string | null;
}

/**
 * Viewer-mode state (P0-8) — who the current session is looking at. Built by
 * the layouts from `me.sharedProfiles` + the cp_view_as selection and provided
 * shell-wide via `ViewerContext`, so downstream components can hide mutation
 * affordances (`useViewer().viewing`) without prop-drilling.
 */
export interface ViewerVM {
  /** True when a SHARED claim is selected — the UI is read-only. */
  viewing: boolean;
  /** The shared claim id being viewed; null when on the user's own claim. */
  claimId: number | null;
  /** The claim owner's display name (falls back to their email), for the banner. */
  ownerName: string | null;
  /** The share grants analysis viewing (owner-Pro dependency still applies). */
  canViewAnalysis: boolean;
  /** The share grants adding documents (never deleting — DELETE_DOCS is owner-only). */
  canUploadDocs: boolean;
  /**
   * Analysis on the viewed claim is NOT accessible: either the share is
   * docs-only, or it grants analysis but the owner's Pro lapsed (the backend
   * 403s analysis reads). Drives the honest owner-Pro dependency notice.
   */
  analysisBlocked: boolean;
}

/** One "View <owner>'s claim" switcher entry, from `me.sharedProfiles`. */
export interface SharedClaimRef {
  claimId: number;
  ownerName: string;
}

export type ShareStatus = "pending" | "accepted" | "revoked" | "expired";

export interface ShareVM {
  id: number;
  email: string;
  canViewAnalysis: boolean;
  canUploadDocs: boolean;
  status: ShareStatus;
  /** ISO instant the viewer accepted, for "Accepted <date>" (P2-2). */
  acceptedAt?: string | null;
  /** ISO instant the pending invite link lapses, for "Invite sent — expires <date>". */
  expiresAt?: string | null;
  /** Backend-computed invite link — lets a pending invite be re-copied (P2-2). */
  acceptUrl?: string | null;
  /** Raw invite token: origin-built link fallback for older backends without acceptUrl. */
  inviteToken?: string | null;
}

export type DocStatus = "done" | "processing" | "paused" | "error" | "queued";

export interface DocVM {
  id: number;
  name: string;
  kind: string;
  icon: IconName;
  color: string;
  /** Normalized pipeline state — never the raw backend enum. */
  status: DocStatus;
  /** Friendly badge label, e.g. "Processed" or "Paused — monthly AI limit reached". */
  statusLabel: string;
  /** Human-readable pipeline detail (error reason, resume date), when the backend sent one. */
  statusDetail: string | null;
  processed: boolean;
}

export interface LegVM {
  level: TriadLevel;
  items: string[];
}

export interface TriadVM {
  dx: TriadLevel;
  is: TriadLevel;
  nx: TriadLevel;
}

export interface CondVM {
  id: number;
  name: string;
  fullName: string;
  system: string;
  vasrdCode: string | null;
  /**
   * Estimated rating percent. `null` = not yet rated (render "Not yet rated").
   * A true `0` is a REAL VA outcome — a 0% grant still service-connects the
   * condition — so it renders "0%", never a dash (P1-5).
   */
  rating: number | null;
  /** 0..100 integer, or `null` when the pipeline sent no confidence — render NOTHING (P1-5). */
  confidence: number | null;
  /** Presumptive basis label, or null when not presumptive. */
  presumptive: string | null;
  triad: TriadVM;
  /** True when all three legs are strong. */
  ready: boolean;
  rationale: string | null;
  /**
   * Rating honesty: a non-alarming "Estimate — needs <objective measure> to confirm"
   * note when this condition's rating rests on a MISSING objective test (e.g. asthma
   * with no PFT). Null when the code is unmapped or the measure is present. The
   * `confidence` above is already tempered by the same backend pass.
   */
  ratingEvidenceNote: string | null;
  /**
   * Pyramiding grouped view: the canonical group this condition is rated WITH under
   * one VA formula (e.g. "Mental Health (§4.130)"), or null when ungrouped. Set
   * deterministically by the backend. The Conditions list groups consecutive rows that
   * share this label under one header.
   */
  pyramidGroup: string | null;
  /** True on the single EFFECTIVE (strongest) member of the group — the one that counts. */
  pyramidPrimary: boolean;
  /**
   * Plain-language "why these don't stack" for an ABSORBED member ("Rated together …
   * the strongest counts, they don't add"). Null on the primary and on ungrouped rows.
   */
  pyramidReason: string | null;
  /**
   * The group's effective rating (the primary member's rating), stamped on every
   * member so the combined line ("~70% — the strongest counts") needs no re-derivation.
   * Null when ungrouped.
   */
  pyramidGroupRating: number | null;
  /**
   * "Don't include in my claim" (owner-set, reversible): true = the veteran isn't
   * filing for this valid condition. The server has already dropped it from the
   * combined rating + pay; the list moves it into the collapsible "Not filing"
   * section (out of its pyramiding group) with a one-tap re-include. Distinct from
   * delete/suppress ("this is wrong").
   */
  excludedFromClaim: boolean;
  legs: { dx: LegVM; is: LegVM; nx: LegVM };
  /**
   * The WEAKEST leg (missing < partial < strong; ties break dx→is→nx), or null
   * when all are strong. A presumptive condition's nexus leg is NEVER the
   * weakest — presumption covers it regardless of the pipeline's score (P1-2).
   */
  weakestLeg: "dx" | "is" | "nx" | null;
}

export interface StepVM {
  id: string;
  condId: number;
  cond: string;
  type: string;
  gap: string;
  why: string;
  suggest: string;
  impact: string;
  impactStrong: boolean;
  priority: Priority;
  /** Gap resolution state ("open" default; resolved/dismissed once P1-6 lands). */
  status: string;
  /** Stable handle for the gap within its condition (future PATCH status endpoint). */
  gapIndex: number | null;
  triadLeg: string | null;
  targetRating: number | null;
  estimatedTime: string | null;
  estimatedCostUsd: number | null;
}

export interface EvidenceHealthLeg {
  leg: "dx" | "is" | "nx";
  label: string;
  color: string;
  strong: number;
  partial: number;
  missing: number;
  total: number;
}

export interface ScenarioVM {
  label: string;
  conditionNames: string[];
  combinedRating: number;
  monthlyPay: number;
  /** Extra monthly pay vs. the base "file ready conditions" scenario. */
  deltaPay: number;
  badge: string;
  alt: boolean;
}

export interface NextStepsVM {
  steps: StepVM[];
  highCount: number;
  medCount: number;
  readyCount: number;
  scenarios: ScenarioVM[];
  /** Server-side pay calc failed — dollar figures in scenarios are unavailable. */
  estimateUnavailable: boolean;
  /**
   * A re-analysis has activated but its gap re-check hasn't finished — some
   * active conditions have no gap data yet. The empty list must render
   * "Re-checking your next steps…" instead of a false "You're all caught up".
   * Optional so hand-built fixtures stay valid; absent means false.
   */
  gapAnalysisPending?: boolean;
  /** Server-side hint that a pipeline run is active — arms the polling banner. */
  pipelineActive?: boolean;
  /** Pro gate for polling — a free user's pipeline never advances. */
  isPro?: boolean;
  /**
   * Tri-state billing truth for the empty state (P1-16): free-with-docs gets
   * the honest Pro boundary; "error" must NEVER select the free copy.
   */
  subState: SubscriptionState;
  /** Evidence count from the active claim; 0 when unknown. */
  documentsCount: number;
  /**
   * Viewer mode (P0-8): analysis reads on the viewed shared claim were
   * 403-blocked — render the owner-Pro dependency notice, not "add evidence".
   * Absent/false on the user's own claim.
   */
  analysisBlocked?: boolean;
}

/**
 * Conditions page data + the subscription context its empty state needs
 * (P1-16). Same tri-state rule as `NextStepsVM` — "error" must never be
 * rendered as free (no upsell on outage).
 */
export interface ConditionsPageData {
  conditions: CondVM[];
  /** Tri-state — "error" must never be rendered as free (no upsell on outage). */
  subState: SubscriptionState;
  /** Evidence count from the active claim; 0 when unknown. */
  documentsCount: number;
  /**
   * Viewer mode (P0-8): analysis reads on the viewed shared claim were
   * 403-blocked — render the owner-Pro dependency notice, not "add evidence".
   * Absent/false on the user's own claim.
   */
  analysisBlocked?: boolean;
}

/**
 * One condition's contribution to a combined-rating scope — the veteran-facing
 * "what makes up your rating" row. `counted` survivors are the labeled contributors;
 * absorbed members (counted=false, reason "pyramided-into:<primary>") nest under the
 * group heading. `group` (humanized, e.g. "mental health") folds multiple members
 * into one line. Every field is server-provided (kill-list rule).
 */
export interface RatingInputVM {
  conditionId: number | null;
  name: string;
  rating: number;
  counted: boolean;
  /** null when counted; "not-ready" | "pyramided-into:<primary>" otherwise. */
  reason: string | null;
  /** Humanized pyramiding group, or null when ungrouped. */
  group: string | null;
}

/**
 * One step of the labeled sequential VA-math combine — one counted contributor (a
 * condition, a pyramiding group, or a bilateral pair), or the final rounding step.
 * Server-computed (VaMathService.combineLabeled); every number is verbatim from the
 * backend (kill-list rule). Ordered DESCENDING by rating; the last entry is the
 * rounding step (`rounding: true`, `rating: null`).
 */
export interface CombineStepVM {
  /** Contributor label ("Mental health", "Asthma", "Left knee + Right knee (bilateral)"). */
  label: string;
  /** Effective % entering the combine; null on the final rounding step. */
  rating: number | null;
  /** Points this contributor added to the running combined (2dp). */
  pointsAdded: number;
  /** Running combined AFTER this contributor (precise cumulative; final rounded on the rounding step). */
  combinedAfter: number;
  /** Remaining capacity after this contributor (2dp). */
  remainingAfter: number;
  /** Pyramided-in member names rated together (they don't add); [] when none. */
  absorbedMembers: string[];
  /** True only on the final "Rounded to nearest 10" step. */
  rounding: boolean;
}

/**
 * One scope of the server-computed combined rating (GET /claim/combined-rating).
 * Every number here came from the backend verbatim — the web NEVER derives a
 * rating or a dollar (kill-list rule).
 */
export interface RatingScopeVM {
  /** VA-rounded combined rating, after pyramiding + bilateral. */
  rating: number;
  /** Monthly dollars, veteran-alone rate. */
  monthly: number;
  /** VaMathService's abstract step-by-step combination walk ("70% + 30% -> 79% -> ..."). */
  steps: string[];
  /**
   * The labeled sequential combine — the primary breakdown ("Mental health 70% →
   * combined 70%, 30% left"). Empty [] on older data or a divergence fallback; the
   * UI then renders the contributor list + abstract steps instead.
   */
  combineSteps: CombineStepVM[];
  /** Pyramiding absorptions / bilateral-factor notes (small print). */
  notes: string[];
  /** Per-condition contributions — the grouped labeled "what makes up your rating". */
  inputs: RatingInputVM[];
  /** Count of conditions the veteran left out ("Don't include in my claim"); 0 when none. */
  excludedCount: number;
}

export interface HomeVM {
  firstName: string;
  hasClaim: boolean;
  isNewUser: boolean;
  isAnalyzing: boolean;
  /**
   * True for a FREE user who has uploaded evidence but whose AI analysis is
   * Pro-gated (the backend stamps the claim "extracting" on upload, but the
   * pipeline never advances without a subscription). Home shows an
   * "upgrade to analyze" state instead of a perpetual progress spinner.
   */
  analysisLocked: boolean;
  analysisProgressPct: number;
  /**
   * The claim's pipeline run ended in ERROR — Home renders an honest error
   * card (with `analysisErrorMessage` when the backend sent one), never an
   * eternal spinner.
   */
  analysisError: boolean;
  analysisErrorMessage: string | null;
  /**
   * Analysis completed but produced zero conditions — a legitimate terminal
   * state ("we couldn't identify conditions from these documents yet"), not
   * "analyzing" and not a brand-new user.
   */
  noConditionsFound: boolean;
  /**
   * A re-run's gap re-check hasn't landed yet (new generation active with no
   * gap data). Consumed defensively — absent upstream field means false.
   */
  gapAnalysisPending: boolean;
  /** Server-side hint that a pipeline run is active (arms the polling banner). */
  pipelineActive: boolean;
  /** Evidence documents on the claim — the honest free-tier Home shows this. */
  documentsCount: number;
  /**
   * True when the server-side pay/rating fetch failed. The hero & pay card must
   * render "Estimate unavailable right now" instead of presenting 0% / $0 as
   * real figures.
   */
  estimateUnavailable: boolean;
  /** scope=ready — only all-three-legs-strong conditions ("Ready today"). */
  readyScope: RatingScopeVM;
  /** scope=all — every active rated condition ("If all N are granted"). */
  allScope: RatingScopeVM;
  /** VA rates vintage the dollars use (e.g. 2026), when the backend sent it. */
  ratesYear: number | null;
  /**
   * The conditions the veteran is FILING (excludes any set "Don't include in my
   * claim"). Every Home count, the evidence-health card, and the "N of M" labels
   * derive from this set. Excluded conditions live only in the full /conditions
   * list; `notFilingCount` records how many were held back.
   */
  conditions: CondVM[];
  /** How many conditions the veteran excluded from filing (in the full list, not counted here). */
  notFilingCount: number;
  ready: CondVM[];
  needsWork: CondVM[];
  presumptiveCount: number;
  steps: StepVM[];
  topStep: StepVM | null;
  evidenceHealth: EvidenceHealthLeg[];
  isPro: boolean;
  /** Tri-state billing truth (upsell affordances treat "error" as neither). */
  subState: SubscriptionState;
  atUsageLimit: boolean;
  /**
   * True when the account has NO usable display name (displayName() === "" —
   * uid-only phone sign-ins). Arms the dismissible "What should we call you?"
   * card. Optional so hand-built fixtures stay valid; absent means false.
   */
  nameless?: boolean;
  /**
   * Viewer mode (P0-8): analysis reads on the viewed shared claim were
   * 403-blocked — Home must not present the empty claim as the owner's real
   * state. Absent/false on the user's own claim.
   */
  analysisBlocked?: boolean;
}
