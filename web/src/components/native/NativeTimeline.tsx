"use client";

import { loadTimeline, useLoader } from "@/lib/api/endpoints.native";
import { TimelineView } from "@/components/timeline/TimelineView";
import HomeLoading from "@/app/(app)/loading";
import { LoaderBoundary } from "./LoaderBoundary";

// Native Timeline wrapper (§A.3). Same `TimelineView` as web — only the data
// path (RSC await → useLoader) differs. Unlike the Home digest, the journal IS
// this screen's content, so a failed load surfaces LoaderBoundary's error +
// Retry rather than a false "No updates yet".
export function NativeTimeline() {
  const state = useLoader(loadTimeline);
  return (
    <LoaderBoundary state={state} skeleton={<HomeLoading />}>
      {(vm) => <TimelineView days={vm.days} />}
    </LoaderBoundary>
  );
}
