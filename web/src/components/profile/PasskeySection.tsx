"use client";

// The Profile passkey manager (auth-program-plan P1.3, PINNED MANAGE contract).
// Lists the account's enrolled passkeys with rename + revoke, and an "Add a
// passkey" affordance. All WebAuthn is feature-detected: on a browser without it
// (or native) the whole section renders nothing, so Profile looks exactly like
// today.
//
// REVOKE is sensitive (removing a factor). The DELETE goes through
// `authDriver.revokePasskey`, which routes the request through the Stage A
// step-up seam: a `403 {code:step_up_required}` transparently opens the
// "Confirm it's you" modal and retries once. A user cancel surfaces as
// `StepUpCancelledError` (we show a gentle "cancelled" note, not an error).

import { useCallback, useEffect, useState } from "react";
import { Button } from "@/components/ui/Button";
import { Icon } from "@/components/ui/Icon";
import { authDriver, type PasskeyCredential } from "@/lib/auth";
import { StepUpCancelledError } from "@/lib/auth/step-up";
import { notifyError, notifySuccess, tapLight } from "@/lib/native/haptics";
import styles from "./PasskeySection.module.css";

type LoadState = "loading" | "ready" | "error";

export function PasskeySection() {
  // Capability gate: read ONCE (it's a synchronous, stable probe). When
  // unsupported we render nothing at all — additive over the existing profile.
  const [supported] = useState(() => {
    try {
      return authDriver.isPasskeySupported();
    } catch {
      return false;
    }
  });

  const [state, setState] = useState<LoadState>("loading");
  const [creds, setCreds] = useState<PasskeyCredential[]>([]);
  const [enrolling, setEnrolling] = useState(false);
  const [notice, setNotice] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  // Initial load: subscribe-style effect (mirrors NativeBillingManage) — the
  // async fetch only writes state after the awaited call resolves, guarded by a
  // liveness flag so an unmount mid-flight is a no-op.
  useEffect(() => {
    if (!supported) return;
    let live = true;
    authDriver
      .listPasskeys()
      .then((list) => {
        if (!live) return;
        setCreds(list);
        setState("ready");
      })
      .catch(() => {
        if (live) setState("error");
      });
    return () => {
      live = false;
    };
  }, [supported]);

  // Reusable refetch for post-mutation refreshes (add). Not called from the
  // effect, so it never triggers the synchronous-setState-in-effect lint.
  const refresh = useCallback(async () => {
    try {
      const list = await authDriver.listPasskeys();
      setCreds(list);
      setState("ready");
    } catch {
      setState("error");
    }
  }, []);

  async function addPasskey() {
    setError(null);
    setNotice(null);
    setEnrolling(true);
    try {
      await authDriver.enrollPasskey();
      notifySuccess();
      setNotice("Passkey added.");
      await refresh();
    } catch {
      // Cancel or failure — never destructive; just let them retry.
      notifyError();
      setError("Couldn't add a passkey. Please try again.");
    } finally {
      setEnrolling(false);
    }
  }

  const onRenamed = useCallback((id: string, nickname: string) => {
    setCreds((prev) => prev.map((c) => (c.id === id ? { ...c, nickname } : c)));
  }, []);

  const onRevoked = useCallback((id: string) => {
    setCreds((prev) => prev.filter((c) => c.id !== id));
  }, []);

  // Feature-detect miss (or native): render nothing.
  if (!supported) return null;

  return (
    <div>
      <div className={styles.secLabel}>Passkeys</div>
      <div className={styles.card}>
        <p className={styles.lead}>
          Sign in with Face ID, Touch ID, or Windows Hello instead of a code.
        </p>

        {state === "loading" && <p className={styles.dim}>Loading your passkeys…</p>}
        {state === "error" && (
          <p className={styles.dim}>Couldn&apos;t load your passkeys.</p>
        )}

        {state === "ready" && creds.length === 0 && (
          <p className={styles.dim}>No passkeys yet.</p>
        )}

        {state === "ready" &&
          creds.map((cred) => (
            <PasskeyRow
              key={cred.id}
              cred={cred}
              onRenamed={onRenamed}
              onRevoked={onRevoked}
              onCancelled={() => setNotice("Removal cancelled.")}
              onError={() => setError("Couldn't remove that passkey. Please try again.")}
            />
          ))}

        {notice && (
          <div className={styles.notice} role="status">
            {notice}
          </div>
        )}
        {error && (
          <div className={styles.error} role="alert">
            {error}
          </div>
        )}

        <Button
          variant="ghost"
          size="sm"
          icon="plus"
          loading={enrolling}
          disabled={enrolling}
          onClick={addPasskey}
        >
          {enrolling ? "Adding…" : "Add a passkey"}
        </Button>
      </div>
    </div>
  );
}

// ── One credential row: label + rename + revoke ──────────────────────────────

function PasskeyRow({
  cred,
  onRenamed,
  onRevoked,
  onCancelled,
  onError,
}: {
  cred: PasskeyCredential;
  onRenamed: (id: string, nickname: string) => void;
  onRevoked: (id: string) => void;
  onCancelled: () => void;
  onError: () => void;
}) {
  const [editing, setEditing] = useState(false);
  const [draft, setDraft] = useState(cred.nickname ?? "");
  const [busy, setBusy] = useState(false);

  const label = cred.nickname || cred.deviceHint || "Passkey";

  async function saveName(e: React.FormEvent) {
    e.preventDefault();
    const v = draft.trim();
    if (!v || v.length > 60) return;
    setBusy(true);
    try {
      await authDriver.renamePasskey(cred.id, v);
      notifySuccess();
      onRenamed(cred.id, v);
      setEditing(false);
    } catch {
      notifyError();
    } finally {
      setBusy(false);
    }
  }

  async function revoke() {
    tapLight();
    setBusy(true);
    try {
      // Sensitive: the driver routes this through the step-up seam. A cancel of
      // the "Confirm it's you" modal throws StepUpCancelledError.
      await authDriver.revokePasskey(cred.id);
      notifySuccess();
      onRevoked(cred.id);
    } catch (e) {
      if (e instanceof StepUpCancelledError) {
        onCancelled();
      } else {
        notifyError();
        onError();
      }
      setBusy(false);
    }
  }

  return (
    <div className={styles.row}>
      <span className={styles.rowIcon} aria-hidden="true">
        <Icon name="lock" size={18} stroke={2.1} />
      </span>
      {editing ? (
        <form className={styles.rowEdit} onSubmit={saveName}>
          <input
            className={styles.nameInput}
            aria-label="Passkey name"
            placeholder="e.g. My laptop"
            maxLength={60}
            value={draft}
            disabled={busy}
            onChange={(e) => setDraft(e.target.value)}
            autoFocus
          />
          <Button type="submit" size="sm" loading={busy}>
            Save
          </Button>
          <Button
            type="button"
            size="sm"
            variant="ghost"
            disabled={busy}
            onClick={() => setEditing(false)}
          >
            Cancel
          </Button>
        </form>
      ) : (
        <>
          <span className={styles.rowLabel}>{label}</span>
          <button
            type="button"
            className={styles.iconBtn}
            aria-label={`Rename ${label}`}
            disabled={busy}
            onClick={() => {
              setDraft(cred.nickname ?? "");
              setEditing(true);
            }}
          >
            <Icon name="pen" size={15} stroke={2.1} />
          </button>
          <button
            type="button"
            className={styles.removeBtn}
            disabled={busy}
            onClick={revoke}
          >
            Remove
          </button>
        </>
      )}
    </div>
  );
}
