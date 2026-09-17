"use client";

import { loadAnalysisUpdates, loadConditionsPage, useLoader } from "@/lib/api/endpoints.native";
import { useRefetchOnResume } from "@/lib/native/resume";
import { ConditionsList } from "@/components/conditions/ConditionsList";
import { ConditionsEmpty } from "@/components/conditions/ConditionsEmpty";
import HomeLoading from "@/app/(app)/loading";
import { LoaderBoundary } from "./LoaderBoundary";

// Native Conditions wrapper (§A.3). Same empty-state and `ConditionsList` as the
// web page — only the data path (RSC await → useLoader) differs. The list plus
// the subscription context the empty state needs (P1-16) come from the shared
// `loadConditionsPage` loader in endpoints-core.
export function NativeConditions() {
  const state = useLoader(loadConditionsPage);
  // "Updated" pills — supplementary (degrades to empty internally), so it never
  // gates the list render.
  const updates = useLoader(loadAnalysisUpdates);

  // Native freshness (P1-22): refetch when the app returns to the foreground —
  // the pipeline may have finished while we were backgrounded.
  useRefetchOnResume(() => {
    state.refetch();
    updates.refetch();
  });

  return (
    <LoaderBoundary state={state} skeleton={<HomeLoading />}>
      {({ conditions, subState, documentsCount }) =>
        conditions.length === 0 ? (
          // Subscription-aware copy (P1-16): free-with-docs gets the honest
          // Pro boundary; "error" NEVER selects the free copy.
          <ConditionsEmpty subState={subState} documentsCount={documentsCount} />
        ) : (
          <ConditionsList
            conditions={conditions}
            updatedIds={updates.data?.changedConditionIds}
            onChanged={state.refetch}
          />
        )
      }
    </LoaderBoundary>
  );
}
