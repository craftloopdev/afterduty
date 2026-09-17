# Dead-code audit — 2026-08-02

Repo: `/Users/rusticus/Developer/va-claim-path-app` · branch `feat/next-web-foundation`
Scope: flutter cutover residue, backend dead beans/endpoints, web dead code/deps, root files, docs, repo hygiene.
**Report-only — nothing has been deleted.**

> **Execution record (2026-08-02):** Tier 1 + the flutter removal were executed
> the same day (commits `a946e67`…, prep → flutter removal → backend → web →
> wiring fixes). Owner decisions: AI-cost read endpoints KEPT, admin /debug/*
> endpoints KEPT, POST /auth/sign-out KEPT and wired into web + native
> sign-out, share PATCH + /shares/profiles and DeviceCredential list/revoke
> deferred (forward-looking surfaces). @capacitor/preferences removal waits for
> the iOS build #64 review to conclude. The StepUpModal wiring bug (Tier 3 #7)
> was fixed with a lazy self-mount + regression test.

Headline numbers: the retired Flutter app (223 tracked files, ~31,000 LOC, 2.2 GB on disk of which ~1.0 GB is untracked build output) plus ~1,350 LOC of orphaned backend controllers/beans and web components are provably dead. Only 2 items lack enough evidence to decide.

---

## Tier 1 — safe to remove now (proven dead)

Every entry below has zero live callers, zero CI/deploy references, and a superseded-by story. Recoverable from git history.

| # | Path | Evidence | Size |
|---|------|----------|------|
| 1 | `flutter_frontend/build/` | Untracked local Flutter build output (0 files in `git ls-files`). The app it builds can no longer deploy anywhere (Cloud Run service `va-claim-web` deleted). | **~1.0 GB** disk, 0 tracked files |
| 2 | Root-level local debris: `profile-mobile.png`, `.env`, `.DS_Store`, `.firecrawl/` | `profile-mobile.png` is the repo's only untracked-and-unignored file (79 KB screenshot, zero references — the one true `git add -A` hazard). `.env`'s sole consumer was `flutter_frontend/deploy.sh` (dead path; no other reader found across scripts/tools/tests/web/backend). `.firecrawl` is an empty dir. `.DS_Store` is Finder junk, already ignored. | ~80 KB, 4 items, all untracked |
| 3 | `spring-backend/src/main/java/com/vaclaimpath/service/gap/EnhancedGapAnalysisOrchestrator.java` | Empty `@Service` shell (constructor + logger only); its own javadoc defers to `GapStateMachine`. Zero references in src/main, src/test, or by bean name repo-wide. | 18 LOC, 1 file |
| 4 | `spring-backend/src/main/java/com/vaclaimpath/service/extraction/ExtractionOrchestrator.java` | `@Service` whose only two methods are **private** — no callable surface even if injected. Zero references anywhere; javadoc names successor `ExtractionStateMachine` (live). | 73 LOC, 1 file |
| 5 | `spring-backend/src/main/java/com/vaclaimpath/service/AtomCitationHelper.java` + `src/test/.../AtomCitationHelperTest.java` | Only consumer is its own unit test. Added in sharing Phase D, never wired — no controller/DTO renders the attribution string. **Delete the test with it or the build breaks.** Minor product note: if the VSO atom-attribution label ships later, re-wire from history. | 134 LOC, 2 files |
| 6 | `spring-backend/src/main/resources/application.yml` — key `va-claim.claude.model` / env `CLAUDE_MODEL` | Zero readers in main or test (no `@Value`, no `getProperty` anywhere). yml comment self-admits "no consumer reads". Superseded by `va-claim.llm.purposes.*.model` + `va-claim.vertex.anthropic.default-model` (both read). **Keep siblings** `claude.api-key/base-url/anthropic-version` — they back the anthropic-batch rollback lane. | ~3 lines |
| 7 | Orphan debug controllers (whole files): `EventPipelineController.java` (307), `SynthesisDebugController.java` (95), `StrategyDebugController.java` (160), `GapAnalysisDebugController.java` (77) | Zero callers anywhere — not even Flutter. Only references are legacy-baseline docs (`docs/architecture/baseline/legacy-*.md`), explicitly superseded by the 2026-06 agentic redesign (089c100). Admin-gated, so no urgency, pure dead weight. **Controller files only** — injected state machines (`SynthesisStateMachine`, `GapStateMachine`, etc.) are shared with the live pipeline; do not touch. | 639 LOC, 4 files |
| 8 | `IntakeController.java` — 3 Flutter-only endpoints: `GET /api/claim/status` (:862), `POST /claim/evidence/{id}/reprocess` (:709), JSON-consumes overload of `POST /claim/evidence` (:192) | Sole callers were `flutter_frontend/lib/services/api_service.dart` (+ build artifacts). Zero hits in web/src, tests/, scripts/. Web polls `GET /claim/jobs` for status and uploads via the **multipart** overload at :106 (live — keep it), reprocessing goes through the synthesisNeeded re-run trigger. | ~110 LOC est., 3 methods |
| 9 | `ConditionController.java` — saved-scenario CRUD (`POST/GET /api/scenarios`, `PUT/GET/DELETE /api/scenarios/{id}`, lines 112–308) + `GET /api/vasrd/codes/{code}` (:103) | Scenario CRUD called only from flutter `api_service.dart:442-488`; live web ScenariosPanel computes client-side and calls **only** `POST /api/scenarios/calculate` (:317 — **keep**). `/vasrd/codes/{code}` has zero consumers anywhere (sibling `/vasrd/search` is regression-suite-live — keep). Follow-up: check `ScenarioRepository` for orphaned rows. | ~120 LOC est., 6 methods |
| 10 | `MasterlistController.java` (whole controller: `GET /api/masterlist`, `/search`, `/vasrd/{code}`) | Flutter-only callers (vcp_evidence_page, live_conditions_page, data/masterlist.dart). Web quick-add is free-text via `POST /claim/quick-add`. **Only the HTTP surface is dead** — `MasterlistService` + bundled `conditions.json` feed live synthesis/gap analysis and must stay. | 53 LOC, 1 file |
| 11 | `web/src/components/ui/Sheet.tsx` + `Sheet.module.css` | Zero imports across web/src, tests, scripts; no barrel re-export, no dynamic import. Never wired since foundation commit 1028bb2. Also fix the stale "Modal and Sheet" comment in `useFocusTrap.ts`. | 146 LOC, 2 files |
| 12 | `web/src/components/ui/TriadCell.tsx` + `TriadCell.module.css` | Zero references of any kind (not even a test). Superseded by sibling `TriadDots.tsx` (3 live importers). | 43 LOC, 2 files |
| 13 | `web/package.json` → dependency `zod` | Zero hits across all source, tests, scripts, and every config file. Nothing resolves it. Remove + `npm install` to refresh lockfile. | 1 dep |
| 14 | `web/public/{file,globe,next,vercel,window}.svg` | Stock create-next-app boilerplate, zero referrers; currently shipped for no reason inside the native bundle and Cloud Run image. (`privacy.html` is NOT in this list — it is live, an App Store requirement.) | 20 KB, 5 files |

Tier 1 totals: **~1,350 LOC + ~1.0 GB disk + 1 npm dep + 5 assets**.

## Tier 2 — remove after a named check (state the check)

| # | Path | Evidence | Named check(s) | Size |
|---|------|----------|----------------|------|
| 1 | `flutter_frontend/` (entire tracked tree, incl. `tests/e2e/`) | Proven dead on every axis: Cloud Run service `va-claim-web` no longer exists (domains map to va-claim-api / va-claim-web-next); no firebase hosting section; no CI workflow builds/deploys it; every external reference is a comment/doc; backend bundles its own masterlist (the "Flutter" grep hits in conditions.json are the medical condition "atrial flutter"); e2e specs target the Flutter canvas and cannot run against Next.js. | (a) **Relocate branding masters first** (Tier 3 #1). (b) **Edit `.github/dependabot.yml` in the same PR** — drop the two `/flutter_frontend` entries or Dependabot errors on missing dirs. (c) **Disable the dormant Xcode Cloud workflow** on ASC app 6771148030 attached to `flutter_frontend/ios` (hygiene, not a blocker — v2 builds from `web/ios`). (d) **Re-status remediation-checklist rows** VCP-DFLT-02 and VCP-HARD-05 (they cite flutter files) as "fixed, surface since removed". (e) Prune stale textual refs in the same PR: `regression/MANIFEST.md` "Web (Flutter)" sections, README/CONTRIBUTING/SECURITY flutter mentions, PR template + SECURITY-OPS `flutter test` command, `docs/security/audit.md` line 22. | **223 files, ~31,000 LOC, 2.2 GB on disk** (1.2 GB tracked-side after build/ is gone) |
| 2 | `IntakeController.java` — 5 admin debug endpoints: `POST /debug/re-extract/{id}`, `/debug/synthesis`, `/debug/gap-analysis`, `/debug/post-process`, `GET /debug/atoms` (:744–839) | Flutter admin console was the only caller; admin-gated via SecurityConfig `/api/claim/debug/**`. | **Owner confirms** he does not curl these for pipeline debugging (they are the only ad-hoc pipeline-stage triggers left). Low security risk to keep meanwhile. | ~120 LOC est., 5 methods |
| 3 | `ShareController.java:53` — `PATCH /api/shares/{id}` | Flutter-only (permission-toggle from api_service.dart:386). Web share UI has create/list/revoke/accept but no PATCH lane. | **Owner confirms** per-share permission editing (canViewAnalysis/canUploadDocs) is not planned for the web viewer-mode roadmap — removal forecloses it until rebuilt. | ~15 LOC |
| 4 | `ShareController.java:88` — `GET /api/shares/profiles` | Flutter-only caller; web view-as resolves viewable claims from `GET /shares` + `/auth/me` (`web/src/app/api/view-as/route.ts:34`). | **Verify in the running web app** that viewer-selection truly populates from `GET /shares` (the lane found in code) before deleting. | ~15 LOC |
| 5 | `AuthController.java:375` — `POST /api/auth/sign-out` | Flutter-only caller. Web sign-out clears cp_session cookie locally; native calls Firebase signOut only. Endpoint is audit-log-only (EVENT_SIGN_OUT, returns 204). | **Owner decision**: remove, or instead *wire* web/native sign-out to call it — the security-remediation docs care about auth auditing, and today the sign-out audit event is emitted by nothing. | ~14 LOC |
| 6 | `web/package.json` → dependency `@capacitor/preferences` | Zero JS/TS callers anywhere; not in capacitor.config.ts. Podfile/packageClassList entries are auto-generated from package.json. A JS-API plugin with no JS caller does nothing at runtime. | Removal **changes the native binaries**: wait until App Store review of build #64 concludes, then `npm install` + `npm run cap:sync` (+ Android sync) and rebuild both platforms so Podfile/packageClassList drop the pod. | 1 dep + regenerated native manifests |

## Tier 3 — relocate / hygiene (not deletions)

1. **`flutter_frontend/assets/branding/app-icon.svg` + `app-icon.png` → move to `branding/` (or `web/branding/`)** — the SVG is the repo's ONLY vector master of the app icon (web ships rasters only, max 512×512); the PNG is the only 1024×1024 — the exact ASC store-icon size. 300 KB, 2 files. **Precondition for Tier 2 #1.**
2. **`.github/dependabot.yml` — edit, don't delete**: drop the two `/flutter_frontend` ecosystems in the flutter-removal PR, and **add the missing `/web` npm entry** — the live Next.js BFF currently gets no dependency updates at all.
3. **`sendgrid-key` (repo root) — relocate to `~/.gcp/` (chmod 600) or delete**: 70-byte live `SG.` key. Gitignored, but one `git add -f` or careless `cat` from leaking. Secret Manager is the source of truth since 2026-06-20; nothing local consumes it.
4. **`web/README.md` — replace content, keep file**: verbatim create-next-app boilerplate ("deploy on Vercel") contradicting the Cloud Run + Capacitor reality. Stub it to point at `web/AGENTS.md` and the real commands.
5. **`.gitignore` root additions**: `.remember/` (currently protected only by the plugin's own inner `.gitignore` — fragile), `.firecrawl/`, and widen the root screenshot rule (existing `vcp-*.png` missed profile-mobile.png; no root-level PNG is legitimate source).
6. **Fix-in-place cluster (live files describing the dead system)** — do these regardless of deletions:
   - `tests/regression.py:276` and `tests/regression.sh:214` — `va-claim-web` → `va-claim-web-next` (frontend smoke check currently targets the deleted Cloud Run service and misreports).
   - `tests/post-deploy.sh` checks 5–6 — fetch a `/_next/` asset + build-id instead of `/main.dart.js` (verified 404 in prod; script fails as written).
   - `docs/security/audit.md:22` — pre-release frontend guardrail must become `cd web && npm test && npm run test:e2e`; as written a contributor would regression-test the retired app.
   - `docs/security/security-review-2026-06-01.md` — one-line header note that the frontend reviewed there is the retired Flutter app; web/ has had no equivalent full review (the cutover itself meets audit.md's re-audit trigger — action item, not a doc change).
   - `web/src/lib/hooks/useFocusTrap.ts` — stale "shared by Modal and Sheet" comment once Sheet goes.
   - `README.md` rewrite for the web/Next.js + Capacitor era (stack, layout, auth = pure OTP) — required before the public flip; `CONTRIBUTING.md` frontend test commands; `SECURITY.md` scope line.
7. **`web/src/components/auth/StepUpModal.tsx` — not dead code, a wiring bug to fix** (see Keep #3): no production module imports it, so the self-mount never registers and any backend `step_up_required` 403 throws instead of showing the modal. Fix = mount `<StepUpHost/>` or dynamic-import inside `runStepUp`.

## Keep — looked dead, is not (with why)

1. `GeminiServiceHistoryAdjudicator.java` — zero name references, but injected **by interface** (`ServiceHistoryAdjudicationService.java:81`, sole `Adjudicator` bean). Removal breaks context startup or kills live conflict adjudication.
2. `SendGridEmailSender.java` — same false-positive pattern: only `EmailSender` impl, injected into `EmailCodeService`; powers live email-OTP login.
3. `web/src/components/auth/StepUpModal.tsx` — only test importers, but live seams (`mutations.ts`, `direct.ts`, `driver.web.ts`) depend on the self-mount it registers. Fix wiring, do not delete.
4. `@capacitor/keyboard` — zero JS imports, but **config-only** usage: `capacitor.config.ts` `Keyboard: { resize: 'native' }` drives native behavior with no JS call. Removing silently breaks keyboard/viewport in shipped apps — the exact App Store 2.1a failure class.
5. `@capacitor/android` — platform runtime, resolved by gradle, no TS import expected. Android is a live surface.
6. All other web deps (`server-only` is a bare side-effect import — invisible to `from "..."` greps; firebase, react-markdown, remaining Capacitor plugins all have live importers).
7. Dormant feature flags (`per-condition-fingerprint`, `deterministic-rating`, `gap.whatif-enabled`, `free-analysis-tier`) — documented launch gates awaiting owner sign-off, not stuck flags.
8. Legacy 5-pass extraction classes (`DiagnosisExtractorService`, `MedicationExtractorService`, `ServiceRecordExtractorService`, `EventExtractionAgent`, `EventSegmentationAgent`) — the documented no-code-deploy rollback of `va-claim.extraction.single-pass`; still referenced from live code. Revisit only when single-pass is declared permanent.
9. `application.yml` `usage.enabled` — live AI-spend-cap kill switch, genuinely read at `UsageGuard.java:19`.
10. `GET /api/claim`, `POST /api/claim/analyze`, `GET /api/claim/pipeline-metrics` — no frontend caller, but **live tooling callers** in `tests/regression.py` / `test_upload_flow.py` (the prod-verification suite).
11. `GET /api/vasrd/search` — exercised by regression.py/.sh.
12. `SubscriptionController` `/webhook`, `/revenuecat/webhook`, `/admin/grant/{userId}` — server-to-server payment infra + admin ops escape hatch; frontend-callerless **by design**.
13. `HealthController` — Cloud Run liveness + post-deploy smoke target.
14. `firebase.json` + `firestore.rules` + `storage.rules` — VCP-HARD-04 deliberate default-deny hardening; the *absence* of Firestore/Storage usage is the argument FOR them.
15. `tools/session-start.sh` — nothing invokes it, but VCP-OPS-04 explicitly resolved to scrub-and-retain; working operator tooling for exactly the drift the repo has now.
16. `scripts/parse_masterlist.py` — regenerates the live backend's bundled `conditions.json` (loaded at startup by MasterlistService; Javadoc cites the script by path).
17. `regression/MANIFEST.md` — referenced from `build.gradle.kts:79` and the pre-prod process; needs Flutter-section pruning, not removal.
18. `tests/` (all files) — mint_token.py/regression/upload-flow are today's prod-verification backbone; only post-deploy.sh needs the Tier 3 fix.
19. `.github/` (all 8 files) + `.gitleaks.toml` — active CI (gitleaks on every PR), public-flip-gated CodeQL, CODEOWNERS/templates = VCP-HARD-03; toml removal would re-flag allowlisted fake tokens and break PR checks.
20. `README.md`, `CONTRIBUTING.md`, `CODE_OF_CONDUCT.md`, `SECURITY.md`, `LICENSE` — governance/legal deliverables (VCP-HARD-03, disclosure path, Apache-2.0); content fixes only.
21. `docs/` wholesale (security SoT + VCP-* anchor, dated architecture decisions, live QA baselines/eval runs, live App Store & Play Store listing records) — nothing presents a removed system as current except the two fix-in-place items above.
22. Ignored local state (`.remember/`, `.claude/`, `.internal/`, `.shell/`, `.tdd/`, `.playwright-mcp/`, `OPEN_SOURCE_TODO.md`) and web build outputs (`web/out/`, `web/.next/`, iOS/Android sync output) — all correctly ignored, 0 tracked files; nothing to do beyond the `.gitignore` hardening in Tier 3 #5.

## Unresolved (what evidence is missing)

1. **`AiCostController.java` — `GET /api/ai-costs/summary`, `/by-user`, `/claim/{claimId}` (~180 LOC est.)** — Only caller was the Flutter admin dashboard, BUT `AiCostService` was modified in the still-uncommitted Vertex cutover work and AI-spend monitoring is an active operational concern post-cutover. **Missing evidence: whether the owner curls these (or has ops scripts outside the repo) for cost monitoring.** The cost-*recording* service is live regardless and must stay; only the read endpoints are in question. Ask owner.
2. **`DeviceCredentialController.java:121,140` — `GET /api/auth/device/credentials`, `DELETE .../{id}` (~40 LOC)** — Orphan today (native driver uses only `/enroll` + `/exchange`), but it belongs to the *active* 2026-07 biometric login and reads as a not-yet-built "manage my devices" security screen — forward-looking surface with **no superseded-by story**. Missing evidence: product decision on whether device revocation ships in the profile/security page (newly unblocked by the HIPAA-gap resolution). Recommend leaving until decided.

Cross-check notes: the beDead raw sweep initially flagged `GeminiServiceHistoryAdjudicator` and `SendGridEmailSender` as dead — resolved as interface-injection false positives (Keep #1–2). `Sheet.tsx` and `StepUpModal.tsx` both show zero non-test importers yet get opposite verdicts — resolved by runtime-dependency analysis (StepUpModal registers a self-mount live code requires). No area's evidence contradicts the flutter_frontend removal; the docs-audit dependency (remediation-checklist rows citing flutter files) is handled as a same-PR re-status, not a blocker.

## Suggested removal sequence

- **Step 0 — local only, no commit (today):** delete `flutter_frontend/build/` (~1.0 GB back), `profile-mobile.png`, `.env`, `.DS_Store`, `rmdir .firecrawl`; move `sendgrid-key` to `~/.gcp/` chmod 600.
- **Commit 1 — prep (small PR):** `git mv` branding pair to `branding/`; `.gitignore` additions (`.remember/`, `.firecrawl/`, widen root PNG rule); stub `web/README.md`.
- **Commit 2 — the big one:** `git rm -r flutter_frontend/`; dependabot.yml (drop 2 flutter entries, **add `/web` npm**); fix `tests/regression.py`/`regression.sh` service name and `post-deploy.sh` checks 5–6; re-status VCP-DFLT-02/VCP-HARD-05; prune MANIFEST.md Flutter sections; fix audit.md:22, security-review header note, README/CONTRIBUTING/SECURITY/PR-template/SECURITY-OPS flutter text. *External to repo:* disable the dormant flutter Xcode Cloud workflow in ASC.
- **Commit 3 — backend Tier 1:** dead beans (#3–5), yml key (#6), orphan debug controllers (#7), Flutter-only endpoints (#8–10, keeping multipart upload, `/scenarios/calculate`, `/vasrd/search`). Gate on `gradlew test -PregressionOnly` + `tests/regression.py` against staging.
- **Commit 4 — web Tier 1:** Sheet, TriadCell, zod, boilerplate SVGs, useFocusTrap comment. Separate bugfix commit: mount StepUpHost (Tier 3 #7).
- **Commit 5 — after checks clear:** Tier 2 #2–5 endpoint removals per owner answers; `@capacitor/preferences` + cap sync + native rebuilds once build #64's review concludes; revisit the two Unresolved items with the owner.
