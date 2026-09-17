# Plan (deferred): Freemium hook — free analysis to drive Pro conversion

**Status:** Parked for later consideration (decided 2026-06-21).
**Current behavior shipped instead (Option B):** AI is fully Pro-gated. A free user who
uploads evidence now sees an "Upgrade to run AI analysis" state on Home (the
`analysisLocked` VM flag) instead of a forever-5% spinner. This document is the
alternative to revisit when we want a stronger free→paid funnel.

---

## Goal

Give free users enough of the analysis to feel the product's value — ideally seeing
their **conditions scored on the VA triad** — then charge for the deeper, more
expensive AI work (gap analysis, scenarios, Ask AI). The thesis: showing a veteran
"here are your 6 conditions and where each stands" is a far stronger conversion hook
than an upgrade wall, and it serves the educational mission.

## Current architecture (what gates what today)

The pipeline runs in three LLM stages (post the 2026-06-10 agentic redesign):

1. **Extraction** — `SinglePassExtractionService` → `DocFacts`/atoms. Turns each
   uploaded document into structured facts. One cheap pass per document.
2. **Synthesis** — `ConditionIdentificationAgent` / `ConditionGenerationService`.
   Turns the fact corpus into **conditions, each scored on the VA triad**
   (Diagnosis / In-service / Nexus). This is the multi-agent, more expensive stage.
3. **Gap analysis** — gaps + prioritized "Do this next" steps.

**The single gate today:** `AnalysisScheduler.advanceClaim()` early-returns when
`!user.hasActiveSubscription()` (`User.hasActiveSubscription()` requires a future
`subscriptionExpiresAt`). That one check gates **all three stages at once**, so a free
user gets nothing. `IntakeController` stamps `analysisStage=EXTRACTING` /
`analysisProgressPct=5` synchronously on upload regardless of subscription, which is
what produced the stuck-at-5% UX before Option B.

### ⚠️ Key boundary decision (must be settled before building)

"Free extraction" alone does **not** show conditions — conditions/triad are a
**synthesis** output. So the freemium boundary is really a choice between:

| Option | Free tier gets | Pro tier gets | Hook strength | LLM cost exposure |
|---|---|---|---|---|
| **A1 — Free through synthesis** | Extraction **+ synthesis** → conditions visible, each triad-scored | Gap analysis, scenarios, Ask AI, re-runs | **Strong** (sees conditions) | **Higher** (synthesis is the expensive stage) |
| **A2 — Free extraction only** | Raw extracted facts / a documents view | Synthesis (conditions+triad), gap, scenarios, Ask AI | **Weak** (no conditions) | **Low** (one cheap pass/doc) |

**Recommendation: A1.** A2's free experience (raw facts, no conditions) is barely more
compelling than today's upgrade wall, so it won't move conversion. A1 delivers the
actual hook. The cost is manageable with the guards below. If cost modeling later
shows A1 is too expensive at scale, fall back to A2 or a hybrid (free synthesis for the
**first N conditions / first claim only**).

## Proposed change (Option A1)

### Backend
- **Split the gate in `AnalysisScheduler.advanceClaim()`** so extraction **and**
  synthesis run for everyone, but **gap analysis (+ scenarios/Ask AI) require an
  active subscription.** Concretely: let the state machine advance through
  `EXTRACTING → SYNTHESIZING → (stop here for free)`; only enter the gap stage when
  `hasActiveSubscription()`.
- **Abuse / cost guard (critical).** Free synthesis is the cost risk. Gate it behind
  `UsageService`/`usageGuard` with an explicit **free-tier cap** (e.g. 1 claim and/or
  N documents and/or one synthesis run per calendar month; re-runs are Pro). Mirror
  the existing calendar-month usage cap + `402` enforcement already used elsewhere.
  Log/measure free-tier LLM spend before and after rollout.
- **Cost controls already in place to lean on:** prompt caching, batch lane, the
  delta/supersede machinery (don't re-synthesize unchanged scope), and the
  `anthropic-batch` routing. Free synthesis should use the cheapest viable route.
- **Stop stamping a misleading stage** for whatever ends up gated: only set a
  progress/stage the pipeline will actually advance, so the UI never shows a spinner
  for work that won't happen (the root cause of the original 5% bug).

### Web / UI
- Free user post-synthesis sees the **real Home**: hero, conditions list, triad
  scoring, EvidenceHealth. The **gap-derived bits are locked**: "Do this next"
  steps, scenarios, Ask AI, and "Why this number?" deep dives show an upgrade
  affordance instead of content.
- Replace/augment the `analysisLocked` state: it becomes "synthesis running" (a real
  spinner, because it now actually runs for free users) and then resolves to the
  conditions view. The lock moves to the **gap/scenario/Ask-AI** surfaces.
- Combined-rating/pay estimate: decide whether the headline estimate is free (strong
  hook) or Pro. Leaning free (it's computed from synthesis output + VA math, no extra
  LLM call), with gap-driven "how to increase it" as the paid upsell.

### Pricing copy
- Update the `/upgrade` value prop + `describePlans()` feature list: Pro becomes
  "**gap analysis, scenario modeling, Ask AI, and unlimited re-analysis**" rather than
  "AI extraction, synthesis & gap analysis" (since synthesis would now be free).

## Risks & open questions
- **Cost at scale** — the make-or-break. Need a spend model: (free signups/mo) ×
  (avg docs) × (synthesis cost/claim) with caching/batch discounts. Set the free cap
  from that number, not vibes.
- **Abuse** — bot signups burning synthesis budget. Mitigate with the passwordless
  auth we now have (phone/email verified), per-account caps, and rate limiting.
- **Value perception** — if free gives "the answer," does Pro still convert? Pro must
  own the *actionable* layer (how to close gaps, model scenarios, ask questions) — the
  part veterans actually pay for. Validate the boundary with a small cohort first.
- **Downgrade behavior** — a lapsed Pro user keeps seeing past conditions (already true
  today); confirm gap/scenario content re-locks cleanly.

## Effort estimate
- Backend gate split + free-tier usage cap + tests: ~1–1.5 days.
- Web: relocate the lock from "whole analysis" to "gap/scenario/Ask-AI" surfaces,
  adjust Home states + tests: ~1 day.
- Pricing copy + `describePlans()` + `/upgrade`: ~0.5 day.
- Cost modeling + a guarded staged rollout (feature flag, watch spend): ~0.5 day +
  monitoring.
- **Total ≈ 3–4 days**, gated on the cost model saying A1 is affordable.

## Related
- `AnalysisScheduler.java`, `IntakeController.java`, `PipelineService.java`,
  `User.hasActiveSubscription()`, `UsageService`, `StripeService.describePlans()`.
- `web/src/lib/adapters/home.ts` (the `analysisLocked` flag added in Option B),
  `web/src/components/home/HomeView.tsx`, `web/src/lib/api/endpoints-core.ts`.
- Memory: agentic-analysis-redesign (pipeline stages + cost machinery),
  iap-rollout (pricing).
