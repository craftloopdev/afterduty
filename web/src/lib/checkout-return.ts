// Single source of truth for reading Stripe's post-checkout return params.
//
// Two conventions reach us, and the DEFAULT (deployed) one does NOT land on
// /upgrade at all:
//
//  (a) Canonical (preferred): the operator sets CHECKOUT_SUCCESS_URL /
//      CHECKOUT_CANCEL_URL to `…/upgrade?checkout=success|cancelled`. /upgrade
//      reads `?checkout` directly — this is the explicit signal we want.
//
//  (b) Backend defaults (application.yml:270-271): success_url is
//      `https://app.afterduty.app/?upgraded=true` and cancel_url is
//      `…/?upgrade_cancelled=true` — both the HOME route, not /upgrade — and
//      StripeService.java:141-142 appends `&session_id=…` onto the success one.
//      So a real paying customer lands on `/`, never on /upgrade, and the
//      success banner + post-checkout refetch never fire.
//
// The HOME route still reads these params and redirects to the canonical
// `/upgrade?checkout=success|cancelled`. Both the home redirect and /upgrade
// use this one parser so the mapping can't drift.
//
// RESOLVED 2026-08-12 (rebrand Stage 1): convention (a) is now BOTH the
// application.yml default and the deployed Cloud Run env, so checkout and the
// billing portal land directly on /upgrade — no home hop. The (b) handling
// below is retained because Stripe sessions created under the old
// configuration can still return, and because StripeService appends
// `&session_id=…` to the success URL.

export type CheckoutOutcome = "success" | "cancelled" | null;

type SearchParams = Record<string, string | string[] | undefined>;

function first(v: string | string[] | undefined): string | undefined {
  return Array.isArray(v) ? v[0] : v;
}

/**
 * Read the canonical `?checkout=success|cancelled` signal (option a). Falls
 * back to treating a bare `session_id` as success, since the default
 * success_url appends it (StripeService.java:141-142).
 */
export function readCheckoutOutcome(sp: SearchParams): CheckoutOutcome {
  const checkout = first(sp.checkout);
  if (checkout === "success" || checkout === "cancelled") return checkout;
  if (first(sp.session_id)) return "success";
  return null;
}

/**
 * Read the HOME-route variant of the checkout return (option b defaults):
 * `?upgraded=true` (+ optional `&session_id`) → success,
 * `?upgrade_cancelled=true` → cancelled. Returns the canonical outcome so the
 * home route can redirect to `/upgrade?checkout=<outcome>`.
 */
export function readHomeCheckoutOutcome(sp: SearchParams): CheckoutOutcome {
  if (first(sp.upgraded) === "true" || first(sp.session_id)) return "success";
  if (first(sp.upgrade_cancelled) === "true") return "cancelled";
  return null;
}
