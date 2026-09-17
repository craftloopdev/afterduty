import type { UserResponse } from "@/lib/models/api";

// Phone sign-ins have no display name, so the backend returns the Firebase UID
// (a long mixed-case alphanumeric blob). Detect that and fall back gracefully.
function looksLikeUid(s: string): boolean {
  // Any single unbroken 20+ char token is an identifier, not a first name —
  // charset-agnostic on purpose (a strict [A-Za-z0-9_-] test let a near-uid
  // variant reach the "Welcome back, dB34I0uAg…" greeting in prod).
  return s.length >= 20 && !s.includes(" ") && !s.includes("@");
}

/**
 * True for the backend's synthetic "<uid>@firebase.local" placeholder emails
 * (phone-first accounts have no real address). Synthetic addresses must NEVER
 * reach the UI — not as an email, and not as a name via the local-part
 * fallback (that's exactly how the raw uid leaked into greetings).
 */
export function isSyntheticEmail(email?: string | null): boolean {
  return !!email && email.trim().toLowerCase().endsWith("@firebase.local");
}

/** A human display name, or "" when we only have a UID / nothing usable. */
export function displayName(
  me: Pick<UserResponse, "name" | "email" | "preferredName">,
): string {
  // The veteran's explicit answer to "What should we call you?" always wins.
  const preferred = me.preferredName?.trim();
  if (preferred) return preferred;
  // A synthetic email can also be COPIED into `name` by older backends — it
  // contains "@" so the uid-token guard alone would let it through.
  const n = me.name?.trim();
  if (n && !looksLikeUid(n) && !isSyntheticEmail(n)) return n;
  const email = me.email?.trim();
  if (email && email.includes("@") && !isSyntheticEmail(email)) return email.split("@")[0];
  return "";
}

/** First token of the display name, or "" when unknown. */
export function firstName(me: Pick<UserResponse, "name" | "email" | "preferredName">): string {
  return displayName(me).split(/\s+/)[0] ?? "";
}

/**
 * "(202) 555-0147" from an E.164 US number ("+12025550147"). Anything else —
 * non-US, already formatted, garbage — renders AS-IS (never mangled); only an
 * absent/blank input returns null.
 */
export function formatPhoneUS(phone?: string | null): string | null {
  if (!phone) return null;
  const t = phone.trim();
  if (!t) return null;
  const m = /^\+1(\d{3})(\d{3})(\d{4})$/.exec(t);
  if (!m) return t;
  return `(${m[1]}) ${m[2]}-${m[3]}`;
}

/**
 * Total years served across service periods, for the "about N years total"
 * header line. Pure client-side arithmetic over dates the backend sent: each
 * period with a parseable start contributes (end ?? now) − start; unparseable
 * or open-start periods contribute nothing. Returns a whole-year count, or
 * null when no period allows the math (or the total rounds below 1 year —
 * "about 0 years" helps no one).
 */
export function serviceTotalYears(
  periods: Array<{ startDate: string | null; endDate: string | null }>,
  now: Date = new Date(),
): number | null {
  const YEAR_MS = 365.25 * 24 * 60 * 60 * 1000;
  let ms = 0;
  let any = false;
  for (const p of periods) {
    if (!p.startDate) continue;
    const start = new Date(p.startDate);
    if (Number.isNaN(start.getTime())) continue;
    const endRaw = p.endDate ? new Date(p.endDate) : now;
    const end = Number.isNaN(endRaw.getTime()) ? now : endRaw;
    const span = end.getTime() - start.getTime();
    if (span <= 0) continue;
    ms += span;
    any = true;
  }
  if (!any) return null;
  const years = Math.round(ms / YEAR_MS);
  return years >= 1 ? years : null;
}

/** "January 15, 2027" from an ISO date, or null when absent/unparseable. */
export function formatLongDate(iso?: string | null): string | null {
  if (!iso) return null;
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return null;
  return d.toLocaleDateString("en-US", { year: "numeric", month: "long", day: "numeric" });
}

/** Title-case a backend tier enum, e.g. "annual" → "Annual". */
export function tierLabel(tier?: string | null): string | null {
  if (!tier) return null;
  const t = tier.trim();
  if (!t) return null;
  return t.charAt(0).toUpperCase() + t.slice(1).toLowerCase();
}
