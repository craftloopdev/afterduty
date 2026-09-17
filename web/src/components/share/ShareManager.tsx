"use client";

import { useState } from "react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { Button } from "@/components/ui/Button";
import { Icon } from "@/components/ui/Icon";
import { Modal } from "@/components/ui/Modal";
import { Pill } from "@/components/ui/Pill";
import { createShare, revokeShare } from "@/lib/api/mutations";
import { inviteExpiresInDays, inviteToken } from "@/lib/adapters/share";
import { formatLongDate } from "@/lib/format";
import type { ShareDto } from "@/lib/models/api";
import type { ShareStatus, ShareVM } from "@/lib/models/vm";
import styles from "./ShareManager.module.css";

const STATUS: Record<ShareStatus, { label: string; tone: "green" | "amber" | "line" }> = {
  accepted: { label: "Active", tone: "green" },
  pending: { label: "Invite sent", tone: "amber" },
  revoked: { label: "Revoked", tone: "line" },
  expired: { label: "Expired", tone: "line" },
};

/** The lifecycle line under each row (P2-2) — accept/expiry signal for the veteran. */
export function lifecycleLine(s: ShareVM): string {
  switch (s.status) {
    case "accepted": {
      const date = formatLongDate(s.acceptedAt);
      return date ? `Accepted ${date}` : "Accepted";
    }
    case "pending": {
      const date = formatLongDate(s.expiresAt);
      return date ? `Invite sent — expires ${date}` : "Invite sent — not yet accepted";
    }
    case "expired":
      return "Invite expired — re-invite to send a fresh link";
    case "revoked":
      return "Access revoked";
  }
}

function Toggle({
  on,
  onClick,
  title,
  desc,
  disabled = false,
}: {
  on: boolean;
  onClick?: () => void;
  title: string;
  desc: string;
  disabled?: boolean;
}) {
  return (
    <button
      type="button"
      className={styles.toggleRow}
      onClick={onClick}
      aria-pressed={on}
      disabled={disabled}
    >
      <span className={styles.toggleTx}>
        <b>{title}</b>
        <small>{desc}</small>
      </span>
      <span className={styles.switch} data-on={on ? "1" : "0"}>
        <span />
      </span>
    </button>
  );
}

/** What the veteran needs to deliver the invite themselves: link + context. */
interface InviteReady {
  email: string;
  link: string | null;
  expiresDays: number | null;
}

/** P2-4: the session died mid-action — offer the way back in, never a dead retry loop. */
function SessionExpired() {
  return (
    <div className={styles.error} role="alert">
      Your session has expired.{" "}
      <Link className={styles.errorLink} href={`/login?next=${encodeURIComponent("/share")}`}>
        Sign in again
      </Link>{" "}
      to continue.
    </div>
  );
}

export function ShareManager({
  shares,
  onChanged,
}: {
  shares: ShareVM[];
  /** Post-mutation list refresh for hosts where router.refresh() is a no-op (native useLoader). */
  onChanged?: () => void;
}) {
  const router = useRouter();
  const [email, setEmail] = useState("");
  const [viewAnalysis, setViewAnalysis] = useState(false);
  const [uploadDocs, setUploadDocs] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [expired, setExpired] = useState(false);
  const [invite, setInvite] = useState<InviteReady | null>(null);
  const [copied, setCopied] = useState<string | null>(null); // "new" or a row id
  const [copyFailed, setCopyFailed] = useState(false);
  const [confirmRevoke, setConfirmRevoke] = useState<ShareVM | null>(null);
  const [revokeError, setRevokeError] = useState<string | null>(null);
  // Post-revoke confirmation (P2-2): the backend drops revoked rows from the
  // list on refresh, so the confirmation state lives client-side.
  const [revokedIds, setRevokedIds] = useState<Set<number>>(new Set());
  const [revokedEmail, setRevokedEmail] = useState<string | null>(null);

  const refreshList = () => {
    router.refresh();
    onChanged?.();
  };

  const submitInvite = async (input: {
    viewerEmail: string;
    canViewAnalysis: boolean;
    canUploadDocs: boolean;
  }): Promise<boolean> => {
    setBusy(true);
    setError(null);
    setExpired(false);
    setInvite(null);
    setCopied(null);
    setCopyFailed(false);
    try {
      const res = await createShare(input);
      if (res.status === 401) {
        setExpired(true);
        return false;
      }
      if (!res.ok) {
        setError("Couldn't create the invite. Please try again.");
        return false;
      }
      const dto = (await res.json().catch(() => null)) as ShareDto | null;
      const token = dto ? inviteToken(dto) : null;
      setInvite({
        email: input.viewerEmail,
        // Prefer the backend-computed acceptUrl (P1-20): under Capacitor,
        // window.location.origin is https://localhost — a copied invite
        // built from it would be dead on arrival. The origin fallback only
        // covers older backends that omit acceptUrl (web-only harmless).
        link: dto?.acceptUrl || (token ? `${window.location.origin}/accept-share/${token}` : null),
        expiresDays: inviteExpiresInDays(dto?.invitationExpiresAt),
      });
      refreshList();
      return true;
    } catch {
      setError("Couldn't create the invite. Please try again.");
      return false;
    } finally {
      setBusy(false);
    }
  };

  const createInvite = async () => {
    if (!email.includes("@")) {
      setError("Enter a valid email address.");
      return;
    }
    const ok = await submitInvite({
      viewerEmail: email.trim(),
      canViewAnalysis: viewAnalysis,
      canUploadDocs: uploadDocs,
    });
    if (ok) setEmail("");
  };

  // P2-2 re-invite: same create endpoint — the backend retires the stale row
  // (its old link answers 410) and issues a brand-new share with a fresh token.
  const reinvite = (s: ShareVM) =>
    submitInvite({
      viewerEmail: s.email,
      canViewAnalysis: s.canViewAnalysis,
      canUploadDocs: s.canUploadDocs,
    });

  const copy = async (key: string, link: string) => {
    setCopyFailed(false);
    try {
      await navigator.clipboard.writeText(link);
      setCopied(key);
      window.setTimeout(() => setCopied(null), 2000);
    } catch {
      setCopyFailed(true);
    }
  };

  /** A pending row's re-copyable link: backend acceptUrl, else origin-built. */
  const rowLink = (s: ShareVM): string | null =>
    s.acceptUrl ||
    (s.inviteToken ? `${window.location.origin}/accept-share/${s.inviteToken}` : null);

  const revoke = async (s: ShareVM) => {
    setBusy(true);
    setRevokeError(null);
    try {
      const res = await revokeShare(s.id);
      if (res.status === 401) {
        setConfirmRevoke(null);
        setExpired(true);
        return;
      }
      if (!res.ok) {
        setRevokeError("Couldn't revoke access. Please try again.");
        return;
      }
      setConfirmRevoke(null);
      setRevokedIds((prev) => new Set(prev).add(s.id));
      setRevokedEmail(s.email);
      refreshList();
    } catch {
      setRevokeError("Couldn't revoke access. Please try again.");
    } finally {
      setBusy(false);
    }
  };

  const closeRevoke = () => {
    if (busy) return;
    setRevokeError(null);
    setConfirmRevoke(null);
  };

  return (
    <div className={styles.wrap}>
      <p className={styles.lead}>
        Invite an accredited VSO or attorney to securely review your claim. They can see your
        documents — and, if you choose, your AI analysis: conditions, estimated ratings &amp;
        evidence gaps. They never see your data unless you invite them.
      </p>

      <div className={styles.card}>
        <label className={styles.field}>
          <span className={styles.fieldLabel}>Email address</span>
          <input
            type="email"
            inputMode="email"
            placeholder="rep@vso.org"
            value={email}
            disabled={busy}
            onChange={(e) => setEmail(e.target.value)}
            onKeyDown={(e) => e.key === "Enter" && createInvite()}
          />
        </label>
        <Toggle
          on={viewAnalysis}
          onClick={() => setViewAnalysis((v) => !v)}
          title="Share analysis access"
          desc="They see your conditions, estimated ratings & evidence gaps"
        />
        {/* Owner-Pro dependency (P0-8): VIEW_ANALYSIS rides on the OWNER's
            subscription — disclose it here, not after the rep hits a wall. */}
        <p className={styles.proNote}>
          Analysis sharing requires your active Pro subscription. If it lapses, your rep keeps
          document access only.
        </p>
        <Toggle
          on={uploadDocs}
          onClick={() => setUploadDocs((v) => !v)}
          title="Allow document uploads"
          desc="Let them add records on your behalf"
        />
        <Button variant="primary" icon="share" full loading={busy} onClick={createInvite}>
          Create secure invite
        </Button>
        {invite && (
          <div className={styles.notice} role="status">
            <b>Invite ready for {invite.email}.</b>
            {invite.link ? (
              <>
                <p className={styles.noticeBody}>
                  We don&apos;t email it for you — copy this link and send it to your rep
                  yourself, by email or text.
                </p>
                <div className={styles.linkRow}>
                  <input
                    readOnly
                    value={invite.link}
                    aria-label="Invite link"
                    onFocus={(e) => e.currentTarget.select()}
                  />
                  <Button
                    variant="ghost"
                    size="sm"
                    icon={copied === "new" ? "check" : "doc2"}
                    onClick={() => copy("new", invite.link!)}
                  >
                    {copied === "new" ? "Copied" : "Copy link"}
                  </Button>
                </div>
                {copyFailed && (
                  <p className={styles.noticeBody}>
                    Couldn&apos;t copy automatically — tap the link above and copy it yourself.
                  </p>
                )}
                {invite.expiresDays != null && (
                  <small className={styles.noticeMeta}>
                    The link expires in {invite.expiresDays}{" "}
                    {invite.expiresDays === 1 ? "day" : "days"} and works once.
                  </small>
                )}
              </>
            ) : (
              <p className={styles.noticeBody}>
                The invite was created, but we couldn&apos;t build its link. Refresh and try
                again.
              </p>
            )}
          </div>
        )}
        {expired && <SessionExpired />}
        {error && (
          <div className={styles.error} role="alert">
            {error}
          </div>
        )}
      </div>

      {revokedEmail && (
        <div className={styles.notice} role="status">
          <b>Access revoked.</b>
          <p className={styles.noticeBody}>
            {revokedEmail} no longer has access to your claim. Any invite links you sent them are
            dead.
          </p>
        </div>
      )}

      {shares.length > 0 && (
        <>
          <div className={styles.secLabel}>Shared with</div>
          <div className={styles.list}>
            {shares.map((s) => {
              // A just-revoked row until the server list refresh drops it.
              const status: ShareStatus = revokedIds.has(s.id) ? "revoked" : s.status;
              const vm = status === s.status ? s : { ...s, status };
              const link = status === "pending" ? rowLink(s) : null;
              return (
                <div key={s.id} className={styles.row}>
                  <span className={styles.avatar}>{(s.email[0] ?? "?").toUpperCase()}</span>
                  <span className={styles.rowTx}>
                    {/* Emails ellipsize on one line — the title carries it all. */}
                    <b title={s.email}>{s.email}</b>
                    <small>
                      {s.canViewAnalysis ? "Analysis & documents" : "Documents only"}
                      {s.canUploadDocs ? " · can upload" : ""}
                    </small>
                    <small className={styles.lifecycle}>{lifecycleLine(vm)}</small>
                    {(status === "pending" || status === "expired") && (
                      <span className={styles.rowActions}>
                        {link && (
                          <Button
                            variant="ghost"
                            size="sm"
                            icon={copied === String(s.id) ? "check" : "doc2"}
                            disabled={busy}
                            onClick={() => copy(String(s.id), link)}
                          >
                            {copied === String(s.id) ? "Copied" : "Copy link"}
                          </Button>
                        )}
                        <Button
                          variant="ghost"
                          size="sm"
                          icon="send"
                          disabled={busy}
                          onClick={() => reinvite(s)}
                        >
                          Re-invite
                        </Button>
                      </span>
                    )}
                  </span>
                  <Pill tone={STATUS[status].tone}>{STATUS[status].label}</Pill>
                  {status !== "revoked" && (
                    <button
                      type="button"
                      className={styles.revoke}
                      disabled={busy}
                      onClick={() => setConfirmRevoke(s)}
                      aria-label={`Revoke access for ${s.email}`}
                    >
                      <Icon name="close" size={16} stroke={2.4} />
                    </button>
                  )}
                </div>
              );
            })}
          </div>
        </>
      )}

      {/* P2-2: revoke is deliberate, not a silent vanish. */}
      <Modal
        open={confirmRevoke !== null}
        onClose={closeRevoke}
        title="Revoke access?"
        size="sm"
      >
        {confirmRevoke && (
          <>
            <p className={styles.modalBody}>
              <b>{confirmRevoke.email}</b> will immediately lose access to your claim
              {confirmRevoke.status === "pending" || confirmRevoke.status === "expired"
                ? ", and their invite link will stop working."
                : "."}
            </p>
            {revokeError && (
              <div className={styles.error} role="alert">
                {revokeError}
              </div>
            )}
            <div className={styles.modalActions}>
              <Button variant="ghost" full onClick={closeRevoke} disabled={busy}>
                Cancel
              </Button>
              <Button
                variant="primary"
                full
                className={styles.confirmDanger}
                loading={busy}
                onClick={() => revoke(confirmRevoke)}
              >
                Revoke access
              </Button>
            </div>
          </>
        )}
      </Modal>
    </div>
  );
}
