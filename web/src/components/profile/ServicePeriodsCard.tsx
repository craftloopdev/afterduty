import Link from "next/link";
import { serviceTotalYears } from "@/lib/format";
import type { ServicePeriodVM } from "@/lib/models/vm";
import { Icon } from "@/components/ui/Icon";
import styles from "./ServicePeriodsCard.module.css";

// Service summary as a PERIODS list (pinned /auth/profile contract): veterans
// often have several — Active, then Guard or Reserve — so one flat
// Branch/Service/MOS trio can't tell the story. Rendering is deterministic
// display of already-extracted facts; nothing is derived here beyond date
// arithmetic for the header's total-years line.

const COMPONENT_LABEL: Record<NonNullable<ServicePeriodVM["component"]>, string> = {
  active: "Active Duty",
  guard: "National Guard",
  reserve: "Reserve",
};

/** "2003 – 2007" / "2008 – Present" / null when the period carries no dates. */
function rangeLabel(p: ServicePeriodVM): string | null {
  const year = (d: string | null): string | null => {
    if (!d) return null;
    const y = new Date(d).getFullYear();
    return Number.isFinite(y) ? String(y) : null;
  };
  const start = year(p.startDate);
  const end = p.endDate ? year(p.endDate) : start ? "Present" : null;
  if (!start && !end) return null;
  return `${start ?? "—"} – ${end ?? "Present"}`;
}

export function ServicePeriodsCard({ periods }: { periods: ServicePeriodVM[] }) {
  const totalYears = serviceTotalYears(periods);
  return (
    <div>
      <div className={styles.secHead}>
        <div className={styles.secLabel}>Service summary</div>
        {totalYears != null && (
          <span className={styles.totalYears}>about {totalYears} years total</span>
        )}
      </div>
      <div className={styles.card}>
        {periods.length === 0 ? (
          <p className={styles.empty}>
            Upload your DD-214 and we&rsquo;ll fill this in automatically.
          </p>
        ) : (
          periods.map((p, i) => {
            const meta = [
              rangeLabel(p),
              p.mos ? `MOS ${p.mos}` : null,
              p.rank,
            ].filter(Boolean);
            return (
              <div
                key={`${p.branch}-${p.startDate ?? "open"}-${i}`}
                className={[styles.period, i < periods.length - 1 && styles.periodLine]
                  .filter(Boolean)
                  .join(" ")}
              >
                <div className={styles.periodTx}>
                  <b className={styles.periodTitle}>
                    {p.branch}
                    {p.component ? ` · ${COMPONENT_LABEL[p.component]}` : ""}
                  </b>
                  {meta.length > 0 && (
                    <small className={styles.periodMeta}>{meta.join(" · ")}</small>
                  )}
                </div>
                <span className={styles.sourceChip} data-source={p.source}>
                  {p.source === "manual" ? "Added by you" : "From your documents"}
                </span>
              </div>
            );
          })
        )}
      </div>
      {periods.length > 0 && (
        // Entry point to the dedicated Service History screen — the drill-down
        // into which documents each period came from and how they reconciled.
        // `/service-history` is a FIXED path, so a plain Link is native-safe
        // (no `[param]` twin needed; the Capacitor static export includes it).
        <Link href="/service-history" className={styles.viewAll}>
          View full service history
          <Icon name="chevron" size={15} stroke={2.3} />
        </Link>
      )}
    </div>
  );
}
