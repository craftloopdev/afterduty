// Shared client helpers for the Stripe checkout / billing-portal flows.
// Both the paywall (PaywallView) and the profile Plan row open the billing
// portal; centralizing the POST /api/subscription call keeps a single source
// of truth for status mapping and avoids duplicating the redirect dance.

export type SubscriptionAction = "checkout" | "portal";

export type SubscriptionActionResult =
  | { ok: true; url: string }
  | { ok: false; reason: "forbidden" | "failed" };

/**
 * Requests a Stripe URL from the BFF for `checkout` (with a plan tier) or the
 * billing `portal`. Returns the URL on success; the caller performs the
 * redirect so it can keep its own busy/error UI in sync. Never throws.
 */
export async function requestSubscriptionUrl(
  action: SubscriptionAction,
  tier?: string,
): Promise<SubscriptionActionResult> {
  try {
    const res = await fetch("/api/subscription", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ action, tier }),
    });
    const data = (await res.json().catch(() => ({}))) as { url?: string };
    if (res.status === 403) return { ok: false, reason: "forbidden" };
    if (!res.ok || !data?.url) return { ok: false, reason: "failed" };
    return { ok: true, url: data.url };
  } catch {
    return { ok: false, reason: "failed" };
  }
}
