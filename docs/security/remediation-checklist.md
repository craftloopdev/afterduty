<!--
  SINGLE SOURCE OF TRUTH for the pre-public security remediation.
  Future Claude Code sessions and CI reconcile against THIS file.
  Status values: open | in-progress | fixed | accepted | wontfix
  When you fix an item: set status=fixed, fill `fix` (commit/PR) and `guard`
  (the regression test or scanner that keeps it fixed), and update the matching
  section in security-review-2026-06-01.md.
  New findings from later re-audits get the NEXT free ID in their category and a row here.
-->
# Remediation Checklist — `va-claim-path-app` pre-public security review

- **Review:** [security-review-2026-06-01.md](./security-review-2026-06-01.md)
- **Last reconciled:** 2026-06-01
- **Gate:** every Tier-1 + Tier-2 item must be `fixed` or consciously `accepted`, and the
  Tier-4 ops checklist complete, **before** the GitHub repo is flipped to public.

## Status legend
`open` = not started · `in-progress` = being worked · `fixed` = remediated + guarded by a test/scanner ·
`accepted` = risk acknowledged, no change · `wontfix` = out of scope by decision.

## Tier 1 — Real authz bugs on the live API (BLOCKER; fix + regression test before public)

| ID | Sev | Title | Location | Status | Fix (commit/PR) | Guard (test/scanner) |
|----|-----|-------|----------|--------|-----------------|----------------------|
| VCP-AUTHZ-01 | HIGH | IDOR — `getGapDetail` returns any veteran's condition by `conditionId` | `GapAnalysisDebugController.java:59-76` | **fixed** | central admin gate on `/api/claim/debug/**` (`SecurityConfig.authFilter`) — on `main` | `SecurityAuthzRegressionTest.debugAtoms_forbiddenForNonAdmin_allowedForAdmin` |
| VCP-AUTHZ-02 | HIGH | IDOR — scenario endpoints resolve arbitrary `conditionIds` unscoped | `ConditionController.java` (saved-scenario CRUD) | **fixed — surface since removed** | the saved-scenario CRUD was deleted with the Flutter era; the only surviving route, `POST /api/scenarios/calculate`, takes a `List<Integer>` of ratings and never resolves condition ids. The owner-scoped `ConditionRepository.findByIdInAndClaim_UserId` is kept for any future caller-supplied ids (verified 2026-09-16 while reconciling `claude/security-remediation`) | no id-taking endpoint remains; `SecurityAuthzRegressionTest` covers the debug + cost gates |
| VCP-AUTHZ-03 | HIGH | Debug controllers not admin-gated (LLM-cost abuse + cap/sub bypass) | `*DebugController.java`, `EventPipelineController.java`, `IntakeController.java:520-626` | **fixed** | central admin gate on `/api/claim/debug/**` (`SecurityConfig.authFilter`) — covers all current + future debug routes | `SecurityAuthzRegressionTest.debugPipelineTrigger_forbiddenForNonAdmin` |
| VCP-AUTHZ-04 | MED | IDOR — `costByClaimId` missing `requireAdmin` | `AiCostController.java:220-243` | **fixed** | added `HttpServletRequest` + `requireAdmin(request)` first line | `SecurityAuthzRegressionTest.aiCostByClaim_forbiddenForNonAdmin_allowedForAdmin` |

## Tier 2 — Insecure defaults / footguns (BLOCKER; fix before public)

| ID | Sev | Title | Location | Status | Fix | Guard |
|----|-----|-------|----------|--------|-----|-------|
| VCP-DFLT-01 | HIGH(self-host) | Fail-open `dev-mode` default + unverified-JWT decode | `application.yml:38`, `FirebaseAuthService.java:25,36-39,51-84`, `application-local.yml:12` | **fixed** | base default → `false` (`application.yml`, `FirebaseAuthService`, `FirebaseConfig`); deleted `extractEmailFromToken`/unsigned-token fallback; boot guard in `FirebaseConfig.init` rejects dev-mode under `cloud` | `FirebaseAuthServiceFailClosedTest` (2 cases) + `FirebaseConfig` boot guard |
| VCP-DFLT-02 | LOW | Auth-bypassing `/dev/*` route gallery ships in prod web build | `flutter_frontend/lib/main.dart:135-136,386-413` | **fixed — surface since removed** | gated behind `!kReleaseMode \|\| ENABLE_DEV_ROUTES`; e2e README updated to pass the define | `flutter analyze` clean; e2e suite serves with `--dart-define=ENABLE_DEV_ROUTES=true` (flutter frontend removed 2026-08-02) |
| VCP-DFLT-03 | LOW | `/h2-console` in public auth-skip list | `SecurityConfig.java:66-67` | **fixed** | `/h2-console` exemption now gated behind `devMode` in `authFilter` | covered by existing suite (local profile) |

## Tier 3 — Governance / CI supply-chain / hardening (land with or just after publish)

| ID | Sev | Title | Location | Status | Fix | Guard |
|----|-----|-------|----------|--------|-----|-------|
| VCP-HARD-01 | LOW | CI hardening: `permissions:`, SHA-pin actions, Dependabot, CodeQL, secret push-protection | `.github/workflows/gitleaks.yml`, `.github/dependabot.yml` (new), `.github/workflows/codeql.yml` (new) | **fixed** (repo-settings part open) | gitleaks `permissions: contents: read` + SHA-pinned actions; added dependabot.yml (gradle/pub/npm/actions) + codeql.yml (java+js) | YAML validated; **you must still** enable native Secret Scanning + Push Protection + Dependabot alerts in repo Settings |
| VCP-HARD-02 | LOW | Enforce branch protection on `main` (currently checklist-only) | repo settings, `.github/SECURITY-OPS.md:28-30` | open | _(repo setting — your action)_ | require PR + gitleaks/codeql checks, no force-push |
| VCP-HARD-03 | LOW | Governance: CODEOWNERS, CoC, DCO; remove internal issue templates | `.github/CODEOWNERS` (new), `CODE_OF_CONDUCT.md` (new), `CONTRIBUTING.md`, templates | **fixed** | added CODEOWNERS + CODE_OF_CONDUCT (Covenant 2.1 by ref) + DCO/license note in CONTRIBUTING; relocated `opportunity.yml`+`feedback.md` to `.internal/`; reworded `feature.yml` | n/a (config/docs) |
| VCP-HARD-04 | LOW | Commit Firebase rules + implement `X-Firebase-AppCheck` | `firebase.json`/`firestore.rules`/`storage.rules` (new), `SecurityConfig.java`, `SECURITY-OPS.md:24-26` | **partial** | default-deny `firestore.rules`+`storage.rules`+`firebase.json` committed (client uses only firebase_core+auth, so safe) | App Check **enforcement** deferred — needs GCP-side App Check setup first or it breaks prod |
| VCP-HARD-05 | LOW | Security headers / CSP | `web/next.config.ts` (was `flutter_frontend/nginx.conf.template`) | **FIXED 2026-08-12 on the Next BFF** — the Flutter surface was deleted and the replacement shipped with NO headers at all; restored + CSP now ENFORCING | `server_tokens off` + X-Frame-Options/X-Content-Type-Options/Referrer-Policy/HSTS on doc + asset locations | CSP shipped **commented** (Flutter-tuned) — enable after testing against the `--wasm` build in a browser (flutter frontend removed 2026-08-02) |
| VCP-HARD-06 | LOW | Rate limiting + stop logging veteran filenames at INFO | backend (bucket4j), `EventPipelineController.java:74`, extraction services | **partial** | filename logging removed from 7 INFO sites (ids/counts only); 2026-09-16: the 5 remaining ERROR-level parse-failure logs in the extraction services no longer print the filename either | rate limiting (bucket4j) **deferred** — bigger lift; usage cap partially backstops cost |

## Tier 4 — Pre-publish operational checklist (mostly non-code; verify before flipping public)

| ID | Title | Owner | Status | Notes |
|----|-------|-------|--------|-------|
| VCP-OPS-01 | Delete 12 pushed `origin/claude/*` branches; prune local | maintainer | open | merged/behind main |
| VCP-OPS-02 | Do NOT push 5 local backup tags; confirm origin has 0 tags | maintainer | open | pin pre-scrub commits |
| VCP-OPS-03 | Verify GCP controls (API-key referrer, Firebase rules, App Check, Cloud Run max-instances, billing alerts, SA IAM, bucket private) | maintainer | open | per SECURITY-OPS.md pre-publish list |
| VCP-OPS-04 | Move `tools/session-start.sh` to `.internal/`; trim `regression/MANIFEST.md` internal paths; reword templates | maintainer | **fixed** | scrubbed incident dates + `~/VAClaimPath` from `session-start.sh` (kept it working); trimmed both `.internal/plans/...` refs in MANIFEST.md; templates reworded/relocated |
| VCP-OPS-05 | Security reports route through GitHub Security Advisories; no contact mailbox is published in the repo | maintainer | **fixed 2026-09-17** | SECURITY.md + CODE_OF_CONDUCT.md point at the advisory form |
| VCP-OPS-06 | Maintainer identity decision (gmail in 190/224 commits + LICENSE); MFA on infra Google account; rename mock persona | maintainer | open | optional: rotate DB pw / scrub `craftloop-admin.json` comment |

## Ruled out (considered, not blocking — see report §"Verified clean / ruled out")
Injected Express/React/Terraform catalog (wrong codebase) · secret-rotation-as-emergency (never in
this repo's history) · CORS `*` (Bearer not cookie) · missing backend security headers · Docker-as-root /
no `.dockerignore` (Cloud Run gVisor; `.gcloudignore` governs deploy) · dependency/Gradle-wrapper checksum
pinning · no access-audit table · no app-layer/CMEK encryption · `/Users/<owner>/` path in old history.
