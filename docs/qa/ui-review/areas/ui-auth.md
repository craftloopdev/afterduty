# UI Review — Login + Auth Flows (user-story coverage)

Scope: `web/src/app/(auth)/login/page.tsx`, `web/src/app/(auth)/finish-sign-in/page.tsx`, `web/src/app/(auth)/layout.tsx`, `web/src/components/auth/SessionWatcher.tsx`, `web/src/lib/firebase/session.ts`, plus `login.module.css`, `auth.module.css`, and the composed primitives `Button.tsx/.module.css`, `Sheet.tsx/.module.css`, `Icon.tsx`.

Personas used: **brand-new user** (veteran, first visit), **returning veteran**, **MFA-enrolled veteran** (enrolled via mobile app), **VSO viewer** (signs in to view a shared claim — `/share` lives inside the authed `(app)` group, so this persona passes through `/login`). Free-vs-Pro is irrelevant pre-auth.

No stories existed for this area; every story below is newly written (26 proposed).

---

## Element Catalog

| # | Element | file:line | User story | States covered | Gaps |
|---|---------|-----------|------------|----------------|------|
| 1 | Auth shell (centered 420px card on `--bg`) | `(auth)/layout.tsx:5-7`, `auth.module.css:1-12` | As a brand-new user, I want the sign-in screen focused and uncluttered so that I'm not overwhelmed on a small phone screen. | Mobile + desktop (fluid, `100dvh`, `max-width: 420px`) | No dark scheme (token system has only `navy/warm/ai` light themes, `styles.css:7,100,124`) |
| 2 | Brand mark (shield icon in navy tile) | `login/page.tsx:81-83`, `login.module.css:15-24` | As a brand-new user, I want a recognizable trust mark so that I'm confident I'm on the real VA Claim Path site before typing my email. | Static | Decorative only; `Icon` SVG has no `aria-hidden` confirmation — verify it's hidden from SRs |
| 3 | H1 "VA Claim Path" | `login/page.tsx:84`, `login.module.css:26-31` | As a brand-new user, I want to see the product name so that I know what I'm signing in to. | Static; proper `h1` | — |
| 4 | Tagline "Organize. Understand. Move forward." | `login/page.tsx:85`, `login.module.css:33-36` | As a brand-new user, I want a one-line promise of value so that I have a reason to create an account. | Static | Thin for a first-touch screen; no link to learn more (see Missing Affordances) |
| 5 | "Continue with Google" button (ghost, `person` icon) | `login/page.tsx:89-98` | As a returning veteran, I want one-tap Google sign-in so that I don't manage another password. | Disabled-while-loading | No spinner (`disabled={loading}` but `loading` prop not passed — only the email button shows progress, `page.tsx:94` vs `:145`); generic `person` icon instead of Google "G" (Google sign-in branding guidelines); no error-specific copy for popup-blocked |
| 6 | "Continue with Apple" button (env-gated) | `login/page.tsx:21,100-111` | As a returning veteran who signed up on iOS with Apple, I want Apple sign-in on web so that I reach the same account. | Disabled-while-loading | Dark by default (`NEXT_PUBLIC_ENABLE_APPLE_WEB`, `page.tsx:21`) — if the flag is off in prod, an iOS-Apple user has **no path to their account on web**; `shield` icon, not the Apple logo (Apple HIG requires Apple branding); no spinner |
| 7 | "Continue with phone" button | `login/page.tsx:113-125` | As a veteran without a usable email/Google account, I want to sign in with my phone number so that I'm not locked out. | Disabled-while-loading | `chat` icon is a weak metaphor for SMS; no spinner |
| 8 | Divider "or use email" (CSS pseudo-element rules) | `login/page.tsx:128-130`, `login.module.css:44-57` | As a brand-new user, I want the OAuth vs. email paths visually separated so that I understand they're alternatives, not steps. | Static | — |
| 9 | Email field + visible label "Email address" | `login/page.tsx:132-144`, `login.module.css:59-81` | As a veteran wary of passwords, I want to type just my email so that I can get a magic link instead. | Disabled-while-loading; Enter submits (`:142`); `autoComplete="email"`, `inputMode="email"`; implicit label via wrapping `<label>` | Not inside a `<form>` (weaker autofill/password-manager heuristics); focus indicator is border-color-only (`login.module.css:78-81`); client validation is just `includes("@")` (`page.tsx:62`) |
| 10 | "Email me a sign-in link" button (spinner) | `login/page.tsx:145-147`, `Button.tsx:36-43` | As a veteran, I want explicit confirmation I'm requesting a link (not a password reset) so that I know what to expect in my inbox. | Loading spinner + `aria-busy`; full-width; 44px min-height | No resend cooldown — can be hammered (Firebase will eventually return `too-many-requests`) |
| 11 | Email-sent notice ("Check {email}… expires in 1 hour") | `login/page.tsx:149-153`, `login.module.css:88-94` | As a veteran, I want confirmation of where the link went and how long it lasts so that I don't sit waiting on a dead link. | Success state; `role="status"` | No "wrong address? edit" / explicit resend affordance; persists alongside the still-active form, which can confuse |
| 12 | Error banner (login) | `login/page.tsx:154-158`, `login.module.css:96-102` | As a veteran whose sign-in failed, I want a plain-English reason so that I know whether to retry or change approach. | Error; `role="alert"`; friendly mapping in `friendlyError()` `page.tsx:23-31` | MFA case is a dead end (see Orphans/Missing); fallback leaks raw Firebase `e.message` (`page.tsx:30`) |
| 13 | Footer reassurance "No password to remember. We'll never share your information." | `login/page.tsx:160`, `login.module.css:104-108` | As a privacy-anxious veteran, I want a no-password/no-sharing promise so that I trust the app with claim data. | Static | Makes a privacy claim with **no privacy policy link to back it** — currently the closest thing to legal copy on the page |
| 14 | Invisible reCAPTCHA mount `#recaptcha-container` | `login/page.tsx:163`, `session.ts:87-92` | As a veteran, I want bot-protection to be invisible so that sign-in stays one step. | Invisible (`size: "invisible"`) | Google's invisible-reCAPTCHA terms require visible badge **or** disclosure text — neither present |
| 15 | Phone sheet (bottom sheet + scrim) | `login/page.tsx:162,221-280`, `Sheet.tsx:34-49`, `Sheet.module.css:1-31` | As a veteran signing in by phone, I want the two-step SMS flow in a focused overlay so that I don't lose the page behind it. | Open/close; Escape closes (`Sheet.tsx:18-20`); scrim click closes; body scroll locked; reduced-motion respected (`Sheet.module.css:61`) | No `role="dialog"`/`aria-modal`; no focus trap or focus return; bottom-sheet idiom on desktop is unusual but acceptable |
| 16 | Sheet grab handle | `Sheet.tsx:37`, `Sheet.module.css:25-31` | (none — see Orphans) | Static | Implies swipe-to-dismiss; no drag behavior exists |
| 17 | Sheet title ("Sign in with your phone" / "Enter the code we sent") | `login/page.tsx:222` | As a veteran, I want the sheet header to track which step I'm on so that I know what's being asked. | Both stages | Not associated via `aria-labelledby` (no dialog role to attach to) |
| 18 | Sheet close button (X, 38×38) | `Sheet.tsx:41-43`, `Sheet.module.css:45-54` | As a veteran who changed my mind, I want to dismiss the phone flow so that I can pick another method. | Has `aria-label="Close"` | 38px — under the 44px tap-target floor the design system itself sets (`Button.module.css:13`) |
| 19 | Phone lead text (stage-aware) | `login/page.tsx:224-228`, `login.module.css:115-118` | As a veteran, I want to know a text is coming and that standard rates apply so that I'm not surprised by the SMS. | Stage 1 + stage 2 (echoes the number sent to) | — |
| 20 | Phone input ("+1" prefill, intl format) | `login/page.tsx:230-241` | As a US veteran, I want my country code prefilled so that I only type my number. | Disabled-while-busy; `autoComplete="tel"`, `inputMode="tel"`; format error copy (`page.tsx:188`) | Free-text intl format with no masking/formatting; non-US users must know E.164; no Enter-to-submit handler (unlike email field) |
| 21 | 6-digit code input (mono, letter-spaced) | `login/page.tsx:243-255`, `login.module.css:82-86` | As a veteran, I want a code-styled box so that I can visually verify the 6 digits against my text message. | `autoComplete="one-time-code"` (iOS SMS autofill), `inputMode="numeric"` | No `maxLength={6}`; no autofocus after the code is sent; no Enter-to-submit |
| 22 | Sheet error banner | `login/page.tsx:257-261` | As a veteran who typo'd the code, I want to be told it didn't match so that I can retry without restarting. | Error; `role="alert"` | — |
| 23 | "Send code" / "Verify and sign in" button | `login/page.tsx:262-264` | As a veteran, I want one clear primary action per step so that the flow never branches confusingly. | Loading spinner per step | No resend-code with countdown — only full restart via #24 |
| 24 | "Use a different phone number" link-button | `login/page.tsx:265-277`, `login.module.css:119-125` | As a veteran who entered the wrong number, I want to back up a step so that I don't have to close and reopen the sheet. | Stage 2 only; disabled-while-busy | ~36px effective height (0.875rem text + `--s8` padding) — under tap-target floor; doubles as the only "resend" path, which is non-obvious |
| 25 | finish-sign-in brand mark (mail icon) + H1 "Finishing sign-in" | `finish-sign-in/page.tsx:53-58` | As a veteran who clicked the emailed link, I want immediate confirmation I'm in the right place so that I don't think the link broke. | All phases | — |
| 26 | "Signing you in…" working state | `finish-sign-in/page.tsx:60` | As a veteran, I want a progress message during the silent exchange so that I don't click the link twice. | `working` phase | Text-swap only — no `aria-live`/`role="status"`, no spinner; `done` phase (`page.tsx:23,10`) renders **nothing** below the H1 — blank beat before `window.location.assign` |
| 27 | "Confirm your email" field + Continue button | `finish-sign-in/page.tsx:62-78` | As a veteran who opened the link on a different device, I want to confirm my address so that sign-in still completes (link + email is the proof pair). | `need-email` phase (cross-device + cleared-storage) | No explanation of *why* it's asking ("you opened this on a new device") — looks like a redundant step; no Enter-to-submit; no loading prop on Continue |
| 28 | Error banner + "Back to sign-in" button | `finish-sign-in/page.tsx:81-89` | As a veteran with an expired/reused link, I want a clear dead-link message and a one-tap route to request a fresh one so that I'm never stranded. | `error` phase; `role="alert"`; recovery CTA | Wrong-email-confirmed error also lands here with the generic "invalid or expired" copy — misleading for that case |
| 29 | SessionWatcher (non-visual) | `SessionWatcher.tsx:7-10`, `session.ts:139-149`, mounted `(app)/layout.tsx:24` | As a signed-in veteran mid-claim-work, I want my session silently refreshed (~1h token rotation) so that I'm never dumped to login while writing. | Silent retry on failure (`session.ts:144-146`) | Correctly invisible; no element needed |

**Distinct visual elements: 28** (#1–#28; #29 is intentionally non-visual).

---

## Orphans

1. **Sheet grab handle** — `Sheet.tsx:37`, `Sheet.module.css:25-31`. Decoration masquerading as function: it signals swipe-to-dismiss, but the Sheet has no drag/swipe behavior anywhere. Either implement swipe-down or remove it. (Shared component — same orphan everywhere Sheet is used.)
2. **"Continue with Apple" button** — `login/page.tsx:100-111`. Currently a stub unless `NEXT_PUBLIC_ENABLE_APPLE_WEB=true` is set in the deployed env (`page.tsx:21`). If the same-origin `/__/auth` handler shipped in commit 23b1608 is live, flip the flag or delete the gate; in its default state this is dead code with a real persona starving behind it (iOS Apple-sign-up users on web).
3. **`done` phase of finish-sign-in** — `finish-sign-in/page.tsx:10,23`. A declared state that renders no UI (heading-only blank screen until the hard navigation lands). Either render "Signed in — taking you to your claim…" or collapse `done` into `working`.
4. **`person` icon on the Google button / `shield` icon on the Apple button** — `login/page.tsx:92,104`. The icons carry no provider meaning (an icon-with-meaning that means the wrong thing). Honest story requires provider logos; both Google and Apple branding guidelines effectively mandate them.

---

## Missing Affordances

1. **Terms of Service / Privacy Policy links at the point of account creation** — grep over `web/src/app` finds zero "terms"/"privacy" matches. The footer (`login/page.tsx:160`) *makes* a privacy promise with nothing behind it. Legal + App Store review exposure.
2. **"Educational tool, not legal advice" disclaimer on the entry screen** — the product's core positioning is absent from the first screen a veteran sees; a `DisclaimerPill` component already exists (`components/ui/DisclaimerPill.tsx`) and isn't used here.
3. **MFA challenge flow** — `friendlyError` maps multi-factor errors to "Multi-factor sign-in isn't available here yet" (`login/page.tsx:29`). A veteran who enrolled MFA in the mobile app is **hard-locked out of web** with no resolver UI (`session.ts` never imports `getMultiFactorResolver`) and no guidance ("sign in on your phone instead").
4. **Deep-link preservation** — `(app)/layout.tsx:17` does `redirect("/login")` with no `next` param, and `afterAuth()` always sends to `/` (`login/page.tsx:43-45`). A VSO viewer following a shared-claim URL signs in and lands on the dashboard, losing the share they came for.
5. **Already-authenticated redirect on /login** — `(auth)/layout.tsx` has no session check; a signed-in veteran navigating to `/login` is shown the full sign-in form again instead of being bounced to `/`.
6. **Resend code with cooldown (phone)** — stage 2 offers only "use a different number" (full restart, new reCAPTCHA). Standard story: "As a veteran whose text never arrived, I want to resend after 30s so that I don't restart."
7. **Resend / edit-address for the email link** — the notice (`login/page.tsx:149-153`) confirms the send but offers no explicit resend or "wrong address?" path (re-clicking the main button works but isn't signposted, and there's no rate-limit feedback until Firebase errors).
8. **First-visit context / "what is this?"** — a brand-new veteran gets logo + 7-word tagline and is asked for credentials. No link to about/marketing/feature summary. (Roadmap notes "home-page parked" — until then login *is* the landing page.)
9. **reCAPTCHA disclosure** — invisible reCAPTCHA (`session.ts:89`) with the badge hidden requires "protected by reCAPTCHA" attribution text in the flow; none exists.
10. **Help/support escape hatch** — no "having trouble signing in?" contact for veterans stuck in error loops (the persona least likely to self-debug OAuth popups).

---

## A11y & Consistency

**Accessibility**
- **Sheet is not an accessible dialog** — no `role="dialog"`, no `aria-modal`, no focus trap, no initial focus, no focus return to trigger (`Sheet.tsx:34-49`). Keyboard/SR users can tab into the obscured login page behind the scrim. Escape handling and `aria-label="Close"` are present; the rest is missing. Highest-impact a11y issue in this area.
- **Tap targets under the system's own 44px floor**: Sheet close button 38×38 (`Sheet.module.css:46-47`); "Use a different phone number" ≈36px (`login.module.css:119-125`). `Button` itself enforces 44px (`Button.module.css:13`) — these two bypass it.
- **Focus indication on inputs is border-color-only** (`login.module.css:78-81`): 1.5px border swaps to `var(--focus)`, no outline/ring — likely fails focus-appearance expectations for low-vision users.
- **Provider buttons give no busy feedback**: `disabled={loading}` without `loading` (`login/page.tsx:94,106,118`), so no spinner and no `aria-busy` while the popup round-trips; only the email button (`:145`) does it right. Inconsistent and confusing when a popup is slow/blocked.
- **`working` phase isn't announced** — "Signing you in…" (`finish-sign-in/page.tsx:60`) is a plain text swap with no live region, unlike the `role="status"`/`role="alert"` discipline elsewhere.
- **Good**: implicit label association via wrapping `<label>` everywhere; `role="alert"`/`role="status"` on error/notice; `autoComplete="one-time-code"` for SMS autofill; `inputMode` set on all inputs; 1rem input font (no iOS zoom); spinner `aria-hidden` + `aria-busy` on Button; reduced-motion guard on Sheet animation; real `h1` on both pages.
- **Input ergonomics gaps**: no `<form>` wrapper (autofill heuristics); code input lacks `maxLength={6}` and autofocus; phone/code/confirm-email inputs lack Enter-to-submit (email field has it, `login/page.tsx:142` — inconsistent).

**Design tokens / consistency**
- Token usage is generally strong (`--s*`, `--r-*`, semantic colors throughout `login.module.css`). Violations: `Sheet.module.css:4` raw `rgba(15,20,32,.5)` scrim; `:53` raw `rgba(120,130,150,.12)`; `:13` raw `26px` radius; `:26-28` raw grab-handle dims; `login.module.css:16-17` raw `88px` mark; `:73` raw `1.5px` border; `Button.module.css:29` raw `11px` radius. None catastrophic; the scrim/close-button rgbas are the ones that will break if a dark scheme ever ships.
- **No dark mode at all**: theme system is three light palettes via `data-theme` (`styles.css:7,100,124`); no `prefers-color-scheme` handling. State gap for every element above.
- `friendlyError` fallback surfaces raw Firebase `error.message` (`login/page.tsx:30`) — inconsistent with the otherwise plain-English voice.
- Error copy duplication: `friendlyError` lives only in `login/page.tsx` but finish-sign-in hand-rolls its own messages — fine today, drift risk.

---

## Verdict

The auth area is small, coherent, and almost every rendered element earns its place — 24 of 28 visual elements map cleanly to a real persona story, and state discipline (loading/error/success) is better than typical for a young codebase. The four orphans are cheap fixes (grab handle, provider icons, blank `done` phase, env-gated Apple button). The serious gaps are *missing* things, not excess: **no legal/privacy/disclaimer links at the exact moment a veteran hands over PII for a disability claim**, **a hard lockout for MFA-enrolled users**, **lost deep links for VSO viewers**, and **a Sheet that isn't an accessible dialog**. Recommend prioritizing: (1) ToS/privacy + disclaimer links on login, (2) MFA resolver or at least redirect-to-mobile guidance, (3) `next=` deep-link round-trip, (4) Sheet dialog semantics + focus trap, (5) resolve the Apple flag.
