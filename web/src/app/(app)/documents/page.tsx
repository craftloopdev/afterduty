import { loadDocuments } from "@/lib/api/endpoints";
import { DocumentsClient } from "@/components/documents/DocumentsClient";
import { NATIVE } from "@/lib/platform";
import { NativeDocuments } from "@/components/native/NativeDocuments";

export default async function DocumentsPage() {
  if (NATIVE) return <NativeDocuments />;

  // Seed the client owner; it re-fetches /api/claim/documents after an upload or
  // delete so the list updates without a manual browser refresh. `subState`
  // rides along so the "Describe to AI" card carries the honest Pro affordance.
  const { docs, subState } = await loadDocuments();
  return <DocumentsClient initialDocs={docs} subState={subState} />;
}
