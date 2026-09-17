import { composeHomeVM } from "@/lib/adapters/home";
import type { GapRawLive } from "@/lib/adapters/gaps";
import type { CombinedRatingSummary, ConditionResponse, UserResponse } from "@/lib/models/api";
import type { HomeVM } from "@/lib/models/vm";

// Real-shaped fixtures (mirror the audit data) that flow through the real
// adapter, so dev previews + component tests exercise the same code path as live.

type Lvl = "strong" | "moderate" | "weak" | "missing";
const leg = (status: Lvl, evidence: string[] = ["Evidence on file"]) => ({ status, evidence, confidence: 0.9 });

function cond(
  id: number,
  name: string,
  system: string,
  code: string,
  rating: number,
  conf: number,
  dx: Lvl,
  is: Lvl,
  nx: Lvl,
  presumptiveBasis: string | null = null,
): ConditionResponse {
  return {
    id,
    name,
    bodySystem: system,
    vasrdCode: code,
    estimatedRating: rating,
    confidence: conf,
    presumptiveBasis,
    triadDiagnosis: leg(dx),
    triadInService: leg(is),
    triadNexus: leg(nx),
  };
}

const CONDITIONS: ConditionResponse[] = [
  cond(93, "PTSD", "Mental Disorders", "9411", 70, 0.95, "strong", "strong", "strong"),
  cond(94, "Asthma", "Respiratory System", "6602", 60, 0.95, "strong", "strong", "strong", "PACT Act"),
  cond(95, "Chronic Migraines", "Neurological", "8100", 50, 0.92, "strong", "strong", "strong"),
  cond(98, "GERD", "Digestive", "7346", 30, 0.8, "strong", "strong", "moderate", "Gulf War (FGID)"),
  cond(99, "Sleep Apnea", "Respiratory System", "6847", 50, 0.55, "strong", "weak", "missing"),
  cond(102, "Tinnitus", "Auditory", "6260", 10, 0.5, "moderate", "moderate", "moderate"),
];

// Gaps in the LIVE /api/claim/gaps shape (P0-1): pipeline-authored content
// mapped by the endpoint (label←title, why←description+" (VASRD: …)",
// suggest←how_to_get_it, impact←rating_impact, type humanized from tokens like
// "nexus_letter") plus the pass-through fields. Keep keys in lockstep with the
// endpoint — a drift here must fail CI, not render blank in production.
const GAPS: GapRawLive[] = [
  { condId: 99, condName: "Sleep Apnea", type: "Nexus letter", label: "Missing nexus letter", why: "You have a sleep study and in-service complaints, but no medical opinion linking them to service. (VASRD: 38 CFR 4.97 DC 6847, 50% criterion: requires use of breathing assistance device)", suggest: "Ask your treating pulmonologist for a nexus letter referencing the snoring complaints in your STR entries.", impact: "Confirms the nexus leg and supports the 50% CPAP criterion.", priority: "high", status: "open", index: 0, triadLeg: "nexus", targetRating: 50, estimatedTime: "2–4 weeks", estimatedCostUsd: 800 },
  { condId: 102, condName: "Tinnitus", type: "C&P exam request", label: "No audiology diagnosis on file", why: "VA requires a current diagnosis. Your records show ringing complaints but no audiologist statement. (VASRD: 38 CFR 4.87 DC 6260, 10% recurrent tinnitus)", suggest: "Request a VA audiology C&P exam, or provide a private audiologist's diagnosis letter.", impact: "Completes the diagnosis leg and secures the 10% rating.", priority: "high", status: "open", index: 0, triadLeg: "diagnosis", targetRating: 10, estimatedTime: "30 days", estimatedCostUsd: 0 },
  { condId: 99, condName: "Sleep Apnea", type: "Buddy statement", label: "In-service symptoms under-documented", why: "Only one mention in your STRs. A lay statement would strengthen this leg. (VASRD: 38 CFR 4.97 DC 6847)", suggest: "Upload a buddy statement (VA Form 21-10210) from a barracks-mate who witnessed snoring or apnea.", impact: "Strengthens the in-service leg from weak to moderate.", priority: "medium", status: "open", index: 1, triadLeg: "in_service", targetRating: 50, estimatedTime: "1–2 weeks", estimatedCostUsd: 0 },
  { condId: 98, condName: "GERD", type: "Presumptive documentation", label: "Confirm Gulf War presumptive", why: "Your DD-214 confirms SWA service; the presumptive applies once documented. (VASRD: 38 CFR 3.317 functional gastrointestinal disorders)", suggest: "Upload the DD-214 page showing your Southwest Asia deployment dates.", impact: "Establishes the presumptive nexus without a private opinion.", priority: "low", status: "open", index: 0, triadLeg: "nexus", targetRating: 30, estimatedTime: "same day", estimatedCostUsd: 0 },
];

// GET /claim/combined-rating fixtures in the pinned C1 shape — dev-preview
// numbers only (live figures are always server-computed).
const READY_SUMMARY: CombinedRatingSummary = {
  scope: "ready",
  combinedRating: 90,
  monthlyEstimate: 2297,
  ratesYear: 2026,
  notes: [],
  steps: ["70% + 60% -> 88%", "88% + 50% -> 94%", "94% rounds to 90%"],
  // Labeled sequential combine (task #185): Mental health 70 → Asthma 60 → Migraines 50 → round.
  combineSteps: [
    {
      label: "Mental health",
      rating: 70,
      pointsAdded: 70,
      combinedAfter: 70,
      remainingAfter: 30,
      absorbedMembers: ["Major Depressive Disorder"],
      rounding: false,
    },
    { label: "Asthma", rating: 60, pointsAdded: 18, combinedAfter: 88, remainingAfter: 12, absorbedMembers: [], rounding: false },
    { label: "Chronic Migraines", rating: 50, pointsAdded: 6, combinedAfter: 94, remainingAfter: 6, absorbedMembers: [], rounding: false },
    { label: "Rounded to nearest 10", rating: null, pointsAdded: 0, combinedAfter: 90, remainingAfter: 6, absorbedMembers: [], rounding: true },
  ],
  excludedCount: 1,
  inputs: [
    { conditionId: 93, name: "PTSD", rating: 70, counted: true, reason: null, group: "mental health" },
    {
      conditionId: 96,
      name: "Major Depressive Disorder",
      rating: 50,
      counted: false,
      reason: "pyramided-into:PTSD",
      group: "mental health",
    },
    { conditionId: 94, name: "Asthma", rating: 60, counted: true, reason: null, group: null },
    { conditionId: 95, name: "Chronic Migraines", rating: 50, counted: true, reason: null, group: null },
    { conditionId: 98, name: "GERD", rating: 30, counted: false, reason: "not-ready", group: null },
    { conditionId: 99, name: "Sleep Apnea", rating: 50, counted: false, reason: "not-ready", group: null },
    { conditionId: 102, name: "Tinnitus", rating: 10, counted: false, reason: "not-ready", group: null },
  ],
};
const ALL_SUMMARY: CombinedRatingSummary = {
  scope: "all",
  combinedRating: 100,
  monthlyEstimate: 3831,
  ratesYear: 2026,
  notes: [
    "GERD is rated inside your Gulf War FGID evaluation — VA won't pay it twice.",
  ],
  steps: ["70% + 60% -> 88%", "88% + 50% -> 94%", "94% + 50% -> 97%", "97% + 10% -> 97%", "97% rounds to 100%"],
  excludedCount: 1,
  inputs: [
    { conditionId: 93, name: "PTSD", rating: 70, counted: true, reason: null, group: "mental health" },
    {
      conditionId: 96,
      name: "Major Depressive Disorder",
      rating: 50,
      counted: false,
      reason: "pyramided-into:PTSD",
      group: "mental health",
    },
    { conditionId: 94, name: "Asthma", rating: 60, counted: true, reason: null, group: null },
    { conditionId: 95, name: "Chronic Migraines", rating: 50, counted: true, reason: null, group: null },
    { conditionId: 98, name: "GERD", rating: 30, counted: false, reason: "pyramided-into:Gulf War FGID", group: "gulf war fgid" },
    { conditionId: 99, name: "Sleep Apnea", rating: 50, counted: true, reason: null, group: null },
    { conditionId: 102, name: "Tinnitus", rating: 10, counted: true, reason: null, group: null },
  ],
};

const me = (overrides: Partial<UserResponse> = {}): UserResponse => ({
  id: 10,
  email: "d.griff@example.com",
  name: "D. Griff",
  // The home greeting uses the first name token; "D." alone reads wrong, so the
  // fixture sets the preferred name the way a real veteran would.
  preferredName: "Griff",
  role: "veteran",
  hasProfile: true,
  activeClaim: { id: 99, status: "ANALYZED", conditionCount: CONDITIONS.length, analysisProgressPct: 100 },
  ...overrides,
});

export const populatedHomeVM: HomeVM = composeHomeVM({
  me: me(),
  conditions: CONDITIONS,
  gaps: GAPS,
  usage: { atLimit: false, percentUsed: 30 },
  subState: "free",
  ratingReady: READY_SUMMARY,
  ratingAll: ALL_SUMMARY,
});

export const emptyHomeVM: HomeVM = composeHomeVM({
  me: me({ hasProfile: false, activeClaim: null }),
  conditions: [],
  gaps: [],
  usage: null,
  subState: "free",
  ratingReady: null,
  ratingAll: null,
});

export const analyzingHomeVM: HomeVM = composeHomeVM({
  me: me({ activeClaim: { id: 99, status: "SYNTHESIZING", analysisStage: "synthesis", analysisProgressPct: 45, conditionCount: 0 } }),
  conditions: [],
  gaps: GAPS,
  usage: null,
  // Pro: a free user with a pending pipeline is analysisLocked (the honest free
  // card), never the analyzing screen — this fixture must exercise the latter.
  subState: "pro",
  ratingReady: null,
  ratingAll: null,
});

/**
 * Fetch failed for a populated user: conditions exist but the combined-rating
 * reads errored, so the hero & pay card must render "Estimate unavailable
 * right now", never $0.
 */
export const estimateErrorHomeVM: HomeVM = composeHomeVM({
  me: me(),
  conditions: CONDITIONS,
  gaps: GAPS,
  usage: { atLimit: false, percentUsed: 30 },
  subState: "free",
  ratingReady: null,
  ratingAll: null,
  estimateUnavailable: true,
});

const LONG = "Chronic Adjustment Disorder with Mixed Anxiety and Depressed Mood, Secondary";
export const overflowHomeVM: HomeVM = composeHomeVM({
  me: me(),
  conditions: [
    cond(1, LONG, "Mental Disorders", "9440", 100, 0.9, "strong", "strong", "strong", "Extremely Long Presumptive Basis Name PACT Act 2022 Burn Pit"),
    ...CONDITIONS,
  ],
  gaps: GAPS,
  usage: { atLimit: false, percentUsed: 95 },
  subState: "pro",
  ratingReady: { ...READY_SUMMARY, combinedRating: 100, monthlyEstimate: 3831 },
  ratingAll: ALL_SUMMARY,
});

export const HOME_FIXTURES: Record<string, HomeVM> = {
  populated: populatedHomeVM,
  empty: emptyHomeVM,
  analyzing: analyzingHomeVM,
  overflow: overflowHomeVM,
  "estimate-error": estimateErrorHomeVM,
};
