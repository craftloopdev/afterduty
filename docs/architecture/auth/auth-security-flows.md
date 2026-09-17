# VAClaimPath — Authentication & Security Flows

**Status:** Design spec for implementation (hand-off to Claude Code)
**Scope:** Native (iOS/Android via Capacitor) + Web (WebAuthn/passkeys)
**Data classification:** HIPAA PHI + PII, at rest on GCP under BAA → **MFA required**

> Items marked `[VERIFY]` depend on external/federal specifics that may have changed and must be confirmed against live docs before coding.

---

## 1. Principles

- **Passwordless.** No passwords are ever created, stored, or transmitted.
- **Two entry paths on the login screen:**
  1. `Continue with phone or email` (OTP + device factor)
  2. `Continue with ID.me` (ID.me does IAL2 identity proofing + its own MFA — see `idme-signin-spec.md`)
- **Factor model (phone/email path):**

  | Factor | Native | Web |
  |---|---|---|
  | 1 — possession | OTP via SMS **or** email (Firebase-backed) | OTP via SMS **or** email |
  | 2 — inherence + device-bound possession | Device biometric unlocking a device-bound key (Face ID / Touch ID / Android BiometricPrompt) | Passkey (WebAuthn platform authenticator: Windows Hello / Touch ID / Face ID / Android) |

  Note for the audit trail: a passkey/WebAuthn assertion is *itself* a possession+inherence factor. Combined with OTP this is defense-in-depth, not a single factor. Document this factor mapping in the compliance record.

- **SMS weakness:** SMS OTP is vulnerable to SIM-swap. Offer email OTP as an equal alternative, and for high-risk step-ups (see §4) prefer the biometric/passkey assertion over SMS.

---

## 2. Native flow (iOS / Android, Capacitor)

### 2.1 Enrollment (first run)
1. User enters phone number or email.
2. Firebase issues OTP.
   - SMS → Firebase Phone Auth (native).
   - Email OTP → **custom**: Cloud Function generates a 6-digit code, sends via your email provider (SendGrid/Postmark), stores a hashed code + expiry, verifies server-side. (Firebase's built-in email option is a magic link, which we are **not** using.) `[VERIFY]` provider choice.
3. User enters code → server verifies → session established.
4. App prompts: **"Enable biometric unlock?"** On accept, generate/store a device-bound credential (long-lived refresh token or a Secure Enclave / Keystore key) guarded by biometric.
   - iOS: Keychain item with `biometryCurrentSet` access control.
   - Android: `EncryptedSharedPreferences` / Keystore key with `setUserAuthenticationRequired(true)`.

### 2.2 Subsequent login
1. App launch → biometric prompt.
2. Biometric success → unlock device-bound credential → exchange for a fresh short-lived session token.
3. Biometric failure / not enrolled → fall back to OTP, then re-enroll biometric.

> **Honest mechanism:** biometric does not authenticate to the server directly. It unlocks a device-bound secret that is exchanged for a session. Spec it this way so the token model is correct.

### 2.3 DECISION REQUIRED — MFA strictness
Pick one default; both are defensible:

- **Model A — strict MFA every login:** OTP *and* biometric on every session. Higher assurance, more friction.
- **Model B — enrollment MFA + device trust (recommended):** OTP + biometric bind the device at enrollment; thereafter biometric alone unlocks the device-bound token (single gesture), with OTP **step-up** required on a new/foreign device and for sensitive actions (§4).

Recommendation: **Model B** — it matches the "intuitive for a 60-year-old, low friction" goal while remaining true MFA at the trust-establishing moments. Make this a config flag so it can be tightened later.

### 2.4 Capacitor plugin
- **Recommended:** `@capgo/capacitor-native-biometric` (maintained fork of `capacitor-native-biometric`). Provides `verifyIdentity` **and** biometric-guarded credential storage (`setCredentials` / `getCredentials`), which fits the device-bound-token design directly. `[VERIFY]` current maintenance/version.
- **Alternative (verify-only, no credential store):** `@aparajita/capacitor-biometric-auth`.
- **Do not use** `@capacitor/biometric` — not a real package.

---

## 3. Web flow (WebAuthn / passkeys)

No biometric API in the browser directly — use **WebAuthn passkeys** (platform authenticators handle the biometric/PIN locally).

### 3.1 Relying Party (RP) backend — required
Firebase does not do WebAuthn. Add a thin RP layer (Cloud Functions or your backend) alongside Firebase-for-OTP.
- **Recommended library:** `@simplewebauthn/server` + `@simplewebauthn/browser`.
- Configure: RP ID (your domain), origin, challenge generation, credential (public key) storage, assertion verification.

### 3.2 Enrollment
1. Phone/email → OTP → verify (factor 1).
2. RP starts registration ceremony → `navigator.credentials.create()` with server challenge.
3. Browser prompts platform authenticator (Windows Hello / Touch ID / Face ID). User confirms.
4. Store the returned public-key credential against the user.

### 3.3 Subsequent login
1. Phone/email → OTP → verify (factor 1).
2. RP starts authentication ceremony → `navigator.credentials.get()` with challenge.
3. Browser prompts biometric/PIN (e.g., Windows Hello, Touch ID) → signs assertion.
4. RP verifies signature → session.

### 3.4 Cross-device & platform notes
- **No phone-scanning after setup.** The passkey lives on the device (or syncs via iCloud Keychain / Google Password Manager). On a device with a synced passkey, it's OTP + local biometric — done.
- **New/foreign device with no passkey:** OTP verifies, then register a fresh passkey for that device. (Optional, later: CTAP2 hybrid transport / "use a passkey from another device" QR — keep out of v1 to control complexity.)
- **Windows:** passkey backed by Windows Hello or device PIN, unlocked locally.
- **Apple:** passkey stored in iCloud Keychain, unlocked by Face ID / Touch ID, syncs across the user's Apple devices.
- **Android/mobile Chrome:** device biometric or screen-lock credential.

---

## 4. Step-up (both platforms)
Require a **fresh** second-factor assertion (biometric/passkey, or OTP if biometric unavailable) — not just an existing session — before:
- Viewing or exporting claim/health documents.
- Initiating the VA Lighthouse import (`lighthouse-import-feature-request.md`).
- Changing contact channels (phone/email) or security settings.

---

## 5. Account recovery (must be defined — do not leave implicit)
True MFA means OTP-only can't be a permanent fallback, but recovery needs an auditable path:
- Issue **one-time recovery codes** at enrollment (user stores them), **or** require verification of *both* SMS and email to reset the second factor.
- On recovery: re-verify identity → invalidate old device-bound credential / passkeys → force re-enrollment of factor 2.
- Log every recovery event (who, when, channel, IP/device).

---

## 6. Session, token & audit policy
- Short-lived access tokens; refresh via the device-bound credential (native) / re-assertion (web).
- Bind sessions to device where possible; revoke on biometric/passkey removal.
- **Audit log** (PHI requirement): auth events, step-ups, recoveries, data views/exports, import initiations — immutable, retained per your HIPAA policy.
- All PHI/PII stays in the BAA-covered GCP store; no auth material or PHI in client logs.

---

## 7. Verification checklist before coding
- [ ] Confirm email-OTP provider + Cloud Function design `[VERIFY]`
- [ ] Confirm `@capgo/capacitor-native-biometric` maintenance/version `[VERIFY]`
- [ ] Decide **Model A vs Model B** (§2.3) and set the config flag
- [ ] Stand up SimpleWebAuthn RP layer + credential store
- [ ] Define recovery-code UX and storage
- [ ] Confirm audit-log fields meet your HIPAA policy
