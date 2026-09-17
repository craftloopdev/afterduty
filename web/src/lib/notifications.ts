// Claim-journal notifications (roadmap §5 item 6 / P1-8). The backend writes
// Notification rows at the generation flip with deterministic, template-built
// text; this module holds the client-safe types + pure helpers both build
// targets share (web RSC loaders AND the native DirectApiClient path — so NO
// `server-only` here, same rule as jobs.ts). Every veteran-facing string here
// is a fixed template over backend-computed numbers — no LLM ever writes a
// number the veteran sees (the review's kill-list rule).
//
// NOTE for the integrator: `NotificationDto`/`NotificationsEnvelope` are raw
// backend shapes and belong in lib/models/api.ts; the VM shapes in
// lib/models/vm.ts. They live here because this change may not edit those files.

import type { IconName } from "@/components/ui/Icon";
import { triadLeg, type TriadLegKey } from "@/lib/theme/tokens";

// ── Raw shapes (GET /api/notifications, pinned contract) ────────────────────

export type NotificationSeverity = "info" | "success" | "warning";

export interface NotificationRatingChange {
  conditionId: number;
  name: string;
  from: number | null;
  to: number | null;
}

export interface NotificationLegChange {
  conditionId: number;
  name: string;
  leg: string; // "dx" | "is" | "nx"
  from: string;
  to: string;
}

export interface NotificationConditionRef {
  conditionId: number;
  name: string;
}

/** metadata of an `analysis_updated` digest row (all fields defensive). */
export interface AnalysisUpdatedMetadata {
  runId?: string;
  ratingChanges?: NotificationRatingChange[];
  legChanges?: NotificationLegChange[];
  gapsClosed?: number;
  gapsOpened?: number;
  addedConditions?: NotificationConditionRef[];
  retiredConditions?: { name: string }[];
  changedConditionIds?: number[];
}

export interface NotificationDto {
  id: number;
  claimId?: number | null;
  eventType?: string;
  title?: string;
  body?: string;
  severity?: string;
  conditionId?: number | null;
  metadata?: Record<string, unknown> | null;
  isRead?: boolean;
  createdAt?: string;
}

export interface NotificationsEnvelope {
  notifications: NotificationDto[];
  unreadCount: number;
}

// ── View-models ──────────────────────────────────────────────────────────────

export interface NotificationVM {
  id: number;
  eventType: string;
  title: string;
  body: string;
  severity: NotificationSeverity;
  conditionId: number | null;
  metadata: AnalysisUpdatedMetadata | null;
  isRead: boolean;
  createdAt: string;
}

/** One deterministic line of the what-changed digest card. */
export interface DigestLineVM {
  key: string;
  text: string;
  /** Link target (via condHref) when the line concerns a live condition. */
  conditionId: number | null;
}

export interface TimelineDayVM {
  /** Stable group key (local YYYY-MM-DD, or "unknown"). */
  key: string;
  /** e.g. "July 2, 2026". */
  label: string;
  items: NotificationVM[];
}

/** Everything Home + Conditions need from the unread journal, in one read. */
export interface AnalysisUpdatesVM {
  /** Latest unread digest-type notification (analysis_updated/complete), if any. */
  digest: NotificationVM | null;
  /** ALL unread digest-type ids — "Got it" marks the whole family read. */
  digestIds: number[];
  /** From the latest unread analysis_updated metadata — drives "Updated" pills. */
  changedConditionIds: number[];
  /** Any journal entries at all (read or unread) — shows the discreet timeline link. */
  hasAny: boolean;
}

export const EMPTY_ANALYSIS_UPDATES: AnalysisUpdatesVM = {
  digest: null,
  digestIds: [],
  changedConditionIds: [],
  hasAny: false,
};

/** Event types whose unread rows summon the Home digest card. */
const DIGEST_EVENT_TYPES = new Set(["analysis_updated", "analysis_complete"]);

// ── Normalization (defensive — the UI must never crash on a missing field) ──

function toSeverity(s: unknown): NotificationSeverity {
  return s === "success" || s === "warning" ? s : "info";
}

function num(v: unknown): number | null {
  return typeof v === "number" && Number.isFinite(v) ? v : null;
}

function str(v: unknown): string {
  return typeof v === "string" ? v : "";
}

function toMetadata(raw: unknown): AnalysisUpdatedMetadata | null {
  if (raw == null || typeof raw !== "object" || Array.isArray(raw)) return null;
  const o = raw as Record<string, unknown>;
  const arr = (v: unknown): unknown[] => (Array.isArray(v) ? v : []);
  const condRef = (v: unknown): NotificationConditionRef | null => {
    if (v == null || typeof v !== "object") return null;
    const c = v as Record<string, unknown>;
    const id = num(c.conditionId);
    return id == null ? null : { conditionId: id, name: str(c.name) };
  };
  return {
    runId: str(o.runId) || undefined,
    ratingChanges: arr(o.ratingChanges).flatMap((v) => {
      const base = condRef(v);
      if (!base) return [];
      const c = v as Record<string, unknown>;
      return [{ ...base, from: num(c.from), to: num(c.to) }];
    }),
    legChanges: arr(o.legChanges).flatMap((v) => {
      const base = condRef(v);
      if (!base) return [];
      const c = v as Record<string, unknown>;
      return [{ ...base, leg: str(c.leg), from: str(c.from), to: str(c.to) }];
    }),
    gapsClosed: num(o.gapsClosed) ?? 0,
    gapsOpened: num(o.gapsOpened) ?? 0,
    addedConditions: arr(o.addedConditions).flatMap((v) => {
      const base = condRef(v);
      return base ? [base] : [];
    }),
    retiredConditions: arr(o.retiredConditions).flatMap((v) => {
      const name = v != null && typeof v === "object" ? str((v as Record<string, unknown>).name) : "";
      return name ? [{ name }] : [];
    }),
    changedConditionIds: arr(o.changedConditionIds).flatMap((v) => {
      const id = num(v);
      return id == null ? [] : [id];
    }),
  };
}

export function toNotification(raw: NotificationDto): NotificationVM {
  return {
    id: raw.id,
    eventType: str(raw.eventType),
    title: str(raw.title),
    body: str(raw.body),
    severity: toSeverity(raw.severity),
    conditionId: num(raw.conditionId),
    metadata: toMetadata(raw.metadata),
    isRead: raw.isRead === true,
    createdAt: str(raw.createdAt),
  };
}

/** Normalize the endpoint body; garbage/absent degrades to empty, never throws. */
export function toNotificationsEnvelope(raw: unknown): NotificationsEnvelope {
  if (raw == null || typeof raw !== "object") return { notifications: [], unreadCount: 0 };
  const o = raw as { notifications?: unknown; unreadCount?: unknown };
  const list = Array.isArray(o.notifications)
    ? (o.notifications as unknown[]).filter(
        (n): n is NotificationDto =>
          n != null && typeof n === "object" && typeof (n as NotificationDto).id === "number",
      )
    : [];
  return { notifications: list, unreadCount: num(o.unreadCount) ?? 0 };
}

// ── Digest derivation (Home card + "Updated" pills) ─────────────────────────

/** Compose the Home/Conditions view of the journal from the raw envelope. */
export function toAnalysisUpdates(envelope: NotificationsEnvelope): AnalysisUpdatesVM {
  const all = envelope.notifications.map(toNotification);
  const unreadDigests = all.filter((n) => !n.isRead && DIGEST_EVENT_TYPES.has(n.eventType));
  // The endpoint orders createdAt DESC — first unread digest is the latest.
  const digest = unreadDigests[0] ?? null;
  const latestUpdated = all.find((n) => !n.isRead && n.eventType === "analysis_updated") ?? null;
  return {
    digest,
    digestIds: unreadDigests.map((n) => n.id),
    changedConditionIds: latestUpdated?.metadata?.changedConditionIds ?? [],
    hasAny: all.length > 0,
  };
}

const LEG_LABEL: Record<string, string> = {
  dx: triadLeg("dx").label,
  is: triadLeg("is").label,
  nx: triadLeg("nx").label,
};

function legLabel(leg: string): string {
  return LEG_LABEL[leg as TriadLegKey] ?? "Evidence";
}

/** "50% → 70%", honest about both directions and about "not yet ratable". */
function ratingText(from: number | null, to: number | null): string {
  const f = from == null ? "not yet ratable" : `${from}%`;
  const t = to == null ? "not yet ratable" : `${to}%`;
  return `rating estimate ${f} → ${t}`;
}

/**
 * The per-condition detail lines of the digest card. Pure string templates over
 * the flip-transaction diff the backend computed — nothing here invents or
 * re-derives a number.
 */
export function digestLines(n: NotificationVM): DigestLineVM[] {
  const m = n.metadata;
  if (!m) return [];
  const lines: DigestLineVM[] = [];
  for (const r of m.ratingChanges ?? []) {
    lines.push({
      key: `rating-${r.conditionId}`,
      text: `${r.name}: ${ratingText(r.from, r.to)}`,
      conditionId: r.conditionId,
    });
  }
  for (const l of m.legChanges ?? []) {
    lines.push({
      key: `leg-${l.conditionId}-${l.leg}`,
      text: `${l.name}: ${legLabel(l.leg)} evidence ${l.from} → ${l.to}`,
      conditionId: l.conditionId,
    });
  }
  for (const a of m.addedConditions ?? []) {
    lines.push({
      key: `added-${a.conditionId}`,
      text: `${a.name}: new condition identified`,
      conditionId: a.conditionId,
    });
  }
  for (const r of m.retiredConditions ?? []) {
    lines.push({
      key: `retired-${r.name}`,
      text: `${r.name}: no longer in your analysis`,
      conditionId: null,
    });
  }
  const gapBits: string[] = [];
  if ((m.gapsClosed ?? 0) > 0) {
    gapBits.push(`${m.gapsClosed} evidence gap${m.gapsClosed === 1 ? "" : "s"} closed`);
  }
  if ((m.gapsOpened ?? 0) > 0) {
    gapBits.push(`${m.gapsOpened} new gap${m.gapsOpened === 1 ? "" : "s"} found`);
  }
  if (gapBits.length) {
    lines.push({ key: "gaps", text: gapBits.join(" · "), conditionId: null });
  }
  return lines;
}


/** A capped, headed group of digest lines — the card renders these instead of
 *  a flat 30-line dump when a big re-analysis changes many conditions. */
export interface DigestGroupVM {
  key: string;
  /** null = ungrouped single line (e.g. the gaps summary). */
  heading: string | null;
  lines: DigestLineVM[];
  /** How many lines were hidden by the cap ("and N more" affordance). */
  more: number;
}

function takeGroup(
  key: string,
  heading: string | null,
  lines: DigestLineVM[],
  cap: number,
): DigestGroupVM | null {
  if (lines.length === 0) return null;
  return { key, heading, lines: lines.slice(0, cap), more: Math.max(0, lines.length - cap) };
}

/**
 * Grouped + capped digest (the readable form of `digestLines`). The Home card
 * keeps the default caps ("and N more" links to /timeline); the timeline
 * itself passes `uncapped` — it IS the full-detail surface, so hiding lines
 * behind a link to the page you're on would strand them.
 */
export function digestGroups(n: NotificationVM, opts?: { uncapped?: boolean }): DigestGroupVM[] {
  const m = n.metadata;
  if (!m) return [];
  const cap = (base: number) => (opts?.uncapped ? Number.POSITIVE_INFINITY : base);
  const ratings: DigestLineVM[] = (m.ratingChanges ?? []).map((r) => ({
    key: `rating-${r.conditionId}`,
    text: `${r.name}: ${ratingText(r.from, r.to)}`,
    conditionId: r.conditionId,
  }));
  const legs: DigestLineVM[] = (m.legChanges ?? []).map((l) => ({
    key: `leg-${l.conditionId}-${l.leg}`,
    text: `${l.name}: ${legLabel(l.leg)} evidence ${l.from} \u2192 ${l.to}`,
    conditionId: l.conditionId,
  }));
  const added: DigestLineVM[] = (m.addedConditions ?? []).map((a) => ({
    key: `added-${a.conditionId}`,
    text: a.name,
    conditionId: a.conditionId,
  }));
  const retired: DigestLineVM[] = (m.retiredConditions ?? []).map((r) => ({
    key: `retired-${r.name}`,
    text: r.name,
    conditionId: null,
  }));
  const gaps: DigestLineVM[] = [];
  const gapBits: string[] = [];
  if ((m.gapsClosed ?? 0) > 0) {
    gapBits.push(`${m.gapsClosed} evidence gap${m.gapsClosed === 1 ? "" : "s"} closed`);
  }
  if ((m.gapsOpened ?? 0) > 0) {
    gapBits.push(`${m.gapsOpened} new gap${m.gapsOpened === 1 ? "" : "s"} found`);
  }
  if (gapBits.length) gaps.push({ key: "gaps", text: gapBits.join(" \u00b7 "), conditionId: null });

  // "New conditions" reads wrong on a first-ever analysis — everything is new.
  const addedHeading = n.eventType === "analysis_complete" ? "Conditions found" : "New conditions";
  return [
    takeGroup("ratings", "Rating estimates", ratings, cap(5)),
    takeGroup("legs", "Evidence strength", legs, cap(3)),
    takeGroup("added", addedHeading, added, cap(3)),
    takeGroup("retired", "No longer supported by the evidence", retired, cap(2)),
    takeGroup("gaps", null, gaps, cap(1)),
  ].filter((g): g is DigestGroupVM => g !== null);
}

// ── Timeline (/timeline journal) ─────────────────────────────────────────────

const EVENT_ICON: Record<string, IconName> = {
  analysis_updated: "sparkle",
  analysis_complete: "checkCircle",
  condition_added: "conditions",
  rating_changed: "money",
};

export function notificationIcon(eventType: string): IconName {
  return EVENT_ICON[eventType] ?? "info";
}

/** Local-date group key, or "unknown" for absent/garbled timestamps. */
function dayKey(iso: string): string {
  const d = new Date(iso);
  if (!iso || Number.isNaN(d.getTime())) return "unknown";
  const pad = (x: number) => String(x).padStart(2, "0");
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;
}

function dayLabel(iso: string): string {
  const d = new Date(iso);
  if (!iso || Number.isNaN(d.getTime())) return "Earlier";
  return d.toLocaleDateString("en-US", { year: "numeric", month: "long", day: "numeric" });
}

/**
 * Group the (already createdAt-DESC) journal into day buckets, preserving
 * order within and across days.
 */
export function groupNotificationsByDay(list: NotificationDto[]): TimelineDayVM[] {
  const days: TimelineDayVM[] = [];
  const byKey = new Map<string, TimelineDayVM>();
  for (const raw of list) {
    const n = toNotification(raw);
    const key = dayKey(n.createdAt);
    let day = byKey.get(key);
    if (!day) {
      day = { key, label: dayLabel(n.createdAt), items: [] };
      byKey.set(key, day);
      days.push(day);
    }
    day.items.push(n);
  }
  return days;
}
