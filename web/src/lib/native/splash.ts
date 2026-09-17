// Splash facade (capacitor-ios-spec §A.4/§D.2). The Capacitor splash is held
// (`launchAutoHide:false`) and hidden only once the native auth gate resolves —
// no white flash, no content pop (§H.5). No-op on web (the constant tree-shakes
// the plugin import out of the web bundle).

import { NATIVE } from "@/lib/platform";

export function hideSplash(): void {
  if (!NATIVE) return;
  // eslint-disable-next-line @typescript-eslint/no-require-imports
  const { SplashScreen } = require("@capacitor/splash-screen") as typeof import("@capacitor/splash-screen");
  void SplashScreen.hide().catch(() => {});
}
