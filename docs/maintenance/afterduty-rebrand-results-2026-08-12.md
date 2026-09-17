# After Duty rebrand — results

**Work period:** 2026-08-09 → 2026-08-12 · **Record written:** 2026-08-17

**Decision (2026-08-09, owner):** "VA Claim Path" / vaclaimpath.com read as VA-affiliated and risked
misrepresenting the agency. Everything rebrands to **After Duty** on **afterduty.app**. afterduty.com
(~$3k) only if there's traction — so keep domain references centralized, because a second hop is
anticipated and will re-break passkeys.

**Status:** Stage 0 + Stage 1 **SHIPPED** (PR #118, 2026-08-11) plus a completion pass 2026-08-12.
**Stages 2 (mobile apps) and 3 (artwork) are DEFERRED indefinitely** by owner decision 2026-08-12.

- Spec: `docs/superpowers/specs/2026-08-10-afterduty-rebrand-design.md`
- Plan: `docs/superpowers/plans/2026-08-10-afterduty-stage1-web-backend.md`

---

## What is live

| Surface | State |
|---|---|
| `afterduty.app` + `www` | Marketing/legal site — Cloudflare Pages, served from the `VAClaimPath` repo |
| `app.afterduty.app` | Web app — Cloud Run `va-claim-web-next` |
| `api.afterduty.app` | API — Cloud Run `va-claim-api` |
| `vaclaimpath.com` + `www` | 301 → afterduty.app, path + query preserved (rule `rebrand-301-vaclaimpath-to-afterduty`) |
| `app.vaclaimpath.com` | 301 → app.afterduty.app (rule `rebrand-301-app-host`, record proxied) |
| `api.vaclaimpath.com` | **Dual-serves. NEVER redirect it** — shipped binaries still call it |

Verified 2026-08-17: all six behave as above. Live revisions `va-claim-api-00165-5wz` and
`va-claim-web-next-00053-5tk`.

**Email.** SPF + DKIM (2048-bit, selector `google`; SendGrid `s1`/`s2`) + DMARC `p=none` on both domains.
Production OTP verified end to end: From `noreply@afterduty.app`, subject "Your After Duty sign-in code",
SPF / DKIM (`d=afterduty.app`) / DMARC all PASS, ~1s delivery. Added the RFC 7489 §7.1 cross-domain report
authorization record `afterduty.app._report._dmarc.craftloop.dev` — without it afterduty.app's aggregate
reports were being silently refused by the destination domain.

**Identity and money.** Firebase + GCP project `displayName` → "After Duty" (this is the string the phone-OTP
SMS interpolates via `%APP_NAME%`). WebAuthn `rpId` → `afterduty.app`. Three Stripe products renamed in
place. The webhook needed no cutover — it targets a domain-independent `run.app` URL. The statement
descriptor stays account-level `CRAFTLOOP` because that Stripe account is shared with ViziTales and
VolunTails; changing it would mislabel sibling products.

---

## Security fixes made during the completion pass (VCP-HARD-05)

**`app.afterduty.app` was serving zero security headers.** They had lived in the Flutter frontend's nginx
config; that surface was deleted with the Flutter app and the Next BFF that replaced it shipped with none.
The product handling veterans' medical records was less protected than the static marketing site.

`web/next.config.ts` now sets, on the standalone/web target only (the native build is a static export with
no server): HSTS `max-age=63072000; includeSubDomains` — deliberately **no `preload`**, since enrolling is
effectively irreversible and the domain may still move to .com — plus nosniff, `X-Frame-Options: DENY`,
`Referrer-Policy`, `Permissions-Policy`, and an **enforcing CSP**.

The CSP shipped Report-Only first and was verified by exercising the real deployed app with the console
open: login, email OTP request → verify → session, home, conditions, documents, Ask AI, and — the path most
likely to break — phone OTP, which falls back to reCAPTCHA v2 and renders its iframe. Zero violations, then
switched to enforcing.

> Caveat worth carrying forward: `'unsafe-inline'` in `script-src` is forced by Next's hydration bootstrap
> and substantially weakens the XSS protection a CSP would otherwise buy. The directives doing real work
> here are `object-src 'none'`, `base-uri 'self'`, `form-action 'self'` and `frame-ancestors 'none'`.
> Nonce-based scripts are the next hardening step.

The API had no headers either — there is no Spring Security dependency, so none of its default header
writers run. Added a `OncePerRequestFilter` in `SecurityConfig` (nosniff, DENY, `no-referrer`, HSTS; no CSP,
which belongs on the HTML-serving app).

`.github/CODEOWNERS` referenced `com/vaclaimpath/**`. The package rename used dot-separated `sed`, so the
slash paths silently stopped matching and `config/`, `security/` and `controller/` lost their review owner.
Fixed.

Branch `claude/security-remediation` is **stale** — its content is already in main via PR #43. Do not
re-merge it.

---

## Known gap — needs owner action in the Admin console

**`support@` / `privacy@` / `security@` / `conduct@` @afterduty.app hard-bounce.**

afterduty.app is a Workspace *user alias domain*, so only local parts that exist on the mailbox resolve:
`good@` and `<owner>@`. An SMTP RCPT probe returns 250 for those two and 550 for the four published contacts.

This is a **regression, not a pre-existing gap**: vaclaimpath.com is a catch-all — any local part returns
250 — so those addresses used to accept mail. The login-lockout screen, the paywall and the privacy policy
now point veterans at addresses that bounce.

Fix (~2 minutes): Admin console → Directory → Users → the owner → Add alternate emails, or create Groups.
Then delete the entries from `KNOWN_DEFERRED` in `tests/check_published_emails.py`, and flip the marketing
site's `veterans@vaclaimpath.com` — deliberately left alone for now precisely because it still delivers
while the @afterduty.app version would not.

**Guard added.** `tests/check_published_emails.py`, wired into `tests/post-deploy.sh`, collects every
address published in shipped copy and RCPT-probes it against the domain's MX (RCPT only — no DATA, so no
mail is sent). Each domain also gets a random-local-part negative control, so a catch-all cannot make the
check pass vacuously. Unexpected breakage fails the gate; the four known gaps print `KNOWN-DEFERRED:` on
every run so they cannot quietly become permanent; and a mailbox that starts working prints `RESOLVED:`
telling the next person to drop it from the list.

Nothing caught the original regression because the strings were internally consistent, the tests passed and
the deploy was green. Consistency checks can't tell you an address is real — only the receiving mail server
can.

**Also owner-only:** GCP Console → APIs & Services → OAuth consent screen → App name still reads
"VA Claim Path". Plus the standing security checklist: branch protection on main, Firebase Web API key
restriction, Firebase App Check, Cloud Run max-instances, billing alerts, Cloud Run SA IAM audit.

---

## Deliberately unchanged — do not flag these as rebrand gaps

Stages 2 and 3 are parked, so every native identifier stays as-is **intentionally**: bundle/package id
`com.vaclaimpath.app`, the `vaclaimpath://` scheme, universal-link host `app.vaclaimpath.com`, keychain
`com.vaclaimpath.app.deviceLogin`, the AASA appID, iOS listing 6771148030, the Play draft, RevenueCat, and
all icons and screenshots. Interim identity is the shield mark plus an "After Duty" wordmark.

Infrastructure names are internal and were left alone on purpose: GCP project `craftloop-va-claim`, the
Cloud Run service names, the GCS bucket, Cloud SQL names, the `cp_session` cookie, `vcp.*` keys, and the
GitHub repo slug. Dated historical docs keep their original wording as a record.

---

## Deferred ledger

Stage 2 (mobile apps as `com.afterduty.app` on both platforms, new listings, Firebase + RevenueCat
re-registration) · Stage 3 (artwork, owner-supplied) · the four @afterduty.app mailboxes · the GCP OAuth
consent-screen name · DMARC `p=none` → `quarantine` · Gmail send-as for @afterduty.app · nonce-based CSP
scripts · `cp_session` rename · Cloud Run service names · GitHub repo slug · purchasing afterduty.com.

---

## How Stage 1 completeness was established

A 51-agent audit with adversarial verification produced 21 confirmed findings, all closed. The ones worth
remembering because they were invisible from the code alone:

- `application.yml`, `AuthProperties`, `SendGridEmailSender` and `StripeService` still defaulted to the
  retired domain. Latent, not live — production env vars overrode them — which is exactly why no test and
  no smoke check saw it. A fresh environment would have come up branded wrong.
- The Stripe billing-portal return URL was a dead Flutter hash route (`#/upgrade`). Now the canonical
  `/upgrade?checkout=…` route.
- The DMARC reporting path for afterduty.app had been silently dead since day one (see RFC 7489 above).

Two audit findings were **false positives** killed by verification, recorded here so nobody re-opens them:
"the marketing site publishes no contact address" (it does — Cloudflare's `data-cfemail` obfuscation defeats
a plain grep of the served HTML) and "Firebase Auth email custom domain still vaclaimpath.com" (the config
returns null).

One recommendation was **declined on judgment**: branding the Stripe billing portal "After Duty". There is
exactly one shared portal configuration serving ViziTales and VolunTails too, so branding it would mislabel
those products. Recorded as pre-existing, not a rebrand regression.
