# UI Review Draft — App Shell & Navigation (user-story coverage)

Scope: `web/src/components/shell/*` (AppShell, Sidebar, TopBar, MobileNav, AddModal, DisclaimerModal, ThemeProvider) + `web/src/app/(app)/layout.tsx`, plus the shared primitives the shell composes (Modal, DisclaimerPill, Icon) and the shell-level `loading.tsx` / `error.tsx`. All paths relative to `web/src/` unless noted. Audited 2026-06-10 on branch `feat/next-web-foundation`.

Personas used: **veteran** (returning, signed-in), **brand-new user** (first session), **free user**, **Pro user**, **VSO viewer** (invited rep). Note: the shell has no VSO-viewer mode at all (viewer-aware shell is explicitly parked per project notes) — every element below assumes the claim owner.

---

## Element Catalog

| # | Element | file:line | User story | States covered | Gaps |
|---|---------|-----------|------------|----------------|------|
| 1 | Shell frame (sidebar + main + 1240px content column) | `components/shell/AppShell.tsx:60-81`, `AppShell.module.css:1-26` | As a veteran, I want a consistent, readable frame on every page so I always know where app chrome ends and my claim data begins. | Desktop & mobile (content padding swaps at 760px; 96px bottom clearance for MobileNav, `AppShell.module.css:24`) | Bottom clearance (96px) and MobileNav height (72px, `MobileNav.module.css:11`) are coupled magic numbers in two files. |
| 2 | Dynamic page title (h1) | `AppShell.tsx:27-49` → `TopBar.tsx:20` | As a veteran, I want the current section named at the top so I never lose my place. | All 8 routes incl. fallback | Unknown paths silently fall back to the Home title ("Welcome back") — a typo'd URL looks like Home. |
| 3 | Personalized greeting "Welcome back, {firstName}" | `AppShell.tsx:44-47` | As a returning veteran, I want to be greeted by name so the app feels like my claim, not a portal. | Name present / name missing fallback | Brand-new user sees "Welcome back" on their **first ever** visit — wrong for that persona; no first-run variant. |
| 4 | Page subtitle | `AppShell.tsx:30-46` → `TopBar.tsx:21` | As a veteran, I want one plain-language line of context per section (e.g. "scored on the VA triad") so I understand what the page is for. | 7 of 8 routes | `/profile` has no sub (likely intentional); copy "unlock extra pay" (`AppShell.tsx:32`) is promissory language worth a legal read for an educational tool. |
| 5 | Brand mark (shield icon tile) | `Sidebar.tsx:27-29`, `Sidebar.module.css:28-35` | As a veteran, I want a trust signal identifying the product so I know whose hands my records are in. | Desktop only (sidebar hidden <760px) | Not a link — convention is logo→Home. Mobile has **no brand presence at all** in the shell. |
| 6 | Wordmark + tagline "Organize. Understand. Move forward." | `Sidebar.tsx:30-33` | As a brand-new user, I want the product's promise in one line so I know what it does for me. | Desktop only | Same as #5: invisible on mobile. |
| 7 | Sidebar "Add evidence" button | `Sidebar.tsx:36-38`, `Sidebar.module.css:50-65` | As a veteran, I want a permanent, prominent way to add evidence because more evidence is the core loop of the product. | Desktop; `:active` press feedback | No loading/disabled state (fires modal, OK). |
| 8 | Nav item: Home | `Sidebar.tsx:8,41-52` | As a veteran, I want a dashboard link so I can see where my claim stands today. | active/hover/idle; `aria-current="page"` (`Sidebar.tsx:47`) | — |
| 9 | Nav item: Conditions | `Sidebar.tsx:9` | As a veteran, I want to review the conditions built from my evidence so I can check them against the VA triad. | same | — |
| 10 | Nav item: Next Steps | `Sidebar.tsx:10` | As a veteran, I want a to-do view so I know exactly what to do next to close gaps. | same | — |
| 11 | Nav item: Documents | `Sidebar.tsx:11` | As a veteran, I want to see everything I've uploaded so I can confirm what the app has processed. | same | — |
| 12 | Active-route highlight | `Sidebar.tsx:46-47`, `Sidebar.module.css:87-90`; `MobileNav.tsx:29-30` | As a veteran, I want my current section highlighted so I can orient at a glance. | 4 nav routes, desktop+mobile | On `/share`, `/upgrade`, `/ask`, `/profile` **nothing** in either nav is highlighted — user appears "nowhere". |
| 13 | Upgrade-to-Pro card (sparkle tile + "AI synthesis & gap analysis") | `Sidebar.tsx:56-65`, `Sidebar.module.css:98-127` | As a free user, I want to see what Pro adds so I can decide whether the AI analysis is worth paying for. | Free only (hidden when `isPro`, `Sidebar.tsx:56`) | Desktop only — free users on mobile get no persistent upgrade affordance in the shell. Nothing replaces it for Pro users (see Missing Affordances). Raw `rgba(124,92,230,.18)` background (`Sidebar.module.css:104`). |
| 14 | User chip (avatar initial + name + branch + chevron) | `Sidebar.tsx:67-74` | As a veteran, I want one-click access to my account/settings from anywhere on desktop. | Hover; long-name ellipsis (`Sidebar.module.css:156-162`) | `branch` is **always null** (`app/(app)/layout.tsx:27`) so the branch line never renders (orphan); chevron-down implies a popover menu but it's a plain link to `/profile` (misleading affordance). |
| 15 | DisclaimerPill "Educational" | `TopBar.tsx:24`, `components/ui/DisclaimerPill.tsx:10-17`, `DisclaimerPill.module.css:1-20` | As a veteran (and as the business), I want a persistent reminder that this is an educational tool so I never mistake estimates for legal advice. | All routes, all viewports | ~27px tall tap target (6px/11px padding, 0.75rem text); hover hint is `title` attr only — invisible on touch. |
| 16 | TopBar "Add evidence" button | `TopBar.tsx:25-28`, `TopBar.module.css:48-71` | As a veteran deep in any page, I want add-evidence within reach without scrolling back somewhere. | Wide (icon+label) and narrow <1100px (icon-only) | **Icon-only mode has no accessible name**: label is `display:none` (`TopBar.module.css:64-66`, removed from AT) and the plus Icon is `aria-hidden` (`components/ui/Icon.tsx:220`). Blind users get an unlabeled button on tablets/mobile. |
| 17 | TopBar avatar → `/profile` | `TopBar.tsx:29-31`, `TopBar.module.css:73-89` | As a veteran on mobile, I want to reach my account/settings — this is the **only** profile entry on mobile (sidebar hidden, MobileNav has no profile tab). | All viewports; hover invert; `aria-label="Profile & settings"` ✓ | 36px target (<44px guideline) and it's the sole mobile path to sign-out/settings/share/legal. |
| 18 | MobileNav tab: Home | `MobileNav.tsx:8,22-36` | As a veteran on my phone, I want thumb-level nav to my dashboard. | active/idle, `aria-current` ✓ | Labels at 0.66rem ≈ 10.5px (`MobileNav.module.css:33`) — very small. |
| 19 | MobileNav tab: Conditions | `MobileNav.tsx:9` | As a veteran on my phone, I want my condition list one tap away. | same | same |
| 20 | MobileNav tab: Steps | `MobileNav.tsx:12` | As a veteran on my phone, I want my next actions one tap away. | same | same |
| 21 | MobileNav tab: Docs | `MobileNav.tsx:13` | As a veteran on my phone, I want my documents one tap away. | same | same |
| 22 | MobileNav center FAB (+) | `MobileNav.tsx:41-43`, `MobileNav.module.css:40-54` | As a veteran on my phone, I want the primary action (add evidence) to be the biggest, most reachable control. | `aria-label="Add evidence"` ✓; press scale; safe-area inset handled (`MobileNav.module.css:12`) | — |
| 23 | AddModal "Add to your claim" | `components/shell/AddModal.tsx:26-41` | As a veteran, I want to choose *how* to add information (file, memory, known condition) because my evidence comes in different forms. | Open/close, Esc, scrim-click (via `Modal.tsx:18-33`) | All three options run the same handler `go()` → `/documents` (`AddModal.tsx:21-24`); no intent is carried (no query param, no distinct flow). |
| 24 | Add option: "Upload documents" | `AddModal.tsx:9` | As a free user, I want to upload medical/service records and letters so the app can organize my claim (free tier's core right). | navy tone tile; hover | Honest — `/documents` has `UploadCard` (`app/(app)/documents/page.tsx:11`). |
| 25 | Add option: "Describe what you remember" | `AddModal.tsx:10` | *(intended)* As a veteran without paperwork, I want to tell my story in my own words and have AI extract facts. | — | **ORPHAN/stub**: routes to `/documents`, which contains no describe/dictate flow (grep of `components/documents/*` and the page: no match). Promise "AI extracts the facts" is not deliverable from where it lands. Also an AI (paid) feature shown to free users with no Pro hint. |
| 26 | Add option: "Add a condition" | `AddModal.tsx:11` | *(intended)* As a veteran who knows what he wants to claim, I want to add a condition directly and have the app find supporting evidence. | — | **ORPHAN/stub**: routes to `/documents`; no add-condition flow exists there. |
| 27 | Add option chevrons | `AddModal.tsx:37` | As a veteran, I want a visual cue that each row navigates somewhere. | aria-hidden ✓ | Fine — but it honestly signals navigation that two rows betray (see 25/26). |
| 28 | DisclaimerModal shield tile | `components/shell/DisclaimerModal.tsx:17-19` | As a veteran, I want a calm trust mark on the disclaimer so it reads as protection, not fine print. | Light/dark via tokens | Decorative; acceptable. |
| 29 | Heading "About this guidance" | `DisclaimerModal.tsx:20` | As a brand-new user, I want the tool's nature named plainly before I rely on it. | — | h3 with no dialog accessible name (see A11y #2). |
| 30 | Lead paragraph ("AI-assisted educational tool…") | `DisclaimerModal.tsx:21-24` | As a veteran, I want a one-paragraph honest framing of what this app is. | — | — |
| 31 | "Does" list (green checks ×2) | `DisclaimerModal.tsx:26-37` | As a veteran, I want to know what the tool *will* do (organize records, estimate via public VASRD) so I can trust its outputs appropriately. | Light/dark (`--strong-bg/--strong`) | — |
| 32 | "Does NOT" list (red crosses ×2) | `DisclaimerModal.tsx:38-49` | As a veteran, I want the limits stated (not legal advice, not a VSO replacement) so I don't skip the human help I'm entitled to. | Light/dark (`--missing-bg/--missing`) | Color + icon shape both encode meaning ✓ (not color-only). |
| 33 | "Got it" button | `DisclaimerModal.tsx:51-53` | As a veteran, I want a clear acknowledge-and-dismiss action. | full-width primary | Acknowledgment is not recorded anywhere — purely dismissive. |
| 34 | Modal scrim + card | `components/ui/Modal.module.css:1-40`, `Modal.tsx:33-42` | As a veteran, I want the page dimmed behind a dialog so my attention is held to one decision. | Click-to-dismiss; reduced-motion respected (`Modal.module.css:29-33`) ✓ | Raw `rgba(10,18,35,.42)` scrim; no focus trap / restore / scroll-lock (see A11y). |
| 35 | Modal close (X) button | `Modal.tsx:46-48`, `Modal.module.css:57-74` | As a veteran, I want an explicit escape hatch from any titled dialog. | hover; `aria-label="Close"` ✓ | 32px target; only rendered when `title` is set, so DisclaimerModal relies solely on "Got it"/Esc/scrim. |
| 36 | Route-transition skeleton | `app/(app)/loading.tsx:4-23`, `loading.module.css` | As a veteran, I want immediate visual feedback while a page loads so the app never feels dead. | `aria-busy` + `aria-label="Loading"` ✓ | It's the **Home** skeleton (hero + 2-col grid) but, being the `(app)`-level `loading.tsx`, it shows for *every* child route — Documents/Profile flash a wrong-shaped preview. |
| 37 | Route error boundary + retry | `app/(app)/error.tsx:12-17` | As a veteran, I want a plain apology and a retry button when a page fails so a flaky connection doesn't strand me. | Error + retry (`unstable_retry`) | Generic copy for all failures; no offline-specific hint. |
| 38 | Auth gate (redirect to /login) | `app/(app)/layout.tsx:12-19` | As a veteran, I want my medical records locked behind my login so nobody else can see my claim. | Unauthorized → redirect; other errors → boundary (#37) | — |
| 39 | Theme/dark/text-scale plumbing | `components/shell/ThemeProvider.tsx:41-74` | As a veteran with low vision, I want my appearance prefs (dark, text scale 0.92–1.13×, 3 themes) honored on every page without flash. | SSR pre-applied to `<html>` (no FOUC), cookie persistence (`ThemeProvider.tsx:31-34`) | Doc comment "toggle UI ships in a later (Settings) cycle" (`ThemeProvider.tsx:39`) is **stale** — toggles already exist in `components/profile/ProfileView.tsx:147-184`. |
| 40 | SessionWatcher (invisible) | `components/auth/SessionWatcher.tsx:7-10`, mounted `layout.tsx:24` | As a veteran, I want my session silently kept fresh so I'm not logged out mid-upload. | n/a (renders null) | Listed for completeness; no visual surface. |

Element count: **40** catalogued (rows above; #40 non-visual). Newly-written user stories: **38** (every row except the two stub options whose stories are *intended*, not honest).

---

## Orphans (no defensible story as shipped)

1. **AddModal "Describe what you remember"** — `AddModal.tsx:10,21-24`. Promises "Tell us in your own words — AI extracts the facts," then dumps the user on the Documents upload page which has no free-text/describe flow. A veteran without paperwork — exactly the persona this row targets — hits a dead end. Either wire it to a real flow (Ask AI intake? a describe form) or cut it until that ships.
2. **AddModal "Add a condition"** — `AddModal.tsx:11,21-24`. Same dead-end: `/documents` has no add-condition capability. The condition list is AI-built (Pro); this row implies manual entry that doesn't exist.
3. **Sidebar branch subtitle** — `Sidebar.tsx:71` renders `branch` which is hardwired to `null` at the only call site (`app/(app)/layout.tsx:27`). Dead UI + dead prop threaded through `AppShell.tsx:14,65`. Cut the prop or populate it from the profile.
4. **User-chip chevron-down** — `Sidebar.tsx:73`. A down-chevron grammatically promises a disclosure menu (sign out, settings, switch account); the chip is a flat link to `/profile`. Decoration masquerading as function — use a right-chevron or no icon, or build the menu.

---

## Missing Affordances (stories with no supporting element)

1. **"As a Pro user, I want to open Ask AI from anywhere"** — `/ask` is a flagship paid feature with a TopBar title (`AppShell.tsx:41-42`) but **zero nav entry** in Sidebar, MobileNav, or TopBar. Its only inbound link in the entire app is `ConditionDetail.tsx:184` (`window.location.assign("/ask")` — a full page reload, no less). Pro users who paid for chat cannot find it.
2. **"As a Pro user, I want confirmation I'm Pro"** — when `isPro` the sidebar upsell vanishes (`Sidebar.tsx:56`) and nothing replaces it. No badge, no "Pro" mark anywhere in the shell; a paying user sees no difference from chrome.
3. **"As a free user on mobile, I want to discover Pro"** — the upgrade card is sidebar-only (hidden <760px); MobileNav/TopBar carry no upgrade affordance. Mobile free users only meet the paywall via the Documents upload CTA or Profile.
4. **"As a veteran, I want my VSO-sharing one tap away"** — sharing is the free tier's headline feature, yet `/share` has no persistent nav slot (desktop or mobile); it's reachable only via the Home ShareCard (`components/home/ShareCard.tsx:19`) and Profile (`ProfileView.tsx:198`).
5. **"As a brand-new user, I want to understand what this tool is before I rely on it"** — the disclaimer is opt-in only (pill click). No first-run presentation or recorded acknowledgment; "Got it" (`DisclaimerModal.tsx:51`) persists nothing.
6. **"As a free user, I want to know which add-methods need Pro before tapping"** — AddModal's AI option ("Describe…") shows no lock/Pro chip to free users, setting up a bait-then-paywall (or, today, bait-then-dead-end).
7. **"As a keyboard user, I want to skip past the nav to the content"** — no skip-to-main link; every page starts with the full sidebar tab order.
8. **"As a veteran on /share, /upgrade, /ask or /profile, I want to see where I am in the nav"** — `activeKey` resolves these (`AppShell.tsx:21-23`) but no nav element represents them, so both navs go dark (catalog #12).

---

## A11y & Consistency

**Accessibility**
1. **Unlabeled icon-only Add button (<1100px)** — `TopBar.module.css:64-66` hides the label with `display:none` (removed from the accessibility tree) while the Icon is `aria-hidden` (`Icon.tsx:220`): the button's accessible name is empty exactly on tablet/mobile widths. Fix: `aria-label="Add evidence"` on the button (cf. MobileNav FAB which does this right, `MobileNav.tsx:41`).
2. **DisclaimerModal dialog has no accessible name** — `Modal.tsx:39` sets `aria-label={title}` but DisclaimerModal passes no title (`DisclaimerModal.tsx:15`), so `role="dialog"` is announced nameless. Pass `title` or `aria-labelledby` the h3.
3. **Modal has no focus trap, no focus restore, no scroll lock** — `Modal.tsx:18-26` focuses the card and handles Esc, but Tab walks out into the background page, focus is not returned to the trigger on close, and the body still scrolls behind the scrim.
4. **Tap targets under 44px** — DisclaimerPill ≈27px (`DisclaimerPill.module.css:5`), Modal close 32px (`Modal.module.css:61-62`), TopBar avatar 36px (`TopBar.module.css:76-77`), collapsed Add ≈36px (`TopBar.module.css:69`).
5. **MobileNav labels 0.66rem (~10.5px)** in `--faint` (#6c7690 on white ≈ 4.9:1) — passes AA numerically but tiny for the older-veteran demographic; same micro-type at 0.66/0.69rem in Sidebar (`Sidebar.module.css:45,126,165`).
6. Positives worth keeping: `aria-current="page"` on both navs; `aria-label="Primary"` landmarks; global `:focus-visible` ring on a token (`styles/base.css:55-58`); Icon aria-hidden-by-default with opt-in `title` (`Icon.tsx:46,218-220`); reduced-motion guard on modal pop (`Modal.module.css:29`); safe-area inset on MobileNav (`MobileNav.module.css:12`); skeleton `aria-busy` (`loading.tsx:6`).

**Design-token / consistency violations** (the token layer's own contract: "Components reference var(--…) only — never raw hex", `styles/styles.css:4`)
7. `Sidebar.module.css:104` — `rgba(124, 92, 230, 0.18)` is raw `--ai` purple; won't track the AI theme or dark palette.
8. `Modal.module.css:4` — raw scrim color `rgba(10, 18, 35, 0.42)`; also hardcoded 520px/440px widths and 16/20px paddings off the `--s` scale (`Modal.module.css:17,22,26`).
9. Eight white-alpha literals in `Sidebar.module.css:34,84,88,113,135,138,146` (`rgba(255,255,255,…)`) — defensible as on-navy overlays but should be tokens (`--on-navy-overlay-*`) to honor the contract.
10. Coupled magic numbers: MobileNav height 72px / FAB 54px / `margin-top:-22px` (`MobileNav.module.css:11,42-44`) vs. the 96px clearance in `AppShell.module.css:24` — change one, break the other.
11. Ad-hoc type scale: 0.66 / 0.69 / 0.75 / 0.8 / 0.84 / 0.875 / 0.9 / 0.94 / 1.06 / 1.2 / 1.25 / 1.5rem across the seven shell files — no `--font-*` size tokens exist.
12. Navigation idiom inconsistency: `ConditionDetail.tsx:184` uses `window.location.assign("/ask")` where everything else uses `next/link`/`router.push` — full reload, loses SPA state.
13. Dark mode: shell surfaces are token-clean (verified against `.cp-dark`, `styles/styles.css:151-176`) **except** items 7–8 above, which are constant in dark.

---

## Verdict

The shell's skeleton is genuinely strong: a clean four-section IA that matches the product's loop (see status → review conditions → act on steps → manage evidence), "Add evidence" correctly enshrined as the primary action on both form factors, honest and well-crafted disclaimer content, server-side auth gating, and disciplined token usage with real dark-mode and reduced-motion coverage.

But it fails the earn-your-place test in three load-bearing spots. First, **two of the three AddModal options are dishonest** — they promise AI-describe and add-a-condition flows and deliver the upload page; for the exact persona each row targets, that's a dead end dressed as a feature. Cut or wire them. Second, **Ask AI — a paid flagship — is unreachable from the shell**; a Pro subscriber cannot navigate to the thing they bought. Third, the shell is **silent about Pro status and viewer context**: paying users get no acknowledgment, and the parked VSO-viewer mode means every chrome element presumes the claim owner.

The a11y debt is small in count but high in severity-per-item: an unlabeled primary button at tablet widths, a nameless dialog, and an untrapped modal are each one-line-to-one-afternoon fixes. Recommend: (1) fix the three a11y blockers, (2) cut or complete the two AddModal stubs, (3) give `/ask` a nav slot (and a Pro badge in the user chip), before any new shell surface is added.
