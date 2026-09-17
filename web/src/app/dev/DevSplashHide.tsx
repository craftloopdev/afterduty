"use client";

import { useEffect } from "react";
import { hideSplash } from "@/lib/native/splash";

// Capture-build helper (capacitor-ios-spec §F.2/§F.3). The `/dev/*` fixture
// screens render the shell directly (ThemeProvider + AppShell), bypassing
// `NativeAppLayout`, which is what normally drops the held Capacitor splash
// (`launchAutoHide:false`) once auth resolves. Without this, a fixture loaded as
// the WebView's start page would render *behind* the never-hidden splash. So on
// mount we hide the splash ourselves. No-op on web (`hideSplash` tree-shakes the
// plugin out via the `NATIVE` constant), and these routes 404 in production via
// the dev layout gate — so this only ever runs inside the screenshot capture
// bundle. Pure side-effect, renders nothing.
export function DevSplashHide(): null {
  useEffect(() => {
    hideSplash();
  }, []);
  return null;
}
