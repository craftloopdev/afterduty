"use client";

import { useEffect, useState } from "react";
import Link from "next/link";
import { Button } from "@/components/ui/Button";
import { EmptyState } from "@/components/ui/EmptyState";
import { Icon } from "@/components/ui/Icon";
import { acceptShare as acceptShareReq, previewShare } from "@/lib/api/mutations";
import { acceptShareHref } from "@/lib/platform";
import { inviteExpiresInDays } from "@/lib/adapters/share";
import type { SharePreviewDto } from "@/lib/models/api";
import styles from "./AcceptShare.module.css";

type Phase =
  | { kind: "loading" }
  | { kind: "preview"; preview: SharePreviewDto }
  | { kind: "accepted"; preview: SharePreviewDto | null }
  | { kind: "dead"; title: string; body: string; showOpenLink?: boolean };

const INVALID: Phase = {
  kind: "dead",
  title: "This invite link doesn't work",
  body: "Double-check you copied the whole link, or ask the veteran to create a new invite from their Share page.",
};

// 410 can't tell us whether the link expired, was revoked, or was already
// accepted — possibly by the person looking at it — so offer the way in
// alongside the ask-for-a-fresh-invite advice.
const GONE: Phase = {
  kind: "dead",
  title: "This invite is no longer active",
  body: "Invite links last 7 days and work once. If you already accepted it, you're in — open After Duty below. Otherwise it may have expired or been revoked; ask the veteran to create a fresh invite.",
  showOpenLink: true,
};

// After a 401 preview/accept, bounce through /login and return to THIS invite.
// The return target must be the route that actually exists in the running
// bundle: web keeps the canonical `/accept-share/[token]`, but the native static
// export only ships the query-param twin (`/accept-share?token=`) — the dynamic
// route is stashed out by scripts/native-export.mjs. `acceptShareHref` picks the
// right one per platform, so the post-login `afterAuth()` -> safeNext(next) lands
// on a real page in the WKWebView bundle instead of a dead 404 (§A.3a, dim 6/7).
function toLogin(token: string) {
  window.location.replace(`/login?next=${encodeURIComponent(acceptShareHref(token))}`);
}

export function AcceptShare({ token }: { token: string }) {
  const [phase, setPhase] = useState<Phase>({ kind: "loading" });
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    (async () => {
      try {
        const res = await previewShare(token);
        if (res.status === 401) {
          toLogin(token);
          return;
        }
        if (cancelled) return;
        if (res.status === 404) {
          setPhase(INVALID);
        } else if (res.status === 410) {
          setPhase(GONE);
        } else if (!res.ok) {
          setPhase({
            kind: "dead",
            title: "Something went wrong",
            body: "We couldn't check this invite. Refresh the page to try again.",
          });
        } else {
          const preview = (await res.json()) as SharePreviewDto;
          if (!cancelled) setPhase({ kind: "preview", preview });
        }
      } catch {
        if (!cancelled) {
          setPhase({
            kind: "dead",
            title: "Something went wrong",
            body: "We couldn't check this invite. Refresh the page to try again.",
          });
        }
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [token]);

  const accept = async () => {
    const preview = phase.kind === "preview" ? phase.preview : null;
    setBusy(true);
    setError(null);
    try {
      const res = await acceptShareReq(token);
      if (res.status === 401) {
        toLogin(token);
        return;
      }
      if (res.ok || res.status === 409) {
        // 409 = already accepted — same happy place for the viewer.
        setPhase({ kind: "accepted", preview });
      } else if (res.status === 403) {
        setError(
          "This invite was created for a different email address. Sign in with the email the veteran invited, or ask them for a new invite.",
        );
      } else if (res.status === 410) {
        setPhase(GONE);
      } else if (res.status === 404) {
        setPhase(INVALID);
      } else {
        setError("Couldn't accept the invite. Please try again.");
      }
    } catch {
      setError("Couldn't accept the invite. Please try again.");
    } finally {
      setBusy(false);
    }
  };

  return (
    <main className={styles.shell}>
      <div className={styles.card}>
        {phase.kind === "loading" && (
          <p className={styles.loading} role="status" aria-busy="true">
            Checking your invite…
          </p>
        )}

        {phase.kind === "preview" && (
          <PreviewCard
            preview={phase.preview}
            busy={busy}
            error={error}
            onAccept={accept}
          />
        )}

        {phase.kind === "accepted" && (
          <EmptyState
            icon="checkCircle"
            title="You're in"
            body={`You now have access to ${ownerLabel(phase.preview)}'s claim.`}
            action={
              <Link className={styles.cta} href="/">
                Open After Duty
              </Link>
            }
          />
        )}

        {phase.kind === "dead" && (
          <EmptyState
            icon="alert"
            title={phase.title}
            body={phase.body}
            action={
              phase.showOpenLink ? (
                <Link className={styles.cta} href="/">
                  Open After Duty
                </Link>
              ) : undefined
            }
          />
        )}
      </div>
    </main>
  );
}

function ownerLabel(preview: SharePreviewDto | null): string {
  return preview?.ownerName || preview?.ownerEmail || "the veteran";
}

function PreviewCard({
  preview,
  busy,
  error,
  onAccept,
}: {
  preview: SharePreviewDto;
  busy: boolean;
  error: string | null;
  onAccept: () => void;
}) {
  const days = inviteExpiresInDays(preview.expiresAt);
  return (
    <>
      <span className={styles.mark}>
        <Icon name="shield" size={36} stroke={2} />
      </span>
      <h1 className={styles.title}>You&apos;re invited to review a claim</h1>
      <p className={styles.lead}>
        {ownerLabel(preview)} invited you to securely review their VA claim on After Duty.
      </p>
      <ul className={styles.perms}>
        <li>
          <Icon name="check" size={16} stroke={2.4} />
          {preview.canViewAnalysis
            ? "See their conditions, ratings & gaps"
            : "See their documents"}
        </li>
        {preview.canUploadDocs && (
          <li>
            <Icon name="check" size={16} stroke={2.4} />
            Add documents on their behalf
          </li>
        )}
      </ul>
      {days != null && (
        <p className={styles.expiry}>
          This invite expires in {days} {days === 1 ? "day" : "days"}.
        </p>
      )}
      <Button variant="primary" size="lg" icon="check" full loading={busy} onClick={onAccept}>
        Accept invite
      </Button>
      {error && (
        <div className={styles.error} role="alert">
          {error}
        </div>
      )}
    </>
  );
}
