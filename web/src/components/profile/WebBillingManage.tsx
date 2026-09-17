"use client";

import { useState } from "react";
import { requestSubscriptionUrl } from "@/lib/web-only/stripe-actions";
import type { BillingSource } from "./billing-source";
import styles from "./ProfileView.module.css";

// Web Stripe billing-manage row (capacitor-ios-spec §C.5). Extracted from
// ProfileView so its `requestSubscriptionUrl` import and "billing portal" copy
// live in a module the native build statically drops (ProfileView selects between
// this and `NativeBillingManage` on the inlined `NEXT_PUBLIC_NATIVE` literal — the
// §H.4 anti-steering audit greps `web/out/` for exactly these Stripe strings).
// Opens the Stripe billing portal (manage card / switch plan / cancel), reusing
// the same shared helper the paywall uses — no duplicated flow.
//
// P1-21: an RC-billed (apple/google) Pro viewing the WEB profile has no Stripe
// customer — the portal request would dead-end. Point them at the store that
// actually bills them instead. Unknown/absent source keeps today's Stripe path.
export function WebBillingManage({
  renews,
  billingSource,
}: {
  renews: string | null;
  billingSource?: BillingSource | null;
}) {
  const [portalBusy, setPortalBusy] = useState(false);
  const [portalError, setPortalError] = useState<string | null>(null);

  if (billingSource === "apple" || billingSource === "google") {
    return (
      <div className={styles.planManage}>
        {renews && <small className={styles.planRenews}>Renews {renews}</small>}
        <small className={styles.planRenews}>
          {billingSource === "apple"
            ? "Manage your subscription in the App Store on your iPhone."
            : "Manage your subscription in Google Play on your device."}
        </small>
      </div>
    );
  }

  async function onManageSubscription() {
    if (portalBusy) return;
    setPortalBusy(true);
    setPortalError(null);
    const result = await requestSubscriptionUrl("portal");
    if (result.ok) {
      window.location.assign(result.url);
      return; // navigating away — keep busy
    }
    setPortalError("Couldn't open the billing portal. Please try again.");
    setPortalBusy(false);
  }

  return (
    <div className={styles.planManage}>
      {renews && <small className={styles.planRenews}>Renews {renews}</small>}
      <button
        type="button"
        className={styles.manageBtn}
        onClick={onManageSubscription}
        disabled={portalBusy}
        aria-busy={portalBusy || undefined}
      >
        {portalBusy ? "Opening…" : "Manage subscription"}
      </button>
      {portalError && (
        <small className={styles.planError} role="alert">
          {portalError}
        </small>
      )}
    </div>
  );
}
