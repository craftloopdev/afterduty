# VA Claim Path — Product Review & Incremental Roadmap

> **EXECUTION STATUS (2026-07-02): ALL PHASES IMPLEMENTED.** Commits 708f911 (A),
> 8c950f4 (B1/B3), 63dad42 (B2+C), 2752175 (D/E/F), 4e10639 (G backend), 078881e
> (G web). Tests 606→781 backend, 164→519 web. Deployed through va-claim-api-00135+
> / va-claim-web-next-00022+. Flags awaiting owner gates: PER_CONDITION_FINGERPRINT
> (live eval + 1wk metrics — §5 step 3), FREE_ANALYSIS_TIER=a1 (after fingerprints —
> §6.3), GAP_WHATIF_ENABLED (needs a renderer — P1-9). Deferred: P2-3's account-
> deletion atom-dirtying (lives in UserDeletionService, protected local changes).

**Date:** 2026-07-01
**Scope:** Full-product review (8 journey audits + 4 UX/content/a11y audits), adversarial verification of the top 15 claims, 3 competing incremental-analysis designs judged by 2 independent judges, and the open free-tier decision.
**Owner's stated goal (today):** "I'm not making this to make money, just help veterans." This report optimizes for veteran benefit within sustainable cost.

---

## 1. Executive summary

**What's strong.** The hard, expensive engineering is done and it is good. The 2026-06-10 pipeline (increments 0–8) shipped: single-pass extraction with content-addressed dedupe, atomic generation flips, prompt caching, the $4/mo UsageGuard with an honest ledger, deterministic VA math (§4.25/§4.26, LLMs never touch dollars), grounded chat with real eCFR citations, and per-claim retrieval isolation enforced in SQL. The product's *honesty patterns* are unusually mature: tri-state subscription handling that never upsells during an outage, `estimateUnavailable` instead of fake $0s, `analysisLocked` instead of a forever-spinner for free users, XSS-safe markdown, genuinely complete account deletion. Do not churn any of this (§2).

**What's broken.** The connective tissue between that engine and the veteran. Ten P0s, **all adversarially verified against the code (several confirmed against the live Cloud Run service)**: the paid gap guidance — the exact thing the paywall sells — renders as blank text because the API reads JSON keys no writer has *ever* produced; a missing 4-line multipart config means any upload over 1MB fails with a generic error **in production** (a phone photo of a DD-214 is 2–8MB — the first action of every new veteran fails); documents show a green "Processed" badge before (or without ever) being read; analysis progress is a fake 5% bar with no polling and invisible failure states; a doc uploaded while extraction is in flight is *silently excluded from the analysis*; re-analysis after new evidence is invisible, then briefly shows a false "You're all caught up"; every condition link 404s on the iOS build; the share flow dead-ends because no viewer UI exists; "ready to file" copy misstates filing rights in a way that can cost real back pay; and the free tier delivers zero mission value while every prerequisite its own plan doc demanded is now shipped.

**One-paragraph recommendation.** Spend the next ~5 days on a "truth week" fixing the verified P0s — almost all are S-sized config/mapping/copy fixes with outsized trust impact, and the gap-key fix alone turns on the product's core paid value. Then build the judges' consensus incremental architecture in four flag-gated steps (~3 weeks): deterministic diff-at-flip written to the already-built-but-unwired `Notification` entity ("here's what your nexus letter changed"), per-condition dirty scoping via atom attribution at identify (cuts incremental cost ~$1.2→~$0.55/doc), durable gap-status state, and static education content. Explicitly do **not** put the Claude Agent SDK or Managed Agents in the pipeline — all three proposals and both judges converged on this. Finally, ship freemium Option A1 (free extraction + synthesis; gaps/chat/scenarios stay Pro) under a hard `free-limit-cents` cap: ~$100–150/mo at 100 active free veterans, which is the not-for-profit endgame the preceding steps fund.

---

## 2. What already works well — do not churn this

Condensed from ~90 praise findings across all audits. These are deliberate, often tested, patterns; any refactor should preserve them.

**Honesty machinery (the app's differentiator):**
- `analysisLocked` vs `isAnalyzing` split with regression tests — free users get a truthful upgrade path, never a forever-5% spinner (`web/src/lib/adapters/home.ts:51-62`, `home.test.ts:106-122`).
- `estimateUnavailable` everywhere money/ratings appear — "Estimate unavailable right now" with `role=status`, never a fake $0/0% (`HomeHero.tsx:40-52`, `PayBreakdown.tsx:25-37`, `ScenariosPanel.tsx:54-63`).
- Tri-state subscription (pro/free/error) threaded through shell, paywall, and native — a billing outage never flashes an upsell at a paying veteran or baits a double charge (`Sidebar.tsx:26-28`, `StripePaywall.tsx:84-92,179-195`, native RC-oracle resolution). Checkout success is only asserted after the backend confirms (`StripePaywall.tsx:50-63,139-148`).
- Disclaimer system layered at decision moments: TopBar pill, does/doesn't `DisclaimerModal` ("does not replace a VSO or accredited attorney"), LegalFooter at the PII handover, VA-math/no-dependents footnotes under every estimate.
- `toTriadLevel` fails safe — unknown statuses collapse to "missing," so data drift under-promises, never over-promises (`adapters/triad.ts:9-15`).

**Cost discipline (what makes a generous free tier affordable):** prompt caching with a cache-miss tripwire, batch lanes, the no-new-facts $0 short-circuit, SHA-256 upload dedupe with a friendly 409, the $4/mo UsageGuard with deferral + auto-resume ("Paused — plan limit reached. Resumes <date>"), and chat spend booked to the AiCallLog ledger.

**Pipeline architecture:** atomic generation flips (PENDING → activate+supersede in one transaction — the veteran never sees a half-built analysis), per-doc failure isolation with plain-language abstention, self-healing failure states, and identity-fingerprint carry-forward that re-anchors what-if scenario references.

**Server-authoritative math:** all dollars from `VaMathService` (correct §4.25 combination, VA rounding, real §4.26 bilateral). The "LLMs never do math" rule held. `PyramidingRules` is thoughtful, documented groundwork — the fix (§4/§6) is wiring, not new engineering.

**Prompts that protect veterans:** the RatingAgent's "If evidence supports a higher rating, assign it. Do not under-rate."; the gap analyzer's concrete who-to-ask/what-form/cost/time guidance with an explicit "presumptive nexus gaps are usually not real" guard (`EvidenceGapAnalyzer.java:60-93`); the adversarial RVSR validator encoding real rater heuristics (`GapValidationAgent.java:64-74`); chat's never-invent-a-citation rules with eCFR freshness stamps and VSO referrals.

**Accessibility & mobile craft:** ChatView's `role=log` streaming a11y (deltas hidden until the canonical bubble announces once), global `prefers-reduced-motion` kill-switch, server-applied text-scale with no flash, 44px button floor, real mobile card adaptations, safe-area work scoped to native, OTP inputs with `one-time-code` autofill, and the branded offline/error split on native cold launches.

**Trust & safety plumbing:** per-claim retrieval isolation in both arms of hybrid search (`HybridRetrievalService.java:66-72`), XSS-safe markdown with scheme allowlist and heading demotion, 32-byte SecureRandom single-use share tokens, `ClaimAccessService` as a single authz chokepoint with logged deny reasons, and Apple-5.1.1(v)-grade account deletion (Stripe cancel, GCS, FK-safe DB order, Firebase credential).

---

## 3. Consolidated findings by severity

Verification legend:
- **VERIFIED** — adversarially re-checked against code (and where noted, the live deployment); claim held.
- **VERIFIED w/ CORRECTION** — held, with the stated correction applied below.
- **TRIAGE** — verified during triage (grep/read-level) but not adversarially re-checked. None of the 15 adversarially checked claims were refuted.

### P0 — fix before anything else

#### P0-1. Gap guidance renders blank: API reads keys no writer ever produced — **VERIFIED w/ CORRECTION**
The pipeline persists gaps keyed `title/description/rating_impact/how_to_get_it/estimated_time/estimated_cost_usd` (`EvidenceGapAnalyzer.java:77-92`; `GapValidationAgent.java:235-253` preserves the same keys). `GET /api/claim/gaps` reads `label/why/suggest/impact` via `getOrDefault(…, "")` (`IntakeController.java:450-460`). So `StepVM.gap/why/suggest/impact` are empty strings: DoThisNext's hero card, every Steps row, both step modals, and the condition-detail step bar render blank text plus raw type tokens ("PTSD · nexus_letter leg"). Fixtures and adapter tests use the phantom legacy shape (`fixtures/home.ts:47-50`, `gaps.test.ts:17-22`), so `/dev` previews and CI look perfect while production is blank. The richest veteran-facing content in the product — who to ask, what form, cost, time, VASRD criterion — is generated, RVSR-validated, paid for, and discarded at the API boundary. This is exactly what the paywall sells.
**Corrections applied:** (a) It is not "drift" — git archaeology shows the reader's keys *never* matched any writer in repo history; even the legacy Gemini path wrote `{type, description, priority, impact}`. The reader was written against the web fixture shape. (b) Not *all* guidance is blank: `priority` (validator-overwritten), `type`, and condition name survive, so steps render sorted with names — just with empty title/why/suggest/impact text.
**Fix (S):** In `getGaps`, map `label←title`, `why←description(+vasrd_reference)`, `suggest←how_to_get_it`, `impact←rating_impact`; pass through `status`, gap index, `triad_leg`, `target_rating`, `estimated_time`, `estimated_cost_usd`; humanize type tokens. Replace fixtures/tests with the LIVE pipeline shape so drift fails CI; add a contract test feeding a pipeline-shaped gap through the endpoint asserting non-empty StepVM fields. **Land before any paywall/free-tier change.**

#### P0-2. 1MB upload cap in production: no multipart config, no exception handler — **VERIFIED (live prod confirmed) w/ CORRECTION**
Zero `spring.servlet.multipart.*` config in any `application*.yml` or Java config → Spring Boot's 1MB default applies; the in-code 50MB check (`IntakeController.java:102`) is unreachable dead code for multipart files >1MB; `RestExceptionHandler` has no `MaxUploadSizeExceededException` handler, so the failure surfaces as a generic error UploadCard renders as "Upload failed. Please try again." — an infinite retry loop. **Checked against the deployed `va-claim-api` Cloud Run service: none of its 33 env vars override multipart settings — this is live in production.** Phone photos of a DD-214 run 2–8MB; scanned records 5–50MB. Uploading is the free tier's entire product and the anxious first-time veteran's first action.
**Corrections applied:** the service runs HTTP/1, so Cloud Run's 32MB request cap exists but Spring's 1MB binds first; after the fix, ~32MB becomes the effective ceiling unless h2c is enabled. A legacy base64 JSON endpoint on the same path bypasses multipart limits but no current client uses it.
**Fix (S):** `spring.servlet.multipart.max-file-size: 50MB` / `max-request-size: 55MB`; add a `MaxUploadSizeExceededException` handler returning 413 with the actual limit (UploadCard's 413 branch already speaks human); client-side pre-check; integration test with a >1MB fixture; decide the honest ceiling vs Cloud Run's 32MB (enable h2c or say ~30MB in copy).

#### P0-3. Green "Processed" badge before (or without ever) being read — **VERIFIED w/ CORRECTION**
`PipelineService.processEvidence:97` sets `processingStatus="processed"` at upload, before extraction. The adapter maps it to a green done badge (`evidence.ts:26-29`). For free users, `AnalysisScheduler.advanceClaim:171` returns before anything runs, so **every free user's documents show "Processed" forever** while Home simultaneously says "Upgrade to run AI analysis" — the veteran concludes the app already read their records, then sees "No conditions yet," or concludes paying is pointless. Verified exhaustively: no webhook, nightly job, or reset path ever corrects it.
**Correction applied:** the premature green applies to *all* users (Pro users see it during the minutes-long real run; a later failure flips green→red). Side effect: since nothing sits in pending/processing during the real run, the scheduler's extraction-in-flight guard is inert.
**Fix (S):** write `queued` at submit, `processing` at job submit, `processed` only in `parseSinglePassResults`. Adapter: queued → neutral "Uploaded"; free users → "Stored — AI analysis is a Pro feature" so Documents agrees with Home. This also restores the in-flight guard for free.

#### P0-4. Fake analysis progress: frozen 5%, no polling, invisible failure and zero-conditions states — **VERIFIED w/ CORRECTION**
`analysisProgressPct` is written exactly at upload (5) and by the legacy admin-only `runFullPipeline`; the production scheduler path never updates or clears stage/pct (`AnalysisScheduler.java:321-393`). Home is a one-shot RSC with zero polling — a veteran who just paid watches "analyzing — 5%" frozen for the 5–15+ min pipeline until manual reload. `markSynthesisFailed` sets status=ERROR + message backend-side, but `composeHomeVM` reads neither — failures render as the same eternal spinner. A legitimate zero-conditions completion leaves `pipelinePending` true forever. Native is worse: no reload chrome, mount-only fetch, no resume listener. The backend already exposes everything needed via the **unused** `GET /api/claim/jobs` endpoint (`IntakeController.java:737-810`, zero web references).
**Correction applied:** in the common success case (≥1 condition), the spinner does clear on the next *manual* reload via the condition count — the failure and zero-conditions cases never clear, and nothing ever updates live.
**Fix (M):** drop the fake percent. Make `AnalyzingState` a client component polling `/api/claim/jobs` (~10–15s while active): indeterminate bar, honest "usually takes 5–15 minutes" copy, per-stage checklist, `router.refresh()` when conditions land. Scheduler terminal paths clear `analysisStage`; render `claim.status==ERROR` and a terminal zero-conditions state. Capacitor resume listener + poll on native.

#### P0-5. Incremental re-analysis is invisible, then numbers shift silently — with a false "You're all caught up" window — **VERIFIED**
The only analyzing UI requires `conds.length === 0` (`home.ts:52-55`); a returning veteran always has conditions, so after uploading a nexus letter they see the normal dashboard with stale numbers for the whole re-run, then ratings/triads/pay change with zero acknowledgment. Worse: on any new-fact upload every condition goes dirty (corpus-wide fingerprint), the new generation activates with `gaps=null`, and `GET /claim/gaps` skips null-gap conditions (`IntakeController.java:448` — literally `if (gs == null) continue;`) — so for minutes the Steps page renders a checkmark "You're all caught up / No open gaps." A veteran checking 10 minutes after uploading can reasonably conclude their claim is evidence-complete — a materially false state that later silently reverts. Verified end-to-end: the flip precedes gap writes by at least one 15s poll cycle plus LLM latency, and no in-progress signal reaches any client.
**Fix (M):** slim AppShell client component polling `/api/claim/jobs` (only while active or just after upload, armed by UploadCard's success): persistent "Analyzing your new evidence…" banner, `router.refresh()` on completion so new numbers arrive *with* acknowledgment. Report `gap_analysis_pending` when any active condition has null gaps; render "Re-checking your next steps…" instead of all-caught-up. This is the keystone UX gap of the shipped incremental architecture — §5 builds the full answer.

#### P0-6. A document uploaded mid-extraction is silently excluded from the analysis — **VERIFIED (worse than claimed)**
The only arming path sets `extractionState="NONE"` when currently null (`PipelineService.java:106-108`); `transitionTo(COMPLETE)` nulls it (`ExtractionStateMachine.java:579`); the scheduler drives extraction only when non-null; grep confirms no other re-arm. Upload doc B 30–180s after doc A and B joins no stage; when A's stage completes the machine goes quiet and B is never extracted. All three adversarial escape hatches checked and closed: single-pass mode has exactly one stage (no re-query), `extract_key` staleness is only consulted from state NONE, and `synthesisNeeded`'s only reader is dead code. **Adversarial finding made it worse:** the stranded doc is already marked "processed" (P0-3), so synthesis runs *without its facts* and presents a completed analysis silently missing that document — not a visibly stuck one. "Upload the nexus letter, then remember the C&P results a minute later" is exactly the target journey.
**Correction applied:** re-arm happens on any future `processEvidence` (new upload, reprocess endpoint, usage reset), not only uploads; the total strand is specific to the default single-pass mode.
**Fix (S):** in `transitionTo(COMPLETE)`, if evidence rows exist with null/stale `extract_key` and non-error status, set state back to `"NONE"` instead of null. Regression test: upload doc B mid-stage, assert extraction without a third upload.

#### P0-7. Free tier delivers zero mission value; its own deferral trigger has been met — **VERIFIED w/ CORRECTION**
`AnalysisScheduler.advanceClaim:171` — `if (!hasActiveSubscription(claim)) return;` — blocks extraction, synthesis, AND gap analysis (verified: this scheduler is extraction's sole driver). A free veteran gets a lock screen (hiding even upload guidance and the working docs-only ShareCard), empty Conditions/Steps, locked chat, and permanent fake "Processed" badges. The freemium plan (`docs/architecture/freemium-free-extraction-plan.md:47`) recommends Option A1 (free through synthesis), deferred pending a cost model. Anti-abuse prerequisites are all shipped (OTP accounts, $4 UsageGuard with deferral, dedupe, no-new-facts short-circuit). Adjacent incoherences: quick-add text statements are Pro-gated (402) while file uploads are explicitly free — same pipeline, zero cost difference; and the locked Home hides ShareCard although docs-sharing works free (`VIEW_DOCS` never requires Pro).
**Correction applied (important):** the "~$1.05/claim free slice" figure is **not in the plan doc or anywhere in the repo** — the doc contains no cost figures and explicitly defers to a not-yet-built spend model. What IS real: the 2026-06-12 eval measured **$0.857 full-pipeline** for one golden case (baseline run $0.035; run verdict was REGRESSION — treat as an upper-ish anchor, not a validated average), and `usage.limit-cents: 400` is live. The ~$1.00–1.10 A1 slice below (§6) is this review's arithmetic, labeled as such.
**Fix:** full decision in §6. Immediately and independent of the decision (S): ungate quick-add; show free users their real state on Home ("12 documents stored" + docs-only ShareCard).

#### P0-8. Share journey dead-ends: backend viewer support is fully wired, no viewer UI exists — **VERIFIED**
`FetchOpts.viewAs` exists in `transport.ts:19,42` with zero callers in all of `web/src`; `UserResponse.sharedProfiles` is consumed nowhere. After accepting, the rep's CTA goes to `/`, where a rep with no claim of their own gets "Let's build your claim — Upload your DD-214…" onboarding for *their own nonexistent claim*. The veteran sees the share as "Active" and believes their rep is reviewing. Every promise on the Share screen is currently unfulfillable, and veterans are actively encouraged to send invites that lead nowhere. Related: `VIEW_ANALYSIS` requires the claim *owner* to keep Pro (`ClaimAccessService.java:162-176`), disclosed nowhere.
**Fix:** immediately (S) gate/label the Share screen "coming soon" for analysis-sharing (docs sharing works — keep it). Then (L) build minimal viewer mode: read `me.sharedProfiles` in the layout, claim-switcher banner, thread `viewAs` through loaders, hide ungrated mutations, surface the owner-Pro dependency. **Must land after the P1 permission tightening (P1-19) — the API currently over-grants.**

#### P0-9. iOS launch blocker: every condition link targets the web-only route; `condHref()` has zero production callers — **VERIFIED (empirically, native export built)**
The native export excludes `(app)/conditions/[id]` (`native-export.mjs:50`) and provides `/conditions/detail?id=` via `condHref()` (`platform.ts:15-17`) — referenced only by its own tests. `ConditionsList.tsx:85,121`, `StepsPanel.tsx:153`, `DoThisNext.tsx:134` all hardcode `/conditions/${id}`. The export was actually built during verification: no per-id routes exist in `out/`; every condition tap on iOS lands on the 404 fallback. Playwright runs web-only, so nothing catches it.
**Fix (S):** replace the four hardcoded templates with `condHref(id)`; ESLint `no-restricted-syntax` ban on the literal; a NEXT_PUBLIC_NATIVE=1 smoke test that clicks a condition card.

#### P0-10. "Ready to file" / "become claimable" misstates filing rights — can cost months of back pay — **VERIFIED**
`HomeHero.tsx:90-92` ("N of N conditions ready to file"), `ConditionsPreview.tsx:94` ("need work to become claimable"), `endpoints-core.ts:317` ("File your N ready conditions") — all derived from a pure evidence heuristic (`condition.ts:25`, all-legs-strong). Legally every condition is claimable now: VA has a duty to assist, and filing (or an Intent to File) sets the effective date for back pay. Verified: zero references to ITF/effective date/Form 21-0966 anywhere in `web/src`. A veteran who trusts this copy may sit on a claim for months gathering a nexus letter VA would have obtained — permanently losing compensation. Kept P0 despite being copy: the harm is concrete, monetary, irreversible; the fix is an afternoon.
**Fix (S):** reframe as evidence strength: "N of N conditions have all three evidence legs" / "N conditions have evidence gaps you can close" / "Your N strongest conditions today." Add one teaching line: "You can file at any time — filing (or an Intent to File) locks in your effective date while you strengthen evidence."

### P1 — fix soon (grouped; sizes and verification status inline)

**Accuracy & money**

- **P1-1. Combined rating ignores pyramiding/bilateral/tinnitus-cap — VERIFIED w/ CORRECTION (M).** `POST /scenarios/calculate` takes a raw `List<Integer>` with null bilateral pairs (`ConditionController.java:262-265`); the web sends every rating>0 including pyramid-absorbed rows. Correction: `ClaudeSynthesisService:287-293` *does* run `PyramidingRules.plan` through the correct calc — but both production callers discard the result. PTSD 70% + absorbed anxiety 50% combine (inflated); two knees never get +10% bilateral (understated). Fix: `GET /api/claim/combined-rating` that runs `PyramidingRules.plan` server-side and returns result + human-readable notes ("Anxiety is rated inside your PTSD evaluation — VA won't pay it twice"); point hero/steps at it.
- **P1-2. Presumptive conditions told to buy nexus letters — VERIFIED (S).** `ready` requires nx strong (`condition.ts:25`); the identification prompt has no presumption→nexus rule, while the gap analyzer's own prompt forbids recommending nexus letters on established presumptives. The weakest-leg callout can steer PACT-Act veterans toward $800–2,000 letters they don't need. Fix both sides: prompt rule ("if is_presumptive applies, score triad_nexus STRONG with evidence [Presumptive under <basis>]") + defensive adapter rendering "Covered by presumption"; never show a nexus callout on a presumptive condition.
- **P1-3. Rate table is 2024 in mid-2026 (~5% low) while claiming "current VA rates" — TRIAGE (S).** `VaMathService` javadoc says 2025, `BASE_RATES` matches 2024 (100% = $3,737.85 vs ~$3,946 in 2026). Update to 2026 + `RATES_YEAR` constant rendered as "at 2026 VA rates" + a test that fails on COLA rot.
- **P1-4. Hero assumes every condition granted; Steps uses ready-only — the two flagship numbers disagree with no reconciliation; "unlock extra pay" framing — TRIAGE (M).** `endpoints-core.ts:154-171` vs `:288-325`. Show both explicitly ("Ready today: 40% · $755/mo · If all 9 are granted: 80% · $1,995/mo"); one consistent hedge under every headline number ("Our estimate from your evidence — VA assigns the actual rating after review and exams"); reword promissory copy; drop the dead SMC/"Potential total" rows (`smcAvailable` hardcoded 0) or wire them; surface VaMath's discarded step-by-step as "How VA math got 70%".
- **P1-5. Condition adapter defects — TRIAGE (S).** null→0 collapse renders true 0% ratings (a real, valuable VA outcome) as "Not yet ratable"; missing confidence renders as an alarming "0% confidence"; `weakestLeg` picks the *first* non-strong leg, not the weakest (`condition.ts:14-44`). Make rating/confidence nullable end-to-end; "0% — still worth filing" explainer; rank missing<partial<strong.

**Incremental-architecture UX (feeds §5)**

- **P1-6. Gap resolutions don't stick — VERIFIED (M).** `/claim/gaps` neither filters nor serializes `status` (chat-resolved gaps stay "open" everywhere) while `/jobs` DOES filter — the app disagrees with itself; dirty re-runs wholesale-replace gaps (statuses/notes destroyed; dismissals not fed to the prompt, so "doesn't apply to me" gaps resurrect); no REST/UI affordance to resolve at all. Fix: status through `/gaps` with a single shared open-gap predicate; durable `user_gap_state` keyed by (identityFingerprint, gap type, triad_leg) re-applied after runs and injected as "do not re-propose"; `PATCH /api/claim/gaps/{condId}/{index}/status` + "Mark done / Doesn't apply" buttons.
- **P1-7. Condition IDs churn every generation; stale links 404 on web, eternal skeleton on native — VERIFIED w/ CORRECTION (M).** `superseded_by` is persisted but `ConditionResponse` never exposes it; detail lookup is find-by-id over active-only. Correction: `ConditionController.redirectToActive` *does* follow the chain — but only for scenario endpoints; there is no per-condition endpoint at all. `LoaderBoundary.tsx:50` treats resolved-null as still-loading, making native's not-found branch dead code. Fix: single-condition endpoint following the chain (308 to successor); web retries with successor; LoaderBoundary settled-flag.
- **P1-8. No "what changed" signal anywhere; purpose-built `Notification` entity has zero writers — TRIAGE (M).** The flip transaction is the natural diff point. §5's keystone deliverable.
- **P1-9. What-if scenarios generated, validated, paid for on every run — rendered nowhere; prompt has the LLM doing dollar math — TRIAGE (S now).** Immediately: disable the `gap_whatif` stage behind its flag until a renderer exists (stops recurring spend). Later: render on ConditionDetail with `monthly_delta` recomputed deterministically.

**Chat / Ask AI**

- **P1-10. Paying user at the $4 cap gets a silent chat failure loop — VERIFIED (M).** UsageLimit → 402 `USAGE_LIMIT_REACHED`; BFF maps any 402 → `subscription_required`; ChatView suppresses that banner for pro users — red bubble, Retry fails identically until month reset. Also: Vertex brownouts stall 90s under "Thinking…" then say "agent error" (the perfect `rate_limited` copy exists but is unreachable); `atUsageLimit` is computed but no component renders it; paused copy shows raw ISO dates. Fix: distinguish the cap on the wire (429 or body-code inspection) → "You've reached this month's AI limit — chat resumes <date>"; classify backoff as rate_limited with a "waiting for capacity…" status; Home banner when paused; humanize dates. Consider raising the 400¢ cap post-cost-cut.
- **P1-11. Ask AI shows the Pro upsell to PAYING users on subscription-check errors — TRIAGE (S).** `ask/page.tsx:13-18` and `NativeAsk.tsx:17` collapse outages to null via `getSubscription()` against the app's own documented tri-state convention. Switch to `getSubscriptionResult()`; never upsell on error.
- **P1-12. Chat can't see the rationale/triad/gap detail the UI shows, and has no pipeline-status tool — TRIAGE (M).** `renderConditions` omits ratingRationale, per-leg triad, and gap how-tos — asked "why is my knee only 10%?" the model plausibly contradicts the "Why this number?" panel; asked "my new doc changed nothing, why?" no tool can answer. Fix: render those fields into the deterministic claim-state block (cache-stable); add read-only `get_pipeline_status`.
- **P1-13. Streamed text can vanish: only the LAST iteration's text is persisted — TRIAGE (S).** `handleStreaming` rebuilds `finalText` per iteration while forwarding deltas from every iteration. Accumulate across iterations.
- **P1-14. Chat mutating tools: direct rating writes, hard deletes with no undo, silent no-ops on superseded rows, deletions resurrect on next upload — TRIAGE (M).** Remove `estimated_rating` from the update schema (mark dirty instead); soft-delete with undo; reject superseded targets self-correctingly; durable suppression record for deleted conditions.
- **P1-15. No Veterans Crisis Line or advice-boundary block in the system prompt — TRIAGE (S).** For a PTSD/MST-adjacent chat this is a product-level obligation. Add to FROZEN_INSTRUCTIONS (cache-stable): VCL (988, press 1) on self-harm signals; no medical/legal advice; never promise outcomes; refer filing decisions to a VSO.

**Free tier & onboarding coherence**

- **P1-16. Pre-upload copy promises unconditional analysis; Conditions/Steps tell free uploaders to "Add your evidence" in a dead loop — TRIAGE (M).** Three screens, three contradictory stories (Home: upgrade; Conditions/Steps: upload more; Documents: "Processed"). Disclose the boundary pre-effort; thread subState into Conditions/Steps. Mostly collapses if A1 ships.
- **P1-17. Cold landing is a bare login form; mandatory second-channel OTP with no skip and no stated reason — TRIAGE (S/M).** Three value bullets + "Free to start" above the input; make the second channel deferrable or explain it; fix `inputMode`.
- **P1-18. Education gaps: load-bearing jargon untaught; free tier has zero education; paywall sells engineering nouns — TRIAGE (M, $0 model spend).** `<Term>` popover reusing the existing leg descriptions; static gather-this checklist, triad explainer, VSO links; rewrite the paywall in outcome language + "Free forever" block + "we'll analyze your N documents automatically" + hardship coupon note.

**Sharing / billing / platform (pre-iOS-launch batch)**

- **P1-19. Share permissions over-grant: "view analysis" enables destructive chat writes; "allow uploads" grants evidence deletion — TRIAGE (M).** Live on the API for any accepted viewer today; becomes P0 the day viewer mode ships. Viewer chat gets grounding tools only; split deletion out of UPLOAD_DOCS.
- **P1-20. iOS invite links are `https://localhost/...` — TRIAGE (S).** ShareManager uses `window.location.origin` under Capacitor; the backend already computes the correct `acceptUrl` and it reaches the DTO. Prefer `dto.acceptUrl`. One line; P0 the day iOS ships.
- **P1-21. Account deletion never warns Apple-billed subscribers they'll keep being charged; web "Manage subscription" dead-ends for RC-billed Pro — TRIAGE (M).** Branch the delete modal on billing source (App Review 5.1.1(v) expectation); expose billing source on `/subscription/status`.
- **P1-22. Native data freshness: successful upload never appears (`router.refresh()` is a no-op for useLoader) → veteran retries → 409 "already uploaded" — TRIAGE (M).** `onUploaded` callback (native: `state.refetch`; web: `router.refresh`) + resume listener + light polling + pull-to-refresh.
- **P1-23. Mobile shell defects — TRIAGE (M).** Bottom nav squeezed to ~26px by safe-area math; chat composer trapped under nav/keyboard fold; A− text scale re-enables iOS zoom-on-focus (`font-size: max(1rem, 16px)` fixes); Enter always sends on phones.
- **P1-24. Dark mode never remaps `--navy` (ratings/ghost buttons ~1.2:1 — invisible) and `outline: var(--focus)` shorthand renders NO focus ring on the main interactive rows — TRIAGE (S).** ~6-line token patch thanks to the token discipline + replace ten shorthand rules; extend `tokens.test.ts`.
- **P1-25. Modals don't trap/restore focus; DisclaimerModal is an unnamed dialog; TopBar "Add evidence" has an empty accessible name on mobile; several sub-44px targets — TRIAGE (M).** Native `<dialog>`/trap + mandatory ariaLabel + body scroll lock (or use the built-but-unused Sheet); aria-label the Add button; bring stragglers to 44px.
- **P1-26. TriadDots convey status by color alone (title-attr only) — TRIAGE (S).** `resolveStatusIcon` already exists unused; render the glyph + `aria-label="diagnosis: strong"`; ARIA table semantics or visually-hidden leg prefixes.
- **P1-27. Default "Ready" filter hides most/all conditions at first visit — TRIAGE (S).** Default to "all" (or ready only when readyCount>0); link preview rows to detail via `condHref`; stateful empty-filter copy.
- **P1-28. Upload ergonomics: single-file, no capture/drag-drop; accepts .doc it mangles, blocks .heic it supports — TRIAGE (M).** `multiple` + per-file status list; add .heic/.webp; convert or reject .doc/.docx with "save as PDF" copy.
- **P1-29. No delete/download for uploaded documents (PHI the veteran can't retract) — TRIAGE (M).** Backend DELETE exists and correctly forces re-analysis; add per-card overflow menu. Free-tier wrinkle: clear stale conditions on delete pre-A1.
- **P1-30. No way to see what the AI read; citation `?focus=` deep links ignored; every doc renders as "Document" — TRIAGE (M).** Persist doc classification; "What we found" expandable from the existing unused facts endpoint; implement focus-scroll-highlight.

### P2 — batched polish

- **P2-1.** Global "+" AddModal steers free users into the locked-chat dead end with no Pro badge (S — or retarget at "Write a statement" once quick-add ungates).
- **P2-2.** Share lifecycle rough edges: signed-out reps bounce to context-free OTP login; re-invites hand out dead 410 links; revoke is unconfirmed silent vanish; pending links can't be re-copied; no accept/expiry signal to the veteran (batched behind viewer mode, M).
- **P2-3.** Account deletion is two taps with no re-auth or export offer; a viewer's deletion silently removes atoms from other claims without dirtying them (S+S).
- **P2-4.** Expired sessions dead-end (chat/upload 401s offer no sign-in link); MFA copy promises support that has no email/link anywhere (S).

---

## 4. Verification summary

15 claims were adversarially re-checked (code, git history, native build, live Cloud Run env). **15/15 held; zero refuted.** Corrections were material in five cases and are applied above: the gap-key mismatch is original-sin, not drift (P0-1); the multipart bug is confirmed live in prod with a 32MB Cloud Run nuance (P0-2); the "~$1.05/claim" free-slice figure is *not* in the freemium doc and must not be cited as such (P0-7, §6); `redirectToActive` exists for scenarios only (P1-7); PyramidingRules' correct math *is* computed but its result discarded (P1-1). One inputs discrepancy worth the owner's 5 minutes: one brief asserted a post-design switch of Claude routing to Vertex us-east5 (+10% premium), while adversarial file checks found the design doc using the global endpoint and the deployed rate stage routing to direct `api.anthropic.com` batch — confirm the actual routing/residency posture once, since it bears on any future Managed-Agents/PHI reasoning.

---

## 5. The incremental-analysis decision

### The problem
Increment 5's machinery works (verified: content-addressed `extract_key` skip, no-new-facts $0 short-circuit, atomic generation flips, identity fingerprints, clean carry-forward) but its granularity is degenerate: `computeEvidenceFingerprint` (ConditionGenerationService.java:163) hashes the **whole atom corpus**, so one new fact dirties every condition — full re-rate, full Opus verify, full 3-stage gap re-run (~$1.2–1.5). The design's "0–2 of 12 dirty" never fires for real new evidence. And the veteran sees none of it (P0-4/5, P1-8).

### The three proposals (summarized)

**A. harden-artifact-graph.** Finish Increment 5 in place; no new runtimes. Five workstreams: (1) correctness patches — stranded-doc re-arm, honest processing statuses, `findWithActiveWork()` scheduler query; (2) in-process Spring event nudges so stage-hops fire in <1s with the 15s poll as backstop; (3) **per-condition dirty scoping via attribution at identify** — identify emits `supporting_atom_ids` per condition (it already re-runs over the full corpus every time, so attribution is cheapest there), fingerprints narrow to each condition's own atoms, with four safety valves (unattributed→body-system dirty, always-dirty new conditions, 30-day full refresh, rollback flag); (4) `analysis_run`/`analysis_delta` tables + deterministic diff-at-flip + Notification writes; (5) durable `user_gap_state`. ~$0.55/incremental doc, 4–6 min latency, honest banner throughout. Explicitly refuses Agent SDK, Pub/Sub, LLM diffs. Claimed ~3 weeks (judges: closer to 4–5).

**B. agent-sdk.** Designs the per-claim Agent SDK agent seriously, then **rejects it on the evidence**: 4–8 loop iterations ≈ $0.15–0.45 just to decide what to recompute, adds 30–90s, a second deployable, and destroys the LlmJob cost/eval discipline; Managed Agents put PHI transcripts in Anthropic-hosted session state. Residual hybrid: a single **Haiku `delta_triage` call** (new atoms + condition index → affected ids + new-condition-possible bool) that skips identify/merge when no new condition is plausible; per-condition fingerprints via RatingAgent citing atom keys; deterministic diff-at-flip; a Haiku narrative with deterministic-template fallback. $0.26–0.39/doc, 2–5 min, 7–9 days. Judges found two **false claims**: RatingAgent does *not* already render atom IDs (its attribution path hides an unbudgeted prompt-version bump), and the "us-east5 +10% HIPAA boundary" story contradicts the design doc and deployed config.

**C. veteran-experience.** Assumes backend incrementality is handled and makes the veteran *feel* it, at ~$0 LLM cost: an append-only **Claim Journal** persisted as `Notification` rows (the complete, zero-writer entity) with six deterministic event writers; deterministic diff computed inside the flip transaction; a `PipelinePulse` polling banner; a what-changed digest card; a `/timeline` trust artifact; a checkable living checklist on the P0-1 fix; **static education** (per-gap-type action scripts, C&P prep page, ITF explainer); two respectful event-triggered emails; and the concrete **free-tier A1 plan** with a `free-limit-cents` cap. Its flaw: "being finished by others" — there are no others; standalone, incremental cost stays ~$0.86+/doc and A1 economics degrade until scoping lands.

### Judges' scores (veteranBenefit / feasibilitySoloDev / costRealism)

| Proposal | Judge A | Judge B | Notable verdicts |
|---|---|---|---|
| harden-artifact-graph | 8 / 7 / 9 | 8 / 7 / 9 | Most technically accurate; every cited line checked out; cost math verified. Kill the event-nudge subsystem; timeline optimistic; no free-tier movement. |
| agent-sdk | 7 / 8 / 7 | 7 / 7 / 7 | Best analysis (the SDK self-rejection) — but two false claims sit under its own hybrid; identify-skip risks merge/boundary regressions; its body-system guard erodes its cost edge. |
| veteran-experience | 9 / 8 / 8 | 9 / 8 / 7 | Most claims-accurate; highest veteran benefit per unit risk; only proposal that moves the free tier. Depends on scoping landing first. |

**Unanimous across all three proposals and both judges:** no Claude Agent SDK or Managed Agents in the pipeline (the loop costs more than the recompute it schedules and destroys LlmJob/eval discipline — agentic stays in chat, plus at most a read-only `get_pipeline_status` chat tool); no Pub/Sub/Cloud Tasks/websockets; **no LLM-written digest numbers** — string templates over deterministic diffs.

### Final recommended architecture (synthesis)

Adopt **veteran-experience's surface on harden's correctness base**, with the one contested internal — the dirty-scoping mechanism — decided in favor of **harden's attribution-at-identify** (Judge B's position):

1. **Deterministic diff-at-flip → `Notification` rows.** Compute the per-condition diff (rating delta, per-leg triad delta, gaps closed/opened, added/retired) inside `doCompleteIfReady`'s flip transaction, where both generations coexist with pointers. Write to the existing `Notification` entity (skip harden's two new tables in v1 — `metadataJson` suffices). try/catch so the diff never blocks the flip. String templates only.
2. **Dirty scoping via `supporting_atom_ids` emitted by identify**, scoped fingerprints, all four safety valves, behind `PER_CONDITION_FINGERPRINT`, eval-gated on the existing 24 golden cases (gc-023/024 already cover incremental). Why this over agent-sdk's Haiku triage: identify re-running *is* how condition boundaries and merges get revised — skipping it on a classifier's say-so is the one semantic regression risk in either design; triage's body-system-union guard erodes the $0.26-vs-$0.55 edge it claims; attribution needs no new state-machine state, no new prompt surface, no new golden-case family; and agent-sdk's own attribution path silently required the same prompt surgery anyway (the judges' false-claim finding). Keep `delta_triage` on the shelf as a *later* optimization — once attribution data exists you can validate a triage classifier against it cheaply.
3. **Route by dirty-count:** ≤3 dirty → realtime lane, never `anthropic-batch` — a nexus-letter upload must never vanish into a 24h SLA (both backend proposals agree; adopt verbatim).
4. **Skip harden's event-nudge executor/lock subsystem.** Replace with `findWithActiveWork()` + a faster poll for active claims. Once the banner is honest, ≤30s/hop is invisible to the veteran; zero new concurrency code. Revisit only if 4–6 min end-to-end still feels slow. (Also: the "min-instances=1" premise was never verified in the repo — confirm before trusting instance liveness for anything.)
5. **Durable `user_gap_state`** keyed by (identity_fingerprint, gap_type, triad_leg) + `PATCH` endpoint + re-apply-after-runs + dismissals injected into the gap prompt — surfaced as the checkable living checklist.
6. **Journal surface:** `PipelinePulse` jobs-polling banner (armed by UploadCard's `onUploaded`, which simultaneously fixes native freshness P1-22), what-changed digest card with `router.refresh()` on arrival, "Updated" pills, `/timeline`. Deep links via `condHref` (fixes P0-9 en route).

**Expected economics:** incremental doc ≈ **$0.55** (extract ~$0.04 + identify/merge cached ~$0.13 + 2 dirty re-rates ~$0.08 + scoped verify ~$0.09 + 2×3 gap calls ~$0.21) vs ~$1.2–1.5 today — verified against `AiCostService` pricing by both judges; inside the design doc's $0.15–0.78 target; duplicates stay $0; worst case degrades to today's cost, never worse. Latency ≈ 4–6 min upload→updated steps, legible the whole way.

### Solo-dev migration sequence

| Step | Work | Size | Gate |
|---|---|---|---|
| 1 | **Truth week** — P0-1 gap keys + contract test, P0-2 multipart + 413 handler, P0-3 honest statuses, P0-6 stranded-doc re-arm + test, P0-4/5 jobs-polling banner + terminal states + gap_analysis_pending, P0-9 condHref, P0-10 copy, P0-8 gate Share "coming soon" | ~5 days | none — ship daily |
| 2 | Diff-at-flip + Notification writers + digest card + Updated pills + /timeline | ~4 days | additive |
| 3 | Attributed fingerprints (prompt change, column, scoped hash, safety valves) + realtime-lane routing rule | ~5–6 days | `PER_CONDITION_FINGERPRINT` flag; eval harness pass on 24 goldens; watch PipelineMetrics 1 week on own claim |
| 4 | `user_gap_state` + PATCH + checkable steps | ~3 days | additive |
| 5 | Static education (per-gap-type actions, C&P prep, ITF card, Term popovers) | ~3 days | parallelizable anytime |
| 6 | Free tier A1 (§6) | ~4 days | after step 3 + 1 week of metrics |

~4.5–5.5 realistic weeks; every step independently shippable and flag-revertible.

**Kill list (unanimous or judge-consensus):** Agent SDK / Managed Agents in the pipeline; Pub/Sub / Cloud Tasks / websockets / SSE-for-progress; LLM-written digests or dollar figures; harden's event-nudge executor; agent-sdk's `delta_triage` + identify-skip (deferred, not dead); `analysis_run`/`analysis_delta` tables until `metadataJson` proves insufficient; email cadence beyond one analysis-complete template + one 7-day checklist; the what-if renderer in v1 (flag the stage OFF now — it's pure spend on unread output, P1-9).

---

## 6. Free-tier decision (the open freemium boundary)

**Current state (verified):** Option B. One gate — `AnalysisScheduler.advanceClaim:171` — blocks extraction, synthesis, and gap analysis for free users. A free veteran gets storage, a lock screen, fake "Processed" badges, and contradictory empty states. Zero mission value; also poor conversion mechanics (nothing demonstrates the product before payment).

**The plan doc's own trigger has been met — with one corrected number.** `freemium-free-extraction-plan.md:47` recommends Option A1 (free through synthesis: conditions + triad + combined-rating estimate; gap analysis/scenarios/chat/re-run priority stay Pro), deferred pending "a cost model proving affordability." Correction from adversarial verification: the oft-quoted "~$1.05/claim free slice" is **not in that doc** — it contains no cost figures. The real numbers available today:

- Measured full pipeline: **$0.857** for one small golden case (2026-06-12 eval; baseline run $0.035; run verdict REGRESSION — single case, so treat as an anchor, not an average).
- Design-doc audited initial analysis: **~$1–2/claim** post-increment economics.
- This review's arithmetic for the A1 slice (initial minus the ~3-stage gap fan-out): **~$1.00–1.10/claim conservative**; incremental docs **$0.26–0.78** once dirty scoping (§5 step 3) lands, ~$0.86+ before it.
- All "critical" anti-abuse prerequisites shipped: OTP-verified accounts, UsageGuard `limit-cents: 400` with deferral + auto-resume, upload dedupe, no-new-facts short-circuit.

**Recommendation: ship A1, hard-capped, sequenced after dirty scoping.**

1. **Now (independent of the decision, S):** ungate quick-add text statements (same pipeline as free uploads — the current 402 is incoherent); show free users their real Home state ("12 documents stored" + docs-only ShareCard with honest copy); land P0-1/2/3 first so what free users would see is true and non-blank.
2. **A1 mechanics (~4 days, the plan doc's own estimate):** split the scheduler gate per stage — extraction + synthesis run for everyone; gap analysis, scenarios, Ask AI, and re-run priority remain Pro. Add `usage.free-limit-cents: 150` beside the existing 400; UsageGuard's shipped defer+resume turns overage into "Paused until Aug 1," not an error.
3. **Sequencing (both judges, emphatically):** A1 lands *after* per-condition scoping. Before scoping, every incremental upload re-runs the whole corpus (~$0.86), so a 150¢ cap exhausts after roughly one full run + one incremental — "paused until next month" on doc #2–3 is a trust failure worse than today's honest lock. After scoping, a free veteran gets an initial analysis plus ~2–4 incremental updates per month inside the cap.
4. **Budget envelope (hard-capped, not hoped):** 100 active free veterans/month × ≤150¢ = **≤$150/month worst case**; realistic (most veterans don't hit caps) ~$60–100. At 500 free actives: ≤$750/mo worst case — the point at which to revisit the cap or add a lightweight sponsor tier.
5. **Reframe Pro, don't bury it:** "Pro keeps analysis free for other veterans" + the recurring-cost features (gap guidance, scenarios, chat, re-run priority). A1 makes the paywall's promise *specific* for the first time: a free user with real conditions on screen sees "2 evidence gaps found — see exactly how to close them (Pro)." Enable a hardship coupon path (the tester-coupon machinery already exists).
6. **The fully-free question (owner's not-for-profit stance):** costed and viable — moving gap analysis under the free cap adds ~$0.90/claim initial; at 100 actives under a 250–400¢ cap that's roughly **$250–400/mo worst case**. Reasonable endgame, but do it as a second step after 4–6 weeks of real A1 spend data from the honest ledger, not as a leap. The ledger (AiCallLog) was built for exactly this measurement.
7. Also revisit the Pro `limit-cents: 400` cap — its raise trigger ("ledger honest since Inc 0") was met long ago, and the 10× cost cut makes $8 safe.

---

## 7. Prioritized roadmap

Sizes: S ≤ 1 day, M = 2–4 days, L = 1–2 weeks. Ordered by veteran impact per unit effort.

### Phase A — Truth week (all P0, ~5 days total)
| # | Item | Size | Veteran impact |
|---|---|---|---|
| A1 | Gap-key mapping fix + live-shape fixtures + contract test (P0-1) | S | Turns on the product's core paid guidance — currently blank for every user |
| A2 | Multipart config + 413 handler + >1MB test (P0-2) | S | Unbreaks the first action of every new veteran, live in prod today |
| A3 | Honest processing statuses: queued/processing/processed (P0-3) | S | Stops the app lying about having read medical records |
| A4 | Stranded-doc re-arm + regression test (P0-6) | S | Stops silently analyzing claims with missing evidence |
| A5 | Jobs-polling AnalyzingState + terminal error/zero-conditions states + `gap_analysis_pending` + re-run banner (P0-4/5) | M | Kills the frozen 5%, the silent shift, and the false "all caught up" |
| A6 | `condHref` wiring + lint rule + native smoke test (P0-9) | S | Unblocks the iOS launch's core surface |
| A7 | "Ready to file" → evidence-strength reframe + ITF teaching line (P0-10) | S | Prevents concrete, irreversible back-pay loss |
| A8 | Gate Share analysis-invites "coming soon"; keep docs sharing (P0-8 interim) | S | Stops veterans sending invites that dead-end |
| A9 | Ungate quick-add; honest free Home (docs count + ShareCard) (P0-7 interim) | S | First real free-tier value; removes gating incoherence |

### Phase B — Incremental core (§5, ~3 weeks, flag-gated)
| # | Item | Size | Veteran impact |
|---|---|---|---|
| B1 | Diff-at-flip → Notification + digest card + pills + /timeline | M | "Your nexus letter worked" — the most motivating moment the product can deliver |
| B2 | Attributed per-condition fingerprints + safety valves + realtime-lane routing (eval-gated) | L | Cuts incremental cost ~60%; makes updates fast; funds the free tier |
| B3 | Durable gap state + PATCH + checkable steps (P1-6) | M | The app stops forgetting what veterans told it; the action list becomes completable |
| B4 | Flag OFF `gap_whatif` stage until a renderer exists (P1-9) | S | Stops paying for unread output immediately |

### Phase C — Accuracy & money (P1 batch, ~1.5 weeks)
| # | Item | Size | Veteran impact |
|---|---|---|---|
| C1 | `GET /combined-rating` with PyramidingRules + notes (P1-1) | M | Headline dollars stop being inflated/understated vs the app's own math |
| C2 | Presumptive-nexus two-sided fix (P1-2) | S | Stops steering PACT-Act veterans toward $800–2,000 letters they don't need |
| C3 | 2026 rate table + RATES_YEAR + COLA-rot test (P1-3) | S | Every dollar shown is ~5% low today |
| C4 | Dual hero numbers + universal estimate hedge + de-hype copy (P1-4) | M | Calibrates the single biggest expectation in the app |
| C5 | Nullable rating/confidence + weakestLeg fix (P1-5) | S | 0% ratings are winnable claims; wrong weakest-leg misdirects evidence-gathering |

### Phase D — Free tier A1 (§6, ~4 days, after B2 + 1 week of metrics)
Split scheduler gate per stage; `free-limit-cents: 150`; honest paused states; paywall rewrite in outcome language + "Free forever" block. Then measure 4–6 weeks and decide on fully-free.

### Phase E — Experience & education (parallelizable, ~2 weeks)
Static education pack (per-gap-type actions, C&P prep, ITF explainer, Term popovers, VSO links — P1-18, $0 spend); chat fixes (cap messaging P1-10, tri-state P1-11, claim-state grounding + `get_pipeline_status` P1-12, stream persistence P1-13, tool safety P1-14, crisis-line block P1-15 — do the crisis line in Phase A if it slips); conditions default filter (P1-27); doc delete/download (P1-29); doc transparency + citation focus (P1-30); upload multi-select (P1-28); onboarding value bullets (P1-17).

### Phase F — Pre-iOS-launch batch (before App Store submission)
iOS invite link (P1-20, one line); native freshness/resume/pull-to-refresh (P1-22); mobile shell fixes (P1-23); billing-source delete warning + manage-subscription branch (P1-21); dark-mode navy tokens + focus outlines (P1-24); modal a11y + tap targets (P1-25); TriadDots a11y (P1-26).

### Phase G — Viewer mode (L) + share lifecycle
Tighten permissions first (P1-19 — mandatory), then minimal viewer mode (P0-8 full fix), then the P2-2 lifecycle polish and P2-3/P2-4 batch.

---

## Appendix: things deliberately not done

- **No Claude Agent SDK / Managed Agents in the pipeline** — unanimous across three proposals and two judges; the loop costs more than the recompute it schedules, adds a deployable and PHI surface, and destroys the LlmJob/eval discipline that increments 0–8 bought. Agentic stays in chat.
- **No re-litigation of shipped decisions:** OTP-only auth, RevenueCat native IAP, bottom-nav redesign, and the 2026-06-10 pipeline architecture stand. Everything above completes them.
- **No new infra** (Pub/Sub, websockets, push before APNs-post-launch) and **no new tables** beyond `user_gap_state` until `Notification.metadataJson` proves insufficient.
- **No LLM anywhere near a number the veteran sees** — diffs, digests, and dollars are deterministic; this rule held through the review and every design keeps it.
