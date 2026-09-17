// Status-bar facade (capacitor-ios-spec §D.3). The ONLY module that touches
// `@capacitor/status-bar`; the plugin import is lazy + guarded by the build-time
// `NATIVE` constant so it is tree-shaken out of the web bundle (no-op on web). The
// token CSS layer needs no changes — only the status-bar TEXT style follows the
// theme: a dark app background (`.ad-dark`) needs light status-bar icons, a light
// background needs dark icons. Driven by the ThemeProvider's `dark` state.

import { NATIVE } from "@/lib/platform";

/** Set the status-bar text/icon style to match the current theme. `dark` is the
 *  app's dark-mode flag (`.ad-dark`): a dark app background needs LIGHT status-bar
 *  text. Note the plugin's enum is named by the TEXT color it produces and reads
 *  counterintuitively: `Style.Dark` => light text (for dark backgrounds),
 *  `Style.Light` => dark text (for light backgrounds). So dark mode ⇒ `Style.Dark`. */
export function syncStatusBar(dark: boolean): void {
  if (!NATIVE) return;
  // eslint-disable-next-line @typescript-eslint/no-require-imports
  const { StatusBar, Style } = require("@capacitor/status-bar") as typeof import("@capacitor/status-bar");
  void StatusBar.setStyle({ style: dark ? Style.Dark : Style.Light }).catch(() => {});
}
