"use client";

import { useState } from "react";
import { usePathname, useRouter } from "next/navigation";
import Link from "next/link";
import { UploadCard } from "./UploadCard";
import { DocumentsView } from "./DocumentsView";
import { EmptyState } from "@/components/ui/EmptyState";
import { Button } from "@/components/ui/Button";
import { ButtonLink } from "@/components/ui/ButtonLink";
import { Icon } from "@/components/ui/Icon";
import { quickAddStatement } from "@/lib/api/mutations";
import type { DocVM, SubscriptionState } from "@/lib/models/vm";
import styles from "./DocumentsClient.module.css";

// Mirrors IntakeController.QUICK_ADD_MAX_CHARS (was AddModal's constant).
const MAX_STATEMENT_CHARS = 10_000;

// The editable opener the veteran sends into the AI composer. It is a natural,
// first-person starter — the composer is prefilled with it and they finish the
// sentence in their own words. Prefill only lands for a Pro user (the Ask page
// gates `?topic` on a confirmed "pro"); free users see the Pro affordance here.
const ASK_STARTER =
  "Here's what I remember about my service and how my conditions affect me:";

/**
 * The Documents client owner (web + native via NativeDocuments). Beyond the
 * upload card + list, it hosts the two evidence entry points that used to live
 * in the dissolved top-right "+" AddModal (P2-1):
 *   - "Write a statement": a FREE quick-add textarea → `quickAddStatement`, the
 *     same "type what you remember, it becomes evidence" path as an upload.
 *   - "Describe what you remember": a card → the Ask-AI page prefilled with an
 *     editable opener. Pro-gated (the composer is), so a CONFIRMED free user
 *     gets an honest "Pro" affordance; never "error" (an outage is not free).
 *
 * `subState` is threaded from the Documents loader (`loadDocuments` already read
 * the subscription for the per-doc free badge; it now surfaces `subState` too).
 */
export function DocumentsClient({
  initialDocs,
  subState,
  onChanged,
}: {
  initialDocs: DocVM[];
  subState: SubscriptionState;
  /** Native passes its loader's refetch (router.refresh() is a no-op on the
   *  static export). Web omits it → the client re-fetches the BFF list itself. */
  onChanged?: () => void;
}) {
  const [docs, setDocs] = useState<DocVM[]>(initialDocs);

  async function refresh() {
    // Native owns its list through the loader — defer to its refetch.
    if (onChanged) return onChanged();
    try {
      const res = await fetch("/api/claim/documents", { cache: "no-store" });
      if (!res.ok) return; // transient — the RSC still holds the last-good list
      const body = (await res.json()) as { docs?: DocVM[] };
      if (Array.isArray(body.docs)) setDocs(body.docs);
    } catch {
      /* offline / transient — keep the current list */
    }
  }

  return (
    <div className={styles.wrap}>
      <UploadCard onUploaded={refresh} />

      <StatementSection onAdded={refresh} />

      <DescribeToAiCard subState={subState} />

      {docs.length === 0 ? (
        <EmptyState
          icon="file"
          title="No documents yet"
          body="Upload your records above and we'll extract the facts to build your claim."
        />
      ) : (
        <DocumentsView docs={docs} onChanged={refresh} />
      )}
    </div>
  );
}

/**
 * "Write a statement" (P2-1 quick-add). Free for everyone — a short statement
 * enters the evidence pipeline exactly like an upload. Mirrors the AddModal
 * states: busy / expired (401) / error / done, and refreshes the list on ok.
 */
function StatementSection({ onAdded }: { onAdded: () => void }) {
  const pathname = usePathname();
  const [text, setText] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [expired, setExpired] = useState(false);
  const [done, setDone] = useState(false);

  const submit = async () => {
    const statement = text.trim();
    if (!statement || busy) return;
    setBusy(true);
    setError(null);
    setExpired(false);
    setDone(false);
    try {
      const res = await quickAddStatement(statement);
      if (res.status === 401) {
        setExpired(true);
      } else if (res.ok) {
        setText("");
        setDone(true);
        onAdded();
      } else {
        setError("Couldn't add your statement. Please try again.");
      }
    } catch {
      setError("Couldn't add your statement. Please try again.");
    } finally {
      setBusy(false);
    }
  };

  return (
    <section className={styles.card} aria-labelledby="statement-heading">
      <div className={styles.cardHead}>
        <span className={styles.ic} data-tone="green">
          <Icon name="pen" size={22} stroke={2.1} />
        </span>
        <div className={styles.cardTitles}>
          <h2 id="statement-heading" className={styles.cardTitle}>
            Write a statement
          </h2>
          <p className={styles.cardDesc}>
            Type what you remember — it becomes evidence, like an upload.
          </p>
        </div>
      </div>
      <p className={styles.hint}>
        In your own words: what happened, when it started, how it affects you today. It&apos;s
        free and becomes part of your evidence.
      </p>
      <textarea
        className={styles.textarea}
        aria-label="Your statement"
        placeholder="During my 2009 deployment I started having knee pain after…"
        rows={6}
        maxLength={MAX_STATEMENT_CHARS}
        value={text}
        disabled={busy}
        onChange={(e) => {
          setText(e.target.value);
          if (done) setDone(false);
        }}
      />
      {expired && (
        <div className={styles.error} role="alert">
          Your session has expired.{" "}
          <Link
            className={styles.errorLink}
            href={`/login?next=${encodeURIComponent(pathname || "/documents")}`}
          >
            Sign in again
          </Link>{" "}
          to continue — your statement stays in the box.
        </div>
      )}
      {error && (
        <div className={styles.error} role="alert">
          {error}
        </div>
      )}
      {done && (
        <div className={styles.done} role="status">
          <Icon name="checkCircle" size={18} stroke={2} /> Statement added to your evidence.
        </div>
      )}
      <div className={styles.actions}>
        <Button
          variant="primary"
          icon="pen"
          loading={busy}
          disabled={!text.trim()}
          onClick={submit}
        >
          Add statement
        </Button>
      </div>
    </section>
  );
}

/**
 * "Describe what you remember" (P2-1): a card that sends the veteran to the
 * Ask-AI page with an editable opener prefilled. The composer is Pro-gated, so
 * a CONFIRMED free user carries the honest "Pro" affordance (no badge on "pro"
 * or on an unknown "error" — never flash upsell at a paying user).
 */
function DescribeToAiCard({ subState }: { subState: SubscriptionState }) {
  const href = `/ask?topic=${encodeURIComponent(ASK_STARTER)}`;
  return (
    <section className={styles.card} aria-labelledby="describe-heading">
      <div className={styles.cardHead}>
        <span className={styles.ic} data-tone="ai">
          <Icon name="chat" size={22} stroke={2.1} />
        </span>
        <div className={styles.cardTitles}>
          <h2 id="describe-heading" className={styles.cardTitle}>
            Describe what you remember
            {subState === "free" && <span className={styles.proBadge}>Pro</span>}
          </h2>
          <p className={styles.cardDesc}>
            Tell us in your own words — chat it through with AI.
          </p>
        </div>
      </div>
      <div className={styles.actions}>
        <ButtonLink href={href} variant="ghost" icon="chat">
          Describe to AI
        </ButtonLink>
      </div>
    </section>
  );
}
