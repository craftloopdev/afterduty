"use client";

import { loadProfilePage, useLoader } from "@/lib/api/endpoints.native";
import { ServiceHistoryView } from "@/components/profile/ServiceHistoryView";
import HomeLoading from "@/app/(app)/loading";
import { LoaderBoundary } from "./LoaderBoundary";

// Native Service History wrapper (§A.3). Same `ServiceHistoryView` as the web
// page — only the data path (RSC await → useLoader) differs. Reuses the shared
// `loadProfilePage` loader (the service periods, with their reconciliation
// receipts, ride on the profile view-model).
export function NativeServiceHistory() {
  const state = useLoader(loadProfilePage);
  return (
    <LoaderBoundary state={state} skeleton={<HomeLoading />}>
      {(profile) => (
        <ServiceHistoryView periods={profile.servicePeriods ?? []} onSaved={state.refetch} />
      )}
    </LoaderBoundary>
  );
}
