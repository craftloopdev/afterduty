# Re-auditing `va-claim-path-app`

The pre-public review (2026-06-01) was produced by a read-only, multi-agent audit across 8 dimensions
(secrets/history, backend authz, backend injection/data, frontend, CI/infra supply-chain,
PII/compliance, dependencies/build, governance), with every finding adversarially re-verified against
the source. Re-run it before each public release and whenever the auth/authorization or deploy
surface changes.

## When to re-audit
- Before flipping the repo public (the first time).
- Before any release that touches controllers, `SecurityConfig`/`FirebaseAuthService`/`AdminCheck`,
  Spring profiles, `.github/workflows/*`, Dockerfiles, or Firebase rules.
- Periodically (e.g. quarterly) as a drift check.

## How to re-run

**Continuous guardrails (run every time, fast):**
```bash
# Backend authz/regression suite (bootstraps Gradle on JDK 21, auto-provisions JDK 25)
cd spring-backend && JAVA_HOME=/opt/homebrew/opt/openjdk@21 ./gradlew test -PregressionOnly
# Frontend regression suite
cd web && npm test && npm run test:e2e
# Secret scan over tree + history
gitleaks detect --config .gitleaks.toml
```

**Full multi-agent re-audit (deep, on demand):**
In a Claude Code session in this repo, re-run the saved audit workflow:
```
Workflow({ scriptPath: "<session>/workflows/scripts/va-claim-path-security-audit-wf_5f412ef5-a1f.js" })
```
(The script is read-only: 8 finder agents + per-finding adversarial verification. If the saved path
is gone, regenerate an equivalent from the dimension list above.) Then reconcile its `confirmed`
output against [remediation-checklist.md](./remediation-checklist.md):
- Every previously-`fixed` ID must **not** reappear as confirmed — if it does, the guard regressed.
- New confirmed findings get the next free `VCP-<CATEGORY>-NN` ID, a checklist row, and a report
  section.

## Reconciliation rules
- A finding may move to `fixed` only when it has both a code fix **and** a guard (regression test or
  scanner) recorded in the checklist.
- `accepted`/`wontfix` require a one-line rationale in the report's §5.
- Update "Last reconciled" in the checklist after each pass.
