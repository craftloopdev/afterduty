// Network facade (capacitor-ios-spec §D.3/§H.5 — REQUIRED offline shell). The ONLY
// module that touches `@capacitor/network`; the plugin import is lazy + guarded by
// the build-time `NATIVE` constant so it is tree-shaken out of the web bundle. The
// connectivity signal feeds the branded offline state that must appear instead of
// a white screen or raw error on a cold launch with no network (a guaranteed
// 4.2/2.1 rejection otherwise). Web falls back to `navigator.onLine`.

"use client";

import { useEffect, useState } from "react";
import { NATIVE } from "@/lib/platform";

function plugin(): typeof import("@capacitor/network") | null {
  if (!NATIVE) return null;
  // eslint-disable-next-line @typescript-eslint/no-require-imports
  return require("@capacitor/network") as typeof import("@capacitor/network");
}

/** Best-effort async connectivity read. On native uses the plugin's
 *  `getStatus().connected`; on web (and on any plugin error) falls back to
 *  `navigator.onLine`. Never throws. */
export async function isConnected(): Promise<boolean> {
  const p = plugin();
  if (p) {
    try {
      const status = await p.Network.getStatus();
      return status.connected;
    } catch {
      /* fall through to navigator */
    }
  }
  if (typeof navigator !== "undefined" && typeof navigator.onLine === "boolean") {
    return navigator.onLine;
  }
  return true; // assume online if we genuinely can't tell
}

/**
 * Reactive online/offline state for the offline shell. Seeds from the current
 * status, then tracks `networkStatusChange` (native) or the window online/offline
 * events (web). Returns `true` while connected; defaults to connected until the
 * first read resolves so we never flash an offline state on a healthy launch.
 */
export function useOnline(): boolean {
  const [online, setOnline] = useState(true);

  useEffect(() => {
    let live = true;
    void isConnected().then((c) => live && setOnline(c));

    const p = plugin();
    if (p) {
      const handlePromise = p.Network.addListener("networkStatusChange", (s) => {
        if (live) setOnline(s.connected);
      });
      return () => {
        live = false;
        void handlePromise.then((h) => h.remove());
      };
    }

    // Web fallback.
    const on = () => live && setOnline(true);
    const off = () => live && setOnline(false);
    if (typeof window !== "undefined") {
      window.addEventListener("online", on);
      window.addEventListener("offline", off);
    }
    return () => {
      live = false;
      if (typeof window !== "undefined") {
        window.removeEventListener("online", on);
        window.removeEventListener("offline", off);
      }
    };
  }, []);

  return online;
}
