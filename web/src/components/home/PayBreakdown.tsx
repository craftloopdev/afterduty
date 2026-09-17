import { ESTIMATE_HEDGE, money, ratingContributors } from "@/lib/adapters/home";
import type { CombineStepVM, RatingScopeVM } from "@/lib/models/vm";
import styles from "./PayBreakdown.module.css";

interface PayBreakdownProps {
  /** scope=ready — the headline ("ready today") figures. */
  readyScope: RatingScopeVM;
  /** scope=all — the "if all N are granted" figures. */
  allScope: RatingScopeVM;
  totalConditions: number;
  /** VA rates vintage (e.g. 2026) when the backend sent it. */
  ratesYear?: number | null;
  /** Server-side fetch failed — show "Estimate unavailable", not fake $0 rows. */
  estimateUnavailable?: boolean;
}

// The pyramiding explainer target — native-safe (a plain in-app route, no origin).
const PYRAMIDING_HREF = "/learn/how-va-rates#pyramiding";

/** The excluded-line copy — fixed missing space + correct singular/plural (task #185). */
function excludedLine(n: number): string {
  return `${n} condition${n === 1 ? "" : "s"} excluded by you — not included in this calculation.`;
}

/**
 * One row of the labeled sequential combine. Shows the contributor's label + its
 * effective %, and the running combined + remaining AFTER applying it. The first
 * contributor reads "combined NN%, MM% left"; later ones "+P pts → combined NN%,
 * MM% left". A pyramiding group names its absorbed members with a "what is
 * pyramiding?" link. The final rounding step reads "Rounded to nearest 10 → NN%".
 */
function CombineRow({ step, isFirst }: { step: CombineStepVM; isFirst: boolean }) {
  if (step.rounding) {
    return (
      <li className={`${styles.combineItem} ${styles.combineRounding}`}>
        <div className={styles.combineTop}>
          <span className={styles.combineLabel}>Rounded to nearest 10</span>
          <b className={styles.combineArrow}>&rarr; {Math.round(step.combinedAfter)}%</b>
        </div>
      </li>
    );
  }

  const combined = Math.round(step.combinedAfter);
  const left = Math.round(step.remainingAfter);
  const pts = Math.round(step.pointsAdded);
  const running = isFirst
    ? `combined ${combined}%, ${left}% left`
    : `+${pts} pts → combined ${combined}%, ${left}% left`;

  return (
    <li className={styles.combineItem}>
      <div className={styles.combineTop}>
        <span className={styles.combineLabel}>
          {step.label}
          {step.rating !== null && <> &mdash; {step.rating}%</>}
        </span>
        <span className={styles.combineRun}>{running}</span>
      </div>
      {step.absorbedMembers.length > 0 && (
        <span className={styles.combineSub}>
          {step.absorbedMembers.join(", ")} rated together &mdash; they don&rsquo;t add.{" "}
          <a className={styles.pyramidLink} href={PYRAMIDING_HREF}>
            What is pyramiding?
          </a>
        </span>
      )}
    </li>
  );
}

/**
 * Estimated monthly pay card (P1-4 / task #185): headline = ready-today dollars,
 * a calibration row for the all-granted scenario, the pinned hedge line, then the
 * LABELED SEQUENTIAL combine — one ordered list that reads like the running VA
 * math ("Mental health — 70% → combined 70%, 30% left" … "Rounded to nearest 10
 * → 90%"), with the excluded line in the summary near the headline. The raw
 * abstract "Apply X%" steps stay as a secondary "Show the exact point math"
 * collapsible. When the server sent no combineSteps (older data / divergence
 * fallback), the card reverts to the grouped contributor list + abstract steps.
 * Every figure is server-computed. Pure presentational → Server Component.
 */
export function PayBreakdown({
  readyScope,
  allScope,
  totalConditions,
  ratesYear = null,
  estimateUnavailable = false,
}: PayBreakdownProps) {
  const assume = `Assumes a veteran with no dependents, at ${ratesYear ?? "current"} VA rates.`;

  if (estimateUnavailable) {
    return (
      <section className={styles.card}>
        <h3 className={styles.title}>Estimated monthly pay</h3>
        <p className={styles.unavail} role="status">
          Estimate unavailable right now. Reload the page to try again.
        </p>
        <p className={styles.assume}>
          When available, estimates assume a veteran with no dependents at current VA rates.
        </p>
      </section>
    );
  }

  // Notes differ per scope (pyramiding absorptions, bilateral factor) — show
  // the union once, deduped, as small print.
  const notes = [...new Set([...readyScope.notes, ...allScope.notes])];

  const excludedCount = readyScope.excludedCount || allScope.excludedCount || 0;

  // The labeled sequential combine is the PRIMARY breakdown (from the ready-today
  // scope — the same scope the headline number reflects). When it's absent
  // (older data / divergence fallback), fall back to the grouped contributor list.
  const combineSteps = readyScope.combineSteps;
  const hasCombine = combineSteps.length > 0;
  const contributors = hasCombine ? [] : ratingContributors(readyScope.inputs);

  return (
    <section className={styles.card}>
      <h3 className={styles.title}>Estimated monthly pay</h3>

      <div className={styles.big}>
        {money(readyScope.monthly)}
        <small>/mo ready today</small>
      </div>
      <p className={styles.hedge}>{ESTIMATE_HEDGE}</p>

      <div className={styles.rows}>
        <div className={styles.row}>
          <span>Combined rating &mdash; ready today</span>
          <b>{readyScope.rating}%</b>
        </div>
        <div className={styles.row}>
          <span>If all {totalConditions} are granted</span>
          <b>
            {allScope.rating}% &middot; {money(allScope.monthly)}/mo
          </b>
        </div>
        {/* Excluded line — in the SUMMARY, near the combined-rating headline. */}
        {excludedCount > 0 && (
          <p className={styles.excludedSummary}>{excludedLine(excludedCount)}</p>
        )}
      </div>

      {hasCombine ? (
        <div className={styles.contrib}>
          <p className={styles.contribHead}>What makes up your {readyScope.rating}%</p>
          <ol className={styles.combineList}>
            {combineSteps.map((step, i) => (
              <CombineRow
                key={`${step.label}-${i}`}
                step={step}
                // First non-rounding step reads "combined NN%" (no "+pts").
                isFirst={i === 0 && !step.rounding}
              />
            ))}
          </ol>
        </div>
      ) : (
        // Fallback (no combineSteps): today's grouped contributor list.
        (contributors.length > 0 || excludedCount > 0) && (
          <div className={styles.contrib}>
            <p className={styles.contribHead}>What makes up your {readyScope.rating}%</p>
            <ul className={styles.contribList}>
              {contributors.map((c) => (
                <li key={c.key} className={styles.contribItem}>
                  <div className={styles.contribRow}>
                    <span className={styles.contribName}>{c.label}</span>
                    <b className={styles.contribPct}>{c.percent}%</b>
                  </div>
                  {c.absorbed.length > 0 && (
                    <span className={styles.contribSub}>
                      {c.absorbed.join(", ")} &mdash; rated together, they don&rsquo;t add.{" "}
                      <a className={styles.pyramidLink} href={PYRAMIDING_HREF}>
                        What is pyramiding?
                      </a>
                    </span>
                  )}
                  {c.absorbed.length === 0 && c.countedNames.length > 1 && (
                    <span className={styles.contribSub}>Includes {c.countedNames.join(", ")}</span>
                  )}
                </li>
              ))}
            </ul>
          </div>
        )
      )}

      {/* The raw abstract VA-math walk stays available as a secondary collapsible.
          When combineSteps is present it's the "exact point math"; in fallback it's
          the only step view, labeled "the exact VA math". */}
      {readyScope.steps.length > 0 && (
        <details className={styles.math}>
          <summary>
            {hasCombine
              ? "Show the exact point math"
              : `The exact VA math — how ${readyScope.rating}% is combined`}
          </summary>
          <ol className={styles.mathSteps}>
            {readyScope.steps.map((s, i) => (
              <li key={i}>{s}</li>
            ))}
          </ol>
        </details>
      )}
      {allScope.steps.length > 0 && (
        <details className={styles.math}>
          <summary>The exact VA math &mdash; {allScope.rating}% if all are granted</summary>
          <ol className={styles.mathSteps}>
            {allScope.steps.map((s, i) => (
              <li key={i}>{s}</li>
            ))}
          </ol>
        </details>
      )}

      {notes.length > 0 && (
        <ul className={styles.notes}>
          {notes.map((n, i) => (
            <li key={i}>{n}</li>
          ))}
        </ul>
      )}

      <p className={styles.assume}>{assume}</p>
    </section>
  );
}
