"use client";

import { useState } from "react";
import { Icon } from "@/components/ui/Icon";
import { ButtonLink } from "@/components/ui/ButtonLink";
import { Modal } from "@/components/ui/Modal";
import { condHref } from "@/lib/platform";
import type { Priority, StepVM } from "@/lib/models/vm";
import { impactChip } from "@/lib/adapters/gaps";
import styles from "./DoThisNext.module.css";

interface DoThisNextProps {
  steps: StepVM[];
}

const PRIORITY_DOT: Record<Priority, string> = {
  high: "var(--missing-dot)",
  medium: "var(--partial-dot)",
  low: "var(--faint)",
};

/**
 * "Do this next" section — promotes the #1 action as a prominent primary card,
 * with up to two more steps as compact rows. Tapping any step opens a detail
 * modal. Mirrors web.jsx HomeView "Do this next" + the step overlay, and
 * screens1.jsx StepCard. Client Component (modal open state + handlers).
 */
export function DoThisNext({ steps }: DoThisNextProps) {
  const [selected, setSelected] = useState<StepVM | null>(null);
  const [open, setOpen] = useState(false);

  if (steps.length === 0) return null;

  const top = steps[0];
  const rest = steps.slice(1, 3);

  // The pipeline `impact` is often a full sentence. Show a SHORT chip in the
  // fixed side column (like the compact rows) so it can't starve the flexible
  // text column; render the full sentence in the main column where it wraps.
  const topChip = impactChip(top.impact, top.targetRating);
  const fullImpact = top.impact?.trim() ?? "";
  const showFullImpact = fullImpact.length > 0 && topChip !== fullImpact;

  const openStep = (step: StepVM) => {
    setSelected(step);
    setOpen(true);
  };
  const close = () => setOpen(false);

  return (
    <section className={styles.section}>
      <div className={styles.head}>
        <h2 className={styles.heading}>Do this next</h2>
      </div>

      {/* Primary — the highest-value step */}
      <button type="button" className={styles.primary} onClick={() => openStep(top)}>
        <span className={styles.ribbon}>
          <Icon name="bolt" size={13} /> Highest-value step
        </span>
        <div className={styles.primaryBody}>
          <div className={styles.primaryTx}>
            <div className={styles.primaryCond}>
              {top.cond} · {top.type} leg
            </div>
            <b className={styles.primaryGap}>{top.gap}</b>
            <small className={styles.primarySuggest}>{top.suggest}</small>
            {showFullImpact && (
              <span
                className={styles.primaryImpactLine}
                data-strong={top.impactStrong ? "1" : "0"}
              >
                {fullImpact}
              </span>
            )}
          </div>
          <div className={styles.primarySide}>
            {topChip && (
              <span
                className={styles.primaryChip}
                data-strong={top.impactStrong ? "1" : "0"}
              >
                {topChip}
              </span>
            )}
            <span className={styles.primaryCta}>
              Start <Icon name="chevron" size={15} stroke={2.4} />
            </span>
          </div>
        </div>
      </button>

      {/* Up to two more steps, compact rows */}
      {rest.length > 0 && (
        <div className={styles.steprows}>
          {rest.map((s) => (
            <button
              key={s.id}
              type="button"
              className={styles.steprow}
              onClick={() => openStep(s)}
            >
              <span
                className={styles.dot}
                style={{ background: PRIORITY_DOT[s.priority] }}
              />
              <span className={styles.rowMain}>
                <b className={styles.rowGap}>{s.gap}</b>
                <small className={styles.rowSub}>
                  {s.cond} · {s.type} leg · {s.suggest}
                </small>
              </span>
              {impactChip(s.impact, s.targetRating) && (
                <span
                  className={styles.rowImpact}
                  data-strong={s.impactStrong ? "1" : "0"}
                >
                  {impactChip(s.impact, s.targetRating)}
                </span>
              )}
              <Icon name="chevron" size={17} stroke={2.2} />
            </button>
          ))}
        </div>
      )}

      <StepModal step={selected} open={open} onClose={close} />
    </section>
  );
}

interface StepModalProps {
  step: StepVM | null;
  open: boolean;
  onClose: () => void;
}

function StepModal({ step, open, onClose }: StepModalProps) {
  return (
    <Modal open={open && step != null} onClose={onClose} title="Next step" size="sm">
      {step && (
        <div className={styles.sheet}>
          <span className={styles.sheetImpact}>
            <Icon name="bolt" size={15} /> {step.impact} · {step.type} evidence
          </span>
          <h2 className={styles.sheetGap}>{step.gap}</h2>
          <p className={styles.sheetCond}>
            For <b>{step.cond}</b>
          </p>
          <p className={styles.sheetWhy}>{step.why}</p>
          <div className={styles.sheetSug}>
            <b>Suggested action</b>
            {step.suggest}
          </div>
          <div className={styles.sheetActs}>
            <ButtonLink variant="primary" icon="plus2" full href="/documents" onClick={onClose}>
              Add evidence
            </ButtonLink>
            {step.condId > 0 && (
              <ButtonLink variant="ghost" full href={condHref(step.condId)} onClick={onClose}>
                View condition
              </ButtonLink>
            )}
          </div>
        </div>
      )}
    </Modal>
  );
}
