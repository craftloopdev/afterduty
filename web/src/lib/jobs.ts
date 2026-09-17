// Client-side view of the pipeline jobs snapshot (`GET /api/claim/jobs` on
// Spring — the endpoint built for a cross-screen actions bar and previously
// unconsumed). PipelinePulse polls this while a run is active so the veteran
// never watches a frozen progress number (P0-4/P0-5). Client-safe on both
// build targets: web goes through the BFF route handler (cookie session),
// native calls Spring directly with a Bearer token.

import { NATIVE } from "@/lib/platform";
import { directFetch } from "@/lib/api/direct";

/** Raw snapshot shape (snake_case, straight off IntakeController.jobs). */
export interface JobsSnapshot {
  claim_id?: number;
  active_count?: number;
  summary?: {
    documents?: number;
    atoms?: number;
    conditions?: number;
    open_gaps?: number;
  };
  extraction?: {
    pending?: number;
    processing?: number;
    processed?: number;
    errored?: number;
  };
  synthesis?: { state?: string; last_run_at?: string | null };
  gap_analysis?: { state?: string; last_run_at?: string | null };
}

/**
 * Fetch the jobs snapshot, or `null` on any failure (a polling loop must never
 * throw — a transient error just means "try again next tick").
 */
export async function fetchJobs(): Promise<JobsSnapshot | null> {
  try {
    if (NATIVE) {
      return await directFetch<JobsSnapshot>("/claim/jobs", { allow404AsNull: true });
    }
    const res = await fetch("/api/claim/jobs", { cache: "no-store" });
    if (!res.ok) return null;
    return (await res.json()) as JobsSnapshot;
  } catch {
    return null;
  }
}

/** True when the snapshot reports any in-flight pipeline work. */
export function jobsActive(jobs: JobsSnapshot | null): boolean {
  return (jobs?.active_count ?? 0) > 0;
}

export type StageState = "done" | "active" | "waiting";

export interface StageVM {
  key: string;
  label: string;
  state: StageState;
}

/**
 * The honest per-stage checklist for the first-analysis screen. Derived purely
 * from the jobs snapshot — no fake percentages, ever.
 */
export function deriveStages(jobs: JobsSnapshot | null): StageVM[] {
  const ex = jobs?.extraction;
  const pending = ex?.pending ?? 0;
  const processing = ex?.processing ?? 0;
  const processed = ex?.processed ?? 0;
  const errored = ex?.errored ?? 0;
  const readActive = pending + processing > 0;
  const readDone = !readActive && processed + errored > 0;

  const synthRunning = jobs?.synthesis?.state === "running";
  const conditions = jobs?.summary?.conditions ?? 0;
  const synthDone = !synthRunning && conditions > 0;

  const gapRunning = jobs?.gap_analysis?.state === "running";
  const gapDone = !gapRunning && !!jobs?.gap_analysis?.last_run_at;

  return [
    {
      key: "read",
      label: "Reading your documents",
      state: readActive ? "active" : readDone ? "done" : "waiting",
    },
    {
      key: "conditions",
      label: "Identifying conditions & scoring the VA triad",
      state: synthRunning ? "active" : synthDone ? "done" : "waiting",
    },
    {
      key: "gaps",
      label: "Finding your evidence gaps",
      state: gapRunning ? "active" : gapDone ? "done" : "waiting",
    },
  ];
}

// ── Upload → pulse arming ────────────────────────────────────────────────────
// A successful upload arms polling across screens (the uploader may navigate to
// Home/Steps mid-run). sessionStorage carries the flag across route loads (with
// an in-memory fallback for storage-less contexts); the event notifies mounted
// PipelinePulse subscribers. TTL keeps a stale flag from polling forever. The
// shape (subscribe + snapshot + server snapshot) is `useSyncExternalStore`'s
// contract, so components consume it without hydration mismatches.

export const PULSE_ARM_EVENT = "cp:pulse-arm";
const PULSE_ARM_KEY = "cp.pulse.armedAt";
const PULSE_ARM_TTL_MS = 30 * 60 * 1000;

let armedAtMemory = 0;

export function armAnalysisPulse(): void {
  armedAtMemory = Date.now();
  try {
    sessionStorage.setItem(PULSE_ARM_KEY, String(armedAtMemory));
  } catch {
    // Storage unavailable — the in-memory flag still arms this page's pulses.
  }
  notifyPulseChanged();
}

export function disarmAnalysisPulse(): void {
  armedAtMemory = 0;
  try {
    sessionStorage.removeItem(PULSE_ARM_KEY);
  } catch {
    // Nothing stored — nothing to clear.
  }
  notifyPulseChanged();
}

/** Timestamp of the most recent (unexpired) arm, or 0 when not armed. */
export function analysisPulseArmedAt(now: number = Date.now()): number {
  let stored = 0;
  try {
    const t = Number(sessionStorage.getItem(PULSE_ARM_KEY));
    if (Number.isFinite(t) && t > 0) stored = t;
  } catch {
    // Fall through to the in-memory flag.
  }
  const armedAt = Math.max(stored, armedAtMemory);
  return armedAt > 0 && now - armedAt < PULSE_ARM_TTL_MS ? armedAt : 0;
}

export function isAnalysisPulseArmed(now: number = Date.now()): boolean {
  return analysisPulseArmedAt(now) > 0;
}

/** `useSyncExternalStore` subscribe: re-read the snapshot on every arm/disarm. */
export function subscribeAnalysisPulse(onChange: () => void): () => void {
  window.addEventListener(PULSE_ARM_EVENT, onChange);
  return () => window.removeEventListener(PULSE_ARM_EVENT, onChange);
}

function notifyPulseChanged(): void {
  try {
    window.dispatchEvent(new Event(PULSE_ARM_EVENT));
  } catch {
    // Non-browser context — nothing to notify.
  }
}
