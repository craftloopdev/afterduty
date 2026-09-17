import Link from "next/link";
import { Icon } from "@/components/ui/Icon";
import { EmptyState } from "@/components/ui/EmptyState";
import { condHref } from "@/lib/platform";
import { digestGroups, notificationIcon, type TimelineDayVM } from "@/lib/notifications";
import styles from "./TimelineView.module.css";

/**
 * The claim timeline (/timeline) — a calm, chronological journal of every
 * analysis event, read and unread, grouped by day (roadmap §5 item 6). Pure
 * presentational: web feeds it from the RSC loader, native from useLoader.
 * Every string is a backend template over deterministic diffs; this component
 * never derives a number.
 */
export function TimelineView({ days }: { days: TimelineDayVM[] }) {
  if (days.length === 0) {
    return (
      <EmptyState
        icon="clock"
        title="No updates yet"
        body="When your analysis changes — new evidence read, a rating estimate updated, a gap closed — you'll see it here, in order."
      />
    );
  }

  return (
    <div className={styles.wrap}>
      <p className={styles.intro}>
        Every change to your analysis, in order. Nothing moves silently.
      </p>
      {days.map((day) => (
        <section key={day.key} className={styles.day} aria-label={day.label}>
          <h2 className={styles.dayLabel}>{day.label}</h2>
          <div className={styles.items}>
            {day.items.map((n) => {
              // The full what-changed detail from the flip diff's metadata.
              // The timeline is the uncapped surface (Home's card caps and
              // links here); with groups present, the backend's prose body is
              // a redundant run-on and drops (same rule as WhatChangedCard).
              const groups = digestGroups(n, { uncapped: true });
              return (
                <article key={n.id} className={styles.item} data-severity={n.severity}>
                  <span className={styles.itemIc} aria-hidden="true">
                    <Icon name={notificationIcon(n.eventType)} size={16} stroke={2.1} />
                  </span>
                  <div className={styles.itemTx}>
                    <b className={styles.itemTitle}>
                      {n.title}
                      {!n.isRead && (
                        <span className={styles.unreadDot} role="img" aria-label="Unread" />
                      )}
                    </b>
                    {groups.length === 0 && n.body && <p className={styles.itemBody}>{n.body}</p>}
                    {groups.map((g) => (
                      <div key={g.key} className={styles.group}>
                        {g.heading && <span className={styles.groupHead}>{g.heading}</span>}
                        <ul className={styles.lines}>
                          {g.lines.map((l) => (
                            <li key={l.key} className={styles.line}>
                              {l.conditionId != null ? (
                                <Link href={condHref(l.conditionId)} className={styles.lineLink}>
                                  {l.text}
                                  <Icon name="chevron" size={12} stroke={2.2} />
                                </Link>
                              ) : (
                                <span>{l.text}</span>
                              )}
                            </li>
                          ))}
                        </ul>
                      </div>
                    ))}
                    {n.conditionId != null && (
                      <Link href={condHref(n.conditionId)} className={styles.itemLink}>
                        View condition <Icon name="chevron" size={13} stroke={2.2} />
                      </Link>
                    )}
                  </div>
                </article>
              );
            })}
          </div>
        </section>
      ))}
    </div>
  );
}
