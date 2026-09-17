import type { UsageBreakdownVM } from "@/lib/models/vm";
import styles from "./UsageBreakdown.module.css";

// resetAt is a UTC-midnight "1st of the month" instant — format it in UTC, or a
// negative-offset timezone renders it as the previous day ("July 31" for an
// Aug-1 reset). Not the shared local-TZ formatLongDate.
function resetDate(iso: string): string | null {
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return null;
  return d.toLocaleDateString("en-US", {
    year: "numeric",
    month: "long",
    day: "numeric",
    timeZone: "UTC",
  });
}

// "How your AI budget was used this month" (owner ask, 2026-07-04). A calm,
// honest read of the AiCallLog ledger: a bar per feature with real dollars, the
// spent-of-cap total, and the reset date. Every dollar is server-computed (cents
// / 100 only) — no cost math here. Renders nothing on a null load (supplementary
// content, never blocks the Upgrade page).
const dollars = (cents: number) => `$${(cents / 100).toFixed(2)}`;

export function UsageBreakdown({ data }: { data: UsageBreakdownVM | null }) {
  if (!data) return null;

  const reset = resetDate(data.resetAt);
  const spent = dollars(data.spentCents);
  const total = data.byFeature.reduce((n, f) => n + f.cents, 0) || 1; // bar scale

  return (
    <section className={styles.card} aria-label="AI usage this month">
      <div className={styles.head}>
        <h3 className={styles.title}>How your AI budget was used this month</h3>
        {data.unlimited ? (
          <p className={styles.sub}>
            Unlimited — thank you for helping keep this free for other veterans.
          </p>
        ) : (
          <p className={styles.sub}>
            <b>{spent}</b> of {dollars(data.limitCents)} used
            {reset ? ` · resets ${reset}` : ""}
            {data.atLimit ? " · limit reached" : ""}
          </p>
        )}
      </div>

      {data.byFeature.length === 0 ? (
        <p className={styles.empty}>No AI usage yet this month.</p>
      ) : (
        <ul className={styles.rows}>
          {data.byFeature.map((f) => {
            const pct = Math.round((f.cents / total) * 100);
            return (
              <li key={f.feature} className={styles.row}>
                <div className={styles.rowTop}>
                  <span className={styles.label}>{f.label}</span>
                  <span className={styles.amount}>
                    {dollars(f.cents)}
                    <span className={styles.calls}>
                      {" "}
                      · {f.calls} {f.calls === 1 ? "call" : "calls"}
                    </span>
                  </span>
                </div>
                <div className={styles.track} aria-hidden="true">
                  <div className={styles.fill} style={{ width: `${Math.max(pct, 2)}%` }} />
                </div>
              </li>
            );
          })}
        </ul>
      )}

      {!data.unlimited && data.atLimit && (
        <p className={styles.note}>
          You&apos;ve reached this month&apos;s AI limit. Analysis and chat resume{" "}
          {reset ? `on ${reset}` : "next month"} — everything else keeps working.
        </p>
      )}
    </section>
  );
}
