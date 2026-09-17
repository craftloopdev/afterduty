"use client";

// Native AuthDriver (capacitor-ios-spec §B.1/§B.2/§B.4) — the single-box
// passwordless OTP contract, plugin-only. The Capacitor Firebase plugin's
// NATIVE layer is the sole source of truth:
//   - email codes  → Spring directly (`${apiBase()}/auth/email-code/*`; the BFF
//                    routes don't exist in the static export), then the custom
//                    token signs in via the plugin's NATIVE path so the keychain
//                    ID token (the DirectApiClient's Bearer source) is populated.
//   - phone codes  → the plugin's silent-APNs app verification (§B.4) — NEVER
//                    web reCAPTCHA, which is broken in WKWebView and the shape
//                    of flow behind the v1.0 2.1(a) rejection.
//   - identity     → a module-level cache fed by the plugin's `authStateChange`
//                    (getUid must be synchronous for the RevenueCat appUserID).
//
// There is deliberately NO JS-SDK mirroring: an SMS code is single-use, so a
// phone sign-in confirmed natively cannot also mint a JS-SDK credential — a
// half-mirrored setup is exactly how "signed in but no API token" bugs happen.
// `watchAccount`/`getUid` read the plugin instead. There is NO session cookie:
// `getToken` reads the native keychain ID token (survives WKWebView storage
// eviction); `signOut` clears the plugin. This module is only in the native
// bundle (selected by the `NATIVE` constant), so its plugin imports never reach
// the web build.

import { FirebaseAuthentication, type User } from "@capacitor-firebase/authentication";
import { apiBase, bearer } from "@/lib/api/direct";
import {
  clearDeviceSecret,
  hasDeviceSecret,
  isStrongBiometricAvailable,
  readDeviceSecret,
  storeDeviceSecret,
} from "@/lib/native/biometric";
import { emailCodePost, type Purpose, type VerifyResponse } from "./email-code-client";
import type {
  AccountDetails,
  AuthDriver,
  AuthStatus,
  PasskeyCredential,
  PhoneConfirmation,
  VerifyEmailResult,
} from "./driver";

// ── Biometric device-login constants ─────────────────────────────────────────
// The app currently ships iOS only (Capacitor 7, App Store v2.0); the contract's
// platform field is "ios|android". Hard-code "ios" here — when an Android target
// is added this becomes a Capacitor.getPlatform() read.
const PLATFORM = "ios";

// Reviewer-/user-facing copy for the fresh biometric prompt on the device-login
// keychain read (mirrors NSFaceIDUsageDescription).
const FACE_ID_REASON = "Sign in to After Duty with Face ID.";

/** A friendly default label for the enrolled device row (the manage list shows
 *  it). No PII — a generic device name is enough for "which device is this". */
function defaultDeviceName(): string {
  return "This iPhone";
}

// ── Plugin user cache ────────────────────────────────────────────────────────
// `getUid` is synchronous in the AuthDriver contract, but the plugin only
// exposes the user asynchronously. Subscribe once at module load; the cache is
// seeded by getCurrentUser and kept fresh by every authStateChange. Listener
// registration happens before any UI subscription (module init precedes render),
// so the cache is warm by the time the gate flips to signed-in.
let currentUser: User | null = null;
let seeded = false;
const accountWatchers = new Set<(d: AccountDetails | null) => void>();

function toAccount(user: User | null): AccountDetails | null {
  if (!user) return null;
  // The plugin's User carries no MFA enrollment info; the app ships no MFA
  // resolver either (§B.4) — an empty list matches the web display for
  // OTP-only accounts.
  return { email: user.email, phoneNumber: user.phoneNumber, mfaFactors: [] };
}

function setUser(user: User | null): void {
  currentUser = user;
  seeded = true;
  for (const cb of accountWatchers) cb(toAccount(user));
}

void FirebaseAuthentication.addListener("authStateChange", (change) => setUser(change.user));
void FirebaseAuthentication.getCurrentUser()
  .then((r) => {
    // Don't clobber a state change that raced ahead of the seed read.
    if (!seeded) setUser(r.user);
  })
  .catch(() => {
    if (!seeded) setUser(null);
  });

/** Re-read the native user (e.g. after a server-side email attach) so the cache
 *  and any account watchers see the updated profile. */
async function refreshUser(): Promise<void> {
  try {
    await FirebaseAuthentication.reload();
    const r = await FirebaseAuthentication.getCurrentUser();
    setUser(r.user);
  } catch {
    /* display freshness only — next authStateChange corrects it */
  }
}

// ── Phone verification (silent APNs — §B.4) ──────────────────────────────────
// The plugin resolves the verification id via the `phoneCodeSent` event, not a
// JS ConfirmationResult. Both sign-in (`signInWithPhoneNumber`) and linking
// (`linkWithPhoneNumber`) use the same event + `confirmVerificationCode`
// completion, so one adapter covers both.
async function startPhoneVerification(begin: () => Promise<unknown>): Promise<PhoneConfirmation> {
  const verificationId = await new Promise<string>((resolve, reject) => {
    let handle: { remove: () => void } | null = null;
    FirebaseAuthentication.addListener("phoneCodeSent", (event) => {
      handle?.remove();
      resolve(event.verificationId);
    })
      .then((h) => {
        handle = h;
      })
      .catch(reject);
    begin().catch((e) => {
      handle?.remove();
      reject(e);
    });
  });
  return {
    confirm: async (code: string) => {
      const result = await FirebaseAuthentication.confirmVerificationCode({
        verificationId,
        verificationCode: code,
      });
      setUser(result.user);
      return {
        isNewUser: result.additionalUserInfo?.isNewUser ?? false,
        hasEmail: Boolean(result.user?.email),
      };
    },
  };
}

export const nativeAuthDriver: AuthDriver = {
  // ── Email OTP → Spring directly (no BFF in the static export) ─────────────
  async requestEmailCode(email: string, purpose: Purpose) {
    // A sign-in code is pre-auth (Spring rate-limits by the real client IP it
    // sees directly). An ATTACH code is the dual-verify second step for a user
    // who is already signed in, and the backend only mints it as ATTACH when
    // the request proves that — unauthed, it is fail-safed down to SIGNIN and
    // /attach can never redeem it. So send the keychain token, as attach does.
    const bearer =
      purpose === "attach"
        ? (await FirebaseAuthentication.getIdToken({ forceRefresh: false })).token
        : undefined;
    await emailCodePost(`${apiBase()}/auth/email-code/request`, { email, purpose }, bearer);
  },

  async verifyEmailCode(email: string, code: string): Promise<VerifyEmailResult> {
    const r = await emailCodePost<VerifyResponse>(`${apiBase()}/auth/email-code/verify`, {
      email,
      code,
    });
    // NATIVE custom-token sign-in (§B.1): populates the keychain so getToken /
    // DirectApiClient work. (skipNativeAuth must stay false — the plugin rejects
    // custom-token sign-in otherwise.)
    const result = await FirebaseAuthentication.signInWithCustomToken({ token: r.custom_token });
    setUser(result.user);
    return { isNewUser: r.isNewUser, hasPhone: r.hasPhone };
  },

  async attachEmailCode(email: string, code: string) {
    // Authed: Bearer from the keychain (the BFF's cookie→Bearer conversion has
    // no native equivalent).
    const { token } = await FirebaseAuthentication.getIdToken({ forceRefresh: false });
    await emailCodePost(`${apiBase()}/auth/email-code/attach`, { email, code }, token);
    // The attach happens server-side (Admin SDK) — refresh the native user so
    // Profile shows the new email without a relaunch.
    await refreshUser();
  },

  // ── Phone OTP → silent-APNs app verification (§B.4) ───────────────────────
  startPhone: (phone: string) =>
    startPhoneVerification(() =>
      FirebaseAuthentication.signInWithPhoneNumber({ phoneNumber: phone }),
    ),

  startLinkPhone: (phone: string) =>
    startPhoneVerification(() =>
      FirebaseAuthentication.linkWithPhoneNumber({ phoneNumber: phone }),
    ),

  resetPhoneVerifier() {
    // Nothing to reset: native verification has no reCAPTCHA widget.
  },

  // ── Passkeys ────────────────────────────────────────────────────────────────
  // Native has no browser WebAuthn (biometric app-lock is a separate, later
  // increment). These stubs keep the AuthDriver interface satisfied without
  // pulling any web-only ceremony code into the static export: passkeys are
  // unsupported, so the login page never offers them and the profile section is
  // hidden. `passkeyLogin` resolves false (login falls back to OTP as always);
  // the authed ceremonies reject "unsupported" but are never reached because the
  // UI is gated on `isPasskeySupported()`.
  isPasskeySupported() {
    return false;
  },
  async passkeyLogin(): Promise<boolean> {
    return false;
  },
  async enrollPasskey(): Promise<void> {
    throw new Error("passkeys-unsupported");
  },
  async listPasskeys(): Promise<PasskeyCredential[]> {
    return [];
  },
  async renamePasskey(): Promise<void> {
    throw new Error("passkeys-unsupported");
  },
  async revokePasskey(): Promise<void> {
    throw new Error("passkeys-unsupported");
  },

  // ── Biometric device-bound token (§B2 / auth-program-plan P1.4) ─────────────
  async isBiometricAvailable(): Promise<boolean> {
    return isStrongBiometricAvailable();
  },

  async enrollDeviceCredential(deviceName?: string): Promise<void> {
    // Authed: Bearer from the keychain (same source as attach). Mint a device
    // secret server-side, then store {deviceSecret, deviceCredentialId} in the
    // biometric-gated keychain. The plaintext secret is returned ONCE by /enroll
    // and never leaves this function except into the secure store.
    const { token } = await FirebaseAuthentication.getIdToken({ forceRefresh: false });
    const res = await emailCodePost<{ deviceSecret: string; deviceCredentialId: string }>(
      `${apiBase()}/auth/device/enroll`,
      { deviceName: deviceName ?? defaultDeviceName(), platform: PLATFORM },
      token,
    );
    // Store behind BIOMETRY_CURRENT_SET. If this throws (keychain error / user
    // backed out of the enable prompt) it propagates so the enroll UI shows a
    // retry; nothing is left half-stored (storeDeviceSecret clears first), and the
    // server row simply goes unused (a never-exchanged secret is inert).
    await storeDeviceSecret({
      deviceSecret: res.deviceSecret,
      deviceCredentialId: res.deviceCredentialId,
    });
  },

  async deviceLogin(): Promise<boolean> {
    // PRE-SESSION. NEVER throws — every failure resolves false so launch falls
    // back to OTP (never a lockout).
    try {
      // Nothing stored (or not on native) → nothing to do.
      if (!(await hasDeviceSecret())) return false;

      // FRESH biometric prompt gates the keychain read. A cancel / biometryChange
      // invalidation / hardware error returns null → OTP fallback. On a
      // biometryChange the stored secret is already invalidated by the OS; drop the
      // dead item so we don't re-prompt against it next launch.
      const bundle = await readDeviceSecret(FACE_ID_REASON);
      if (!bundle) {
        // Distinguish "user cancelled" (item still valid) from "item gone/invalid".
        // If the item vanished (biometryChange), stop trusting it.
        if (!(await hasDeviceSecret())) await clearDeviceSecret();
        return false;
      }

      // Exchange the secret for a session (PUBLIC route — no Bearer). Same
      // custom_token shape as email-code / passkey verify.
      const { custom_token } = await emailCodePost<{ custom_token: string }>(
        `${apiBase()}/auth/device/exchange`,
        { deviceCredentialId: bundle.deviceCredentialId, deviceSecret: bundle.deviceSecret },
      );

      // NATIVE custom-token sign-in (§B.1): populates the keychain ID token so
      // getToken / DirectApiClient work — identical to the email-code path.
      const result = await FirebaseAuthentication.signInWithCustomToken({ token: custom_token });
      setUser(result.user);
      return true;
    } catch {
      // A revoked/absent credential returns a 4xx from /exchange (surfaced as
      // EmailCodeError). The secret on this device is now useless — drop it so we
      // don't keep prompting for a dead credential, and fall back to OTP.
      await clearDeviceSecret();
      return false;
    }
  },

  // ── Session ────────────────────────────────────────────────────────────────
  async signOut() {
    // Auth-audit the sign-out (EVENT_SIGN_OUT) while the token is still valid —
    // best-effort: signing out must never fail because the audit call did.
    try {
      const token = await bearer();
      await fetch(`${apiBase()}/auth/sign-out`, {
        method: "POST",
        headers: { Authorization: `Bearer ${token}` },
        signal: AbortSignal.timeout(3000),
      });
    } catch {
      /* audit-only; ignore */
    }
    await FirebaseAuthentication.signOut();
    setUser(null);
    // Cross-account hygiene: never leave one account's device secret readable for
    // the next sign-in on this device. Re-enroll is a one-tap opt-in after OTP.
    await clearDeviceSecret();
  },

  watchAccount(cb: (d: AccountDetails | null) => void): () => void {
    accountWatchers.add(cb);
    if (seeded) cb(toAccount(currentUser));
    return () => {
      accountWatchers.delete(cb);
    };
  },

  watchAuth(cb: (status: AuthStatus) => void): () => void {
    // The NATIVE plugin is the source of truth for persistence (keychain).
    cb("undetermined");
    const handlePromise = FirebaseAuthentication.addListener("authStateChange", (change) => {
      cb(change.user ? "signed-in" : "signed-out");
    });
    // Seed the initial state from the plugin (the listener only fires on change).
    FirebaseAuthentication.getCurrentUser()
      .then((r) => cb(r.user ? "signed-in" : "signed-out"))
      .catch(() => cb("signed-out"));
    return () => {
      void handlePromise.then((h) => h.remove());
    };
  },

  async getToken(opts) {
    // Native keychain ID token (auto-refreshing). The DirectApiClient's 401
    // retry passes forceRefresh:true (§B.3).
    const r = await FirebaseAuthentication.getIdToken({ forceRefresh: opts?.forceRefresh ?? false });
    return r.token;
  },

  getUid() {
    // From the plugin cache — the same Firebase UID the backend keys on; used as
    // the RevenueCat appUserID (§C.1).
    return currentUser?.uid ?? null;
  },
};
