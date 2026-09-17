"use client";

import { loadAnalysisUpdates, loadHomeVM, useLoader } from "@/lib/api/endpoints.native";
import { useRefetchOnResume } from "@/lib/native/resume";
import { HomeView } from "@/components/home/HomeView";
import HomeLoading from "@/app/(app)/loading";
import { LoaderBoundary } from "./LoaderBoundary";

// Native Home wrapper (capacitor-ios-spec §A.3): the RSC `loadHomeVM()` await on
// web becomes a client `useLoader` here, rendering the SAME `HomeView`. The
// Stripe-return redirect the web page owns is web-only by construction (Stripe
// never runs natively — §A.3), so there is nothing to port for it.
export function NativeHome() {
  const state = useLoader(loadHomeVM);
  // The claim journal (digest card / timeline link). loadAnalysisUpdates
  // degrades to the empty VM internally, so this loader never gates the screen
  // — while (or if) it can't resolve, Home simply renders without the card.
  const updates = useLoader(loadAnalysisUpdates);

  const refetchAll = () => {
    state.refetch();
    updates.refetch();
  };

  // Native freshness (P1-22): numbers and the digest may have moved while the
  // app was backgrounded — refetch on every return to the foreground.
  useRefetchOnResume(refetchAll);

  return (
    <LoaderBoundary state={state} skeleton={<HomeLoading />}>
      {/* refetch replaces router.refresh() (a no-op on the static export) so
          PipelinePulse's run-complete acknowledgment brings fresh numbers —
          and the fresh digest row written at the flip arrives with them. */}
      {(vm) => <HomeView vm={vm} updates={updates.data ?? undefined} onRefresh={refetchAll} />}
    </LoaderBoundary>
  );
}
