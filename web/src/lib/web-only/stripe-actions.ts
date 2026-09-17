"use client";

// Web-only Stripe action accessor (capacitor-ios-spec §C.3/§H.4 anti-steering).
// The native build must contain ZERO Stripe UI strings/flows — the §H.4 bundle
// audit greps `web/out/` for exactly this. A plain top-level
// `import { requestSubscriptionUrl }` is NOT tree-shaken even when its callers are
// behind a dead `NATIVE` JSX branch (the import keeps the module — and its
// "/api/subscription" / "billing portal" strings — alive in the bundle).
//
// So we mirror the auth-driver / splash facade idiom and put the `require` INSIDE
// the `if (!NATIVE)` block. On native `NATIVE` inlines to the literal `true`, so
// `if (!NATIVE)` is `if (false)` — webpack's dead-code elimination drops the whole
// block, and with it the `require("@/lib/subscription-actions")`. The real Stripe
// module (and every Stripe string) therefore never enters the static export. On
// web the block is live and forwards to the real helper unchanged.

import type {
  SubscriptionAction,
  SubscriptionActionResult,
} from "@/lib/subscription-actions";

export type { SubscriptionAction, SubscriptionActionResult };

// Inline `process.env.NEXT_PUBLIC_NATIVE` DIRECTLY (not via the imported `NATIVE`
// const) so webpack's DefinePlugin substitutes the literal at the `if` site and
// dead-code-eliminates the entire web block — including the
// `require("@/lib/subscription-actions")` — from the native bundle. Going through
// a re-exported const can leave the require reachable; the literal does not.

/** The web Stripe checkout/portal helper. Web-only — on native this is a
 *  never-reached stub (callers branch to RevenueCat first) and the real module is
 *  tree-shaken out of the export bundle (§H.4). */
export function requestSubscriptionUrl(
  action: SubscriptionAction,
  tier?: string,
): Promise<SubscriptionActionResult> {
  if (process.env.NEXT_PUBLIC_NATIVE !== "1") {
    // Live only on web; on native the condition folds to `false` and this block
    // (with its Stripe `require`) is removed.
    // eslint-disable-next-line @typescript-eslint/no-require-imports
    const mod = require("@/lib/subscription-actions") as typeof import("@/lib/subscription-actions");
    return mod.requestSubscriptionUrl(action, tier);
  }
  // Unreachable on native by construction; fail safe if it ever isn't.
  return Promise.resolve({ ok: false, reason: "failed" });
}
