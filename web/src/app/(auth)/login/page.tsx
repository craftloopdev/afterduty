"use client";

import { useRef, useState } from "react";
import { useRouter } from "next/navigation";
import Link from "next/link";
import { Icon } from "@/components/ui/Icon";
import { Button } from "@/components/ui/Button";
import { LegalFooter } from "@/components/auth/LegalFooter";
import { PasskeyEnroll, shouldOfferPasskeyEnroll } from "@/components/auth/PasskeyEnroll";
import { BiometricEnroll, shouldOfferBiometricEnroll } from "@/components/auth/BiometricEnroll";
import { authDriver } from "@/lib/auth";
import type { PhoneConfirmation } from "@/lib/auth";
import { EmailCodeError } from "@/lib/auth/email-code-client";
import { classify, toE164US } from "@/lib/auth/otp-identifier";
import { NATIVE } from "@/lib/platform";
import { safeNext } from "@/lib/safe-next";
import { friendlyError } from "./friendly-error";
import styles from "./login.module.css";

// A stalled verify (native plugin confirmVerificationCode / email-code network)
// must not leave the code screen spinning forever after the user types the code
// (App Store 2.1a). Bound it so a hang surfaces as a normal, retryable error.
function verifyWithin<T>(p: Promise<T>): Promise<T> {
  return Promise.race([
    p,
    new Promise<T>((_, reject) =>
      setTimeout(() => reject(new Error("Verification timed out. Please try again.")), 20_000),
    ),
  ]);
}

// Single-box passwordless OTP login (passwordless-otp-auth-spec §9). One "Phone
// or email" box: any "@" → backend email-code (request → verify → custom-token
// session); otherwise → Firebase Phone Auth. Brand-new users dual-verify the
// SECOND channel and converge on ONE Firebase uid carrying both
// email/emailVerified and a phone. Returning users (incl. legacy Google/Apple,
// matched by identifier server-side) sign in with a single channel and never
// enter the dual steps.
//
// No Google, no Apple, no password, no magic-link — on BOTH targets. All auth
// I/O goes through the AuthDriver seam (capacitor-ios-spec §B.2): web = BFF
// email-code proxy + invisible reCAPTCHA + session cookie; native = Spring
// directly + silent-APNs phone verify + keychain, no cookie.

type Method = "email" | "phone";
type Need = "phone" | "email"; // which SECOND channel a new user still owes
// `enroll` is the OPTIONAL post-sign-in passkey offer (web) and `bioEnroll` its
// NATIVE biometric twin (progressive): the session already exists at that point,
// so both are purely a "faster next time" upsell that always ends in navigation
// (set up OR skip). Only one is ever offered on a given target — passkeys on web,
// biometric device-login on native.
type Step = "enter" | "code" | "dualCollect" | "dualCode" | "enroll" | "bioEnroll";

/** Whether the driver exposes passkeys on this build/target AND the browser
 *  supports them. Guarded so a test stub / non-WebAuthn browser behaves exactly
 *  like today — the passkey code is strictly additive. */
function passkeysAvailable(): boolean {
  try {
    return typeof authDriver.isPasskeySupported === "function" && authDriver.isPasskeySupported();
  } catch {
    return false;
  }
}

// The invisible-reCAPTCHA mount point for the WEB phone verifier (native
// ignores it — §B.4).
const RECAPTCHA_ID = "recaptcha-container";

// VolunTails copy strings (spec §9.4).
const INSTRUCTION = "We only use your phone and email for passwordless sign-in.";
const RESEND_COOLDOWN_SEC = 30;

// Cold-landing value bullets (P1-17): tell a veteran what this is BEFORE asking
// for a phone/email. Copy additions only — the OTP flow is untouched.
const VALUE_BULLETS = [
  "Organize your medical evidence",
  "Understand your conditions and what VA looks for",
  "See exactly what to do next",
];
const FREE_TO_START = "Free to start — no card required.";

export default function LoginPage() {
  const router = useRouter();
  const [step, setStep] = useState<Step>("enter");
  const [method, setMethod] = useState<Method>("email");

  // The raw single-box value, and the canonical target we sent the first code to
  // (E.164 phone or lower-cased email) — used in verify + the code-step copy.
  const [value, setValue] = useState("");
  const [target, setTarget] = useState("");
  const [code, setCode] = useState("");

  // Dual-verify: which channel is still owed + its own input. Brand-new users
  // MUST complete it (onboarding collects both sign-in channels); returning
  // accounts missing a channel (abandoned dual-verify, pre-cutover legacy) get
  // the same step with a "Skip for now" escape so they're never locked out.
  const [need, setNeed] = useState<Need | null>(null);
  const [canSkipDual, setCanSkipDual] = useState(false);
  const [secondValue, setSecondValue] = useState("");
  const [secondTarget, setSecondTarget] = useState("");
  const [secondCode, setSecondCode] = useState("");

  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const confirmationRef = useRef<PhoneConfirmation | null>(null);

  // Web: full top-level navigation (not a soft push) — the fresh first-party
  // session cookie reliably rides a real navigation even under Safari ITP.
  // Native: no cookie to carry, and a hard assign() would re-boot the whole
  // WKWebView — a router replace is correct. Both honor same-origin ?next= via
  // safeNext (open-redirect-safe, spec §7).
  const afterAuth = () => {
    const dest = safeNext(new URLSearchParams(window.location.search).get("next"));
    if (NATIVE) router.replace(dest);
    else window.location.assign(dest);
  };

  // After a completed OTP sign-in: OFFER faster-sign-in enrollment once per device
  // (progressive — skippable, never blocks). Web offers PASSKEYS; native offers
  // BIOMETRIC device-login. If neither applies (unsupported, already offered),
  // navigate straight through exactly as before. The biometric check is async
  // (a plugin capability query), so we resolve it before deciding.
  const proceed = () => {
    if (passkeysAvailable() && shouldOfferPasskeyEnroll()) {
      setBusy(false);
      setStep("enroll");
      return;
    }
    // Native biometric offer (device-login is native-only). Gated on NATIVE so the
    // WEB path stays exactly as before — synchronous navigation, no capability
    // query — while native does an async plugin check before deciding to offer.
    if (NATIVE) {
      // The biometric-enroll OFFER is optional and must NEVER gate sign-in. Bound
      // the capability query so a stalled native call can't hang here after the
      // code verifies (App Store 2.1a) — fall straight through into the app if it
      // doesn't answer in time.
      void Promise.race([
        shouldOfferBiometricEnroll(),
        new Promise<boolean>((resolve) => setTimeout(() => resolve(false), 2500)),
      ])
        .then((offer) => {
          if (offer) {
            setBusy(false);
            setStep("bioEnroll");
          } else {
            afterAuth();
          }
        })
        .catch(afterAuth);
      return;
    }
    afterAuth();
  };

  // Surface the backend's user-facing {detail} as-is; map Firebase errors through
  // the kept friendlyError mapper. Never leak a raw error.message.
  function message(e: unknown, fallback: string): string {
    if (e instanceof EmailCodeError && e.detail) return e.detail;
    return friendlyError(e) || fallback;
  }

  // ── Resend cooldown (shared across the two code steps) ────────────────────
  const [cooldownUntil, setCooldownUntil] = useState(0);
  const [now, setNow] = useState(() => Date.now());
  const cooldownLeft = Math.max(0, Math.ceil((cooldownUntil - now) / 1000));
  function startCooldown() {
    const until = Date.now() + RESEND_COOLDOWN_SEC * 1000;
    setCooldownUntil(until);
    const tick = () => {
      setNow(Date.now());
      if (Date.now() < until) setTimeout(tick, 500);
    };
    setTimeout(tick, 500);
  }

  // ── ENTER: send first code to whichever was entered ───────────────────────
  async function sendFirstCode() {
    setError(null);
    const c = classify(value);
    if (c.kind === "invalid") {
      setError("Enter a phone number or email.");
      return;
    }
    setBusy(true);

    // Passkey-first (Model B, identifier-first — auth-program-plan P1.3). If this
    // account has a passkey on this device and the browser supports WebAuthn,
    // prompt it and sign in WITHOUT an OTP. `passkeyLogin` NEVER throws and
    // resolves false when there's nothing to do / the user cancels / anything
    // fails, so we SILENTLY fall through to the OTP flow below — login is never
    // blocked. This account already has a passkey, so we skip the enroll offer
    // and navigate straight in.
    if (passkeysAvailable() && typeof authDriver.passkeyLogin === "function") {
      const identifier = c.kind === "email" ? c.email : c.e164;
      try {
        if (await authDriver.passkeyLogin(identifier)) {
          afterAuth();
          return; // leave busy true through the navigation
        }
      } catch {
        /* additive — any failure falls through to OTP */
      }
    }

    try {
      if (c.kind === "email") {
        await authDriver.requestEmailCode(c.email, "signin");
        setMethod("email");
        setTarget(c.email);
      } else {
        confirmationRef.current = await authDriver.startPhone(c.e164, RECAPTCHA_ID);
        setMethod("phone");
        setTarget(c.e164);
      }
      setCode("");
      setStep("code");
      startCooldown();
    } catch (e) {
      setError(message(e, "Couldn't send a code. Please try again."));
      if (c.kind === "phone") authDriver.resetPhoneVerifier();
    } finally {
      setBusy(false);
    }
  }

  // ── CODE: verify the first code (both methods funnel to a session) ────────
  async function verifyFirstCode() {
    const c = code.trim();
    if (!c) {
      setError("Enter the 6-digit code we sent you.");
      return;
    }
    setError(null);
    setBusy(true);
    try {
      if (method === "email") {
        const { isNewUser, hasPhone } = await verifyWithin(authDriver.verifyEmailCode(target, c));
        if (!hasPhone) {
          // The account owes its phone channel — brand-new users always land
          // here; returning phone-less accounts (dual-verify abandoned, or
          // pre-cutover legacy) get the same step with a Skip escape.
          setNeed("phone");
          setCanSkipDual(!isNewUser);
          setSecondValue("");
          setSecondCode("");
          setStep("dualCollect");
          // Latent bug fix: busy stayed true into dualCollect, rendering the
          // second-channel form fully disabled — the veteran was STUCK here.
          setBusy(false);
        } else {
          proceed();
          return; // leave busy true through the navigation (or into the enroll step)
        }
      } else {
        const confirmation = confirmationRef.current;
        if (!confirmation) {
          setError("Request a code first.");
          setBusy(false);
          return;
        }
        const { isNewUser, hasEmail } = await verifyWithin(confirmation.confirm(c));
        if (!hasEmail) {
          // Phone-only account (new or legacy) → collect the email channel.
          setNeed("email");
          setCanSkipDual(!isNewUser);
          setSecondValue("");
          setSecondCode("");
          setStep("dualCollect");
          setBusy(false); // same stuck-form fix as the email-first branch
        } else {
          proceed();
          return;
        }
      }
    } catch (e) {
      setError(message(e, "Couldn't verify that code. Try again."));
      setBusy(false);
    }
  }

  // ── DUAL_COLLECT: brand-new user adds the SECOND channel ──────────────────
  async function sendSecondCode() {
    setError(null);
    if (need === "phone") {
      const e164 = toE164US(secondValue);
      if (!e164) {
        setError("Enter a phone number or email.");
        return;
      }
      setBusy(true);
      try {
        confirmationRef.current = await authDriver.startLinkPhone(e164, RECAPTCHA_ID);
        setSecondTarget(e164);
        setSecondCode("");
        setStep("dualCode");
        startCooldown();
      } catch (e) {
        setError(message(e, "Couldn't send a code. Please try again."));
        authDriver.resetPhoneVerifier();
      } finally {
        setBusy(false);
      }
    } else {
      const v = secondValue.trim().toLowerCase();
      if (!v.includes("@")) {
        setError("Enter a phone number or email.");
        return;
      }
      setBusy(true);
      try {
        await authDriver.requestEmailCode(v, "attach");
        setSecondTarget(v);
        setSecondCode("");
        setStep("dualCode");
        startCooldown();
      } catch (e) {
        setError(message(e, "Couldn't send a code. Please try again."));
      } finally {
        setBusy(false);
      }
    }
  }

  // ── DUAL_CODE: verify the second channel → done ───────────────────────────
  async function verifySecondCode() {
    const c = secondCode.trim();
    if (!c) {
      setError("Enter the 6-digit code we sent you.");
      return;
    }
    setError(null);
    setBusy(true);
    try {
      if (need === "phone") {
        const confirmation = confirmationRef.current;
        if (!confirmation) {
          setError("Request a code first.");
          setBusy(false);
          return;
        }
        await confirmation.confirm(c);
      } else {
        await authDriver.attachEmailCode(secondTarget, c);
      }
      proceed();
      // leave busy true through the navigation (or into the enroll step)
    } catch (e) {
      setError(message(e, "Couldn't verify that code. Try again."));
      setBusy(false);
    }
  }

  // ── Resend (from a code step) ─────────────────────────────────────────────
  async function resend() {
    if (cooldownLeft > 0 || busy) return;
    setError(null);
    setBusy(true);
    try {
      if (step === "code") {
        if (method === "email") {
          await authDriver.requestEmailCode(target, "signin");
        } else {
          authDriver.resetPhoneVerifier();
          confirmationRef.current = await authDriver.startPhone(target, RECAPTCHA_ID);
        }
      } else {
        // dualCode
        if (need === "phone") {
          authDriver.resetPhoneVerifier();
          confirmationRef.current = await authDriver.startLinkPhone(secondTarget, RECAPTCHA_ID);
        } else {
          await authDriver.requestEmailCode(secondTarget, "attach");
        }
      }
      startCooldown();
    } catch (e) {
      setError(message(e, "Couldn't resend a code. Please try again."));
    } finally {
      setBusy(false);
    }
  }

  // ── Edit identifier (start over from the code step) ───────────────────────
  function startOver() {
    setStep("enter");
    setCode("");
    setError(null);
    confirmationRef.current = null;
    authDriver.resetPhoneVerifier();
  }

  function onSubmit(e: React.FormEvent) {
    e.preventDefault();
    if (step === "enter") void sendFirstCode();
    else if (step === "code") void verifyFirstCode();
    else if (step === "dualCollect") void sendSecondCode();
    else if (step === "dualCode") void verifySecondCode();
  }

  const codeSubtitle =
    method === "phone"
      ? "Enter the 6-digit code we just texted you."
      : "Enter the 6-digit code we just emailed you.";

  // Post-sign-in passkey enroll (progressive): the session already exists here, so
  // this branch is a standalone "faster next time" offer — no OTP form, no value
  // bullets. Both actions in PasskeyEnroll call `afterAuth` via onDone.
  if (step === "enroll") {
    return (
      <div className={styles.wrap}>
        <div className={styles.brand}>
          <span className={styles.mark}>
            <Icon name="shield" size={44} stroke={2} />
          </span>
          <h1 className={styles.title}>After Duty</h1>
        </div>
        <PasskeyEnroll onDone={afterAuth} />
        <LegalFooter />
      </div>
    );
  }

  // Native post-sign-in biometric enroll (progressive): the session already exists
  // here, so this is a standalone "faster next time" offer. Both actions in
  // BiometricEnroll call `afterAuth` via onDone.
  if (step === "bioEnroll") {
    return (
      <div className={styles.wrap}>
        <div className={styles.brand}>
          <span className={styles.mark}>
            <Icon name="shield" size={44} stroke={2} />
          </span>
          <h1 className={styles.title}>After Duty</h1>
        </div>
        <BiometricEnroll onDone={afterAuth} />
        <LegalFooter />
      </div>
    );
  }

  return (
    <div className={styles.wrap}>
      <div className={styles.brand}>
        <span className={styles.mark}>
          <Icon name="shield" size={44} stroke={2} />
        </span>
        <h1 className={styles.title}>After Duty</h1>
        <p className={styles.tagline}>Organize. Understand. Move forward.</p>
      </div>

      {step === "enter" && (
        <div className={styles.value}>
          <ul className={styles.valueList}>
            {VALUE_BULLETS.map((b) => (
              <li key={b} className={styles.valueItem}>
                <span className={styles.valueCheck} aria-hidden="true">
                  <Icon name="check" size={13} stroke={3} />
                </span>
                {b}
              </li>
            ))}
          </ul>
          <p className={styles.freeLine}>{FREE_TO_START}</p>
        </div>
      )}

      <form onSubmit={onSubmit} className={styles.providers}>
        {step === "enter" && (
          <>
            <label className={styles.field}>
              <span className={styles.fieldLabel}>Phone or email</span>
              <input
                type="text"
                inputMode="text"
                autoComplete="username"
                autoFocus
                placeholder="(555) 555-0123 or you@example.com"
                value={value}
                disabled={busy}
                onChange={(e) => setValue(e.target.value)}
              />
            </label>
            <p className={styles.instruction}>{INSTRUCTION}</p>
          </>
        )}

        {step === "code" && (
          <>
            <p className={styles.tagline}>{codeSubtitle}</p>
            <label className={styles.field}>
              <span className={styles.fieldLabel}>Verification code</span>
              <input
                type="text"
                inputMode="numeric"
                autoComplete="one-time-code"
                autoFocus
                placeholder="123456"
                value={code}
                disabled={busy}
                onChange={(e) => setCode(e.target.value)}
                className={styles.code}
              />
            </label>
          </>
        )}

        {step === "dualCollect" && (
          <>
            <h2 className={styles.title} style={{ fontSize: "1.25rem" }}>
              How you&apos;ll sign in
            </h2>
            <p className={styles.tagline}>
              {need === "phone"
                ? "Add your phone number — we'll text you a code to confirm it. From then on you can sign in with either your email or your phone. No passwords."
                : "Add your email — we'll send you a code to confirm it. From then on you can sign in with either your phone or your email. No passwords."}
            </p>
            <label className={styles.field}>
              <span className={styles.fieldLabel}>
                {need === "phone" ? "Phone number" : "Email address"}
              </span>
              <input
                type={need === "phone" ? "tel" : "email"}
                inputMode={need === "phone" ? "tel" : "email"}
                autoComplete={need === "phone" ? "tel" : "email"}
                autoFocus
                placeholder={need === "phone" ? "(555) 555-0123" : "you@example.com"}
                value={secondValue}
                disabled={busy}
                onChange={(e) => setSecondValue(e.target.value)}
              />
            </label>
            <p className={styles.instruction}>{INSTRUCTION}</p>
          </>
        )}

        {step === "dualCode" && (
          <>
            <p className={styles.tagline}>
              {need === "phone"
                ? "Enter the 6-digit code we just texted you."
                : "Enter the 6-digit code we just emailed you."}
            </p>
            <label className={styles.field}>
              <span className={styles.fieldLabel}>Verification code</span>
              <input
                type="text"
                inputMode="numeric"
                autoComplete="one-time-code"
                autoFocus
                placeholder="123456"
                value={secondCode}
                disabled={busy}
                onChange={(e) => setSecondCode(e.target.value)}
                className={styles.code}
              />
            </label>
          </>
        )}

        {error && (
          <div className={styles.error} role="alert">
            {error}
          </div>
        )}

        <Button
          variant="primary"
          size="lg"
          icon={step === "enter" || step === "dualCollect" ? "send" : "check"}
          full
          loading={busy}
          disabled={busy}
          type="submit"
        >
          {step === "enter" && (busy ? "Sending…" : "Send me a code")}
          {step === "code" && (busy ? "Verifying…" : "Verify & sign in")}
          {step === "dualCollect" && (busy ? "Sending…" : "Send me a code")}
          {step === "dualCode" && (busy ? "Verifying…" : "Verify & finish")}
        </Button>
      </form>

      {(step === "code" || step === "dualCode") && (
        <div className={styles.codeActions}>
          <button type="button" className={styles.linkBtn} disabled={busy || cooldownLeft > 0} onClick={resend}>
            {cooldownLeft > 0 ? `Resend code in ${cooldownLeft}s` : "Resend code"}
          </button>
          {step === "code" && (
            <button type="button" className={styles.linkBtn} disabled={busy} onClick={startOver}>
              Use a different phone or email
            </button>
          )}
        </div>
      )}

      {/* Returning accounts missing a channel are prompted but never blocked —
          they're already signed in at this point; skipping just proceeds.
          Brand-new users get no skip: onboarding collects BOTH sign-in channels. */}
      {(step === "dualCollect" || step === "dualCode") && canSkipDual && (
        <div className={styles.codeActions}>
          <button type="button" className={styles.linkBtn} disabled={busy} onClick={proceed}>
            Skip for now
          </button>
        </div>
      )}

      <p className={styles.foot}>No password to remember. We&apos;ll never share your information.</p>

      {/* Factor-2 recovery link (auth program P1.5). Shown only on the identifier
          step so it never disturbs an in-progress OTP/dual-verify flow. It leads
          to the standalone dual-channel recovery page — nothing here changes the
          normal sign-in behavior. */}
      {step === "enter" && (
        <div className={styles.codeActions}>
          <Link href="/recovery" className={styles.linkBtn}>
            Can&apos;t use your passkey or Face ID?
          </Link>
        </div>
      )}

      <LegalFooter />

      {/* invisible reCAPTCHA target for the WEB phone verifier (kept mounted; the
          verifier is cleared + rebuilt on each phone retry — spec §9.4). Unused
          on native (silent-APNs verification — §B.4). */}
      <div id={RECAPTCHA_ID} />
    </div>
  );
}
