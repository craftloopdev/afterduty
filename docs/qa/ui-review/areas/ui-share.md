# UI Review — Share / VSO Collaboration (user-story coverage)

Scope: `web/src/app/(app)/share/page.tsx`, `web/src/components/share/ShareManager.tsx` (+ `.module.css`),
`web/src/app/api/share/route.ts`, `web/src/lib/adapters/share.ts`, plus shell context (`AppShell.tsx`),
entry points (`home/ShareCard.tsx`, `profile/ProfileView.tsx`), fixtures (`lib/fixtures/shares.ts`,
`app/dev/share/page.tsx`), and the Spring backend share contract (`ShareController.java`, `ShareService.java`)
used to verify which UI states are actually reachable.

All paths below are relative to `/Users/rusticus/Developer/va-claim-path-app`.

---

## Element Catalog

| # | Element | file:line | User story | States covered | Gaps |
|---|---------|-----------|------------|----------------|------|
| 1 | Page title "Share with a VSO" + sub "Invite an accredited rep to securely review your claim." | `web/src/components/shell/AppShell.tsx:37-38` | As a veteran, I want to know what this page does and that it is safe, so that I share confidently. | Desktop + mobile (TopBar), dark mode (tokens) | Copy is near-duplicated by element 2 — pick one. |
| 2 | Lead paragraph ("They never see your data unless you invite them.") | `web/src/components/share/ShareManager.tsx:93-96`; `ShareManager.module.css:7-10` | As a privacy-conscious veteran, I want explicit reassurance that access is opt-in, so that I trust the feature. | All viewports, dark mode | Redundant with TopBar sub; consolidate. |
| 3 | Invite card (container) | `ShareManager.tsx:98-136`; `ShareManager.module.css:11-20` | As a veteran, I want one clear place to grant a rep access, so that the flow is obvious. | Light/dark via tokens, mobile (max-width 560px wrap, `module.css:1-6`) | — |
| 4 | "Email address" label + email input (placeholder `rep@vso.org`, `inputMode=email`, Enter submits) | `ShareManager.tsx:99-110`; `module.css:21-42` | As a veteran, I want to enter my rep's email, so that the invite is tied to their identity. | Disabled-while-busy, focus-visible border (`module.css:39-42`) | Error not associated with input (no `aria-invalid`/`aria-describedby`); validation is only `includes("@")` (`tsx:52`); no `<form>` element (Enter handled manually, `tsx:108`). |
| 5 | Toggle "Share analysis access" (default ON) | `ShareManager.tsx:111-115` | As a veteran, I want to choose whether my rep sees conditions/ratings/gaps, so that I control sensitive analysis. | On/off, `aria-pressed` | No upfront free-vs-Pro signal (see element 11 / Orphan O2); should be `role="switch"`. |
| 6 | Toggle "Allow document uploads" (default OFF) | `ShareManager.tsx:117-122` | As a veteran, I want to let my VSO add records on my behalf, so that they can complete my file. | On/off | Same switch-semantics note as #5. |
| 7 | Toggle switch visual (track + animated knob, `data-on`) | `ShareManager.module.css:65-90` | (Supports #5/#6) As a veteran, I want a glanceable on/off state, so that I know what I'm granting. | On (`--strong-dot`)/off (`--line`), dark tokens | Hardcoded dims (44/26/20/3px, `translateX(18px)`); state conveyed by color+position only (covered by `aria-pressed`, ok). |
| 8 | Primary button "Send secure invite" (share icon, full width, spinner when busy) | `ShareManager.tsx:123-125`; `web/src/components/ui/Button.tsx:36-43`; `Button.module.css:13` (44px tap target), `:65-78` (spinner) | As a veteran, I want to send a secure invite to my rep, so that they can review my claim. | Loading (`aria-busy`, spinner), disabled, dark mode | **The story is not actually fulfilled**: backend creates a share row but sends no email (`spring-backend/.../ShareService.java:66-98` — no mail integration), and the returned `acceptUrl` (`route.ts:33`) is discarded by the client (`tsx:60-73`). Also shares one `busy` flag with revoke — revoking a row makes *this* button show a spinner (`tsx:81-89`). |
| 9 | Success notice "Invite sent to {email}." (`role="status"`) | `ShareManager.tsx:126-130`; `module.css:92-98` | Intended: As a veteran, I want confirmation my invite went out, so that I know to expect my rep to accept. | Success only, polite live region | **False claim** — nothing is sent anywhere (see #8). Until email or copy-link ships this is a false affordance. Notice also persists until next action. |
| 10 | Error notice (`role="alert"`) — invalid email / 403-Pro / generic | `ShareManager.tsx:53,66-68,74-75,131-135`; `module.css:99-105` | As a veteran, I want to know why my invite failed, so that I can fix it. | Client validation, network failure, 403, dark tokens | 403 copy "needs an active Pro subscription" (`tsx:66`) has **no producing code path** — `ShareController.createShare`/`ShareService` contain no subscription gating (`ShareController.java:36-43`); speculative copy. |
| 11 | BFF route POST/DELETE `/api/share` (validation + error mapping) | `web/src/app/api/share/route.ts:13-49` | As a veteran, I want my session kept server-side while the app talks to the backend, so that my data stays secure. | 400 invalid email, 401/403/upstream mapping, 201/204 | POST response (incl. `acceptUrl`, `invitationExpiresAt`) is returned but unused by the UI. No PATCH passthrough (backend has it, `ShareController.java:54-61`). |
| 12 | Status adapter (revoked > accepted > expired > pending) | `web/src/lib/adapters/share.ts:4-19` | As a veteran, I want each share labeled by its real lifecycle state, so that I know who can see what right now. | All four states, clock-injectable (testable) | "revoked" branch unreachable from live API — `listShares` filters `RevokedAtIsNull` (`ShareService.java:106-110`). |
| 13 | Section label "SHARED WITH" | `ShareManager.tsx:140`; `module.css:107-113` | As a veteran, I want a clear register of who has access, so that I can audit it. | Shown only when list non-empty (`tsx:138`) | When empty there is no positive "No one has access" confirmation — audit story half-served. |
| 14 | Share row card | `ShareManager.tsx:143-164`; `module.css:119-127` | As a veteran, I want each rep listed with their permissions and status, so that I can review access at a glance. | Mobile (ellipsized email, `module.css:146-153`), dark mode | No tap/expand for details (created date, expiry — data exists in `ShareDto`, `web/src/lib/models/api.ts:135-147`, but is dropped by `toShare`). |
| 15 | Avatar initial circle (first letter of email) | `ShareManager.tsx:144`; `module.css:128-139` | As a veteran, I want rows visually distinguishable, so that I can scan the list quickly. (Weak but honest.) | Dark mode (`--navy100` remapped, `styles.css:160`) | Decorative; letter is read by screen readers (stray "R" before email) — should be `aria-hidden`. |
| 16 | Row text: email + permission summary ("Analysis"/"Documents only", "· can upload") | `ShareManager.tsx:145-151` | As a veteran, I want to see exactly what each person can do, so that I can verify I granted the right access. | Both permission axes, truncation | Summary is read-only; no way to change it (backend PATCH exists, unused). |
| 17 | Status pill — Active (green) / Invited (amber) / Revoked (line) / Expired (line) | `ShareManager.tsx:11-16,152`; `web/src/components/ui/Pill.tsx:11-20`; `Pill.module.css` | As a veteran, I want to see whether my rep has accepted yet, so that I can follow up. | accepted/pending/expired reachable; token colors incl. dark | "Revoked" variant unreachable (Orphan O1). "Expired" and "Revoked" share the same gray `line` tone — indistinguishable at a glance except by text. |
| 18 | Revoke icon button (close ×, `aria-label="Revoke access for {email}"`) | `ShareManager.tsx:153-163`; `module.css:158-170` | As a veteran, I want to cut off a rep's access instantly, so that I stay in control of my data. | Hidden for revoked rows, disabled-while-busy, hover danger tint | **No confirmation** for a destructive action; **failure is silent** (`revoke()` ignores `res.ok`, no error UI, `tsx:81-89`); 32×32px tap target (<44px, `module.css:159-161`); danger affordance hover-only (invisible on touch); one shared `busy` disables every row and spins the Send button. |
| 19 | Loading state (route-level skeleton) | `web/src/app/(app)/loading.tsx:4-23` (no share-specific `loading.tsx` — `ls web/src/app/(app)/share/` shows only `page.tsx`) | As a veteran on slow connection, I want a loading hint, so that I know the page is coming. | `aria-busy` + `aria-label="Loading"` | Skeleton is home-page shaped (hero + 2-col grid) — wrong silhouette for the single-column share page. |
| 20 | Error boundary (retry) | `web/src/app/(app)/error.tsx:12-17` | As a veteran, I want a retry option when the page fails, so that I'm not stuck. | Retry via `unstable_retry` | Generic copy; acceptable. |
| 21 | Empty state (list section omitted when `shares.length === 0`) | `ShareManager.tsx:138` | As a brand-new user, I want the page focused on the invite form, so that the first action is obvious. | First-run | No positive confirmation "No one currently has access" — the audit/privacy persona can't distinguish "no shares" from "section missing". |
| 22 | Entry point: Home "Share with your VSO" card → CTA "Send secure invite" | `web/src/components/home/ShareCard.tsx:9-24` | As a veteran on Home, I want a prompt to involve my VSO, so that I discover collaboration. | Server component, dark tokens | CTA text says "Send secure invite" but links to the page (doesn't send) — minor verb mismatch; same delivery falsehood downstream. |
| 23 | Entry point: Profile link row → `/share` | `web/src/components/profile/ProfileView.tsx:198` | As a veteran in account settings, I want to manage sharing, so that I can review access from a settings context. | — | Share is absent from both `Sidebar` nav (`Sidebar.tsx:7-12`) and `MobileNav` (`MobileNav.tsx:7-14`); discoverability rests entirely on the Home card and Profile row. Deliberate per nav-reorg, but worth confirming. |
| 24 | Dev fixture page + fixture data | `web/src/app/dev/share/page.tsx:6-14`; `web/src/lib/fixtures/shares.ts:3-6` | As a developer, I want a fixture render of the manager, so that I can iterate on UI without a backend. | accepted + pending rows only | Fixture covers neither `expired` nor `revoked`; dev-only (404 in prod per convention). |

Element count: **24** distinct catalogued elements/states.

---

## Orphans (no honest user story possible as shipped)

- **O1 — "Revoked" status pill + revoked-row guard** (`ShareManager.tsx:14,153`; `share.ts:6`). The live API can never return a revoked share: `ShareService.listShares` filters `RevokedAtIsNull` (`ShareService.java:106-110`). The pill, its STATUS entry, and the `s.status !== "revoked"` conditional are unreachable decoration. Either delete, or change the backend to return revoked rows so the veteran gets an access-history audit trail (the better product answer).
- **O2 — 403 → "Sharing analysis access needs an active Pro subscription"** (`ShareManager.tsx:65-66`). No code path in `ShareController`/`ShareService` produces a 403 on create; there is no subscription gating on sharing at all. The message describes a business rule that doesn't exist — it will misdiagnose any real 403 (e.g. auth edge cases) and mislead support.
- **O3 — "Invite sent to {email}." success notice** (`ShareManager.tsx:126-130`) **and the verb "Send" in the CTA** (`tsx:123`, `ShareCard.tsx:20`). Nothing is sent: the backend creates a row + token with no mail integration (`ShareService.java:66-98`), and the one artifact that could deliver access — `acceptUrl` (`ShareService.java:338-340`, surfaced by `route.ts:33`) — is thrown away by the client. As shipped this is a false affordance: the veteran believes their VSO was notified; the VSO receives nothing.

---

## Missing Affordances (persona stories with NO supporting element)

1. **Deliver the invite.** As a veteran, I want the invite emailed to my rep — or at minimum a "Copy secure link" button next to a pending row — so that my VSO can actually receive access. The data already round-trips (`acceptUrl` in the POST response); only UI is missing. *Highest priority.*
2. **Accept the invite in this app.** As a VSO viewer, I want the invite link to open an accept page, so that I can take up access. Backend has public preview + authenticated accept (`ShareController.java:74-88`) and hardcodes `https://app.vaclaimpath.com/accept-share/{token}` (`ShareService.java:43`), but there is **no `accept-share` route anywhere in `web/src/app`** — in this app the link is a dead end.
3. **Viewer mode.** As a VSO viewer with accepted shares, I want to switch into the veteran's claim, so that I can review it. `GET /api/shares/profiles` exists (`ShareController.java:90-95`); zero web UI (known-parked "viewer-aware" roadmap item — restate so it isn't lost).
4. **See invite expiry.** As a veteran, I want to see "expires in N days" on a pending invite (7-day TTL, `ShareService.java:81,95`; `invitationExpiresAt` already in `ShareDto`), so that I know to nudge my rep.
5. **Resend an expired/pending invite.** As a veteran, I want a one-tap resend, so that an expired invite isn't a dead row. Backend already supports it (re-POST same email re-issues the token, `ShareService.java:75-84`) — the UI just doesn't expose it; re-typing the email works but is undiscoverable.
6. **Edit permissions on an existing share.** As a veteran, I want to upgrade/downgrade a rep from "Documents only" to "Analysis" (or revoke upload rights) without revoke-and-reinvite. `PATCH /api/shares/{id}` exists (`ShareController.java:54-61`); no BFF passthrough, no UI.
7. **Revoke confirmation + failure feedback.** As a veteran, I want a confirm step before cutting off my rep and an error message if revocation fails, so that access control is never silently wrong (`revoke()` swallows failures, `ShareManager.tsx:81-89`).
8. **Empty-list reassurance.** As a privacy-conscious veteran, I want "No one currently has access to your claim" when the list is empty, so that I get positive confirmation of my privacy.

---

## A11y & Consistency

**A11y**
- Toggles use `button` + `aria-pressed` (`ShareManager.tsx:30`); `role="switch"` + `aria-checked` would match the visual semantics. Whole-row hit area is good (44px+ effective).
- Email input has a real `<label>` (good) but errors are not programmatically linked: no `aria-invalid`, no `aria-describedby` pointing at the alert (`tsx:101-109,131-135`).
- No `<form>` element; Enter is hand-wired via `onKeyDown` (`tsx:108`) — works, but loses native submit semantics.
- Revoke button: proper `aria-label` (`tsx:159`, good), but 32×32px tap target (`module.css:159-161`) below the 44px minimum the project's own `Button` enforces (`Button.module.css:13`), and the destructive red affordance is hover-only (`module.css:167-170`) — invisible on touch.
- Avatar initial is exposed to screen readers as a stray letter (`tsx:144`); add `aria-hidden="true"`.
- Live regions used correctly: `role="status"` for success, `role="alert"` for errors (`tsx:127,132`); `aria-busy` + hidden spinner on Button (`Button.tsx:36-38`).
- Global `:focus-visible` outline exists (`web/src/styles/base.css:55-57`) and the input has a focus border (`module.css:39-42`) — keyboard focus is visible throughout.
- One shared `busy` flag creates a confusing announced state: revoking row 3 sets `aria-busy` on the "Send secure invite" button.

**Consistency / design tokens**
- No raw hex anywhere in `ShareManager.module.css` — colors, spacing, radii all token-based. Good.
- Hardcoded sizes: input padding `13px 14px` + `1.5px` border (`module.css:31-38`), switch dims `44/26/20/3px` + `translateX(18px)` (`:65-90`), avatar `34px` (`:129-130`), revoke `32px` (`:159-161`). Component-local, but the switch and revoke dims should at least respect the 44px tap-target convention.
- "Expired" and "Revoked" share the identical `line` pill tone (`ShareManager.tsx:14-15`) — text-only differentiation.
- Page copy duplication: TopBar sub vs lead paragraph say the same thing twice (`AppShell.tsx:38` vs `ShareManager.tsx:93-96`).
- Skeleton mismatch: `/share` inherits the home-shaped loading grid (`(app)/loading.tsx`); a single-column 560px skeleton would prevent layout jump.
- Dark mode: fully covered via the `.cp-dark` token remap (`web/src/styles/styles.css:151-170`).

---

## Verdict

The page is visually disciplined (tokens, focus states, live regions, real labels) and the invite-form mechanics are solid — but the area currently fails its core user story. **The "Send secure invite" flow does not deliver anything**: no email is sent, the accept link is discarded client-side, and this app has no accept route for the link's hardcoded destination. Until delivery (email or copy-link) plus an accept page exist, the entire share loop only works if veteran and VSO coordinate out of band with a URL the UI never shows. Secondary gaps cluster around lifecycle management the backend already supports but the UI ignores: edit permissions (PATCH), resend, expiry display, revoked history, and viewer mode. Fix order: (1) deliver/copy the invite link + accept route, (2) make the success/403 copy honest, (3) revoke confirm + failure feedback + tap target, (4) expiry/resend/permission-edit, (5) empty-state confirmation and skeleton shape.
