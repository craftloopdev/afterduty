# UI Regression — Home (web/) · cycle 1

Foundation + Home vertical slice of the Next.js web frontend. These journeys are the
"definition of done" for the slice and are **replayable** via the committed test suite.

- **App:** `web/` (Next.js 16 BFF in front of the unchanged Spring backend)
- **First recorded:** 2026-06-07
- **Replay (deterministic, no creds):**
  ```
  cd web && npm install
  npm test                 # Vitest: adapters, tokens, composeHomeVM
  npm run test:e2e         # Playwright: correct-actor + dev-fixture states (auto-starts dev server)
  ```
- **Replay (authed veteran, real token):** set `GOOGLE_APPLICATION_CREDENTIALS`,
  `FIREBASE_API_KEY`, `FIREBASE_PROJECT_ID`, `FIREBASE_TEST_UID`, `FIREBASE_TEST_EMAIL`,
  then `npm run test:e2e`. `tests/e2e/global-setup.ts` mints a real Firebase ID token via the
  repo's `tests/mint_token.py`, exchanges it at `/api/session`, and the authed spec runs
  instead of skipping.

## Correct-actor matrix (the regression critic — assert network/state/nav, not "it looked right")

| Actor | Setup | Asserted outcome | Status |
|-------|-------|------------------|--------|
| Unauthenticated visitor | no session cookie | `GET /` → **307 → /login**; no `Welcome back` / claim content in DOM (no leak); `/login` shows Google/Phone/Email providers | ✅ e2e + curl |
| BFF session route | — | `POST /api/session {}` → **400**; `{idToken}` → **201** + `Set-Cookie: cp_session … HttpOnly; Secure; SameSite=Lax` | ✅ e2e + curl |
| Authenticated veteran (populated) | real minted token | `/` renders the claim (not redirected); `Welcome back` + a rating visible | ✅ wired (skips without creds) |
| Brand-new user (no claim) | fixture `empty` | onboarding "Let's build your claim" + Add CTA; no NaN/crash | ✅ e2e |
| Analyzing claim (stage set, <100%, 0 conditions) | fixture `analyzing` | "We're analyzing your records" + progress bar at 45% (not the empty state — regression-locked after a real bug) | ✅ e2e + unit |
| Overflow (long names, many conditions) | fixture `overflow` | names truncate, layout holds | ✅ screenshot |
| Free vs Pro | fixture `populated` (free) | sidebar shows "Upgrade to Pro"; BFF never proxies `/api/claim/debug/*` as 200 (admin-only 403 preserved server-side) | ✅ free path / BFF allowlist |
| Veteran with a "not filing" condition (task #186) | condition with `excludedFromClaim:true` | it is dropped from **every** Home count (`ready`/`needsWork`/`presumptive`), the evidence-health totals, and the Home list; `notFilingCount` records it; a "N you're not filing — not counted here" link surfaces it; the full /conditions list still holds it | ✅ unit (`composeHomeVM`) |
| Veteran with a stale-grouped mental claim (task #186) | mental conditions absorbed (`pyramidReason` set) but **no** stored `pyramidGroup` | `GET /claim/combined-rating` labels the group deterministically from the VASRD code ("Mental Health (§4.130)") — the grouped VA-math step + its absorbed members show **without** a re-analysis; the absorber note names the real survivor, never itself | ✅ backend (`RatingControllerTest`) |
| Cross-cutting (security) | — | BFF outbound headers are an allowlist (`Authorization` + optional `X-View-As`), never `X-User-Email`; `/dev/*` → 404 in a production build | ✅ code + prod-build check |

## States captured (1280×900 desktop / 390×844 mobile)

| State | Screenshot |
|-------|-----------|
| Populated (desktop) | `home-desktop-populated.png` |
| Populated (mobile, responsive sidebar→bottom-nav, table→glance) | `home-mobile-populated.png` |
| Empty / new user | `home-empty.png` |
| Analyzing | `home-analyzing.png` |
| Overflow | `home-overflow.png` |

Console hygiene: **zero** console errors/warnings on the populated Home (asserted in e2e).

## Notes
- Apple sign-in is hidden on web by default (matches the Flutter app, which hides it on web due
  to Safari ITP on the cross-origin Firebase auth handler). Enable with
  `NEXT_PUBLIC_ENABLE_APPLE_WEB=true` once `/__/auth` is hosted same-origin.
- The Home hero's combined rating + monthly pay are **server-authoritative** (POST
  `/api/scenarios/calculate`), not recomputed client-side.
