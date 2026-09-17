import { StrengthBar } from "@/components/ui/StrengthBar";
import { ESTIMATE_HEDGE, money } from "@/lib/adapters/home";
import type { RatingScopeVM } from "@/lib/models/vm";
import styles from "./HomeHero.module.css";

interface HomeHeroProps {
  /** scope=ready — what the evidence supports TODAY (the headline). */
  readyScope: RatingScopeVM;
  /** scope=all — if every identified condition were granted. */
  allScope: RatingScopeVM;
  readyCount: number;
  needsWorkCount: number;
  presumptiveCount: number;
  totalConditions: number;
  /** When true, the server-side fetch failed — show "unavailable", not 0% / $0. */
  estimateUnavailable?: boolean;
}

/**
 * The signature navy hero — dual expectation-calibrated numbers (P1-4).
 * Headline = "Ready today" (evidence-strong scope); companion line = "If all N
 * are granted"; ONE consistent hedge under the headline. All numbers arrive
 * server-computed (kill-list rule). Pure presentational → Server Component.
 * Desktop: 3 columns split by thin vertical dividers (rating / pay / readiness).
 * Mobile: a single stacked card.
 */
export function HomeHero({
  readyScope,
  allScope,
  readyCount,
  needsWorkCount,
  presumptiveCount,
  totalConditions,
  estimateUnavailable = false,
}: HomeHeroProps) {
  const readyPct =
    totalConditions > 0 ? Math.round((readyCount / totalConditions) * 100) : 0;

  return (
    <section className={styles.hero}>
      <span className={styles.glow} aria-hidden="true" />

      {/* (1) Combined rating — ready today + if-all-granted */}
      <div className={`${styles.col} ${styles.main}`}>
        <span className={styles.lab}>Ready today &mdash; estimated rating</span>
        {estimateUnavailable ? (
          <div className={styles.unavail} role="status">
            Estimate unavailable right now
          </div>
        ) : (
          <>
            <div
              className={styles.rating}
              aria-label={`Ready today: estimated combined rating ${readyScope.rating} percent, ${money(readyScope.monthly)} per month`}
            >
              {readyScope.rating}
              <span aria-hidden="true">%</span>
              <em className={styles.ratingPay}>{money(readyScope.monthly)}/mo</em>
            </div>
            <div className={styles.ifAll}>
              If all {totalConditions} are granted: <b>{allScope.rating}%</b> &middot;{" "}
              <b>{money(allScope.monthly)}/mo</b>
            </div>
          </>
        )}
        <p className={styles.hedge}>{ESTIMATE_HEDGE}</p>
        <div className={styles.chips}>
          <span>
            <b>{readyCount}</b> ready
          </span>
          <span>
            <b>{needsWorkCount}</b> need work
          </span>
          <span>
            <b>{presumptiveCount}</b> presumptive
          </span>
        </div>
      </div>

      <div className={styles.div} aria-hidden="true" />

      {/* (2) Monthly pay — both scopes */}
      <div className={styles.col}>
        <span className={styles.lab}>Estimated monthly pay</span>
        {estimateUnavailable ? (
          <div className={styles.unavailSm}>Unavailable &mdash; reload to retry</div>
        ) : (
          <>
            <div className={styles.pay}>
              {money(readyScope.monthly)}
              <small>/mo ready today</small>
            </div>
            <div className={styles.payAll}>
              {money(allScope.monthly)}
              <small>/mo if all are granted</small>
            </div>
          </>
        )}
      </div>

      <div className={styles.div} aria-hidden="true" />

      {/* (3) Claim readiness */}
      <div className={`${styles.col} ${styles.strength}`}>
        <div className={styles.readhead}>
          <span className={styles.lab}>Claim readiness</span>
          <b>{readyPct}%</b>
        </div>
        <StrengthBar ready={readyCount} total={totalConditions} />
        <span className={styles.note}>
          {/* Evidence-strength framing — "ready to file" misstates filing
              rights (every condition is legally claimable today; P0-10). */}
          {readyCount} of {totalConditions} conditions have all three evidence legs
        </span>
        <span className={styles.teach}>
          You can file at any time &mdash; filing (or an Intent to File) locks in your effective
          date while you strengthen evidence.
        </span>
      </div>
    </section>
  );
}
