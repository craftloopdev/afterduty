"use client";

import { useCallback, useEffect, useRef, useState } from "react";

// The native replacement for RSC `await` + `router.refresh()` (capacitor-ios-spec
// §A.2/§A.3). Each native screen mounts exactly one `useLoader(loader)`: it runs
// the loader on mount, exposes `{ data, error, loading }`, and a `refetch()` that
// re-runs it (the post-mutation refresh path — PaywallView, ProfileView, retries).
// Per-render dedupe that web gets from React `cache()` is unnecessary here because
// one screen = one loader; `loadHomeVM`'s internal `Promise.all` still parallelizes.

export interface LoaderState<T> {
  data: T | null;
  error: unknown;
  loading: boolean;
  refetch: () => void;
}

export function useLoader<T>(loader: () => Promise<T>): LoaderState<T> {
  const [data, setData] = useState<T | null>(null);
  const [error, setError] = useState<unknown>(null);
  const [loading, setLoading] = useState(true);
  // Keep the latest loader without making `run` depend on its identity (callers
  // pass an inline arrow; we don't want a refetch loop on every render).
  const loaderRef = useRef(loader);
  loaderRef.current = loader;
  // Drop results from a superseded run (refetch raced with an in-flight load).
  const runId = useRef(0);

  const run = useCallback(() => {
    const id = ++runId.current;
    setLoading(true);
    setError(null);
    loaderRef.current().then(
      (result) => {
        if (id !== runId.current) return;
        setData(result);
        setLoading(false);
      },
      (err) => {
        if (id !== runId.current) return;
        setError(err);
        setLoading(false);
      },
    );
  }, []);

  useEffect(() => {
    run();
    // Run once on mount; explicit refetch() handles re-loads.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  return { data, error, loading, refetch: run };
}
