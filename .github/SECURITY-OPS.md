# Operational security checklist

This is the operator-facing checklist that protects the *running* service.
The public repo's job is to not leak credentials — this checklist is what
prevents a clean codebase from being undermined by misconfigured infra.

> This file ships in the public repo intentionally. None of these items
> require secrets to follow.

## Pre-publish (do once before flipping the repo public)

- [ ] **Restrict the Firebase Web API key in GCP Console.** Even though
      the key is meant to be public, restricting it to your domains
      (`app.afterduty.app/*`, `afterduty.app/*`, and the Capacitor WebView origins `https://localhost/*` + `localhost:*`) makes it useless for
      third parties trying to abuse the Identity Toolkit free tier on
      your project's quota.
      Console → APIs & Services → Credentials → the Web API key →
      Application restrictions: HTTP referrers.

- [ ] **Lock Firebase Security Rules.** Storage and (if used) Firestore
      rules must deny by default. Test with the Firebase Local Emulator
      Suite before pushing.

- [ ] **Enable Firebase App Check** with reCAPTCHA Enterprise on the
      web app. Then enforce it on the Cloud Run backend by validating
      the `X-Firebase-AppCheck` header in `SecurityConfig`.

- [ ] **Branch protection on `main`.** Require PR review, status
      checks (gitleaks + tests), and disallow force-pushes.
      Settings → Branches → main → Add rule.

- [ ] **Set Cloud Run max-instances** on both `va-claim-api` and
      `va-claim-web-next` to bound DoS-driven cost. Default is unlimited.

- [ ] **Billing alerts** on the GCP project for daily budget thresholds
      — catches Anthropic / Vertex abuse early.

- [ ] **Rotate the existing Firebase Web API key** if you suspect it
      ever escaped the prior repo's public window. Get a new key,
      redeploy frontend, then delete the old one. Cheap insurance.

- [ ] **Audit Cloud Run service-account IAM** — the runtime SA
      (`<project-number>-compute@…`) should only have the roles it
      actually needs: `roles/cloudsql.client`,
      `roles/secretmanager.secretAccessor`,
      `roles/storage.objectViewer` on your bucket, plus whatever
      Vertex/Gemini scopes you use. Drop anything broader.

## Ongoing (per-PR / per-deploy)

- [ ] `gitleaks` Action runs on every PR (see
      `.github/workflows/gitleaks.yml`).
- [ ] Pre-deploy regression sweep:
      `./gradlew test -PregressionOnly` (backend) and
      `cd web && npm test` (frontend).
- [ ] Watch the Stripe webhook event log for `*.signature_invalid` —
      indicates someone is probing the webhook endpoint.
- [ ] Watch Cloud Run logs for `admin_only` and `Not authenticated`
      403/401 spikes — indicates endpoint enumeration.

## Incident response

If a secret leaks (or you suspect one has):

1. **Rotate the secret immediately** — Stripe key, Anthropic key,
   Firebase key — at the provider, before doing anything else.
2. Push a redeploy with the rotated secret.
3. File a SECURITY.md disclosure issue if external researchers were
   involved.
4. Audit logs for the leaked-secret window to assess use.
5. Notify affected users only if PII was demonstrably accessed.

## Things the public repo intentionally exposes

These are by design and not a security issue:

- Architecture, frameworks, dependencies.
- API endpoint URLs and request/response shapes.
- JPA entity definitions / database schema.
- Stripe pricing structure and the public domains.
- Public Firebase config (web API key, project id) **in the deployed
  bundle** — not the repo, but anyone visiting the live site can read
  it. Defense is API key restrictions + Firebase rules + App Check,
  not key secrecy.
