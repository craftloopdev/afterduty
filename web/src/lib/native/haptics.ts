// Haptics facade (capacitor-ios-spec §D.3/§H.5 — REQUIRED, cheap). The ONLY
// module that touches `@capacitor/haptics`; the plugin import is lazy + guarded by
// the build-time `NATIVE` constant so it is fully tree-shaken out of the web
// bundle (no-op on web). Every call swallows errors — feedback must never break a
// flow. Wired on the key actions the spec names: Subscribe / Upload / Delete-
// confirm taps (light impact) and purchase + account-deletion outcomes
// (notification success/error).

import { NATIVE } from "@/lib/platform";

function plugin(): typeof import("@capacitor/haptics") | null {
  if (!NATIVE) return null;
  // eslint-disable-next-line @typescript-eslint/no-require-imports
  return require("@capacitor/haptics") as typeof import("@capacitor/haptics");
}

/** Light tap on a primary CTA press (Subscribe, Upload, Delete-confirm). */
export function tapLight(): void {
  const p = plugin();
  if (!p) return;
  void p.Haptics.impact({ style: p.ImpactStyle.Light }).catch(() => {});
}

/** Success notification buzz (purchase complete, account deleted). */
export function notifySuccess(): void {
  const p = plugin();
  if (!p) return;
  void p.Haptics.notification({ type: p.NotificationType.Success }).catch(() => {});
}

/** Error notification buzz (purchase/restore failed). */
export function notifyError(): void {
  const p = plugin();
  if (!p) return;
  void p.Haptics.notification({ type: p.NotificationType.Error }).catch(() => {});
}
