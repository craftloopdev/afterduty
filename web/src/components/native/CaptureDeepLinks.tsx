"use client";

// Screenshot-pipeline deep links (capacitor-ios-spec §F.3) — CAPTURE BUILDS ONLY.
//
// The production deep-link listener lives in <NativeAppLayout>, which mounts
// only inside the signed-in `(app)` route group. On a signed-out launch the gate
// replaces to /login, that layout unmounts, and nothing is listening any more —
// so the capture scripts' `afterduty:///dev/*` links were silently dropped and
// every "screenshot" was the login screen (2026-09-14). This component sits in
// the ROOT layout so a `/dev/*` link resolves from any screen, and it also reads
// the cold-launch URL, which `appUrlOpen` never carries on Android.
//
// It handles ONLY `/dev/*` destinations, so share/reopen links keep exactly one
// handler (the gate's). `ENABLED` is a build-time constant: release bundles
// (flag unset) compile this to a null component, matching the dev layout's gate.

import { useEffect } from "react";
import { useRouter } from "next/navigation";
import { NATIVE } from "@/lib/platform";
import { mapDeepLink } from "@/lib/native/deep-links";

const ENABLED = NATIVE && process.env.NEXT_PUBLIC_ENABLE_DEV_ROUTES === "true";

export function CaptureDeepLinks() {
  const router = useRouter();

  useEffect(() => {
    if (!ENABLED) return;
    // eslint-disable-next-line @typescript-eslint/no-require-imports
    const { App } = require("@capacitor/app") as typeof import("@capacitor/app");

    const go = (url: string | null | undefined) => {
      if (!url) return;
      const dest = mapDeepLink(url, { allowDev: true });
      // Capture-only diagnostics: shows in `xcrun simctl spawn … log` / `adb logcat`
      // (Capacitor/Console) so a silent no-op is visible to the pipeline operator.
      let parsed = "unparseable";
      try {
        const u = new URL(url);
        parsed = `host=${JSON.stringify(u.host)} pathname=${JSON.stringify(u.pathname)}`;
      } catch {
        /* reported as unparseable */
      }
      console.log(`[capture-deeplinks] ${url} → ${dest ?? "(ignored)"} (${parsed})`);
      if (dest && dest.startsWith("/dev/")) router.replace(dest);
    };
    console.log("[capture-deeplinks] installed");

    // Cold start: the URL the app was launched with (no appUrlOpen on Android).
    void App.getLaunchUrl().then((r) => go(r?.url));
    // Warm: every later link while the app is running, whatever screen is up.
    const handle = App.addListener("appUrlOpen", (event: { url: string }) => go(event.url));
    return () => {
      void handle.then((h) => h.remove());
    };
  }, [router]);

  return null;
}
