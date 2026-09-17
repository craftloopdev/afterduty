import type { SubscriptionVM } from "@/lib/models/vm";

const PLANS = [
  { tier: "monthly", price: 11.99, period: "month", label: "Monthly" },
  { tier: "annual", price: 119.99, period: "year", label: "Annual" },
];
const FEATURES = [
  { key: "extract", name: "AI evidence extraction", desc: "Pulls every relevant fact from your records." },
  { key: "synthesis", name: "Condition synthesis", desc: "Builds your conditions and scores each on the VA triad." },
  { key: "gaps", name: "Gap analysis", desc: "Finds the highest-value evidence to strengthen your claim." },
];

/** Free user — the sales paywall (default dev preview). */
export const subscriptionFixture: SubscriptionVM = {
  state: "free",
  active: false,
  currentTier: null,
  expiresAt: null,
  plans: PLANS,
  features: FEATURES,
};

/** Active Pro subscriber — the confirmation/ActiveCard branch, with renewal date. */
export const subscriptionProFixture: SubscriptionVM = {
  state: "pro",
  active: true,
  currentTier: "annual",
  expiresAt: "2027-01-15T00:00:00Z",
  plans: PLANS,
  features: FEATURES,
};

/**
 * Status fetch failed (upstream/network). PaywallView must render an honest
 * error+retry state — never the sales paywall with live Subscribe buttons.
 */
export const subscriptionErrorFixture: SubscriptionVM = {
  state: "error",
  active: false,
  currentTier: null,
  expiresAt: null,
  plans: PLANS,
  features: FEATURES,
};
