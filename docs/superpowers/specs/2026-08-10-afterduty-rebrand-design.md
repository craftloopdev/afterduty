# After Duty rebrand — design

**Date:** 2026-08-10 · **Owner:** Sean OBryan · **Status:** Stage 0 + Stage 1 **SHIPPED** 2026-08-11 (PR #118). **Stages 2 and 3 DEFERRED** by owner 2026-08-12 — see §6/§7; the deferred ledger owns them. Active scope of the rebrand is now Stage 1 completeness only.

## 1. Context and decision record

The product ships as "VA Claim Path" on vaclaimpath.com. The owner decided (2026-08-09) that the name relates to the VA too closely and could be read as representing the VA. Everything rebrands to **After Duty** on **afterduty.app**.

Owner decisions captured during design (2026-08-10):

| Decision | Choice |
|---|---|
| Name treatment | "After Duty" as the brand; store subtitle carries the descriptor ("VA claim prep & guidance" — tunable). Descriptive use of "VA" in copy is fine; the risk was the name itself. |
| App identifiers | Clean break to `com.afterduty.app` on **both** platforms. There are no mobile users. New App Store listing (old listing 6771148030 retired after new approval); new Google Play app (old app was never rolled out). |
| Hostnames | `afterduty.app` = marketing/legal · `app.afterduty.app` = web app · `api.afterduty.app` = API. Mirrors the current layout so the anticipated future hop to afterduty.com (purchased only on traction, ~$3k) is a config flip, not a rework. |
| WebAuthn | rpId → `afterduty.app` now (clean break). Existing passkeys stop matching; users fall back to OTP and re-enroll. The future .com hop will re-break passkeys — accepted; don't over-promote passkeys until the domain settles. |
| Internal code identifiers | Java package `com.vaclaimpath` → `com.afterduty` included (mechanical commit). Invisible infra names (Cloud Run services, bucket, DB, `cp_session`) deferred — see §7. |
| Artwork | Owner supplies new art later. Interim identity = existing shield mark + "After Duty" wordmark. Trademark line: "After Duty and the shield mark are trademarks of Craftloop." |
| Approach | Staged, infrastructure-out (A), over big-bang and fresh-repo. |

**Grounding:** a 5-agent inventory swept the repo and consoles: 62 deduplicated brand touchpoints across 8 categories + 13 gaps. Key file-level findings are folded into the stages below; the merged inventory JSON is preserved in the session workflow journal (`wf_490418ef-c74`).

## 2. Identity map (canonical old → new)

| Surface | Old | New |
|---|---|---|
| Product name | VA Claim Path | After Duty |
| Pro subscription display | VA Claim Path Pro | After Duty Pro |
| Web app host | app.vaclaimpath.com | app.afterduty.app |
| API host | api.vaclaimpath.com | api.afterduty.app |
| Marketing/legal | vaclaimpath.com | afterduty.app *(already live — §3)* |
| Privacy policy URL (stores + in-app) | app.vaclaimpath.com/privacy.html + vaclaimpath.com/privacy | **https://afterduty.app/privacy** (single consolidated URL; marketing site serves it today) |
| Bundle/package id | com.vaclaimpath.app | com.afterduty.app (new listings both stores) |
| Deep-link scheme | vaclaimpath:// | afterduty:// |
| Universal-link host / AASA appID | app.vaclaimpath.com / 3ZKP4S469J.com.vaclaimpath.app | app.afterduty.app / 3ZKP4S469J.com.afterduty.app |
| WebAuthn rpId / origin | app.vaclaimpath.com | afterduty.app / https://app.afterduty.app |
| Keychain server | com.vaclaimpath.app.deviceLogin | com.afterduty.app.deviceLogin |
| Java package / main class | com.vaclaimpath.* / VaClaimPathApplication | com.afterduty.* / AfterDutyApplication |
| Backend health service id | va-claim-path-api | afterduty-api (atomically with smoke asserts) |
| Sender + contact emails | noreply@/support@/privacy@/security@/conduct@ vaclaimpath.com | same locals @afterduty.app; old addresses forward indefinitely |
| RevenueCat entitlement | "VA Claim Path Pro" (verify actual id in dashboard) | `pro` (fresh apps, no subscribers) |
| Stripe products | display names + statement descriptor renamed **in place** (AFTERDUTY); customers/subscriptions untouched |
| Gradle/jar/spring names | va-claim-path / com.vaclaimpath | after-duty / com.afterduty |
| Internal window key / CSS class | `__VCP_FB__` / `cp-dark` | `__AD_FB__` / `ad-dark` (cheap atomic renames, fold into Stage 1) |
| **Unchanged (permanent legacy)** | Firebase/GCP project `craftloop-va-claim`, Cloud Run service names, GCS bucket `vaclaim-documents`, Cloud SQL `vaclaim`, `cp_session` cookie (deferred), `vcp.*` localStorage keys (deferred), git repo slug (rename later, GitHub auto-redirects) | |

## 3. Already done (pre-work, verified)

- **Email/domain runbook (2026-08-09/10):** afterduty.app is a verified user alias domain of the craftloop.dev Workspace; good@/sean@afterduty.app deliver. SPF + DKIM (selector `google`, "authenticating") + DMARC p=none live on both craftloop.dev and afterduty.app; outbound SPF/DKIM/DMARC = PASS verified. Send-as deferred by owner. Cloudflare DNS token (Zone.DNS:Edit, craftloop.dev + afterduty.app only) at `~/.gcp/cf-dns-token.txt`; change log at `~/.gcp/email-auth-changes-2026-08-09.log`.
- **Marketing site cutover (2026-08-10):** the VAClaimPath Pages project serves **https://afterduty.app** (+www, TLS). Zone rule `rebrand-301-vaclaimpath-to-afterduty` 301s vaclaimpath.com apex+www (path+query preserved) → afterduty.app; app./api. hostnames excluded and verified unaffected. `/privacy`, `/terms`, `/delete-account`, `/security` serve 200 with After Duty titles. Play-listing legal links remain valid through the 301.
- **Cross-session coordination:** `REBRAND-AFTERDUTY.md` briefs + CLAUDE.md banners in `~/Developer/VAClaimPath` and `~/Documents/craftloop`. The VAClaimPath repo owes: sitemap/canonical → afterduty.app; CTA + contact-email flips gated on Stage 1.

## 4. Stage 0 — Foundations (additive, zero user impact)

1. **SendGrid:** authenticate afterduty.app (CNAMEs via the existing DNS token). Verify trial-account send limits *now*; upgrade if OTP volume is at risk. Create sender identity noreply@afterduty.app. Old sender remains valid.
2. **Cloud Run domain mappings:** app.afterduty.app → `va-claim-web-next`, api.afterduty.app → `va-claim-api` (service names unchanged); DNS records; wait for managed TLS. Deploy SA per feedback memory (`~/.gcp/default-compute-sa.json` on PERMISSION_DENIED).
3. **Firebase Auth:** add app.afterduty.app (and afterduty.app) to authorized domains — the web client sets `authDomain` from `window.location.host`, so this is the OTP-login prerequisite.
4. **Execution-time verifications (gap closures):** read Stripe statement descriptor + product names via API (dashboard browser-blocked); confirm RevenueCat entitlement id; confirm whether the VA Lighthouse sandbox OAuth redirect (`api.vaclaimpath.com/api/va/oauth/callback` in docs/architecture/auth-program-plan.md) is a live registration; check Play data-safety deletion URL.
5. **Gate:** full OTP login + smoke pass on app.afterduty.app while old hosts still carry production traffic.

## 5. Stage 1 — Web + backend cutover (one PR, ordered commits, then env/console work)

**Commit 1 (mechanical):** Java package rename `com.vaclaimpath` → `com.afterduty` (~398 files) with, in the same commit: `.github/CODEOWNERS` paths, JPQL string literals (LlmJobRepository), gradle test filter, `application-local.yml` logging keys, gradle group/rootProject.name (`after-duty`), spring.application.name, main class rename.

**Commit 2 (brand sweep):** all user-visible strings + their tests:
- Backend: OTP/verify/recovery email subjects + "The After Duty team" signature (EmailCodeService ×3, EmailCodeAuthController ×3, RecoveryService ×1); WebAuthn rp-name default.
- Web: layout metadata title; 7 learn/service-history metadata titles; login/Sidebar wordmarks (4); DisclaimerModal/LegalFooter/LearnArticle/OfflineState/friendly-error copy; AcceptShare invite copy (highest exposure — seen by non-user VSOs); paywall copy → "After Duty Pro"; Face ID reason string; `web/public/privacy.html` (brand + trademark line + @afterduty.app emails).
- Internal cheap renames: `__VCP_FB__` → `__AD_FB__` (writer+reader), `cp-dark` → `ad-dark` (7 files).
- `constants.ts` PRIVACY_URL → `https://afterduty.app/privacy` (the five-identical-places invariant now binds the NEW ASC listing to this URL).

**Commit 3 (config hygiene):** ShareService ACCEPT_URL_PREFIX hardcode → env-driven property (default new domain; test updated); HealthController service id → `afterduty-api` atomically with tests/regression.py:98 + regression.sh:57; smoke/regression/post-deploy scripts + `.env.example` + deploy.sh → new domains; CORS allowed origins = old + new app hosts (transition window).

**Sequenced rollout:** deploy backend + web images → flip Cloud Run env vars (`WEBAUTHN_RP_ID=afterduty.app`, `WEBAUTHN_RP_NAME=After Duty`, `WEBAUTHN_RP_ORIGIN=https://app.afterduty.app`, `SENDGRID_FROM_EMAIL=noreply@afterduty.app`, `CHECKOUT_SUCCESS/CANCEL/PORTAL_RETURN` → app.afterduty.app, share prefix) → Stripe: rename products + statement descriptor in place; webhook cutover = create endpoint at api.afterduty.app → rotate `STRIPE_WEBHOOK_SECRET` in Secret Manager/Cloud Run → verify live events → disable (not delete) old endpoint → Cloudflare: 301 `app.vaclaimpath.com/*` → `app.afterduty.app/*`. **api.vaclaimpath.com dual-serves — never redirected** (API clients don't follow redirects) until Stage 2 retires old binaries.

**Accepted cost:** web users re-login once via OTP on the new host (301 drops host-scoped cookie); passkey users re-enroll.

**Gate:** updated smoke suites green against new domains; manual OTP login, Stripe checkout + portal return, share-link accept flow, email sender/DKIM check (should now sign as afterduty.app via SendGrid auth).

## 6. Stage 2 — New mobile apps (com.afterduty.app) — **DEFERRED 2026-08-12 (owner)**

> **Status: deferred indefinitely.** Stage 1 shipped and the web product is fully After Duty. Stage 2 is
> parked in the deferred ledger (§7) rather than scheduled. Nothing depends on it: neither store app has
> users, the old iOS listing and the never-rolled-out Play draft can sit as-is, and `api.vaclaimpath.com`
> simply keeps dual-serving (it is never redirected) for as long as the old binaries exist. The design
> below is preserved verbatim so the work can be picked up unchanged whenever mobile becomes a priority.
> While deferred, the old bundle id, `vaclaimpath://` scheme, universal-link host, keychain id, and every
> native/console artifact are **intentionally unchanged** and must not be reported as rebrand gaps.

**Code:** capacitor appId/appName; iOS PRODUCT_BUNDLE_IDENTIFIER, CFBundleDisplayName, permission purpose strings, entitlements `applinks:app.afterduty.app` only, scheme `afterduty://`, keychain `com.afterduty.app.deviceLogin`; AASA route appID `3ZKP4S469J.com.afterduty.app`; Android applicationId/namespace + Java package/MainActivity + strings.xml + manifest; deep-links.ts (new host + scheme only); screenshot scripts re-keyed; interim shield+wordmark art via `@capacitor/assets`; **post-build grep gate** over `web/out/`, native `public/` copies, and generated configs — zero "VA Claim Path"/"vaclaimpath" strings may ship (site-verification-style false positives excepted by allowlist).

**Consoles, in order:**
1. Firebase (project unchanged): register new iOS + Android apps, upload-key SHAs, pull new GoogleService-Info.plist / google-services.json. Reviewer test phone number carries over.
2. Apple: check "After Duty" name availability FIRST (fallback variant is an owner decision); register bundle id + associated domains; new ASC app; Xcode Cloud re-point (new plist secret); subscription group + `pro_monthly`/`pro_annual` ($11.99/$119.99); listing (subtitle, description embedding https://afterduty.app/privacy + stdeula, review notes with OTP test phone + demo account); recaptured screenshots; submit.
3. Play: new app on package com.afterduty.app; same upload keystore; re-enter App-content forms from docs/playstore/google-play-listing.md; privacy URL https://afterduty.app/privacy; internal testing → rollout.
4. RevenueCat: new iOS/Play apps → new `appl_`/`goog_` keys in revenuecat.ts; entitlement `pro`.
5. Retirements (only after new iOS app approved): old listing 6771148030 removed from sale; old Play draft deleted; old Firebase registrations (incl. legacy `com.craftloop.vaclaimpath`) deleted; then api.vaclaimpath.com mapping can be dropped after a grace period.
6. In-repo listing docs (docs/playstore/, docs/appstore/) updated in the same PR as console changes.

**Versioning:** marketing version continues (2.1.0); build numbers restart.

**Gate:** universal links verified on device; IAP sandbox purchase; store review approval; post-build grep gate clean.

## 7. Stage 3 — Identity polish + deferred ledger — **DEFERRED 2026-08-12 (owner)**

> **Status: deferred indefinitely**, alongside Stage 2. The interim identity (existing shield mark +
> "After Duty" wordmark) is what ships until the owner's artwork lands; it is coherent on its own, so
> there is no partial state to maintain. Preserved verbatim below for pickup.

**On artwork delivery:** replace `web/assets/` sources → regenerate all icon/splash sets; web favicons (`src/app/icon.png`, `favicon.ico`, `apple-icon.png`); marketing-site `favicon.svg` (VAClaimPath repo); recapture + re-upload store screenshots and listing graphics; ship as normal updates.

**Deferred ledger (tracked, not in scope).** As of 2026-08-12 this ledger owns **Stage 2 (new mobile apps
`com.afterduty.app`, new ASC + Play listings, Firebase/RevenueCat re-registration, native identifiers and
scheme)** and **Stage 3 (artwork/icon/screenshot refresh)** in their entirety, plus:

- **@afterduty.app contact mailboxes (deferred 2026-08-12, owner).** `support@`, `privacy@`, `security@`
  and `conduct@` are published in shipped copy (login-lockout screen, recovery page, paywall,
  privacy.html, SECURITY.md, CODE_OF_CONDUCT.md) but **hard-bounce**: afterduty.app is a Workspace *user
  alias domain*, so only local-parts that exist on the mailbox resolve — verified by SMTP RCPT probe,
  `good@` and `sean@` return 250, all four published addresses return 550. This is a **regression**:
  vaclaimpath.com is a catch-all and accepted every one of them. Owner fix (~2 min, Admin console →
  Directory → Users → Sean OBryan → Add alternate emails, or Groups for multi-responder): add the four
  local-parts; they then resolve at both craftloop.dev and afterduty.app with no redeploy. Until then the
  marketing site's `veterans@vaclaimpath.com` must NOT be flipped to @afterduty.app (it currently
  delivers), and `tests/check_published_emails.py` lists them in `KNOWN_DEFERRED` so the deploy gate
  reports the gap on every run without blocking. Delete those entries when the mailboxes exist.
- **GCP OAuth consent screen app name** still reads "VA Claim Path" (IAP brands API is read-only; Console →
  APIs & Services → OAuth consent screen). Firebase + GCP project display names are already "After Duty",
  which is what feeds the phone-OTP SMS, so this is limited to OAuth grant screens. Cloud Run service renames; GCS bucket; Cloud SQL names; `cp_session` → `ad_session` (requires BFF dual-read grace); `vcp.*` localStorage keys (one-time re-prompt if renamed); GitHub repo slug → `afterduty-app` (after Stage 2; update SECURITY.md advisory URL + remotes after); keystore file path cosmetics; DMARC p=none → quarantine after clean reports on both domains; Gmail send-as for @afterduty.app (owner-deferred); afterduty.com purchase + second domain hop (re-breaks passkeys; layout ports 1:1); internal docs bulk find/replace (historical dated docs stay as historical record).

## 8. Invariants and hard rules

1. **Never modify craftloop.dev MX** (legacy aspmx set); never click "Activate Gmail" for craftloop.dev.
2. **api.vaclaimpath.com is never 301'd** — dual-serve until old binaries are retired.
3. **Privacy URL invariant:** `constants.ts` PRIVACY_URL ≡ ASC privacy field ≡ App Store description ≡ review notes ≡ LegalFooter/paywall consumers. One value: `https://afterduty.app/privacy`.
4. **The vaclaimpath.com 301 rule and Pages custom domains stay in place indefinitely**; @vaclaimpath.com addresses forward indefinitely.
5. Generated artifacts (`web/out/`, native `public/`) are never hand-edited — rebuild and verify with the grep gate.
6. Additive-first: every stage's external changes are reversible (env revert, endpoint disable, rule delete) for at least one grace week.
7. Old iOS listing is retired only **after** the new listing is approved and smoke-tested.

## 9. Risks

| Risk | Mitigation |
|---|---|
| App Review rejection on fresh listing | Proven review-notes + Firebase test phone + demo-account provisioner; 2.1(a) fixes already in codebase |
| SendGrid trial limits break OTP email | Verify limits in Stage 0 before sender flip; old sender kept as instant rollback env value |
| Stripe webhook secret rotation race | New endpoint + secret deployed atomically; old endpoint disabled-not-deleted |
| Stale brand in shipped binaries | Post-build grep gate in Stage 2 |
| "After Duty" taken on ASC | Checked first in Stage 2; owner picks fallback variant |
| Passkey/biometric user surprise | Release note + re-enroll prompts; OTP always works |
| Smoke tests red mid-cutover | Scripts updated in the same commits as the contracts they assert; staged gates |

## 10. Validation matrix (every stage)

Backend + web unit suites green → updated regression.py/.sh + post-deploy.sh green → manual OTP login (email + SMS) → Stripe checkout + portal → share-link accept → email auth headers (SPF/DKIM/DMARC pass, correct d= domain) → (Stage 2) universal links, IAP sandbox, store review.
