# UI Review — Profile / Settings / Account (Next.js web)

Scope: `web/src/app/(app)/profile/page.tsx`, `web/src/components/profile/ProfileView.tsx` (+ `.module.css`), `web/src/app/api/account/route.ts`, `web/src/lib/fixtures/profile.ts`, plus shared elements the page composes (`Modal`, `Button`, group-level `loading.tsx`/`error.tsx`) and entry points (`TopBar` avatar, `Sidebar` user chip).

Data flow: server component `profile/page.tsx:4-6` calls `loadProfilePage()` (`web/src/lib/api/endpoints.ts:154-175`, parallel `/auth/me` + `/auth/profile` + `/subscription/status`); account email/phone/MFA stream in client-side via Firebase `watchAccount` (`web/src/lib/firebase/session.ts:30-44`). The fixture (`web/src/lib/fixtures/profile.ts`) feeds only the dev preview `web/src/app/dev/profile/page.tsx` (404s in prod per project convention) — it is not a prod stub.

Personas used: **brand-new user** (no service profile yet), **veteran (free)**, **Pro user**, **admin**. There is no VSO role in the user model (`web/src/lib/models/api.ts:78` — `"veteran" | "admin"`), so VSO-viewer stories don't apply to this page today.

---

## Element Catalog

| # | Element | file:line | User story | States covered | Gaps |
|---|---------|-----------|------------|----------------|------|
| 1 | Page route + server load | `web/src/app/(app)/profile/page.tsx:4-7` | As a veteran, I want one place for my identity, plan, appearance, and account actions so I don't hunt through the app. | SSR data; group error boundary w/ retry (`(app)/error.tsx:12-17`); group loading skeleton | Loading skeleton is Home-shaped (2-col grid, `(app)/loading.tsx:8-20`), not profile-shaped |
| 2 | Profile header card | `ProfileView.tsx:67-77`; CSS `:28-60` | As a veteran, I want to see whose account I'm in so I trust I'm editing my own data. | Light/dark via tokens; long-name truncation via `min-width:0` | — |
| 3 | Avatar initial disc | `ProfileView.tsx:69-71`; CSS `.avatar:33-44` | As a veteran, I want a visual anchor for my identity consistent with the TopBar/Sidebar avatar. | `aria-hidden` (name adjacent) — correct; initial computed server-side (`endpoints.ts:168`) | No photo support (acceptable); 52px raw px size |
| 4 | Name | `ProfileView.tsx:73` | As a veteran, I want my display name shown so I can confirm the account. | Fallback chain `displayName → email → "Your account"` (`endpoints.ts:156`) | Not editable anywhere (see Missing Affordances) |
| 5 | Subline "branch · Veteran/Admin" | `ProfileView.tsx:35-37,74` | As a veteran, I want my branch and role at a glance. As an admin, I want to see I'm in an admin account. | Branch-null collapses cleanly via `.filter(Boolean)` | Any non-admin role renders "Veteran" — will mislabel future VSO accounts |
| 6 | Upgrade-to-Pro card (link → `/upgrade`) | `ProfileView.tsx:80-93`; CSS `.proCard:63-96` | As a free user, I want to see what Pro adds and the price so I can decide to upgrade. | Free-only (`!profile.isPro`); dark-mode tokens (`--ai-bg/--ai-fg`) | Nothing replaces it for Pro users (see Missing Affordances #1); `$11.99/mo` hardcoded here while `/upgrade` fetches plans (`endpoints.ts:121-124`) — drift risk |
| 7 | Sparkle chip on Pro card | `ProfileView.tsx:82-84` | (decorative brand mark for the AI tier) — supports story #6 | `aria-hidden`; Icon defaults aria-hidden | Pure decoration, fine as support |
| 8 | Chevron on Pro card | `ProfileView.tsx:91` | As a free user, I want a cue this card is tappable. | Icon aria-hidden by default | — |
| 9 | "Account details" section label | `ProfileView.tsx:97`; CSS `.secLabel:18-25` | As a veteran, I want settings grouped so I can scan. | Uppercase, token colors | Rendered as `<div>`, not a heading — SR users can't navigate by headings |
| 10 | Email row | `ProfileView.tsx:99-102` | As a veteran, I want to confirm which email this account uses (esp. with Google/Apple/email-link sign-in) so shares and receipts go to the right place. | Fallback `account?.email → profile.email → "—"`; live via `watchAccount` | No change-email affordance |
| 11 | Phone row | `ProfileView.tsx:103-106` | (weak) As a phone-sign-in veteran, I want to confirm my number. | "—" fallback | **Orphan-leaning**: Google/Apple/email users (most) see "—" forever; no add/edit path; row is dead weight for them |
| 12 | Two-step verification row | `ProfileView.tsx:107-110` | (weak) As a security-minded veteran, I want to confirm 2FA is on so I feel my medical records are safe. | On/Off text | **Stub**: no enroll/manage control, so "Off" is a dead end; shows "Off" while Firebase still loading (`account === null` → falsy); "On (SMS)" hardcodes SMS regardless of `factorId` (`session.ts:38`) |
| 13 | Row dividers (data rows) | CSS `.dataRow:99-113` | (visual rhythm; supports scanability) | Token `--line-soft`, dark-mode aware | — |
| 14 | "Service summary" label + card | `ProfileView.tsx:115-131` | As a veteran, I want my branch/era/MOS on file because they drive presumptives and buddy-statement context for my claim. | Null → "—" per row; year-range formatting `start – Present` (`endpoints.ts:157-164`) | Read-only; **no edit/add affordance**; brand-new user sees three "—" rows with no CTA to fix it |
| 15 | "Appearance" label | `ProfileView.tsx:135` | As a veteran (often older, low vision), I want display controls grouped where I expect them. | — | `<div>` not heading |
| 16 | Dark-mode switch | `ProfileView.tsx:138-151`; CSS `.switch:149-181` | As a veteran reading long documents at night, I want dark mode to reduce eye strain. | `role="switch"`, `aria-checked`, `aria-label`, `:focus-visible` ring; persisted to cookie + `<html>` (ThemeProvider.tsx:56-62) — survives SSR, no FOUC | 44×26px — 26px height under 44px tap-target guidance |
| 17 | Switch thumb (CSS-only) | CSS `.switch > span:161-177` | (visual state of #16) | Animates; `--strong-dot` on; dark tokens | Raw px geometry (20px thumb, translateX(18px)) — intrinsic, acceptable |
| 18 | Theme segmented control (Navy/Warm/AI) | `ProfileView.tsx:154-170`; CSS `.seg:188-213` | As a veteran, I want to pick a comfortable palette; as a Pro-curious user, "AI" theme matches the AI-feature branding. | `role="group"` + `aria-label`, `aria-pressed`, focus ring, hover; persists; all 3 themes have dark variants (styles.css) | Labels "Navy/Warm/AI" are unexplained color words; no swatch preview — user must trial-and-error |
| 19 | Text-size segmented control (A−/A/A+) | `ProfileView.tsx:173-190` | As an older veteran with low vision, I want larger text app-wide so I can read my claim data. | `aria-pressed` + explicit `aria-label` small/default/large; persisted scale on `<html>` | 36px min-height under 44px tap target; only 3 steps |
| 20 | "Account" section label + card | `ProfileView.tsx:195-212` | As a veteran, I want account actions grouped and predictable. | — | `<div>` not heading |
| 21 | "Share with a VSO" link row → `/share` | `ProfileView.tsx:198-204` | As a veteran, I want to give my VSO read access from my account hub so they can help with my claim (core free-tier promise). | Icon aria-hidden; chevron cue; `/share` route exists | Duplicates a nav destination — fine as cross-link |
| 22 | "Sign out" button row | `ProfileView.tsx:205-210` | As a veteran on a shared/library computer, I want to sign out so my medical data isn't exposed. | `disabled` while busy (CSS `:disabled:239-242`) | No error handling — if `signOut()` throws, button stays disabled forever with no message (`ProfileView.tsx:39-43`); uses `back` (left-arrow) icon — wrong metaphor for sign-out |
| 23 | "Delete account" danger button | `ProfileView.tsx:215-223`; CSS `.deleteBtn:266-284` | As a veteran who no longer wants his medical data held, I want to permanently delete my account and data. | `--missing` token color, hover bg, focus ring; gated behind confirm modal | Visually quiet (text-only) — appropriate for danger-zone |
| 24 | Delete confirm modal (title + body) | `ProfileView.tsx:225-229`; `Modal.tsx:32-53` | As a veteran, I want to understand exactly what deletion destroys (conditions, evidence, shares) before committing. | `role="dialog"`, `aria-modal`, Escape, scrim-click close, `sm` size, reduced-motion-aware animation; close guarded while busy (`closeConfirm:58-62`) | No focus trap (Tab escapes dialog); single-click confirm for irreversible action — no type-to-confirm friction; `aria-label` not `aria-labelledby` |
| 25 | Modal error banner | `ProfileView.tsx:230`; CSS `.modalError:293-300` | As a veteran whose deletion failed, I want to know it failed and that I can retry. | `--missing/--missing-bg` tokens, dark-aware | Not `aria-live` — SR users may miss it; 401 vs 500 collapse to one message (`api/account/route.ts:13-16`) |
| 26 | Modal actions: Cancel (ghost) / "Delete everything" (danger primary) | `ProfileView.tsx:231-244`; CSS `.confirmDanger:307-314` | As a veteran, I want an obvious abort path and an unambiguous destructive label. | Cancel disabled while busy; confirm shows spinner (`Button.tsx:36-43`, `aria-busy`) | Stacked full-width buttons put the destructive action last/bottom — acceptable, but destructive-styled primary relies on className override |
| 27 | DELETE `/api/account` BFF route | `api/account/route.ts:9-20` | (backend of #26) As a veteran, deleting on the web must cascade server-side and end my session. | 401/500 mapped; cookie cleared on success | — |
| 28 | Group loading skeleton | `(app)/loading.tsx:4-23` | As a veteran on slow rural internet, I want immediate feedback that the page is coming. | `aria-busy`, `aria-label="Loading"` | Shape mirrors Home (hero + 2-col grid), not the profile's single 560px column |
| 29 | Entry points: TopBar avatar / Sidebar user chip | `TopBar.tsx:29-31`; `Sidebar.tsx:67-74` | As a veteran (mobile or desktop), I want a conventional avatar entry to my account. | `aria-label="Profile & settings"` on TopBar; Sidebar chip shows name+branch+chevDown | — |

29 distinct elements catalogued; none had pre-existing documented stories, so 26 stories were written fresh (3 elements could not earn an honest story — see Orphans).

---

## Orphans

1. **Phone row** — `ProfileView.tsx:103-106`. For the dominant sign-in methods (Google, Apple, email magic-link — `session.ts:69-130`), `user.phoneNumber` is null, so most users see `Phone —` permanently with no add/edit affordance. Information-free for the majority; either add a "Add phone" action or show the row only when a phone exists.
2. **Two-step verification row** — `ProfileView.tsx:107-110`. Status with no control: a user seeing "Off" can do nothing about it from here (no MFA enrollment flow exists in the web app). As shipped it's a stub masquerading as a security feature, and it briefly reads "Off" even for enrolled users while Firebase initializes.
3. **`back` icon on Sign out** — `ProfileView.tsx:207`. A left-pointing arrow (`Icon.tsx:107`) used as a sign-out glyph; the metaphor is "navigate back", not "exit session". Decoration miscast as meaning; swap for a dedicated logout icon.

Not orphans, but watched: the "AI" theme label (`ProfileView.tsx:17`) is a real selectable theme with full token support, yet the one-word label gives no preview of what choosing it does.

---

## Missing Affordances

1. **Pro user: see and manage subscription — no path exists at all.** When `isPro`, the profile hides the upgrade card (`ProfileView.tsx:80`) and the sidebar hides its `/upgrade` link (`Sidebar.tsx:56`). The only "Manage subscription" button in the app lives on `/upgrade` (`PaywallView.tsx:108`), which a Pro user can now only reach by typing the URL. Story: *As a Pro user, I want to see my plan, renewal date, and a manage/cancel button so I'm not trapped in a subscription.* Severity: highest — billing dead-end and a likely store/compliance issue.
2. **Edit service summary.** Branch/service dates/MOS are read-only (`ProfileView.tsx:115-131`) with no edit or empty-state CTA. *As a brand-new user who skipped onboarding, I want to add my branch/era/MOS so analysis and presumptive matching work.* Three "—" rows currently lead nowhere.
3. **Plan indicator for everyone.** Account details has Email/Phone/2FA but no "Plan: Free / Pro" row; a Pro user's only signal is the *absence* of the upgrade card.
4. **Edit display name** (and email/phone change). Name comes from the auth provider with no override.
5. **Enroll/manage two-step verification** — companion to orphan #2.
6. **Export my data**, especially adjacent to "Delete everything" (`ProfileView.tsx:226-229`): *As a veteran, I want a copy of my uploaded evidence and conditions before I destroy the account.* Deletion is offered with no export.
7. **Help / support / legal links** (contact, privacy policy, terms, app version). The disclaimer pill lives in the TopBar, but privacy/ToS — expected on an account page handling medical records — appear nowhere here.
8. **Sign-out failure feedback** — `onSignOut` (`ProfileView.tsx:39-43`) has no catch; a network failure leaves a silently disabled button.

---

## A11y & Consistency

- **No heading hierarchy in the page body.** Section labels are `<div className={styles.secLabel}>` (`ProfileView.tsx:97,116,135,196`) and the user's name is a `<b>` (`:73`). The only `h1` is the TopBar title; screen-reader users cannot jump between Account details / Service summary / Appearance / Account. Convert `secLabel` to `h2`.
- **Modal (shared) gaps**: focus moves into the dialog but is not trapped (`Modal.tsx:18-26`); error banner not `aria-live` (`ProfileView.tsx:230`); scrim uses raw `rgba(10, 18, 35, 0.42)` and raw `16px/20px` padding (`Modal.module.css:4,10,21`) instead of tokens — same scrim in dark mode.
- **False MFA "Off" during load** (`ProfileView.tsx:109`): `account === null` (pre-hydration) renders "Off" — show "—" until Firebase reports.
- **Tap targets**: switch 44×26 (`ProfileView.module.css:151-152`), segments `min-height: 36px` (`:190`) — both below the 44px guideline this audience (older, motor-impaired veterans) particularly needs.
- **Good token discipline in ProfileView.module.css**: every color is a `var(--*)` with light/dark/theme variants defined in `src/styles/styles.css` (incl. `.cp-dark:150-176`). Raw values are limited to intrinsic component geometry (avatar 52px `:34-35`, switch geometry `:151-177`, `1.5px` border `:197`) and 12 raw rem font-sizes — the latter is the project-wide idiom (174 instances across modules; no type-scale token layer exists, a repo-level gap rather than a profile one).
- **Contrast to verify**: Pro-card `small` at `opacity: 0.85` of `--ai-fg` on `--ai-bg` (`ProfileView.module.css:92-96`); inactive segment `--muted` on `--sunken`.
- **Destructive flow**: one click + one confirm deletes all medical evidence; consider type-to-confirm or a grace period, and distinguish 401 ("sign in again") from 500 ("try later") in the modal message (`api/account/route.ts:13-16`).
- **Loading-state mismatch**: `(app)/loading.tsx` skeleton is Home's layout; on `/profile` the page "shape-shifts" on load. A route-level `profile/loading.tsx` with header-card + 4 card skeletons would fix it.
- **Price duplication**: `$11.99/mo` hardcoded in the card (`ProfileView.tsx:88`) while the paywall fetches server plans (`endpoints.ts:121-147`) — a price change desyncs the profile card.

---

## Verdict

The page is small, token-disciplined, and most elements earn their place — identity header, appearance controls (genuinely strong a11y semantics on the switch/segments), VSO share cross-link, sign-out, and a well-guarded delete flow all map to honest veteran stories. The failures are of omission, not decoration: **Pro subscribers have no path to view or manage their subscription anywhere in the app once the upgrade CTAs hide**, the entire Account-details/Service-summary block is read-only status with zero edit/enroll affordances (two rows are outright dead ends), deletion is offered without export, and the body has no heading semantics. Fix the Pro dead-end first (add a plan row + manage link when `isPro`), then give Service summary an edit path or an empty-state CTA, then the heading/focus-trap/aria-live trio.
