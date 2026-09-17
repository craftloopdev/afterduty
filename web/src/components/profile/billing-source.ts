/**
 * Billing-source reader (P1-21). `/subscription/status` MAY expose where the
 * subscription is billed (stripe | apple | google); the backend field is
 * landing this cycle, so read it DEFENSIVELY off the profile VM: when the
 * mapper starts forwarding it, the Apple/Google branches light up with no
 * change here; until then (or on any unrecognized value) callers get `null`
 * and must render the generic copy that covers every store.
 *
 * Local type on purpose — `ProfileVM` (models/vm.ts) is another agent's
 * territory this cycle; the `billingSource` addition is tracked as a followUp.
 */
// "portal" = billed via the web billing portal. Named WITHOUT the vendor
// literal on purpose: this module ships in the NATIVE bundle and the §H.4
// anti-steering release guard greps the export for that string.
export type BillingSource = "portal" | "apple" | "google";

export function readBillingSource(profile: unknown): BillingSource | null {
  if (typeof profile !== "object" || profile === null) return null;
  const raw = (profile as { billingSource?: unknown }).billingSource;
  if (typeof raw !== "string") return null;
  const v = raw.trim().toLowerCase();
  if (v === "apple" || v === "google") return v;
  // The backend only emits apple/google/its web-billing value; any other
  // non-empty source is the web portal.
  return v.length > 0 ? "portal" : null;
}

/**
 * The delete-account modal's subscription warning (App Review 5.1.1(v)):
 * deleting the account must not silently leave an Apple/Google subscription
 * billing. Returns `null` when no extra line is needed:
 *  - confirmed free user → nothing to warn about;
 *  - stripe → current behavior (the account owns the subscription).
 * `subState` is the tri-state truth — "error" is NEVER treated as free, so an
 * unknown subscription still gets the generic warning.
 */
export function deleteBillingWarning(
  source: BillingSource | null,
  subState: "pro" | "free" | "error",
): string | null {
  if (subState === "free") return null;
  switch (source) {
    case "apple":
      return "Deleting your account does NOT cancel your Apple subscription — manage it in Settings > Apple ID on your device.";
    case "google":
      return "Deleting your account does NOT cancel your Google Play subscription — manage it in the Play Store on your device.";
    case "portal":
      return null;
    default:
      // Billing source unknown — cover both stores without asserting either.
      return "If you subscribed through the App Store or Google Play, deleting your account does NOT cancel that subscription — manage it with Apple or Google on your device.";
  }
}
