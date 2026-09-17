"use client";

// Post-OTP biometric enrollment (auth-program-plan P1.4 / B2) — the NATIVE mirror
// of PasskeyEnroll. After a veteran signs in with OTP on a device that has no
// device credential, we offer a one-tap "Enable Face ID / Touch ID sign-in" so
// next launch they unlock with biometrics and skip the code entirely.
//
// PROGRESSIVE / NON-BLOCKING: purely additive. It is only OFFERED when the driver
// reports biometric hardware is available AND we have NOT already offered on this
// device (a `localStorage` flag, once per device). Both "Enable" and "Not now"
// call `onDone` so the login flow proceeds either way — enrollment can never trap
// the user. A failure/cancel shows a gentle retry, never an error that blocks
// sign-in. On web this never renders (isBiometricAvailable resolves false).

import { useState } from "react";
import { Button } from "@/components/ui/Button";
import { Icon } from "@/components/ui/Icon";
import { authDriver } from "@/lib/auth";
import styles from "./PasskeyEnroll.module.css";

// One prompt per device (P1.4): once a veteran has seen this on a device, we don't
// nag on every sign-in. (They can re-enable anytime from Profile in a later
// increment.) Separate key from the passkey offer so the two are independent.
const OFFERED_KEY = "vcp.biometric.enroll.offered";

/** Async capability gate: should we offer biometric enrollment right now? True
 *  only when the driver reports biometrics AND we've never offered on this device.
 *  SSR/localStorage-safe; never throws (resolves false on any error). */
export async function shouldOfferBiometricEnroll(): Promise<boolean> {
  try {
    if (window.localStorage.getItem(OFFERED_KEY) === "1") return false;
    return await authDriver.isBiometricAvailable();
  } catch {
    return false;
  }
}

function markOffered(): void {
  try {
    window.localStorage.setItem(OFFERED_KEY, "1");
  } catch {
    /* private mode / storage disabled — worst case we offer again later */
  }
}

/** Turn an enrollment failure into human copy. The device-login enroll can fail
 *  because the user backed out of the OS "allow Face ID" prompt, a keychain error,
 *  or a network failure minting the secret — all recoverable with a retry or skip. */
export function biometricEnrollMessage(): string {
  return "We couldn't set that up. You can try again or skip for now.";
}

/**
 * The enroll card. `onDone` fires after a successful enrollment OR a skip — the
 * caller then navigates into the app. Marks the device as offered on either action
 * so the prompt is one-and-done regardless of the outcome.
 */
export function BiometricEnroll({ onDone }: { onDone: () => void }) {
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function enroll() {
    setError(null);
    setBusy(true);
    try {
      await authDriver.enrollDeviceCredential();
      markOffered();
      onDone();
      // leave busy true through the navigation the caller runs
    } catch {
      // Unsupported / cancelled / failed — never block sign-in. Offer a retry;
      // "Not now" always escapes. Nothing is left half-stored (the driver clears
      // the keychain before storing, and a failed store leaves the server row
      // inert/unexchanged).
      setError(biometricEnrollMessage());
      setBusy(false);
    }
  }

  function skip() {
    markOffered();
    onDone();
  }

  return (
    <div className={styles.wrap}>
      <span className={styles.mark} aria-hidden="true">
        <Icon name="lock" size={30} stroke={2} />
      </span>
      <h2 className={styles.title}>Faster sign-in next time</h2>
      <p className={styles.body}>
        Turn on Face ID or Touch ID on this device so you can sign in without waiting for a code.
      </p>
      {error && (
        <div className={styles.error} role="alert">
          {error}
        </div>
      )}
      <Button variant="primary" size="lg" icon="lock" full loading={busy} disabled={busy} onClick={enroll}>
        {busy ? "Setting up…" : "Enable biometric unlock"}
      </Button>
      <button type="button" className={styles.skip} disabled={busy} onClick={skip}>
        Not now
      </button>
    </div>
  );
}
