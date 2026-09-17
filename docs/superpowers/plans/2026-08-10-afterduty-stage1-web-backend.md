# After Duty Rebrand — Stage 0/1 (Web + Backend Cutover) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Rename the product from "VA Claim Path" to "After Duty" across backend + web, and cut the live web/API hosts over to app.afterduty.app / api.afterduty.app with old-host 301s — per the approved spec `docs/superpowers/specs/2026-08-10-afterduty-rebrand-design.md`.

**Architecture:** Three ordered code commits on one branch (mechanical Java package rename → user-visible brand sweep → config hygiene), then a sequenced ops rollout (deploy → env flips → Stripe rename/webhook cutover → old-host 301). Every commit keeps the full test suite green; smoke scripts move in the same commit as the contracts they assert.

**Tech Stack:** Spring Boot (Gradle Kotlin DSL) in `spring-backend/`, Next.js 16 in `web/`, Cloud Run (`craftloop-va-claim`, us-central1), Cloudflare (DNS token at `~/.gcp/cf-dns-token.txt` covers afterduty.app + craftloop.dev only), SendGrid (key at `~/.gcp/sendgrid-key`), Stripe live (key at `~/.gcp/stripe-live-secret.txt`).

## Global Constraints

- Product name: `After Duty` · Pro tier: `After Duty Pro` · sender: `noreply@afterduty.app` · privacy URL: `https://afterduty.app/privacy` (must equal ASC field, store description, review notes, and in-app constant — five-identical-places invariant).
- Hosts: web `app.afterduty.app`, API `api.afterduty.app`, marketing `afterduty.app` (already live).
- WebAuthn: `WEBAUTHN_RP_ID=afterduty.app`, `WEBAUTHN_RP_ORIGIN=https://app.afterduty.app`, `WEBAUTHN_RP_NAME=After Duty`.
- Java package: `com.vaclaimpath` → `com.afterduty`; main class `VaClaimPathApplication` → `AfterDutyApplication`; gradle root project `after-duty`.
- **Never** modify craftloop.dev MX. **Never** 301 `api.vaclaimpath.com` (dual-serve until Stage 2 retires old binaries). Keep vaclaimpath.com zone 301 rule + Pages custom domains in place.
- Do not hand-edit generated artifacts (`web/out/`, `web/ios/App/App/public/`, `web/android/app/src/main/assets/public/`) — they regenerate on build.
- Bundle/package ids (`com.vaclaimpath.app`) and the `vaclaimpath://` scheme are **Stage 2** — do NOT touch in this plan. Same for `cp_session`, `vcp.*` localStorage keys (deferred ledger).
- Deploy auth: `gcloud auth activate-service-account --key-file ~/.gcp/default-compute-sa.json` on PERMISSION_DENIED. Never `gcloud auth login`.

## Pre-flight state (verify, don't redo)

Some ops were executed interactively on 2026-08-10; each ops task below starts with a verification step and skips work already done:

- afterduty.app marketing site live on the VAClaimPath Pages project; vaclaimpath.com apex+www 301 active.
- Firebase Auth authorized domains already include `afterduty.app`, `app.afterduty.app`, `api.afterduty.app`.
- Cloud Run domain mappings for app./api.afterduty.app + the app.vaclaimpath.com 301 may already exist (session was mid-execution). Verify with the commands in Tasks 1–2 before creating.

---

### Task 1: Stage 0 ops — Cloud Run domain mappings + DNS for app./api.afterduty.app

**Files:** none (console/CLI only). Log every DNS record id to `~/.gcp/email-auth-changes-2026-08-09.log`.

**Interfaces:**
- Produces: `https://app.afterduty.app` and `https://api.afterduty.app` serving the existing services with valid TLS. Later tasks (env flips, smoke) depend on these hosts resolving.

- [ ] **Step 1: Verify current state (skip any sub-step already done)**

```bash
gcloud auth activate-service-account --key-file ~/.gcp/default-compute-sa.json
gcloud beta run domain-mappings list --project craftloop-va-claim --region us-central1
curl -sI -o /dev/null -w "%{http_code}\n" https://app.afterduty.app/   # 404/SSL error = not mapped yet
```

- [ ] **Step 2: Ensure the mapping SA is a verified owner of afterduty.app** (one-time; requires Site Verification API enabled on project 1048958573080 — owner clicked Enable on 2026-08-10)

```bash
TOKEN=$(gcloud auth print-access-token --scopes=https://www.googleapis.com/auth/siteverification)
# get SA-specific DNS token
curl -s -X POST -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  "https://www.googleapis.com/siteVerification/v1/token" \
  -d '{"verificationMethod":"DNS_TXT","site":{"type":"INET_DOMAIN","identifier":"afterduty.app"}}'
# create returned token as TXT at afterduty.app apex via Cloudflare API (CF_API_TOKEN=$(cat ~/.gcp/cf-dns-token.txt), zone 10561a6114f5d18291ffb4c3a7bb2b9a), then:
curl -s -X POST -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  "https://www.googleapis.com/siteVerification/v1/webResource?verificationMethod=DNS_TXT" \
  -d '{"site":{"type":"INET_DOMAIN","identifier":"afterduty.app"}}'
```

Expected: webResource response lists the SA as an owner.

- [ ] **Step 3: Create both mappings**

```bash
gcloud beta run domain-mappings create --service va-claim-web-next --domain app.afterduty.app --project craftloop-va-claim --region us-central1
gcloud beta run domain-mappings create --service va-claim-api      --domain api.afterduty.app --project craftloop-va-claim --region us-central1
```

- [ ] **Step 4: Create the DNS records the mappings request** (CNAME `app` and `api` → `ghs.googlehosted.com`, **DNS-only / proxied:false** — Google terminates TLS)

```bash
CF_API_TOKEN=$(cat ~/.gcp/cf-dns-token.txt); ZONE_AD=10561a6114f5d18291ffb4c3a7bb2b9a
for h in app api; do
  curl -s -X POST -H "Authorization: Bearer $CF_API_TOKEN" -H "Content-Type: application/json" \
    "https://api.cloudflare.com/client/v4/zones/$ZONE_AD/dns_records" \
    -d "{\"type\":\"CNAME\",\"name\":\"$h.afterduty.app\",\"content\":\"ghs.googlehosted.com\",\"proxied\":false,\"ttl\":3600}"
done   # log returned ids to the change log
```

- [ ] **Step 5: Wait for managed certs, then verify**

```bash
watch: gcloud beta run domain-mappings describe --domain app.afterduty.app --project craftloop-va-claim --region us-central1 --format='value(status.conditions)'
curl -sI -o /dev/null -w "%{http_code}\n" https://app.afterduty.app/      # expect 307 -> /login (auth gate)
curl -s https://api.afterduty.app/api/health | head -c 200                # expect the health/auth JSON the old host returns
```

### Task 2: Stage 1 ops (part A) — host-tied env flips + app.vaclaimpath.com 301

Do this only after Task 1 verifies. Code rename (Tasks 4–10) is independent and can proceed in parallel.

**Interfaces:**
- Produces: new host is the primary origin (WebAuthn + checkout URLs point at it); old web host 301s. Consumed by Task 11 smoke.

- [ ] **Step 1: Snapshot current env (rollback reference)**

```bash
for s in va-claim-api va-claim-web-next; do
  gcloud run services describe $s --project craftloop-va-claim --region us-central1 \
    --format='value(spec.template.spec.containers[0].env)' > ~/.gcp/rebrand-env-snapshot-$s-$(date +%Y%m%d).txt
done
```

- [ ] **Step 2: Flip host-tied env vars on va-claim-api** (brand-string env like SENDGRID_FROM_EMAIL waits for Task 3)

```bash
gcloud run services update va-claim-api --project craftloop-va-claim --region us-central1 \
  --update-env-vars WEBAUTHN_RP_ID=afterduty.app,WEBAUTHN_RP_NAME="After Duty",WEBAUTHN_RP_ORIGIN=https://app.afterduty.app,CHECKOUT_SUCCESS_URL=https://app.afterduty.app/upgrade/success,CHECKOUT_CANCEL_URL=https://app.afterduty.app/upgrade,STRIPE_PORTAL_RETURN_URL=https://app.afterduty.app/settings
```

(Confirm the exact current paths from the Step 1 snapshot first — keep paths identical, change host only. If a CORS/allowed-origins env exists in the snapshot, append `https://app.afterduty.app` there in the same update.)

- [ ] **Step 3: Verify login on the new host manually** — OTP email + SMS to app.afterduty.app must succeed before any redirect traps users there.

- [ ] **Step 4: 301 the old web host (Cloudflare dashboard, vaclaimpath.com zone — the API token does NOT cover this zone)**
  1. DNS: edit `app` CNAME record → toggle **Proxied** (orange cloud). (Universal SSL covers app.vaclaimpath.com.)
  2. Rules → Redirect Rules → create `rebrand-301-app-host`: custom expression `(http.host eq "app.vaclaimpath.com")` → Dynamic `concat("https://app.afterduty.app", http.request.uri.path)`, 301, preserve query string.
  3. Do NOT touch the `api` record.

- [ ] **Step 5: Verify**

```bash
curl -sI -o /dev/null -w "%{http_code} -> %{redirect_url}\n" https://app.vaclaimpath.com/login   # 301 -> https://app.afterduty.app/login
curl -s https://api.vaclaimpath.com/api/health | head -c 120                                     # unchanged (never redirected)
```

### Task 3: Stage 0 ops — SendGrid domain authentication for afterduty.app

**Interfaces:**
- Produces: afterduty.app DKIM-signed sending; unblocks `SENDGRID_FROM_EMAIL=noreply@afterduty.app` (flipped in Task 12).

- [ ] **Step 1: Create the domain authentication and read required CNAMEs**

```bash
SG_KEY=$(cat ~/.gcp/sendgrid-key)
curl -s -X POST https://api.sendgrid.com/v3/whitelabel/domains \
  -H "Authorization: Bearer $SG_KEY" -H "Content-Type: application/json" \
  -d '{"domain":"afterduty.app","automatic_security":true,"default":false}' | tee /tmp/sg-afterduty.json | jq '.dns'
```

- [ ] **Step 2: Create each returned CNAME via the Cloudflare API** (same POST pattern as Task 1 Step 4; proxied:false; log ids). Typically three records: `em####.afterduty.app`, `s1._domainkey.afterduty.app`, `s2._domainkey.afterduty.app`.

- [ ] **Step 3: Validate + check trial limits**

```bash
DOMAIN_ID=$(jq -r '.id' /tmp/sg-afterduty.json)
curl -s -X POST "https://api.sendgrid.com/v3/whitelabel/domains/$DOMAIN_ID/validate" -H "Authorization: Bearer $SG_KEY" | jq '{valid: .valid, results: .validation_results}'
curl -s https://api.sendgrid.com/v3/user/credits -H "Authorization: Bearer $SG_KEY"   # trial limit check — surface to owner if daily cap < 200
```

Expected: `valid: true`. **Do not flip the sender env until this passes.**

---

### Task 4: Code — mechanical Java package rename (spec Commit 1)

**Files:**
- Modify: every file matching `git grep -l 'com\.vaclaimpath\|VaClaimPathApplication\|va-claim-path' -- spring-backend .github/CODEOWNERS` (≈400)
- Rename dirs: `spring-backend/src/{main,test}/java/com/vaclaimpath/` → `.../com/afterduty/`

**Interfaces:**
- Produces: package `com.afterduty.*`, class `AfterDutyApplication`, gradle `rootProject.name=after-duty`, `group=com.afterduty`, `spring.application.name: after-duty`. All later backend tasks import from `com.afterduty`.

- [ ] **Step 1: Branch**

```bash
git checkout -b rebrand/stage1-web-backend
```

- [ ] **Step 2: Baseline count** — `git grep -c 'com\.vaclaimpath' | wc -l` (expect ≈398 files; record the number).

- [ ] **Step 3: Mechanical rename**

```bash
cd spring-backend
git mv src/main/java/com/vaclaimpath src/main/java/com/afterduty
git mv src/test/java/com/vaclaimpath src/test/java/com/afterduty
grep -rl 'com\.vaclaimpath' src build.gradle.kts settings.gradle.kts ../.github/CODEOWNERS | xargs sed -i '' 's/com\.vaclaimpath/com.afterduty/g'
grep -rl 'VaClaimPathApplication' src | xargs sed -i '' 's/VaClaimPathApplication/AfterDutyApplication/g'
git mv src/main/java/com/afterduty/VaClaimPathApplication.java src/main/java/com/afterduty/AfterDutyApplication.java 2>/dev/null || true  # adjust to actual path
sed -i '' 's/rootProject.name = "va-claim-path"/rootProject.name = "after-duty"/' settings.gradle.kts
sed -i '' 's/^spring:\n  application:\n    name: va-claim-path/&/' /dev/null  # do the yml edit by hand:
```

Edit `src/main/resources/application.yml`: `spring.application.name: va-claim-path` → `after-duty`. Edit `spring-backend/README.md:32` jar reference `va-claim-path-4.0.0.jar` → `after-duty-4.0.0.jar`. Check `application-local.yml` logging keys mentioning `com.vaclaimpath` (covered by sed if under src/main/resources — verify).

- [ ] **Step 4: Zero-stragglers check** — `git grep -n 'com\.vaclaimpath\|VaClaimPathApplication' -- spring-backend .github` → must return nothing.

- [ ] **Step 5: Full backend suite** — `cd spring-backend && ./gradlew test` → green (same count as pre-rename baseline).

- [ ] **Step 6: Commit** — `git commit -am "refactor(rebrand)!: com.vaclaimpath -> com.afterduty package rename (mechanical)"`

### Task 5: Code — backend user-visible strings (spec Commit 2a)

**Files:**
- Modify: `spring-backend/src/main/java/com/afterduty/service/EmailCodeService.java` (lines ≈267/272/287), `controller/EmailCodeAuthController.java` (≈82/86/94), `service/RecoveryService.java` (≈132), `src/main/resources/application.yml` (webauthn rp-name default, ≈line 86)
- Test: whichever tests assert those strings — find with `git grep -ln 'VA Claim Path' -- spring-backend/src/test`

**Interfaces:**
- Produces: every outbound email says "After Duty"; passkey dialogs say "After Duty".

- [ ] **Step 1: Update test expectations first** — in the test files found above, replace `VA Claim Path` → `After Duty`. Run `./gradlew test --tests '*Email*' --tests '*Recovery*'` → expect FAIL (source still old).
- [ ] **Step 2: Flip the 7 source strings + rp-name default** — exact replacements, e.g. subject `"Your VA Claim Path sign-in code"` → `"Your After Duty sign-in code"`, HTML signature `"The VA Claim Path team"` → `"The After Duty team"`, yml `rp-name: VA Claim Path` → `rp-name: After Duty`.
- [ ] **Step 3: Run** — `./gradlew test` green.
- [ ] **Step 4: Commit** — `git commit -am "feat(rebrand): After Duty in email copy and passkey rp-name"`

### Task 6: Code — web user-visible strings (spec Commit 2b)

**Files (source ↔ test pairs move together):**
- `web/src/app/layout.tsx` (metadata title), 7 metadata titles: `web/src/app/(app)/learn/page.tsx`, `learn/records/`, `learn/vso/` (+ body line ≈67), `learn/cp-exam/`, `learn/intent-to-file/`, `learn/how-va-rates/`, `service-history/page.tsx`
- `web/src/app/(auth)/login/page.tsx` (≈416/434/448), `web/src/components/shell/Sidebar.tsx:37` (wordmarks)
- `web/src/components/shell/DisclaimerModal.tsx:22`, `auth/LegalFooter.tsx:30`, `education/LearnArticle.tsx:28`, `ui/OfflineState.tsx:25`, `(auth)/login/friendly-error.ts`
- `web/src/components/share/AcceptShare.tsx` (≈32/147/161/195), `paywall/PaywallParts.tsx:88`, `paywall/PaywallView.tsx:422` ("After Duty Pro")
- `web/src/lib/auth/driver.native.ts:52` (`"Sign in to After Duty with Face ID."`)
- Tests: `PaywallCopy.test.tsx`, `friendly-error.test.ts`, `driver.native.test.ts`, `biometric.test.ts`, AcceptShare/Share tests — find the full set with `git grep -ln 'VA Claim Path' -- web/src`

- [ ] **Step 1:** `git grep -n 'VA Claim Path' -- web/src | wc -l` baseline (record).
- [ ] **Step 2:** Replace `VA Claim Path Pro` → `After Duty Pro` FIRST, then `VA Claim Path` → `After Duty` across `web/src` (ordered so Pro isn't double-replaced): `git grep -l 'VA Claim Path' -- web/src | xargs sed -i '' -e 's/VA Claim Path Pro/After Duty Pro/g' -e 's/VA Claim Path/After Duty/g'`
- [ ] **Step 3:** Route slugs (`/learn/cp-exam` etc.) and VA-terminology copy ("C&P exam", "VA claim") are NOT brand — verify the sed touched none: `git diff --stat` should list only the files above + tests.
- [ ] **Step 4:** `cd web && npm test` green; `git grep -n 'VA Claim Path' -- web/src` → empty.
- [ ] **Step 5:** Commit — `git commit -am "feat(rebrand): After Duty across web UI copy"`

### Task 7: Code — privacy page + PRIVACY_URL consolidation

**Files:**
- Modify: `web/public/privacy.html` (brand, trademark line → `After Duty and the shield mark are trademarks of Craftloop.`, emails → `privacy@afterduty.app`), `web/src/lib/constants.ts:18`
- Test: LegalFooter/paywall tests asserting the URL

- [ ] **Step 1:** `constants.ts` → `export const PRIVACY_URL = "https://afterduty.app/privacy";` — update the file's five-identical-places comment to name the new URL and the NEW (Stage 2) ASC listing.
- [ ] **Step 2:** privacy.html copy pass (brand strings covered by Task 6 sed if run repo-wide — it was scoped to web/src, so do this file explicitly; keep the "(formerly VA Claim Path)" note per the marketing site's convention).
- [ ] **Step 3:** Tests green; commit — `git commit -am "feat(rebrand): privacy policy + consolidated privacy URL"`

### Task 8: Code — internal cheap renames (`__VCP_FB__`, `cp-dark`)

**Files:** `web/src/lib/firebase/public-config.ts:15` + `web/src/app/layout.tsx` (writer+reader `__VCP_FB__` → `__AD_FB__`); `cp-dark` → `ad-dark` across `web/src/app/layout.tsx`, `src/styles/styles.css`, ThemeBoot/ThemeProvider/prefs/status-bar + `tokens.test` (7 files: `git grep -l 'cp-dark' -- web/src`)

- [ ] **Step 1:** Atomic sed both pairs; run `npm test`; verify `git grep -n '__VCP_FB__\|cp-dark' -- web/src` empty.
- [ ] **Step 2:** Commit — `git commit -am "refactor(rebrand): internal token renames (__AD_FB__, ad-dark)"`

### Task 9: Code — ShareService URL prefix → env-driven property (spec Commit 3a)

**Files:**
- Modify: `spring-backend/src/main/java/com/afterduty/service/ShareService.java:43`, `src/main/resources/application.yml`
- Test: `ShareControllerTest.java:82`

**Interfaces:**
- Produces: property `share.accept-url-prefix` (env `SHARE_ACCEPT_URL_PREFIX`), default `https://app.afterduty.app/accept-share/`. Task 12 sets the env var explicitly.

- [ ] **Step 1: Failing test** — change `ShareControllerTest` line ≈82 expected prefix to `https://app.afterduty.app/accept-share/`; run → FAIL.
- [ ] **Step 2: Implementation** — replace the hardcoded constant:

```java
@Value("${share.accept-url-prefix:https://app.afterduty.app/accept-share/}")
private String acceptUrlPrefix;
```

and in `application.yml`: `share.accept-url-prefix: ${SHARE_ACCEPT_URL_PREFIX:https://app.afterduty.app/accept-share/}`.

- [ ] **Step 3:** `./gradlew test` green; commit — `git commit -am "feat(rebrand): share accept-URL prefix env-driven"`

### Task 10: Code — health id + smoke/deploy scripts + test-fixture domains (spec Commit 3b)

**Files:**
- Modify: `spring-backend/.../controller/HealthController.java:19,27` (`va-claim-path-api` → `afterduty-api`, message → `After Duty API v...`), `tests/regression.py` (≈:98 service assert, :351 default URL, banners), `tests/regression.sh` (:9/:14/:57), `tests/post-deploy.sh`, `tests/test_upload_flow.py:24`, `tests/create_demo_account.py` (run.app URL stays — service unrenamed), `web/deploy.sh`, `web/.env.example:28`, `web/src/lib/api/direct.ts` (comments), `web/ios/App/ci_scripts/ci_post_clone.sh:76-83` (API origin default → `https://api.afterduty.app`)
- Web tests with old-domain fixtures: `deep-links.test.ts`*, `ShareManager*.test.tsx`, `share.test.ts`, `checkout-return.test.ts:30`, `passkey.test.ts`, `driver.web.passkey.test.ts`, `login/page.passkey.test.tsx:155`, `fixtures/shares.ts`; spring `SecurityAuthzRegressionTest.java:46,56` (`@vaclaimpath.test` → `@afterduty.test`)

*`deep-links.ts` itself (universal-link hosts) is Stage 2 — in this task only change test fixtures that assert the SHARE/checkout domains, not the deep-link host allowlist.

- [ ] **Step 1:** HealthController + both smoke asserts in one edit set; `./gradlew test` green.
- [ ] **Step 2:** Script/domain sweep: `grep -rn 'vaclaimpath' tests/ web/deploy.sh web/.env.example web/ios/App/ci_scripts/ci_post_clone.sh` — replace app./api. hosts with afterduty equivalents; leave `com.vaclaimpath.app` bundle-id references untouched (Stage 2).
- [ ] **Step 3:** `npm test` + `./gradlew test` green.
- [ ] **Step 4:** Commit — `git commit -am "chore(rebrand): health id, smoke scripts, fixtures -> afterduty domains"`

### Task 11: Build gates + PR

- [ ] **Step 1:** `cd web && npm run build:native && npx cap sync` (regenerates `web/out/` + native `public/`).
- [ ] **Step 2:** Artifact grep gate:

```bash
grep -rIl 'VA Claim Path\|vaclaimpath' web/out web/ios/App/App/public web/android/app/src/main/assets/public \
  | grep -v 'com.vaclaimpath.app' || echo CLEAN
```

Expected: `CLEAN` except bundle-id (`com.vaclaimpath.app`) and `vaclaimpath://` scheme occurrences (Stage 2) and the site-verification TXT-style strings. Anything else = a missed source string; fix and rebuild.

- [ ] **Step 3:** Full suites one more time; push branch; open PR titled `feat(rebrand)!: After Duty — Stage 1 web+backend` with the spec linked; merge per normal review.

### Task 12: Stage 1 ops (part B) — brand env flips + Stripe cutover (after PR merged + deployed)

- [ ] **Step 1: Deploy** backend + web per `web/deploy.sh` / existing backend deploy flow; post-deploy smoke (`tests/post-deploy.sh`) green against the new domains.
- [ ] **Step 2: Sender flip** (requires Task 3 `valid:true`): `gcloud run services update va-claim-api ... --update-env-vars SENDGRID_FROM_EMAIL=noreply@afterduty.app,SHARE_ACCEPT_URL_PREFIX=https://app.afterduty.app/accept-share/` then trigger an email-OTP login; verify received headers show `d=afterduty.app` DKIM pass.
- [ ] **Step 3: Stripe rename in place** (`SK=$(cat ~/.gcp/stripe-live-secret.txt)`):

```bash
curl -s https://api.stripe.com/v1/products -u $SK: | jq -r '.data[] | [.id,.name] | @tsv'   # find the two products
curl -s -X POST https://api.stripe.com/v1/products/{PRODUCT_ID} -u $SK: -d name="After Duty Pro"          # each product
curl -s https://api.stripe.com/v1/account -u $SK: | jq '.settings.payments.statement_descriptor'
# statement descriptor is ACCOUNT-level on the shared craftloop account — decide with owner before changing;
# if per-product descriptors are in use, set: -d statement_descriptor="AFTERDUTY"
```

- [ ] **Step 4: Webhook cutover (atomic):** create endpoint `https://api.afterduty.app/api/subscription/webhook` (same enabled events as old — read them first with `GET /v1/webhook_endpoints`); store new signing secret in Secret Manager (`STRIPE_WEBHOOK_SECRET`); redeploy/refresh api service; send a test event; **disable** (not delete) the old endpoint.
- [ ] **Step 5: Full validation matrix** (spec §10): OTP email+SMS login, checkout + portal return, share-link accept, email auth headers, smoke suites. Update `REBRAND-AFTERDUTY.md` briefs (VAClaimPath repo: CTAs may now flip to `app.afterduty.app`; craftloop.dev may link the new brand).

---

## Self-review notes

- Spec §4 (Stage 0) → Tasks 1–3. §5 (Stage 1 commits) → Tasks 4–10 map to commits 1/2a/2b/3a/3b; §5 rollout → Tasks 2 + 12 (split so the owner-visible host fix lands first, per owner's 2026-08-10 directive). §8 invariants restated in Global Constraints. Stage 2/3 intentionally out of scope (separate plan).
- Exact line numbers are from the 2026-08-10 inventory; treat as ≈ and re-locate with the given greps if drifted.
- CHECKOUT_*/PORTAL paths in Task 2 Step 2 must be read from the live env snapshot — the path components are assumptions; host is the change.
