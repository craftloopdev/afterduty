"use client";

import { useCallback, useState } from "react";
import { Button } from "@/components/ui/Button";
import { Modal } from "@/components/ui/Modal";
import { authDriver } from "@/lib/auth";
import { EmailCodeError } from "@/lib/auth/email-code-client";
import styles from "./AddEmailSheet.module.css";

// "Add an email" sheet for phone-first accounts (their stored address is a
// synthetic placeholder the UI never shows). Reuses the passwordless OTP
// attach machinery through the AuthDriver seam — web goes via the BFF
// email-code proxy routes, native calls Spring directly with the keychain
// Bearer — so this one component serves both targets (§B.2).
//
// Flow (passwordless-otp-auth-spec §2.1/§2.3): request a 6-digit code with
// purpose "attach", then attach the verified email to the CURRENT uid. The
// backend's {detail} errors are already user-facing copy; fallbacks match the
// login page's tone.

type Step = "email" | "code";

/** The backend's user-facing {detail}, or the fallback (login-page tone). */
function message(e: unknown, fallback: string): string {
  return e instanceof EmailCodeError && e.detail ? e.detail : fallback;
}

export function AddEmailSheet({
  open,
  onClose,
  onAttached,
}: {
  open: boolean;
  onClose: () => void;
  /** Fires with the attached address after a successful verify. */
  onAttached: (email: string) => void;
}) {
  const [step, setStep] = useState<Step>("email");
  const [email, setEmail] = useState("");
  const [code, setCode] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  // A fresh open starts a fresh flow — never a stale code screen. Derived
  // state reset during render (the sanctioned you-might-not-need-an-effect
  // pattern), so no cascading effect renders.
  const [prevOpen, setPrevOpen] = useState(open);
  if (open !== prevOpen) {
    setPrevOpen(open);
    if (open) {
      setStep("email");
      setEmail("");
      setCode("");
      setBusy(false);
      setError(null);
    }
  }

  async function sendCode() {
    const v = email.trim().toLowerCase();
    if (!v.includes("@")) {
      setError("Enter your email address.");
      return;
    }
    setBusy(true);
    setError(null);
    try {
      await authDriver.requestEmailCode(v, "attach");
      setEmail(v);
      setCode("");
      setStep("code");
    } catch (e) {
      setError(message(e, "Couldn't send a code. Please try again."));
    } finally {
      setBusy(false);
    }
  }

  async function verifyCode() {
    const c = code.trim();
    if (c.length !== 6) {
      setError("Enter the 6-digit code we sent you.");
      return;
    }
    setBusy(true);
    setError(null);
    try {
      await authDriver.attachEmailCode(email, c);
      onAttached(email);
    } catch (e) {
      setError(message(e, "Couldn't verify that code. Try again."));
      setBusy(false);
    }
  }

  // MUST be referentially stable across keystroke re-renders: Modal's focus
  // trap re-arms whenever its onClose identity changes, yanking focus from the
  // input back to the dialog card mid-word (each retrap refocuses the surface).
  const close = useCallback(() => {
    if (busy) return;
    onClose();
  }, [busy, onClose]);

  return (
    <Modal open={open} onClose={close} title="Add an email" size="sm">
      <form
        className={styles.form}
        onSubmit={(e) => {
          e.preventDefault();
          if (busy) return;
          void (step === "email" ? sendCode() : verifyCode());
        }}
      >
        {step === "email" ? (
          <>
            <p className={styles.tagline}>
              We&rsquo;ll send you a code to confirm it. From then on you can sign in with either
              your phone or your email. No passwords.
            </p>
            <label className={styles.field}>
              <span className={styles.fieldLabel}>Email address</span>
              <input
                type="email"
                inputMode="email"
                autoComplete="email"
                placeholder="you@example.com"
                value={email}
                onChange={(e) => setEmail(e.target.value)}
                disabled={busy}
                autoFocus
              />
            </label>
          </>
        ) : (
          <>
            <p className={styles.tagline}>
              Enter the 6-digit code we just emailed to <b>{email}</b>.
            </p>
            <label className={styles.field}>
              <span className={styles.fieldLabel}>Verification code</span>
              <input
                type="text"
                inputMode="numeric"
                autoComplete="one-time-code"
                maxLength={6}
                placeholder="123456"
                value={code}
                onChange={(e) => setCode(e.target.value.replace(/\D/g, ""))}
                disabled={busy}
                autoFocus
              />
            </label>
          </>
        )}

        {error && (
          <div className={styles.error} role="alert">
            {error}
          </div>
        )}

        <div className={styles.actions}>
          <Button type="submit" full loading={busy}>
            {step === "email"
              ? busy
                ? "Sending…"
                : "Send me a code"
              : busy
                ? "Verifying…"
                : "Verify email"}
          </Button>
          {step === "code" && (
            <button
              type="button"
              className={styles.linkBtn}
              disabled={busy}
              onClick={() => {
                setStep("email");
                setCode("");
                setError(null);
              }}
            >
              Use a different email
            </button>
          )}
        </div>
      </form>
    </Modal>
  );
}
