"use client";

import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useState,
} from "react";
import type { ThemeName, TextScale } from "@/lib/theme/tokens";
import { PREFS_COOKIE } from "@/lib/constants";
import { syncStatusBar } from "@/lib/native/status-bar";

interface ThemeContextValue {
  theme: ThemeName;
  scale: TextScale;
  dark: boolean;
  setTheme: (t: ThemeName) => void;
  setScale: (s: TextScale) => void;
  setDark: (d: boolean) => void;
}

const ThemeContext = createContext<ThemeContextValue | null>(null);

export function useTheme(): ThemeContextValue {
  const ctx = useContext(ThemeContext);
  if (!ctx) throw new Error("useTheme must be used within ThemeProvider");
  return ctx;
}

function persist(theme: ThemeName, scale: TextScale, dark: boolean) {
  const value = encodeURIComponent(JSON.stringify({ theme, scale, dark }));
  document.cookie = `${PREFS_COOKIE}=${value}; path=/; max-age=${60 * 60 * 24 * 365}; samesite=lax`;
}

/**
 * Owns appearance state. SSR already applied the persisted prefs to <html> (no
 * FOUC); this provider hydrates from those attributes and writes changes back to
 * both <html> and the cookie. The toggle UI ships in a later (Settings) cycle.
 */
export function ThemeProvider({ children }: { children: React.ReactNode }) {
  const [theme, setThemeState] = useState<ThemeName>("navy");
  const [scale, setScaleState] = useState<TextScale>(1);
  const [dark, setDarkState] = useState(false);

  useEffect(() => {
    const el = document.documentElement;
    if (el.dataset.theme === "warm" || el.dataset.theme === "ai") {
      setThemeState(el.dataset.theme);
    }
    const s = Number(el.dataset.scale);
    if (s === 0 || s === 2) setScaleState(s);
    const isDark = el.classList.contains("ad-dark");
    setDarkState(isDark);
    // Match the native status-bar icons to the booted theme (§D.3; no-op on web).
    syncStatusBar(isDark);
  }, []);

  const apply = useCallback((t: ThemeName, s: TextScale, d: boolean) => {
    const el = document.documentElement;
    el.dataset.theme = t;
    el.dataset.scale = String(s);
    el.classList.toggle("ad-dark", d);
    persist(t, s, d);
    // Keep the native status bar in sync as the user toggles dark mode (§D.3).
    syncStatusBar(d);
  }, []);

  const setTheme = useCallback((t: ThemeName) => { setThemeState(t); apply(t, scale, dark); }, [apply, scale, dark]);
  const setScale = useCallback((s: TextScale) => { setScaleState(s); apply(theme, s, dark); }, [apply, theme, dark]);
  const setDark = useCallback((d: boolean) => { setDarkState(d); apply(theme, scale, d); }, [apply, theme, scale]);

  const value = useMemo(
    () => ({ theme, scale, dark, setTheme, setScale, setDark }),
    [theme, scale, dark, setTheme, setScale, setDark],
  );

  return <ThemeContext.Provider value={value}>{children}</ThemeContext.Provider>;
}
