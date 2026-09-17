import type { IconName } from "@/components/ui/Icon";

/*
 * Semantic-state → design-token resolver (the charter's "groupColors.ts" analog).
 * Every color this module returns is a `var(--…)` reference — NEVER a hex literal —
 * so dark mode and theme switching recolor components with zero component change.
 * A unit test (tokens.test.ts) enforces the no-hex rule.
 */

export type TriadLevel = "strong" | "partial" | "missing";
export type ThemeName = "navy" | "warm" | "ai";
export type TextScale = 0 | 1 | 2;

export interface StatusColors {
  /** Foreground/text color. */
  fg: string;
  /** Soft background fill. */
  bg: string;
  /** Solid indicator dot. */
  dot: string;
  label: string;
}

const STATUS_LABEL: Record<TriadLevel, string> = {
  strong: "Strong",
  partial: "Partial",
  missing: "Missing",
};

export function statusColors(level: TriadLevel): StatusColors {
  return {
    fg: `var(--${level})`,
    bg: `var(--${level}-bg)`,
    dot: `var(--${level}-dot)`,
    label: STATUS_LABEL[level],
  };
}

export function resolveStatusIcon(level: TriadLevel): IconName {
  const map: Record<TriadLevel, IconName> = {
    strong: "check",
    partial: "alert",
    missing: "dash",
  };
  return map[level];
}

export type TriadLegKey = "dx" | "is" | "nx";

export interface TriadLegMeta {
  /** CSS var for this leg's identity color. */
  color: string;
  icon: IconName;
  label: string;
  desc: string;
}

const TRIAD_LEG: Record<TriadLegKey, TriadLegMeta> = {
  dx: { color: "var(--leg-dx)", icon: "medical", label: "Diagnosis", desc: "A current, documented diagnosis" },
  is: { color: "var(--leg-is)", icon: "flag", label: "In-Service Event", desc: "Something that happened in service" },
  nx: { color: "var(--leg-nx)", icon: "share", label: "Nexus", desc: "A medical link between the two" },
};

export function triadLeg(k: TriadLegKey): TriadLegMeta {
  return TRIAD_LEG[k];
}

/** Map a document/evidence kind to an icon + identity color token. */
export function resolveDocIcon(kind: string | null | undefined): { icon: IconName; color: string } {
  const k = (kind ?? "").toLowerCase();
  if (/service|dd.?214|orders|military/.test(k)) return { icon: "flag", color: "var(--leg-is)" };
  if (/medic|clinic|lab|exam|dbq|blue.?button/.test(k)) return { icon: "medical", color: "var(--leg-dx)" };
  if (/statement|letter|buddy|nexus|personal/.test(k)) return { icon: "letter", color: "var(--leg-nx)" };
  return { icon: "file", color: "var(--muted)" };
}

/** Pill tone → token pair. */
export type PillTone = "green" | "indigo" | "amber" | "line";
export function pillColors(tone: PillTone): { fg: string; bg: string } {
  switch (tone) {
    case "green":
      return { fg: "var(--strong)", bg: "var(--strong-bg)" };
    case "indigo":
      return { fg: "var(--ai-fg)", bg: "var(--ai-bg)" };
    case "amber":
      return { fg: "var(--partial)", bg: "var(--partial-bg)" };
    case "line":
      return { fg: "var(--muted)", bg: "transparent" };
  }
}
