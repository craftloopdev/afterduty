"use client";

import { useEffect } from "react";
import { PREFS_COOKIE } from "@/lib/constants";

// Native theme boot (capacitor-ios-spec §A.6). On web the no-FOUC trick reads the
// prefs cookie server-side and sets data-theme / .ad-dark on <html> before first
// paint. The export build has no request-time cookie, so the root layout renders
// with DEFAULT_PREFS and this client component applies the PERSISTED prefs on
// mount — the held splash covers the swap, so there's no visible flash. It reads
// the same `cp_prefs` value the web ThemeProvider writes (document.cookie), so a
// user's choice survives. ThemeProvider then hydrates from the <html> attributes
// exactly as on web.
export function ThemeBoot() {
  useEffect(() => {
    try {
      const match = document.cookie.match(new RegExp(`(?:^|; )${PREFS_COOKIE}=([^;]*)`));
      if (!match) return;
      const p = JSON.parse(decodeURIComponent(match[1])) as {
        theme?: string;
        scale?: number;
        dark?: boolean;
      };
      const el = document.documentElement;
      if (p.theme === "warm" || p.theme === "ai") el.dataset.theme = p.theme;
      if (p.scale === 0 || p.scale === 2) el.dataset.scale = String(p.scale);
      if (p.dark === true) el.classList.add("ad-dark");
    } catch {
      /* default prefs already applied server-side */
    }
  }, []);
  return null;
}
