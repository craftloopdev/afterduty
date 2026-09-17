"use client";

// Web AuthDriver (capacitor-ios-spec §B.2): the `session.ts` OTP behavior —
// invisible reCAPTCHA phone verify, BFF email-code proxy routes,
// postSession/clearSession cookie sync, watchIdToken rotation. The seam just
// gives it the shared `AuthDriver` shape so the login page is target-agnostic.
// The web build selects this driver (NATIVE === false).

import { onIdTokenChanged, type ConfirmationResult } from "firebase/auth";
import { getFirebaseAuth } from "@/lib/firebase/client";
import {
  getRecaptcha,
  resetRecaptcha,
  startPhoneSignIn,
  confirmPhoneSignIn,
  startLinkPhone as startLinkPhoneWeb,
  confirmLinkPhone,
  signInWithCustomTokenAndEstablish,
  signOut,
  watchAccount,
} from "@/lib/firebase/session";
import { emailCodePost, type Purpose, type VerifyResponse } from "./email-code-client";
import type {
  AuthDriver,
  AuthStatus,
  PasskeyCredential,
  PhoneConfirmation,
  VerifyEmailResult,
} from "./driver";
import {
  createPasskey,
  getPasskeyAssertion,
  isWebAuthnSupported,
  type AuthenticationOptionsJSON,
  type RegistrationOptionsJSON,
} from "./passkey";
import { withStepUp } from "./step-up";

// Same-origin BFF proxy routes (never Spring directly from the browser): the
// proxy forwards the client IP (request/verify) and converts the httpOnly
// session cookie → Bearer (attach).
const EMAIL_CODE_BASE = "/api/auth/email-code";
const WEBAUTHN_BASE = "/api/auth/webauthn";

/** JSON POST to a same-origin BFF webauthn route. Throws on non-2xx so the
 *  ceremonies surface a failure the login page can silently swallow. `extra`
 *  merges the step-up header on a guarded retry. */
async function webauthnFetch<T>(
  path: string,
  init: { method?: string; body?: unknown } = {},
  extra: Record<string, string> = {},
): Promise<T> {
  const headers: Record<string, string> = { Accept: "application/json", ...extra };
  if (init.body !== undefined) headers["Content-Type"] = "application/json";
  const res = await fetch(`${WEBAUTHN_BASE}${path}`, {
    method: init.method ?? "GET",
    headers,
    body: init.body !== undefined ? JSON.stringify(init.body) : undefined,
  });
  if (!res.ok) throw new Error(`webauthn ${path} ${res.status}`);
  const text = await res.text();
  return (text ? JSON.parse(text) : null) as T;
}

/**
 * The Yubico RP serializes ceremony options as the browser-ready
 * `{ publicKey: {...} }` envelope (what `navigator.credentials.create/get`
 * consumes and `PublicKeyCredential.parse*OptionsFromJSON` expects). Our
 * ceremony helpers — and the `allowCredentials` probe below — work on the INNER
 * options object, so unwrap the envelope here. Tolerates an already-inner body
 * so unit fixtures and any future shape both round-trip.
 *
 * Without this, `options.challenge` is `undefined` (it lives at
 * `options.publicKey.challenge`), `base64urlToBuffer(undefined)` throws a plain
 * `TypeError`, and enrollment dies before `register/verify` is ever called.
 */
function unwrapCeremony<T>(json: unknown): T {
  return json && typeof json === "object" && "publicKey" in (json as object)
    ? (json as { publicKey: T }).publicKey
    : (json as T);
}

export const webAuthDriver: AuthDriver = {
  async requestEmailCode(email: string, purpose: Purpose) {
    await emailCodePost(`${EMAIL_CODE_BASE}/request`, { email, purpose });
  },

  async verifyEmailCode(email: string, code: string): Promise<VerifyEmailResult> {
    const r = await emailCodePost<VerifyResponse>(`${EMAIL_CODE_BASE}/verify`, { email, code });
    // Exchange the custom token for a real session + sync the cookie.
    await signInWithCustomTokenAndEstablish(r.custom_token);
    return { isNewUser: r.isNewUser, hasPhone: r.hasPhone };
  },

  async attachEmailCode(email: string, code: string) {
    await emailCodePost(`${EMAIL_CODE_BASE}/attach`, { email, code });
  },

  async startPhone(phone: string, recaptchaContainerId = "recaptcha-container"): Promise<PhoneConfirmation> {
    const confirmation = await startPhoneSignIn(phone, getRecaptcha(recaptchaContainerId));
    return {
      confirm: (code: string) => confirmPhoneSignIn(confirmation, code),
    };
  },

  async startLinkPhone(phone: string, recaptchaContainerId = "recaptcha-container"): Promise<PhoneConfirmation> {
    const confirmation: ConfirmationResult = await startLinkPhoneWeb(
      phone,
      getRecaptcha(recaptchaContainerId),
    );
    return {
      confirm: async (code: string) => {
        await confirmLinkPhone(confirmation, code);
        return { isNewUser: false, hasEmail: true }; // linking never creates an account
      },
    };
  },

  resetPhoneVerifier: resetRecaptcha,

  // ── Passkeys (WebAuthn) ─────────────────────────────────────────────────────
  isPasskeySupported() {
    return isWebAuthnSupported();
  },

  async passkeyLogin(identifier: string): Promise<boolean> {
    // NEVER throw to the caller — every failure path (unsupported, no creds,
    // cancel, network) resolves false so the login page falls back to OTP.
    try {
      if (!isWebAuthnSupported()) return false;
      // assert/options resolves the user WITHOUT leaking existence — a decoy
      // returns valid options with empty allowCredentials, so we treat "no
      // credentials" as "nothing to do" and fall back to OTP.
      const options = unwrapCeremony<AuthenticationOptionsJSON>(
        await webauthnFetch<unknown>("/assert/options", {
          method: "POST",
          body: { identifier },
        }),
      );
      if (!options?.allowCredentials || options.allowCredentials.length === 0) return false;

      const assertion = await getPasskeyAssertion(options);
      const { custom_token } = await webauthnFetch<{ custom_token: string; credentialId: string }>(
        "/assert/verify",
        { method: "POST", body: { credential: assertion } },
      );
      // Same custom-token exchange + cookie sync as email-code verify (contract).
      await signInWithCustomTokenAndEstablish(custom_token);
      return true;
    } catch {
      return false;
    }
  },

  async enrollPasskey(nickname?: string): Promise<void> {
    // Authed: the BFF converts the session cookie → Bearer. This one is allowed
    // to throw — the enroll UI shows a friendly retry/not-now on failure.
    const options = unwrapCeremony<RegistrationOptionsJSON>(
      await webauthnFetch<unknown>("/register/options", {
        method: "POST",
        body: {},
      }),
    );
    const attestation = await createPasskey(options);
    await webauthnFetch("/register/verify", {
      method: "POST",
      body: { credential: attestation, ...(nickname ? { nickname } : {}) },
    });
  },

  async listPasskeys(): Promise<PasskeyCredential[]> {
    const r = await webauthnFetch<{ credentials: PasskeyCredential[] }>("/credentials");
    return r?.credentials ?? [];
  },

  async renamePasskey(id: string, nickname: string): Promise<void> {
    await webauthnFetch(`/credentials/${encodeURIComponent(id)}`, {
      method: "PATCH",
      body: { nickname },
    });
  },

  async revokePasskey(id: string): Promise<void> {
    // Sensitive (removing a factor) → step-up guarded. A `403 step_up_required`
    // transparently runs the ceremony and retries ONCE with the X-Step-Up header
    // via the shared seam. A still-non-2xx after that surfaces as an error.
    const res = await withStepUp((extra) =>
      fetch(`${WEBAUTHN_BASE}/credentials/${encodeURIComponent(id)}`, {
        method: "DELETE",
        headers: { Accept: "application/json", ...extra },
      }),
    );
    if (!res.ok) throw new Error(`webauthn revoke ${res.status}`);
  },

  // ── Biometric device-bound token (native only) ─────────────────────────────
  // Web has no biometric keychain — passkeys ARE the web factor-2. These stubs
  // keep the AuthDriver interface satisfied without pulling any native plugin into
  // the web bundle: the offer UI gates on `isBiometricAvailable()` (false here) so
  // enroll/deviceLogin are never reached; deviceLogin resolves false so a shared
  // launch path falls straight through to OTP.
  async isBiometricAvailable(): Promise<boolean> {
    return false;
  },
  async enrollDeviceCredential(): Promise<void> {
    throw new Error("biometric-unsupported");
  },
  async deviceLogin(): Promise<boolean> {
    return false;
  },

  signOut,
  watchAccount,
  watchAuth(cb: (status: AuthStatus) => void) {
    return onIdTokenChanged(getFirebaseAuth(), (user) => cb(user ? "signed-in" : "signed-out"));
  },
  getToken(opts) {
    const user = getFirebaseAuth().currentUser;
    if (!user) return Promise.reject(new Error("not-signed-in"));
    return user.getIdToken(opts?.forceRefresh ?? false);
  },
  getUid() {
    return getFirebaseAuth().currentUser?.uid ?? null;
  },
};
