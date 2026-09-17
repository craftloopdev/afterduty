"use client";

import { useState } from "react";
import { useRouter } from "next/navigation";
import type { CondVM, LegVM, StepVM } from "@/lib/models/vm";
import { Icon } from "@/components/ui/Icon";
import { Button } from "@/components/ui/Button";
import { ButtonLink } from "@/components/ui/ButtonLink";
import { Pill } from "@/components/ui/Pill";
import { StatusTag } from "@/components/ui/StatusTag";
import { RatingBadge } from "@/components/ui/RatingBadge";
import { Modal } from "@/components/ui/Modal";
import { setConditionExcluded } from "@/lib/api/mutations";
import { statusColors, triadLeg, type TriadLegKey } from "@/lib/theme/tokens";
import { impactChip } from "@/lib/adapters/gaps";
import styles from "./ConditionDetail.module.css";

interface ConditionDetailProps {
  cond: CondVM;
  relatedStep: StepVM | null;
  /**
   * Native refetch hook (§A.3). On the static export `router.refresh()` is a
   * no-op, so the native detail wrapper passes its loader's refetch; web leaves
   * it undefined and the exclude/include toggle falls back to
   * `router.refresh()` — which re-runs this RSC detail read AND (on navigation
   * back) the combined-rating header.
   */
  onChanged?: () => void;
}

const DASH = "—";

const WEAK_LABEL: Record<TriadLegKey, string> = {
  dx: "diagnosis",
  is: "in-service",
  nx: "nexus",
};

/**
 * Single leg of the triad assessment.
 * Header: tinted icon chip (leg identity color) + label/desc + StatusTag.
 * Body: a list of evidence items, each bulleted with a status-colored icon.
 * Mirrors web.jsx LegColumn + screens1.jsx LegCard.
 *
 * `presumptive` (nexus leg only): the condition is presumptive, so the nexus is
 * covered by law — render "Covered by presumption" instead of the pipeline's
 * score, and never a scare tag that steers toward a nexus letter (P1-2,
 * defensive regardless of what the pipeline scored).
 */
function LegCard({
  legKey,
  leg,
  presumptive = null,
}: {
  legKey: TriadLegKey;
  leg: LegVM;
  presumptive?: string | null;
}) {
  const meta = triadLeg(legKey);
  const covered = legKey === "nx" && presumptive != null;
  const sc = statusColors(covered ? "strong" : leg.level);
  return (
    <div className={styles.leg}>
      <div className={styles.legHead}>
        <span
          className={styles.legIc}
          style={{
            background: `color-mix(in srgb, ${meta.color} 14%, transparent)`,
            color: meta.color,
          }}
        >
          <Icon name={meta.icon} size={20} stroke={2.1} />
        </span>
        <div className={styles.legHt}>
          <b>{meta.label}</b>
          <small>{meta.desc}</small>
        </div>
        {covered ? (
          <Pill tone="indigo" icon="sparkle" wrap>
            Covered by presumption
          </Pill>
        ) : (
          <StatusTag level={leg.level} />
        )}
      </div>
      <ul className={styles.legItems}>
        {covered && (
          <li>
            <span className={styles.legBullet} style={{ color: sc.dot }}>
              <Icon name="check" size={14} stroke={2.6} />
            </span>
            Covered by presumption ({presumptive}) &mdash; VA presumes the service connection, so
            no nexus letter is needed.
          </li>
        )}
        {leg.items.map((t, i) => (
          <li key={i}>
            <span className={styles.legBullet} style={{ color: sc.dot }}>
              <Icon
                name={!covered && leg.level === "missing" ? "dash" : "check"}
                size={14}
                stroke={2.6}
              />
            </span>
            {t}
          </li>
        ))}
      </ul>
    </div>
  );
}

/**
 * Condition detail view.
 * Desktop (>=760): web.jsx ConditionDetailView (3-col legs, side-by-side metrics).
 * Mobile (<760): screens1.jsx ConditionDetail (stacked hero + leg cards).
 */
export function ConditionDetail({ cond, relatedStep, onChanged }: ConditionDetailProps) {
  const router = useRouter();
  const [showRationale, setShowRationale] = useState(false);
  const [stepOpen, setStepOpen] = useState(false);
  const [excludeBusy, setExcludeBusy] = useState(false);
  const [excludeError, setExcludeError] = useState<string | null>(null);

  // null = not yet rated; 0 = a REAL VA outcome worth explaining (P1-5).
  const unrated = cond.rating == null;
  const ratingLabel = unrated ? "Rating" : "Estimated rating";
  const ratingValue = unrated ? "Not yet rated" : `${cond.rating}%`;

  const excluded = cond.excludedFromClaim;

  async function toggleExcluded() {
    if (excludeBusy) return;
    setExcludeBusy(true);
    setExcludeError(null);
    try {
      const res = await setConditionExcluded(cond.id, !excluded);
      if (!res.ok) throw new Error(`exclude failed: ${res.status}`);
      // Refetch this condition AND the server-authoritative combined rating: on
      // web router.refresh() re-runs the RSC tree; native passes its loader
      // refetch (router.refresh is a no-op on the static export).
      if (onChanged) onChanged();
      else router.refresh();
    } catch {
      setExcludeError(
        excluded
          ? "Couldn't re-include this just now. Please try again."
          : "Couldn't update this just now. Your claim is unchanged — please try again.",
      );
    } finally {
      setExcludeBusy(false);
    }
  }

  return (
    <div className={styles.page}>
      {/* ── Hero: tags + rating + rationale toggle ── */}
      <div className={styles.hero}>
        <div className={styles.tags}>
          {excluded && (
            <Pill tone="amber" icon="flag" wrap>
              Not filing
            </Pill>
          )}
          {cond.presumptive && (
            <Pill tone="indigo" icon="sparkle" wrap>
              {cond.presumptive}
            </Pill>
          )}
          <Pill tone="line">VASRD {cond.vasrdCode ?? DASH}</Pill>
          <Pill tone="line">{cond.system}</Pill>
        </div>

        <h1 className={styles.name}>{cond.fullName}</h1>

        <div className={styles.ratingRow}>
          <div className={styles.ratingMain}>
            <div className={styles.ratingLabel}>{ratingLabel}</div>
            <div className={[styles.ratingValue, unrated && styles.ratingValueSoft].filter(Boolean).join(" ")}>
              {ratingValue}
            </div>
            {cond.rating === 0 && (
              <p className={styles.zeroNote}>
                0% &mdash; still worth filing: a 0% grant makes this condition service-connected,
                so you can seek an increase later if it worsens.
              </p>
            )}
          </div>
          {/* Missing confidence renders NOTHING — never "0% confidence" (P1-5). */}
          {cond.confidence != null && <RatingBadge value={cond.confidence} sub="confidence" />}
        </div>

        {/* Rating honesty: a non-alarming amber note when the rating rests on a
            MISSING objective test (e.g. asthma with no PFT). The confidence badge
            above already shows the tempered value from the same backend pass. */}
        {cond.ratingEvidenceNote && (
          <div className={styles.evidenceNote}>
            <Pill tone="amber" icon="info" wrap>
              {cond.ratingEvidenceNote}
            </Pill>
          </div>
        )}

        {cond.rationale && (
          <>
            <button
              type="button"
              className={styles.why}
              aria-expanded={showRationale}
              onClick={() => setShowRationale((s) => !s)}
            >
              <Icon name="info" size={16} />
              Why this number?
              <Icon
                name={showRationale ? "chevDown" : "chevron"}
                size={15}
                stroke={2.4}
                className={styles.whyChev}
              />
            </button>
            {showRationale && <div className={styles.rationale}>{cond.rationale}</div>}
          </>
        )}
      </div>

      {/* ── Triad assessment ── */}
      <div className={styles.secHead}>
        <h2>Triad assessment</h2>
        <span className={styles.secNote}>Every claim needs all three legs</span>
      </div>
      <div className={styles.legs}>
        <LegCard legKey="dx" leg={cond.legs.dx} />
        <LegCard legKey="is" leg={cond.legs.is} />
        <LegCard legKey="nx" leg={cond.legs.nx} presumptive={cond.presumptive} />
      </div>

      {/* ── Strengthen (weakest leg) — never the nexus on a presumptive (P1-2) ── */}
      {cond.weakestLeg && (
        <div className={styles.fix}>
          <span className={styles.fixIc}>
            <Icon name="bolt" size={18} />
          </span>
          <div className={styles.fixTxt}>
            <b>Strengthen this claim</b>
            <small>
              The {WEAK_LABEL[cond.weakestLeg]} leg is the weakest. Add evidence to close it.
            </small>
          </div>
        </div>
      )}

      {/* ── Related next step ── */}
      {relatedStep && relatedStep.gap && (
        <button type="button" className={styles.fixbar} onClick={() => setStepOpen(true)}>
          <span className={styles.fixbarIc}>
            <Icon name="target" size={20} />
          </span>
          <span className={styles.fixbarTx}>
            <b>{relatedStep.gap}</b>
            <small>{relatedStep.suggest}</small>
          </span>
          {impactChip(relatedStep.impact, relatedStep.targetRating) && (
            <span
              className={[styles.fixbarImpact, relatedStep.impactStrong && styles.fixbarImpactStrong]
                .filter(Boolean)
                .join(" ")}
            >
              {impactChip(relatedStep.impact, relatedStep.targetRating)}
            </span>
          )}
          <Icon name="chevron" size={18} stroke={2.2} className={styles.fixbarChev} />
        </button>
      )}

      {/* ── Actions ── */}
      <div className={styles.actions}>
        <ButtonLink variant="primary" icon="plus2" full href="/documents">
          Add evidence
        </ButtonLink>
        <ButtonLink
          variant="ghost"
          icon="sparkle"
          full
          href={`/ask?topic=${encodeURIComponent(cond.name)}`}
        >
          Ask AI about this
        </ButtonLink>
      </div>

      {/* ── "Don't include in my claim" toggle (owner-set, reversible) ── */}
      <div className={styles.exclude}>
        <div className={styles.excludeTxt}>
          <b>{excluded ? "Not counted in your claim" : "Include in my claim"}</b>
          <small>
            {excluded
              ? "This condition is excluded from your combined rating. Re-include it anytime."
              : "Excluded conditions aren't counted in your combined rating. You can re-include anytime."}
          </small>
        </div>
        <Button
          variant={excluded ? "primary" : "ghost"}
          icon={excluded ? "plus2" : "flag"}
          loading={excludeBusy}
          onClick={toggleExcluded}
        >
          {excluded ? "Include in my claim" : "Don't include in my claim"}
        </Button>
      </div>
      {excludeError && (
        <p role="alert" className={styles.excludeError}>
          {excludeError}
        </p>
      )}

      {/* ── Next step modal ── */}
      {relatedStep && (
        <Modal open={stepOpen} onClose={() => setStepOpen(false)} title="Next step" size="sm">
          <div className={styles.modalBody}>
            <div className={styles.modalGap}>
              <span className={styles.fixbarIc}>
                <Icon name="target" size={20} />
              </span>
              <div className={styles.modalGapTx}>
                <b>{relatedStep.gap}</b>
                <span
                  className={[
                    styles.fixbarImpact,
                    relatedStep.impactStrong && styles.fixbarImpactStrong,
                  ]
                    .filter(Boolean)
                    .join(" ")}
                >
                  {relatedStep.impact}
                </span>
              </div>
            </div>

            <dl className={styles.modalFacts}>
              <dt>Why it matters</dt>
              <dd>{relatedStep.why}</dd>
              <dt>What to do</dt>
              <dd>{relatedStep.suggest}</dd>
            </dl>

            <ButtonLink
              variant="primary"
              icon="plus2"
              full
              href="/documents"
              onClick={() => setStepOpen(false)}
            >
              Add evidence
            </ButtonLink>
          </div>
        </Modal>
      )}
    </div>
  );
}
