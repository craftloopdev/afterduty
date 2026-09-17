# iOS native setup — capabilities, entitlements, secrets

This file documents the Xcode-GUI / Apple-account steps that cannot be done from
code (capacitor-ios-spec §G.2). The codebase already carries `App/App/Info.plist`
(purpose strings, URL schemes, export compliance) and `App/App/App.entitlements`.
The remaining wiring happens in Xcode's **Signing & Capabilities** tab on the
**App** target.

## 1. Reference the entitlements file

In the App target → Build Settings → set **Code Signing Entitlements** to
`App/App.entitlements` (Xcode does this automatically the first time you add a
capability below; verify it points at the committed file).

## 2. Add capabilities (App target → Signing & Capabilities → "+ Capability")

| Capability | Why | Spec |
|---|---|---|
| **Push Notifications** | Phone-auth silent-APNs verification ONLY (no messaging) | §B.4 |
| **Associated Domains** | Universal links: share-accept. Value: `applinks:app.afterduty.app` (add the Cloud Run preview host while testing) | §B.5 |
| **In-App Purchase** | RevenueCat / StoreKit paywall (no entitlements-file key needed) | §C.1 |

**Do NOT add Sign in with Apple.** It left with the third-party providers (owner
decision 2026-07-01) — login is pure passwordless OTP, so guideline 4.8 is
untriggered. The Stage 2 App ID `com.afterduty.app` (bundle id resource
`4LTFPMV6DF`) is registered with exactly `PUSH_NOTIFICATIONS`,
`ASSOCIATED_DOMAINS` and `IN_APP_PURCHASE`; adding the Apple Sign-In capability
in Xcode without enabling `APPLE_ID_AUTH` on the App ID fails provisioning.

The entitlements file declares `aps-environment:production` and the associated
domain; adding the capabilities in Xcode provisions the App ID to match.

## 3. GoogleService-Info.plist

Restore into the App target from the existing Xcode Cloud secret
`GOOGLE_SERVICE_INFO_PLIST_BASE64` (the same secret the Flutter workflow uses —
§B.1 / §D.4). Required for the Capacitor Firebase plugin's Google sign-in.

The Xcode project references `App/App/GoogleService-Info.plist` as a **required
Copy-Bundle-Resources input** (same posture as the Flutter target): any build —
local or CI — fails if the file is absent. It is gitignored (never commit a real
or fake one). In CI, `ci_scripts/ci_post_clone.sh` writes it from the secret;
locally, drop in your own copy (the screenshot pipeline already injects one).

## 4. Native build env (set in Xcode Cloud `ci_post_clone.sh` and locally)

`npm run build:native` requires (see `.env.example`):

- `NEXT_PUBLIC_API_BASE=https://api.afterduty.app/api`
- the six `NEXT_PUBLIC_FIREBASE_*` values
- `NEXT_PUBLIC_REVENUECAT_IOS_KEY=appl_XuvonHELPKuXURtGzVeGYQklSPF` (public)

## 5. Build + sync

```
cd web
npm run cap:sync        # build:native (release export, asserts no out/dev) + cap sync ios
```

Then open `web/ios/App/App.xcworkspace` in Xcode (or let Xcode Cloud build it —
§D.4). The capture build for screenshots is `npm run build:native:capture`.

## 6. Xcode Cloud workflow (CI) — owner runbook

The repo side is already wired; everything below is owner click-path only.

**What's committed:**

- `web/ios/App/ci_scripts/ci_post_clone.sh` — installs Node 26 (Homebrew; the
  Xcode Cloud image ships Homebrew but not Node), restores
  `GoogleService-Info.plist` from the `GOOGLE_SERVICE_INFO_PLIST_BASE64` secret,
  `npm ci` → `npm run build:native` → `npx cap sync ios` → `pod install`
  (Pods are not tracked in git), then asserts the §F.2 release guard (no `dev/`
  fixture routes in `out/` or the synced `App/App/public/`).
- `web/ios/App/ci_scripts/ci_pre_xcodebuild.sh` — stamps
  `CURRENT_PROJECT_VERSION = CI_BUILD_NUMBER + 100` (the Flutter v1.0 line on
  this app record ended at build **23**; the +100 floor keeps the new line
  strictly above it — §H.8) and `MARKETING_VERSION = 2.0.0`, and re-asserts the
  release guard when `CI_XCODEBUILD_ACTION = archive`.
- A **shared** `App` scheme
  (`App.xcodeproj/xcshareddata/xcschemes/App.xcscheme`) — Xcode Cloud only
  offers shared schemes.

**Owner click-path — REPLACE the Flutter workflow in place (preferred: keeps the
write-only `GOOGLE_SERVICE_INFO_PLIST_BASE64` secret and the TestFlight
distribution config attached):**

1. First do §2 (Signing & Capabilities in Xcode) so cloud signing can provision
   the App ID, and verify the Team in Signing matches the AASA team `3ZKP4S469J`.
2. App Store Connect → Apps → VA Claim Path (6771148030) → **Xcode Cloud** →
   Manage Workflows → open the existing (Flutter) workflow → **Edit**.
3. **Actions → Archive — iOS**: change Scheme from `Runner` to **`App`**
   (visible once this commit is pushed; Xcode Cloud rescans the repo). Keep
   Distribution Preparation = **App Store Connect / TestFlight (Internal)**.
4. **Start Conditions**: Branch Changes → `feat/next-web-foundation`
   (flip back to `main` after the merge).
5. **Post-Actions → TestFlight Internal Testing**: confirm your internal tester
   group is still attached — this is what auto-delivers EVERY successful build
   to enrolled devices via TestFlight, no Beta App Review wait. If the group
   was dropped by the edit, re-add it here.
6. **Environment**: confirm `GOOGLE_SERVICE_INFO_PLIST_BASE64` (secret) is still
   listed, and add the six plain `NEXT_PUBLIC_FIREBASE_*` vars +
   `NEXT_PUBLIC_API_BASE` / `NEXT_PUBLIC_REVENUECAT_IOS_KEY` if not inherited
   (values are the committed public ones in `web/.env.local` /
   `web/deploy.sh`).
7. Save → **Start Build**. Expect build number ≥ 101 (CI stamps
   `CI_BUILD_NUMBER + 100`, safely past Flutter's 23), version 2.0.0. The
   green build lands on TestFlight devices automatically via the post-action.

Renaming the workflow (e.g. "Capacitor v2.0") is cosmetic but recommended.
From this point Flutter is no longer built anywhere — this workflow IS the
app's build. If you ever need the old Flutter build back, recreate a workflow
against `flutter_frontend/ios/Runner.xcworkspace` scheme `Runner`.

**Fallback — create a NEW workflow instead** (if editing the scheme in place
misbehaves): "+" → workspace `web/ios/App/App.xcworkspace`, scheme `App`,
Archive iOS + TestFlight Internal post-action **with your tester group added**,
branch `feat/next-web-foundation`, re-enter the secret
(`base64 -i GoogleService-Info.plist | pbcopy`) + the plain env vars above —
then DISABLE the Flutter workflow immediately (same bundle id; two active
workflows would double-build and race build numbers).
