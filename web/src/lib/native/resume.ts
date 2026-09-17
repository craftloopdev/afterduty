"use client";

// Native data freshness on app resume (P1-22, capacitor-ios-spec §A.3). A
// WKWebView screen keeps whatever `useLoader` fetched at mount — hours can pass
// in the background while the pipeline finishes, so coming back to the
// foreground must refetch. Web pages don't need this (RSC navigation refetches),
// so everything here is a no-op off the native build.

import { useEffect, useRef } from "react";
import { NATIVE } from "@/lib/platform";

/**
 * Install a Capacitor App `resume` listener; returns a teardown. No-op on web.
 * `@capacitor/app` is DYNAMICALLY imported inside the NATIVE guard so the web
 * bundle never pulls the plugin — off native this function returns before the
 * import expression is ever reached.
 */
export function installResumeListener(onResume: () => void): () => void {
  if (!NATIVE) return () => {};
  let disposed = false;
  let remove: (() => void) | null = null;
  void import("@capacitor/app").then(({ App }) => {
    if (disposed) return; // torn down before the plugin loaded — don't register
    void App.addListener("resume", onResume).then((handle) => {
      if (disposed) void handle.remove();
      else remove = () => void handle.remove();
    });
  });
  return () => {
    disposed = true;
    remove?.();
  };
}

/**
 * Re-run a loader's `refetch` every time the app returns to the foreground.
 * Mount-once; the ref keeps the latest refetch without re-subscribing (the
 * caller passes `state.refetch`, whose identity is stable, but inline arrows
 * composing several refetches are fine too). Inert on web.
 */
export function useRefetchOnResume(refetch: () => void): void {
  const ref = useRef(refetch);
  useEffect(() => {
    ref.current = refetch;
  });
  useEffect(() => installResumeListener(() => ref.current()), []);
}
