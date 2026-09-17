// Biometric device-bound token facade (auth-program-plan P1.4 / B2). The ONLY
// module that touches `@capgo/capacitor-native-biometric`; the plugin import is
// lazy + guarded by the build-time `NATIVE` constant so it is fully tree-shaken
// out of the web bundle (no-op on web). Mirrors the splash/haptics/revenuecat
// facade pattern.
//
// SECURITY MODEL — the whole point of this file:
//   The device secret is a 256-bit random value minted server-side by
//   /api/auth/device/enroll; the plaintext is returned ONCE and stored HERE in
//   the platform keychain/keystore, HARDWARE-PROTECTED behind biometrics via
//   `accessControl: BIOMETRY_CURRENT_SET`. On iOS this attaches a SecAccessControl
//   to the Keychain item; on Android it wraps the value in a biometric-gated
//   Keystore key. The secret can then ONLY be read back through
//   `getSecureCredentials()`, which forces a FRESH biometric prompt AT THE
//   KEYCHAIN LAYER for every read — there is no code path that returns the secret
//   without a live biometric check. This is deliberately NOT a bare
//   `verifyIdentity()` gate followed by an unprotected `getCredentials()` read:
//   that pattern is bypassable on a rooted/jailbroken device (skip the JS gate,
//   read the keychain directly), which is exactly what the plugin README warns
//   against. `BIOMETRY_CURRENT_SET` (not `_ANY`) also invalidates the stored
//   secret if the enrolled biometric set changes (a new fingerprint/face added),
//   forcing OTP re-enrollment — a biometryChange can never silently keep unlocking.
//
// The backend only ever sees the secret's SHA-256 hash; this device holds the
// only plaintext copy, biometric-gated. Losing the device (or a biometry change)
// costs nothing — the row is revoked/rotated and the user falls back to OTP.

import { NATIVE } from "@/lib/platform";

// The keychain "server" namespace the device secret is filed under. A stable,
// app-specific value so enroll/login/clear all address the same item.
const SERVER = "com.afterduty.app.deviceLogin";

// Minimal structural mirror of the plugin's `BiometryType` / `AccessControl`
// enums + the `AvailableResult` shape we read. We avoid a hard type-import of the
// plugin at module scope so this file type-checks on the web target too (the
// plugin's types are still installed, but keeping the surface local documents
// exactly what the flow relies on and survives plugin minor churn). Runtime
// values come straight from the plugin.
//
// AccessControl.BIOMETRY_CURRENT_SET === 1 in the plugin enum.
const ACCESS_CONTROL_BIOMETRY_CURRENT_SET = 1;

/** What `enrollDevice` persists — the values `/enroll` returned once. */
export interface DeviceSecretBundle {
  /** base64url device secret (256-bit). Presented to /exchange to mint a session. */
  deviceSecret: string;
  /** The server-side credential row id, echoed back on /exchange. */
  deviceCredentialId: string;
}

/**
 * Lazy plugin handle — only resolved on native, never bundled on web. Dynamic
 * `import()` (not `require`) so the web build fully tree-shakes the native plugin
 * out AND the import is mockable in tests. Resolves null on web (the `if (!NATIVE)`
 * short-circuits before the import graph is touched) or if the plugin can't load.
 */
// eslint-disable-next-line @typescript-eslint/no-explicit-any
async function plugin(): Promise<any | null> {
  if (!NATIVE) return null;
  try {
    // Bound the lazy chunk load: in the native WebView a dynamic import can stall
    // (pending, never rejecting) — and every caller `await`s this. A stall here
    // must degrade to "no biometric" (→ OTP), never hang the caller. This is
    // load-bearing on the launch path: the pre-session deviceLogin awaits
    // hasDeviceSecret() → plugin(); a hung import would hold the splash forever
    // (App Store 2.1(a) "app never finishes loading").
    const mod = (await Promise.race([
      import("@capgo/capacitor-native-biometric"),
      new Promise<null>((resolve) => setTimeout(() => resolve(null), 2500)),
    ])) as { NativeBiometric: unknown } | null;
    return mod?.NativeBiometric ?? null;
  } catch {
    return null;
  }
}

/**
 * Is a STRONG biometric available and enrolled right now (Face ID / Touch ID /
 * strong fingerprint)? We require strong biometry specifically — a PIN/passcode
 * fallback or a "weak" face is NOT sufficient to gate the device secret. Returns
 * false on web, on missing hardware, when nothing is enrolled, or on any plugin
 * error. Never throws — the caller uses this purely to decide whether to OFFER
 * biometric unlock; a false answer just means "keep using OTP".
 */
export async function isStrongBiometricAvailable(): Promise<boolean> {
  const p = await plugin();
  if (!p) return false;
  // CAPACITOR-VERSION GATE (load-bearing security guarantee): the biometric-gated
  // secure store — `getSecureCredentials` + `accessControl: BIOMETRY_CURRENT_SET` —
  // only exists in the plugin's 8.x line, which needs Capacitor 8. On the current
  // Capacitor-7 build the pinned 7.6.0 plugin stores credentials WITHOUT a
  // SecAccessControl (readable without a live biometric = the bypassable pattern
  // we refuse). So if the secure-read API isn't present, biometric is reported
  // UNAVAILABLE — the feature never enrolls (never stores an ungated secret) and
  // the user stays on OTP. This lights up automatically once the app moves to
  // Capacitor 8 + plugin 8.x; the backend /device/* endpoints are already live.
  if (typeof p.getSecureCredentials !== "function") return false;
  try {
    // useFallback:false — a device passcode must NOT count as "biometric
    // available"; we only want to store the secret behind real biometrics.
    // Time-bounded: this feeds the post-login enroll OFFER, which must never
    // gate sign-in — a stalled native isAvailable() would otherwise hang the
    // login flow after the OTP code (App Store 2.1a). Timeout ⇒ "unavailable".
    const r = (await Promise.race([
      p.isAvailable({ useFallback: false }),
      new Promise<{ isAvailable?: boolean; strongBiometryIsAvailable?: boolean }>((resolve) =>
        setTimeout(() => resolve({ isAvailable: false }), 2500),
      ),
    ])) as { isAvailable?: boolean; strongBiometryIsAvailable?: boolean };
    return Boolean(r?.isAvailable) && Boolean(r?.strongBiometryIsAvailable);
  } catch {
    return false;
  }
}

/**
 * Persist the device secret bundle in the biometric-gated keychain. The secret is
 * the `password`; the credential id is the `username`, so a single read returns
 * both. `accessControl: BIOMETRY_CURRENT_SET` makes the item unreadable without a
 * fresh biometric and invalidates it on any biometric-enrollment change.
 *
 * Throws on a plugin failure so the enroll UI can surface a retry (and, on the
 * caller's side, the just-minted secret is discarded — never left half-stored).
 */
export async function storeDeviceSecret(bundle: DeviceSecretBundle): Promise<void> {
  const p = await plugin();
  if (!p) throw new Error("biometric-unsupported");
  // Overwrite any stale item first — setCredentials rejects a duplicate on some
  // platforms, and a re-enroll must always land the NEW secret.
  await clearDeviceSecret();
  await p.setCredentials({
    server: SERVER,
    username: bundle.deviceCredentialId,
    password: bundle.deviceSecret,
    accessControl: ACCESS_CONTROL_BIOMETRY_CURRENT_SET,
  });
}

/**
 * Read the device secret back behind a FRESH biometric prompt (the keychain
 * enforces the prompt — this is not a bypassable JS gate). Returns null when
 * there is no stored secret, the user cancels/fails the prompt, the biometric set
 * changed (BIOMETRY_CURRENT_SET invalidated the item), or on any plugin error —
 * so the caller can fall back to OTP without ever hard-failing. NEVER throws.
 *
 * @param reason reviewer-/user-facing prompt copy (Face ID sheet subtitle).
 */
export async function readDeviceSecret(reason: string): Promise<DeviceSecretBundle | null> {
  const p = await plugin();
  if (!p) return null;
  try {
    const creds = await p.getSecureCredentials({ server: SERVER, reason });
    if (!creds?.password || !creds?.username) return null;
    return { deviceSecret: creds.password, deviceCredentialId: creds.username };
  } catch {
    // Cancelled prompt / biometryChange invalidation / no item / hardware error —
    // all collapse to "no secret", and the caller falls back to OTP.
    return null;
  }
}

/** Is a device secret currently stored on this device? Cheap check (no prompt) so
 *  the launch flow can decide whether to attempt biometric login at all. */
export async function hasDeviceSecret(): Promise<boolean> {
  const p = await plugin();
  if (!p) return false;
  try {
    const r = await p.isCredentialsSaved({ server: SERVER });
    return Boolean(r?.isSaved);
  } catch {
    return false;
  }
}

/** Remove the stored device secret (on revoke, sign-out, or a re-enroll's
 *  overwrite). Idempotent and never throws — a missing item is success. */
export async function clearDeviceSecret(): Promise<void> {
  const p = await plugin();
  if (!p) return;
  try {
    await p.deleteCredentials({ server: SERVER });
  } catch {
    /* nothing stored / already gone — clearing is best-effort */
  }
}
