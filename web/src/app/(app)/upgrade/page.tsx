import { loadSubscription, loadUsageBreakdown } from "@/lib/api/endpoints";
import { PaywallView } from "@/components/paywall/PaywallView";
import { readCheckoutOutcome } from "@/lib/checkout-return";
import { NATIVE } from "@/lib/platform";
import { NativeUpgrade } from "@/components/native/NativeUpgrade";

/**
 * Stripe-return handling for the canonical `/upgrade?checkout=success|cancelled`
 * URL (and a bare `session_id`, which the default success_url appends). The
 * deployed default success/cancel URLs actually point at the HOME route, so the
 * HOME page (src/app/(app)/page.tsx) reads those and redirects here with the
 * canonical param. See src/lib/checkout-return.ts for the full convention and
 * the backend env-var follow-up that removes the home hop.
 */
export default async function UpgradePage({
  searchParams,
}: {
  searchParams: Promise<Record<string, string | string[] | undefined>>;
}) {
  if (NATIVE) return <NativeUpgrade />;
  const [sub, usage, sp] = await Promise.all([
    loadSubscription(),
    loadUsageBreakdown(),
    searchParams,
  ]);
  return <PaywallView sub={sub} usage={usage} checkout={readCheckoutOutcome(sp)} />;
}
