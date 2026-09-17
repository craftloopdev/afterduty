"use client";

import { loadShares, useLoader } from "@/lib/api/endpoints.native";
import { ShareManager } from "@/components/share/ShareManager";
import HomeLoading from "@/app/(app)/loading";
import { LoaderBoundary } from "./LoaderBoundary";

// Native Share wrapper (§A.3). `ShareManager` posts through the mutations facade
// (direct `/shares` on native), so only the list load moves to useLoader.
// `onChanged` is the native stand-in for `router.refresh()` (a no-op under the
// static export — P1-22): create/re-invite/revoke re-run the list loader, and
// LoaderBoundary keeps the mounted view (and its invite-link notice) rendered
// while the refetch is in flight because `data` stays non-null.
export function NativeShare() {
  const state = useLoader(loadShares);
  return (
    <LoaderBoundary state={state} skeleton={<HomeLoading />}>
      {(shares) => <ShareManager shares={shares} onChanged={state.refetch} />}
    </LoaderBoundary>
  );
}
