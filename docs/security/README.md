# Security

This directory is the living record of `va-claim-path-app`'s security posture. It exists so security
findings are tracked with **stable IDs**, fixed deliberately, and **proven to stay fixed** by
regression tests + CI + periodic re-audits.

## Contents

| File | Purpose |
|------|---------|
| [security-review-2026-06-01.md](./security-review-2026-06-01.md) | Latest full review: findings (`VCP-*` IDs), evidence, fixes, what was ruled out, and the pre-public gate. |
| [remediation-checklist.md](./remediation-checklist.md) | **Single source of truth** for remediation status — one row per finding ID (`open`/`in-progress`/`fixed`/`accepted`/`wontfix`) with the guarding test. Future sessions reconcile here. |
| [audit.md](./audit.md) | How to re-run the audit before each release and reconcile new findings. |

## Reporting a vulnerability
See the repository [`SECURITY.md`](../../SECURITY.md) for the private disclosure process. Because this
app handles veterans' health-related data, please report privately and do not open a public issue.

## Finding ID scheme
`VCP-<CATEGORY>-NN`: `AUTHZ` (access control), `DFLT` (insecure defaults), `HARD` (hardening / CI /
governance), `OPS` (operational pre-publish). IDs are stable and never reused; new findings take the
next free number in their category.

## For self-hosters
Read the review's §3 (what's safe) and the **VCP-DFLT-\*** findings before deploying. In particular:
never run a non-`local` deployment with `DEV_MODE=true`, lock down your Firebase Security Rules, and
restrict your Firebase API key — see [`.github/SECURITY-OPS.md`](../../.github/SECURITY-OPS.md).
