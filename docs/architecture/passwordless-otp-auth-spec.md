# Passwordless OTP Auth — Architecture Spec

**Status:** Frozen contract for build. **Owner:** auth rebuild.
**Date:** 2026-06-20.
**Mirrors:** VolunTails' proven, shipped single-box phone-or-email OTP
(`/Users/rusticus/Developer/voluntails`). Backend pattern ported FastAPI/SQLAlchemy →
Spring Boot/Java; web pattern ported Next.js → Next.js (BFF).

This spec replaces VA Claim Path's current login (Google + Apple + password +
Firebase email magic-link + the phone sheet) with **one input box: phone or
email, OTP only**. No Google, no Apple, no password, no magic-link.

---

## 0. Goals & non-goals

**Goals**
- Single "Phone or email" box. `@` → email-code (our backend). No `@` → Firebase
  phone OTP + invisible reCAPTCHA.
- Brand-new users verify **both** channels (dual-verify) and converge on **one**
  Firebase user carrying both `email`/`emailVerified` and `phoneNumber`.
- Adopt VolunTails' wire contract verbatim where it exists so the two apps stay
  consistent: `POST /api/auth/email-code/request`, `POST /api/auth/email-code/verify`
  → `{custom_token}`, SHA-256 hashed code, 10-min TTL, constant-time compare,
  max-attempts, SendGrid sender.
- Returning users (including legacy Google/Apple accounts) sign in with **one**
  channel — never locked out. Link by identifier.

**Non-goals**
- No web MFA challenge UI (existing `MFA_MESSAGE` copy stays for the popup path
  that is being removed; see §10 cleanup).
- No SMS provider on our side — phone OTP stays 100% Firebase Phone Auth.
- No change to the BFF session model (`cp_session` httpOnly cookie via
  `POST /api/session`); we reuse it untouched.

---

## 1. File boundaries (parallel build)

Web and backend can be built independently against the frozen §2 contract.

### Backend (Spring Boot / Java) — `spring-backend/...`
| File | Purpose |
|---|---|
| `model/EmailVerificationCode.java` | **NEW** JPA entity — OTP storage (§3). |
| `repository/EmailVerificationCodeRepository.java` | **NEW** Spring Data repo + the queries in §3.4. |
| `service/EmailCodeService.java` | **NEW** mint+send + verify core (ports `_mint_and_send_email_code` / `_verify_email_code_row`). |
| `service/EmailSender.java` (interface) + `SendGridEmailSender.java` | **NEW** SendGrid abstraction + graceful-degrade (§5). |
| `service/EmailCodeRateLimiter.java` | **NEW** (optional split) per-identifier + per-IP caps + cooldown, or inline in `EmailCodeService`. |
| `controller/EmailCodeAuthController.java` | **NEW** `@RequestMapping("/api/auth")` — the three endpoints in §2. Kept separate from the existing `AuthController` because two of these routes are **public** (pre-auth) and `attach` is Bearer-authed. |
| `service/FirebaseCustomTokenService.java` | **NEW** wraps `FirebaseAuth.createCustomToken` / `getUserByEmail` / `createUser` / `updateUser` + the IAM gotcha error surface (§6). |
| `config/SecurityConfig.java` | **EDIT** add the two public paths to the auth-filter skip list (§7). |
| `config/FirebaseConfig.java` | **REUSE** as-is — Firebase Admin already initialized via ADC + `va-claim.firebase.project-id`. |
| `service/FirebaseAuthService.java` | **REUSE** `getOrCreateFirebaseUser` already links a Firebase uid to an existing-by-email `User` row — this is our convergence anchor on the app-DB side (§8). |
| `application.yml` | **EDIT** add `va-claim.sendgrid.*` keys (§5). |
| `db/` doc-migration | **NEW** DDL note (§3.3) — `ddl-auto: update` creates the table; record the manual prod DDL for the security reviewer. |

### Web (Next.js BFF) — `web/src/...`
| File | Purpose |
|---|---|
| `app/(auth)/login/page.tsx` | **REWRITE** single-box state machine (§9). Remove Google/Apple/password/magic-link. |
| `app/(auth)/login/login.module.css` | **EDIT** trim provider/divider styles; keep field/error/notice. |
| `app/(auth)/login/friendly-error.ts` | **REUSE/trim** — keep `friendlyError`; the Firebase phone codes still apply. |
| `lib/auth/otp-identifier.ts` | **NEW** `@`-branch + `toE164US` E.164 normalization (default `+1`) (§9.2). Ported from VolunTails. |
| `lib/auth/email-code-client.ts` | **NEW** thin `fetch` helpers to the BFF proxy routes for request/verify/attach. |
| `app/api/auth/email-code/request/route.ts` | **NEW** BFF proxy → backend (pre-auth; forwards client IP). |
| `app/api/auth/email-code/verify/route.ts` | **NEW** BFF proxy → backend (pre-auth). |
| `app/api/auth/email-code/attach/route.ts` | **NEW** BFF proxy → backend (cookie→Bearer; authed). |
| `lib/firebase/session.ts` | **REUSE** — `establish(user)`, `postSession`, `watchIdToken`, `signOut`, the phone helpers (`getRecaptcha`, `startPhoneSignIn`, `confirmPhoneCode`). Add `signInWithCustomToken` + `linkWithPhoneNumber` wrappers (§9.4). |
| `lib/firebase/client.ts` | **REUSE** `getFirebaseAuth()`. |
| `lib/safe-next.ts` | **REUSE** untouched — `afterAuth` redirect stays open-redirect-safe (§7). |

---

## 2. Frozen HTTP contract

All bodies are JSON. Identifiers are normalized server-side: email = trimmed +
lower-cased; phone is **not** handled by these endpoints (phone OTP is Firebase).
Error bodies use the existing backend shape `{"detail": "<message>"}` (matches
`SecurityConfig` 401 writer and the FastAPI `{detail}` the web layer already
parses).

### 2.1 `POST /api/auth/email-code/request` — public (pre-auth)
Email a 6-digit code. **Always** returns `200 {"ok": true}` for a syntactically
valid email (anti-enumeration — never reveals whether the email exists). Mints,
hashes, stores, and sends the code (§3, §5).

Request:
```json
{ "email": "you@example.com" }
```
Responses:
- `200 {"ok": true}` — code sent (or generically accepted).
- `400 {"detail": "Enter a valid email address"}` — fails the shape check
  (`@` present, a `.` in the domain, not starting with `@`).
- `429 {"detail": "Please wait a moment before requesting another code"}` — resend cooldown (<30s).
- `429 {"detail": "Too many codes requested. Try again later."}` — per-email (≥5/hr) or per-IP (≥20/hr) cap.
- `502 {"detail": "Email could not be sent ..."}` — SendGrid failure (only when not in the dev-unconfigured grace, §5).

Client IP for the per-IP cap is derived **server-side** from `X-Forwarded-For`
using the second-to-last entry behind GCP's LB (§3.5) — the BFF proxy forwards
the original `X-Forwarded-For` (§7).

### 2.2 `POST /api/auth/email-code/verify` — public (pre-auth)
Verify the 6-digit code and mint a Firebase **custom token** for the *exact
verified email*. Sign-in == sign-up: an unknown email creates a Firebase user
with `emailVerified=true`. One **generic** `400` for every failure mode
(wrong / expired / none / exhausted) — no oracle.

Request:
```json
{ "email": "you@example.com", "code": "123456" }
```
Response `200`:
```json
{ "custom_token": "<firebase-custom-jwt>", "isNewUser": true, "hasPhone": false }
```
- `custom_token` — pass to `signInWithCustomToken` on the client.
- `isNewUser` — **true iff the Firebase user was created in THIS request**
  (detected post-verify, never pre-send → no enumeration). Drives dual-verify
  (§9.3). For an existing user, `false`.
- `hasPhone` — whether the resolved Firebase user already has a `phoneNumber`.
  Lets the client skip the phone-link step for a returning email user who already
  linked a phone. (For a brand-new email-first user this is always `false`.)

Other responses:
- `400 {"detail": "Incorrect or expired code"}` — the single generic failure.
- `400 {"detail": "Enter your email"}` / `{"detail": "Enter the code"}` — missing field.
- `502 {"detail": "Could not complete sign-in: ..."}` — Firebase Admin failure,
  **including** the IAM token-mint 403 surfaced with a clear message (§6).

> Token-minting rule (security): the custom token is minted **only** for the uid
> that owns the just-verified email. We never accept a uid/email from the client
> and never mint a token for an arbitrary address.

### 2.3 `POST /api/auth/email-code/attach` — **authenticated** (Bearer)
Phone-first new users use this to attach a **verified email** to their *current*
Firebase uid. The email analogue of VolunTails' `confirm-email`. Requires the
normal `Authorization: Bearer <Firebase ID token>` (resolved by the existing auth
filter; the BFF proxy converts the `cp_session` cookie → Bearer like every other
authed call).

Request:
```json
{ "email": "you@example.com", "code": "123456" }
```
Response `200`:
```json
{ "status": "verified", "email": "you@example.com", "emailVerified": true }
```
Behavior:
1. Verify + **consume** the code with `purpose = ATTACH` (an attach code can't be
   redeemed as a sign-in code and vice-versa).
2. **Hijack guard:** if the email already belongs to *another* Firebase user →
   `409 {"detail": "That email is already in use by another account."}`. Re-check
   after consume to close the TOCTOU race.
3. Otherwise call Firebase Admin `updateUser(currentUid, email, emailVerified=true)`
   on the **current** uid **only** — never another uid.
4. Mirror into the app `User` row (`email`, set verified flag) for the current user.

Other responses:
- `400 {"detail": "Incorrect or expired code"}` — generic verify failure.
- `400 {"detail": "Enter the verification code"}` / `"Enter a valid email address"`.
- `401 {"detail": "Invalid Firebase token"}` — not signed in.
- `409` — email owned by another account (also returned on a unique-index
  violation, never a 500).

> The attach endpoint's `request` step reuses `POST /api/auth/email-code/request`
> with the **same** body. The *only* server difference is the `purpose` stamped
> on the minted row. To keep the wire surface minimal we do **not** add a second
> request endpoint: the client calls `request` (purpose defaults to `SIGNIN`),
> and `attach` accepts a code minted under either `SIGNIN` or `ATTACH`?
> **No** — see §3.6 decision: attach requires its own request. **Frozen choice:**
> add `POST /api/auth/email-code/request` to accept an optional
> `"purpose": "attach"` field (default `"signin"`); the verify-vs-attach
> consumption then scopes by purpose. This keeps three endpoints total and avoids
> a sign-in code being usable to silently attach an email to a hijacked session.

Revised §2.1 body (purpose-aware):
```json
{ "email": "you@example.com", "purpose": "signin" }   // or "attach"
```
`purpose` is optional; absent ⇒ `"signin"`. For `"attach"` the caller **must** be
authenticated (the BFF `attach/request` proxy is the authed one); an unauthed
`request` with `purpose=attach` is treated as `signin` (fail safe — never trust a
client-asserted purpose to widen scope).

### 2.4 No separate existence/`/me` check
New-vs-returning is decided **only** by `isNewUser` on the verify response (and
`phoneNumber` on the resulting Firebase user) — *post-verification*. There is
deliberately **no** pre-send "does this user exist" endpoint: that would be an
enumeration oracle. The existing `GET /api/auth/me` is unchanged and is called
*after* the session cookie is established, exactly as today.

---

## 3. OTP storage model

### 3.1 Entity — `EmailVerificationCode`
Ports VolunTails' `EmailVerificationCode` SQLAlchemy model to JPA.

| Column | Type | Notes |
|---|---|---|
| `id` | `BIGINT` PK, identity | |
| `email` | `VARCHAR(320)` NOT NULL | normalized (lower-cased). Indexed. |
| `code_hash` | `VARCHAR(64)` NOT NULL | **SHA-256 hex of the 6-digit code**. Plaintext code is never stored or logged. |
| `purpose` | `VARCHAR(16)` NULL | `SIGNIN` \| `ATTACH`. NULL allowed for legacy grace (none exist yet, but keep the column nullable to match the pattern). |
| `attempts` | `INT` NOT NULL default 0 | wrong-guess counter; cap = 5. |
| `expires_at` | `TIMESTAMP` NOT NULL | `created_at + 10min`. |
| `consumed_at` | `TIMESTAMP` NULL | non-null ⇒ burned (single-use / superseded). |
| `request_ip` | `VARCHAR(45)` NULL | for per-IP hourly cap (IPv6-sized). |
| `created_at` | `TIMESTAMP` NOT NULL | mint time; cooldown + hourly windows. |

Indexes: `(email, created_at)`, `(request_ip, created_at)`, `(email, consumed_at)`.

### 3.2 Constants (port verbatim)
```
EMAIL_CODE_TTL_MIN                = 10
EMAIL_CODE_MAX_ATTEMPTS           = 5
EMAIL_CODE_RESEND_COOLDOWN_SEC    = 30
EMAIL_CODE_MAX_PER_EMAIL_HOUR     = 5
EMAIL_CODE_MAX_PER_IP_HOUR        = 20
CODE_LENGTH                       = 6   // secure RNG, 000000–999999, zero-padded
```
Code generation: `String.format("%06d", new SecureRandom().nextInt(1_000_000))`
(equivalent to VolunTails' `secrets.randbelow(1_000_000)`).

### 3.3 Schema convention (ddl-auto + doc-migration)
The backend runs `spring.jpa.hibernate.ddl-auto: update` (see `application.yml`),
so the `email_verification_code` table and its columns are **auto-created** on
boot from the entity — no Flyway/Liquibase in this project. **However**, for the
security reviewer and for a controlled prod rollout, record the equivalent DDL as
a doc-migration under `spring-backend/src/main/resources/db/`:

```sql
-- db/2026-06-20_email_verification_code.sql  (documentation — applied by ddl-auto)
CREATE TABLE IF NOT EXISTS email_verification_code (
  id          BIGSERIAL PRIMARY KEY,
  email       VARCHAR(320) NOT NULL,
  code_hash   VARCHAR(64)  NOT NULL,
  purpose     VARCHAR(16),
  attempts    INT          NOT NULL DEFAULT 0,
  expires_at  TIMESTAMP    NOT NULL,
  consumed_at TIMESTAMP,
  request_ip  VARCHAR(45),
  created_at  TIMESTAMP    NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS ix_evc_email_created ON email_verification_code (email, created_at);
CREATE INDEX IF NOT EXISTS ix_evc_ip_created    ON email_verification_code (request_ip, created_at);
CREATE INDEX IF NOT EXISTS ix_evc_email_consumed ON email_verification_code (email, consumed_at);
```

### 3.4 Mint+send core (`EmailCodeService.mintAndSend`)
Ports `_mint_and_send_email_code`. One critical section per email:
1. **Serialize per email** — Postgres `SELECT pg_advisory_xact_lock(hashtext(:email))`
   inside the `@Transactional` method so the cooldown-read → burn → insert is
   atomic (no two-live-codes race). On non-Postgres (H2 local), skip the lock.
2. **Resend cooldown** — newest row for this email must be ≥30s old, else `429`.
3. **Per-email hourly cap** — count rows for this email in the last hour; `≥5` ⇒ `429`.
4. **Per-IP hourly cap** — count rows with `request_ip = ip` in the last hour;
   `≥20` ⇒ `429`. (DB-backed so it survives Cloud Run multi-instance.)
5. **Single live code** — set `consumed_at = now` on all this email's
   unconsumed rows (an attacker can't stack multiple live codes; brute force
   stays ≤5 guesses / 10 min / email).
6. **Opportunistic sweep** — delete rows with `expires_at < now - 1 day`
   (no scheduler exists; sweep at write).
7. Generate code, store `sha256(code)` + `expires_at` + `purpose` + `request_ip`,
   commit.
8. Send via `EmailSender` (§5) with **no user lookup** — never resolve the `User`
   here, so request latency can't leak account existence (timing oracle). On a
   non-`sent` status, `502` unless the dev-unconfigured grace applies (§5).

### 3.5 Client IP derivation (`request_ip`)
Port VolunTails' `_client_ip`: behind GCP's LB, `X-Forwarded-For` is
`<client-supplied…>, <real-client>, <GFE>`; trust the **second-to-last** entry.
Single-entry header (local/tests) ⇒ that entry; else the socket peer
(`request.getRemoteAddr()`). The leftmost entry is attacker-controlled — never
trust it (prevents rotating a fake first hop to defeat the per-IP cap).

### 3.6 Verify core (`EmailCodeService.verifyAndConsume`)
Ports `_verify_email_code_row`:
1. Load the **latest unconsumed** row for `(email, purpose)` — `purpose` matches
   the requested flow OR is NULL (legacy grace). On Postgres use `SELECT … FOR
   UPDATE`.
2. If no row, expired, or `attempts >= 5` ⇒ generic `400 "Incorrect or expired code"`.
3. **Constant-time compare**: `MessageDigest.isEqual(storedHashBytes,
   sha256(code)Bytes)` — Java's documented constant-time digest comparison, the
   analogue of `hmac.compare_digest`. On mismatch: `attempts++`, commit, generic `400`.
4. On match: set `consumed_at = now`, commit (**consume before the caller acts** —
   single-use is a hard invariant; a token/email-write can never happen without a
   fresh consume in the same request).
5. Optional dev shortcut: only when an explicit `va-claim.auth.dev-mode=true`
   **and** SendGrid is unconfigured, accept the literal `123456`. Inert in prod
   (dev-mode is forbidden under the `cloud` profile by `FirebaseConfig`).

---

## 4. Custom-token mint + linking logic (convergence)

The whole point: **one** Firebase user carries both a verified email and a phone.
Two app-DB anchors already exist and are reused:
- `FirebaseAuthService.getOrCreateFirebaseUser` already relinks: when a verified
  Firebase token arrives whose uid is unknown but whose **email** matches an
  existing `User` row, it updates that row's `firebaseUid` instead of creating a
  duplicate. This is how a legacy Google/Apple user is NOT locked out.
- `FirebaseAuthService.resolveUser` is the single auth entry point.

### 4.1 Email path (`/verify`) — `FirebaseCustomTokenService.mintForVerifiedEmail`
```
verifyAndConsume(email, code, SIGNIN)            // throws generic 400 on failure
FirebaseUserRecord rec;
boolean isNewUser;
try {
    rec = firebaseAuth.getUserByEmail(email);    // returning user
    isNewUser = false;
} catch (FirebaseAuthException notFound) {
    rec = firebaseAuth.createUser(new CreateRequest()
            .setEmail(email).setEmailVerified(true));   // sign-in == sign-up, verified
    isNewUser = true;
}
String token = firebaseAuth.createCustomToken(rec.getUid());   // §6 IAM gotcha
boolean hasPhone = rec.getPhoneNumber() != null && !rec.getPhoneNumber().isBlank();
return { custom_token: token, isNewUser, hasPhone };
```
- Custom token carries the standard claims; because the Firebase user is created
  with `emailVerified=true`, the ID token minted after `signInWithCustomToken`
  carries `email_verified: true`, so the email-code sign-in lands **verified**
  (honored by the app's `getOrCreateFirebaseUser`).
- `isNewUser` is computed from "did we just create the record", **after** the
  code was verified — never before sending — so it can't be used to enumerate.

### 4.2 Phone path — pure Firebase (client)
Phone OTP is Firebase Phone Auth end-to-end (`signInWithPhoneNumber` + invisible
reCAPTCHA → `confirm`). No backend OTP for phone. After a phone-first new user is
signed in, the client collects + verifies an email via §4.3.

### 4.3 Attach (`/attach`) — `FirebaseCustomTokenService.attachEmailToCurrentUser`
```
// caller is authed; currentUid from the verified Bearer token
verifyAndConsume(email, code, ATTACH)                       // generic 400 on failure
if firebaseAuth.getUserByEmail(email) exists && uid != currentUid -> 409   // hijack guard (post-consume re-check too)
firebaseAuth.updateUser(new UpdateRequest(currentUid)
        .setEmail(email).setEmailVerified(true))            // current uid ONLY
appUser.email = email; appUser.emailVerified = true; save   // mirror to app DB
return { status:"verified", email, emailVerified:true }
```
Linking the **phone** onto an email-first user is done **client-side** with
Firebase `linkWithPhoneNumber` (§9.4) — there is no backend phone-link endpoint
(phone is a Firebase-native factor). The email-first new user therefore converges
as: created with email/emailVerified by `/verify` → client signs in →
`linkWithPhoneNumber` adds the phone factor to the **same** uid.

### 4.4 Convergence matrix
| First channel | New user does | Returning user does |
|---|---|---|
| **Email** | `/verify` creates user (email, verified) + `{custom_token, isNewUser:true, hasPhone:false}` → client `signInWithCustomToken` → then **`linkWithPhoneNumber`** to add phone. | `/verify` → `{isNewUser:false, hasPhone:?}` → signed in. Done (single channel). |
| **Phone** | Firebase phone sign-in (new uid, has phone) → client collects email → `request`+`/attach` sets email/verified on **this** uid. | Firebase phone sign-in → signed in. Done (single channel). |

In all new-user paths the end state is **one** Firebase uid with
`emailVerified=true` **and** a `phoneNumber`.

---

## 5. SendGrid sender abstraction + env + graceful-degrade

### 5.1 Abstraction
```java
public interface EmailSender {
    enum Status { SENT, SKIPPED, FAILED }
    record Result(Status status, String externalId, String errorMessage) {}
    Result sendHtml(String toEmail, String subject, String htmlBody);
}
```
`SendGridEmailSender` implements it with the official `com.sendgrid:sendgrid-java`
client (`SendGrid` + `Mail`, `from = va-claim.sendgrid.from-email`). Mirrors
VolunTails' `notification_service.send_email`:
- No `User` lookup, no notification-log row for OTP sends (no timing/enumeration
  oracle; no PII trail of who got a code).
- Returns `Result` so callers can branch on send failure.

### 5.2 Email templates (port `_email_code_html` / `_email_verify_html`)
Two HTML bodies, HTML-escape the code, big monospaced 6-digit display, "expires
in 10 minutes / ignore if you didn't request it" footer, VA Claim Path branding.

| Flow | Subject |
|---|---|
| `SIGNIN` | `Your VA Claim Path sign-in code` |
| `ATTACH` | `Verify your email for VA Claim Path` |

### 5.3 Env keys (`application.yml` → `va-claim.sendgrid.*`)
```yaml
va-claim:
  sendgrid:
    api-key:    ${SENDGRID_API_KEY:}          # empty ⇒ unconfigured
    from-email: ${SENDGRID_FROM_EMAIL:noreply@vaclaimpath.com}
```
`SENDGRID_API_KEY` is set as a Cloud Run secret in prod. `from-email` must be a
SendGrid-verified sender (`noreply@…`, matching VolunTails' convention).

### 5.4 Graceful-degrade
- **No API key** → `sendHtml` returns `SKIPPED`. The mint path then `502`s
  **unless** `va-claim.auth.dev-mode=true` (local) — in which case it tolerates
  `SKIPPED` and the `123456` dev shortcut (§3.6) covers local testing.
- **SendGrid throws / non-2xx** → `FAILED` → `502 "Email could not be sent…"`.
  The error message is logged server-side; the **code is never logged**.

---

## 6. IAM gotcha — `createCustomToken` on Cloud Run (FLAG PROMINENTLY)

`FirebaseAuth.createCustomToken(uid)` signs a JWT via the IAM Credentials
`signJwt` API using the Cloud Run **runtime service account**. That SA must hold
`roles/iam.serviceAccountTokenCreator` **on itself**, or signing fails with a
`403 PERMISSION_DENIED (signJwt)` — **this project has hit exactly this before.**

**Ops (one-time, required before `/verify` works in prod):**
```bash
SA="<cloud-run-runtime-sa>@<project>.iam.gserviceaccount.com"
gcloud iam service-accounts add-iam-policy-binding "$SA" \
  --member="serviceAccount:$SA" \
  --role="roles/iam.serviceAccountTokenCreator"
```
(Per MEMORY: when a deploy/IAM call hits `PERMISSION_DENIED`, activate the
deploy SA `~/.gcp/default-compute-sa.json` rather than `gcloud auth login`.)

**Code requirement — surface a clear error if minting fails:**
`FirebaseCustomTokenService` must catch the mint exception and, when the message
contains `signJwt` / `PERMISSION_DENIED` / `iam.serviceAccounts.signBlob`, log a
**loud, actionable** error naming the missing role and the runtime SA, then return
`502 {"detail":"Sign-in is temporarily unavailable (token signing). Please try
again shortly."}` to the client (never the raw IAM error). A generic
`502 "Could not complete sign-in"` for any other Admin failure.

---

## 7. BFF proxy routes & redirect safety (web)

Three new App Router routes proxy to the Spring backend (base URL from the
existing server-side backend constant used by `app/api/*`). They follow the
project's BFF convention (server-only, allowlist headers):

- `api/auth/email-code/request` & `api/auth/email-code/verify` — **pre-auth**:
  no session cookie required. Forward the inbound `X-Forwarded-For` (and
  `X-Real-IP` if present) so the backend's per-IP cap sees the real client IP.
  Do **not** forward arbitrary client headers.
- `api/auth/email-code/attach` — **authed**: convert the `cp_session`
  (`SESSION_COOKIE`) → `Authorization: Bearer` exactly like the other authed BFF
  routes, then forward.

Add the two **public** backend paths to `SecurityConfig.authFilter()`'s skip list
so the auth filter doesn't reject them (they are pre-auth):
```
path.equals("/api/auth/email-code/request") ||
path.equals("/api/auth/email-code/verify")
```
`/api/auth/email-code/attach` is **NOT** added — it must stay authed.

**Open-redirect safety:** `afterAuth()` continues to route through
`safeNext(?next=)` (`lib/safe-next.ts`, unchanged): only same-origin `"/x…"`
paths, never protocol-relative or absolute URLs. The login rewrite must keep using
`window.location.assign(safeNext(...))`.

---

## 8. firebase/session.ts reuse points

Reuse the existing module wholesale; **remove** the OAuth/magic-link exports that
the new login no longer calls (§10). Concretely:

- **Keep & reuse:** `getRecaptcha(containerId)`, `startPhoneSignIn`,
  `confirmPhoneCode` (phone OTP), `establish(user)` / `postSession` (cookie sync
  via `POST /api/session`), `watchIdToken` (token rotation), `signOut`,
  `watchAccount`/`AccountDetails` (profile screen shows email+phone).
- **Add two thin wrappers** (§9.4):
  - `signInWithCustomTokenAndEstablish(token)` — `signInWithCustomToken` →
    `establish(cred.user)`.
  - `linkPhone(verifier, e164, code)` — `linkWithPhoneNumber` → `confirm(code)` →
    `establish(currentUser)` (re-sync cookie after the link adds the factor).
- **Remove:** `signInWithGoogle`, `signInWithApple`, `sendEmailLink`,
  `isEmailLink`, `storedEmailForSignIn`, `completeEmailLink`, and the
  `EMAIL_KEY` machinery.

The httpOnly `cp_session` session model and `/api/session` route are **unchanged**.

---

## 9. UI state machine (web) — exact copy

### 9.1 Login layout (top → bottom, EXACTLY)
1. **Icon** — the shield mark (existing `<Icon name="shield" size={44} />`).
2. **Title** — `VA Claim Path`.
3. **Subtitle** — `Organize. Understand. Move forward.`
4. **ONE input** — a single text box, label `Phone or email`,
   placeholder `(555) 555-0123 or you@example.com`,
   `autoComplete="username"`, `inputMode` text, autofocus.
5. **Small instruction line** (muted, directly under the box):
   `We only use your phone and email for passwordless sign-in.`

**Removed entirely:** Continue with Google, Continue with Apple, password, the
"or use email" divider, the email magic-link button, the separate phone sheet.
Phone and email are the same one box.

Primary button copy: `Send me a code` (idle) / `Sending…` (busy).

### 9.2 `@`-branch + phone normalization (`lib/auth/otp-identifier.ts`)
Port VolunTails verbatim (the comment about why `@` must win is load-bearing):
```ts
// Detect EMAIL intent (any "@") BEFORE phone normalization. Stripping non-digits
// from an email that contains a 10/11-digit run would mis-route it to phone,
// fail Firebase, and lock out a valid-email user. So: any value containing "@"
// → email; only a "@"-free value is tried as a phone.
export function classify(raw: string): { kind: "email"; email: string }
  | { kind: "phone"; e164: string } | { kind: "invalid" } {
  const v = raw.trim();
  if (v.includes("@")) return { kind: "email", email: v.toLowerCase() };
  const e164 = toE164US(v);
  return e164 ? { kind: "phone", e164 } : { kind: "invalid" };
}

// US default: accept 10 digits → +1XXXXXXXXXX; 11 starting with 1 → +1…;
// already-"+" → keep if 8–15 digits; else null.
export function toE164US(raw: string): string | null { /* exact VolunTails impl */ }
```

### 9.3 States & transitions

State enum: `ENTER → CODE → (DUAL_COLLECT → DUAL_CODE)? → DONE`, plus `ERROR`
overlays inline.

```
ENTER
  ├─ classify(@) = invalid  → inline error: "Enter a phone number or email."
  ├─ classify = email → POST request{email} → CODE(method=email, target=email)
  └─ classify = phone → Firebase signInWithPhoneNumber(e164, invisibleRecaptcha)
                        → CODE(method=phone, target=e164)

CODE  (label "Verification code", placeholder "123456",
       inputMode numeric, autoComplete one-time-code)
  ├─ method=email → POST verify{email,code}
  │     → signInWithCustomTokenAndEstablish(custom_token)
  │     → isNewUser && !hasPhone ? DUAL_COLLECT(need=phone) : DONE
  └─ method=phone → confirmation.confirm(code) [Firebase signs in]
        → isNewUser(phone)? DUAL_COLLECT(need=email) : DONE
        // phone new-user detection: Firebase UserCredential
        // additionalUserInfo.isNewUser

DUAL_COLLECT(need=phone)   // email-first new user adds phone
  → one box "Phone number", placeholder "(555) 555-0123"
  → linkWithPhoneNumber(e164, invisibleRecaptcha) → DUAL_CODE(need=phone)

DUAL_COLLECT(need=email)   // phone-first new user adds email
  → one box "Email address", placeholder "you@example.com"
  → POST request{email, purpose:"attach"} → DUAL_CODE(need=email)

DUAL_CODE(need=phone) → confirm(code) [linkWithPhoneNumber confirm] → establish → DONE
DUAL_CODE(need=email) → POST attach{email,code} → DONE

DONE → afterAuth() → window.location.assign(safeNext(?next))
```

Returning users (incl. legacy Google/Apple, matched by identifier in
`getOrCreateFirebaseUser`) never enter `DUAL_*`: a single channel signs them in.

### 9.4 Exact copy strings
- Single-box label: `Phone or email`
- Single-box placeholder: `(555) 555-0123 or you@example.com`
- Instruction line (ENTER): `We only use your phone and email for passwordless sign-in.`
- Send button: `Send me a code` / busy `Sending…`
- Code step subtitle (email): `Enter the 6-digit code we just emailed you.`
- Code step subtitle (phone): `Enter the 6-digit code we just texted you.`
- Code label: `Verification code`  • placeholder `123456`
- Verify button: `Verify & sign in` / busy `Verifying…`
- "start over" link (code step): `Use a different phone or email`
- **DUAL_COLLECT (need second channel) heading:** `One more step`
- DUAL_COLLECT(phone) lead: `Add your phone number to finish setting up your account.`
- DUAL_COLLECT(email) lead: `Add your email to finish setting up your account.`
- **Passwordless note shown again in DUAL_COLLECT:**
  `We only use your phone and email for passwordless sign-in.`
- DUAL phone box label: `Phone number` • placeholder `(555) 555-0123`
- DUAL email box label: `Email address` • placeholder `you@example.com`
- DUAL send button: `Send me a code` / `Sending…`
- DUAL verify button: `Verify & finish` / `Verifying…`
- Invalid single box: `Enter a phone number or email.`
- Empty code: `Enter the 6-digit code we sent you.`

Errors: server `{detail}` is surfaced as-is (the backend 400/429 copy is already
user-facing); Firebase errors map through the kept `friendlyError`/`friendly`
mapper. Invisible reCAPTCHA target `<div id="recaptcha-container" />` stays on the
page; clear+rebuild the verifier on a phone retry (a verifier is single-use).

---

## 10. Cleanup / removals (part of the rewrite)
- `login/page.tsx`: delete Google/Apple/password/magic-link UI + `authDriver`
  calls + `PhoneSheet` (folded into the inline phone path), `SHOW_APPLE`.
- `firebase/session.ts`: remove the exports listed in §8.
- `friendly-error.ts`: keep; `MFA_MESSAGE` only fired on the removed popup path,
  so it becomes dead but harmless — leave it (one less behavioral change) or drop
  it in a follow-up. **Not** load-bearing for this rebuild.
- Native (`authDriver`/Capacitor) phone+email paths should reuse the same
  `otp-identifier.ts` + the email-code endpoints; native phone stays Firebase.
  (Native is out of scope for this spec's UI but the contract is shared.)
  **DONE 2026-07-01:** ported as the AuthDriver OTP contract — capacitor-ios-spec
  §B.1/§B.2 (Spring-direct email codes, plugin-native custom token, silent-APNs
  phone, no JS-SDK mirror). Pure OTP on native too: no Apple/Google (owner
  decision; guideline 4.8 untriggered) and the magic-link finish-sign-in page
  is deleted on both targets.

---

## 11. Security checklist (the review will hammer these)
- [ ] **Codes hashed only.** Store `sha256(code)` hex; plaintext never stored, never logged, never returned.
- [ ] **Constant-time compare.** `MessageDigest.isEqual` (not `String.equals`) on the hashes.
- [ ] **10-min TTL.** `expires_at = created_at + 10min`; expired ⇒ generic 400.
- [ ] **Max attempts = 5.** Per live code; exhausted ⇒ generic 400 (no separate "too many attempts" oracle distinct from wrong/expired).
- [ ] **Single live code per email.** Prior unconsumed codes burned on each mint.
- [ ] **Consume before act.** Code consumed in the same tx before any token mint / email write.
- [ ] **Generic failure.** One `400 "Incorrect or expired code"` for wrong/expired/none/exhausted — no enumeration via verify.
- [ ] **No enumeration on request.** Always `200 {ok:true}` for a syntactically valid email; no user lookup on the request path (no timing oracle); no notification-log row.
- [ ] **Per-identifier cap.** ≤5 codes / email / hour.
- [ ] **Per-IP cap.** ≤20 codes / IP / hour, DB-backed (survives Cloud Run multi-instance); IP from XFF second-to-last entry (LB-aware), leftmost never trusted.
- [ ] **Resend cooldown.** ≥30s between codes per email.
- [ ] **Token minted only for the exact verified identifier.** Custom token uid = uid that owns the just-verified email; never accept a uid/email pair from the client; never mint for an arbitrary address.
- [ ] **Attach can't hijack.** `attach` writes email/`emailVerified` on the **current** uid only; 409 (with post-consume re-check + unique-index translation to 409) if the email belongs to another user; `purpose=ATTACH` scoping so a sign-in code can't silently attach.
- [ ] **New-user detection is post-verify.** `isNewUser` computed after the code is verified — never pre-send → no enumeration.
- [ ] **IAM token-mint failure surfaced.** `serviceAccountTokenCreator` self-binding required; clear logged error + clean 502 (never raw IAM error) on `signJwt` 403.
- [ ] **Open-redirect safe.** `afterAuth` routes via `safeNext` — same-origin paths only.
- [ ] **Invisible reCAPTCHA on phone path.** Firebase `RecaptchaVerifier({size:"invisible"})`; verifier cleared+rebuilt on retry.
- [ ] **`emailVerified=true` claim** set on email-code users so sign-in lands verified (honored by `getOrCreateFirebaseUser`).
- [ ] **Public-path allowlist exact.** Only `…/request` and `…/verify` are exempt from the auth filter; `…/attach` stays authed.
- [ ] **Dev shortcut inert in prod.** `123456` only when `dev-mode=true` AND SendGrid unconfigured; dev-mode forbidden under `cloud` profile.
```
