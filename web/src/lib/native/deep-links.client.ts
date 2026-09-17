"use client";

// Deep-link wiring (capacitor-ios-spec §D.3). Subscribes to Capacitor's
// `appUrlOpen` and routes via the pure `mapDeepLink` mapper. No-op on web (the
// `NATIVE` constant tree-shakes the plugin import out of the web bundle). Capture
// builds pass `allowDev` so the screenshot pipeline's `afterduty:///dev/*`
// links resolve (§F.3).

import { NATIVE } from "@/lib/platform";
import { mapDeepLink } from "./deep-links";

const ALLOW_DEV = process.env.NEXT_PUBLIC_ENABLE_DEV_ROUTES === "true";

/** Install the appUrlOpen listener; returns a teardown. `navigate` is the
 *  router's replace/push (the caller passes `router.replace`). */
export function installDeepLinks(navigate: (path: string) => void): () => void {
  if (!NATIVE) return () => {};
  // eslint-disable-next-line @typescript-eslint/no-require-imports
  const { App } = require("@capacitor/app") as typeof import("@capacitor/app");
  const handle = App.addListener("appUrlOpen", (event: { url: string }) => {
    const dest = mapDeepLink(event.url, { allowDev: ALLOW_DEV });
    if (dest) navigate(dest);
  });
  return () => {
    void handle.then((h) => h.remove());
  };
}
