"use client";

import { loadSubscription, useLoader } from "@/lib/api/endpoints.native";
import { PaywallView } from "@/components/paywall/PaywallView";
import HomeLoading from "@/app/(app)/loading";
import { LoaderBoundary } from "./LoaderBoundary";

// Native Upgrade wrapper (§A.3). The `?checkout=` success/cancel banner is
// web-only by construction (Stripe never runs natively), so it is omitted here.
// `native` switches PaywallView to the RevenueCat/StoreKit branch (§C.3); the
// useLoader supplies the tri-state `SubscriptionVM` (the RC oracle refines an
// "error" backend read on-device — §C.4) and its `refetch` is the post-purchase
// refresh (there is no `router.refresh()` natively).
export function NativeUpgrade() {
  const state = useLoader(loadSubscription);
  return (
    <LoaderBoundary state={state} skeleton={<HomeLoading />}>
      {(sub) => <PaywallView sub={sub} native onRefetch={state.refetch} />}
    </LoaderBoundary>
  );
}
