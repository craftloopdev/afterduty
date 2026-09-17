"use client";

import { useState } from "react";
import Link from "next/link";
import { Icon } from "@/components/ui/Icon";
import { ButtonLink } from "@/components/ui/ButtonLink";
import { Modal } from "@/components/ui/Modal";
import { money } from "@/lib/adapters/home";
import { condHref } from "@/lib/platform";
import { isOpenStep, impactChip } from "@/lib/adapters/gaps";
import { setGapStatus, type GapStatus } from "@/lib/api/mutations";
import { gapActionScript, type GapTypeToken } from "@/components/education/actionScripts";
import type { Priority, StepVM } from "@/lib/models/vm";
import styles from "./StepsPanel.module.css";

interface StepsPanelProps {
  steps: StepVM[];
  highCount: number;
  /** Extra monthly SMC pay unlocked by closing gaps; the stat is omitted when 0/undefined. */
  smcAvailable?: number;
  /**
   * A re-analysis is still re-checking gaps — an empty list means "re-checking",
   * NOT "all caught up" (the false-completion window of P0-5).
   */
  gapAnalysisPending?: boolean;
}

const PRIORITY_DOT: Record<Priority, string> = {
  high: "var(--missing-dot)",
  medium: "var(--partial-dot)",
  low: "var(--faint)",
};

const CLOSED_LABEL: Record<string, string> = {
  resolved: "Marked done",
  dismissed: "Doesn't apply",
};

/**
 * Prioritized "next steps" panel for the /steps route.
 * Leads with a navy "ceiling reframe" banner (the estimate is an estimate; these
 * steps lock in evidence) + a stats row, then a ranked step list. Tapping a step
 * opens the inline detail Modal.
 *
 * Checkable steps (P1-6 / roadmap B3): every step with a `gapIndex` carries
 * "Mark done" / "Doesn't apply" affordances → PATCH via the mutations facade
 * (durable `user_gap_state`), applied optimistically and ROLLED BACK on failure
 * — the list must never claim a save that didn't stick. Resolved/dismissed
 * steps move to a collapsed "Done / dismissed" section with Undo. Legacy steps
 * without a `gapIndex` have no persistence handle, so their affordances hide.
 */
export function StepsPanel({ steps, highCount, smcAvailable, gapAnalysisPending }: StepsPanelProps) {
  const [selected, setSelected] = useState<StepVM | null>(null);
  const [open, setOpen] = useState(false);
  // Optimistic status overrides (step.id → status) layered over the server VMs.
  const [overrides, setOverrides] = useState<Record<string, string>>({});
  const [savingId, setSavingId] = useState<string | null>(null);
  const [actionError, setActionError] = useState<string | null>(null);
  const [showClosed, setShowClosed] = useState(false);

  const statusOf = (s: StepVM) => overrides[s.id] ?? s.status;
  const openList = steps.filter((s) => isOpenStep({ status: statusOf(s) }));
  const closedList = steps.filter((s) => !isOpenStep({ status: statusOf(s) }));

  const openStep = (step: StepVM) => {
    setSelected(step);
    setOpen(true);
  };
  const close = () => setOpen(false);

  async function updateStatus(s: StepVM, status: GapStatus) {
    if (s.gapIndex == null || savingId != null) return;
    const prev = statusOf(s);
    setSavingId(s.id);
    setActionError(null);
    setOverrides((o) => ({ ...o, [s.id]: status })); // optimistic
    try {
      const res = await setGapStatus(s.condId, s.gapIndex, status);
      if (!res.ok) throw new Error(`PATCH failed: ${res.status}`);
    } catch {
      // Roll back — never pretend a save stuck when it didn't.
      setOverrides((o) => ({ ...o, [s.id]: prev }));
      setActionError("Couldn't save that just now. Your steps are unchanged — please try again.");
    } finally {
      setSavingId(null);
    }
  }

  const showSmc = !!smcAvailable && smcAvailable > 0;

  return (
    <section className={styles.section}>
      {/* Ceiling reframe — the estimate is an estimate; these gaps confirm it. */}
      <div className={styles.ceiling}>
        <div className={styles.ceilingTx}>
          <span className={styles.ceilingIc}>
            <Icon name="info" size={22} stroke={2.2} />
          </span>
          <div>
            <b className={styles.ceilingTitle}>Your estimate is exactly that — an estimate.</b>
            <p className={styles.ceilingNote}>
              These steps lock in the evidence so your estimate holds up when VA reviews it.
            </p>
          </div>
        </div>
        <div className={styles.ceilingStats}>
          <div className={styles.stat}>
            <b className={styles.statAmt}>{highCount}</b>
            <small className={styles.statLabel}>high-priority gaps</small>
          </div>
          {showSmc && (
            <div className={styles.stat}>
              <b className={styles.statAmt}>+{money(smcAvailable)}</b>
              <small className={styles.statLabel}>/mo SMC available</small>
            </div>
          )}
        </div>
      </div>

      <div className={styles.head}>
        <h2 className={styles.heading}>Prioritized steps</h2>
        <span className={styles.note}>Ranked by value to your claim</span>
      </div>

      {actionError && (
        <p role="alert" className={styles.actErr}>
          {actionError}
        </p>
      )}

      {openList.length === 0 && gapAnalysisPending ? (
        <div className={styles.empty}>
          <span className={styles.emptyIc}>
            <Icon name="clock" size={26} stroke={2.2} />
          </span>
          <b className={styles.emptyTitle}>Re-checking your next steps&hellip;</b>
          <small className={styles.emptyNote}>
            We&rsquo;re re-analyzing your evidence. Your updated steps will appear here shortly.
          </small>
        </div>
      ) : openList.length === 0 ? (
        <div className={styles.empty}>
          <span className={styles.emptyIc}>
            <Icon name="checkCircle" size={26} stroke={2.2} />
          </span>
          <b className={styles.emptyTitle}>You&rsquo;re all caught up</b>
          <small className={styles.emptyNote}>No open gaps.</small>
        </div>
      ) : (
        <div className={styles.steprows}>
          {openList.map((s, i) => (
            <div key={s.id} className={styles.steprow} data-hero={i === 0 ? "1" : "0"}>
              {i === 0 && (
                <span className={styles.ribbon}>
                  <Icon name="bolt" size={12} /> Highest value
                </span>
              )}
              <button type="button" className={styles.rowOpen} onClick={() => openStep(s)}>
                <span className={styles.dot} style={{ background: PRIORITY_DOT[s.priority] }} />
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
              {s.gapIndex != null && (
                <span className={styles.rowActs}>
                  <button
                    type="button"
                    className={styles.rowAct}
                    aria-label={`Mark "${s.gap}" done`}
                    disabled={savingId != null}
                    onClick={() => updateStatus(s, "resolved")}
                  >
                    <Icon name="check" size={14} stroke={2.6} /> Mark done
                  </button>
                  <button
                    type="button"
                    className={styles.rowAct}
                    aria-label={`"${s.gap}" doesn't apply to me`}
                    disabled={savingId != null}
                    onClick={() => updateStatus(s, "dismissed")}
                  >
                    <Icon name="close" size={14} stroke={2.4} /> Doesn&rsquo;t apply
                  </button>
                </span>
              )}
            </div>
          ))}
        </div>
      )}

      {closedList.length > 0 && (
        <div className={styles.doneWrap}>
          <button
            type="button"
            className={styles.doneToggle}
            aria-expanded={showClosed}
            onClick={() => setShowClosed((v) => !v)}
          >
            <Icon name="checkCircle" size={16} stroke={2.2} />
            Done / dismissed ({closedList.length})
            <span className={styles.doneChev} data-open={showClosed ? "1" : "0"}>
              <Icon name="chevDown" size={15} stroke={2.2} />
            </span>
          </button>
          {showClosed && (
            <ul className={styles.doneList}>
              {closedList.map((s) => (
                <li key={s.id} className={styles.doneRow}>
                  <span className={styles.doneTx}>
                    <b className={styles.doneGap}>{s.gap}</b>
                    <small className={styles.doneSub}>
                      {s.cond} · {CLOSED_LABEL[statusOf(s)] ?? statusOf(s)}
                    </small>
                  </span>
                  {s.gapIndex != null && (
                    <button
                      type="button"
                      className={styles.rowAct}
                      aria-label={`Reopen "${s.gap}"`}
                      disabled={savingId != null}
                      onClick={() => updateStatus(s, "open")}
                    >
                      Undo
                    </button>
                  )}
                </li>
              ))}
            </ul>
          )}
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

/** Gap types with a matching /learn deep-dive (P1-18) — small "Learn more" links. */
const LEARN_LINKS: Partial<Record<GapTypeToken, { href: string; label: string }>> = {
  c_and_p_exam_request: { href: "/learn/cp-exam", label: "Getting ready for your C&P exam" },
  service_record: { href: "/learn/records", label: "Gathering your records" },
  presumptive_documentation: { href: "/learn/records", label: "Gathering your records" },
};

function StepModal({ step, open, onClose }: StepModalProps) {
  // Static per-gap-type action script (P1-18, $0 model spend). Null for
  // unknown types — render nothing rather than guess.
  const script = step ? gapActionScript(step.type) : null;
  const learn = script ? (LEARN_LINKS[script.token] ?? null) : null;
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
          {script && (
            <div className={styles.sheetScript}>
              <b>What to do</b>
              <p className={styles.scriptWhat}>{script.what}</p>
              <ol className={styles.scriptSteps}>
                {script.steps.map((s) => (
                  <li key={s}>{s}</li>
                ))}
              </ol>
              <dl className={styles.scriptFacts}>
                <div className={styles.scriptFact}>
                  <dt>Who to ask</dt>
                  <dd>{script.whoToAsk}</dd>
                </div>
                {script.form && (
                  <div className={styles.scriptFact}>
                    <dt>Form</dt>
                    <dd>{script.form}</dd>
                  </div>
                )}
                <div className={styles.scriptFact}>
                  <dt>Typical cost</dt>
                  <dd>{script.typicalCost}</dd>
                </div>
                <div className={styles.scriptFact}>
                  <dt>Typical time</dt>
                  <dd>{script.typicalTime}</dd>
                </div>
              </dl>
              {learn && (
                <Link className={styles.scriptLearn} href={learn.href} onClick={onClose}>
                  Learn more: {learn.label}
                </Link>
              )}
            </div>
          )}
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
