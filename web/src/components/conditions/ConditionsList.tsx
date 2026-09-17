"use client";

import { useEffect, useState } from "react";
import { useRouter } from "next/navigation";
import Link from "next/link";
import type { CondVM } from "@/lib/models/vm";
import { condHref } from "@/lib/platform";
import { setConditionExcluded } from "@/lib/api/mutations";
import { Icon } from "@/components/ui/Icon";
import { Chip } from "@/components/ui/Chip";
import { Pill } from "@/components/ui/Pill";
import { StatusTag } from "@/components/ui/StatusTag";
import { TriadDots } from "@/components/ui/TriadDots";
import styles from "./ConditionsList.module.css";

interface ConditionsListProps {
  conditions: CondVM[];
  /**
   * Condition ids the latest unread analysis update changed
   * (metadata.changedConditionIds) — these rows get an "Updated" pill so the
   * veteran can see WHERE the numbers moved (P1-8).
   */
  updatedIds?: number[];
  /**
   * Native refetch hook (§A.3). On the static export `router.refresh()` is a
   * no-op, so NativeConditions passes its loader's refetch; web leaves it
   * undefined and the toggle falls back to `router.refresh()` — which re-runs
   * the RSC conditions read AND the server-authoritative combined-rating header,
   * so both update after an exclude/include.
   */
  onChanged?: () => void;
}

type FilterId = "all" | "ready" | "needswork" | "presumptive";

const DASH = "—"; // em-dash

/**
 * A rendered segment of the (already filtered) list: either a single ungrouped
 * condition, or a pyramiding group (2+ conditions VA rates together). Grouping is
 * computed WITHIN the current filter view (see `segment`), so filters/sorting keep
 * working — a group only forms from rows that survived the active filter.
 */
type Segment =
  | { kind: "single"; cond: CondVM }
  | { kind: "group"; label: string; primary: CondVM; members: CondVM[]; groupRating: number | null };

/**
 * Fold consecutive same-`pyramidGroup` conditions into group segments; everything
 * else stays a single. Consecutive is the right rule here: the adapter preserves the
 * backend's condition order and a group's members arrive adjacent, so we never
 * reorder the veteran's list — a group of one (or a lone member left after filtering)
 * degrades to a plain single row, exactly today's behavior.
 */
function segment(list: CondVM[]): Segment[] {
  const out: Segment[] = [];
  let i = 0;
  while (i < list.length) {
    const c = list[i];
    const group = c.pyramidGroup;
    if (group) {
      // Collect the adjacent run sharing this group label.
      const members: CondVM[] = [c];
      let j = i + 1;
      while (j < list.length && list[j].pyramidGroup === group) {
        members.push(list[j]);
        j++;
      }
      if (members.length >= 2) {
        // The primary is the backend-marked effective member; fall back to the
        // highest-rated (then first) so a group never renders headerless.
        const primary =
          members.find((m) => m.pyramidPrimary) ??
          [...members].sort((a, b) => (b.rating ?? -1) - (a.rating ?? -1))[0];
        const groupRating =
          primary.pyramidGroupRating ?? primary.rating ?? null;
        out.push({ kind: "group", label: group, primary, members, groupRating });
        i = j;
        continue;
      }
    }
    out.push({ kind: "single", cond: c });
    i++;
  }
  return out;
}

/**
 * Full conditions list: a filter-chip row + a list + a collapsible "Not filing"
 * section for conditions the veteran excluded from their claim.
 * Desktop (>=760): a table mirroring web.jsx ConditionsView/CondTable.
 * Mobile (<760): condition cards mirroring screens1.jsx ConditionsScreen/ConditionCard.
 *
 * "Don't include in my claim" (owner-set, reversible): an excluded condition is
 * VALID but not being filed for, so the SERVER already dropped it from the combined
 * rating + pay. Here it moves OUT of the main list (and out of its pyramiding group)
 * into a de-emphasized, collapsed "Not filing (N)" section with a one-tap Include —
 * exactly reversible. The included list groups/filters as before, untouched.
 */
export function ConditionsList({ conditions, updatedIds, onChanged }: ConditionsListProps) {
  const router = useRouter();

  // Split included vs excluded up-front. Grouping/filtering operate ONLY on the
  // included set, so an excluded condition leaves its pyramiding group cleanly.
  const active = conditions.filter((c) => !c.excludedFromClaim);
  const excluded = conditions.filter((c) => c.excludedFromClaim);

  // Default filter (P1-27): "Ready" only when it would actually show something;
  // otherwise "All" — a first visit with zero ready conditions must never open
  // onto an apparently empty list.
  const [filter, setFilter] = useState<FilterId>(() =>
    active.some((c) => c.ready) ? "ready" : "all",
  );
  const updated = new Set(updatedIds ?? []);

  // Per-row overflow menu (which row's ⋯ menu is open) + in-flight toggle guard.
  const [menuFor, setMenuFor] = useState<number | null>(null);
  const [busyId, setBusyId] = useState<number | null>(null);
  const [actionError, setActionError] = useState<string | null>(null);
  const [notFilingOpen, setNotFilingOpen] = useState(false);

  // Close the overflow menu on any outside click.
  useEffect(() => {
    if (menuFor === null) return;
    const close = () => setMenuFor(null);
    document.addEventListener("click", close);
    return () => document.removeEventListener("click", close);
  }, [menuFor]);

  async function toggleExcluded(cond: CondVM, excludedNext: boolean) {
    if (busyId != null) return;
    setMenuFor(null);
    setBusyId(cond.id);
    setActionError(null);
    try {
      const res = await setConditionExcluded(cond.id, excludedNext);
      if (!res.ok) throw new Error(`exclude failed: ${res.status}`);
      // Refetch BOTH the conditions and the server-authoritative combined rating.
      // On web, router.refresh() re-runs the RSC tree (conditions + the rating
      // header that reads GET /claim/combined-rating), so the hero/pay update
      // automatically. Native passes onChanged (router.refresh is a no-op there).
      if (onChanged) onChanged();
      else router.refresh();
    } catch {
      setActionError(
        excludedNext
          ? "Couldn't update that just now. Your claim is unchanged — please try again."
          : "Couldn't re-include that just now. Please try again.",
      );
    } finally {
      setBusyId(null);
    }
  }

  const filters: { id: FilterId; label: string; count: number }[] = [
    { id: "all", label: "All", count: active.length },
    { id: "ready", label: "Ready", count: active.filter((c) => c.ready).length },
    { id: "needswork", label: "Needs work", count: active.filter((c) => !c.ready).length },
    { id: "presumptive", label: "Presumptive", count: active.filter((c) => c.presumptive).length },
  ];

  let list = active;
  if (filter === "ready") list = active.filter((c) => c.ready);
  else if (filter === "needswork") list = active.filter((c) => !c.ready);
  else if (filter === "presumptive") list = active.filter((c) => c.presumptive);

  // Group WITHIN the filtered view so filters/sorting keep working — a group only
  // forms from rows that survived the active filter.
  const segments = segment(list);

  // The overflow menu for one row (excluded state drives the label). Rendered as a
  // SIBLING of the Link so it never nests an interactive control inside the anchor.
  const rowMenu = (c: CondVM) => (
    <span
      className={styles.menuWrap}
      onClick={(e) => {
        // The menu lives inside the clickable row; stop the click from following
        // the row Link / toggling the wrong menu.
        e.preventDefault();
        e.stopPropagation();
      }}
    >
      <button
        type="button"
        className={styles.menuBtn}
        aria-label={`More options for ${c.name}`}
        aria-haspopup="menu"
        aria-expanded={menuFor === c.id}
        disabled={busyId != null}
        onClick={(e) => {
          e.preventDefault();
          e.stopPropagation();
          setMenuFor((cur) => (cur === c.id ? null : c.id));
        }}
      >
        <span aria-hidden="true">⋯</span>
      </button>
      {menuFor === c.id && (
        <span className={styles.menu} role="menu">
          <button
            type="button"
            className={styles.menuItem}
            role="menuitem"
            disabled={busyId != null}
            onClick={() => toggleExcluded(c, true)}
          >
            Don&rsquo;t include in my claim
          </button>
        </span>
      )}
    </span>
  );

  // One desktop table row for a condition (shared by grouped + ungrouped).
  const tableRow = (c: CondVM) => (
    <div key={c.id} className={styles.trowWrap}>
      <Link href={condHref(c.id)} className={styles.trow}>
        <span className={styles.tdName}>
          <span className={styles.tdNameTop}>
            {/* Ellipsized cell — the title carries the full name. */}
            <b title={c.name}>{c.name}</b>
            {updated.has(c.id) && (
              <Pill tone="green" icon="sparkle">
                Updated
              </Pill>
            )}
            {c.presumptive && (
              <Pill tone="indigo" icon="sparkle" wrap>
                {c.presumptive}
              </Pill>
            )}
          </span>
          <small>
            {c.system} · VASRD {c.vasrdCode ?? DASH}
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
      {rowMenu(c)}
    </div>
  );

  // One mobile card for a condition (shared by grouped + ungrouped).
  const mobileCard = (c: CondVM) => (
    <div key={c.id} className={styles.cardWrap}>
      <Link href={condHref(c.id)} className={styles.card}>
        <span className={styles.cardMain}>
          <span className={styles.cardHead}>
            <span className={styles.cardName}>{c.name}</span>
            {updated.has(c.id) && (
              <Pill tone="green" icon="sparkle">
                Updated
              </Pill>
            )}
            {c.presumptive && (
              <Pill tone="indigo" icon="sparkle" wrap>
                {c.presumptive}
              </Pill>
            )}
          </span>
          <span className={styles.cardMeta}>
            {c.system} · VASRD {c.vasrdCode ?? DASH}
          </span>
          <TriadDots triad={c.triad} />
        </span>
        <span className={styles.cardRight}>
          {c.rating == null ? (
            <span className={`${styles.cardRate} ${styles.cardRatePending}`}>Not yet rated</span>
          ) : (
            <span className={styles.cardRate}>{c.rating}%</span>
          )}
          <Icon name="chevron" size={18} stroke={2.2} />
        </span>
      </Link>
      {rowMenu(c)}
    </div>
  );

  return (
    <section className={styles.wrap}>
      {/* ── Filter chips ── */}
      <div className={styles.chips}>
        {filters.map((f) => (
          <Chip
            key={f.id}
            active={filter === f.id}
            count={f.count}
            onClick={() => setFilter(f.id)}
          >
            {f.label}
          </Chip>
        ))}
      </div>

      {/* ── Triad key ── */}
      <div className={styles.triadKey}>
        <span>
          <span className={styles.kDot} style={{ background: "var(--leg-dx)" }} /> Dx diagnosis
        </span>
        <span>
          <span className={styles.kDot} style={{ background: "var(--leg-is)" }} /> IS in-service
        </span>
        <span>
          <span className={styles.kDot} style={{ background: "var(--leg-nx)" }} /> Nx nexus
        </span>
      </div>

      {actionError && (
        <p role="alert" className={styles.actErr}>
          {actionError}
        </p>
      )}

      {list.length === 0 ? (
        // Filter-specific empty state (P1-27): included conditions EXIST (the
        // page-level "No conditions yet" gate handles the true-empty claim before
        // this component renders) — say so, and offer the way back. When ALL
        // conditions are excluded, "all" is empty too; the "Not filing" section
        // below still renders so nothing is lost.
        active.length === 0 && excluded.length > 0 ? null : (
          <div className={styles.empty}>
            <p className={styles.emptyMsg}>No conditions match this filter.</p>
            <button type="button" className={styles.emptyReset} onClick={() => setFilter("all")}>
              Show all {active.length} condition{active.length === 1 ? "" : "s"}
            </button>
          </div>
        )
      ) : (
        <>
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
            {segments.map((seg) =>
              seg.kind === "single" ? (
                tableRow(seg.cond)
              ) : (
                <div key={`g-${seg.primary.id}`} className={styles.groupBlock}>
                  <PyramidGroupHeader
                    label={seg.label}
                    count={seg.members.length}
                    groupRating={seg.groupRating}
                    primaryName={seg.primary.name}
                  />
                  {seg.members.map((c) => tableRow(c))}
                </div>
              ),
            )}
          </div>

          {/* ── Mobile: condition cards ── */}
          <div className={styles.cards}>
            {segments.map((seg) =>
              seg.kind === "single" ? (
                mobileCard(seg.cond)
              ) : (
                <div key={`g-${seg.primary.id}`} className={styles.groupBlockCards}>
                  <PyramidGroupHeader
                    label={seg.label}
                    count={seg.members.length}
                    groupRating={seg.groupRating}
                    primaryName={seg.primary.name}
                  />
                  {seg.members.map((c) => mobileCard(c))}
                </div>
              ),
            )}
          </div>
        </>
      )}

      {/* ── "Not filing" section — excluded conditions, collapsed by default ── */}
      {excluded.length > 0 && (
        <div className={styles.notFiling}>
          <button
            type="button"
            className={styles.notFilingToggle}
            aria-expanded={notFilingOpen}
            onClick={() => setNotFilingOpen((v) => !v)}
          >
            <Icon name="flag" size={16} stroke={2.2} />
            Not filing ({excluded.length})
            <span className={styles.notFilingChev} data-open={notFilingOpen ? "1" : "0"}>
              <Icon name="chevDown" size={15} stroke={2.2} />
            </span>
          </button>
          {notFilingOpen && (
            <>
              <p className={styles.notFilingNote}>
                These aren&apos;t counted in your combined rating. Re-include any of them anytime.
              </p>
              <ul className={styles.excludedList}>
                {excluded.map((c) => (
                  <li key={c.id} className={styles.excludedRow}>
                    <Link href={condHref(c.id)} className={styles.excludedMain}>
                      <span className={styles.excludedName} title={c.name}>
                        {c.name}
                      </span>
                      <span className={styles.excludedMeta}>
                        {c.system} · VASRD {c.vasrdCode ?? DASH}
                        {c.rating != null && ` · ${c.rating}%`}
                      </span>
                    </Link>
                    <button
                      type="button"
                      className={styles.includeBtn}
                      disabled={busyId != null}
                      aria-label={`Include ${c.name} in my claim`}
                      onClick={() => toggleExcluded(c, false)}
                    >
                      <Icon name="plus2" size={14} stroke={2.4} /> Include
                    </button>
                  </li>
                ))}
              </ul>
            </>
          )}
        </div>
      )}
    </section>
  );
}

/**
 * Turn the backend's canonical group label ("Mental Health (§4.130)") into a warm
 * list header ("Mental health — VA rates these together"). Unknown labels degrade to
 * "<label> — VA rates these together" so a future group still reads sensibly.
 */
function groupHeading(label: string): string {
  const base = label.replace(/\s*\(§[\d.]+\)\s*$/, "").trim().toLowerCase();
  const nice = base ? base.charAt(0).toUpperCase() + base.slice(1) : label;
  return `${nice} — VA rates these together`;
}

interface PyramidGroupHeaderProps {
  label: string;
  count: number;
  groupRating: number | null;
  primaryName: string;
}

/**
 * Inline treatment shown above a pyramiding group's member rows: a header naming the
 * group, a compact combined line (the strongest counts, they don't add), and a short
 * plain-language "why" linking to the /learn/how-va-rates explainer. Tokens-only;
 * reuses Pill. `/learn/how-va-rates` is a static route, so a plain Link is native-safe.
 */
function PyramidGroupHeader({ label, count, groupRating, primaryName }: PyramidGroupHeaderProps) {
  return (
    <div className={styles.groupHeader}>
      <div className={styles.groupHeaderTop}>
        <span className={styles.groupTitle}>{groupHeading(label)}</span>
        <Pill tone="indigo" icon="info">
          {count} rated as one
        </Pill>
      </div>
      <p className={styles.groupCombined}>
        {groupRating == null ? (
          <>These are combined into one rating — the strongest counts, they don&apos;t add.</>
        ) : (
          <>
            These combine to about <b>{groupRating}%</b> — the strongest ({primaryName}) sets the
            rating, they don&apos;t add up.
          </>
        )}
      </p>
      <p className={styles.groupWhy}>
        The VA rates related conditions together under one formula, so their percentages
        don&apos;t stack.{" "}
        <Link href="/learn/how-va-rates" className={styles.groupWhyLink}>
          How VA rates conditions
        </Link>
      </p>
    </div>
  );
}
