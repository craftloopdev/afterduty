# Capacitor iOS Implementation Spec — Next.js Native Build, Full App Store Publication & Screenshot Pipeline

**Status:** approved-for-implementation draft
**Goal:** **full App Store publication** — v2.0 live on the existing listing, passing App Review on the **first** submission. This listing has been rejected before (2.1(a) sign-in failure, then 5.1.1(v) + 3.1.2(c) + a Guideline-3 price query — §H.1 ledger). **§H "App Review Proofing" is the publication gate:** nothing is submitted until every §H checklist is green with recorded evidence.
**Spec'd against:** `feat/next-web-foundation` @ `4f060a2` (+ Week-2 UI P0s landing in `web/`: subscription tri-state, profile billing row, post-checkout banners, login legal links — `src/lib/subscription-actions.ts`, `components/auth/LegalFooter.tsx`, `(auth)/login/friendly-error.ts` are already in the working tree and MUST be treated as present).
**Scope:** iOS only (Android later reuses ~90% of this). The Flutter app (`flutter_frontend/`) is the binary being **replaced** on the existing App Store listing.

**Local tooling verified (2026-06-12):** Xcode 26.5 (17F42), CocoaPods 1.16.2, Node v26.0.0, iOS 26.5 simulators including **iPhone 17 Pro Max** (6.9", renders 1320×2868) and **iPad Pro 13-inch (M5)** (renders 2064×2752). No 6.5"-class simulator exists in this runtime — see §F for how the 6.5" slot is satisfied.

---

## 0. Key decisions (summary)

| # | Decision | Rationale |
|---|----------|-----------|
| D1 | **One codebase, two build targets.** Web keeps `output:"standalone"` BFF unchanged. Native = `NEXT_PUBLIC_NATIVE=1 npm run build:native` with `output:"export"` → `web/out/` → Capacitor `webDir`. | RSC/route handlers can't run inside a static bundle; the BFF stays the web story. |
| D2 | **ApiClient seam:** extract transport from `serverFetch` into an injected `ApiFetch`; `BffApiClient` (cookie→Bearer, server-only, web) vs `DirectApiClient` (plugin ID token→Bearer, client, native). Pages branch on a build-time constant; dead branch is eliminated. No forked pages. | This is the seam the charter promised; views and adapters are 100% shared. |
| D3 | **Auth: pure passwordless OTP, plugin-only** (decided 2026-07-01, supersedes the original Apple/Google plan). `@capacitor-firebase/authentication` for native phone verify (silent APNs) + native custom-token sign-in for email codes (Spring-direct `/auth/email-code/*`). The native layer is the SOLE source of truth — no JS-SDK mirroring (an SMS code is single-use, so a native phone sign-in cannot also mint a JS credential). **TokenProvider = plugin `getIdToken()`** (native keychain persistence). No `cp_session` cookie, no `/api/session`, no `proxy.ts` natively. No Google, no Apple — with no third-party login offered, guideline 4.8 does not apply. | Strict parity with the shipped web login (passwordless-otp-auth-spec §9); native verification is silent-APNs (never the WKWebView reCAPTCHA shape behind the v1.0 2.1(a) rejection); keychain survives WKWebView storage eviction. |
| D4 | **Payments:** native shows **RevenueCat only** (`@revenuecat/purchases-capacitor`); web stays Stripe, untouched. Backend contract already shipped: `POST /api/subscription/revenuecat/sync` + webhook; app-user-id = Firebase UID; entitlement id `VA Claim Path Pro`; products `pro_monthly`/`pro_annual`; offering `default`. Native NEVER renders Stripe checkout/portal (App Store 3.1.1). | Backend + RC dashboard are already built out from the Flutter Phase-4 work (PR #40). |
| D5 | **Same bundle id `com.vaclaimpath.app`, same listing (app id 6771148030).** This ships as **v2.0 of the existing app**, not a new listing. | Preserves reviews, the approved IAP products, the RC iOS app (`app57ffb7b7e5`), Paid Apps agreement, and subscriber entitlements (RC keys off Firebase UID, store-side subs keep renewing). Firebase iOS SDK persists auth in the keychain per bundle id, so many Flutter users will stay signed in across the update (verify on device). |
| D6 | **Screenshots:** capture-only build flag (`NEXT_PUBLIC_ENABLE_DEV_ROUTES=true`) compiles the existing `/dev/*` fixture routes into the native bundle; a script boots simulators, deep-links via a custom `vaclaimpath://` scheme, and shoots `xcrun simctl io screenshot`. Release builds exclude `/dev/*` (build gate + post-build assert). | Reuses the fixture states the web e2e suite already trusts; zero prod exposure. |
| D7 | Capacitor lives at **`web/ios/` + `web/capacitor.config.ts`**; npm deps in `web/package.json`. Capacitor **7.x** line (`@capacitor/core`, `/ios`, `/cli` — pin latest stable at install). `server.iosScheme: "https"` → WebView origin `https://localhost`. | Keeps the app one npm project; `https://localhost` is in Firebase's default authorized domains and is friendlier to IndexedDB/localStorage. |
| D8 | **§H is the publication gate.** Review-proofing items (production OTP smoke on fresh accounts, reviewer test-phone-number access, paywall disclosure block + legal links, mandatory Restore Purchases, anti-steering subscriber matrix, REQUIRED offline shell + polish, purpose strings, reviewer-demo allowlist, review-notes package) are in-scope REQUIREMENTS, not polish. The owner holds the final "Submit for Review" click, pressed only against green §H checklists. | The owner has eaten three rejection rounds on this listing (§H.1). Every mitigation in §H is cheaper than one more round. |

---

## A. Bundle strategy — the native build target

### A.1 Build modes

`web/next.config.ts` branches on `process.env.NEXT_PUBLIC_NATIVE`:

```ts
const isNative = process.env.NEXT_PUBLIC_NATIVE === "1";

const nextConfig: NextConfig = {
  output: isNative ? "export" : "standalone",
  outputFileTracingRoot: appRoot,
  ...(isNative
    ? { images: { unoptimized: true } }       // no image optimizer in export
    : { async rewrites() { /* existing /__/auth proxy — web only */ } }),
};
```

`web/package.json` scripts:

```json
"build:native":        "NEXT_PUBLIC_NATIVE=1 next build",
"build:native:capture":"NEXT_PUBLIC_NATIVE=1 NEXT_PUBLIC_ENABLE_DEV_ROUTES=true next build",
"cap:sync":            "npm run build:native && npx cap sync ios"
```

Native builds also require at build time: `NEXT_PUBLIC_API_BASE=https://api.vaclaimpath.com/api`, the six `NEXT_PUBLIC_FIREBASE_*` values, and `NEXT_PUBLIC_REVENUECAT_IOS_KEY=appl_XuvonHELPKuXURtGzVeGYQklSPF` (public SDK key, safe to embed). In export mode the root layout's `readPublicFirebaseConfig()` executes **at build time** and inlines the config into the static HTML — the runtime-injection property is web-only and that's fine (these are public client values).

Things that do not exist in the native bundle and whose responsibilities move:

| Web mechanism | Native replacement |
|---|---|
| `proxy.ts` cookie guard | client `<NativeAuthGate>` in the (app) layout branch (§A.4) |
| `app/api/*` route handlers (8) | `DirectApiClient` calls straight to Spring (§A.5) |
| `/__/auth` same-origin rewrite | not needed — OAuth is native (plugin), never a web popup |
| `cookies()` reads: `serverFetch`, `readThemePrefs` | Bearer from TokenProvider; theme prefs from `localStorage`/`@capacitor/preferences` applied client-side (§A.6) |
| `router.refresh()` after mutations | loader-hook `refetch()` (§A.3) |

### A.2 The seam (refactor on web first — zero behavior change)

```
web/src/lib/api/
  errors.ts            (unchanged — shared error classes)
  transport.ts         NEW: type ApiFetch = <T>(path, opts?: FetchOpts) => Promise<T>;
                       shared status→error mapping (401/402/403/404-null/204/!ok) lifted out of client.ts
  client.ts            BffApiClient: keeps `import "server-only"` + cookies(); becomes a thin
                       ApiFetch built on transport.ts (today's behavior, byte-for-byte semantics)
  direct.ts            NEW (client-safe): DirectApiClient — fetch(`${NEXT_PUBLIC_API_BASE}${path}`)
                       with `Authorization: Bearer ${await tokenProvider()}`; same allowlist
                       headers (Authorization, Accept, Content-Type, X-View-As); same mapping
  endpoints-core.ts    NEW: makeEndpoints(apiFetch: ApiFetch) — ALL loader bodies from today's
                       endpoints.ts (loadHomeVM, loadConditions, loadConditionDetail, loadNextSteps,
                       loadDocuments, loadShares, loadMessages, loadProfilePage, loadSubscription,
                       getSubscriptionResult, getUsage, calculateCombined, getMe, getProfile),
                       parameterized on the transport. NO `server-only`, NO React cache import.
  endpoints.ts         web entry: makeEndpoints(serverFetch) with React `cache()` re-applied to
                       getMe/getConditions/getGaps (server-only file, signatures unchanged —
                       existing RSC pages and api/ routes don't change imports)
  endpoints.native.ts  native entry: makeEndpoints(directFetch); plus `useLoader<T>(fn)` hook
                       (load on mount, expose { data, error, loading, refetch }) replacing
                       RSC await + router.refresh()
```

Critical detail: **`React.cache()` must not move into `endpoints-core.ts`** (server-only API). Per-render dedupe on native is handled by the fact that each screen mounts one loader; `loadHomeVM`'s internal `Promise.all` already parallelizes.

The **tri-state subscription semantics (Week-2 P0)** live in `getSubscriptionResult` and move verbatim into `endpoints-core.ts`: `pro`/`free`/`error` with 401 rethrow. `DirectApiClient` must preserve this — a network failure or 5xx maps to `UpstreamError` (→ `error` state), never to `free`. On native the paywall additionally consults RC `getCustomerInfo()` (§C.4), which is allowed to upgrade `error`→`pro` but never `error`→`free`.

### A.3 Page-by-page export audit (every route, what changes)

Pattern for each `(app)` page — one file, build-time branch, dead branch eliminated by the bundler:

```tsx
// (app)/page.tsx
import { NATIVE } from "@/lib/platform";          // = process.env.NEXT_PUBLIC_NATIVE === "1"
import { NativeHome } from "@/components/native/NativeHome";  // "use client"
export default async function HomePage() {
  if (NATIVE) return <NativeHome />;              // statically true in export build
  const vm = await loadHomeVM();                  // unchanged RSC path on web
  return <HomeView vm={vm} />;
}
```

`Native*` wrappers are ~10-line client components: `const { data, error, loading, refetch } = useLoader(loadHomeVM); …` rendering the **same** view component (`HomeView` etc.) plus the existing `loading.tsx` skeleton / `ErrorState` for the in-flight/error cases. They are the ONLY new UI files; no view component forks.

| Route | Web data path | Native change | Verdict |
|---|---|---|---|
| `(app)/page.tsx` Home | RSC `loadHomeVM()` | `<NativeHome>` + `useLoader(loadHomeVM)` | **clean** |
| `(app)/conditions` | RSC `loadConditions()` | hook; empty-state markup shared | **clean** |
| `(app)/conditions/[id]` | RSC `loadConditionDetail(id)` | **route-shape change** — see below | ⚠ needs `generateStaticParams` or query route |
| `(app)/steps` | RSC `loadNextSteps()` | hook | **clean** |
| `(app)/documents` | RSC `loadDocuments()`; `UploadCard` posts `/api/upload` | hook; UploadCard → direct multipart (§A.5) | **clean** |
| `(app)/ask` | RSC `loadMessages()` + `getSubscription()` + `searchParams.topic` | hook ×2; prefill via `useSearchParams()` (client) — works in export | **clean** |
| `(app)/share` | RSC `loadShares()`; `ShareManager` posts `/api/share` | hook; ShareManager → direct `/shares` | **clean** |
| `(app)/profile` | RSC `loadProfilePage()`; delete via `/api/account` | hook; delete → direct `DELETE /auth/account` + plugin signOut; billing row branches by `subscriptionSource` (§C.5) | **clean** |
| `(app)/upgrade` | RSC `loadSubscription()` → `PaywallView` (+ post-checkout banner P0) | hook; PaywallView platform branch (§C); `?checkout=` banner is web-only by construction (Stripe never runs natively) | **clean** |
| `(app)/layout.tsx` | RSC auth gate `getMe()` + `getSubscriptionResult()` | `<NativeAppLayout>` client gate (§A.4) | **clean** |
| `(app)/error.tsx`, `loading.tsx` | client already | unchanged | clean |
| `(auth)/login` | client already | all auth I/O through the AuthDriver seam (§B.2); `afterAuth` is the one platform branch (web: hard assign for the cookie; native: router.replace); legal links P0 unchanged | **clean** |
| `(auth)/finish-sign-in` | — | **RETIRED with the magic-link flow** (OTP codes are typed, not clicked); page deleted, deep-link mapping + AASA path removed (§B.5) | n/a |
| `accept-share/[token]` | RSC param→prop wrapper around client `AcceptShare` | **route-shape change** — see below | ⚠ same as conditions/[id] |
| `app/api/*` (8 handlers) | BFF | **do not exist natively** — every consumer flips (§A.5) | n/a |
| `/dev/*` fixtures | fixture-fed, no auth | included only in capture builds (§F.2); `dev/home/[state]` + `dev/conditions/[id]` get `generateStaticParams` over their **fixture keys** (statically known — this is the easy case) | clean (capture only) |

**The two dynamic routes are the only pages that cannot export as-is.** `output:"export"` requires `generateStaticParams`, and `(app)/conditions/[id]` / `accept-share/[token]` depend on per-user server data — impossible at build time. Resolution (pick 1; recommended **(a)**):

- **(a) Query-param twin routes (recommended).** Add `(app)/conditions/detail/page.tsx` and `accept-share/page.tsx` — thin client pages reading `useSearchParams().get("id"|"token")` and rendering the existing `ConditionDetail`-loader wrapper / `AcceptShare`. On native, all internal links (`ConditionsList`, `ConditionsPreview`, deep-link mapper) point at `/conditions/detail?id=N` and `/accept-share?token=T`; on web the `[id]`/`[token]` routes remain canonical and the twins simply also work. One small link helper `condHref(id)` in `lib/platform.ts` keeps it to a single decision point. No page forks; ~40 lines total.
- (b) Hash routing inside one exported page — rejected: fights `next/navigation`, breaks back-button semantics in the WebView.

**Verdict line for the summary: no page is blocked; only the two dynamic-param routes need the query-param twin, everything else is a mechanical RSC→hook flip.**

### A.4 Native auth gate (replaces proxy.ts + layout RSC gate)

`NativeAppLayout` (client): subscribes to the AuthDriver's auth state; while `undetermined`, keep the Capacitor splash up (`SplashScreen.hide()` only after resolution); if signed out → `router.replace("/login")`; if signed in → fetch `getMe()` + `getSubscriptionResult()` via `useLoader`, render `<ThemeProvider><AppShell …>` exactly as the server layout does today (`SessionWatcher` is web-only — its cookie-rotation job doesn't exist natively). NO auth-state mirror guard on `/login` (proxy.ts's second rule has no native equivalent): the OTP dual-verify makes one a footgun — a brand-new user becomes signed-in MID-FLOW (custom token / phone confirm) and must still attach the second channel on that same page; the page itself navigates on completion (`afterAuth`). A signed-in user has no nav path to `/login` anyway (deep-link mapper sends unknown paths home; cold start lands at `/`).

### A.5 Client components that call `/api/*` today — native equivalents

Verified by grep; this is the complete list:

| Caller (file:line at HEAD) | Web call | Native equivalent (DirectApiClient) | CORS notes |
|---|---|---|---|
| `chat/useChatStream.ts:273` | `POST /api/chat/stream` (SSE) | `POST {API}/claim/chat/stream` with `Authorization: Bearer`, `Accept: text/event-stream` | The consumer is **`fetch` + `res.body.getReader()` + `parseSseStream`** (not `EventSource`) — Bearer headers work as-is. Spring already returns the same raw statuses the hook branches on (402 gate, 409 streaming-disabled, 404 old backend), because the BFF's `mapUpstreamError` mirrors them 1:1. Preflighted (`POST` + custom headers) — wildcard CORS covers it today (§E). |
| `chat/useChatStream.ts:233` | `POST /api/chat` (fallback, returns refreshed thread) | **two calls**: `POST {API}/claim/chat {message}` then `GET {API}/claim/messages` → `toMessage` map — replicating the BFF's compose inside `sendViaPost` behind a `chatTransport` seam | |
| `documents/UploadCard.tsx:38` | `POST /api/upload` multipart | `POST {API}/claim/evidence`, `FormData` with `file`, Bearer header, **no manual Content-Type** (boundary set by fetch). 402/409/413 pass through unchanged. | multipart preflight fine under wildcard |
| `share/ShareManager.tsx:73,113` | `POST /api/share`, `DELETE /api/share?id=` | `POST {API}/shares` (same body), `DELETE {API}/shares/{id}` | |
| `share/AcceptShare.tsx:47,87` | `GET/POST /api/share/accept/{token}` | `GET/POST {API}/shares/accept/{token}` — GET is public on Spring (no Bearer needed pre-login), POST authed. The 401→`/login?next=`→return loop keeps working because the native guard honors `?next=` via `safeNext`. | |
| `profile/ProfileView.tsx:48` | `DELETE /api/account` | `DELETE {API}/auth/account`, then AuthDriver `signOut()` (replaces cookie delete) | |
| `paywall` via `lib/subscription-actions.ts:22` (Week-2) | `POST /api/subscription` (Stripe URLs) | **never called natively** — PaywallView branches to RC before this code path (§C) | |
| `firebase/session.ts:50,61` | `POST/DELETE /api/session` | **deleted from the native path** — no cookie exists; AuthDriver native is a no-op here | |

Implementation rule: these components get their transport from a tiny `lib/api/mutations.ts` facade (`uploadEvidence()`, `createShare()`, `revokeShare()`, `previewShare()`, `acceptShare()`, `deleteAccount()`, `sendChat()`, `streamChat()`) that internally branches Bff-route vs Direct on `NATIVE` — so the **components themselves don't change** beyond swapping inline `fetch("/api/…")` for the facade. This also de-duplicates the three copies of `mapError` currently living in the route handlers.

### A.6 Root layout + theme in export mode

`app/layout.tsx` branches: `NATIVE` → skip `readThemePrefs()` (a `cookies()` call would make the export build dynamic and fail) and render with `DEFAULT_PREFS`; a small client `ThemeBoot` (inside the existing `ThemeProvider`) reads persisted prefs from `localStorage` and applies `data-theme` / `data-scale` / `.cp-dark` on `document.documentElement` before first paint of content (the splash screen covers the swap; the web no-FOUC cookie trick stays web-only). `viewport` gains `viewportFit: "cover"` unconditionally (harmless on web, required for safe-area env() vars under the WKWebView).

`next/font/google` self-hosts at build time — works in export. No `next/image` usage exists in `src/` (verified) — `images.unoptimized` is belt-and-braces.

---

## B. Native auth

### B.1 Plugin + flow

> **Superseded 2026-07-01 (owner decision): pure passwordless OTP, no Apple/Google.** The
> original Apple-first plan below §B.2 is retired; kept history lives in git. With no
> third-party login offered, guideline 4.8 (Sign in with Apple) does not apply (§H.2).

`@capacitor-firebase/authentication` (Capawesome; pin the major matching Capacitor 7). Config: `skipNativeAuth: false` (REQUIRED — the plugin rejects custom-token sign-in when true), providers `["phone"]`. `GoogleService-Info.plist` goes in the App target — restored from the **same Xcode Cloud secret** (`GOOGLE_SERVICE_INFO_PLIST_BASE64`) the Flutter workflow already uses.

Sign-in (the shipped web OTP contract — passwordless-otp-auth-spec §9 — on native transports):

- **Email code:** `POST {API}/auth/email-code/request` → `POST …/verify` **directly against
  Spring** (the BFF proxy routes do not exist in the static export) → the returned custom
  token signs in via the plugin's **native** `signInWithCustomToken` so the keychain ID token
  (the DirectApiClient's Bearer source) is populated. `…/attach` (dual-verify) sends the
  keychain token as an explicit Bearer.
- **Phone code:** plugin `signInWithPhoneNumber` / `linkWithPhoneNumber` → `phoneCodeSent`
  event → `confirmVerificationCode` — Apple's **silent-APNs app verification** (§B.4), never
  the web reCAPTCHA path.
- **No JS-SDK mirroring.** An SMS code is single-use: a phone sign-in confirmed natively
  cannot also mint a JS-SDK credential, so a mirrored JS session can never be guaranteed —
  and a half-mirrored one is how "signed in but no API token" bugs happen. The native layer
  is the sole source of truth; `watchAccount`/`getUid` read a module-level cache fed by the
  plugin's `authStateChange`. No `postSession` (no cookie).

### B.2 AuthDriver seam (mirrors the ApiClient seam)

One OTP contract, two implementations selected by the build-time `NATIVE` constant (`lib/auth/index.ts`) so the unselected driver's imports are tree-shaken out of the other bundle:

```
lib/auth/driver.ts          AuthDriver = { requestEmailCode, verifyEmailCode, attachEmailCode,
                            startPhone, startLinkPhone, resetPhoneVerifier,
                            signOut, watchAccount, watchAuth, getToken, getUid }
lib/auth/driver.web.ts      session.ts behavior: BFF email-code proxy routes, invisible
                            reCAPTCHA phone verify, postSession/clearSession cookie sync
lib/auth/driver.native.ts   Spring-direct email codes + plugin-native custom token; silent-APNs
                            phone; keychain getToken; plugin-fed user cache; NO cookie calls
```

`verifyEmailCode` absorbs the custom-token exchange (web: sign-in + cookie establish; native: plugin keychain), so the login page never sees `custom_token` and stays byte-identical across targets. `startPhone`/`startLinkPhone` return a shared `PhoneConfirmation` whose `confirm(code)` reports `isNewUser` for the dual-verify branch. The shared `email-code-client.ts` owns the request/verify/attach body + `{detail}` error mapping for both transports. The login page's ONLY platform branch is post-auth navigation (web: hard `location.assign` so the fresh cookie rides a real navigation under Safari ITP; native: `router.replace` — no cookie, and a hard assign would re-boot the WKWebView).

### B.3 TokenProvider (the DirectApiClient's token source)

`tokenProvider = () => FirebaseAuthentication.getIdToken({ forceRefresh: false }).then(r => r.token)`. The **native** Firebase SDK is the source of truth: tokens persist in the iOS keychain (survives WKWebView website-data eviction, the known IndexedDB risk for JS-SDK-only persistence). On a 401 from Spring, retry once with `forceRefresh: true`; if still 401 → driver `signOut()` → login. This replaces the web's 55-min cookie + `watchIdToken` rotation entirely.

### B.4 Phone OTP + MFA posture

Plugin `verifyPhoneNumber` uses Apple's silent-APNs app verification — the app needs the **Push Notifications capability + `aps-environment` entitlement**, which is exactly why the Flutter Runner already carries `aps-environment: production` despite shipping no messaging. Keep it (entitlement parity; reCAPTCHA fallback opens Safari otherwise, matching Flutter's `LSApplicationQueriesSchemes https` hint). Firebase test phone numbers work in the simulator for dev. MFA: the project has SMS MFA enabled and the web ships **no resolver UI** — native keeps parity: surface the same honest `friendly-error` message on `multi-factor-auth-required`; do not build a resolver in this phase.

**HARD REQUIREMENT (crash lesson, 2026-07-02):** the reCAPTCHA fallback needs a registered
callback **custom URL scheme** — the Google reversed-client-id
(`com.googleusercontent.apps.1048958573080-…`) in `Info.plist` `CFBundleURLTypes`. FirebaseAuth
11.x `fatalError()`s AT PHONE-VERIFY TIME if it's missing, and the fallback fires on any fresh
install whose APNs token isn't ready within the SDK's ~5s window — i.e., the exact reviewer /
new-veteran path. This scheme is **required by phone auth itself**, NOT by offering Google
sign-in (it was briefly removed on that wrong assumption in the OTP port → instant crash at
"Send me a code" on device). If the Safari reCAPTCHA sheet appears often instead of silent
verification, check the APNs auth key in Firebase console → Cloud Messaging.

### B.5 Email magic link — RETIRED (no auth deep link)

The magic-link flow (and its `finish-sign-in` page) left with the OTP cutover: a 6-digit
code is **typed, not clicked**, so sign-in needs no universal link, no auth-handler redirect
caveat, and no "Open the app" bridge. The Safari-vs-app redirect problem this section used
to mitigate is gone by construction.

Universal links remain for **share-invite acceptance only**: Associated Domains
`applinks:app.vaclaimpath.com` + the AASA route (`web/src/app/.well-known/…`, paths
`["/accept-share/*"]`). Team-ID caveat RESOLVED 2026-07-01: the Xcode Cloud build-28
archive log shows `DEVELOPMENT_TEAM = 3ZKP4S469J`, so the AASA route's default appID
`3ZKP4S469J.com.vaclaimpath.app` matches the production signing team.

Build-numbering ground truth (learned from builds 26/28): Xcode Cloud REPLACES
CFBundleVersion with its own CI build number at TestFlight distribution, so the
`ci_pre_xcodebuild.sh` +100 stamp is cosmetic (local builds only). Safe anyway: the
workflow counter is shared with the old Flutter line (…23 → 24/25 Flutter → 26+ Capacitor),
so numbers stay strictly monotonic on the app record — TestFlight shows 2.0.0 (26), (28), …

### B.6 Session persistence summary

| Concern | Web | Native |
|---|---|---|
| Token storage | httpOnly `cp_session` (55 min, rotated by `watchIdToken`) | iOS keychain via native Firebase SDK (plugin) |
| Route guard | `proxy.ts` + RSC `getMe()` | `NativeAuthGate` (client) under splash |
| Refresh | POST `/api/session` on `onIdTokenChanged` | automatic in native SDK; per-request `getIdToken()` |
| Sign-out | JS signOut + DELETE `/api/session` | plugin signOut (sole session — no JS SDK, no cookie) |
| Identity display | JS SDK `onAuthStateChanged` | plugin `authStateChange`-fed cache (`watchAccount`/`getUid`) |

---

## C. Payments (App Store 3.1.1)

### C.1 Plugin + configuration

`@revenuecat/purchases-capacitor`. At app boot (native only, after Capacitor ready): `Purchases.configure({ apiKey: NEXT_PUBLIC_REVENUECAT_IOS_KEY })`; on auth resolution: `Purchases.logIn({ appUserID: firebaseUid })` (matches the backend's `subscriber.firebase_uid` lookup); on sign-out: `Purchases.logOut()`. Add the **In-App Purchase capability** to the App target.

### C.2 Backend contract (already deployed — no backend work)

- `POST /api/subscription/revenuecat/sync` (authed, Bearer): pulls the RC subscriber, copies entitlement **`VA Claim Path Pro`** (`REVENUECAT_ENTITLEMENT_ID` env on Cloud Run) expiry/store onto `User.subscriptionExpiresAt`/`subscriptionSource` (`apple`/`google`). Call it **immediately after a successful purchase or restore** so `/subscription/status` flips without waiting for the webhook.
- `POST /api/subscription/revenuecat/webhook` (public, bearer-token-gated via `REVENUECAT_WEBHOOK_AUTH`): handles INITIAL_PURCHASE/RENEWAL/PRODUCT_CHANGE/UNCANCELLATION. Note from memory: the RC dashboard webhook may not be configured yet — `/sync` covers the purchase path regardless; configuring the webhook is an ops task, not code.
- Both rails write the same `subscriptionExpiresAt`, so all 402 gating (`requireActiveSubscription`, usage cap) is platform-agnostic. ASC products exist and are approved-with-app: `pro_monthly` ($11.99, Apple ID 6774088295) / `pro_annual` ($119.99, Apple ID 6774085821) in group "VA Claim Path Pro" (22118719), wired into RC offering `default` (`$rc_monthly`/`$rc_annual`).

### C.3 PaywallView platform branch

`PaywallView` keeps its layout (hero, plan cards, features, ActiveCard) and branches the **commerce actions** only:

- **Web (unchanged):** `requestSubscriptionUrl("checkout"|"portal")` → Stripe redirect; `?checkout=` banners (Week-2 P0) as-is.
- **Native:** plans come from `Purchases.getOfferings()` → `current.availablePackages` — **display StoreKit-localized prices from the package, never the hardcoded/Stripe-fed `$11.99`** (price parity exists, but StoreKit is the truth Apple renders in the purchase sheet — and a metadata/charge mismatch is how we drew the v1.0 Guideline-3 price query, §H.1). Subscribe → `Purchases.purchasePackage({ aPackage })` → on success `POST /subscription/revenuecat/sync` → `refetch()` status → ActiveCard. User-cancelled purchase = silent reset (RC error `PURCHASE_CANCELLED`), other errors → existing error strip. Add a **"Restore Purchases"** button (MANDATORY for App Review — §H.4): `Purchases.restorePurchases()` → sync → refetch; rendered directly below the plan cards, plainly visible, not buried in Profile. "Manage subscription" (ActiveCard, `subscriptionSource === "apple"`) → the **native StoreKit management sheet** via RC `showManageSubscriptions()` (fallback if the plugin version lacks it: open `https://apps.apple.com/account/subscriptions` externally). Never the Stripe portal on iOS.
- **Native paywall furniture (Apple-required — §H.4, changed by the App-Review addendum):** below the plan cards the native branch renders:
  1. The **auto-renewal disclosure block**: "VA Claim Path Pro is an auto-renewing subscription: {monthly priceString}/month or {annual priceString}/year. Payment is charged to your Apple Account at confirmation of purchase. The subscription renews automatically unless cancelled at least 24 hours before the end of the current period. Manage or cancel anytime in your Apple Account settings." Prices interpolated from the StoreKit packages — never literals.
  2. **Functional Terms of Use (EULA) + Privacy Policy links** — the same canonical URLs the web login footer ships in `components/auth/LegalFooter.tsx` (Apple Standard EULA `https://www.apple.com/legal/internet-services/itunes/dev/stdeula/`; Privacy `https://app.vaclaimpath.com/privacy.html`). Extract both to a shared `lib/constants.ts` export so footer and paywall can never drift; open externally. This is the literal 3.1.2(c) fix from the v1.0 rejection, carried into the native paywall (§H.1). The Stripe (web) branch is unchanged — the Apple-Account language would be wrong there.
- Native build must contain **zero** Stripe UI strings/flows; `subscription-actions.ts` is only reachable from the web branch (tree-shaken by the `NATIVE` constant). No "subscribe on our website", no pricing-comparison copy, no link whose destination sells the subscription (§H.4 anti-steering).

### C.4 Tri-state on native

`sub.state === "error"` (backend unreachable) on web hides Subscribe buttons to avoid double-charge. Native has a better oracle: `Purchases.getCustomerInfo()` (local cache). Rule: if customerInfo shows the `VA Claim Path Pro` entitlement active → render ActiveCard even when the backend status read failed; if customerInfo says no entitlement → it is genuinely safe to show purchase buttons (StoreKit/RC dedupes purchases at the store level — Apple will surface "you're already subscribed"); keep the error strip informing that server sync is pending.

### C.5 Profile billing row (Week-2 P0) on native

Branch by `subscriptionSource`: `apple` → "Manage subscription" → native management sheet (same path as the paywall ActiveCard, §C.3); `stripe` → neutral static text **"Your subscription is managed where you purchased it."** with **no tappable link and no mention of "the web"** (changed by the App-Review addendum — even naming the web portal is steering-adjacent; zero-risk posture per the §H.4 matrix); `google` → n/a on iOS. Web behavior unchanged.

### C.6 Free tier

Unchanged everywhere: uploads free, 402 boundaries decided by Spring; native upgrade CTAs route to the native paywall.

---

## D. Native shell

### D.1 Identity & migration

- `appId: "com.vaclaimpath.app"`, `appName: "VA Claim Path"` — **replaces the Flutter binary on listing 6771148030 as v2.0** (D5). Marketing version `2.0.0`, build number continues the Xcode Cloud sequence.
- Migration implications to verify on a device with the Flutter build installed: (1) keychain-persisted Firebase auth should carry over (same bundle id + Firebase iOS SDK keychain service) — if it doesn't, users simply re-login; (2) RC entitlements carry over by construction (appUserID = Firebase UID; receipts re-validated on first `configure`); (3) Flutter's local prefs are abandoned (nothing critical stored).
- Android later: note the Play package is **`com.craftloop.vaclaimpath`** (differs from iOS — intentional, per-store products already exist in RC).

### D.2 capacitor.config.ts

```ts
{
  appId: "com.vaclaimpath.app",
  appName: "VA Claim Path",
  webDir: "out",
  server: { iosScheme: "https" },           // origin https://localhost
  plugins: {
    SplashScreen: { launchAutoHide: false },       // NativeAuthGate hides it
    Keyboard: { resize: "native" },
  },
}
```

Plugins: `@capacitor/app` (deep links, lifecycle), `@capacitor/splash-screen`, `@capacitor/status-bar`, `@capacitor/keyboard`, `@capacitor/haptics`, `@capacitor/preferences` (theme prefs), `@capacitor-firebase/authentication`, `@revenuecat/purchases-capacitor`. **No `@capacitor/push-notifications`** — Flutter shipped no push (`pubspec.yaml` has no `firebase_messaging`); the `aps-environment` entitlement stays solely for phone-auth silent verification (§B.4). Defer real push.

### D.3 Assets, safe areas, system chrome

- **Icons/splash:** regenerate from the masters in `flutter_frontend/ios/Runner/Assets.xcassets/AppIcon.appiconset` + `LaunchImage.imageset` (use `@capacitor/assets` with the same source art). Launch storyboard = brand navy `#13284f` to blend with the splash plugin.
- **Safe areas:** `viewportFit: "cover"` (§A.6). `MobileNav.module.css` already pads `env(safe-area-inset-bottom)`. Add `env(safe-area-inset-top)` padding to the TopBar/app container in `AppShell.module.css` under a `:root[data-native]` guard (the native layout sets `data-native` on `<html>`) so web is pixel-identical. Audit `Sheet`/`Modal` bottom padding the same way.
- **Status bar:** `StatusBar.setStyle` dark-content/light-content driven by `ThemeProvider` (`.cp-dark` ⇒ light icons). Token CSS layer needs no changes — it's all `var(--…)`.
- **Keyboard:** resize `native`; verify the chat composer and login inputs scroll into view (WKWebView default behavior plus `Keyboard.setScroll`).
- **Info.plist:** carry over from Flutter Runner: `NSPhotoLibraryUsageDescription` + `NSCameraUsageDescription` (file upload via `<input type=file>` triggers the photo picker — **missing strings = crash on tap = instant 2.1(a)**; exact reviewer-facing strings in §H.6.1), `ITSAppUsesNonExemptEncryption=false` (export-compliance answer baked in, §H.7.3), `LSApplicationQueriesSchemes: [https]`. Audit every Capacitor plugin's docs for additional purpose-string demands at integration time (§H.6.1 checklist). Add `CFBundleURLTypes` for `vaclaimpath://` (screenshot driving §F.3) AND the Google reversed-client-id scheme — the latter is REQUIRED by Firebase phone auth's reCAPTCHA fallback (§B.4 crash lesson), independent of Google sign-in being offered.
- **Entitlements:** Sign in with Apple (`Default`), `aps-environment`, Associated Domains (`applinks:app.vaclaimpath.com`), In-App Purchase.
- **Deep links:** `App.addListener("appUrlOpen", …)` → map `https://app.vaclaimpath.com/accept-share/{token}` → `router.push("/accept-share?token=…")`; unknown paths (including retired `/finish-sign-in` links from old emails) → home. Same handler serves `vaclaimpath://` scheme URLs (capture builds extend it to `/dev/*`, §F.3).
- **Offline shell (4.2 mitigation — REQUIRED, §H.5):** the bundle boots with zero network, and the app must **never white-screen or surface a raw error without connectivity in front of a reviewer**. Ship a dedicated friendly offline state — brand mark + "You're offline. VA Claim Path needs a connection to load your claim." + Retry — rendered whenever `DirectApiClient` fails at the connectivity level (`@capacitor/network` status + fetch-failure detection), distinct from the generic `ErrorState`. Acceptance (recorded evidence, §H.5): airplane-mode **cold launch** → branded offline state → network restored → Retry → Home loads. Optional polish on top: cache last-good `HomeVM` in `Preferences` and render it flagged as stale while refetching.
- **Haptics (REQUIRED, cheap — §H.5):** `@capacitor/haptics` light impact on primary CTAs (Subscribe, Upload, Delete-account confirm) and notification success/error on purchase outcome + account deletion. One `lib/native/haptics.ts` facade, no-op on web.

### D.4 CI (Xcode Cloud)

New workflow targeting `web/ios/App/App.xcworkspace`, modeled on `flutter_frontend/ios/ci_scripts/ci_post_clone.sh`: install Node 26 (Homebrew in the post-clone), `cd web && npm ci && NEXT_PUBLIC_NATIVE=1 NEXT_PUBLIC_API_BASE=… NEXT_PUBLIC_FIREBASE_*=… NEXT_PUBLIC_REVENUECAT_IOS_KEY=… npm run build:native && npx cap sync ios && pod install`. `GoogleService-Info.plist` restored from the existing workflow secret. The Flutter workflow is retired when v2.0 ships.

---

## E. Backend touches (exhaustive — it's small)

1. **CORS — no mandatory change today.** `SecurityConfig.corsFilter()` is `allowedOrigins: ["*"]`, `allowedHeaders: ["*"]`, no credentials. Bearer-token requests from the WKWebView origin (`https://localhost` with `iosScheme: https`, or `capacitor://localhost` otherwise) succeed under the wildcard, including the preflighted SSE/multipart POSTs, because nothing uses cookies. **Hard requirement on the security-remediation track:** if/when origins get pinned (the `docs/security/` work has this in scope), the allowlist MUST include `https://localhost` and `capacitor://localhost` or the native app bricks. Recommended landing shape: explicit origins `[https://app.vaclaimpath.com, https://va-claim-web-next-….run.app, https://localhost, capacitor://localhost, http://localhost:3100]`, headers `[Authorization, Content-Type, Accept, X-View-As]`. File a cross-track note in the security checklist now.
2. **Cookie coupling: none.** Spring is Bearer-only (`Authorization` header → `FirebaseAuthService.resolveUser`); `cp_session` never reaches it. `X-User-Email` is dev-mode-only and unused natively. OPTIONS is auth-exempt (preflights pass). `GET /api/shares/accept/{token}` is already public for the pre-login preview.
3. **SSE:** direct connection (no BFF hop) — verify Cloud Run/api LB doesn't buffer for the native origin (it doesn't for web today; same service).
4. **No new endpoints.** RC sync/webhook + Stripe + status all exist. (Ops, not code: set the RC dashboard webhook + `REVENUECAT_WEBHOOK_AUTH` when convenient.)
5. **Reviewer-demo allowlist (§H.7.2 — the one new backend behavior).** New env `SUBSCRIPTION_DEMO_EMAILS` (comma-separated, default empty), mirroring the existing `USAGE_UNLIMITED_EMAILS` operator-allowlist pattern (`config/UsageProperties` + the `UsageService` check): when the authenticated user's email matches, subscription checks report Pro — a **computed** entitlement at the `hasActiveSubscription`/`requireActiveSubscription`/`/subscription/status` evaluation point; no DB write, `subscription_source` untouched, removing the email instantly revokes. Pair any staffed demo email into `USAGE_UNLIMITED_EMAILS` too, so a reviewer demo never trips the $4 AI cap mid-review. ~30 lines + regression tests; no new endpoints.

---

## F. App Store screenshot pipeline

### F.1 Required sets (existing listing conventions)

| Slot | Device (available locally) | Pixel size | Count/naming |
|---|---|---|---|
| iPhone 6.9" | iPhone 17 Pro Max (iOS 26.5 sim) | **1320×2868** portrait | 5 PNGs, `ios-00-….png` … `ios-04-….png` (matches the prior `~/Downloads/vcp-screenshots/` upload set) |
| iPhone 6.5" | no 6.5" sim in iOS 26.5 | 1242×2688 | **ASC falls back to the 6.9" set automatically.** If an explicit set is ever demanded: `sips -z 2688 1242` downscale of the 6.9" PNGs into `iphone65/` |
| iPad 13" (listing previously satisfied the "12.9" requirement with 13") | iPad Pro 13-inch (M5) sim | **2064×2752** portrait | same 5 shots, `…-ipad13.png` in `ipad-13/` (the universal app makes the iPad set mandatory — learned on the Flutter submission) |

Output root: `web/screenshots/out/<set>/…` (gitignored), mirroring the manual-upload layout. **Upload to ASC stays manual** — automation cannot drag host files into ASC (hard-learned constraint in the submission memory).

### F.2 SCREENSHOT_BUILD — fixture routes in the capture bundle only

- Capture build: `npm run build:native:capture` (= `NEXT_PUBLIC_NATIVE=1 NEXT_PUBLIC_ENABLE_DEV_ROUTES=true next build`). The existing `app/dev/layout.tsx` gate (`NODE_ENV==="production" && NEXT_PUBLIC_ENABLE_DEV_ROUTES!=="true"` → `notFound()`) then admits the fixture pages; `dev/home/[state]` exports `generateStaticParams` over `Object.keys(HOME_FIXTURES)` (`populated|empty|analyzing|overflow`) and `dev/conditions/[id]` over its fixture ids — both statically known, so export is trivial.
- **Release build (`build:native`):** flag unset → the dev layout 404s at export time. Belt-and-braces (because `notFound()`-during-export behavior should be verified on Next 16, flagged in §G): the build script asserts `! -d web/out/dev` and `cap:sync` greps the synced `ios/App/App/public` for `dev/` — fail the build if present. The capture build is **never** archived/uploaded; it's a local `npx cap run ios --target <sim>` artifact.

### F.3 Driving the simulator

Deep-link driving via the `vaclaimpath://` scheme: the `appUrlOpen` handler (capture builds extend the path allowlist to `/dev/*`) maps `vaclaimpath:///dev/home/populated` → `router.replace("/dev/home/populated")`. Fixture pages need no auth and render the full `AppShell` (already proven by `/dev/home/[state]`).

`web/scripts/capture-screenshots.sh` (the deliverable):

```bash
DEVICES=("iPhone 17 Pro Max:iphone69" "iPad Pro 13-inch (M5):ipad-13")
SCREENS=(
  "00-home:/dev/home/populated"
  "01-conditions:/dev/conditions"
  "02-condition-detail:/dev/conditions/1"     # fixture id from dev fixtures
  "03-steps:/dev/steps"
  "04-documents:/dev/documents"
  "05-ask:/dev/ask"                            # cited-answer fixture
  "06-share:/dev/share"
)
# per device:
#   xcrun simctl boot "$UDID"   (skip if booted)
#   xcrun simctl status_bar "$UDID" override --time 9:41 --batteryLevel 100 \
#        --batteryState charged --cellularBars 4 --wifiBars 3 --dataNetwork wifi
#   xcrun simctl install "$UDID" "$APP_PATH"   # capture build .app
#   per screen:
#     xcrun simctl launch "$UDID" com.vaclaimpath.app   (first screen only)
#     xcrun simctl openurl "$UDID" "vaclaimpath://$PATH"
#     sleep 2.5                                          # render + skeleton settle
#     xcrun simctl io "$UDID" screenshot --type png "$OUT/$slot/ios-$name[$SUFFIX].png"
#   xcrun simctl status_bar "$UDID" clear
# post: verify dimensions with sips -g pixelWidth -g pixelHeight (1320×2868 / 2064×2752);
#       fail loudly on mismatch.
```

7 screens are captured; the 5 uploaded per slot are chosen at upload time (listing convention is 5). Marketing framing/captions, if wanted, happen outside this pipeline.

### F.4 Subscription review screenshot

The IAP products' Review Information screenshot (the prior submission blocker) = the **native paywall** (`/dev/upgrade` fixture, or live paywall on the sim) captured at any accepted size (1206×2622 worked previously). Add `07-upgrade:/dev/upgrade` to the capture list for this purpose; not part of the listing set.

---

## G. Sequencing, verification limits, test plan, risks

### G.1 Implementation order (each step lands green on web before the next)

1. **Seam refactor (web-only, zero behavior change).** `transport.ts`, `endpoints-core.ts` + `makeEndpoints`, `mutations.ts` facade, error-mapping dedupe. Existing Vitest + Playwright suites must pass untouched. *Coordinate: rebase over the Week-2 P0 commits; `subscription-actions.ts` joins the facade.*
2. **Native build target.** next.config branch, `lib/platform.ts`, page `NATIVE` branches + `Native*` wrappers + `useLoader`, query-param twin routes, `NativeAppLayout`/AuthGate skeleton (token provider stubbed), theme `ThemeBoot`, AASA static file. CI job: `npm run build:native` must succeed + `out/` smoke (serve statically, hit `/login`, `/dev/*` in capture mode).
3. **Capacitor shell.** `web/ios/` platform, config, plugins, icons/splash, Info.plist/entitlements, deep-link handler, safe-area CSS, status bar/keyboard/haptics. Runs in simulator against the prod API with web-style JS auth as a temporary bridge if needed.
4. **Native auth.** AuthDriver OTP contract (§B.2), plugin wiring (custom token + silent-APNs phone), sign-out, 401-retry-then-signout; magic-link page + deep link retired. ✅ DONE 2026-07-01 (drivers + login page + tests; device verification pending §G.2).
5. **Payments.** RC plugin, logIn/logOut on auth changes, PaywallView branch + Restore, sync-after-purchase, profile billing row branch.
6. **Screenshot pipeline.** Capture build flag plumbing, scheme allowlist, `capture-screenshots.sh`, dimension assertions.
7. **CI + submission — gated by §H.** Xcode Cloud workflow, TestFlight, the on-device evidence pass (§H.1–H.7 checklists, recordings), v2.0 metadata with the §H.7.1 review-notes draft verbatim, then the §H.8 submission-day sequence. (Do not ad-lib review notes; the old "also sold on the web" framing is retired — §H.4.)

### G.2 Cannot be verified without a Mac GUI / Apple account / device (hand off to user)

- Signing/provisioning + Associated Domains validation (Xcode Cloud/ASC).
- OTP login end-to-end on device against PRODUCTION (email code → custom token, phone code,
  dual-verify both directions — the §H.1 smokes; sim covers phone only via Firebase test numbers).
- Phone-auth silent APNs verification (needs real device + production entitlement; sim works only with Firebase test numbers).
- **RevenueCat sandbox purchase/restore** (real device + sandbox Apple ID; StoreKit-config-file testing in sim is a partial preflight).
- ASC screenshot/IAP-screenshot uploads (manual drag — automation rejected by ASC, per submission memory).
- Keychain auth carry-over from the installed Flutter app (device with v1.0 installed → update to v2.0 build).

### G.3 Test plan

- **Unit (Vitest):** transport mapping (Direct vs Bff parity on 401/402/403/404/204), `useLoader` states, chat fallback compose (POST + history merge), tri-state preservation in `DirectApiClient`, paywall branch (RC mock vs Stripe mock), deep-link path mapper.
- **Static-bundle e2e:** Playwright against `npx serve web/out` (capture build): `/dev/*` fixtures render, login screen renders, no `/api/*` requests issued in native mode (route interception asserts zero).
- **Web regression:** full existing suites after every step (the seam must be invisible).
- **Device checklist (manual, one page in `docs/qa/`):** OTP sign-in (email-first + phone-first, incl. dual-verify); upload via photo picker + Files; chat stream + mid-stream kill (fallback + retry paths); share create/revoke/accept via universal link; account deletion; sandbox purchase, restore, manage; offline launch; dark mode + status bar; safe areas on notch + home-indicator devices; keyboard over chat composer.

### G.4 Risks & mitigations

| Risk | Severity | Mitigation |
|---|---|---|
| **Apple 4.2 minimum functionality** ("web wrapper") | High (rejection) | Native silent-APNs phone verify + keychain auth, native IAP, splash/status-bar/keyboard/haptics polish, REQUIRED offline shell, photo-picker upload — all itemized with evidence in §H.5. Precedent helps: the listing already passed review as a native app and this replaces it with equal-or-better UX. Review notes emphasize native capabilities. |
| `notFound()`-in-layout behavior during `output:"export"` differs on Next 16 (dev-route exclusion) | Medium | Verified by the build assert + grep in §F.2; worst case the release script `rm -rf web/out/dev` before sync. |
| Security remediation pins CORS origins without the Capacitor origins | High (field breakage) | §E.1 cross-track note **now**, in `docs/security/` checklist; CI smoke from a `https://localhost`-origin fetch if feasible. |
| WKWebView website-data eviction killing web-storage auth | Low | The native session lives ENTIRELY in the keychain (plugin); no JS-SDK session exists to evict (§B.1). |
| OTP backend path (createCustomToken / SendGrid) fails for the reviewer's fresh account | High (2.1(a) rerun) | This is the exact shape of the v1.0 rejection. §H.1: prod smoke on a physical device with a NEVER-seen phone + email before every submission; reviewer uses a Firebase test phone number on a pre-provisioned account (§H.7.1/§H.7.2) so review never depends on SMS/SendGrid delivery. |
| React `cache()` leaking into shared `endpoints-core.ts` | Medium (native build break) | Lint rule: `endpoints-core.ts`/`direct.ts` may not import `react`'s `cache`, `server-only`, or `next/headers`; CI `build:native` catches regressions structurally. |
| Stripe-sourced subscriber confusion on native | Low | §C.5: entitlement honored everywhere (backend merges rails); manage routed by `subscriptionSource`; no external purchase links. |
| Two live workflows editing `web/`/`spring-backend` while this lands | Medium (merge churn) | Step 1 is pure refactor — land it immediately after Week-2 P0s merge; everything later is additive (new files + small branches). |
| RC entitlement-id mismatch (`pro` default vs `VA Claim Path Pro`) | Low (already handled in env) | Keep `REVENUECAT_ENTITLEMENT_ID` env authoritative; native code reads entitlement key from a constant matching it. |
| `useSearchParams`/`router.refresh` web-isms in export mode | Low | `useSearchParams` works client-side in export; all `router.refresh()` calls go through `refetch()` on native (audited: PaywallView, ProfileView, ErrorState retries). |

---

## H. App Review Proofing — the publication gate

> The owner has been rejected before and the instruction is explicit: **"I don't want to get
> denied again."** This section is the gate (D8): the v2.0 build is not submitted until every
> checklist below is green, with evidence (test run, screenshot, or screen recording) attached
> to the release PR. If Apple rejects anyway, the protocol is §H.8 step 6 — and the new
> rejection gets appended to the §H.1 ledger before any resubmission.

### H.1 Past rejection ledger — what Apple already caught us on (this listing, v1.0)

| Date | Guideline | What happened | Standing fix / v2.0 carry-over |
|---|---|---|---|
| 2026-05-27 | **2.1(a)** App Completeness | Reviewer tapped **"Continue with Apple" → error dialog**. Root cause: backend `verifyIdToken` missing the Firebase project id. | Backend fixed (Cloud Run rev 00108-s7j, made durable by binding the project id to `GCP_PROJECT`). Lesson institutionalized below. |
| 2026-06-03 | **Guideline 3** | Apple asked to confirm $119.99/yr was the intended price. | Confirmed by reply. v2.0: paywall prices render from StoreKit packages (§C.3), so UI and charge cannot diverge. |
| 2026-06-03 | **5.1.1(v)** | Account creation without in-app account **deletion**. | `DELETE /api/auth/account` + `UserDeletionService` + Profile flow shipped (backend rev 00112-z9m, verified live). Must work through the native seam — §H.3. |
| 2026-06-03 | **3.1.2(c)** | Purchase flow lacked functional **Terms (EULA) + Privacy** links; EULA also required in the App Store **Description**. | In-app links shipped; carried into the native paywall (§C.3). The Description half lives on the listing — **verify it survived any metadata edits** before resubmitting (checklist below). |

**The 2.1(a) lesson, made structural.** The v1.0 rejection's shape was "login flow succeeds
client-side, backend token verification fails — visible only against production with a fresh
account." v2.0's login is pure passwordless OTP (§B.1), so the equivalent risk now lives in
the OTP backend path: `createCustomToken` (email codes), SendGrid delivery, silent-APNs
phone verification, and the dual-verify convergence for brand-new users. Therefore in v2.0:

- The OTP flow is **fully native-transport** — Spring-direct email codes + plugin custom-token
  sign-in + silent-APNs phone verify (§B.1); zero web-popup/reCAPTCHA auth code reachable on
  native (the reCAPTCHA shape of flow is what broke in WKWebView in v1.0).
- The path is **tested against production on a physical device before every submission**,
  with a NEVER-before-seen phone number AND email — exercising both first-channel sign-in
  and the dual-verify second channel, exactly what the reviewer's fresh account would hit.
- The reviewer **never depends on SMS or email delivery**: a Firebase test phone number
  (fixed code, works in production builds) on a **pre-provisioned** account (dual-verify
  already satisfied) is in the review notes (§H.7.1/§H.7.2).

**Checklist H.1**
- [ ] Native OTP sign-in implemented via the plugin (custom token + silent-APNs phone); zero web-popup/reCAPTCHA auth code reachable on native.
- [ ] **Prod smoke on a physical device:** fresh install → never-before-seen EMAIL → code → dual-verify phone → lands on Home. Recorded.
- [ ] **Prod smoke, phone-first:** never-before-seen PHONE → SMS code → dual-verify email → lands on Home. Recorded.
- [x] Firebase **test phone number** configured — DONE 2026-07-01: `<test-phone>` / code `<test-otp>` (Identity Platform admin API, `signIn.phoneNumber.testPhoneNumbers`). Reviewer account PRE-CREATED (uid `gFk7MjnkCAao0Uw8u9ik4IU7zZt1`, email `stobryan+applereview@gmail.com` verified, phone attached) so sign-in is a RETURNING user — no dual-verify. Pair pasted into §H.7.1.
- [x] Returning-user smoke with the test phone number — VERIFIED 2026-07-08 by replaying the flow against prod (Identity Toolkit REST): sendVerificationCode(<test-phone>) → signInWithPhoneNumber(<test-otp>) signs in `localId gFk7MjnkCAao0Uw8u9ik4IU7zZt1` (the reviewer account), `isNewUser:false` (NO dual-verify), and the idToken is accepted by the backend `/auth/me` → 200 (`stobryan+applereview@gmail.com`, `activeClaim:true`). This is the exact 2.1(a) path (backend verifyIdToken) — now clean.
- [x] App Store **Description still contains the EULA + Privacy links** (the 3.1.2 metadata half) — VERIFIED 2026-07-07 via ASC API (en-US description carries both the Apple stdeula URL and app.vaclaimpath.com/privacy.html).

### H.2 Guideline 4.8 — does not apply (no third-party login)

4.8 is TRIGGERED by offering a third-party/social login (Google, Facebook, …): only then
must an equivalent privacy-preserving option (i.e., Sign in with Apple) also be offered.
v2.0 ships **first-party passwordless OTP only** (§B.1) — no Google, no Apple, no social
provider — so 4.8 imposes nothing. Guard the premise, not the checkbox:

- If ANY third-party login is ever reintroduced on iOS, 4.8 re-arms and Sign in with Apple
  becomes mandatory again — that is a §H-gate change, not a quiet feature add.
- The adjacent privacy facts still stand on their own: no advertising/tracking SDKs in the
  binary — for the App Privacy "no tracking" labels (§H.6.2) and the brand promise to a
  scam-targeted veteran audience. OTP identifiers (phone/email) are collected for sign-in
  only, matching the login screen's stated promise.

**Checklist H.2**
- [ ] Login screen offers NO third-party provider (4.8 stays untriggered).
- [ ] Dependency audit (Podfile.lock + JS bundle): no ads/tracking SDKs.

### H.3 Guideline 5.1.1(v) — account deletion, native

The deletion flow exists because Apple rejected v1.0 without it. It must **work and be
discoverable in the native build**:

- **Flow:** Profile → "Delete account" → destructive confirm modal ("Delete everything") →
  on web `DELETE /api/account` (BFF route); **on native the seam bypasses the BFF**:
  `DirectApiClient` sends `DELETE {API}/auth/account` straight to Spring with Bearer
  (§A.5 row `profile/ProfileView.tsx:48`), then AuthDriver `signOut()` (plugin + JS SDK) +
  `Purchases.logOut()` + local-state purge → `/login`. This is the one destructive mutation
  where a missed seam = a reviewer-visible dead button = rejection; it gets its own test.
- **Server behavior (already shipped, rev 00112-z9m):** FK-safe, idempotent full wipe — all
  user data, cancel active **Stripe** sub, delete GCS documents, delete the Firebase user.
- **Apple-purchased subs:** cannot be cancelled server-side (Apple owns the billing
  relationship). Deletion proceeds; the store subscription lapses or the user cancels via
  the management sheet — this is Apple's own expected pattern and is fine for 5.1.1(v).
- **SIWA token revocation: MOOT.** Apple's revoke-on-deletion guidance applies to apps
  offering Sign in with Apple; v2.0 offers no Apple provider (§B.1), so there are no Apple
  tokens to revoke. LEGACY caveat: v1.0 accounts created via Apple may still carry an
  `apple.com` Firebase provider — deleting the Firebase user severs the app link, and with
  no SIWA button in v2.0 the obligation doesn't attach to this build. Re-arms only if Apple
  sign-in is ever reintroduced (§H.2 note).
- **Discoverability:** reachable in ≤3 taps from Home (Profile → Delete account), in the
  same session the reviewer creates the account. No support-email gatekeeping, no "visit our
  website" — both are named 5.1.1(v) failure modes.

**Checklist H.3**
- [ ] `mutations.ts` `deleteAccount()` native path unit-tested (Bearer allowlist, 204, sign-out sequence).
- [ ] Device E2E: create account via OTP sign-in → delete → signed out → re-sign-in yields a fresh empty account. Recorded.
- [ ] Post-delete spot-check: Firebase user gone, DB rows gone, GCS objects gone.
- [ ] Delete reachable in ≤3 taps; confirm copy unambiguous ("permanently deletes… conditions, evidence…").

### H.4 Guideline 3.1 — IAP compliance and anti-steering

§C.3/§C.5 are the implementation; this is the contract and the verification.

**Hard rules on iOS:**
1. Purchases happen **only** via IAP (RevenueCat → StoreKit). No Stripe checkout, portal,
   pricing page, or any link whose destination sells the subscription — anywhere in the
   reachable native UI.
2. **"Restore Purchases" is mandatory** and prominent (§C.3) — required for any
   auto-renewable subscription app; its absence alone is a rejection.
3. The paywall carries the **subscription disclosure block** (price, period, auto-renewal
   language) and **functional Terms/EULA + Privacy links** (§C.3) — the 3.1.2 furniture.
4. **No purchase steering.** Even where US court rulings have opened cracks in 3.1.1's
   link-out prohibition, we take the zero-risk posture: nothing on iOS points off-platform
   for purchasing, and even "manage on the web" copy is retired (§C.5). Decision (addendum):
   **existing web subscribers do NOT get a portal link on iOS** — the safest reading wins.
5. Apple-purchased subs manage via the **native management sheet** only (§C.3).

**Cross-platform subscriber matrix (the canonical statement):**

| Subscriber state | iOS app (native) shows | Web shows |
|---|---|---|
| Free / expired (any rail) | RC paywall: StoreKit-priced plans + Restore + disclosure + legal links | Stripe paywall (unchanged) |
| Bought on iOS (`subscription_source=apple`) | ActiveCard; "Manage subscription" → native management sheet | ActiveCard; informational "manage in your Apple Account settings" hint, no Stripe portal |
| **Bought on web** (`subscription_source=stripe`) | **Entitlement honored** (all 402 gating reads the rail-agnostic `subscriptionExpiresAt`). ActiveCard with **no purchase UI and no manage link**; neutral copy "Your subscription is managed where you purchased it." | ActiveCard + Stripe portal (unchanged) |
| `google` | n/a on iOS (Android later) | informational hint |

The Stripe-on-iOS row is the subtle one: a portal link is steering; purchase buttons risk a
double subscription; the honored entitlement with neutral copy is both compliant and correct.
Honoring multiplatform entitlements without IAP is explicitly permitted (3.1.3(b)).

**ASC product state (no new product work):** `pro_monthly` (Apple ID 6774088295, $11.99) and
`pro_annual` (Apple ID 6774085821, $119.99) in group "VA Claim Path Pro" (22118719) were
**approved with v1.0** — a v2.0 binary does not re-review them unless their metadata is
touched. If any localization IS touched, remember the v1.0 gotcha: the subscription **group**
needs a display-name localization or every sub flips back to "Missing Metadata".

**Checklist H.4**
- [ ] Unit test: native paywall renders Restore + disclosure block + both legal links, and renders **zero** Stripe-rail actions (extends the §G.3 paywall-branch test).
- [ ] Bundle audit: `subscription-actions.ts` / Stripe URLs unreachable in `web/out/` for the native build (grep the export output).
- [ ] Device: sandbox purchase (monthly) → `/revenuecat/sync` → entitlement live in-app. Recorded.
- [ ] Device: delete app + reinstall → **Restore Purchases** recovers the entitlement. Recorded.
- [ ] Device: Stripe-sourced test account → ActiveCard, no purchase UI, no manage link, neutral copy.
- [ ] Device: both legal links open and resolve (tap each).
- [ ] Localization sanity: non-USD storefront region shows StoreKit-localized prices on the paywall.

### H.5 Guideline 4.2 — minimum functionality (native-feel items, all REQUIRED)

A Capacitor app draws extra "web wrapper" scrutiny; this is the evidence pack. Every item
below is REQUIRED scope (D8) — none are deferrable polish:

- [ ] **Native auth sheets** (Apple/Google/phone) — not browser redirects (evidence shared with H.1/H.2).
- [ ] **Native IAP** purchase sheet + Restore (evidence shared with H.4).
- [ ] **Splash screen**: brand navy + shield mark, `launchAutoHide:false`, hidden only after the auth gate resolves — no white flash, no content pop (§D.2/§A.4).
- [ ] **Status bar** style correct in light AND dark themes (§D.3).
- [ ] **Safe areas** verified on a notched + home-indicator device: TopBar, bottom nav, Sheet/Modal (§D.3).
- [ ] **Keyboard** behavior over the chat composer and login inputs (§D.3).
- [ ] **Haptics** on key actions: Subscribe, Upload, Delete-confirm, purchase success/error (§D.3).
- [ ] **Offline launch shell**: airplane-mode cold launch → branded friendly offline state → Retry recovers (§D.3). **Recorded — this recording is mandatory evidence**; a white screen here in front of a reviewer is a guaranteed 4.2/2.1 rejection.
- [ ] **Free tier shows real utility before any purchase**: conditions, document upload (explicitly free-tier), profile — the reviewer sees an app, not a subscription shell.

### H.6 Privacy — purpose strings, nutrition labels, policy URLs

#### H.6.1 `Info.plist` purpose strings (reviewer-facing text)

Required because the upload flow presents the photo/camera sheet (§D.3) — and Apple reads
these strings for specificity (generic boilerplate draws metadata rejections):

```xml
<key>NSPhotoLibraryUsageDescription</key>
<string>VA Claim Path lets you choose medical records and supporting documents from your photo library to attach as evidence to your claim.</string>
<key>NSCameraUsageDescription</key>
<string>VA Claim Path lets you photograph paper medical records and letters to attach as evidence to your claim.</string>
```

- [ ] Both strings present; on-device tap-test of "Take Photo" and "Photo Library" from the upload sheet (no crash, correct prompt copy).
- [ ] Plugin audit complete: each installed Capacitor plugin's docs checked for additional required keys (e.g. `@capacitor/camera`, if ever adopted, additionally demands `NSPhotoLibraryAddUsageDescription`). Current plugin set (§D.2) requires none beyond the two above.

#### H.6.2 App Privacy nutrition labels (ASC — update the published section for v2.0)

The app handles **user-provided medical documents** — health-adjacent, sensitive, and linked
to an account. Label truthfully and conservatively:

| Data type | Collected | Linked to identity | Used for tracking |
|---|---|---|---|
| **Health & Fitness → Health** (user-uploaded medical records; condition data) | Yes | **Yes** | **No** |
| Contact Info → Email Address, Phone Number (auth) | Yes | Yes | No |
| Identifiers → User ID (Firebase UID) | Yes | Yes | No |
| Purchases → Purchase History (subscription state via RC) | Yes | Yes | No |
| User Content → Other User Content (documents, chat messages) | Yes | Yes | No |
| Diagnostics | No (no crash/analytics SDK shipped) | — | — |

"Used for tracking" = **No** across the board, and must remain true (no ad SDKs, no
cross-app identifiers — same assertion as §H.2).

- [ ] ASC App Privacy section updated per the table (Claude drives the browser; owner present for the session).

#### H.6.3 Policy URLs (single source of truth)

- Privacy Policy: `https://app.vaclaimpath.com/privacy.html` — re-verify HTTP 200 pre-submission.
- Terms / EULA: Apple Standard EULA (`https://www.apple.com/legal/internet-services/itunes/dev/stdeula/`).
- These are the **same URLs** in: the web login footer (`LegalFooter.tsx`), the native
  paywall (§C.3 — via the shared `lib/constants.ts` export), the ASC Privacy Policy URL
  field, the App Store Description (3.1.2 metadata half), and the review notes.

- [x] Privacy URL 200; all five locations point at the identical URLs — FIXED 2026-07-07: the Flutter→Next cutover left `app.vaclaimpath.com/privacy.html` a 404 (the exact 3.1.2(c) dead-link shape). Ported the policy to `web/public/privacy.html` (auth line corrected to passwordless OTP); deployed `va-claim-web-next-00047-9zg`; verified **200**. `PRIVACY_URL`/`EULA_URL` in `lib/constants.ts` unchanged. Owner: review policy for currency.

### H.7 Review submission package

#### H.7.1 Review notes (draft — paste into App Review Information for v2.0)

> VA Claim Path helps U.S. military veterans organize medical evidence and understand their
> VA disability claim. Veterans upload their own medical documents; the app organizes them
> into conditions, identifies evidence gaps, and — with a Pro subscription — provides
> AI-assisted analysis and chat. Version 2.0 is a full redesign of the app.
>
> **Sign in (passwordless):** The app uses one-time codes sent to a phone or email — there
> are no passwords. For review, please use the test phone number below; it accepts a fixed
> verification code and does not send a real SMS:
>
> &nbsp;&nbsp;Phone: **<test phone — see Identity Platform>** &nbsp;·&nbsp; Verification code: **<test-otp>**
>
> Enter the number on the login screen, tap "Send me a code," and enter the code. This
> account is already set up, so you will land directly on the app's Home screen. (Any real
> phone number or email also works and creates an account automatically.)
>
> **Subscription (VA Claim Path Pro):** AI features (analysis, chat) require the Pro
> auto-renewing subscription ($11.99/month or $119.99/year), purchased in-app. The purchase
> completes in the sandbox without charge: Profile → "Upgrade to Pro" → choose a plan
> (about 60 seconds). A **"Restore Purchases"** button is on the same screen.
>
> **Account deletion (5.1.1(v)):** Profile → "Delete account". (Please use a fresh
> throwaway account for the deletion test, not the test-phone account above.)
>
> Free features (document upload, condition organization, profile) work without any
> subscription. The app is an educational tool, not legal or medical advice.

The test-phone pair above is LIVE (configured 2026-07-01 via the Identity Platform admin
API; fictional 555-01XX number, no real SMS ever sent; the fixed code only unlocks the demo
account). ASC's demo-account fields get the same pair (user = the phone number, password =
"one-time code — see notes"), since ASC requires the fields to be populated when a login
exists.

#### H.7.2 Reviewer access — the test-phone account (auth) + PAID features

**Auth access (the OTP prerequisite).** A pure-OTP login means the reviewer must receive a
code — but App Review devices can't be assumed to receive SMS, and email delivery mid-review
is a dependency we refuse (§H.1). The fix is a **Firebase test phone number**: Console →
Authentication → Sign-in method → Phone → "Phone numbers for testing" (max 10). A test
number works in production builds, accepts its fixed code, and sends NO real SMS. Setup, in
order, BEFORE submission:

1. Add the test number + fixed code in the Firebase console.
2. Sign in with it ONCE ourselves and complete dual-verify by attaching a demo email we
   control — the account now EXISTS, so the reviewer signs in as a returning user and never
   hits the dual-verify second-channel step (which would demand a live inbox).
3. Put that demo email on `SUBSCRIPTION_DEMO_EMAILS` **and** `USAGE_UNLIMITED_EMAILS`
   (below) so the same account can also demo Pro without stalls.
4. Paste the number + code into the review notes (§H.7.1) and ASC's demo-account fields.

**Paid-feature access.** Two paths, in order:

1. **Sandbox IAP purchase (primary).** App Review tests IAP against the StoreKit sandbox:
   the purchase sheet completes without charge, RevenueCat validates sandbox receipts, and
   `POST /subscription/revenuecat/sync` flips the entitlement — the reviewer sees Pro
   features live in ~60 seconds. This simultaneously demonstrates the purchase flow Apple
   wants to verify anyway. The review notes give the exact path.
2. **Server-side demo allowlist (safety net — build now, staff at submission time).**
   `SUBSCRIPTION_DEMO_EMAILS` per §E.5 (the `USAGE_UNLIMITED_EMAILS` pattern): matching
   accounts compute as Pro, no DB writes, instantly revocable. With OTP the staffing is
   CLEANER than the old Google-demo-account plan: the test-phone account's attached demo
   email (step 2–3 above) is a stable, non-relay address we fully control — no third-party
   sign-in challenges from review-farm IPs. Live remediation lever if Apple replies
   mid-review: the existing admin-gated `POST /api/subscription/admin/grant/{userId}`.

#### H.7.3 Export compliance

HTTPS/ATS only — standard-encryption **exempt**. `ITSAppUsesNonExemptEncryption=false` is
already in `Info.plist` (§D.3), which pre-answers the question for every build; v1.0
answered the questionnaire identically. No French declaration, no annual self-classification
report needed at this usage class.

#### H.7.4 Age rating

The listing shipped at **4+** with the existing questionnaire. Keep it for v2.0 unless ASC
forces a re-answer; if it does, answer truthfully — the "Medical/Treatment Information"
question may move the rating to 12+, which is acceptable. Do not contort answers to hold 4+.

#### H.7.5 Screenshots (all-new set — the UI changed wholesale)

Pipeline and exact dimensions are §F; the submission requirements:

- **iPhone 6.9"** (1320×2868): 5 shots — Home (populated), Conditions/detail, Documents/upload,
  **Paywall** (StoreKit prices + disclosure block visible — this same capture serves as the
  IAP Review Information screenshot if product metadata is ever touched, §F.4), Ask AI.
- **iPad 13"** (2064×2752): required — the app stays universal (D5/§F.1; learned on the
  Flutter submission). Real simulator captures preferred (the ≥760px sidebar layout is a
  genuine iPad UI); the v1.0 sips letterbox recipe is the fallback.
- **Upload is manual:** browser automation cannot drag host files into ASC (proven twice on
  v1.0) — the owner uploads; Claude verifies slot/spec compliance beforehand (§F's dimension
  asserts).

**Checklist H.7**
- [x] Review notes pasted per H.7.1 — DONE 2026-07-07 via ASC API onto the existing version's review detail (pure-OTP flow + test phone `<test phone — see Identity Platform>` / code `<test-otp>`); demo-account fields populated (`demoAccountRequired=true`, name = the test phone). (Could not create a *separate* v2.0 while v1.0 is REJECTED — Apple 409 "cannot create a new version in the current state"; edited the existing version instead, which moved REJECTED → PREPARE_FOR_SUBMISSION.)
- [x] `SUBSCRIPTION_DEMO_EMAILS` implemented + tested (§E.5), even if left unstaffed — DONE 2026-07-07 (commit 49088ab, deployed `va-claim-api-00153-zch`). `SubscriptionProperties.demo-emails` + central `SubscriptionAccess.isPro()` routed through every Pro gate; empty default = inert. Tests: `SubscriptionAccessTest` (6) + `SubscriptionDemoEmailTest` (2, end-to-end status flip). **Staff at submission time:** `SUBSCRIPTION_DEMO_EMAILS=stobryan+applereview@gmail.com` (+ the same into `USAGE_UNLIMITED_EMAILS`) on the api service; sandbox IAP purchase remains the primary reviewer path, this is the safety net.
- [ ] Sandbox purchase verified on device (shared with H.4). — OWNER (device)
- [x] Export compliance plist key confirmed — `ITSAppUsesNonExemptEncryption=false` present in `web/ios/App/App/Info.plist`.
- [ ] Age rating reviewed against the questionnaire. — OWNER (ASC)
- [ ] Both screenshot sets captured, dimension-verified, in the owner's hands for upload. — OWNER (0 uploaded as of 2026-07-07; iPhone 6.7" + iPad 12.9" sets exist but empty).

### H.8 Submission mechanics — v2.0 on the existing listing

**Versioning:** same bundle id `com.vaclaimpath.app` ⇒ **create version 2.0 on the existing
ASC app record (6771148030)** — never a new listing (D5: preserves ratings, approved IAPs,
the RC app, Paid Apps agreement, and subscriber entitlements). Build numbers continue the
Xcode Cloud sequence above the Flutter line (set a floor ≥100 in the new workflow to be
collision-proof). Listing updates: Description ("What's New": redesigned app — keep the EULA
+ Privacy links intact), screenshots, review notes, App Privacy.

**What runs where:**

| Step | Mechanism |
|---|---|
| Build + sign + upload to ASC | **Xcode Cloud** (new workflow on `web/ios/App/App.xcworkspace`, §D.4; cloud signing — no local certs). Carry the two v1.0 gotchas: `GOOGLE_SERVICE_INFO_PLIST_BASE64` secret set, Archive's Distribution Preparation = "App Store Connect" |
| Local archive | Fallback only (`xcodebuild … archive` + Organizer); needs local signing — avoid |
| TestFlight install + the §H device-evidence pass | **Owner's physical iPhone** (Claude directs, owner drives/records) |
| ASC metadata: create v2.0, attach build, Description/What's New, review notes, App Privacy, age rating | **Claude drives the owner's signed-in ASC browser session** |
| File uploads into ASC (screenshots, any recordings Apple requests) | **Owner, by hand** — hard constraint, automation cannot upload host files to ASC |
| Apple ID 2FA / ASC session renewal | Owner |
| Signing certs / agreements | None expected (cloud signing; Paid Apps Agreement Active since 2026-05-29) |
| Final **"Submit for Review"** click | **Owner**, only against green §H checklists |

**Submission-day sequence:**
1. §H.1–H.7 all green; evidence linked on the release PR.
2. Xcode Cloud builds from `main`; build lands in TestFlight.
3. Owner runs the device-evidence pass **on that exact build** (not an earlier one).
4. ASC: create v2.0 → attach build → metadata + screenshots + review notes → verify
   EULA/Privacy in Description → Add for Review.
5. Owner clicks Submit for Review.
6. **If rejected anyway:** reply in Resolution Center *with the §H evidence* (a recording
   exists for every flow a reviewer can break), fix forward, append the rejection to the
   §H.1 ledger, re-run the affected checklist, resubmit. Never resubmit on a hunch.

---

## Appendix: Spring endpoints the DirectApiClient must cover (the BFF's full upstream surface today)

`/auth/me`, `/auth/profile`, `DELETE /auth/account`, `/usage`, `/claim/conditions`, `/claim/gaps`, `/claim/evidence` (GET list, POST multipart), `/claim/messages`, `POST /claim/chat`, `POST /claim/chat/stream` (SSE), `POST /scenarios/calculate`, `/subscription/status`, `POST /subscription/revenuecat/sync` (native-only), `/shares` (GET, POST, DELETE `/shares/{id}`), `/shares/accept/{token}` (GET public, POST). Web-only (never native): `/subscription/checkout`, `/subscription/portal`.
