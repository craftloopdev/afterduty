"use client";

import { Suspense, useCallback, useEffect, useRef, useState } from "react";
import { useRouter, useSearchParams } from "next/navigation";
import type { DocStatus, DocVM } from "@/lib/models/vm";
import { toFacts, type FactVM } from "@/lib/adapters/evidence";
import { Icon, type IconName } from "@/components/ui/Icon";
import { Button } from "@/components/ui/Button";
import { Modal } from "@/components/ui/Modal";
import { NATIVE } from "@/lib/platform";
import { armAnalysisPulse } from "@/lib/jobs";
import { tapLight, notifyError, notifySuccess } from "@/lib/native/haptics";
import { deleteEvidence, evidenceDownloadHref, fetchEvidenceFacts } from "./evidence-actions";
import styles from "./DocumentsView.module.css";

const BADGE: Record<DocStatus, { cls: string; icon?: IconName; stroke?: number }> = {
  done: { cls: styles.ok, icon: "check", stroke: 3 },
  paused: { cls: styles.paused, icon: "clock", stroke: 2.4 },
  error: { cls: styles.err, icon: "alert", stroke: 2.4 },
  processing: { cls: styles.pending },
  // Stored/uploaded but not yet AI-read — neutral, never a green done badge.
  queued: { cls: styles.pending, icon: "file", stroke: 2 },
};

// How long the citation-focus highlight ring stays on the card (P1-30).
const FOCUS_HIGHLIGHT_MS = 2600;

/**
 * Reads the `?focus=<evidenceId>` deep-link param that chat citations emit
 * (lib/cite.ts builds `/documents?focus=<id>` for `cite:doc/<id>` links).
 * Isolated in its own component so the `useSearchParams` read can sit under a
 * Suspense boundary — required for the native static export's prerender.
 */
function FocusParamListener({ onFocus }: { onFocus: (id: number) => void }) {
  const params = useSearchParams();
  const raw = params.get("focus");
  useEffect(() => {
    if (!raw) return;
    const id = Number.parseInt(raw, 10);
    if (Number.isFinite(id) && id > 0) onFocus(id);
  }, [raw, onFocus]);
  return null;
}

/**
 * "What we found" (P1-30): the extracted facts for one processed document.
 * Renders exactly what the facts endpoint returned — deterministic, no client
 * synthesis, and never the LLM confidence number.
 */
function WhatWeFound({ docId }: { docId: number }) {
  const [state, setState] = useState<
    { s: "loading" } | { s: "error" } | { s: "done"; facts: FactVM[] }
  >({ s: "loading" });

  useEffect(() => {
    let alive = true;
    (async () => {
      try {
        const res = await fetchEvidenceFacts(docId);
        if (!res.ok) throw new Error(`facts ${res.status}`);
        const facts = toFacts(await res.json());
        if (alive) setState({ s: "done", facts });
      } catch {
        if (alive) setState({ s: "error" });
      }
    })();
    return () => {
      alive = false;
    };
  }, [docId]);

  if (state.s === "loading") return <small className={styles.factsNote}>Loading…</small>;
  if (state.s === "error")
    return (
      <small className={styles.factsNote} role="alert">
        Couldn&apos;t load what we found. Please try again.
      </small>
    );
  if (state.facts.length === 0)
    return <small className={styles.factsNote}>No facts were extracted from this document.</small>;
  return (
    <ul className={styles.factList}>
      {state.facts.map((f, i) => (
        <li key={i} className={styles.factRow}>
          <span className={styles.factLabel}>
            {f.label}
            {f.date ? ` · ${f.date}` : ""}
          </span>
          <span className={styles.factValue}>{f.value}</span>
        </li>
      ))}
    </ul>
  );
}

/**
 * `onChanged` refreshes the list after a delete — native passes its loader's
 * refetch (router.refresh() is a no-op on the static export); web falls back
 * to router.refresh().
 */
export function DocumentsView({ docs, onChanged }: { docs: DocVM[]; onChanged?: () => void }) {
  const router = useRouter();
  const [menuFor, setMenuFor] = useState<number | null>(null);
  const [factsFor, setFactsFor] = useState<Set<number>>(new Set());
  const [confirmDoc, setConfirmDoc] = useState<DocVM | null>(null);
  const [deleteBusy, setDeleteBusy] = useState(false);
  const [deleteError, setDeleteError] = useState<string | null>(null);
  const [focusId, setFocusId] = useState<number | null>(null);
  const cardRefs = useRef(new Map<number, HTMLDivElement>());

  // Close the overflow menu on any outside click.
  useEffect(() => {
    if (menuFor === null) return;
    const close = () => setMenuFor(null);
    document.addEventListener("click", close);
    return () => document.removeEventListener("click", close);
  }, [menuFor]);

  // Citation deep link (P1-30): scroll the cited card into view + highlight it.
  useEffect(() => {
    if (focusId === null) return;
    const el = cardRefs.current.get(focusId);
    // jsdom has no scrollIntoView — guard so tests and odd embeds never throw.
    el?.scrollIntoView?.({ behavior: "smooth", block: "center" });
    const t = setTimeout(() => setFocusId(null), FOCUS_HIGHLIGHT_MS);
    return () => clearTimeout(t);
  }, [focusId]);

  const onFocusParam = useCallback((id: number) => setFocusId(id), []);

  const toggleFacts = (id: number) =>
    setFactsFor((prev) => {
      const next = new Set(prev);
      if (next.has(id)) next.delete(id);
      else next.add(id);
      return next;
    });

  const closeConfirm = () => {
    if (deleteBusy) return;
    setConfirmDoc(null);
    setDeleteError(null);
  };

  const onDelete = async () => {
    if (!confirmDoc) return;
    tapLight();
    setDeleteBusy(true);
    setDeleteError(null);
    try {
      const res = await deleteEvidence(confirmDoc.id);
      if (res.ok) {
        notifySuccess();
        // The backend cleared the synthesis/gap timestamps — analysis re-runs.
        // Arm the cross-screen pipeline pulse so that re-run is visible (P1-29).
        armAnalysisPulse();
        setConfirmDoc(null);
        if (onChanged) onChanged();
        else router.refresh();
      } else {
        notifyError();
        setDeleteError(
          res.status === 401
            ? "Please sign in again."
            : "Couldn't delete the document. Please try again.",
        );
      }
    } catch {
      notifyError();
      setDeleteError("Couldn't delete the document. Please try again.");
    } finally {
      setDeleteBusy(false);
    }
  };

  return (
    <div className={styles.grid}>
      <Suspense fallback={null}>
        <FocusParamListener onFocus={onFocusParam} />
      </Suspense>
      {docs.map((d) => {
        const badge = BADGE[d.status];
        const factsOpen = factsFor.has(d.id);
        return (
          <div
            key={d.id}
            ref={(el) => {
              if (el) cardRefs.current.set(d.id, el);
              else cardRefs.current.delete(d.id);
            }}
            className={[styles.card, focusId === d.id && styles.focused].filter(Boolean).join(" ")}
            data-focused={focusId === d.id || undefined}
          >
            <div className={styles.head}>
              <span
                className={styles.ic}
                style={{ color: d.color, background: `color-mix(in srgb, ${d.color} 14%, transparent)` }}
              >
                <Icon name={d.icon} size={22} />
              </span>
              <span className={styles.menuWrap}>
                <button
                  type="button"
                  className={styles.menuBtn}
                  aria-label={`More options for ${d.name}`}
                  aria-haspopup="menu"
                  aria-expanded={menuFor === d.id}
                  onClick={(e) => {
                    e.stopPropagation();
                    setMenuFor((cur) => (cur === d.id ? null : d.id));
                  }}
                >
                  <span aria-hidden="true">⋯</span>
                </button>
                {menuFor === d.id && (
                  <div className={styles.menu} role="menu">
                    {/* Native has no BFF and an <a> can't carry a Bearer —
                        download is web-only until the filesystem plugin lands. */}
                    {!NATIVE && (
                      <a
                        className={styles.menuItem}
                        role="menuitem"
                        href={evidenceDownloadHref(d.id)}
                        download
                        onClick={() => setMenuFor(null)}
                      >
                        Download
                      </a>
                    )}
                    <button
                      type="button"
                      className={`${styles.menuItem} ${styles.menuDanger}`}
                      role="menuitem"
                      onClick={() => {
                        setMenuFor(null);
                        setDeleteError(null);
                        setConfirmDoc(d);
                      }}
                    >
                      Delete
                    </button>
                  </div>
                )}
              </span>
            </div>
            <b className={styles.name} title={d.name}>
              {d.name}
            </b>
            <small className={styles.kind}>{d.kind}</small>
            <div className={styles.foot}>
              <span className={badge.cls}>
                {badge.icon && <Icon name={badge.icon} size={13} stroke={badge.stroke} />} {d.statusLabel}
              </span>
              {d.statusDetail && <small className={styles.detail}>{d.statusDetail}</small>}
            </div>
            {d.processed && (
              <>
                <button
                  type="button"
                  className={styles.factsToggle}
                  aria-expanded={factsOpen}
                  onClick={() => toggleFacts(d.id)}
                >
                  <Icon
                    name="chevron"
                    size={13}
                    stroke={2.4}
                    className={factsOpen ? styles.chevOpen : styles.chev}
                  />
                  What we found
                </button>
                {factsOpen && <WhatWeFound docId={d.id} />}
              </>
            )}
          </div>
        );
      })}

      <Modal open={confirmDoc !== null} onClose={closeConfirm} title="Delete this document?" size="sm">
        <p className={styles.modalBody}>
          Deleting &ldquo;{confirmDoc?.name}&rdquo; removes it from your analysis &mdash; your
          conditions will update.
        </p>
        {deleteError && (
          <div className={styles.modalError} role="alert">
            {deleteError}
          </div>
        )}
        <div className={styles.modalActions}>
          <Button variant="ghost" full onClick={closeConfirm} disabled={deleteBusy}>
            Cancel
          </Button>
          <Button
            variant="primary"
            full
            className={styles.confirmDanger}
            onClick={onDelete}
            loading={deleteBusy}
          >
            Delete document
          </Button>
        </div>
      </Modal>
    </div>
  );
}
