"use client";

// The step-up ceremony UI (auth-program-plan P1.2). A small "Confirm it's you"
// dialog that runs a FRESH OTP before a sensitive action proceeds. It reuses the
// login page's OTP copy/tone, the `authDriver` (account channels + phone verify +
// fresh token), the `emailCodePost` {detail}-shaped helper, and the `Modal` a11y
// shell (role=dialog, focus trap, labelled). It never surfaces itself: the
// mutation seam (`withStepUp` → `runStepUp`) opens it and awaits the token.
//
// Two lanes, matching the PINNED contract:
//   email → POST /api/auth/email-code/request {purpose:"stepup"} to send, then
//           POST /api/auth/step-up/verify {factor:"otp",channel:"email",code}.
//           (Fully server-verified, exactly like sign-in.)
//   phone → a fresh Firebase phone verify (authDriver.startPhone); on confirm we
//           read a fresh ID token and POST {factor:"otp",channel:"phone",idToken}.
//
// Mount ONE <StepUpHost/> high in the client tree (it registers the opener). If
// none is mounted, `runStepUp` self-mounts a throwaway host via `mountStepUpHost`.

import { useCallback, useEffect, useRef, useState } from "react";
import { Modal } from "@/components/ui/Modal";
import { Button } from "@/components/ui/Button";
import { authDriver } from "@/lib/auth";
import type { AccountDetails, PhoneConfirmation } from "@/lib/auth";
import { EmailCodeError, emailCodePost } from "@/lib/auth/email-code-client";
import {
  registerStepUpOpener,
  setStepUpSelfMount,
  StepUpCancelledError,
  type StepUpResult,
} from "@/lib/auth/step-up";
import styles from "./StepUpModal.module.css";

// The invisible-reCAPTCHA mount point for the WEB phone verifier (native ignores
// it). Distinct id from the login page's so the two never collide if both mount.
const RECAPTCHA_ID = "step-up-recaptcha";

type Channel = "email" | "phone";
type Phase = "choose" | "code";

// One active ceremony at a time. The opener resolves/rejects the promise the
// mutation seam is awaiting; the host component reads these refs to drive its UI.
interface Ceremony {
  challenge: { acceptedFactors: string[] };
  resolve: (r: StepUpResult) => void;
  reject: (e: unknown) => void;
}

/** POST the step-up verify call, mapping the backend's {detail} error shape
 *  (identical to the email-code helper). Returns the minted token + TTL. */
async function verifyStepUp(body: unknown): Promise<StepUpResult> {
  const r = await emailCodePost<{ stepUpToken: string; expiresInSec: number }>(
    "/api/auth/step-up/verify",
    body,
  );
  return { token: r.stepUpToken, expiresInSec: r.expiresInSec };
}

/**
 * The step-up host. Renders nothing until the mutation seam requests a ceremony,
 * then shows the "Confirm it's you" dialog. Register ONE instance in the client
 * tree. Safe to also let `runStepUp` self-mount a throwaway one when absent.
 */
export function StepUpHost() {
  const [ceremony, setCeremony] = useState<Ceremony | null>(null);
  const [account, setAccount] = useState<AccountDetails | null>(null);

  const [phase, setPhase] = useState<Phase>("choose");
  const [channel, setChannel] = useState<Channel>("email");
  const [code, setCode] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const confirmationRef = useRef<PhoneConfirmation | null>(null);

  // Track account channels so we can offer email vs phone and pick a sensible
  // default (prefer email — the fully server-verified lane).
  useEffect(() => authDriver.watchAccount(setAccount), []);

  const reset = useCallback(() => {
    setPhase("choose");
    setCode("");
    setBusy(false);
    setError(null);
    confirmationRef.current = null;
  }, []);

  // Register the opener so `runStepUp` can drive this host. Returns a promise the
  // seam awaits; storing resolve/reject lets the UI settle it.
  useEffect(() => {
    const opener = (challenge: { acceptedFactors: string[] }) =>
      new Promise<StepUpResult>((resolve, reject) => {
        // Default channel: email if the account has one, else phone.
        const hasEmail = !!account?.email;
        setChannel(hasEmail ? "email" : "phone");
        setPhase("choose");
        setCode("");
        setError(null);
        setBusy(false);
        confirmationRef.current = null;
        setCeremony({ challenge, resolve, reject });
      });
    return registerStepUpOpener(opener);
  }, [account]);

  const finish = (result: StepUpResult) => {
    ceremony?.resolve(result);
    setCeremony(null);
    reset();
  };

  // Any dismissal (scrim, X, Escape, Cancel button) rejects with a clean typed
  // cancel so the caller shows a "cancelled" state, not a generic error.
  const cancel = () => {
    ceremony?.reject(new StepUpCancelledError());
    setCeremony(null);
    reset();
  };

  const label =
    channel === "email"
      ? account?.email ?? "your email"
      : account?.phoneNumber ?? "your phone";

  async function sendCode() {
    setError(null);
    setBusy(true);
    try {
      if (channel === "email") {
        if (!account?.email) {
          setError("No email on file. Use your phone instead.");
          setBusy(false);
          return;
        }
        // Fully server-verified email OTP with the dedicated stepup purpose.
        await emailCodePost("/api/auth/email-code/request", {
          email: account.email,
          purpose: "stepup",
        });
      } else {
        if (!account?.phoneNumber) {
          setError("No phone on file. Use your email instead.");
          setBusy(false);
          return;
        }
        confirmationRef.current = await authDriver.startPhone(account.phoneNumber, RECAPTCHA_ID);
      }
      setCode("");
      setPhase("code");
    } catch (e) {
      setError(message(e, "Couldn't send a code. Please try again."));
      if (channel === "phone") authDriver.resetPhoneVerifier();
    } finally {
      setBusy(false);
    }
  }

  async function verify() {
    const c = code.trim();
    if (!c) {
      setError("Enter the 6-digit code we sent you.");
      return;
    }
    setError(null);
    setBusy(true);
    try {
      let result: StepUpResult;
      if (channel === "email") {
        result = await verifyStepUp({
          factor: "otp",
          channel: "email",
          code: c,
        });
      } else {
        const confirmation = confirmationRef.current;
        if (!confirmation) {
          setError("Request a code first.");
          setBusy(false);
          return;
        }
        // Confirm the SMS code (re-authenticates the phone), then post the FRESH
        // ID token — the backend verifies possession server-side (contract).
        await confirmation.confirm(c);
        const idToken = await authDriver.getToken({ forceRefresh: true });
        result = await verifyStepUp({
          factor: "otp",
          channel: "phone",
          idToken,
        });
      }
      finish(result);
    } catch (e) {
      setError(message(e, "Couldn't confirm that code. Try again."));
      setBusy(false);
    }
  }

  const open = ceremony !== null;

  return (
    <Modal open={open} onClose={cancel} title="Confirm it's you" size="sm">
      <div className={styles.body}>
        {phase === "choose" ? (
          <>
            <p className={styles.lead}>
              For your security, confirm it&apos;s you before continuing. We&apos;ll send a 6-digit
              code — no password needed.
            </p>
            {account?.email && account?.phoneNumber && (
              <div className={styles.choices} role="radiogroup" aria-label="Where to send your code">
                <button
                  type="button"
                  role="radio"
                  aria-checked={channel === "email"}
                  className={styles.choice}
                  data-active={channel === "email"}
                  disabled={busy}
                  onClick={() => setChannel("email")}
                >
                  Email <span className={styles.dim}>{account.email}</span>
                </button>
                <button
                  type="button"
                  role="radio"
                  aria-checked={channel === "phone"}
                  className={styles.choice}
                  data-active={channel === "phone"}
                  disabled={busy}
                  onClick={() => setChannel("phone")}
                >
                  Text <span className={styles.dim}>{account.phoneNumber}</span>
                </button>
              </div>
            )}
            {error && (
              <div className={styles.error} role="alert">
                {error}
              </div>
            )}
            <div className={styles.actions}>
              <Button variant="ghost" full disabled={busy} onClick={cancel}>
                Cancel
              </Button>
              <Button variant="primary" full icon="send" loading={busy} onClick={sendCode}>
                {busy ? "Sending…" : "Send me a code"}
              </Button>
            </div>
          </>
        ) : (
          <>
            <p className={styles.lead}>
              Enter the 6-digit code we just {channel === "phone" ? "texted" : "emailed"} to{" "}
              <b>{label}</b>.
            </p>
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
            {error && (
              <div className={styles.error} role="alert">
                {error}
              </div>
            )}
            <div className={styles.actions}>
              <Button variant="ghost" full disabled={busy} onClick={cancel}>
                Cancel
              </Button>
              <Button variant="primary" full icon="check" loading={busy} onClick={verify}>
                {busy ? "Confirming…" : "Confirm"}
              </Button>
            </div>
          </>
        )}
      </div>
      {/* Invisible reCAPTCHA target for the WEB phone verifier (unused on native). */}
      <div id={RECAPTCHA_ID} />
    </Modal>
  );
}

// Surface the backend's user-facing {detail} as-is; never leak a raw error
// message (Firebase phone errors are cryptic) — fall back to friendly copy.
function message(e: unknown, fallback: string): string {
  if (e instanceof EmailCodeError && e.detail) return e.detail;
  return fallback;
}

// ── Self-mount fallback (used only when no <StepUpHost/> is in the tree) ──────
// Injected into the framework-free `step-up.ts` so THAT module never imports
// React/react-dom. Creates a detached root, mounts a host, and returns an
// unmount fn `runStepUp` calls once the ceremony settles.
setStepUpSelfMount(async () => {
  const { createRoot } = await import("react-dom/client");
  const container = document.createElement("div");
  container.setAttribute("data-step-up-host", "");
  document.body.appendChild(container);
  const root = createRoot(container);
  root.render(<StepUpHost />);
  return () => {
    root.unmount();
    container.remove();
  };
});
