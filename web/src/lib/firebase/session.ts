"use client";

// Web auth implementation (passwordless-otp-auth-spec §9) — the machinery behind
// `driver.web.ts`. Firebase JS SDK + invisible reCAPTCHA phone verify + the
// httpOnly `cp_session` cookie sync. Passwordless OTP only: no Google, no
// Apple, no password, no magic-link.

import {
  RecaptchaVerifier,
  signInWithPhoneNumber,
  signInWithCustomToken,
  getAdditionalUserInfo,
  onIdTokenChanged,
  onAuthStateChanged,
  multiFactor,
  signOut as fbSignOut,
  type ConfirmationResult,
  type User,
} from "firebase/auth";
import { getFirebaseAuth } from "./client";

export interface AccountDetails {
  email: string | null;
  phoneNumber: string | null;
  mfaFactors: string[]; // factorIds, e.g. ["phone"]
}

/** Subscribe to the current Firebase user's account details (email/phone/MFA). */
export function watchAccount(cb: (d: AccountDetails | null) => void): () => void {
  return onAuthStateChanged(getFirebaseAuth(), (user) => {
    if (!user) {
      cb(null);
      return;
    }
    let mfaFactors: string[] = [];
    try {
      mfaFactors = multiFactor(user).enrolledFactors.map((f) => f.factorId);
    } catch {
      mfaFactors = [];
    }
    cb({ email: user.email, phoneNumber: user.phoneNumber, mfaFactors });
  });
}

// ── BFF cookie sync ──────────────────────────────────────────────
async function postSession(idToken: string): Promise<void> {
  const res = await fetch("/api/session", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ idToken }),
  });
  if (!res.ok) {
    throw new Error(`Could not establish session (${res.status})`);
  }
}

export async function clearSession(): Promise<void> {
  await fetch("/api/session", { method: "DELETE" });
}

async function establish(user: User): Promise<void> {
  await postSession(await user.getIdToken());
}

// ── Phone OTP (invisible reCAPTCHA; spec §9.2) ──
// A verifier is single-use — `resetRecaptcha` clears it so a retry rebuilds a
// fresh one (spec §9.4 / security checklist).
let recaptcha: RecaptchaVerifier | undefined;
export function getRecaptcha(containerId: string): RecaptchaVerifier {
  if (!recaptcha) {
    recaptcha = new RecaptchaVerifier(getFirebaseAuth(), containerId, { size: "invisible" });
  }
  return recaptcha;
}

export function resetRecaptcha(): void {
  try {
    recaptcha?.clear();
  } catch {
    /* already torn down */
  }
  recaptcha = undefined;
}

export async function startPhoneSignIn(
  phone: string,
  verifier: RecaptchaVerifier,
): Promise<ConfirmationResult> {
  return signInWithPhoneNumber(getFirebaseAuth(), phone, verifier);
}

/** Complete a phone sign-in: confirm the SMS code, sync the cookie, and report
 *  whether this created a brand-new account (drives dual-verify — spec §9.3). */
export async function confirmPhoneSignIn(
  confirmation: ConfirmationResult,
  code: string,
): Promise<{ isNewUser: boolean; hasEmail: boolean }> {
  const cred = await confirmation.confirm(code);
  await establish(cred.user);
  return {
    isNewUser: getAdditionalUserInfo(cred)?.isNewUser ?? false,
    // Phone-only accounts (new OR pre-cutover legacy) still owe the email
    // channel — the login flow collects it right after this sign-in.
    hasEmail: Boolean(cred.user.email),
  };
}

// ── Passwordless OTP (passwordless-otp-auth-spec §9.4) ──
// Email-code sign-in: the backend mints a Firebase custom token for the exact
// verified email; exchange it for a real session and sync the cookie.
export async function signInWithCustomTokenAndEstablish(
  customToken: string,
): Promise<import("firebase/auth").UserCredential> {
  const cred = await signInWithCustomToken(getFirebaseAuth(), customToken);
  await establish(cred.user);
  return cred;
}

// Dual-verify: an email-first NEW user adds a phone to the SAME Firebase uid via
// the native phone factor (no backend phone OTP). Split into start/confirm to
// match the DUAL_COLLECT → DUAL_CODE UI steps.
export async function startLinkPhone(
  phone: string,
  verifier: RecaptchaVerifier,
): Promise<ConfirmationResult> {
  const user = getFirebaseAuth().currentUser;
  if (!user) throw new Error("not-signed-in");
  const { linkWithPhoneNumber } = await import("firebase/auth");
  return linkWithPhoneNumber(user, phone, verifier);
}

export async function confirmLinkPhone(
  confirmation: ConfirmationResult,
  code: string,
): Promise<void> {
  // confirm() resolves the linkWithPhoneNumber challenge, adding the phone factor
  // to the current uid; re-sync the cookie so the fresh token carries the phone.
  const cred = await confirmation.confirm(code);
  await establish(cred.user);
}

// ── Session lifecycle ──
export async function signOut(): Promise<void> {
  await fbSignOut(getFirebaseAuth());
  await clearSession();
}

/** Keep the httpOnly cookie fresh as Firebase auto-rotates the ID token (~1h). */
export function watchIdToken(): () => void {
  return onIdTokenChanged(getFirebaseAuth(), async (user) => {
    if (user) {
      try {
        await postSession(await user.getIdToken());
      } catch {
        /* transient; next change retries */
      }
    }
  });
}
