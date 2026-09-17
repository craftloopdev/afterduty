import { Icon } from "@/components/ui/Icon";
import { Pill } from "@/components/ui/Pill";
import { money } from "@/lib/adapters/home";
import type { ScenarioVM } from "@/lib/models/vm";
import styles from "./ScenariosPanel.module.css";

interface ScenariosPanelProps {
  scenarios: ScenarioVM[];
  /** Server-side pay calc failed — dollar figures show "Estimate unavailable". */
  estimateUnavailable?: boolean;
}

/**
 * Scenarios rail — compares filing strategies. Each card shows a label + badge
 * pill and an estimated monthly pay figure; "alt" scenarios get a green accent
 * and a delta line vs filing now. Server Component (presentational only).
 * Mirrors web.jsx NextStepsView scenarios rail + screens2.jsx ScenarioPanel.
 */
export function ScenariosPanel({ scenarios, estimateUnavailable = false }: ScenariosPanelProps) {
  if (scenarios.length === 0) return null;

  return (
    <div className={styles.panel}>
      <div className={styles.info}>
        <Icon name="info" size={16} />
        We combine ratings with VA math (not simple addition), then estimate
        monthly pay for a veteran with no dependents.
      </div>

      {scenarios.map((s, i) => {
        const conds = s.conditionNames.slice(0, 3);
        const moreCount = s.conditionNames.length - conds.length;
        return (
          <div
            key={s.label + i}
            className={[styles.scen, s.alt && styles.alt].filter(Boolean).join(" ")}
          >
            <div className={styles.head}>
              <b className={styles.label}>{s.label}</b>
              <Pill tone="green">{s.badge}</Pill>
            </div>

            {conds.length > 0 && (
              <div className={styles.conds}>
                {conds.join(", ")}
                {moreCount > 0 && (
                  <span className={styles.more}> +{moreCount} more</span>
                )}
              </div>
            )}

            <div className={styles.foot}>
              <span>Estimated monthly pay</span>
              {estimateUnavailable ? (
                <span className={styles.unavail} role="status">
                  Estimate unavailable
                </span>
              ) : (
                <b className={styles.pay}>
                  {money(s.monthlyPay)}
                  <small>/mo</small>
                </b>
              )}
            </div>

            {!estimateUnavailable && s.alt && s.deltaPay > 0 && (
              <div className={styles.delta}>
                <Icon name="bolt" size={14} />
                <span>
                  +{money(s.deltaPay)}/mo vs filing now
                </span>
              </div>
            )}
          </div>
        );
      })}

      {estimateUnavailable && (
        <p className={styles.retryHint}>
          Pay figures didn&rsquo;t load. Reload the page to try again.
        </p>
      )}
    </div>
  );
}
