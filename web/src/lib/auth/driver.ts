// The AuthDriver seam (capacitor-ios-spec §B.2) — mirrors the ApiClient seam.
// Two implementations satisfy this interface, selected by the build-time
// `NATIVE` constant in ./index.ts:
//   - driver.web.ts    — the `session.ts` behavior: Firebase JS SDK, invisible
//                        reCAPTCHA phone verify, BFF email-code proxy routes,
//                        postSession/clearSession httpOnly-cookie sync.
//   - driver.native.ts — Capacitor Firebase plugin only (keychain persistence,
//                        silent-APNs phone verify), Spring-direct email-code
//                        calls; NO JS-SDK mirror, NO session-cookie calls.
//
// The contract is the single-box passwordless OTP login
// (passwordless-otp-auth-spec §9): email codes via the backend, phone codes via
// Firebase Phone Auth, dual-verify for brand-new users. No Google, no Apple, no
// password, no magic-link — on either target (App Store note: with no
// third-party login offered, guideline 4.8 does not apply, so no Sign in with
// Apple is required).
//
// `login/page.tsx` consumes the driver instead of importing `session.ts` or the
// email-code client directly, so one flow drives both targets.

/** Account details surfaced to the UI (email/phone/MFA display). */
export interface AccountDetails {
  email: string | null;
  phoneNumber: string | null;
  mfaFactors: string[];
}

/** Auth resolution for the native gate (§A.4): `undetermined` keeps the splash
 *  up; `signed-out` → /login; `signed-in` → load + render the app shell. */
export type AuthStatus = "undetermined" | "signed-in" | "signed-out";

/** Outcome of a verified email code (the custom-token exchange is internal to
 *  the driver): dual-verify signals for the login flow (spec §9.3). */
export interface VerifyEmailResult {
  isNewUser: boolean;
  hasPhone: boolean;
}

/** A pending phone verification. `confirm` completes sign-in (or linking) with
 *  the SMS code; for sign-ins, `isNewUser`/`hasEmail` drive the dual-verify
 *  branch (a phone-only account — new OR pre-cutover legacy — still owes the
 *  email channel). */
export interface PhoneConfirmation {
  confirm(code: string): Promise<{ isNewUser: boolean; hasEmail: boolean }>;
}

/** A passkey (WebAuthn credential) enrolled on the account, for the profile
 *  management list. NEVER carries the public key — only display metadata
 *  (auth-program-plan P1.3 MANAGE contract). */
export interface PasskeyCredential {
  id: string;
  nickname: string | null;
  createdAt: string;
  lastUsedAt: string | null;
  /** Coarse hint derived server-side from transports/aaguid (e.g. "This device",
   *  "Security key"); presentation only. */
  deviceHint: string | null;
}

export interface AuthDriver {
  // ── Email OTP (backend /auth/email-code/*; spec §2) ────────────────────────
  /** Request a 6-digit code be emailed (pre-auth; anti-enumeration server-side). */
  requestEmailCode(email: string, purpose: "signin" | "attach"): Promise<void>;
  /** Verify a sign-in code AND establish the session (custom-token exchange +
   *  web cookie / native keychain are driver-internal). */
  verifyEmailCode(email: string, code: string): Promise<VerifyEmailResult>;
  /** Attach a verified email to the CURRENT signed-in uid (authed; the
   *  phone-first dual-verify second step). */
  attachEmailCode(email: string, code: string): Promise<void>;

  // ── Phone OTP (Firebase Phone Auth; spec §9.2/§9.3) ────────────────────────
  /** Begin phone sign-in. The web driver needs an invisible-reCAPTCHA container
   *  id; native uses silent-APNs app verification and ignores it (§B.4). */
  startPhone(phone: string, recaptchaContainerId?: string): Promise<PhoneConfirmation>;
  /** Begin linking a phone to the CURRENT uid (the email-first dual-verify
   *  second step). Same verifier rules as `startPhone`. */
  startLinkPhone(phone: string, recaptchaContainerId?: string): Promise<PhoneConfirmation>;
  /** Discard the phone verifier so a retry rebuilds a fresh one (a reCAPTCHA
   *  verifier is single-use — spec §9.4). Native: no-op. */
  resetPhoneVerifier(): void;

  // ── Passkeys (WebAuthn; auth-program-plan P1.3) ────────────────────────────
  // ALL additive over OTP: every method is feature-detected and safe to call on
  // a browser without WebAuthn. `passkeyLogin` returns false (never throws to the
  // caller) when unsupported / no creds / cancelled, so the login page silently
  // falls back to OTP. Native today has no WebAuthn (biometric is a later
  // increment): `isPasskeySupported` is false and the ceremonies no-op.

  /** True iff this target can run a WebAuthn ceremony right now (web: the
   *  platform API is present; native: false). Cheap synchronous capability gate
   *  the UI uses before offering "Set up Face ID / Touch ID". */
  isPasskeySupported(): boolean;

  /** PRE-SESSION passkey login (Model B, identifier-first). Resolve options for
   *  `identifier`, and IF the account has credentials AND the browser supports
   *  WebAuthn, run the assertion and mint the app session (same custom-token
   *  exchange as email-code verify). Resolves `true` on a completed session,
   *  `false` when there was nothing to do or the user cancelled — NEVER throws
   *  to the caller, so login always falls back to OTP cleanly. */
  passkeyLogin(identifier: string): Promise<boolean>;

  /** AUTHED enrollment: register a passkey on the CURRENT signed-in account.
   *  Runs register/options → navigator.credentials.create → register/verify.
   *  Rejects on cancel/unsupported so the enroll UI can show "not now"/retry. */
  enrollPasskey(nickname?: string): Promise<void>;

  /** AUTHED: list the account's enrolled passkeys for the profile manager. */
  listPasskeys(): Promise<PasskeyCredential[]>;

  /** AUTHED: rename a passkey. */
  renamePasskey(id: string, nickname: string): Promise<void>;

  /** AUTHED + STEP-UP GUARDED: revoke a passkey. The DELETE may return
   *  `403 {code:step_up_required}`; the driver runs it through the step-up seam
   *  so the ceremony runs and the call retries once transparently. */
  revokePasskey(id: string): Promise<void>;

  // ── Biometric device-bound token (native only; auth-program-plan P1.4/B2) ──
  // The native mirror of passkeys: after an OTP session the veteran can enable
  // biometric unlock, minting a device secret that is stored biometric-gated in
  // the keychain; next launch a fresh Face ID / Touch ID prompt unlocks it and
  // exchanges it for a session WITHOUT an OTP. ALL additive and feature-detected,
  // exactly like passkeys — the web driver stubs every method (no-ops / false), so
  // the shared UI can call them unconditionally and web behaves as before.

  /** True iff this target can run a biometric device-login ceremony right now
   *  (native: strong biometric hardware present AND enrolled; web: false). Cheap
   *  async capability gate the UI uses before offering "Enable biometric unlock".
   *  Never throws — a failure resolves false. */
  isBiometricAvailable(): Promise<boolean>;

  /** AUTHED enrollment (native): mint a device secret on the CURRENT signed-in
   *  account via `/api/auth/device/enroll` and store it biometric-gated in the
   *  keychain (BIOMETRY_CURRENT_SET). Rejects on cancel/unsupported/failure so the
   *  enroll UI can show a retry / "not now"; on failure NOTHING is left half-stored
   *  and no half-enrolled row is trusted. Web: no-op stub (rejects "unsupported"),
   *  never reached because the UI gates on `isBiometricAvailable`. */
  enrollDeviceCredential(deviceName?: string): Promise<void>;

  /** PRE-SESSION biometric login (native). If a device secret is stored, force a
   *  FRESH biometric prompt to read it, exchange it at `/api/auth/device/exchange`
   *  for a session (same custom-token exchange as email-code/passkey verify), and
   *  sign in. Resolves `true` on a completed session, `false` when there was
   *  nothing stored / the user cancelled / the biometric set changed / anything
   *  failed — NEVER throws to the caller, so launch always falls back to OTP
   *  cleanly (never a lockout). Web: resolves false. */
  deviceLogin(): Promise<boolean>;

  // ── Session ────────────────────────────────────────────────────────────────
  /** Sign out everywhere (web: JS SDK + cookie; native: plugin keychain). */
  signOut(): Promise<void>;
  /** Subscribe to account details for display (email/phone/MFA). */
  watchAccount(cb: (d: AccountDetails | null) => void): () => void;
  /** Subscribe to the high-level auth status for the gate (§A.4). */
  watchAuth(cb: (status: AuthStatus) => void): () => void;
  /** Current Bearer ID token (web: JS SDK; native: plugin keychain). */
  getToken(opts?: { forceRefresh?: boolean }): Promise<string>;
  /** Current Firebase UID, or null when signed out. Used as the RevenueCat
   *  appUserID on native (§C.1). */
  getUid(): string | null;
}
