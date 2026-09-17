# UI Review — Paywall / Upgrade / Subscription (user-story coverage)

Scope: `web/src/app/(app)/upgrade/page.tsx`, `web/src/components/paywall/PaywallView.tsx` (+ `.module.css`), `web/src/app/api/subscription/route.ts`, `web/src/lib/fixtures/subscription.ts`, plus the adapter (`web/src/lib/api/endpoints.ts:121-147`), DTO (`web/src/lib/models/api.ts:104-111`), VMs (`web/src/lib/models/vm.ts:13-31`), the route-group loading/error states, and the three entry-point CTAs that link to `/upgrade`.

Personas used: **brand-new user** (just signed up, free), **free user** (uploaded docs, hit AI gate), **Pro user** (active subscription), **VSO viewer** (shared/limited account), **veteran** (any tier).

---

## Element Catalog

| # | Element | file:line | User story | States covered | Gaps |
|---|---------|-----------|------------|----------------|------|
| 1 | AppShell page header — title "Upgrade to Pro", sub "AI extraction, condition synthesis & gap analysis." | `web/src/components/shell/AppShell.tsx:39-40` | As a free user landing on /upgrade, I want the page identified in the shell so that I know I navigated to the upgrade flow. | Desktop + mobile header; light/dark via tokens | Title says "Upgrade to Pro" even for an already-Pro user viewing their ActiveCard — header contradicts body ("You're on Pro") |
| 2 | Hero sparkle chip (52px icon tile) | `web/src/components/paywall/PaywallView.tsx:49-51`, `PaywallView.module.css:26-35` | As a brand-new user, I want a visual cue that this page is about the AI/premium tier so that the offer registers before I read. (Decorative; `aria-hidden` — defensible brand affordance, borderline.) | free state only; dark mode via `--ai-bg/--ai-fg` | Pure decoration; hidden from AT correctly. Hardcoded 52px |
| 3 | H1 "Unlock the full claim engine" | `PaywallView.tsx:52` | As a free user, I want a one-line value proposition so that I know what paying gets me. | free only | Marketing-speak ("claim engine") vs. feature names below; no plain-language tie to "stronger VA claim" |
| 4 | Hero subtext "AI extraction, condition synthesis, and gap analysis — built for your VA claim." | `PaywallView.tsx:53-56` | As a free user, I want the three paid capabilities named up front so that I can decide whether to scroll. | free only | Duplicates AppShell sub (element 1) word-for-word — redundant on desktop where both are visible |
| 5 | Plan cards grid (Monthly / Annual) | `PaywallView.tsx:59-68`, `module.css:53-66` | As a free user ready to buy, I want to compare billing periods so that I pick the cadence that fits my budget. | free only; mobile stacks at <760px (`module.css:201-205`); dark mode via tokens | No comparison of what differs (nothing differs except price — never stated); no trial info; no "cancel anytime" reassurance |
| 6 | Featured-plan treatment on Annual (navy border + heavier shadow) | `PaywallView.tsx:124-127`, `module.css:63-66` | As a free user, I want the recommended plan visually emphasized so that choosing is easier. | free only | Detection is `period === "year" \|\| label === "Annual"` — fragile string match on adapter output |
| 7 | Plan label ("Monthly"/"Annual") | `PaywallView.tsx:132`, adapter `endpoints.ts:138` | As a free user, I want each card named so that I know which billing period I'm clicking. | free only | Label derived solely from `billing_period === "year"`; any future tier (e.g. lifetime) silently labels "Monthly" |
| 8 | "Best value" green Pill on Annual | `PaywallView.tsx:133` | As a free user, I want to know which plan saves money so that I don't overpay. | free only | Claim is unquantified — $119.99 vs $143.88/yr (~17% / $23.89) never shown; "Best value" is assertion, not evidence |
| 9 | Price display — `$11.99` `/month`, `$119.99` `/year` (mono, tabular nums) | `PaywallView.tsx:136-139`, `module.css:78-95` | As a free user, I want the exact recurring price and period so that there are no billing surprises. | free only | `$` hardcoded (USD-only, acceptable); no tax note; no "billed annually" clarification on the year plan |
| 10 | "Subscribe" primary button (per plan, full width, per-button spinner) | `PaywallView.tsx:141-148`, busy logic `:13-19` | As a free user who chose a plan, I want one tap to start Stripe checkout so that I can pay without re-entering plan details. | per-plan loading (spinner + `aria-busy` + disabled via `Button.tsx:36-38`); error path | **Both buttons read "Subscribe" to AT — no aria-label with plan+price**; sibling button stays enabled while one is busy (double-checkout race); no disabled state for VSO viewer pre-click (only post-click 403 toast) |
| 11 | Features card — "EVERYTHING IN PRO" eyebrow + 3 feature rows (check chip, name, desc) | `PaywallView.tsx:153-172`, `module.css:97-145` | As a free user skimming, I want each paid feature named and explained in one line so that I can judge worth before paying. | free AND Pro (flush variant); dark mode | Only 3 features; "share with VSO" / uploads not listed as *free* anywhere, so users can't tell what stays free; content duplicated as hardcoded `DEFAULT_FEATURES` fallback (`endpoints.ts:125-129`) and again in fixture (`fixtures/subscription.ts:10-14`) — three copies to keep in sync |
| 12 | Feature check chips (22px green circles) | `PaywallView.tsx:160-162`, `module.css:119-129` | As a free user, I want included-feature markers so that the list scans as "all included." | both states; `aria-hidden` correct | Hardcoded 22px/14px |
| 13 | Inline error banner (`role="alert"`) — "Couldn't start checkout. Please try again." / "That action isn't available on your account." | `PaywallView.tsx:28-31,36-37,74-78`, `module.css:191-198` | As a free user whose checkout failed (or a VSO viewer who can't buy), I want a clear failure message so that I know whether to retry or stop. | network error, non-OK, missing url, 403 | Renders at the **bottom** of `.wrap`, below the features card — likely off-viewport relative to the clicked button on mobile; portal failures for Pro users also say "checkout" (`:37` reuses copy); no retry button, no support link |
| 14 | ActiveCard container (Pro branch) | `PaywallView.tsx:44-45,83-113` | As a Pro user opening /upgrade, I want confirmation I'm subscribed instead of a sales pitch so that I don't buy twice. | Pro state; dark mode | Shown only when `active:true`; see Top Issue 1 — backend failure silently flips this to the sales pitch |
| 15 | Check chip + "You're on Pro · {tier}" title | `PaywallView.tsx:95-100` | As a Pro user, I want my current plan named so that I know which tier renews. | Pro | Raw tier string interpolated — renders "You're on Pro · monthly" (lowercase enum leak); no mapping to "Monthly" label |
| 16 | "Thanks for supporting your claim." subtext | `PaywallView.tsx:102` | As a Pro user, I want a human acknowledgment so that the page doesn't feel like a dead end. | Pro | Copy nit: user is supporting *the product*; "your claim" reads oddly |
| 17 | Flush features list w/ top divider inside ActiveCard | `PaywallView.tsx:104`, `module.css:181-185` | As a Pro user, I want a reminder of what my plan includes so that I use what I pay for. | Pro | Says "Everything in Pro" but offers no links into those features (e.g. "Run gap analysis →") |
| 18 | "Manage subscription" ghost button (lock icon → Stripe billing portal) | `PaywallView.tsx:107-109`, API `route.ts:31-37` | As a Pro user, I want one click to the billing portal so that I can update card, switch plan, or cancel without emailing support. | loading state (`busy === "portal"`); error path | **Lock icon mis-signifies** (security, not billing — a card/receipt icon fits); portal return URL default still points at the Flutter hash route `https://app.vaclaimpath.com/#/upgrade` (`spring-backend/.../StripeService.java:82`); no inline hint that cancel/plan-switch lives behind this button |
| 19 | BFF route POST /api/subscription (checkout/portal → `{url}` redirect) | `web/src/app/api/subscription/route.ts:14-42` | As a veteran on any tier, I want the app (not my browser) to hold Stripe secrets so that checkout is safe; as a VSO viewer I want a clean 403 instead of a broken Stripe page. | 400 invalid action, 401, 403, upstream mapping; tier whitelisting `:24` | No GET — page is server-rendered only; after returning from Stripe the client has no way to re-poll status without a full reload |
| 20 | Route-group loading skeleton | `web/src/app/(app)/loading.tsx:4-23` | As a veteran navigating to /upgrade, I want a loading placeholder so that the app feels responsive while subscription status loads. | loading; `aria-busy`/`aria-label` set | Skeleton is shaped like the **Home** page (hero + 2-col grid), not the paywall — visible layout jump on slow loads |
| 21 | Route-group error boundary (ErrorState + retry) | `web/src/app/(app)/error.tsx:12-17` | As a veteran, I want a retry screen if the page crashes so that I'm not stuck on a blank page. | error + retry | Rarely reachable for this page: `getSubscription` swallows all errors (`endpoints.ts:61-67`), so real failures bypass this boundary and render the wrong branch instead |
| 22 | Entry CTA — Sidebar footer "Upgrade to Pro / AI synthesis & gap analysis" (sparkle tile) | `web/src/components/shell/Sidebar.tsx:56-66` | As a free user anywhere in the app, I want a persistent upgrade path so that I can buy the moment I'm convinced. | hidden when `isPro` (`:56`); desktop only | Not in catalog scope but the primary desktop entry; no price shown |
| 23 | Entry CTA — Profile "Upgrade to Pro … $11.99/mo" card (chevron) | `web/src/components/profile/ProfileView.tsx:80-93` | As a free user reviewing my account, I want the upgrade offer with price so that I can act where billing decisions live. | hidden when Pro; mobile-reachable (mobile account access) | Price hardcoded "$11.99/mo" in copy — will drift if `plans` from backend change (paywall reads live data, this card doesn't) |
| 24 | Entry CTA — UploadCard Pro-gate alert "Evidence upload & AI extraction are a Pro feature. → Upgrade to Pro" | `web/src/components/documents/UploadCard.tsx:67-74` | As a free user blocked at the moment of need, I want the gate to link straight to /upgrade so that I can unblock in one step. | `needsPro` + `role="alert"` | **Copy contradicts the product**: free tier includes uploads (commit b6ac332 "evidence upload is free"); message claims upload itself is Pro |
| 25 | Dev preview page `/dev/upgrade` (fixture-fed PaywallView) | `web/src/app/dev/upgrade/page.tsx:6-14`, fixture `fixtures/subscription.ts:3-15` | As a developer, I want a stateless render of the paywall so that I can iterate on UI without a backend. | free state only | Fixture only models `active:false` — the ActiveCard/Pro branch has no dev preview; fixture duplicates `DEFAULT_PLANS`/`DEFAULT_FEATURES` |
| 26 | Button loading spinner (shared) | `web/src/components/ui/Button.tsx:36-44` | As a free user who clicked Subscribe, I want immediate feedback so that I don't click twice. | `aria-busy`, `disabled`, spinner `aria-hidden` | Spinner replaces the icon, not the label — good; but see element 10 race on the sibling button |

**Count: 26 distinct elements** (21 in the paywall surface proper, 3 entry CTAs, 1 dev preview, 1 shared loading affordance). All 26 required newly written stories — none documented anywhere in repo.

---

## Orphans (no honest user story)

1. **Lock icon on "Manage subscription"** — `PaywallView.tsx:107`. A lock signifies security/privacy; the action opens a billing portal. No persona reads "lock" as "manage billing." Decoration with the *wrong* meaning. Swap for a card/receipt/settings glyph or drop it.
2. **Raw tier suffix "· monthly" in the ActiveCard title** — `PaywallView.tsx:98-100`. The user story ("know which tier renews") is real, but the element as shipped renders a lowercase backend enum, not a label; in its current form it's data leakage, not UI.
3. **`publishable_key` on `SubscriptionStatus`** — `web/src/lib/models/api.ts:110`. Fetched on every status call, never read by any component (Stripe is fully server-side via redirect URLs). Vestigial field; candidate to drop from the DTO.
4. **Duplicated hero subtext** — `PaywallView.tsx:53-56` vs `AppShell.tsx:40`. Same sentence twice on one screen; one of the two is redundant on desktop.

Borderline (kept, story defensible): hero sparkle chip (#2) — pure decoration, but correctly `aria-hidden` and consistent with the sparkle = AI/premium motif used at all three entry points.

---

## Missing Affordances (stories with no element)

1. **"As a Pro user, I want to see when my subscription renews or expires so that I'm not surprised by a charge."** — `expires_at` exists in the DTO (`api.ts:106`) and is **dropped** by the adapter (`endpoints.ts:141-146`); the ActiveCard shows no date.
2. **"As a free user who just paid, I want confirmation when I land back from Stripe so that I know it worked."** — No success/cancel handling on `/upgrade` (no query-param read, no toast, no re-poll); the server-rendered state may even still say "not subscribed" if the webhook hasn't landed. Backend appends `session_id` to the success URL (`StripeService.java:141-142`) and nothing in `web/` consumes it.
3. **"As a Pro user who cancelled, I want to see 'your plan ends on {date}' so that I know I keep access until period end."** — Backend explicitly lets the period run out (`StripeService.java:37,403-404`); no UI state for cancelled-but-active.
4. **"As a hesitant veteran, I want 'cancel anytime' + terms/refund links near the Subscribe button so that I trust the purchase."** — No legal/trust copy anywhere on the paywall.
5. **"As a free user, I want to see what stays free (uploads, VSO sharing) so that I know I'm not losing access by not paying."** — The paywall lists only Pro features; the free tier is never described, and the one place it's mentioned (UploadCard gate) gets it wrong.
6. **"As a budget-minded user, I want the annual savings quantified so that 'Best value' is verifiable."** — No "save ~$24/yr" or "2 months free" math.
7. **"As a Pro user with a billing problem, I want a support contact from this page so that a failed portal launch isn't a dead end."** — Error banner has no escalation path.
8. **"As a veteran on a slow connection, I want the upgrade page's loading state to resemble the upgrade page."** — Only the Home-shaped route-group skeleton exists (`(app)/loading.tsx`).

---

## A11y & Consistency

**Good:**
- `role="alert"` on the error banner (`PaywallView.tsx:75`) and on the UploadCard gate.
- Buttons: 44px min tap target (`Button.module.css:13`), `aria-busy` + `disabled` while loading (`Button.tsx:36`).
- Decorative icons consistently `aria-hidden` (Icon defaults to hidden without `title`, `Icon.tsx:218-220`).
- Real `<h1>`, semantic `<ul>/<li>` feature list.
- Zero raw hex in the module — all colors via tokens (`--card`, `--ai-bg`, `--strong-bg`, `--missing-bg`…), with dark-mode variants defined in `web/src/styles/styles.css` (e.g. `:162,168,171`).
- Mobile: plan grid collapses to one column at <760px (`module.css:201-205`).

**Issues:**
1. **Identical accessible names**: two "Subscribe" buttons with no plan/price context for screen readers (`PaywallView.tsx:141-148`). Needs `aria-label={'Subscribe to ' + plan.label + ', $' + price + ' per ' + period}`.
2. **Error proximity**: alert renders after the features card, far below the plan buttons that triggered it (`PaywallView.tsx:74-78`) — likely off-screen on mobile at the moment of failure; no focus move.
3. **Double-submit race**: while plan A is busy, plan B's button and the portal button remain enabled (`busy` only disables the matching button, `PaywallView.tsx:145`).
4. **State integrity (worst)**: `getSubscription` returns `null` on *any* error (`endpoints.ts:61-67`) and `loadSubscription` defaults `active:false` + hardcoded plans (`:141-146`) — a backend outage shows a paying customer the sales paywall with live Subscribe buttons. Error masquerades as "not subscribed."
5. **Copy/consistency**: portal failure says "checkout" (`PaywallView.tsx:37`); header says "Upgrade to Pro" for Pro users (element 1); UploadCard gate copy contradicts free-tier uploads (`UploadCard.tsx:68-69` vs commit b6ac332); price duplicated in ProfileView copy (`ProfileView.tsx:88`) vs backend-driven plans.
6. **Token nits** (minor, matches house style elsewhere): hardcoded dimensions 52px/32px/22px chips (`module.css:29-30,119-123,158-166`), `gap: 2px`, `margin-top: 1px`, raw rem font sizes & letter-spacing — colors/spacing/radius are tokenized, type scale is not.
7. **Fixture/dev coverage**: `/dev/upgrade` renders only the free branch; Pro/ActiveCard, error, and per-button loading states have no preview; fixture is a third copy of plans/features content.

---

## Verdict

The paywall is small, coherent, and almost every element earns its place — the free→Pro pitch (hero, two plan cards, feature list) and the Pro state (confirmation + portal) map cleanly onto real stories, the BFF route is properly hardened (tier whitelist, 401/403/400 mapping), and tokens/dark-mode/mobile are genuinely covered. The failures are at the *edges of the purchase lifecycle*, not the happy path: an outage silently demotes Pro users to the sales pitch (the single most dangerous bug here), nothing closes the loop after Stripe redirects back, `expires_at` is fetched and thrown away, and the two Subscribe buttons are indistinguishable to assistive tech. Fix those four plus the contradictory UploadCard gate copy and this area is in strong shape; the orphan list is short (a mis-signifying lock icon, a leaked enum, a dead DTO field, one duplicated sentence) and all cheap to cut.
