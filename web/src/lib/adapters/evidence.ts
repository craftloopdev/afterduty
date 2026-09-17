import type { EvidenceResponse } from "@/lib/models/api";
import type { DocStatus, DocVM } from "@/lib/models/vm";
import { resolveDocIcon } from "@/lib/theme/tokens";

function prettyKind(sourceType: string | undefined, classification: string | null | undefined): string {
  const c = classification?.trim();
  if (c) return c;
  switch ((sourceType ?? "").toLowerCase()) {
    case "chat":
      return "Statement";
    case "quick_add":
      return "Added";
    default:
      return "Document";
  }
}

interface MappedStatus {
  status: DocStatus;
  statusLabel: string;
  statusDetail: string | null;
}

// A free user's documents are stored but never AI-read (the pipeline is
// Pro-gated) — the badge must say so instead of a false green "Processed",
// so Documents agrees with Home's upgrade framing.
const FREE_STORED: MappedStatus = {
  status: "queued",
  statusLabel: "Stored — AI analysis is a Pro feature",
  statusDetail: null,
};

// The Spring pipeline writes: queued | pending | processing | processed |
// error | deferred_usage_limit (PipelineService, IntakeController,
// UsageResetJob). "queued" is stamped at upload, "processing" when extraction
// actually starts, "processed" only once the document has been read — the UI
// must never show a done badge before that. Raw enums never reach the screen.
function mapStatus(raw: string, message: string | null, free: boolean): MappedStatus {
  switch (raw) {
    case "queued":
    case "pending":
      // Uploaded, not yet read. Neutral — not green, not spinning.
      if (free) return FREE_STORED;
      return { status: "queued", statusLabel: "Uploaded", statusDetail: null };
    case "processing":
      return { status: "processing", statusLabel: "Reading…", statusDetail: null };
    case "processed":
      // Legacy rows for free users carry "processed" stamped at upload —
      // nothing was actually read, so keep the honest stored badge.
      if (free) return FREE_STORED;
      // The pipeline's processingMessage here is internal ("Queued for async
      // extraction") — don't show it under a green badge.
      return { status: "done", statusLabel: "Processed", statusDetail: null };
    case "error":
      return { status: "error", statusLabel: "Couldn't process", statusDetail: message };
    case "deferred_usage_limit":
      return { status: "paused", statusLabel: "Paused — monthly AI limit reached", statusDetail: message };
    default:
      // Any status the backend grows later — in flight.
      return { status: "processing", statusLabel: "Processing", statusDetail: null };
  }
}

// ── Extracted facts ("What we found", P1-30) ────────────────────────────────
// Wire shape of GET /claim/evidence/{id}/facts — Spring's AtomDto:
// { type, value, source, confidence, date }. Local to this adapter until the
// endpoint graduates into models/api.ts. `confidence` is an LLM-derived number
// and is deliberately NEVER surfaced (house rule: no LLM-derived numbers).
export interface EvidenceFactDto {
  type?: string | null;
  value?: string | null;
  source?: string | null;
  confidence?: number | null;
  date?: string | null;
}

export interface FactVM {
  /** Prettified fact type, e.g. "service_period" → "Service period". */
  label: string;
  /** The extracted fact text, verbatim from the API — no client synthesis. */
  value: string;
  /** The fact's date string as the API sent it, or null. */
  date: string | null;
}

/** Deterministic snake_case → sentence-case label. Never invents words. */
function factLabel(type: string | null | undefined): string {
  const t = (type ?? "").trim().replace(/_/g, " ");
  if (!t) return "Fact";
  return t[0].toUpperCase() + t.slice(1);
}

/** Map one wire fact; facts with no value carry no information — drop them. */
export function toFact(raw: EvidenceFactDto): FactVM | null {
  const value = raw.value?.trim();
  if (!value) return null;
  return {
    label: factLabel(raw.type),
    value,
    date: raw.date?.trim() || null,
  };
}

/** Defensive array mapping — anything non-array becomes an empty list. */
export function toFacts(raw: unknown): FactVM[] {
  if (!Array.isArray(raw)) return [];
  return raw
    .filter((f): f is EvidenceFactDto => f != null && typeof f === "object")
    .map(toFact)
    .filter((f): f is FactVM => f !== null);
}

export function toDoc(e: EvidenceResponse, opts: { free?: boolean } = {}): DocVM {
  const kindRaw = e.aiClassification?.trim() || e.sourceType || "Document";
  const { icon, color } = resolveDocIcon(kindRaw);
  const { status, statusLabel, statusDetail } = mapStatus(
    e.processingStatus || "pending",
    e.processingMessage?.trim() || null,
    opts.free === true,
  );
  return {
    id: e.id,
    name: e.filename?.trim() || e.aiSummary?.trim() || "Untitled",
    kind: prettyKind(e.sourceType, e.aiClassification),
    icon,
    color,
    status,
    statusLabel,
    statusDetail,
    processed: status === "done",
  };
}
