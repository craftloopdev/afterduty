"use client";

import { useState } from "react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import type { ServicePeriodVM, ServiceSourceVM } from "@/lib/models/vm";
import { Icon } from "@/components/ui/Icon";
import { Button } from "@/components/ui/Button";
import { Modal } from "@/components/ui/Modal";
import {
  setServiceHistoryOverride,
  clearServiceHistoryOverride,
  type ServiceHistoryOverrideInput,
} from "@/lib/api/mutations";
import {
  adoptedFlags,
  correctedTotalYears,
  isCorrectedByYou,
  sourceHeading,
  type AdoptedFlags,
} from "@/lib/service-history";
import styles from "./ServiceHistoryView.module.css";

const COMPONENT_LABEL: Record<NonNullable<ServicePeriodVM["component"]>, string> = {
  active: "Active Duty",
  guard: "National Guard",
  reserve: "Reserve",
};

/** "2003 – 2007" / "2008 – Present" / null when the period carries no dates. */
function rangeLabel(p: Pick<ServicePeriodVM, "startDate" | "endDate">): string | null {
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

/** "SGT · MOS 11B" — the compact rank/MOS line under a conclusion row. */
function rankMosLine(p: ServicePeriodVM): string | null {
  const parts = [p.rank, p.mos ? `MOS ${p.mos}` : null].filter(Boolean);
  return parts.length ? parts.join(" · ") : null;
}

/** How many contributing sources a conclusion carries (default 1 when unreconciled). */
function sourceCount(p: ServicePeriodVM): number {
  return p.sources?.length ?? 1;
}

/**
 * Full Service History — one row per RECONCILED conclusion (conclusion only),
 * each opening an evidence/reasoning modal. Calm and educational: this is
 * transparency into how the app reconciled duplicate government records into one
 * period, NOT an error report. The header shows the corrected total-years the
 * reconciliation produced (the veteran previously saw an inflated sum).
 */
export function ServiceHistoryView({
  periods,
  onSaved,
}: {
  periods: ServicePeriodVM[];
  /** Native loaders' refetch after a correction; web omits it (router.refresh()). */
  onSaved?: () => void;
}) {
  const [openIdx, setOpenIdx] = useState<number | null>(null);
  const total = correctedTotalYears(periods);
  const active = openIdx == null ? null : periods[openIdx] ?? null;

  return (
    <section className={styles.wrap}>
      <Link href="/profile" className={styles.back}>
        <Icon name="back" size={18} stroke={2.4} /> Profile
      </Link>

      <header className={styles.head}>
        <h1 className={styles.title}>Service history</h1>
        {total != null && (
          <p className={styles.total}>
            About <b>{total}</b> {total === 1 ? "year" : "years"} of service
          </p>
        )}
        <p className={styles.lede}>
          We combined the records you uploaded into one entry per period of service. Tap any period
          to see which documents it came from and how we reconciled them.
        </p>
      </header>

      {periods.length === 0 ? (
        <div className={styles.empty}>
          <span className={styles.emptyIc} aria-hidden="true">
            <Icon name="shield" size={26} stroke={1.9} />
          </span>
          <p className={styles.emptyMsg}>
            No service history yet. Upload your DD-214 and we&rsquo;ll fill this in automatically.
          </p>
        </div>
      ) : (
        <ul className={styles.list}>
          {periods.map((p, i) => {
            const range = rangeLabel(p);
            const rankMos = rankMosLine(p);
            const n = sourceCount(p);
            return (
              <li key={`${p.branch}-${p.startDate ?? "open"}-${i}`}>
                <button
                  type="button"
                  className={styles.row}
                  onClick={() => setOpenIdx(i)}
                  aria-label={`${p.branch} — view sources and reasoning`}
                >
                  <span className={styles.rowTx}>
                    <b className={styles.rowTitle}>
                      {p.branch}
                      {p.component ? ` · ${COMPONENT_LABEL[p.component]}` : ""}
                    </b>
                    <span className={styles.rowMeta}>
                      {range && <span className={styles.rowRange}>{range}</span>}
                      {rankMos && <span className={styles.rowRankMos}>{rankMos}</span>}
                    </span>
                  </span>
                  <span className={styles.sourcesCtl}>
                    {n} source{n === 1 ? "" : "s"}
                    <Icon name="chevron" size={15} stroke={2.3} />
                  </span>
                </button>
              </li>
            );
          })}
        </ul>
      )}

      <EvidenceModal period={active} onClose={() => setOpenIdx(null)} onSaved={onSaved} />
    </section>
  );
}

/** A single ✓/• fact row inside the modal's Facts section. */
function FactValue({ adopted, children }: { adopted: boolean; children: React.ReactNode }) {
  return (
    <span className={styles.factVal} data-adopted={adopted ? "1" : undefined}>
      <span className={styles.factMark} aria-hidden="true">
        {adopted ? <Icon name="check" size={13} stroke={2.7} /> : <span className={styles.factDot} />}
      </span>
      <span className={styles.factText}>{children}</span>
      {adopted && <span className={styles.srOnly}> (used in the final record)</span>}
    </span>
  );
}

const DASH = "—";

/** Humanized raw date span for a source ("2003 – 2007", "2003 – present", "—"). */
function sourceRange(s: ServiceSourceVM): string {
  const range = rangeLabel({ startDate: s.rawStart, endDate: s.rawEnd });
  return range ?? DASH;
}

/** One source's block in the Facts list — its heading + each raw value with a ✓ on adopted. */
function SourceFacts({ source, flags }: { source: ServiceSourceVM; flags: AdoptedFlags }) {
  return (
    <div className={styles.source}>
      <div className={styles.sourceHead}>{sourceHeading(source)}</div>
      <dl className={styles.facts}>
        <dt>Branch</dt>
        <dd>
          <FactValue adopted={flags.branch}>{source.rawBranch ?? DASH}</FactValue>
        </dd>
        <dt>Dates</dt>
        <dd>
          <FactValue adopted={flags.dates}>{sourceRange(source)}</FactValue>
        </dd>
        {source.rawMos && (
          <>
            <dt>MOS</dt>
            <dd>
              <FactValue adopted={flags.mos}>{source.rawMos}</FactValue>
            </dd>
          </>
        )}
        {source.rawRank && (
          <>
            <dt>Rank</dt>
            <dd>
              <FactValue adopted={flags.rank}>{source.rawRank}</FactValue>
            </dd>
          </>
        )}
      </dl>
    </div>
  );
}

/**
 * The evidence/reasoning modal for one conclusion. Facts = one block per source
 * (humanized doc type + authority, its raw values, a ✓ on what the conclusion
 * adopted). When the period carries no `sources` (unreconciled/older data) we
 * show a graceful "Reconciled from your documents" line instead of an empty
 * list. Reasoning renders only when present. When the conclusion carries a stable
 * `clusterKey`, a "This is wrong — correct it" affordance opens the inline
 * veteran-override form (Service History P3); an already-corrected conclusion
 * shows a "Corrected by you" marker + "Undo".
 */
function EvidenceModal({
  period,
  onClose,
  onSaved,
}: {
  period: ServicePeriodVM | null;
  onClose: () => void;
  /** Native loaders' refetch; web leaves it unset (router.refresh() is used). */
  onSaved?: () => void;
}) {
  const open = period != null;
  const title = period
    ? `${period.branch}${period.component ? ` · ${COMPONENT_LABEL[period.component]}` : ""}`
    : undefined;
  const sources = period?.sources ?? null;
  const corrected = period ? isCorrectedByYou(period) : false;

  return (
    <Modal open={open} onClose={onClose} title={title} ariaLabel="Service period details">
      {period && (
        <div className={styles.modalBody}>
          {corrected && (
            <p className={styles.correctedBadge}>
              <Icon name="checkCircle" size={15} stroke={2.2} /> Corrected by you
            </p>
          )}

          <section>
            <h4 className={styles.modalSecLabel}>Facts</h4>
            {sources && sources.length > 0 ? (
              <div className={styles.sources}>
                {sources.map((s, i) => (
                  <SourceFacts key={i} source={s} flags={adoptedFlags(s, period)} />
                ))}
                <p className={styles.factsNote}>
                  <Icon name="check" size={13} stroke={2.7} /> marks the details we kept in your
                  final record.
                </p>
              </div>
            ) : (
              <p className={styles.reconciledLine}>Reconciled from your documents.</p>
            )}
          </section>

          {period.reasoning && (
            <section>
              <h4 className={styles.modalSecLabel}>How we reconciled this</h4>
              <p className={styles.reasoning}>{period.reasoning}</p>
            </section>
          )}

          {period.clusterKey && (
            <OverrideSection period={period} corrected={corrected} onSaved={onSaved} onClose={onClose} />
          )}
        </div>
      )}
    </Modal>
  );
}

const COMPONENT_OPTIONS: Array<{ value: "" | "active" | "guard" | "reserve"; label: string }> = [
  { value: "", label: "Keep as is" },
  { value: "active", label: "Active Duty" },
  { value: "guard", label: "National Guard" },
  { value: "reserve", label: "Reserve" },
];

/** "YYYY-MM-DD" | null → the value an <input type="date"> expects (or ""). */
function dateInputValue(d: string | null): string {
  if (!d) return "";
  const m = /^(\d{4}-\d{2}-\d{2})/.exec(d);
  return m ? m[1] : "";
}

/**
 * The veteran-override affordance + inline form (Service History P3 Part A).
 * Collapsed by default to a "This is wrong — correct it" button (calm, opt-in —
 * this screen is transparency, not an error report). Expanded, it prefills from
 * the conclusion and POSTs only the changed fields through the mutations facade
 * (BFF on web, direct on native), then refetches. An already-corrected conclusion
 * also offers "Undo" (clears the override). Native-safe: token system throughout,
 * overflow discipline preserved.
 */
function OverrideSection({
  period,
  corrected,
  onSaved,
  onClose,
}: {
  period: ServicePeriodVM;
  corrected: boolean;
  onSaved?: () => void;
  onClose: () => void;
}) {
  const router = useRouter();
  const clusterKey = period.clusterKey as string;
  const [editing, setEditing] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  // Form state, prefilled from the conclusion.
  const [branch, setBranch] = useState(period.branch ?? "");
  const [component, setComponent] = useState<"" | "active" | "guard" | "reserve">(
    period.component ?? "",
  );
  const [startDate, setStartDate] = useState(dateInputValue(period.startDate));
  const [endDate, setEndDate] = useState(dateInputValue(period.endDate));
  const [mos, setMos] = useState(period.mos ?? "");
  const [rank, setRank] = useState(period.rank ?? "");

  function refetch() {
    if (onSaved) onSaved();
    else router.refresh();
  }

  async function save(e: React.FormEvent) {
    e.preventDefault();
    // Send only fields that differ from the current conclusion (a null/empty change
    // is a no-op — the backend leaves unset fields alone). At least one must change.
    const payload: ServiceHistoryOverrideInput = { clusterKey };
    if (branch.trim() && branch.trim() !== period.branch) payload.branch = branch.trim();
    if (component && component !== period.component) payload.component = component;
    if (startDate && startDate !== dateInputValue(period.startDate)) payload.startDate = startDate;
    if (endDate && endDate !== dateInputValue(period.endDate)) payload.endDate = endDate;
    if (mos.trim() && mos.trim() !== period.mos) payload.mos = mos.trim();
    if (rank.trim() && rank.trim() !== period.rank) payload.rank = rank.trim();

    const changed = Object.keys(payload).length > 1; // more than just clusterKey
    if (!changed) {
      setError("Change at least one field, then save.");
      return;
    }
    setBusy(true);
    setError(null);
    try {
      const res = await setServiceHistoryOverride(payload);
      if (!res.ok) throw new Error(`override ${res.status}`);
      onClose();
      refetch();
    } catch {
      setError("Couldn't save your correction. Please try again.");
    } finally {
      setBusy(false);
    }
  }

  async function undo() {
    setBusy(true);
    setError(null);
    try {
      const res = await clearServiceHistoryOverride(clusterKey);
      if (!res.ok) throw new Error(`clear ${res.status}`);
      onClose();
      refetch();
    } catch {
      setError("Couldn't undo your correction. Please try again.");
    } finally {
      setBusy(false);
    }
  }

  if (!editing) {
    return (
      <section className={styles.correctSection}>
        <button type="button" className={styles.correctCta} onClick={() => setEditing(true)}>
          <Icon name="pen" size={15} stroke={2.2} />
          {corrected ? "Edit your correction" : "This is wrong — correct it"}
        </button>
        {corrected && (
          <button type="button" className={styles.undoCta} onClick={undo} disabled={busy}>
            Undo correction
          </button>
        )}
        {error && (
          <small className={styles.overrideError} role="alert">
            {error}
          </small>
        )}
      </section>
    );
  }

  return (
    <section className={styles.correctSection}>
      <h4 className={styles.modalSecLabel}>Correct this record</h4>
      <p className={styles.correctLede}>
        Your correction takes priority over the documents. Change only what&rsquo;s wrong.
      </p>
      <form className={styles.overrideForm} onSubmit={save}>
        <label className={styles.field}>
          <span className={styles.fieldLabel}>Branch</span>
          <input
            className={styles.input}
            value={branch}
            maxLength={120}
            onChange={(e) => setBranch(e.target.value)}
            disabled={busy}
          />
        </label>
        <label className={styles.field}>
          <span className={styles.fieldLabel}>Component</span>
          <select
            className={styles.input}
            value={component}
            onChange={(e) => setComponent(e.target.value as typeof component)}
            disabled={busy}
          >
            {COMPONENT_OPTIONS.map((o) => (
              <option key={o.value} value={o.value}>
                {o.label}
              </option>
            ))}
          </select>
        </label>
        <div className={styles.fieldRow}>
          <label className={styles.field}>
            <span className={styles.fieldLabel}>Start date</span>
            <input
              className={styles.input}
              type="date"
              value={startDate}
              onChange={(e) => setStartDate(e.target.value)}
              disabled={busy}
            />
          </label>
          <label className={styles.field}>
            <span className={styles.fieldLabel}>End date</span>
            <input
              className={styles.input}
              type="date"
              value={endDate}
              onChange={(e) => setEndDate(e.target.value)}
              disabled={busy}
            />
          </label>
        </div>
        <label className={styles.field}>
          <span className={styles.fieldLabel}>MOS / rate</span>
          <input
            className={styles.input}
            value={mos}
            maxLength={120}
            onChange={(e) => setMos(e.target.value)}
            disabled={busy}
          />
        </label>
        <label className={styles.field}>
          <span className={styles.fieldLabel}>Rank</span>
          <input
            className={styles.input}
            value={rank}
            maxLength={120}
            onChange={(e) => setRank(e.target.value)}
            disabled={busy}
          />
        </label>
        {error && (
          <small className={styles.overrideError} role="alert">
            {error}
          </small>
        )}
        <div className={styles.overrideActions}>
          <Button type="button" variant="ghost" size="sm" onClick={() => setEditing(false)} disabled={busy}>
            Cancel
          </Button>
          <Button type="submit" size="sm" loading={busy}>
            Save correction
          </Button>
        </div>
      </form>
    </section>
  );
}
