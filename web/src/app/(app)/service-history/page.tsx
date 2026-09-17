import { loadProfilePage } from "@/lib/api/endpoints";
import { ServiceHistoryView } from "@/components/profile/ServiceHistoryView";
import { NATIVE } from "@/lib/platform";
import { NativeServiceHistory } from "@/components/native/NativeServiceHistory";

export const metadata = { title: "Service history — After Duty" };

// Dedicated Service History screen (Service History P2). A FIXED path — not a
// per-user `[param]` route — so it exports cleanly for the Capacitor static
// build and needs NO query-param twin (unlike `/conditions/[id]`). The service
// periods, carrying their reconciliation receipts (`sources`/`reasoning`/
// `totalYears`), ride on the same profile view-model ProfileView loads, so we
// reuse `loadProfilePage` and hand the periods to the client view.
export default async function ServiceHistoryPage() {
  if (NATIVE) return <NativeServiceHistory />;

  const profile = await loadProfilePage();
  return <ServiceHistoryView periods={profile.servicePeriods ?? []} />;
}
