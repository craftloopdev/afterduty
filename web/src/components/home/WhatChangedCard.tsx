"use client";

import { useState } from "react";
import Link from "next/link";
import { Icon } from "@/components/ui/Icon";
import { condHref } from "@/lib/platform";
import { markNotificationsRead } from "@/lib/api/mutations";
import { digestGroups, type AnalysisUpdatesVM } from "@/lib/notifications";
import styles from "./WhatChangedCard.module.css";

/**
 * The what-changed digest surface at the top of Home (roadmap §5 item 6).
 * Renders one of:
 *   - the digest CARD when an unread analysis_updated/analysis_complete
 *     notification exists — backend-templated headline + deterministic
 *     per-condition lines from the flip diff's metadata, links via condHref;
 *   - a DISCREET "Claim timeline" link when the journal has entries but
 *     nothing is unread;
 *   - nothing when there is no journal at all.
 * "Got it" marks the unread digest rows read (optimistic hide; a failed write
 * brings the card back — the veteran's dismissal must actually stick).
 */
export function HomeUpdates({
  updates,
  onDismissed,
}: {
  updates: AnalysisUpdatesVM;
  onDismissed?: () => void;
}) {
  const [dismissed, setDismissed] = useState(false);
  const [saving, setSaving] = useState(false);

  const digest = dismissed ? null : updates.digest;

  if (!digest) {
    if (!updates.hasAny) return null;
    return (
      <p className={styles.timelineLink}>
        <Link href="/timeline">
          <Icon name="clock" size={14} stroke={2.2} /> Claim timeline
        </Link>
      </p>
    );
  }

  const groups = digestGroups(digest);

  async function dismiss() {
    if (saving) return;
    setSaving(true);
    setDismissed(true); // optimistic — the card hides immediately
    try {
      const res = await markNotificationsRead({ ids: updates.digestIds });
      if (!res.ok) setDismissed(false); // rollback: the dismissal didn't stick
      else onDismissed?.();
    } catch {
      setDismissed(false);
    } finally {
      setSaving(false);
    }
  }

  return (
    <section className={styles.card} aria-label="What changed">
      <div className={styles.head}>
        <span className={styles.ic} aria-hidden="true">
          <Icon name="sparkle" size={18} stroke={2.1} />
        </span>
        <b className={styles.title}>{digest.title || "Your analysis was updated"}</b>
      </div>
      {/* The backend body is a fallback ONLY — with metadata present, grouped
          lines replace the run-on paragraph (2026-07-03 readability fix). */}
      {groups.length === 0 && digest.body && <p className={styles.body}>{digest.body}</p>}
      {groups.map((g) => (
        <div key={g.key} className={styles.group}>
          {g.heading && <span className={styles.groupHead}>{g.heading}</span>}
          <ul className={styles.lines}>
            {g.lines.map((l) => (
              <li key={l.key} className={styles.line}>
                {l.conditionId != null ? (
                  <Link href={condHref(l.conditionId)} className={styles.lineLink}>
                    {l.text}
                    <Icon name="chevron" size={13} stroke={2.2} />
                  </Link>
                ) : (
                  <span>{l.text}</span>
                )}
              </li>
            ))}
            {g.more > 0 && (
              <li className={styles.moreLine}>
                <Link href="/timeline" className={styles.lineLink}>
                  and {g.more} more <Icon name="chevron" size={13} stroke={2.2} />
                </Link>
              </li>
            )}
          </ul>
        </div>
      ))}
      <div className={styles.acts}>
        <button type="button" className={styles.gotIt} onClick={dismiss} disabled={saving}>
          <Icon name="check" size={15} stroke={2.6} /> Got it
        </button>
        <Link href="/timeline" className={styles.seeAll}>
          See all updates <Icon name="chevron" size={14} stroke={2.2} />
        </Link>
      </div>
    </section>
  );
}
