# Security Review — `va-claim-path-app` (pre-public), 2026-06-01

> **2026-08-02 note:** the frontend reviewed below is the retired Flutter app, removed in the
> flutter cleanup (see `docs/maintenance/dead-code-audit-2026-08-02.md`). The live Next.js
> frontend (`web/`) has not had an equivalent full review — the cutover meets `audit.md`'s
> re-audit trigger. The body of this report is preserved unchanged as a historical record.

## 1. Executive summary

`va-claim-path-app` is being prepared to go **public** on GitHub for peer review and self-hosting.
It is a Spring Boot 4.0.4 / Java 25 backend + Flutter (web/iOS/Android) frontend running on Google
Cloud Run + Cloud SQL (Postgres) + Firebase Auth + Google Cloud Storage, using Anthropic Claude and
Vertex Gemini for claim synthesis and Stripe/RevenueCat for billing. It processes **veterans' health
summaries and disability data — sensitive personal information** — so the bar is high.

This review is a read-only, full-codebase audit (8 dimensions, multi-agent, every finding
adversarially re-verified against the actual source, then reconciled with manual review of the
authentication, authorization, and configuration code). It is the broad **application +
infrastructure + governance** audit that the earlier secrets/OSS-hygiene pass
(`OPEN_SOURCE_TODO.md`) never performed.

### Risk posture — counts (post-verification, public-relevant)

| Severity | Count | Notes |
|---|---|---|
| Critical | 0 | No live-prod takeover and no secret-exposure emergency. |
| High | 3 (+1 self-host) | 3 IDOR/authz bugs on the live API; 1 fail-open default that mainly endangers self-hosters. |
| Medium | 1 | 1 lower-sensitivity IDOR (operational metadata). |
| Low | ~12 | Defaults, CI/governance, hardening. |
| Info / verified-clean | many | Documented so they aren't re-chased. |

### Do this before going public (the gate)
1. Fix the **Tier-1 authorization bugs** (VCP-AUTHZ-01..04) and add a regression test for each.
2. Fix the **Tier-2 insecure defaults** (VCP-DFLT-01..03) — chiefly: make `dev-mode` fail *closed*
   and delete the unverified-JWT decode path.
3. Complete the **Tier-4 operational checklist** (branches, tags, GCP-side controls).
4. Only then commit this report (statuses → ✅ Fixed) and flip the repo to public.

> **Note on a misdirected instruction.** During this engagement an instruction arrived describing an
> *Express + React + Terraform* application (`server/index.js`, `terraform/main.tf`, findings
> `VCP-C-01..C-09`, "world-readable PHI bucket", "open database", "JWT none"). **None of those files
> or technologies exist in this repository** (this is Spring Boot + Flutter; 440 files; 224 commits).
> That catalog was treated as belonging to a different project and used only as a generic *pattern
> checklist*; every item below was verified against the real code. **No finding in this report is
> imported from that catalog.**

## 2. Scope & methodology

- **In scope:** entire tracked tree + full git history (224 commits, all branches/tags), backend
  Java, Flutter Dart, Android/iOS native, deploy/CI scripts, `.github/`, Dockerfiles, nginx config,
  Spring profiles, and data/fixtures.
- **Method:** read-only static review + `git log -p`/grep over history; multi-agent fan-out across 8
  dimensions (secrets/history, backend authz, backend injection/data, frontend, CI/infra
  supply-chain, PII/compliance, dependencies/build, governance); each finding independently
  re-verified by reading the cited code; severities calibrated to the *deployed* posture and to the
  *public-repo delta* ("what specifically gets worse when the source is public").
- **Out of scope (this pass):** live GCP/Firebase console configuration (documented as operational
  checks), penetration testing of the running service, and behavioral changes to billing/AI logic.

## 3. Verified clean / strong (do not re-chase)

- **Secrets & history:** zero real credentials in the tree or anywhere in 224 commits/all
  branches/tags across all checked classes (GCP SA JSON, OAuth, RevenueCat, Apple `.p8/.p12`, Android
  keystores, RSA/EC keys, JWT/HMAC secrets, live Stripe/Anthropic/Vertex keys). The Firebase web key,
  DB password, and admin emails were **never in this repo's tracked history** — always env-vars or
  placeholders. `.env`, `GoogleService-Info.plist`, `google-services.json`, `key.properties`,
  `.internal/`, `CLAUDE.md`, `OPEN_SOURCE_TODO.md` are correctly gitignored and never tracked. Both
  `.env.example` files are placeholder-only. The `sk_test_anything` Stripe string is the documented
  fake sentinel (allowlisted in `.gitleaks.toml`). Test health fixture is synthetic.
- **Injection classes:** **no SQL/JPQL injection** (all queries parameterized; `findByIdAndUserId`
  patterns), **no XXE**, **no insecure deserialization** (no Jackson polymorphic typing), **no SSRF**
  (outbound calls use fixed base URLs), **no mass assignment** (request bodies bind to DTOs, never to
  `@Entity`).
- **Authorization core:** `ClaimAccessService.resolve`/`assertScope` is a sound owner-or-active-share
  chokepoint with deny-by-default (404/403) and subscription gating; `IntakeController` (12 checks)
  and the share + `X-View-As` paths route through it correctly.
- **Sharing:** invitation tokens are 256-bit `SecureRandom`, single-use, with expiry — not guessable
  or enumerable.
- **Billing:** Stripe webhook is signature-verified; entitlement state (`subscriptionExpiresAt`) is
  not settable from any request body — it cannot be forged via the API.
- **Frontend:** no insecure on-device storage of tokens or veteran health data; **no WebView/JS
  bridge**; Android manifest hardened (no rogue exported components, no `usesCleartextTraffic`, minify
  on); iOS has no ATS arbitrary-loads exception; deploy/CI scripts embed no credentials.
- **Dependencies:** current, not stale — Spring Boot 4.0.4 / Java 25 / Gradle 9.4; Flutter deps all
  from pub.dev with committed lockfiles; e2e npm deps are dev-only and pinned. The gap is the
  *absence of automated scanning*, not vulnerable versions.
- **GitHub Actions:** the one workflow (`gitleaks.yml`) triggers on `pull_request` (not
  `pull_request_target`), so fork PRs cannot exfiltrate secrets, and there is no shell script
  injection. (Hardening of permissions/pinning is VCP-HARD-01.)

## 4. Findings

> Severity reflects the **public-repo** lens. `file:line` references are from the audited tree.

### VCP-AUTHZ-01 — IDOR: `getGapDetail` leaks any veteran's condition by `conditionId` · HIGH
- **Where:** `spring-backend/.../controller/GapAnalysisDebugController.java:59-76`;
  `ConditionRepository` (no scoped lookup).
- **CWE:** CWE-639 (Authorization Bypass Through User-Controlled Key), CWE-284.
- **Evidence:** `GET /api/claim/debug/gap-detail/{conditionId}` calls `getCurrentUser(request)` (authn
  only, return value discarded) then `conditionRepository.findById(conditionId)` **unscoped**, and
  returns `name`, `vasrd_code`, `body_system`, `estimated_rating`, `gaps`, `what_if_scenarios`.
  `IdentifiedCondition` uses `GenerationType.IDENTITY` → sequential, enumerable IDs.
- **Impact / public-repo risk:** any authenticated user enumerates `conditionId` and harvests other
  veterans' diagnosed conditions and AI claim-strategy gaps (health-derived PII). Public source hands
  attackers the exact route, the discarded-`getCurrentUser` tell, and the sequential-ID model for use
  against the live API.
- **Fix:** resolve ownership before returning — mirror the same file's `runEnhancedGapAnalysis`
  (`claimRepository.findByIdAndUserId(condition.getClaimId(), user.getId())`, 404 otherwise) and/or
  route through `ClaimAccessService.resolve`. Also admin-gate the debug controller (VCP-AUTHZ-03).
- **Guard:** regression test — user-B token reading user-A's `conditionId` → 403/404.

### VCP-AUTHZ-02 — IDOR: scenario endpoints resolve arbitrary `conditionIds` unscoped · HIGH
- **Where:** `ConditionController.java:56-68` (`createScenario`), `:70-106` (`updateScenario`/
  `listScenarios`), `:209-221` (`toScenarioResponse`).
- **CWE:** CWE-639, CWE-862 (Missing Authorization).
- **Evidence:** `conditionRepository.findAllById(req.getConditionIds())` with no owner filter;
  `toScenarioResponse` echoes each condition's `name`, `vasrd_code`, `estimated_rating`,
  `original_rating`. The scenario row itself is owner-scoped (`findByIdAndUserId`) — the leak is the
  *referenced conditions*. Cleanest PoC: `POST /api/scenarios {"conditionIds":[1..N],"name":"x"}` →
  201 response echoes victims' condition names + rating estimates.
- **Impact / public-repo risk:** cross-tenant disclosure of condition names (e.g. specific diagnoses)
  and VA rating estimates via a normal product endpoint; combined with VCP-AUTHZ-01 it maps a
  victim's whole claim profile. Source publishes the exact request shape + missing filter.
- **Fix:** add a scoped query (e.g. `findByIdInAndClaim_UserId(ids, userId)`) and use it everywhere
  scenarios resolve conditions; drop/403 IDs the caller doesn't own.
- **Guard:** regression test — `createScenario` with another user's `conditionId` returns no foreign
  condition data (403 or filtered-empty).

### VCP-AUTHZ-03 — Debug controllers not admin-gated (LLM-cost abuse + cap/subscription bypass) · HIGH
- **Where:** `SynthesisDebugController.java:43-80`, `StrategyDebugController.java:41-68`,
  `GapAnalysisDebugController.java:39-54`, `EventPipelineController.java:63-194`,
  `IntakeController.java:520-626` (`/debug/*`).
- **CWE:** CWE-862, CWE-285, CWE-770 (Resource Allocation Without Limits).
- **Evidence:** these handlers call only `getUser(request)` — **no `AdminCheck.isAdmin`** — unlike the
  gated `IntakeController.analyzeClaim:437`. They are registered unconditionally in the `cloud`
  profile. Several AI-triggering paths (`/debug/synthesis`, `/debug/gap-analysis`,
  `enhanced-synthesis`, `enhanced-gap-analysis`) bypass `UsageGuard.assertCapacity` and
  `requireActiveSubscription`; `/strategy-info` discloses the internal strategy taxonomy.
  *(Verifier corrections: `StrategyDebugController.optimized-synthesis` is a deprecated stub — no AI;
  `/debug/re-extract` does hit `assertCapacity`. Most debug paths act on the caller's own claim — the
  cross-tenant leak is the `getGapDetail` route, tracked as VCP-AUTHZ-01.)*
- **Impact / public-repo risk:** any logged-in (free) user grinds expensive Anthropic/Vertex runs,
  bypassing the usage cap and subscription gate; the public repo publishes the full unguarded debug
  route map.
- **Fix:** gate every `/api/claim/debug/**` + the `*DebugController`/`EventPipelineController` write
  endpoints behind `AdminCheck.isAdmin` (403), **or** compile them out of `cloud` with
  `@Profile`/`@ConditionalOnProperty`. Route every AI path through `UsageGuard.assertCapacity`.
- **Guard:** regression test — non-admin → 403 on representative `/api/claim/debug/**` routes.

### VCP-AUTHZ-04 — IDOR: `costByClaimId` missing `requireAdmin` · MEDIUM
- **Where:** `AiCostController.java:220-243`.
- **CWE:** CWE-639.
- **Evidence:** unlike siblings `costSummary` (`:43`) and `costByUser` (`:144`), `costByClaimId` takes
  no `HttpServletRequest`, never calls `requireAdmin(request)`, and never checks claim ownership. Any
  authenticated user enumerates `claimId` and reads per-call AI metadata (`call_type`, `model`, token
  counts, `total_cost`, `latency_ms`, `status`, timestamps). *Not* PHI, but broken object-level
  authorization + activity enumeration.
- **Fix (lowest-effort):** add `HttpServletRequest` param and `requireAdmin(request)` as the first
  line, matching the siblings.
- **Guard:** regression test — non-admin → 403 on `/api/ai-costs/claim/{id}`.

### VCP-DFLT-01 — Fail-open `dev-mode` default + unverified-JWT decode · HIGH (for self-hosters)
- **Where:** `application.yml:38` (`dev-mode: ${DEV_MODE:true}`); `application-local.yml:12`
  (hardcoded `true`); `FirebaseAuthService.java:25` (`@Value(...:true)`), `:36-39` (X-User-Email path),
  `:51-84` (unverified decode); `FirebaseConfig.java:23,52-58`; `AdminCheck.java:35-40`.
- **CWE:** CWE-1188 (insecure default), CWE-287, CWE-347 (improper signature verification), CWE-302.
- **Evidence:** when `dev-mode` is true, `resolveUser` authenticates **any** `X-User-Email:` header
  with no token, and on Firebase-verify failure **base64-decodes the JWT without verifying the
  signature** (`extractEmailFromToken`) and trusts its `email` claim. `AdminCheck` is case-insensitive
  email equality, so `X-User-Email:<admin>` yields admin.
- **Production is mitigated (verified):** `Dockerfile:13` pins `--spring.profiles.active=cloud`;
  `application-cloud.yml:17` sets `dev-mode: ${DEV_MODE:false}`; `tests/regression.sh:78-89` asserts
  `X-User-Email` → 401 in prod. So this is **not** a live-prod emergency and going public does not
  newly expose the live API (the headers are black-box-probeable today regardless of source).
- **Public-repo risk (why it still matters):** (a) the README's own local-run path
  (`--spring.profiles.active=local`) and the base default are **fully unauthenticated**, so every
  self-hoster/contributor inherits a wide-open backend with the bypass header now public knowledge;
  (b) the unverified-JWT-decode fallback is a footgun that should not exist in shipped code.
- **Fix:** flip the base default to `dev-mode:false` everywhere (`${DEV_MODE:false}` + `@Value(...:false)`),
  keep `true` only in `application-local.yml`; **delete `extractEmailFromToken` / the unverified-decode
  path**; add a startup guard that refuses to boot if `dev-mode=true` under a non-local profile;
  document `DEV_MODE` prominently in README/SECURITY.md.
- **Guard:** regression test — `cloud` profile + `X-User-Email` → 401; boot guard test; (and the
  existing `regression.sh` Test 4).

### VCP-DFLT-02 — Auth-bypassing `/dev/*` route gallery ships in prod web builds · LOW
- **Where:** `flutter_frontend/lib/main.dart:386-413` (`/dev/*`), `:135-136` (`/ai-costs`,
  `/pipeline-debug`).
- **Fix:** guard with `if (!kReleaseMode && isDevRoute)` or `--dart-define=ENABLE_DEV_ROUTES`; add a
  client-side non-admin redirect (defense-in-depth; the backend authz fixes are the real control).

### VCP-DFLT-03 — `/h2-console` in public auth-skip list · LOW
- **Where:** `SecurityConfig.java:66-67`. **Fix:** remove the `/h2-console` skip from the `cloud`
  profile (dev-only), comment that these exemptions are dev-only — so self-hosters don't expose a DB
  web console.

### VCP-HARD-01..06 — Governance / CI supply-chain / hardening · LOW
- **01 CI:** add top-level `permissions: { contents: read }` to `gitleaks.yml`; SHA-pin
  `actions/checkout` + `gitleaks/gitleaks-action`; add `.github/dependabot.yml` (gradle, pub, npm @
  `flutter_frontend/tests/e2e`, github-actions) + `.github/workflows/codeql.yml`; enable GitHub native
  Secret Scanning + Push Protection and Dependabot alerts.
- **02 Branch protection:** enable on `main` (require PR + status checks, no force-push,
  dismiss-stale-reviews, "require approval for first-time contributors").
- **03 Governance files:** add `CODEOWNERS`, `CODE_OF_CONDUCT.md`, DCO in `CONTRIBUTING.md`; remove/
  relocate `.github/ISSUE_TEMPLATE/opportunity.yml` (business strategy) + `feedback.md` (internal
  AI-loop); reword `feature.yml`'s AI-self-test framing for humans.
- **04 Firebase boundary:** commit `firebase.json` + default-deny `firestore.rules`/`storage.rules`
  (per-uid scoping); implement the `X-Firebase-AppCheck` validation `SECURITY-OPS.md` promises, or
  drop the claim.
- **05 nginx headers:** `server_tokens off`, `X-Frame-Options DENY`, `X-Content-Type-Options nosniff`,
  `Referrer-Policy`, HSTS, and a CSP tested against the CanvasKit/skwasm build.
- **06 Rate limiting + logs:** bucket4j limits on `/api/claim/chat`, `/quick-add`, debug routes, and
  public `/api/shares/accept/{token}`; stop logging veteran-supplied filenames at INFO
  (`EventPipelineController.java:74`, extraction services) — log IDs or a hash.

### VCP-OPS-01..06 — Pre-publish operational checklist (non-code)
See [remediation-checklist.md](./remediation-checklist.md) Tier 4. Highlights: delete 12 pushed
`origin/claude/*` branches; never push the 5 local pre-scrub backup tags (confirm origin has 0 tags);
execute & verify the GCP-side controls in `SECURITY-OPS.md`; move `tools/session-start.sh` to
`.internal/`; confirm `security@afterduty.app` is monitored; make the maintainer-identity decision
and ensure MFA on the infra Google account.

## 5. Verified clean / ruled out (considered, not blocking)

Documented so future reviews don't re-flag them. Each was confirmed against the code.

- **Injected Express/React/Terraform catalog** — wrong codebase; not applicable.
- **"Rotate leaked secrets" as an emergency** — the DB password and Firebase key were never in this
  repo's tracked history; rotation is optional cheap insurance, not a fix for an exposure.
- **CORS `*`** (`SecurityConfig.java:48`) — `allowCredentials=false` and Bearer-token (not cookie)
  auth ⇒ no credentialed-CORS attack; the policy is observable via one request regardless of source.
  Defense-in-depth only (env-driven origin allowlist for self-hosters).
- **No backend security headers / Docker-as-root / no `.dockerignore`** — low/info; headers are
  observable via `curl -I`; Cloud Run runs containers in a gVisor sandbox; the deploy path uses
  `--source .` governed by `.gcloudignore` (exists), and `.env` is gitignored so `COPY . .` cannot
  leak it.
- **Dependency / Gradle-wrapper checksum pinning** — info; no PR-triggered workflow builds with
  Gradle, so no fork-PR toolchain-poisoning vector; versions derivable from the public Spring Boot BOM.
- **No application access-audit table; no app-layer/CMEK encryption** — real backlog/compliance items,
  but pre-existing and not worsened by going public.
- **RevenueCat webhook non-constant-time compare; verbose-error reflection** — low; gating secret is
  an env var (not in tree); `server.error.include-message` defaults to `never`.
- **`/Users/sobryan/` path in old history** (`regression/MANIFEST.md`, scrubbed in current tree) —
  exposes only the already-public username; not worth a history rewrite.
- **Maintainer gmail in 190/224 author fields + `LICENSE`** — maintainer's deliberate call (already
  public via the GitHub profile).

## 6. Keeping it fixed
The "stays fixed" mechanism: **regression tests** (primary — each authz/default fix asserts the attack
now returns 403/404 and runs in CI as a required check), **CI scanners** (gitleaks + Dependabot +
CodeQL + secret push-protection), and a **periodic re-audit** (re-run the saved audit workflow — see
[audit.md](./audit.md) — and reconcile against [remediation-checklist.md](./remediation-checklist.md)).

## 7. Pre-publish gate
Flip the repo to public **only** when: every Tier-1 + Tier-2 item is `fixed` (or consciously
`accepted`) in the checklist with a guard; the Tier-4 ops checklist is complete; this report shows
resolved status; and `origin` contains only `main` with 0 tags.
