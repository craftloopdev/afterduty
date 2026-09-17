# Full Site Review — User-Story Map (Next.js web app)

**Date:** 2026-06-10 · **Scope:** every route and component under `web/src` (11 areas, 11 parallel
reviewers, every visual element catalogued with `file:line`)
**Result:** **364 visual elements** catalogued; **333 user stories** written/confirmed;
**~40 orphan elements** (no defensible story — stubs, dead states, decoration); **~100 missing
affordances** (stories a persona clearly has, with no supporting UI).

Per-area detail (full element catalogs with story tables) lives in [`areas/`](areas/):
[shell-nav](areas/ui-shell-nav.md) · [home](areas/ui-home.md) · [conditions](areas/ui-conditions.md) ·
[steps](areas/ui-steps.md) · [documents](areas/ui-documents.md) · [share](areas/ui-share.md) ·
[profile](areas/ui-profile.md) · [paywall](areas/ui-paywall.md) · [chat](areas/ui-chat.md) ·
[auth](areas/ui-auth.md) · [primitives](areas/ui-primitives.md)

---

## P0 — Broken promises (the element exists, the story it claims is false)

1. **Share invite is undeliverable — the free tier's headline feature doesn't work.**
   "Send secure invite" creates a token but sends nothing; the `acceptUrl` in the POST response is
   discarded (`ShareManager.tsx:60-73`), and the link the backend builds points at
   `app.vaclaimpath.com/accept-share/{token}` — a route that **does not exist** in the Next app
   (`ShareService.java:43`). Fix: surface a copy-secure-link button (and/or send email), and build
   the `/accept-share/[token]` page + VSO viewer mode.
2. **Dead "Add evidence" / "View condition" CTAs across four surfaces.** The step-detail modal
   buttons only close the modal on Home (`DoThisNext.tsx:130-135`), Conditions detail
   (`ConditionDetail.tsx:177,220`), and Steps (`StepsPanel.tsx:149-154`); all three AddModal options
   route identically to `/documents` (`AddModal.tsx:10-24`). The app's core
   insight→action funnel is a stub.
3. **Ask AI is unreachable** — `/ask` is in neither `Sidebar.tsx:7` nor `MobileNav.tsx:7`; the only
   entry is one condition-detail button that does a full-page reload and drops the promised
   condition context. The flagship paid feature is effectively hidden.
4. **Silent wrong numbers.** Pay-calc failure renders combined rating **0% and $0/mo as real values**
   (`endpoints.ts:96-98` swallows the error); subscription-fetch failure shows a **Pro user the sales
   paywall with live Subscribe buttons** (`endpoints.ts:61-67`) — double-charge risk.
5. **Documents status contract broken.** Adapter requires `status==='complete'` but the backend
   emits `processed/error/deferred_usage_limit` (`evidence.ts:29`) — the green "Processed" badge is
   unreachable in prod and raw enums leak to the UI ("Deferred_usage_limit"). The stale "uploads
   need Pro" banner (`UploadCard.tsx:67-74`) contradicts the now-free uploads.
6. **Pro billing dead-end.** Once `isPro`, both upgrade entry points hide (`ProfileView.tsx:80`,
   `Sidebar.tsx:56`) — making PaywallView's "Manage subscription" (the only billing-portal door)
   unreachable. No renewal date shown anywhere (`expires_at` dropped, `endpoints.ts:141-146`).
   Stripe portal return-url still points at the Flutter hash route (`StripeService.java:82`).
7. **MFA-enrolled veterans are locked out of web sign-in** — dead-end error, no challenge flow
   (`login/page.tsx:29`).
8. **No ToS/Privacy links or "educational, not legal advice" disclaimer anywhere in auth**, despite
   collecting PII for disability claims (zero matches app-wide for terms/privacy).

## P1 — Systemic accessibility (older-veteran demographic; several are blockers)

- **Keyboard focus is invisible on most interactives**: module CSS uses `outline: var(--focus)`
  (bare color shorthand → `outline-style: none`), overriding the correct global ring
  (DoThisNext/ConditionsPreview/ShareCard + others).
- **Modal and Sheet are not accessible dialogs**: no `role=dialog`/`aria-modal`, no focus trap, no
  focus restore (`Modal.tsx:18-26`, `Sheet.tsx:34-49`) — phone sign-in rides on Sheet. Untitled
  Modal loses both its accessible name *and* its close X (`Modal.tsx:39-49`).
- **Color-only encodings**: TriadDots (the sole triad readout on mobile), step-priority dots,
  EvidenceHealth bars; desktop "tables" are div grids with no table semantics.
- **Tap targets under the app's own 44px floor**: Button sm 38px, Modal close 32px, Sheet close
  38px, dark-mode switch 26px, chips ~36px, DisclaimerPill ~27px.
- **Chat transcript invisible to screen readers** (no `aria-live`/`role=log`; sender conveyed only
  by alignment/color); composer has no accessible name; failed send eats the typed message and
  leaves a phantom bubble (`ChatView.tsx:29-32`).
- Icon-only Add button has an empty accessible name <1100px; AnalyzingState progressbar unnamed and
  never self-updates; loading skeletons aren't announced; `(app)/loading.tsx` is Home-shaped on
  every route.

## P2 — Missing states & affordances (top recurring patterns)

- **Free-vs-Pro is unhandled everywhere**: 402s hit generic retry-forever error boundaries with no
  upgrade CTA (conditions, steps, chat SSR); free users get full chat composer + suggestion chips
  that bait a guaranteed 402; no upgrade affordance exists on mobile at all; `isPro`/`atUsageLimit`
  are computed in the home VM and never rendered.
- **Documents are write-only dead-ends**: no delete/download/view/detail despite backend endpoints
  existing; no post-upload status polling; the real paid gate (extraction deferral) is invisible.
- **No deep links where routes exist**: Home condition rows → list instead of `/conditions/[id]`;
  steps modal knows `condId` but doesn't link; filter/tab state not URL-synced.
- **Trust/assumption gaps**: pay estimates show no assumptions (dependents, rate year, "2024 rate
  tables" in code); no analysis-freshness timestamp; evidence bullets don't link to source docs;
  no dispute/dismiss for AI-identified conditions; no manual add-condition.
- **Dead-but-shipped elements to cut or wire**: SMC-K row hardcoded unreachable
  (`smcAvailable=0`), `TriadCell.tsx` imported nowhere, `resolveStatusIcon` zero consumers, 6 unused
  icon glyphs, Sheet `full` prop no-op, GET `/api/chat` dead endpoint, "Revoked" pill unreachable
  (backend filters revoked rows), 413 branch unreachable (backend sends 400).
- **Primitive gaps**: no toast/snackbar, no danger Button variant, no form-field primitive, no
  tooltip (title-attr only — unreachable on touch), no banner/alert.
- **Token violations cluster in module CSS** (raw `rgba()` scrims/cards/glows, off-scale px) —
  the no-hex token test only covers `tokens.ts`; extend it to `*.module.css`.

## What's strong (keep)

Token-driven theming with triad/status color discipline; RSC data flow with typed adapters and
state-aware view models (loading/empty/analyzing/error modeled at the VM layer); per-area state
coverage on Home is genuinely good (analyzing vs new-user vs populated); consistent shell at the
760px breakpoint; dev-preview fixture routes for every state (404 in prod); the correct-actor
regression discipline in `docs/qa/ui-regression/`.

## Recommended fix order

1. **Week 1 (P0 funnel):** Share invite delivery + `/accept-share` route; wire the three dead
   modal CTAs (real links to `/documents` + `/conditions/[id]`); add `/ask` to both navs;
   fix documents status mapping + remove stale Pro-gate copy.
2. **Week 2 (P0 money/truth):** subscription-error fail-loud (never show paywall to Pro);
   pay-calc "estimate unavailable" state; Pro billing entry (Profile plan row → portal);
   post-checkout success/cancel handling; auth legal links + MFA challenge (or honest messaging).
3. **Week 3 (P1 a11y sweep):** fix `outline: var(--focus)` shorthand globally; dialog semantics +
   focus trap in Modal/Sheet; text equivalents for color encodings; tap-target floor; chat
   transcript a11y + failed-send recovery.
4. **Ongoing (P2):** free-tier upsell states on 402 paths; document lifecycle (delete/download/
   detail); deep links; assumption disclosures; cut or wire every dead element above; extend the
   token lint to module CSS.

Each fix should land with its regression entry under `docs/qa/ui-regression/` per house convention.
