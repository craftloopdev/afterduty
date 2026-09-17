import type { EvidenceHealthLeg } from "@/lib/models/vm";
import styles from "./EvidenceHealth.module.css";

interface EvidenceHealthProps {
  legs: EvidenceHealthLeg[];
}

/**
 * Evidence-health card — reflects the triad spine across all conditions.
 * Mirrors web.jsx TriadHealth: per-leg stacked bar (strong/partial/missing),
 * sized by flex-grow, plus a Strong/Partial/Missing legend footer.
 * Pure presentational → Server Component.
 */
export function EvidenceHealth({ legs }: EvidenceHealthProps) {
  const total = legs[0]?.total ?? 0;
  // Weakest leg = the one with the fewest strong (stable, matches mockup).
  const weakest = legs.reduce<EvidenceHealthLeg | null>(
    (acc, l) => (acc == null || l.strong < acc.strong ? l : acc),
    null,
  );

  return (
    <div className={styles.card}>
      <h3 className={styles.title}>Evidence health</h3>
      <p className={styles.sub}>
        Every condition needs all three legs.
        {weakest && total > 0 && (
          <>
            {" "}
            <b>{weakest.label}</b> is your weakest link across {total}{" "}
            {total === 1 ? "condition" : "conditions"}.
          </>
        )}
      </p>

      {legs.map((l) => {
        const segments = [
          { count: l.strong, color: "var(--strong-dot)" },
          { count: l.partial, color: "var(--partial-dot)" },
          { count: l.missing, color: "var(--missing-dot)" },
        ];
        return (
          <div key={l.leg} className={styles.leg}>
            <div className={styles.legHead}>
              <span className={styles.legName}>
                <span className={styles.legDot} style={{ background: l.color }} />
                {l.label}
              </span>
              <span className={styles.legCount}>
                {l.strong}/{l.total} strong
              </span>
            </div>
            <div className={styles.bar}>
              {segments.map(
                (seg, i) =>
                  seg.count > 0 && (
                    <span
                      key={i}
                      style={{ flexGrow: seg.count, background: seg.color }}
                    />
                  ),
              )}
            </div>
          </div>
        );
      })}

      <div className={styles.legend}>
        <span>
          <i style={{ background: "var(--strong-dot)" }} />
          Strong
        </span>
        <span>
          <i style={{ background: "var(--partial-dot)" }} />
          Partial
        </span>
        <span>
          <i style={{ background: "var(--missing-dot)" }} />
          Missing
        </span>
      </div>
    </div>
  );
}
