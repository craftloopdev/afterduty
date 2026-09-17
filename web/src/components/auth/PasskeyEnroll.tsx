"use client";

// Post-OTP passkey enrollment (auth-program-plan P1.3). After a veteran signs in
// with OTP on a session that has no passkey, we offer a one-tap "Set up Face ID /
// Touch ID / Windows Hello sign-in" so next time they skip the code entirely.
//
// PROGRESSIVE / NON-BLOCKING: this is purely additive. It is only rendered when
// `authDriver.isPasskeySupported()` and it has NOT already been offered on this
// device (a `localStorage` flag, once per device). Both "Set up" and "Not now"
// call `onDone` so the login flow proceeds to the app either way — enrollment can
// never trap the user. A ceremony failure/cancel shows a gentle retry, never an
// error that blocks sign-in.

import { useState } from "react";
import { Button } from "@/components/ui/Button";
import { Icon } from "@/components/ui/Icon";
import { authDriver } from "@/lib/auth";
import styles from "./PasskeyEnroll.module.css";

// One prompt per device (P1.3): once a veteran has seen this on a browser, we
// don't nag on every sign-in. They can still add a passkey anytime from Profile.
const OFFERED_KEY = "vcp.passkey.enroll.offered";

/** Should we offer enrollment right now? True only when WebAuthn is supported
 *  AND we've never offered on this device. SSR/localStorage-safe. */
export function shouldOfferPasskeyEnroll(): boolean {
  try {
    if (!authDriver.isPasskeySupported()) return false;
    return window.localStorage.getItem(OFFERED_KEY) !== "1";
  } catch {
    return false;
  }
}

function markOffered(): void {
  try {
    window.localStorage.setItem(OFFERED_KEY, "1");
  } catch {
    /* private mode / storage disabled — the worst case is we offer again later */
  }
}

/** Turn an enrollment failure into human copy that still names the real cause,
 *  so a device-side failure is diagnosable. WebAuthn surfaces a DOMException
 *  whose `name` is the actual signal:
 *   - NotAllowedError  → user cancelled / dismissed / activation lost (benign)
 *   - NotSupportedError→ no platform authenticator on this device
 *   - SecurityError    → RP-ID / origin mismatch (a real server-config bug)
 *   - InvalidStateError→ a passkey for this account already exists here
 */
export function passkeyEnrollMessage(e: unknown): string {
  // WebAuthn cancel/config failures are DOMExceptions whose `name` is the signal.
  // Everything else (a ceremony-options TypeError, a BFF/network Error) is a
  // plain Error — surface ITS name too so no failure is fully opaque.
  const name = e instanceof DOMException ? e.name : e instanceof Error ? e.name : "";
  switch (name) {
    case "NotAllowedError":
      return "No problem — sign-in wasn't confirmed. Tap “Set up faster sign-in” to try again, or skip for now.";
    case "NotSupportedError":
      return "This device doesn't support Face ID / passkey sign-in. You can keep using your code.";
    case "SecurityError":
      return "Passkey sign-in isn't configured for this site yet. Please skip for now — we're on it. (SecurityError)";
    case "InvalidStateError":
      return "You already have a passkey on this device. You're all set — skip for now.";
    default:
      return `We couldn't set that up. You can try again or skip for now.${name ? ` (${name})` : ""}`;
  }
}

/**
 * The enroll card. `onDone` fires after a successful enrollment OR a skip — the
 * caller then navigates into the app. Marks the device as offered on mount-driven
 * actions so the prompt is one-and-done regardless of the outcome.
 */
export function PasskeyEnroll({ onDone }: { onDone: () => void }) {
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function enroll() {
    setError(null);
    setBusy(true);
    try {
      await authDriver.enrollPasskey();
      markOffered();
      onDone();
      // leave busy true through the navigation the caller runs
    } catch (e) {
      // Unsupported / cancelled / failed — never block sign-in. Offer a retry;
      // "Not now" always escapes. Surface the specific reason so a device-side
      // failure is diagnosable (Face-ID cancel vs an actual config/RP error)
      // instead of an opaque "couldn't set that up".
      setError(passkeyEnrollMessage(e));
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
        Set up Face ID, Touch ID, or Windows Hello on this device so you can sign in without
        waiting for a code.
      </p>
      {error && (
        <div className={styles.error} role="alert">
          {error}
        </div>
      )}
      <Button variant="primary" size="lg" icon="lock" full loading={busy} disabled={busy} onClick={enroll}>
        {busy ? "Setting up…" : "Set up faster sign-in"}
      </Button>
      <button type="button" className={styles.skip} disabled={busy} onClick={skip}>
        Not now
      </button>
    </div>
  );
}
