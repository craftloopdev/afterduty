// Pure presentation helpers for the Service History screen + evidence modal.
// The screen renders RECONCILED conclusions; the modal opens each conclusion's
// "receipts" — the contributing raw records (`sources[]`), humanized, with a ✓
// on whichever raw value(s) the conclusion adopted. Everything here is
// deterministic display of already-extracted facts: no derivation, no math
// beyond a whole-year sum. Kept framework-free so it unit-tests without React.
import type { ServicePeriodVM, ServiceSourceVM } from "@/lib/models/vm";
import { serviceTotalYears } from "@/lib/format";

/**
 * Humanize a backend `docType` token into calm, veteran-facing copy. The
 * backend emits classifier tokens ("DD-214", "personnel_record", "orders",
 * "manual", …); we normalize casing/underscores and map the well-known ones to
 * their familiar names, falling back to a title-cased version of whatever we got
 * (so a new classifier label still reads sensibly rather than raw). `null`/empty
 * → "Document" (a self-statement row uses `docTypeLabel` via its "manual" token).
 */
export function docTypeLabel(docType: string | null | undefined): string {
  const raw = docType?.trim();
  if (!raw) return "Document";
  const key = raw.toLowerCase().replace(/[\s_-]+/g, " ").trim();
  const KNOWN: Record<string, string> = {
    "dd214": "DD-214",
    "dd 214": "DD-214",
    "dd form 214": "DD-214",
    "manual": "Self-statement",
    "self statement": "Self-statement",
    "statement": "Self-statement",
    "orders": "Orders",
    "military orders": "Orders",
    "personnel record": "Personnel record",
    "service personnel record": "Personnel record",
    "ngb 22": "NGB-22",
    "ngb22": "NGB-22",
    "ngb form 22": "NGB-22",
    "va decision": "VA decision letter",
    "decision letter": "VA decision letter",
    "medical record": "Medical record",
  };
  const hit = KNOWN[key.replace(/-/g, "")] ?? KNOWN[key];
  if (hit) return hit;
  // Unknown token: title-case each word, but keep short all-caps acronyms intact.
  return raw
    .replace(/[_-]+/g, " ")
    .split(/\s+/)
    .filter(Boolean)
    .map((w) => (w.length <= 3 && w === w.toUpperCase() ? w : w.charAt(0).toUpperCase() + w.slice(1).toLowerCase()))
    .join(" ");
}

/**
 * A calm authority phrase for a source's `authorityRank` (higher wins in the
 * backend's reconciliation). Buckets — not exact numbers — because the veteran
 * doesn't need the scoring, just "how much does VA lean on this document":
 *   ≥90 → "authoritative" (DD-214-class discharge documents)
 *   ≥50 → "official record"
 *   >0  → "supporting"
 * A self-statement (the manual row, rank null/0) → "self-reported".
 * Returns null when there's no rank to describe (renders nothing extra).
 */
export function authorityLabel(
  rank: number | null | undefined,
  docType?: string | null,
): string | null {
  const isSelf = (docType ?? "").trim().toLowerCase() === "manual";
  if (isSelf) return "self-reported";
  if (rank == null) return null;
  if (rank >= 90) return "authoritative";
  if (rank >= 50) return "official record";
  if (rank > 0) return "supporting";
  return "self-reported";
}

/** "DD-214 · authoritative" — the source's header line. Drops the authority half when unknown. */
export function sourceHeading(s: ServiceSourceVM): string {
  const doc = docTypeLabel(s.docType);
  const auth = authorityLabel(s.authorityRank, s.docType);
  return auth ? `${doc} · ${auth}` : doc;
}

/**
 * Which of a source's raw values the CONCLUSION adopted. We compare each raw
 * field to the conclusion's chosen value and mark the matches with a ✓ so the
 * veteran can see, per document, exactly which facts the reconciler took from
 * it. Comparison is tolerant:
 *   - dates match on the calendar day (ignores any time component / formatting);
 *   - text matches case-insensitively after collapsing whitespace, so "U.S.
 *     Army" ~ "Army" would NOT match but "Army " ~ "Army" would (we compare the
 *     RAW value to the adopted value, only normalizing incidental formatting).
 * A raw value the source didn't carry (null) is never "adopted".
 */
export interface AdoptedFlags {
  branch: boolean;
  dates: boolean;
  mos: boolean;
  rank: boolean;
}

function normText(v: string | null | undefined): string | null {
  const t = v?.trim().replace(/\s+/g, " ").toLowerCase();
  return t ? t : null;
}

/** ISO/date-ish string → "YYYY-MM-DD" calendar key, or null when unparseable/absent. */
function dayKey(v: string | null | undefined): string | null {
  const t = v?.trim();
  if (!t) return null;
  // Fast path for the "YYYY-MM-DD" the contract sends (avoids TZ drift from Date).
  const m = /^(\d{4})-(\d{2})-(\d{2})/.exec(t);
  if (m) return `${m[1]}-${m[2]}-${m[3]}`;
  const d = new Date(t);
  if (Number.isNaN(d.getTime())) return null;
  return d.toISOString().slice(0, 10);
}

function textAdopted(raw: string | null | undefined, chosen: string | null | undefined): boolean {
  const r = normText(raw);
  const c = normText(chosen);
  return r != null && c != null && r === c;
}

function dateAdopted(raw: string | null | undefined, chosen: string | null | undefined): boolean {
  const r = dayKey(raw);
  const c = dayKey(chosen);
  return r != null && c != null && r === c;
}

/**
 * Compute the ✓ flags for one source against its owning conclusion. `dates` is a
 * single flag covering the start/end pair: it's ✓ when the source's raw span
 * matches the conclusion's adopted span on BOTH ends that the source carried
 * (the whole date range is one adopted fact — a partial-date-only match isn't
 * "adopted the dates").
 */
export function adoptedFlags(source: ServiceSourceVM, period: ServicePeriodVM): AdoptedFlags {
  const branch = textAdopted(source.rawBranch, period.branch);
  const mos = textAdopted(source.rawMos, period.mos);
  const rank = textAdopted(source.rawRank, period.rank);
  // Dates: the source must carry at least a start, and every date it carries
  // must match the conclusion's corresponding adopted date.
  const hasStart = dayKey(source.rawStart) != null;
  const startOk = source.rawStart == null ? true : dateAdopted(source.rawStart, period.startDate);
  const endOk = source.rawEnd == null ? true : dateAdopted(source.rawEnd, period.endDate);
  const dates = hasStart && startOk && endOk;
  return { branch, dates, mos, rank };
}

/**
 * Whether this conclusion carries a veteran override (Service History P3). The
 * backend appends "Corrected by you." to the reasoning trace when an override is
 * applied at top authority; the modal reads this to show the "Corrected by you"
 * marker and offer "Undo" instead of "Correct it". A conclusion with no reasoning
 * (older/unreconciled) is never marked corrected.
 */
export function isCorrectedByYou(
  period: Pick<ServicePeriodVM, "reasoning">,
): boolean {
  return (period.reasoning ?? "").toLowerCase().includes("corrected by you");
}

/**
 * The corrected total for the screen header: sum of the conclusions' own
 * `totalYears` when the backend reconciled them; otherwise fall back to
 * `serviceTotalYears` over the whole list (the same date arithmetic the profile
 * card uses). Returns null when neither path yields a ≥1-year figure — the
 * header then omits the line rather than claim "0 years".
 */
export function correctedTotalYears(
  periods: Array<Pick<ServicePeriodVM, "startDate" | "endDate" | "totalYears">>,
): number | null {
  const reconciled = periods.filter((p) => typeof p.totalYears === "number");
  if (reconciled.length > 0) {
    const sum = reconciled.reduce((acc, p) => acc + (p.totalYears ?? 0), 0);
    if (sum >= 1) return sum;
    // Reconciled but sub-year totals — still try the date fallback below so a
    // long unreconciled sibling period isn't dropped from the headline.
  }
  return serviceTotalYears(periods);
}
