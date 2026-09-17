"use client";

import type { ReactNode } from "react";
import { UnauthorizedError } from "@/lib/api/errors";
import { ErrorState } from "@/components/ui/ErrorState";
import { OfflineState } from "@/components/ui/OfflineState";
import { useOnline } from "@/lib/native/network";
import type { LoaderState } from "@/lib/hooks/useLoader";

// Shared in-flight / error scaffolding for the Native* page wrappers
// (capacitor-ios-spec §A.3). On web these states are the route's `loading.tsx`
// skeleton and `error.tsx`; natively each screen mounts one `useLoader`, so the
// wrapper renders the same skeleton while loading and `ErrorState` (with Retry →
// refetch) on failure, then hands `data` to the SAME shared view component. A
// 401 here is the auth gate's job — `NativeAppLayout` already watches auth and
// redirects — so we surface a neutral retry rather than a scary error.
//
// REQUIRED offline shell (§D.3/§H.5): when a load fails AND the device has no
// connectivity, render the BRANDED OfflineState instead of the generic error —
// the cold-launch-in-airplane-mode case must never show a raw error in front of
// a reviewer. Connectivity is read live (`useOnline`); web is unaffected (the
// network facade falls back to `navigator.onLine`, which only matters once a load
// actually fails, and web pages don't mount this boundary).

interface LoaderBoundaryProps<T> {
  state: LoaderState<T>;
  /** Route skeleton to show while loading (the page's existing loading.tsx). */
  skeleton: ReactNode;
  /** Renders the resolved, non-null data. */
  children: (data: T) => ReactNode;
}

export function LoaderBoundary<T>({ state, skeleton, children }: LoaderBoundaryProps<T>) {
  const online = useOnline();
  if (state.loading && state.data == null) return <>{skeleton}</>;
  if (state.error && state.data == null) {
    const unauth = state.error instanceof UnauthorizedError;
    // Offline takes precedence over a generic error (a connectivity-level fetch
    // failure IS what we hit on a cold airplane-mode launch). Auth errors stay the
    // gate's concern even offline.
    if (!unauth && !online) return <OfflineState onRetry={state.refetch} />;
    return (
      <ErrorState
        title={unauth ? "Please sign in again" : "Something went wrong"}
        body={unauth ? undefined : "We couldn't load this. Check your connection and try again."}
        onRetry={state.refetch}
      />
    );
  }
  if (state.data == null) return <>{skeleton}</>;
  return <>{children(state.data)}</>;
}
