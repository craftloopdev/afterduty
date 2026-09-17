"use client";

import { useState, useSyncExternalStore } from "react";
import { useRouter } from "next/navigation";
import { Button } from "@/components/ui/Button";
import { Icon } from "@/components/ui/Icon";
import { updatePreferredName } from "@/lib/api/mutations";
import { notifySuccess } from "@/lib/native/haptics";
import styles from "./NameCaptureCard.module.css";

// "What should we call you?" — a small dismissible Home card shown ONLY when
// the account has no usable display name (uid-only phone sign-ins; `show` is
// the loader's `nameless` flag). One first-name input, one save; the PATCH
// goes through the mutations facade so web (BFF) and native (direct) both
// work. Dismissal is per-tab (sessionStorage) — and once a name is saved the
// flag flips server-side, so it never nags again.

const DISMISS_KEY = "cp_name_card_dismissed";

// sessionStorage as an external store (SSR-safe, no setState-in-effect): the
// server snapshot says "dismissed" so nothing flashes before the client
// confirms; only this component writes the key, so no subscription is needed —
// the interactive dismissal drives its own local state below.
const emptySubscribe = () => () => {};
function useDismissedAtMount(): boolean {
  return useSyncExternalStore(
    emptySubscribe,
    () => {
      try {
        return sessionStorage.getItem(DISMISS_KEY) === "1";
      } catch {
        return false; // storage blocked — still offer the card
      }
    },
    () => true,
  );
}

export function NameCaptureCard({
  show,
  onSaved,
}: {
  /** The loader's `nameless` flag — false renders nothing. */
  show: boolean;
  /** Native loaders' refetch; web leaves it unset (router.refresh() is used). */
  onSaved?: () => void;
}) {
  const router = useRouter();
  const dismissedAtMount = useDismissedAtMount();
  const [dismissedNow, setDismissedNow] = useState(false);
  const [value, setValue] = useState("");
  const [busy, setBusy] = useState(false);
  const [saved, setSaved] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  if (!show || dismissedAtMount || dismissedNow) return null;

  function dismiss() {
    setDismissedNow(true);
    try {
      sessionStorage.setItem(DISMISS_KEY, "1");
    } catch {
      /* per-tab nicety only */
    }
  }

  async function save(e: React.FormEvent) {
    e.preventDefault();
    const v = value.trim();
    if (!v) {
      setError("Enter a name — even just your first name.");
      return;
    }
    if (v.length > 60) {
      setError("Keep it under 60 characters.");
      return;
    }
    setBusy(true);
    setError(null);
    try {
      const res = await updatePreferredName(v);
      if (!res.ok) throw new Error(`patch ${res.status}`);
      notifySuccess();
      setSaved(v);
      // The saved name flips `nameless` server-side; refresh so the greeting
      // (and this card's gate) pick it up. Native passes its loaders' refetch.
      if (onSaved) onSaved();
      else router.refresh();
    } catch {
      setError("Couldn't save your name. Please try again.");
    } finally {
      setBusy(false);
    }
  }

  if (saved) {
    return (
      <section className={styles.card} aria-label="Name saved">
        <span className={styles.chip} aria-hidden="true">
          <Icon name="checkCircle" size={20} stroke={2.1} />
        </span>
        <p className={styles.thanks}>
          Good to meet you, <b>{saved}</b>.
        </p>
      </section>
    );
  }

  return (
    <section className={styles.card} aria-label="What should we call you?">
      <button
        type="button"
        className={styles.dismiss}
        aria-label="Dismiss"
        onClick={dismiss}
        disabled={busy}
      >
        <Icon name="close" size={16} stroke={2.2} />
      </button>
      <div className={styles.tx}>
        <b className={styles.title}>What should we call you?</b>
        <p className={styles.body}>
          Add your first name so this feels like your claim — not a case number.
        </p>
      </div>
      <form className={styles.form} onSubmit={save}>
        <input
          className={styles.input}
          aria-label="First name"
          placeholder="First name"
          maxLength={60}
          value={value}
          onChange={(e) => setValue(e.target.value)}
          disabled={busy}
        />
        <Button type="submit" size="sm" loading={busy}>
          Save
        </Button>
      </form>
      {error && (
        <small className={styles.error} role="alert">
          {error}
        </small>
      )}
    </section>
  );
}
