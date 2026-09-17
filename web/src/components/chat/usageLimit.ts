// Defensive wire-shape helpers for the monthly AI usage cap (P1-10). The
// backend is making cap failures distinguishable from subscription 402s —
// either a 429 status or a body code `USAGE_LIMIT_REACHED` (+ optional
// `resumesAt`). Today's deployed backend still sends a plain 402 whose BODY
// carries the code, so both shapes are accepted; absent both, callers fall
// back to their existing behavior. Pure TS (no "use client") so the BFF chat
// routes and the client hook share ONE classification.

export interface UsageLimitInfo {
  /** ISO timestamp when the cap resets, when the backend provided one. */
  resumesAt?: string;
}

/** True when a body field spells the usage-cap code (any casing). */
function hasUsageCode(body: unknown): boolean {
  if (!body || typeof body !== "object") return false;
  const o = body as Record<string, unknown>;
  return [o.code, o.error].some(
    (v) => typeof v === "string" && v.toLowerCase() === "usage_limit_reached",
  );
}

function resumesAtOf(body: unknown): string | undefined {
  if (!body || typeof body !== "object") return undefined;
  const o = body as Record<string, unknown>;
  const v = o.resumesAt ?? o.resumes_at;
  if (typeof v === "string" && v) return v;
  if (typeof v === "number" && Number.isFinite(v)) return new Date(v).toISOString();
  return undefined;
}

/**
 * Classify a failed chat exchange as the monthly usage cap. Cap when the
 * status is 429 OR the (JSON) body carries `USAGE_LIMIT_REACHED`; `null`
 * otherwise (callers keep today's 402→subscription / generic handling).
 * Pass status 0 to classify a body alone (e.g. an SSE `error` event payload).
 */
export function detectUsageLimit(status: number, body: unknown): UsageLimitInfo | null {
  if (status !== 429 && !hasUsageCode(body)) return null;
  const resumesAt = resumesAtOf(body);
  return resumesAt ? { resumesAt } : {};
}

/** The uniform JSON body the BFF chat routes emit (status 429) for the cap. */
export function usageLimitBody(resumesAt?: string): Record<string, string> {
  return {
    error: "usage_limit_reached",
    code: "USAGE_LIMIT_REACHED",
    ...(resumesAt ? { resumesAt } : {}),
  };
}

/**
 * "August 1" (adds the year when it differs from the current one) from an ISO
 * timestamp, or null when absent/unparseable. Rendered in UTC — the cap resets
 * on the backend's month boundary, and a fixed zone keeps the copy (and tests)
 * deterministic; at worst we understate the reset by a few hours.
 */
export function formatResumeDate(iso?: string | null, now: Date = new Date()): string | null {
  if (!iso) return null;
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return null;
  const opts: Intl.DateTimeFormatOptions = { month: "long", day: "numeric", timeZone: "UTC" };
  if (d.getUTCFullYear() !== now.getUTCFullYear()) opts.year = "numeric";
  return d.toLocaleDateString("en-US", opts);
}

/** The cap banner copy — humanized date, never raw ISO (P1-10). */
export function usageLimitCopy(resumesAt?: string | null): string {
  const date = formatResumeDate(resumesAt);
  return date
    ? `You've reached this month's AI limit — chat resumes ${date}.`
    : "You've reached this month's AI limit — chat resumes next month.";
}
