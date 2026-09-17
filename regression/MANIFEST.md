# Regression Test Manifest

The pre-prod regression suite. Every shipped feature has tests pinned here
so the same set runs against every build that's about to ship.

## How to run

Run all regression tests for a platform with these two commands:

```
# Backend / service (Java + Spring Boot)
cd spring-backend && JAVA_HOME=/opt/homebrew/opt/openjdk@21 ./gradlew test -PregressionOnly
```

Mobile-native targets aren't shipped yet (web-only per `CLAUDE.md`).
Add a mobile section here when the App Store / Play Store path goes live.

A future "regression plugin" will consume this file as input and orchestrate
the runs + report deltas across builds. For now, this file is the source of
truth for *what* the suite contains; the gradle command above is
*how* it's executed.

## How to add a feature (mandatory step in every phase PR)

1. Tag every test you add as regression:
   - **Backend (JUnit 5):** `@Tag("regression")` at class level on the test class.
     Class-level tagging applies the tag to every `@Test` method inside.
2. Append an entry to the relevant feature section below. Include:
   - The phase / sub-feature label
   - Each test file's absolute-from-repo-root path
   - One sentence on what the file covers (so a future operator triaging a
     regression failure has context without re-reading the test)
3. If you're adding the *first* test for a feature, add the feature heading
   under the right subsystem section. Keep features alphabetised within a
   subsystem.

## Features

### Eval harness (Increment 8)

The offline deterministic eval tier — a golden corpus of ~24 synthetic cases
driven end-to-end through the REAL analysis pipeline (extraction → synthesis →
gap) on the fake LLM substrate, scored against per-case expectations, and gated
by a checked-in end-state digest snapshot. See
`docs/architecture/inc8-eval-harness-spec.md` and `docs/qa/evals/README.md`.

Harness + gate (backend):
- `spring-backend/src/test/java/com/afterduty/eval/OfflineGoldenPipelineTest.java`
  — parameterized over every active golden case: drives each through the real
  pipeline and asserts the §2.4 invariants (abstention honesty, supersede
  atomicity, carry-forward/dirty-scope, VA-math determinism via the real
  PyramidingRules + VaMathService, pyramiding clamps, deterministic-rating
  engine cross-check, forbidden conditions, gap must-flags) at 100%, AND that
  each case's EndStateDigest equals the committed snapshot.
- `spring-backend/src/test/java/com/afterduty/eval/PromptVersionEvalGateTest.java`
  — the prompt-bump gate: the live PROMPT_VERSION / SCHEMA_VERSION /
  RATING_PROMPT_VERSION constants (via PromptVersionRegistry) must equal the
  snapshot's recorded versions, so a prompt/schema bump fails `check` until the
  snapshot is regenerated. Regeneration (`evalSnapshot -PevalRunId=<id>`) is now
  BUILD-enforced to link to a real scored run (review fix minor #3): the named run
  must have a committed `docs/qa/evals/runs/<id>/report.json` whose prompt_versions
  match the constants being stamped, OR an explicit `-PevalWaiver="<reason>"` — the
  conscious-waiver path is a logged invocation flag, no longer honor-system.
- `spring-backend/src/test/java/com/afterduty/eval/GoldenCorpusValidationTest.java`
  — keeps the hand-authored case dirs honest: ids unique, files exist, regexes
  compile, every `*_by_vasrd` key is an identified condition, roster covers
  every required tag, every doc carries the synthetic marker (PHI rule).
- `spring-backend/src/test/java/com/afterduty/eval/DeterministicScorerTest.java`
  — scorer unit edges: recall/precision, laterality twins, inclusive band
  boundaries, forbidden hit, deterministic-engine cross-check, dirty-scope counts.
- `spring-backend/src/test/java/com/afterduty/eval/EndStateDigestTest.java`
  — digest stability under condition reordering; sensitivity to rating/outcome.
- `spring-backend/src/test/java/com/afterduty/eval/MutationSanityTest.java`
  — mutation sanity: proves the scorer FAILS on each regression class (dropped
  condition, wrong rating, leaked forbidden condition, un-clamped tinnitus,
  silent-zero, deterministic-engine regression) — the guardrail on the guardrail.
- `spring-backend/src/test/java/com/afterduty/eval/FakeResponderTest.java`
  — the additive `FakeLlmAsyncProvider.setResponder` per-condition fan-out and
  its precedence (per-evidence > responder > purpose-keyed > default `[]`).
- `spring-backend/src/test/java/com/afterduty/eval/EvalReportWriterTest.java`
  — the live-run report writer: all three artifacts, aborted-run shape, hard-fail
  quotes.
- `spring-backend/src/test/java/com/afterduty/eval/EvalJudgeTest.java`
  — the cross-family judge with a fake caller: parse + re-ask + judge_error,
  and that expected.json never leaks into the judge prompt.
- `spring-backend/src/test/java/com/afterduty/eval/EvalSpendGuardTest.java`
  — the live-tier spend-cap ABORT DECISION unit, proven offline (canned cost
  supplier, no live calls): under/at/over cap, inclusive boundary, latched trip
  (a refund cannot un-abort), crossing mid-roster, null ledger, and the full
  runner protocol (trip ⇒ remaining cases `skipped` ⇒ report stamps `spend_cap`).
- `spring-backend/src/test/java/com/afterduty/eval/SpendCapEnforcementTest.java`
  — the spend cap proven as ENFORCEMENT, not just a decision unit (review fix,
  major #1): the same `EvalRunLoop` the live runner uses, with the guard bound to
  the REAL `AiCallLogRepository.totalCostSince` query against H2, driving a real
  pipeline case (gc-001) that books real `AiCallLog` rows, asserts the loop aborts
  MID-ROSTER when the real ledger crosses the cap. No live calls.
- `spring-backend/src/test/java/com/afterduty/eval/EvalRunLoop.java` +
  `EvalRunLoopTest.java` — the live-tier run loop itself (the piece the review
  found missing): §3.3 protocol (check before each case, check after each
  tick-batch, skip remainder on trip, stamp `aborted_reason`).
- `spring-backend/src/test/java/com/afterduty/eval/EvalVerdict.java` +
  `EvalVerdictTest.java` — the run-level pass/fail POLICY (spec §3.5, review fix
  minor #4): any hard fail on any case ⇒ FAIL (dominates averages); no baseline ⇒
  BASELINE; aggregate deterministic metric drop > 0.05 or `gap_completeness` mean
  drop > 0.10 ⇒ REGRESSION; else PASS. Consumed by `LiveGoldenEvalTest` in place of
  the old inline single-case ternary. Tests pin hard-fail dominance, the strict
  threshold boundaries, and skipped-case-excluding mean aggregation.
- `DeterministicScorer` now also asserts per-evidence processing state
  (`expected_evidence`, review fix major #2): the `abstention-expected` unreadable
  case gc-013 pins `processing_status=error` + a non-blank plain-language
  `processing_message`, and the two legitimately-empty cases (gc-015 DD-214,
  gc-019 clean exam) pin `processing_status=processed` — so the three formerly
  byte-identical zero-condition outcomes now each carry a distinct, asserted
  observable field, closing the silent-zero-on-unreadable blind spot.
  `DeterministicScorerTest` + `MutationSanityTest` prove the scorer FAILS when an
  unreadable doc is silently marked `processed` with empty atoms.
- `PromptVersionEvalGateTest` also covers the NEGATIVE path: a doctored snapshot
  fails with a message that names the `evalSnapshot -PevalRunId=` regeneration
  command.

(The LIVE scored tier — `LiveGoldenEvalTest`, `@Tag("eval-live")` — is excluded
from the regression suite; it hits real Vertex endpoints and runs only under
`-PliveEval`. Its spend cap, judge parsing, and report shape are all verified
offline by the tests above, so the live path is gated by design review + fakes,
never by making live calls in CI.)

### Sharing

The multi-tenant sharing layer (veteran shares claim with VSO, lawyer, etc.)
— see internal design notes.

**Phase A — schema + ClaimAccessService**

Service (backend):
- `spring-backend/src/test/java/com/afterduty/service/ClaimAccessServiceTest.java`
  — 13 tests covering: owner always has full access regardless of
  subscription; viewer access requires accepted + non-revoked share;
  `VIEW_ANALYSIS` requires `can_view_analysis=true` AND owner Pro;
  `UPLOAD_DOCS` requires `can_upload_docs=true`; `CHAT` requires
  viewer's own Pro AND `can_view_analysis=true`; revoked / unaccepted /
  no-share viewers → 403; migration SQL file presence + required DDL
  strings.

Web (Flutter): _(none — backend-only phase)_

**Phase B — share lifecycle endpoints**

Service + controller (backend):
- `spring-backend/src/test/java/com/afterduty/controller/ShareControllerTest.java`
  — 14 tests covering: POST /api/shares creates share (201 + token + acceptUrl);
  duplicate POST merges row and re-issues token (idempotency); GET /api/shares
  returns owner's non-revoked shares; PATCH /api/shares/{id} updates permission
  flags without touching token; PATCH by non-owner returns 403; DELETE sets
  revokedAt (row preserved) and preserves ChatThread; DELETE by non-owner
  returns 403; GET /api/shares/accept/{token} (unauthenticated) returns 200
  with preview payload; invalid token returns 404; POST /api/shares/accept/{token}
  with wrong email returns 403; correct email (case-insensitive) sets acceptedAt,
  clears token, creates ChatThread; expired invite returns 410; already-consumed
  token returns 404; GET /api/shares/profiles returns own + shared entries.

**Phase C — X-View-As header routing + sharedProfiles in getMe**

Service (backend):
- `spring-backend/src/test/java/com/afterduty/service/ClaimAccessServiceResolveIfPresentTest.java`
  — 7 tests (T1–T7) covering: `resolveIfPresent` returns empty when header absent;
  own claim id returns isOwner=true; accepted share returns viewer permissions;
  no share throws 403; revoked share throws 403; sentinel -1L (malformed header)
  throws 400 with "invalid_view_as_header"; zero/negative claim id throws 400.

Controller (backend):
- `spring-backend/src/test/java/com/afterduty/controller/IntakeControllerPhaseC_Test.java`
  — 9 tests (T8–T16) covering: VSO with VIEW_DOCS can GET /api/claim/evidence on
  owner's claim; VSO without canViewAnalysis gets 403 on GET /api/claim/conditions;
  owner Pro lapsed → VIEW_ANALYSIS 403 for VSO; VSO without canUploadDocs gets 403
  on POST /api/claim/evidence (JSON), no row added; VSO with canUploadDocs=true
  can POST evidence, row saved to owner's claim; VSO without own Pro gets 403 on
  CHAT; VSO with own Pro + canViewAnalysis passes CHAT scope gate; no-share viewer
  gets 403 (not 404) on GET evidence; own claim id in header = same as no header.
- `spring-backend/src/test/java/com/afterduty/controller/AuthControllerPhaseC_Test.java`
  — 2 tests (T18–T19) covering: GET /api/auth/me for fresh user returns
  sharedProfiles as empty array (not null); user with one accepted share has
  sharedProfiles.length >= 1 with isOwn=false entry.

Web (Flutter): _(none — backend-only phase)_

**Phase D — ChatService + thread-scoped messages + Atom.creatorUserId + AtomCitationHelper**

Service (backend):
- `spring-backend/src/test/java/com/afterduty/service/IntakeMessageThreadIdTest.java`
  — 1 test (TA): saving an IntakeMessage without threadId throws a DataAccessException
  (NOT NULL constraint enforced by JPA entity's nullable=false).
- `spring-backend/src/test/java/com/afterduty/service/ChatServiceTest.java`
  — 6 tests (TB–TG): TB: sendMessage auto-creates ChatThread on first call and reuses
  on second; TC: listMessages returns only the viewer's own thread messages, not
  another viewer's messages on the same claim; TD: VSO sendMessage calls
  usageGuard.assertCapacity with vsoId (not ownerId); TE: owner sendMessage calls
  usageGuard.assertCapacity with ownerId; TF: VSO add_atom tool → atom.creatorUserId==vsoId,
  atom.messageId set, claim.synthesisNeeded=true; TG: owner add_atom tool →
  atom.creatorUserId==ownerId.
- `spring-backend/src/test/java/com/afterduty/service/AtomCitationHelperTest.java`
  — 4 tests (TH): null creator → null; creator==owner → null; creator==VSO →
  "From <name>'s chat — YYYY-MM-dd"; unknown user → label contains "Unknown".

**Phase E — profile-switching plumbing**

Web (Flutter): _(retired with the Flutter frontend, 2026-08-02 — see
docs/maintenance/dead-code-audit-2026-08-02.md; equivalent coverage lives in
web/src "*.test.tsx")_

**Phase F — owner-side share management UI**

Web (Flutter): _(retired with the Flutter frontend, 2026-08-02 — see
docs/maintenance/dead-code-audit-2026-08-02.md; equivalent coverage lives in
web/src "*.test.tsx")_

**Phase G — recipient-side accept-invite + sign-up bridging**

Web (Flutter): _(retired with the Flutter frontend, 2026-08-02 — see
docs/maintenance/dead-code-audit-2026-08-02.md; equivalent coverage lives in
web/src "*.test.tsx")_

**Phase H — profile-switcher dropdown + conditional rendering for shared profiles**

Web (Flutter): _(retired with the Flutter frontend, 2026-08-02 — see
docs/maintenance/dead-code-audit-2026-08-02.md; equivalent coverage lives in
web/src "*.test.tsx")_

**Phase H (regression) — end-to-end share → profile-switch**

Service + controller (backend):
- `spring-backend/src/test/java/com/afterduty/controller/ShareProfileSwitchRegressionTest.java`
  — 6 tests (T-SP1–T-SP6): T-SP1: viewer GET /api/claim/evidence with X-View-As returns
  200 after full token-based accept flow; T-SP2: canViewAnalysis=true + owner Pro →
  GET /api/claim/conditions returns 200; T-SP3: canViewAnalysis=false + owner Pro →
  GET /api/claim/conditions returns 403; T-SP4: DELETE /api/shares/{id} (revoke) then
  GET /api/claim/evidence returns 403; T-SP5: GET /api/shares/profiles after accept
  contains isOwn=false entry with correct claimId; T-SP6: X-View-As: notanumber →
  GET /api/claim/evidence returns 400.

Web (Flutter): _(retired with the Flutter frontend, 2026-08-02 — see
docs/maintenance/dead-code-audit-2026-08-02.md; equivalent coverage lives in
web/src "*.test.tsx")_

### Subscription / pricing

The Stripe-backed subscription layer — current tier derivation and Customer Portal
access — see internal design notes.

**Phase A — current_tier in status + portal endpoint**

Service + controller (backend):
- `spring-backend/src/test/java/com/afterduty/controller/SubscriptionStatusCurrentTierTest.java`
  — 3 tests (T1–T3): GET /api/subscription/status for a free user omits the
  `current_tier` field entirely (non_null serialization); for a monthly Pro user
  returns `"monthly"`; for an annual Pro user returns `"annual"`. Uses @MockBean
  StripeService to stub currentTierForUser() and describePlans() without real
  Stripe calls.
- `spring-backend/src/test/java/com/afterduty/controller/SubscriptionPortalTest.java`
  — 2 tests (T4–T5): POST /api/subscription/portal for a Pro user (has
  stripeCustomerId) returns 200 with the billing portal URL; for a free user
  (no stripeCustomerId, service throws IllegalArgumentException) returns 400 with
  HTTP reason containing "no_stripe_customer".

**Phase B — frontend three-tier pricing page**

Web (Flutter): _(retired with the Flutter frontend, 2026-08-02 — see
docs/maintenance/dead-code-audit-2026-08-02.md; equivalent coverage lives in
web/src "*.test.tsx")_

**Phase D — RevenueCat (Apple/Play) rail**

The native IAP path, fronted by RevenueCat. Web stays on Stripe (Phase A); both
rails write the same `User.subscriptionExpiresAt`, so all downstream gating is
unchanged. We add `subscriptionSource` only to route "Manage subscription" to
the right portal (Apple requires native subscribers be sent to the App Store).

- `spring-backend/src/test/java/com/afterduty/service/RevenueCatServiceTest.java`
  — 13 unit tests (T1–T13) covering the webhook handler:
  INITIAL_PURCHASE / RENEWAL / UNCANCELLATION set `subscriptionExpiresAt` and
  `subscriptionSource` (apple/google); missing or wrong `Authorization` →
  SecurityException; unconfigured webhook-auth → IllegalStateException; unknown
  `app_user_id` → ignored (no save); CANCELLATION / EXPIRATION just log
  ("cancelled_logged", paid period runs to its end); BILLING_ISSUE logs;
  unknown event types ignored; missing fields ignored; `mapStore()` recognises
  APP_STORE / MAC_APP_STORE → apple and PLAY_STORE → google, others null.

### Navigation / IA

The bottom-nav information architecture (Option A "Claim Workflow": 4 items —
Evidence / Conditions / Plan / Share — with Action Plan + Scenarios merged
behind the "Plan" tabs, and Profile moved to the top-right avatar).

Web (Flutter): _(retired with the Flutter frontend, 2026-08-02 — see
docs/maintenance/dead-code-audit-2026-08-02.md; equivalent coverage lives in
web/src "*.test.tsx")_

### Native in-app purchases (RevenueCat)

Phase 4 wiring: native (iOS/Android) buys Pro through RevenueCat IAP while web
stays on Stripe. The web-vs-native decision keys off `useNativePurchases`
(`!kIsWeb && _revenueCatReady`) so VM tests (RC never configured) keep
exercising the Stripe path, and the native path is verified via a fake that
forces `useNativePurchases`.

Web (Flutter): _(retired with the Flutter frontend, 2026-08-02 — see
docs/maintenance/dead-code-audit-2026-08-02.md; equivalent coverage lives in
web/src "*.test.tsx")_

### Perf / rebuild discipline

The always-visible shell chrome (status bar + avatar overlay) is on every
page and animates, so any rebuild it does on unrelated `AppProvider` notifies
turns into iOS scroll/animation jank. These tests pin the
`context.select`-not-`context.watch` discipline + the pulse-when-running gate
+ the keyed RepaintBoundary isolation so the jank can't return silently.

Web (Flutter): _(retired with the Flutter frontend, 2026-08-02 — see
docs/maintenance/dead-code-audit-2026-08-02.md; equivalent coverage lives in
web/src "*.test.tsx")_
