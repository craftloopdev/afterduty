import Link from "next/link";
import type { CondVM } from "@/lib/models/vm";
import { Icon } from "@/components/ui/Icon";
import { Pill } from "@/components/ui/Pill";
import { StatusTag } from "@/components/ui/StatusTag";
import { TriadDots } from "@/components/ui/TriadDots";
import styles from "./ConditionsPreview.module.css";

interface ConditionsPreviewProps {
  /** The conditions being FILED — already excludes "Don't include in my claim" rows. */
  conditions: CondVM[];
  readyCount: number;
  needsWorkCount: number;
  /** How many conditions the veteran held back from filing (shown in the full list). */
  notFilingCount?: number;
}

const DASH = "—"; // em-dash

/**
 * Home-screen "Your conditions" preview.
 * Desktop (>=760): a 6-row table mirroring web.jsx CondTable.
 * Mobile (<760): a "glance" card mirroring screens1.jsx HomeScreen glance.
 * Pure presentational → Server Component.
 */
export function ConditionsPreview({
  conditions,
  needsWorkCount,
  notFilingCount = 0,
}: ConditionsPreviewProps) {
  const tableRows = conditions.slice(0, 6);
  const glanceRows = conditions.filter((c) => c.ready).slice(0, 3);
  // "See all" points at the full /conditions list, which still holds the
  // excluded rows in its "Not filing" section — so the count includes them.
  const totalCount = conditions.length + notFilingCount;

  return (
    <section className={styles.wrap}>
      <div className={styles.head}>
        <h2 className={styles.title}>Your conditions</h2>
        <Link href="/conditions" className={styles.link}>
          See all {totalCount}
          <Icon name="chevron" size={14} stroke={2.6} />
        </Link>
      </div>

      {/* Honest acknowledgement that some conditions are held back on purpose —
          they're valid, just not part of this claim, so no count above includes
          them. Keeps the veteran oriented (P1-5: no silent drops). */}
      {notFilingCount > 0 && (
        <Link href="/conditions" className={styles.notFiling}>
          <Icon name="flag" size={14} stroke={2.2} />
          {notFilingCount} condition{notFilingCount === 1 ? "" : "s"} you&rsquo;re not filing —
          not counted here
        </Link>
      )}

      {/* ── Desktop: table ── */}
      <div className={styles.table}>
        <div className={styles.thead}>
          <span className={styles.thName}>Condition</span>
          <span className={styles.thTri}>Diagnosis</span>
          <span className={styles.thTri}>In-Service</span>
          <span className={styles.thTri}>Nexus</span>
          <span className={styles.thRate}>Est. rating</span>
          <span className={styles.thArrow} />
        </div>
        {tableRows.map((c) => (
          <Link key={c.id} href="/conditions" className={styles.trow}>
            <span className={styles.tdName}>
              {/* Both lines ellipsize in the table cell — titles carry the full
                  text (the presumptive basis can be sentence-length). */}
              <b title={c.name}>{c.name}</b>
              <small
                title={`${c.system} · VASRD ${c.vasrdCode ?? DASH}${
                  c.presumptive ? " · " + c.presumptive : ""
                }`}
              >
                {c.system} · VASRD {c.vasrdCode ?? DASH}
                {c.presumptive ? " · " + c.presumptive : ""}
              </small>
            </span>
            <span className={styles.tdTri}>
              <StatusTag level={c.triad.dx} />
            </span>
            <span className={styles.tdTri}>
              <StatusTag level={c.triad.is} />
            </span>
            <span className={styles.tdTri}>
              {/* Presumptive nexus is covered by law — never a scare tag (P1-2). */}
              {c.presumptive ? (
                <Pill tone="indigo" icon="sparkle" wrap>
                  Covered
                </Pill>
              ) : (
                <StatusTag level={c.triad.nx} />
              )}
            </span>
            <span className={styles.tdRate}>
              {/* null = not yet rated; a true 0% is a real outcome (P1-5). */}
              {c.rating == null ? <em>Not yet rated</em> : c.rating + "%"}
            </span>
            <span className={styles.tdArrow}>
              <Icon name="chevron" size={17} stroke={2.2} />
            </span>
          </Link>
        ))}
      </div>

      {/* ── Mobile: glance card ── */}
      <div className={styles.glance}>
        {glanceRows.map((c) => (
          <Link key={c.id} href="/conditions" className={styles.glanceRow}>
            <span className={styles.glanceIcon}>
              <Icon name="checkCircle" size={20} />
            </span>
            <span className={styles.glanceName}>
              {c.name}
              <small>{c.system}</small>
            </span>
            <TriadDots triad={c.triad} />
            <span className={styles.glanceRate}>
              {c.rating == null ? DASH : c.rating + "%"}
            </span>
          </Link>
        ))}
        <Link href="/conditions" className={styles.glanceMore}>
          <Icon name="alert" size={18} />
          <span className={styles.glanceMoreTxt}>
            {needsWorkCount} conditions have evidence gaps you can close
          </span>
          <Icon name="chevron" size={15} stroke={2.4} />
        </Link>
      </div>
    </section>
  );
}
