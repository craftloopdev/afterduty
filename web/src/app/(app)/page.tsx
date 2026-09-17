import { redirect } from "next/navigation";
import { loadAnalysisUpdates, loadHomeVM } from "@/lib/api/endpoints";
import { HomeView } from "@/components/home/HomeView";
import { readHomeCheckoutOutcome } from "@/lib/checkout-return";
import { NATIVE } from "@/lib/platform";
import { NativeHome } from "@/components/native/NativeHome";

export default async function HomePage({
  searchParams,
}: {
  searchParams: Promise<Record<string, string | string[] | undefined>>;
}) {
  // Native (static export): client wrapper fetches via DirectApiClient (§A.3).
  // The Stripe-return redirect below is web-only by construction.
  if (NATIVE) return <NativeHome />;

  // The deployed Stripe success/cancel URLs default to the HOME route
  // (`?upgraded=true` / `?upgrade_cancelled=true`, plus an appended
  // `session_id`), not /upgrade — so a paying customer lands here. Forward to
  // the canonical /upgrade?checkout=… so the success/cancel banner and the
  // post-checkout refetch fire. See src/lib/checkout-return.ts.
  const outcome = readHomeCheckoutOutcome(await searchParams);
  if (outcome) redirect(`/upgrade?checkout=${outcome}`);

  // The journal read is supplementary (it degrades to empty internally) and
  // server-loaded here so the digest card renders without a client fetch.
  const [vm, updates] = await Promise.all([loadHomeVM(), loadAnalysisUpdates()]);
  return <HomeView vm={vm} updates={updates} />;
}
