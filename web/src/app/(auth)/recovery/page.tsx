"use client";

import { useRef, useState } from "react";
import { useRouter } from "next/navigation";
import Link from "next/link";
import { Icon } from "@/components/ui/Icon";
import { Button } from "@/components/ui/Button";
import { LegalFooter } from "@/components/auth/LegalFooter";
import { authDriver } from "@/lib/auth";
import type { PhoneConfirmation } from "@/lib/auth";
import { classify, toE164US } from "@/lib/auth/otp-identifier";
import {
  RecoveryError,
  startRecovery,
  verifyRecovery,
} from "@/lib/auth/recovery-client";
import { signInWithCustomTokenAndEstablish } from "@/lib/firebase/session";
import { NATIVE } from "@/lib/platform";
import { safeNext } from "@/lib/safe-next";
import { friendlyError } from "../login/friendly-error";
import styles from "../login/login.module.css";

// Dual-channel factor-2 RECOVERY (auth program P1.5 — the pinned C contract). A
// calm, deliberate flow for a veteran who can no longer use their passkey /
// Face ID: re-prove BOTH original channels (a fresh emailed code AND a fresh
// phone verification), and the backend then clears every saved sign-in method so
// they set it up fresh next time. This is intentionally NOT the login page — it's
// linked from it ("Can't use your passkey / Face ID?"). The normal OTP login flow
// is untouched.
//
//   start  → enter email + phone; we email a recovery code AND text a phone code
//   codes  → enter BOTH; we confirm the phone (fresh re-auth → fresh ID token) and
//            POST /verify {email, emailCode, phoneIdToken}. NEITHER alone works.
//   done   → "We've removed your saved sign-in methods. Set up Face ID again next
//            time." → continue into the app.

// The invisible-reCAPTCHA mount point for the WEB phone verifier (native ignores
// it). Distinct id from the login page's so the two never collide.
const RECAPTCHA_ID = "recovery-recaptcha";

type Step = "start" | "codes" | "support" | "done";

export default function RecoveryPage() {
  const router = useRouter();
  const [step, setStep] = useState<Step>("start");

  const [emailValue, setEmailValue] = useState("");
  const [phoneValue, setPhoneValue] = useState("");
  const [email, setEmail] = useState(""); // normalized, sent to /verify
  const [phone, setPhone] = useState(""); // E.164, for the code-step copy

  const [emailCode, setEmailCode] = useState("");
  const [smsCode, setSmsCode] = useState("");

  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const confirmationRef = useRef<PhoneConfirmation | null>(null);

  const afterAuth = () => {
    const dest = safeNext(new URLSearchParams(window.location.search).get("next"));
    if (NATIVE) router.replace(dest);
    else window.location.assign(dest);
  };

  function message(e: unknown, fallback: string): string {
    if (e instanceof RecoveryError && e.detail) return e.detail;
    return friendlyError(e) || fallback;
  }

  // ── START: collect both channels, send both codes ──────────────────────────
  async function sendCodes() {
    setError(null);
    const emailClass = classify(emailValue);
    if (emailClass.kind !== "email") {
      setError("Enter the email on your account.");
      return;
    }
    const e164 = toE164US(phoneValue);
    if (!e164) {
      setError("Enter the phone number on your account.");
      return;
    }
    setBusy(true);
    try {
      // 1) Email the recovery code (anti-enumeration: ALWAYS resolves — never
      //    proof the account exists). 2) Text the phone code via Firebase.
      await startRecovery(emailClass.email);
      confirmationRef.current = await authDriver.startPhone(e164, RECAPTCHA_ID);
      setEmail(emailClass.email);
      setPhone(e164);
      setEmailCode("");
      setSmsCode("");
      setStep("codes");
    } catch (e) {
      setError(message(e, "Couldn't start recovery. Please try again."));
      authDriver.resetPhoneVerifier();
    } finally {
      setBusy(false);
    }
  }

  // ── CODES: verify BOTH channels → revoke factors + sign in ─────────────────
  async function submitCodes() {
    const ec = emailCode.trim();
    const sc = smsCode.trim();
    if (!ec) {
      setError("Enter the code we emailed you.");
      return;
    }
    if (!sc) {
      setError("Enter the code we texted you.");
      return;
    }
    setError(null);
    setBusy(true);
    try {
      const confirmation = confirmationRef.current;
      if (!confirmation) {
        setError("Request codes first.");
        setBusy(false);
        return;
      }
      // Confirm the SMS code — this re-authenticates the phone and advances
      // auth_time — then read the FRESH ID token the backend freshness-gates on.
      await confirmation.confirm(sc);
      const phoneIdToken = await authDriver.getToken({ forceRefresh: true });

      // BOTH proofs together. The backend requires a fresh email code AND this
      // fresh phone token for the SAME account; neither alone completes.
      const { custom_token } = await verifyRecovery(email, ec, phoneIdToken);

      // Recovery cleared every saved factor-2 credential and minted a fresh
      // session — exchange the custom token for a real session (same as sign-in).
      await signInWithCustomTokenAndEstablish(custom_token);
      setStep("done");
      setBusy(false);
    } catch (e) {
      // Single-channel legacy account → the support-hold path (not an error the
      // veteran can fix by retrying).
      if (e instanceof RecoveryError && e.needsSupport) {
        setStep("support");
        setBusy(false);
        return;
      }
      setError(message(e, "Recovery failed. Check both codes and try again."));
      setBusy(false);
    }
  }

  function onSubmit(e: React.FormEvent) {
    e.preventDefault();
    if (step === "start") void sendCodes();
    else if (step === "codes") void submitCodes();
  }

  // ── DONE ───────────────────────────────────────────────────────────────────
  if (step === "done") {
    return (
      <div className={styles.wrap}>
        <div className={styles.brand}>
          <span className={styles.mark}>
            <Icon name="shield" size={44} stroke={2} />
          </span>
          <h1 className={styles.title}>You&apos;re back in</h1>
        </div>
        <p className={styles.tagline}>
          For your security, we&apos;ve removed your saved sign-in methods (passkeys and
          Face&nbsp;ID / Touch&nbsp;ID). You can set them up again next time from your profile —
          it only takes a moment.
        </p>
        <Button variant="primary" size="lg" icon="checkCircle" full onClick={afterAuth}>
          Continue
        </Button>
        <LegalFooter />
      </div>
    );
  }

  // ── SUPPORT HOLD (single-channel legacy account) ───────────────────────────
  if (step === "support") {
    return (
      <div className={styles.wrap}>
        <div className={styles.brand}>
          <span className={styles.mark}>
            <Icon name="shield" size={44} stroke={2} />
          </span>
          <h1 className={styles.title}>We need to verify you</h1>
        </div>
        <p className={styles.tagline}>
          This account only has one sign-in channel on file, so we can&apos;t recover it
          automatically. Please email{" "}
          <a href="mailto:support@afterduty.app">support@afterduty.app</a> from the address on
          your account and we&apos;ll help you get back in safely.
        </p>
        <Link href="/login" className={styles.linkBtn}>
          Back to sign in
        </Link>
        <LegalFooter />
      </div>
    );
  }

  // ── START + CODES ──────────────────────────────────────────────────────────
  return (
    <div className={styles.wrap}>
      <div className={styles.brand}>
        <span className={styles.mark}>
          <Icon name="shield" size={44} stroke={2} />
        </span>
        <h1 className={styles.title}>Recover your account</h1>
        <p className={styles.tagline}>
          Can&apos;t use your passkey or Face&nbsp;ID? Confirm your email and phone and we&apos;ll
          get you back in.
        </p>
      </div>

      <form onSubmit={onSubmit} className={styles.providers}>
        {step === "start" && (
          <>
            <label className={styles.field}>
              <span className={styles.fieldLabel}>Email address</span>
              <input
                type="email"
                inputMode="email"
                autoComplete="email"
                autoFocus
                placeholder="you@example.com"
                value={emailValue}
                disabled={busy}
                onChange={(e) => setEmailValue(e.target.value)}
              />
            </label>
            <label className={styles.field}>
              <span className={styles.fieldLabel}>Phone number</span>
              <input
                type="tel"
                inputMode="tel"
                autoComplete="tel"
                placeholder="(555) 555-0123"
                value={phoneValue}
                disabled={busy}
                onChange={(e) => setPhoneValue(e.target.value)}
              />
            </label>
            <p className={styles.instruction}>
              We&apos;ll email you a code and text you a code — enter both to confirm it&apos;s you.
            </p>
          </>
        )}

        {step === "codes" && (
          <>
            <label className={styles.field}>
              <span className={styles.fieldLabel}>Emailed code</span>
              <input
                type="text"
                inputMode="numeric"
                autoComplete="one-time-code"
                autoFocus
                placeholder="123456"
                value={emailCode}
                disabled={busy}
                onChange={(e) => setEmailCode(e.target.value)}
                className={styles.code}
              />
            </label>
            <label className={styles.field}>
              <span className={styles.fieldLabel}>Texted code</span>
              <input
                type="text"
                inputMode="numeric"
                autoComplete="one-time-code"
                placeholder="123456"
                value={smsCode}
                disabled={busy}
                onChange={(e) => setSmsCode(e.target.value)}
                className={styles.code}
              />
            </label>
            <p className={styles.instruction}>
              Enter the code we emailed to <b>{email}</b> and the code we texted to <b>{phone}</b>.
            </p>
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
          icon={step === "start" ? "send" : "check"}
          full
          loading={busy}
          disabled={busy}
          type="submit"
        >
          {step === "start" && (busy ? "Sending…" : "Send me codes")}
          {step === "codes" && (busy ? "Verifying…" : "Verify & recover")}
        </Button>
      </form>

      <div className={styles.codeActions}>
        <Link href="/login" className={styles.linkBtn}>
          Back to sign in
        </Link>
      </div>

      <LegalFooter />

      {/* invisible reCAPTCHA target for the WEB phone verifier (unused on native). */}
      <div id={RECAPTCHA_ID} />
    </div>
  );
}
