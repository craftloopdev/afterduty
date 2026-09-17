import "server-only";
import { cookies } from "next/headers";
import type { ThemeName, TextScale } from "./tokens";
import { PREFS_COOKIE } from "@/lib/constants";

// Persisted appearance prefs — read server-side so the first paint already
// carries the right data-theme / .ad-dark / data-scale (no flash).
export { PREFS_COOKIE };

export interface ThemePrefs {
  theme: ThemeName;
  scale: TextScale;
  dark: boolean;
}

export const DEFAULT_PREFS: ThemePrefs = { theme: "navy", scale: 1, dark: false };

export async function readThemePrefs(): Promise<ThemePrefs> {
  try {
    const raw = (await cookies()).get(PREFS_COOKIE)?.value;
    if (!raw) return DEFAULT_PREFS;
    const p = JSON.parse(raw) as Partial<ThemePrefs>;
    return {
      theme: p.theme === "warm" || p.theme === "ai" ? p.theme : "navy",
      scale: p.scale === 0 || p.scale === 2 ? p.scale : 1,
      dark: p.dark === true,
    };
  } catch {
    return DEFAULT_PREFS;
  }
}
