"use client";

import { Suspense } from "react";
import { useSearchParams } from "next/navigation";
import { ThemeProvider } from "@/components/shell/ThemeProvider";
import { AppShell } from "@/components/shell/AppShell";
import { PaywallView, type CheckoutOutcome } from "@/components/paywall/PaywallView";
import {
  subscriptionFixture,
  subscriptionProFixture,
  subscriptionErrorFixture,
} from "@/lib/fixtures/subscription";
import type { SubscriptionVM } from "@/lib/models/vm";

// Dev preview for the paywall states via ?v=:
//   (default)        free user — sales paywall
//   pro              active subscriber — ActiveCard + renewal date + Manage
//   error            status fetch failed — honest error + retry (no Subscribe buttons)
//   success          checkout success + refetch confirms Pro → "welcome to Pro" banner
//   success-pending  checkout success but still free (webhook lag / spoofed) →
//                    neutral "finalizing…" banner over the sales paywall, never "active"
//   cancelled        no-charge banner over the sales paywall
// Dev routes 404 in production (handled globally). Variant read CLIENT-side so
// the route exports statically for the native capture build (§F.2/§F.4 — the
// paywall screenshot also serves as the IAP Review Information screenshot).
export default function DevUpgradePage() {
  return (
    <Suspense fallback={null}>
      <DevUpgradeInner />
    </Suspense>
  );
}

function DevUpgradeInner() {
  const v = useSearchParams().get("v") ?? undefined;
  let sub: SubscriptionVM = subscriptionFixture;
  let checkout: CheckoutOutcome = null;
  if (v === "pro") sub = subscriptionProFixture;
  else if (v === "error") sub = subscriptionErrorFixture;
  else if (v === "success") {
    sub = subscriptionProFixture;
    checkout = "success";
  } else if (v === "success-pending") {
    // success URL but the webhook hasn't granted Pro yet (or a spoofed
    // session_id): still free, so the banner must NOT claim "active".
    sub = subscriptionFixture;
    checkout = "success";
  } else if (v === "cancelled") checkout = "cancelled";

  return (
    <ThemeProvider>
      <AppShell userName="Griff" branch="U.S. Army · SGT" subState={sub.state}>
        <PaywallView sub={sub} checkout={checkout} />
      </AppShell>
    </ThemeProvider>
  );
}
