"use client";

import { loadDocuments, useLoader } from "@/lib/api/endpoints.native";
import { DocumentsClient } from "@/components/documents/DocumentsClient";
import HomeLoading from "@/app/(app)/loading";
import { LoaderBoundary } from "./LoaderBoundary";

// Native Documents wrapper (§A.3). Renders the SHARED DocumentsClient so the
// native screen gets the same UploadCard + "Write a statement" quick-add +
// "Describe what you remember → Ask AI" card as web (P2-1). The loader carries
// `subState` (for the Describe card's Pro affordance) alongside the list, and
// `onChanged` wires delete/quick-add refreshes back to the loader's refetch —
// router.refresh() is a no-op on the static export.
export function NativeDocuments() {
  const state = useLoader(loadDocuments);
  return (
    <LoaderBoundary state={state} skeleton={<HomeLoading />}>
      {(data) => (
        <DocumentsClient
          initialDocs={data.docs}
          subState={data.subState}
          onChanged={state.refetch}
        />
      )}
    </LoaderBoundary>
  );
}
