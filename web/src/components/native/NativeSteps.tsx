"use client";

import { loadNextSteps, useLoader } from "@/lib/api/endpoints.native";
import { useRefetchOnResume } from "@/lib/native/resume";
import { NextStepsTabs } from "@/components/steps/NextStepsTabs";
import { StepsEmpty } from "@/components/steps/StepsEmpty";
import HomeLoading from "@/app/(app)/loading";
import { LoaderBoundary } from "./LoaderBoundary";

// Native Next Steps wrapper (§A.3) — same empty-state + `NextStepsTabs` as web.
export function NativeSteps() {
  const state = useLoader(loadNextSteps);

  // Native freshness (P1-22): refetch on app resume — a run that finished in
  // the background must not leave stale steps on screen.
  useRefetchOnResume(state.refetch);

  return (
    <LoaderBoundary state={state} skeleton={<HomeLoading />}>
      {(vm) =>
        vm.steps.length === 0 && vm.readyCount === 0 ? (
          // Mid-re-run the gaps are being recomputed — "add evidence" would be
          // a false instruction; the tabs render the honest "Re-checking…"
          // state (parity with the web page).
          vm.gapAnalysisPending || vm.pipelineActive ? (
            <NextStepsTabs vm={vm} onRefresh={state.refetch} />
          ) : (
            // Subscription-aware copy (P1-16): free-with-docs gets the honest
            // Pro boundary; "error" NEVER selects the free copy.
            <StepsEmpty subState={vm.subState} documentsCount={vm.documentsCount} />
          )
        ) : (
          <NextStepsTabs vm={vm} onRefresh={state.refetch} />
        )
      }
    </LoaderBoundary>
  );
}
