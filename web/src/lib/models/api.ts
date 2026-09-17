/*
 * Raw backend response shapes (Spring DTOs), typed off the actual source:
 *   spring-backend/src/main/java/com/afterduty/dto/*.java
 * Field names are camelCase. Everything is optional/defensive because the BFF
 * must never crash on a missing field — adapters normalize into view-models.
 */

export interface TriadElementRaw {
  status?: string | null; // "strong" | "moderate" | "weak" | "missing"
  evidence?: unknown[];
  confidence?: number;
}

export interface GapRaw {
  condId?: number;
  condName?: string;
  type?: string; // humanized token, e.g. "Nexus letter" / "C&P exam request"
  label?: string;
  why?: string;
  suggest?: string;
  impact?: string;
  priority?: string; // "high" | "medium" | "low"
  status?: string; // "open" (default) | "resolved" | "dismissed"
  index?: number; // gap's position within its condition's gap list
  triadLeg?: string | null;
  targetRating?: number | null;
  estimatedTime?: string | null;
  estimatedCostUsd?: number | null;
}

export interface ConditionResponse {
  id: number;
  claimId?: number;
  name: string;
  vasrdCode?: string | null;
  bodySystem?: string | null;
  triadDiagnosis?: TriadElementRaw | null;
  triadInService?: TriadElementRaw | null;
  triadNexus?: TriadElementRaw | null;
  // Jackson serializes the DTO's boolean `isPresumptive` as the JSON key
  // "presumptive" (is-prefix stripped); we read both to stay compatible.
  presumptive?: boolean;
  isPresumptive?: boolean;
  presumptiveBasis?: string | null;
  /** Null/absent = not yet rated. A true 0 is a real VA outcome (0% grant). */
  estimatedRating?: number | null;
  ratingRationale?: string | null;
  /**
   * Rating honesty: a non-alarming "Estimate — needs <objective measure> to confirm"
   * note set by the backend when this condition's VASRD code is in a curated map of
   * high-value quantitative codes AND the measure its rating tiers hinge on is absent
   * from the evidence (e.g. asthma 6602 with no PFT/FEV-1). Null when the code is
   * unmapped or the measure is present. The `confidence` below is already tempered by
   * the same backend pass, so no separate signal is needed here.
   */
  ratingEvidenceNote?: string | null;
  /** 0..1. Null/absent = pipeline sent no confidence — render nothing, never "0%". */
  confidence?: number | null;
  gaps?: GapRaw[];
  whatIfScenarios?: unknown[];
  pyramidGroup?: string | null;
  pyramidReason?: string | null;
  /**
   * Pyramiding grouped view: true on the single EFFECTIVE (counting, highest-rated)
   * member of a pyramiding group — the one whose rating the group combines to. Null
   * on absorbed members and ungrouped conditions. Set deterministically by the backend
   * (assignPyramidingGroups), not the LLM.
   */
  pyramidPrimary?: boolean | null;
  /**
   * Pyramiding grouped view: the group's effective rating (the primary member's own
   * estimatedRating), stamped on every member so the web can show the single combined
   * line without re-deriving it. Null when this condition is not in a group.
   */
  pyramidGroupRating?: number | null;
  /**
   * "Don't include in my claim" (owner-set, reversible): true = the veteran isn't
   * filing for this valid condition, so the server drops it from the combined rating
   * (and pay). It STAYS in this list so the web can move it to the collapsible "Not
   * filing" section with a one-tap re-include. Null/absent = included. Distinct from
   * delete/suppress ("this is wrong").
   */
  excludedFromClaim?: boolean | null;
}

export interface ClaimResponse {
  id: number;
  claimType?: string;
  status?: string; // DRAFT | EXTRACTING | READY | SYNTHESIZING | ANALYZED | ERROR
  synthesisNeeded?: boolean;
  evidenceCount?: number;
  conditionCount?: number;
  atomCount?: number;
  createdAt?: string;
  updatedAt?: string;
  analysisMessage?: string | null;
  analysisStage?: string | null;
  analysisProgressPct?: number | null;
  lastAnalyzedAt?: string | null;
}

export interface ProfileSummary {
  claimId: number;
  claimOwnerId?: number;
  ownerName?: string;
  ownerEmail?: string;
  isOwn?: boolean;
  canViewAnalysis?: boolean;
  canUploadDocs?: boolean;
}

export interface UserResponse {
  id: number;
  /**
   * Absent/null when the stored address is a synthetic "<uid>@firebase.local"
   * placeholder (phone-first accounts) — the backend omits it via non_null.
   * Older backends may still send the synthetic literal; format.ts filters it.
   */
  email?: string | null;
  name?: string;
  /** The veteran's chosen display name ("What should we call you?"). */
  preferredName?: string | null;
  role?: string; // "veteran" | "admin"
  hasProfile?: boolean;
  activeClaim?: ClaimResponse | null;
  sharedProfiles?: ProfileSummary[];
}

export interface UsageResponse {
  percentUsed?: number;
  atLimit?: boolean;
  periodStart?: string;
  periodEnd?: string;
}

export interface SubscriptionPlan {
  tier: string;
  price: number;
  currency?: string;
  billing_period?: string;
}

export interface SubscriptionFeature {
  key: string;
  name: string;
  desc?: string;
}

export interface SubscriptionStatus {
  active?: boolean;
  expires_at?: string | null;
  current_tier?: string | null;
  plans?: SubscriptionPlan[];
  features?: SubscriptionFeature[];
  publishable_key?: string | null;
  /** Billing rail ("stripe" | "apple" | "google"); omitted on free/legacy rows. */
  source?: string | null;
}

/** One condition's contribution to GET /api/claim/combined-rating. */
export interface CombinedRatingInputRow {
  conditionId?: number;
  name?: string;
  rating?: number;
  counted?: boolean;
  /** Why not counted: "pyramided-into:PTSD" | "not-ready" — null when counted. */
  reason?: string | null;
  /**
   * Humanized pyramiding group (e.g. "mental health"), present on BOTH the counted
   * survivor and its absorbed members so the web can fold them into one grouped line
   * with the absorbed members nested. Null/absent when the condition has no group.
   */
  group?: string | null;
}

/**
 * One step of the labeled sequential VA-math combine (GET .../combined-rating →
 * `combineSteps`). One entry per counted contributor (a condition, a pyramiding
 * group, or a bilateral pair), ordered DESCENDING by rating, then a final rounding
 * step. Server-computed via VaMathService.combineLabeled — the exact §4.25 math
 * (kill-list rule). Omitted entirely when the labeled walk would diverge from the
 * authoritative combinedRating (web then falls back to the abstract `steps`).
 */
export interface CombineStepRow {
  /** Contributor label ("Mental health", "Asthma", "Left knee + Right knee (bilateral)"). */
  label?: string;
  /** The contributor's effective % entering the combine; null on the final rounding step. */
  rating?: number | null;
  /** Points this contributor added to the running combined (2dp). */
  pointsAdded?: number;
  /** Running combined AFTER this contributor (2dp precise cumulative; final rounded int on rounding step). */
  combinedAfter?: number;
  /** Remaining capacity after this contributor (2dp). */
  remainingAfter?: number;
  /** Pyramided-in member names rated together (they don't add); [] when none. */
  absorbedMembers?: string[];
  /** True only on the final "Rounded to nearest 10" step. */
  rounding?: boolean;
}

/**
 * GET /api/claim/combined-rating?scope=all|ready (pinned C1 contract).
 * Server-side ONLY math: PyramidingRules.plan + VaMathService — the web never
 * computes a dollar or a percent (kill-list rule). An empty claim is a 200 with
 * combinedRating 0 and empty inputs; a null FETCH (throw / 404 route skew) is
 * the estimate-unavailable state, never $0.
 */
export interface CombinedRatingSummary {
  scope?: string; // "all" | "ready"
  /** VA-rounded, after pyramiding plan + bilateral factor. */
  combinedRating?: number;
  /** Dollars, veteran-alone rate. */
  monthlyEstimate?: number;
  ratesYear?: number;
  /** Human-readable pyramiding absorptions / bilateral notes. */
  notes?: string[];
  /** VaMathService's abstract step-by-step combination walk ("70% + 30% -> 79% -> ..."). */
  steps?: string[];
  /**
   * The labeled sequential combine (one contributor per step, descending, + a
   * rounding step). Absent on older backends OR on a divergence fallback — the web
   * then renders the abstract `steps` / contributor list instead.
   */
  combineSteps?: CombineStepRow[];
  inputs?: CombinedRatingInputRow[];
  /**
   * Count of ACTIVE, rated conditions the veteran left out ("Don't include in my
   * claim"). Drives the "N excluded" line. Absent on older backends → treat as 0.
   */
  excludedCount?: number;
}

/** Result of POST /api/scenarios/calculate (VaMathService.calculateCombinedRating). */
export interface CombinedRatingResult {
  combined_rating?: number;
  exact_value?: number;
  rounded_value?: number;
  bilateral_factor?: number;
  monthly_estimate?: number;
  steps?: string[];
}

export interface EvidenceResponse {
  id: number;
  sourceType?: string; // upload | chat | quick_add
  filename?: string;
  aiClassification?: string | null; // Service | Medical | Statement | ...
  aiExtractedData?: Record<string, unknown> | null;
  aiSummary?: string | null;
  processingStatus?: string; // pending | processing | processed | error | deferred_usage_limit
  processingMessage?: string | null;
  createdAt?: string;
}

export interface ShareDto {
  id: number;
  claimId?: number;
  viewerEmail?: string;
  canViewAnalysis?: boolean;
  canUploadDocs?: boolean;
  invitationToken?: string | null;
  acceptUrl?: string | null;
  invitationExpiresAt?: string | null;
  acceptedAt?: string | null;
  revokedAt?: string | null;
  createdAt?: string;
  /** Server-computed lifecycle status (P2-2): "pending" | "accepted" | "expired" | "revoked". */
  status?: string | null;
}

/** Invite preview for GET /shares/accept/{token} (who's inviting + what access). */
export interface SharePreviewDto {
  shareId?: number;
  ownerName?: string | null;
  ownerEmail?: string | null;
  claimId?: number;
  claimStatus?: string | null;
  canViewAnalysis?: boolean;
  canUploadDocs?: boolean;
  expiresAt?: string | null;
}

/**
 * One contributing raw record behind a reconciled service-period conclusion
 * (Spring `ServiceSourceDto`) — the "receipts" for the Service History
 * drill-down. Emitted only inside a reconciled period's `sources[]`; every
 * field is optional (the BFF must tolerate self-statement rows with no
 * `evidenceId`, undated records, older backends).
 */
export interface ServiceSourceRaw {
  evidenceId?: number | null; // owning EvidenceItem id; null for the manual/self-statement row
  docType?: string | null; // classified document type, or "manual"
  authorityRank?: number | null; // VA-style authority rank of docType (higher wins)
  rawBranch?: string | null; // raw field values as this record carried them,
  rawStart?: string | null; // pre-reconciliation (before the conclusion picked
  rawEnd?: string | null; // winners) — YYYY-MM-DD for dates
  rawMos?: string | null;
  rawRank?: string | null;
}

/**
 * One service period from GET /auth/profile (pinned additive contract).
 * `component` is derived deterministically server-side from branch/discharge
 * text; `source` says whether the period came from extracted documents or the
 * veteran's manual ServiceProfile row. Everything optional — the BFF must
 * tolerate older backends and partial rows.
 *
 * Reconciliation (2026-07-05): when the backend collapses the duplicate
 * government records into ONE conclusion per enlistment, the conclusion
 * additionally carries `sources[]` (contributing raw records), `reasoning`
 * (deterministic trace of how the fields resolved), and `totalYears` (this
 * conclusion's own span). All three are ADDITIVE + NULLABLE — omitted on the
 * wire for older/unreconciled data.
 */
export interface ServicePeriodRaw {
  branch?: string | null; // "Army", "Army National Guard", …
  component?: string | null; // "active" | "guard" | "reserve" | null
  startDate?: string | null; // "YYYY-MM-DD"
  endDate?: string | null; // "YYYY-MM-DD" | null = Present (when startDate known)
  mos?: string | null;
  rank?: string | null;
  source?: string | null; // "documents" | "manual"
  /** Contributing raw records behind this conclusion; absent when unreconciled. */
  sources?: ServiceSourceRaw[] | null;
  /** Deterministic explanation of how this conclusion resolved; absent when unreconciled. */
  reasoning?: string | null;
  /** This conclusion's own span in whole years; absent when unreconciled or dateless. */
  totalYears?: number | null;
  /**
   * Stable cluster key (Service History P3) — the backend's identity for this
   * reconciled enlistment. The web round-trips it in a veteran-override request so
   * the correction re-attaches to the right conclusion after re-derivation. Absent
   * on older/unreconciled data (override affordance is then hidden).
   */
  clusterKey?: string | null;
}

export interface ServiceProfileResponse {
  id?: number;
  userId?: number;
  branch?: string | null;
  serviceStart?: string | null;
  serviceEnd?: string | null;
  dutyStations?: Array<Record<string, unknown>>;
  deployments?: Array<Record<string, unknown>>;
  mos?: string | null;
  exposureRisks?: string[];
  /** NEW (additive): multiple service periods, newest-first. Absent on older backends. */
  servicePeriods?: ServicePeriodRaw[];
}

export interface MessageResponse {
  id?: number;
  role?: string; // "user" | "assistant" | ...
  content?: string;
  extractedData?: Record<string, unknown> | null;
  createdAt?: string;
}
