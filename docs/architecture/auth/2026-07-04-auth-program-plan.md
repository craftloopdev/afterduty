# Auth Program Plan — 2026-07-04

**Status:** Executable plan (synthesis of the three specs in this directory + live-verified research, 2026-07-04)
**Inputs:** `auth-security-flows.md`, `idme-signin-spec.md`, `lighthouse-import-feature-request.md`,
`../passwordless-otp-auth-spec.md`, `../capacitor-ios-spec.md` §B/§H.2, and the shipped code cited below.
**Tracking:** tasks #148 (P1), #149 (P2), #150 (P3).

All `[VERIFY]` items in the three specs were checked against live documentation on 2026-07-04.
Everything resolvable without an account is resolved inline below (with sources). Everything that
requires a registration or a human at ID.me/VA is an **owner action** (§4), not a guess.

## Owner ratifications — 2026-07-04
- **Model B ratified** as the MFA default (`va-claim.auth.mfa-model=ENROLLMENT_DEVICE_TRUST`) — build Phase 1 to it (D1).
- **Phase 1 approved to start immediately** (no external deps).
- **Redirect URI = option B:** the Lighthouse OAuth callback is served by the **existing prod
  backend** at `https://api.vaclaimpath.com/api/va/oauth/callback` with an `?env=sandbox` query
  flag routing to `sandbox-api.va.gov` vs prod — NOT a new `api-dev` service. The owner
  re-registers this exact URI at the Patient-Health sandbox signup (the current registration
  points at the non-existent `api-dev.vaclaimpath.com`).
- **VA production application will EXCLUDE Benefits Claims** (`claim.read`) to shorten the PHI
  review — prod scope = **Patient Health API + Veteran Service History** only. Benefits Claims
  and Benefits Intake stay sandbox-only (Benefits Intake is document *submission*, a separate
  future capability). Phase 3 §3 scope list below is trimmed accordingly for the prod ask.
- **Sandbox creds captured** (owner-authorized chat values; owner rotates later): stashed at
  `~/.gcp/va-sandbox-creds.env` (chmod 600, outside repo); load into Secret Manager
  (`va-lighthouse-sandbox-*`, `va-benefits-intake-sandbox-api-key`) at Phase-3 wiring / next
  `good@` reauth. Owner still to: change the portal password, rotate the OAuth secret + API key.

---

## 1. What's already done — spec requirements → shipped code

The specs were written before the OTP cutover shipped. A large slice of `auth-security-flows.md`
is **already live** (api rev 00127-8jh, web rev 00016-c5m, both channels confirmed 2026-06-20):

| Spec requirement | Status | Where |
|---|---|---|
| §1 "Passwordless, no passwords ever" | **DONE** | Entire login surface: `web/src/app/(auth)/login/page.tsx` — single-box phone/email OTP, no Google/Apple/password/magic-link on either target |
| §2.1/§3.2-3.3 factor-1 OTP (SMS via Firebase, email via custom service) | **DONE** | Phone: Firebase Phone Auth (web invisible reCAPTCHA / native silent-APNs, `web/src/lib/auth/driver.web.ts`, `driver.native.ts`). Email: the spec said "custom: Cloud Function + SendGrid `[VERIFY]`" — this was built as a **Spring service, stronger than spec'd**: `spring-backend/.../service/EmailCodeService.java` (SHA-256-hashed codes, constant-time compare, 10-min TTL, 5 attempts, single live code, per-email 5/hr + per-IP 20/hr DB-backed caps, pg advisory locks, anti-enumeration, purpose-separated signin/attach lanes) + `EmailCodeAuthController.java` + `FirebaseCustomTokenService.java` (mints custom tokens only for the just-verified email's uid). §7 checklist item 1 ✓ |
| §5-adjacent dual-channel binding | **DONE** | Dual-verify onboarding: brand-new users must verify BOTH channels (login page `dualCollect`/`dualCode` states; `/attach` endpoint with hijack guard + TOCTOU re-check; `linkWithPhoneNumber`). Every new account has two independently verified possession channels — this is exactly the machinery §5 recovery needs (see D4) |
| §6 short-lived tokens, session model | **DONE (factor-1 grade)** | Web: httpOnly `cp_session` cookie, 55-min maxAge, rotated on `onIdTokenChanged` via `POST /api/session` (`web/src/app/api/session/route.ts`); native: iOS-keychain ID tokens via the Capacitor Firebase plugin, ~1 h Firebase expiry, auto-refresh (`capacitor-ios-spec` §B.3/§B.6). Sign-out clears both |
| §4 step-up (adjacent piece) | **PARTIAL — the client seam exists** | `web/src/lib/api/direct.ts`: a 401 is retried once with `forceRefresh:true`; a second 401 → `onAuthLost()` → signOut → login. `transport.ts` maps 401/402/403 into typed errors for both targets. The step-up framework (P1.2) reuses this exact interception pattern for a `step_up_required` challenge — the plumbing pattern is proven, only the second-factor ceremony is new |
| §2.4 Capacitor plugin choice `[VERIFY]` | **VERIFIED, not yet installed** | `@capgo/capacitor-native-biometric` confirmed correct: actively maintained (8.5.0 published 2026-07-03), has biometric-GUARDED credential storage (`getSecureCredentials`, `BIOMETRY_CURRENT_SET`) — maps 1:1 to spec §2.1. **Capacitor 7 constraint: install the 7.x line (`7.6.0`, 2025-12-05, peer `@capacitor/core >= 7`)**; 8.x requires Capacitor 8. [npm; github.com/Cap-go/capacitor-native-biometric] |
| §2 biometric device-bound credential, §3 passkeys, §4 step-up, §5 recovery, §6 audit log | **NOT BUILT** | → Phase 1 |
| ID.me spec (all) | **NOT BUILT** | → Phase 2. All §4 `[VERIFY]` items resolved (§2/D2 here + owner actions) |
| Lighthouse spec (all) | **NOT BUILT** | → Phase 3. All §8 `[VERIFY]` items resolved or owner-gated |

**Factor-mapping compliance note (spec §1):** record in the compliance file that a passkey/WebAuthn
assertion is itself possession+inherence; OTP + passkey is defense-in-depth, not a single factor.
Same for the native device-bound secret behind Face ID.

---

## 2. Decisions

### D1 — MFA strictness: **Model B (enrollment MFA + device trust), config-flagged**

Per `auth-security-flows.md` §2.3's own recommendation: OTP + factor-2 bind the device at
enrollment; thereafter one biometric/passkey gesture unlocks the session, with OTP/passkey
**step-up** on new devices and sensitive actions (§4 of that spec). Matches the
low-friction-for-a-60-year-old goal while staying true MFA at trust-establishing moments.

**Config key:** `va-claim.auth.mfa-model` (follows the existing `va-claim.auth.dev-mode` /
`va-claim.sendgrid.*` namespace in `application.yml`).
Values: `ENROLLMENT_DEVICE_TRUST` (**default**, Model B) | `STRICT_EVERY_LOGIN` (Model A).
Read in the step-up/service layer so tightening later is a config change, not a code change.

### D2 — App Store Guideline 4.8: **adding ID.me does NOT force Sign in with Apple**

Verified live 2026-07-04 at https://developer.apple.com/app-store/review/guidelines/ (§4.8
"Login Services"). The trigger:

> "Apps that use a third-party or social login service (such as Facebook Login, Google Sign-In,
> Log in with X, Sign In with LinkedIn, Login with Amazon, or WeChat Login) to set up or
> authenticate the user's primary account with the app must also offer as an equivalent option
> another login service with the following features: the login service limits data collection to
> the user's name and email address; the login service allows users to keep their email address
> private as part of setting up their account; and the login service does not collect
> interactions with your app for advertising purposes without consent."

And the operative exemption:

> "Another login service is not required if: … **Your app uses a government or industry-backed
> citizen identification system or electronic ID to authenticate users.**"

**Verdict:** ID.me is a federally relied-upon credential service provider (one of exactly two
CSPs accepted at VA.gov sign-in today [va.gov/resources/signing-in-to-vagov]) — squarely a
"government or industry-backed citizen identification system." So when P2 ships `Continue with
ID.me` on iOS, 4.8's exemption applies and Sign in with Apple stays non-mandatory.
Secondary (belt-and-braces) argument: the first-party OTP login remains offered, collects only
phone/email for sign-in, and involves no advertising data collection — substantially the
privacy profile 4.8 asks of the alternative service.
**Consequence:** `capacitor-ios-spec.md` §H.2 currently rests on the premise "no third-party
login → 4.8 untriggered," and its own guard says any third-party login re-arms the gate. §H.2
must be **rewritten with P2** to rest on the citizen-ID exemption instead, and the App Review
notes must cite the exemption explicitly (cross-cutting item, §3.4). Residual risk: exemption
applicability is a reviewer judgment — flagged in the P2 risk list.

### D3 — WebAuthn RP layer: **Spring backend, Yubico `java-webauthn-server` (revises spec §3.1)**

`auth-security-flows.md` §3.1 suggested `@simplewebauthn/server` in a Node/Cloud Functions RP.
Research verdict (2026-07-04): use **`com.yubico:webauthn-server-core:2.9.0`** (released
2026-05-12, actively maintained by Yubico) **inside the existing Spring backend** instead
[github.com/Yubico/java-webauthn-server; Maven Central]. Rationale:

- The credential store belongs next to the `User` table in Cloud SQL — Spring already owns the
  user model, the custom-token mint, the rate-limit tables, and (after P1.1) the audit log.
  One authoritative auth tier, not two.
- The Next.js BFF is deliberately thin/stateless (proxy + cookie); adding server-side ceremony
  state and a credential DB there would break that convention (`project_web_frontend` notes).
- The browser side needs no framework: `navigator.credentials.create()/get()` called from the
  login page/profile UI, JSON options proxied through same-origin BFF routes
  (`/api/auth/webauthn/*` → Spring), exactly like the shipped email-code proxy pattern.
- RP ID: `vaclaimpath.com` (registrable suffix — valid for `app.vaclaimpath.com` and any future
  subdomain); allowed origins pinned to the production web origin(s).

### D4 — Account recovery: **dual-channel re-verify first; recovery codes later**

`auth-security-flows.md` §5 offers two options. Pick: **require fresh verification of BOTH
channels (email code + SMS code) to reset factor 2** — because the dual-verify onboarding
(§1 table above) already guarantees every new account has both verified channels, and the
verification machinery (EmailCodeService purposes, Firebase phone confirm, the login page's
two-step UI) is shipped and battle-tested. Recovery = prove both possessions → revoke all
passkeys + device credentials → force factor-2 re-enrollment → audit-log the event
(who/when/channels/IP/device). One-time recovery codes are deferred to a later increment
(they add printing/storage UX and a new secret class for marginal benefit while both channels
exist); they become necessary only if we later allow single-channel accounts or channel loss
is observed in support load. Legacy/skip-dual accounts with one channel: recovery falls back
to that channel **plus** a mandatory support-reviewed hold (flag in the audit log) — never
silent single-channel factor-2 reset.

---

## 3. Phases

Sizes are single-engineer estimates. Each increment lands independently and green.

### Phase 1 — Factor-2 core (task #148) — **no external dependencies, start immediately**

| # | Increment | Size | Depends on |
|---|---|---|---|
| P1.1 | **Auth audit log.** New append-only table `auth_audit_log` (§3.4 schema). Write events from the existing flows first (OTP request/verify/fail, sign-in, attach, sign-out) so the log has value before factor 2 exists. No UPDATE/DELETE code paths; retention per HIPAA policy (owner confirms period). | 1-2 d | — |
| P1.2 | **Step-up framework.** Server: `@RequiresStepUp`-style guard on sensitive endpoints (document view/export, contact-channel change, future VA import) returning `403 {code:"step_up_required"}`; a step-up verify endpoint that accepts a fresh factor proof (passkey assertion / device-credential exchange / OTP with new purpose `stepup` added to `EmailCodeService`) and mints a short-TTL (~5 min) step-up token checked via header. Client: intercept `step_up_required` in the ApiClient exactly where the 401-retry lives today (`direct.ts` / `transport.ts`), run the ceremony, retry once. Reads `va-claim.auth.mfa-model` (D1). | 3-4 d | P1.1 (logs step-ups) |
| P1.3 | **Passkeys (web factor 2).** Spring RP with `com.yubico:webauthn-server-core:2.9.0` (D3); table `webauthn_credentials` (user_id, credential_id, COSE public key, sign_count, transports, aaguid, backup flags, nickname, created/last_used/revoked); server-side challenge store with TTL; BFF proxy routes `/api/auth/webauthn/{register,assert}/{options,verify}`; UI: post-OTP "Set up Face ID / fingerprint / PIN sign-in" enroll step, passkey-first path on returning login (identifier-first + allowCredentials in v1; discoverable-credential/usernameless later), management list in Profile (rename/revoke). Model B: new device = OTP → enroll passkey; known device = passkey alone; step-up prefers passkey (spec §1 SMS-weakness note). | 5-8 d | P1.2 |
| P1.4 | **Native biometric + device-bound token.** Install `@capgo/capacitor-native-biometric@7.6.0` (Capacitor-7 line — D/§1). Table `device_credentials` (user_id, device_secret **hash**, device name/platform, created/last_used/revoked). Enrollment: after OTP session + "Enable biometric unlock?" opt-in, Spring mints a random 256-bit device secret (returned once), stored via `setCredentials` with `accessControl: BIOMETRY_CURRENT_SET`. Login: `getSecureCredentials()` (forces a FRESH biometric prompt — never a bare `verifyIdentity()` gate, which is bypassable on rooted devices per the plugin's own README) → `POST /api/auth/device/exchange` → constant-time hash check → Firebase custom token → plugin `signInWithCustomToken`. Fallback to OTP + re-enroll on biometric failure/`biometryChange`. Revocation on recovery and "sign out everywhere". | 4-6 d | P1.2; device-on-hand for final verify (simulator `simctl` biometrics for dev) |
| P1.5 | **Recovery (D4).** "Can't use your passkey/Face ID?" flow: verify email code **and** SMS code back-to-back (existing services, new orchestration + purpose), then revoke all rows in `webauthn_credentials` + `device_credentials`, force factor-2 re-enrollment, audit-log. Single-channel legacy accounts → flagged support-hold path. | 2-3 d | P1.3, P1.4 |
| P1.6 | **Config + compliance record.** `va-claim.auth.mfa-model` default `ENROLLMENT_DEVICE_TRUST`; factor-mapping note (§1) in the compliance/audit doc. | ~1 d (mostly folded into P1.2/P1.4) | — |

**Phase total ≈ 3-4 engineer-weeks.** Ships alone: users get passkey/biometric sign-in,
step-up on sensitive actions, an auditable recovery path — independent of ID.me and VA.

### Phase 2 — ID.me login + linking (task #149) — **gated on owner's ID.me registration**

Dev can start the day sandbox credentials exist (sandbox is automatic on developer-account
creation [docs.id.me/integrations/deploy-and-monitor/launching-in-production]). Production is
human-gated (E2E walkthrough + commercial relationship) — see owner actions.

Corrections to `idme-signin-spec.md` from verified research (apply before build):
- **§1 Scopes is wrong as written.** There are no `profile`/`email` scopes at ID.me. The request
  is `scope=openid%20<POLICY>` — `openid` plus exactly ONE org-assigned policy scope. Profile
  claims come back per policy with **proprietary names** (`fname`/`lname`/`uuid`/`zip`, not
  `given_name`/`family_name`) [docs.id.me/integrations/configurations/configuration-standards].
- Veteran verification policy scope = **`military`**; response includes
  `status:[{"group":"military","subgroups":[…],"verified":true}]` with subgroups
  `Service Member | Veteran | Retiree | Military Spouse | Military Family | Surviving Spouse`.
  **`verified:true` alone does NOT mean veteran** — gate veteran-only features on
  subgroup ∈ {Veteran, Service Member, Retiree} [docs.id.me/guides/o-auth-2-0/community-group-payloads].
- Endpoints (pulled live from discovery): prod issuer `https://api.id.me/oidc`, authorize
  `https://api.id.me/oauth/authorize`, token `https://api.id.me/oauth/token`, userinfo
  `https://api.id.me/api/public/v3/userinfo`, JWKS `https://api.id.me/oidc/.well-known/jwks`;
  sandbox = identical paths on `api.idmelabs.com`. RS256 id_tokens.
- **Configure the OIDC client manually, not from discovery**: the discovery doc advertises
  `scopes_supported:["openid"]` only and omits `code_challenge_methods_supported`, so
  auto-configuring libraries may silently drop PKCE or reject the policy scope. PKCE is
  documented and **must be S256** [docs.id.me/guides/oidc/pkce].
- The documented id_token example shows **no `nonce` claim** — verify nonce echo in sandbox
  before making spec §1 step-6 nonce validation a hard failure.
- IAL2 (`http://idmanagement.gov`) is **not needed** for login or for the Lighthouse import
  (VA runs its own ID.me IAL2 at VA's sign-in page); adding it is a commercial decision (owner).

| # | Increment | Size | Depends on |
|---|---|---|---|
| P2.1 | Spring OIDC client (authorization code + PKCE S256, `state`+`nonce`, server-side exchange, client_secret_post) against sandbox `api.idmelabs.com`; manual endpoint/scope config; id_token validation via JWKS. Table `idme_identities` (`idme_sub` unique → `user_id`, email, subgroup(s), verified flag, linked_at). | 3-4 d | Owner: sandbox client_id/secret |
| P2.2 | Scenario A login: `Continue with ID.me` button (web first) → session mint via the existing `FirebaseCustomTokenService` pattern (custom token for the uid mapped to `idme_sub`); provision-or-link on first login; **no app-side factor 2 on this path** (ID.me does IAL2+MFA — record in the factor-mapping note). | 2-3 d | P2.1 |
| P2.3 | Scenario B linking: `Connect ID.me` from Profile (and as the pre-step of "Import my VA records"); conflict/merge path when `idme_sub` or verified email maps to a different user (surface, never silently fork — spec §2). Veteran-status badge from `military` subgroup. | 2-3 d | P2.1 |
| P2.4 | Native: `ASWebAuthenticationSession` with custom-scheme callback (evidenced by ID.me's own iOS SDK pattern, `yourapp://idme/callback`); code exchange stays server-side. **Blocked on owner confirming custom-scheme redirect approval with ID.me** (redirect-URI docs cover only static https URIs; universal links undocumented). | 2-3 d | P2.2; owner confirmation |
| P2.5 | §H.2 rewrite + review notes citing the 4.8 citizen-ID exemption (D2). | 0.5 d | ships with the first iOS build containing P2 |

**Phase total ≈ 2 engineer-weeks** after sandbox credentials. Production cutover waits on the
ID.me E2E walkthrough (owner action; no published timeline).

### Phase 3 — Lighthouse import (task #150) — **gated on VA sandbox key; production gated 1-6 months**

Spec deltas from verified research (apply to `lighthouse-import-feature-request.md`):
(a) "Veterans Health API" → **Patient Health API (FHIR), R4**; (b) **Benefits Claims API pinned
to v1** ("v2 — Internal VA Use Only"); (c) ratings/service = **Veteran Service History and
Eligibility API v2**; (d) exact scopes now enumerable (below); (e) VA.gov sign-in offers **both
ID.me and Login.gov** (MHV retired 2025-03-05, DS Logon retired 2025-11-18) — spec §8 first
question answered; (f) **refresh tokens expire after 42 days of inactivity in production**
(7 in sandbox) — re-pull UX must handle re-auth at VA.gov, no indefinite background sync;
(g) **60 requests/min per consumer (app-wide, not per veteran)** — importer needs a queue,
429 backoff, and `ratelimit-remaining` awareness; (h) VA ToS/policy standards (45-day 100%
deletion, disclosure standards, mandatory health disclaimer) enter the acceptance criteria.
[developer.va.gov/explore; live `/.well-known/openid-configuration` on api.va.gov;
developer.va.gov/production-access]

OAuth facts (live-verified): authorization-code + PKCE **S256 only**; per-API-family endpoints
`https://api.va.gov/oauth2/{health|claims|veteran-verification}/v1/{authorization,token,introspect,revoke,manage,keys}`
(sandbox identical on `sandbox-api.va.gov`); access tokens ~3600 s; `launch/patient` returns the
patient ICN as the `patient` claim; veterans can inspect/revoke grants at the `/manage` URL.
Scopes to request (minimum-sufficient — adding later forces a fresh consent):
- Health: `openid profile offline_access launch/patient` + `patient/{Condition,DiagnosticReport,DocumentReference,Medication,MedicationRequest,Observation,Procedure,Immunization,AllergyIntolerance,Binary,Patient}.read` (trim in sandbox against what the pipeline actually consumes)
- Claims: `claim.read` (v1; confirm the issued client's naming — sandbox also lists `veteran/claim.read` variants)
- Verification: `service_history.read disability_rating.read disability_rating_summary.read permanent_and_total_disability.read`

| # | Increment | Size | Depends on |
|---|---|---|---|
| P3.1 | OAuth plumbing: per-API-family clients against sandbox; table `va_authorizations` (user_id, api_family, encrypted access/refresh tokens — AES-GCM, key in Secret Manager, **server-side only** —, patient ICN, scopes, granted/expires/revoked). Confidential-client mode (the Spring backend can hold a secret — declare that on the sandbox form). | 3-4 d | Owner: sandbox signup |
| P3.2 | Consent + step-up UX: pre-authorization consent screen listing the exact scopes and what will be pulled; the import action sits behind a **fresh P1.2 step-up**; "Import my VA records" triggers ID.me/Login.gov **at VA's page** (independent of P2 linking — spec boundary preserved). | 2-3 d | P1.2 |
| P3.3 | Import orchestrator: job table `va_import_jobs` (user, claim, scope set, status, counts); global token-bucket ≤60 req/min with 429/`ratelimit-remaining` backoff; paginated multi-resource FHIR pull; idempotent re-pull. | 4-5 d | P3.1 |
| P3.4 | **Imported-data model → existing evidence/atoms pipeline.** Two lanes: (1) **Structured facts** (disability ratings + effective dates, P&T/IU status, service periods, claim status list/detail) map **deterministically** to `Atom` rows (`created_by="import:va-lighthouse"`, `confidence=1.0`, `evidence_id` linking back, existing `atom_type` vocabulary) — no LLM pass needed, source-of-truth data enters the gap/scenario engine directly. (2) **Clinical content** (DocumentReference/Binary PDFs and notes, DiagnosticReports, Conditions, Medications) lands as `EvidenceItem` rows (`source_type="va_lighthouse_health"` etc., FHIR JSON in `raw_content`, binaries in GCS via `gcs_path`) and flows through the **existing single-pass extraction** exactly like an upload — the `extract_key` content-hash machinery already dedupes re-imports for free. | 4-5 d | P3.3 |
| P3.5 | Revoke + delete: user-facing "Disconnect VA / delete imported data" → call the API-family `/revoke` endpoint, null tokens, delete imported `EvidenceItem`s + cascaded atoms (extend the existing `UserDeletionService` so account deletion also covers imported data — feeds VA's 45-day/100% deletion policy requirement), audit-log both. Show "what was imported" per job. | 2-3 d | P3.4 |
| P3.6 | Compliance surface: mandatory in-app disclaimer ("Service is for educational and informational purposes, not clinical decisions") on health-data screens; ToS/privacy-policy alignment (owner-published, §4); DD-214 stays manual-upload (NOT a Lighthouse capability — spec §3 stands). | 1-2 d | — |

**Phase total ≈ 3 engineer-weeks in sandbox.** Production cutover waits on VA approval
(published 1-6 months + a live banner warning of "long delays in approving production access
for Veteran benefits APIs" — start the owner paperwork NOW, §4).

### 3.4 Cross-cutting

**Audit-log schema (`auth_audit_log`, P1.1, consumed by all phases):**

```
id            bigserial PK
occurred_at   timestamptz NOT NULL
user_id       bigint NULL          -- pre-auth events have none
firebase_uid  varchar NULL
event_type    varchar NOT NULL     -- OTP_REQUESTED|OTP_VERIFIED|OTP_FAILED|SIGNIN|SIGNOUT|
                                   -- EMAIL_ATTACHED|PHONE_LINKED|
                                   -- PASSKEY_ENROLLED|PASSKEY_ASSERTED|PASSKEY_REVOKED|
                                   -- DEVICE_CRED_ISSUED|DEVICE_CRED_EXCHANGED|DEVICE_CRED_REVOKED|
                                   -- STEP_UP_PASSED|STEP_UP_FAILED|
                                   -- RECOVERY_STARTED|RECOVERY_COMPLETED|
                                   -- IDME_LINKED|IDME_LOGIN|IDME_LINK_CONFLICT|
                                   -- VA_AUTH_GRANTED|VA_IMPORT_STARTED|VA_IMPORT_COMPLETED|
                                   -- VA_TOKENS_REVOKED|VA_DATA_DELETED|
                                   -- DOC_VIEWED|DATA_EXPORTED
channel       varchar NULL         -- email|phone|passkey|biometric|idme|va
outcome       varchar NOT NULL     -- SUCCESS|FAILURE
ip            varchar NULL         -- clientIp() rules from EmailCodeAuthController
user_agent    varchar NULL
device_credential_id bigint NULL
detail_json   jsonb NULL           -- NEVER codes, tokens, secrets, or PHI
```
Append-only (no update/delete code paths; revoke UPDATE/DELETE from the app DB role on this
table). Retention period = owner's HIPAA policy decision (§4).

**App Store §H.2 update:** rewrite with P2 per D2 (exemption-based, quoted guideline, review-notes
paragraph). Until P2 ships on iOS, current §H.2 stays correct as-is.

**BAA owner steps:** the specs assert "BAA-covered GCP store"; the coverage must be confirmed
before P3 lands PHI (owner actions #6): execute/confirm the Google Cloud BAA and check the
HIPAA-included-services list covers everything PHI touches (Cloud Run, Cloud SQL, GCS, Secret
Manager, Vertex AI); confirm Firebase Auth's position (identifiers only, no PHI — document the
boundary); SendGrid is not HIPAA-eligible → keep emails PHI-free (already true: OTP codes only —
keep it that way in all future notification work).

---

## 4. Owner-action list (ordered by lead time, longest first)

1. **VA production access — START THE PAPERWORK NOW (lead time 1-6 months, plus VA's posted
   warning of long delays for Veteran-benefits APIs).**
   (a) Publish production-grade **Terms of Service and Privacy Policy URLs** meeting VA's ACG
   policy standards: ≤grade-12 reading level, ≥14px/WCAG 4.5:1, data-retention statement,
   user-initiated 100% data deletion within 45 days, named third-party-sharing disclosures,
   breach-notification + ownership-transfer + change-notice clauses.
   (b) Complete the production-access application at
   https://developer.va.gov/production-access (form can't save progress, ~30 min; requires
   business model, PHI/PII storage details, breach/vuln processes, exact scopes).
   (c) Respond to VA's ~1-2-week review, then pass the 30-60-min demo (health-API demo needs the
   medical-advice disclaimer visible in screenshots).
2. **ID.me production path (no published timeline; human-gated).** Engage a solution consultant
   (partnersupport@id.me): commercial terms/pricing for a veteran-services app (**no public
   pricing exists — could not be verified without an account**), exact policy scopes enabled for
   our client (`military` community; optionally `http://idmanagement.gov` IAL2 — not needed for
   the Lighthouse path), custom-scheme native redirect approval, then schedule and pass the
   **End-to-end walkthrough QA session** that gates production.
3. **VA sandbox signup (instant, self-serve) — gates P3 dev start.**
   https://developer.va.gov/explore/api/{patient-health|benefits-claims|veteran-service-history-and-eligibility}/sandbox-access
   — select **Authorization Code Grant**, attest secure secret storage (confidential client for
   the Spring BFF). The welcome email carries client id/secret + the private link to
   ID.me/Login.gov **test-account credentials** (masked publicly — unresolvable without signup).
4. **ID.me developer account + org (instant sandbox) — gates P2 dev start.** Sign in at
   https://api.id.me/en/session/new, dashboard https://developers.id.me; register redirect URIs
   (static, ≤255 chars, no wildcards/fragments; localhost/ngrok = sandbox-only via the
   consultant); read our client's **actual policy scope handle** from the dashboard (do not
   hardcode `military` before seeing it).
5. **MyHealthApplication.com listing (free, CARIN Alliance attestation)** before the VA demo —
   VA strongly recommends and checks it for Patient Health API consumers.
6. **BAA/HIPAA posture:** execute/confirm the Google Cloud BAA in the Cloud Console for
   project craftloop-va-claim; verify the HIPAA-included-services list covers Cloud Run, Cloud
   SQL, GCS, Secret Manager, Vertex AI; decide the audit-log retention period (typ. 6 years);
   document the Firebase-Auth/SendGrid no-PHI boundary.
7. **Decisions to ratify (cheap, do with #2/#4):** ID.me product mix (community verification
   alone vs +IAL2); accept Model B default (D1); accept recovery-first-via-dual-channel (D4).

### Open `[VERIFY]` items research could NOT resolve without accounts (folded above)

- ID.me pricing/commercial terms for a veteran-services app (→ #2).
- The concrete policy-scope handle for OUR client (documented standard is `military`; the real
  handle is org-assigned, read from the dashboard → #4).
- Whether ID.me echoes `nonce` in the id_token (docs example omits it) — test in sandbox before
  hard-failing on nonce (P2.1 acceptance item).
- Custom-scheme native redirect approval for a non-SDK flow (→ #2/#4; P2.4 blocked on it).
- VA sandbox test-account passwords and the production form's steps beyond page 1 (→ #3, #1).
- Claims-scope naming on the issued client (`claim.read` vs `veteran/claim.read`) (→ after #3).
- Real-world VA approval latency vs the published 1-6-month range (→ #1; plan pessimistically).
- Apple's acceptance of the 4.8 citizen-ID exemption for ID.me is a review-time judgment —
  mitigated by the review-notes citation (P2.5) and the always-available first-party OTP login.

---

## 5. Sequencing summary

```
NOW ──────────► P1 factor-2 core (3-4 wks, zero external deps)  [task #148]
owner #3/#4 ──► sandbox keys arrive (days)
   then ─────► P2 ID.me sandbox build (2 wks)                   [task #149]
   then ─────► P3 Lighthouse sandbox build (3 wks)              [task #150]
owner #1/#2 ──► production gates clear (months) ──► P2/P3 production cutover
cross-cutting: audit schema lands in P1.1; §H.2 rewrite ships with P2 iOS; BAA before P3 PHI.
```
