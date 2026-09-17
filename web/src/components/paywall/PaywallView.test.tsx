import { describe, it, expect, vi, beforeEach } from "vitest";
import { render, screen } from "@testing-library/react";
// Render the web Stripe branch directly. Since the §H.4 anti-steering hardening,
// `StripePaywall` is its own web-only module (the dispatcher `PaywallView` loads
// it via a DCE-gated `require`, so it carries no Stripe import); the web behavior
// asserted here lives in this component. A separate dispatcher test
// (PaywallView.dispatch.test.tsx) covers PaywallView's native-vs-Stripe routing.
import { StripePaywall } from "./StripePaywall";
import type { SubscriptionVM } from "@/lib/models/vm";

// StripePaywall uses useRouter().refresh() after a success return; stub it.
const refresh = vi.fn();
vi.mock("next/navigation", () => ({ useRouter: () => ({ refresh }) }));
// Don't hit the BFF from button handlers in these render-only assertions.
vi.mock("@/lib/subscription-actions", () => ({
  requestSubscriptionUrl: vi.fn(),
}));

const PLANS = [
  { tier: "monthly", price: 11.99, period: "month", label: "Monthly" },
  { tier: "annual", price: 119.99, period: "year", label: "Annual" },
];
const FEATURES = [{ key: "x", name: "AI extraction", desc: "..." }];

const FREE: SubscriptionVM = {
  state: "free",
  active: false,
  currentTier: null,
  expiresAt: null,
  plans: PLANS,
  features: FEATURES,
};
const PRO: SubscriptionVM = {
  state: "pro",
  active: true,
  currentTier: "annual",
  expiresAt: "2027-01-15T00:00:00Z",
  plans: PLANS,
  features: FEATURES,
};

beforeEach(() => {
  refresh.mockReset();
});

describe("PaywallView — success banner gated on refetched Pro state", () => {
  it("asserts 'subscription is active' ONLY when checkout=success AND sub.active", () => {
    render(<StripePaywall sub={PRO} checkout="success" />);
    expect(screen.getByText(/welcome to Pro\. Your subscription is active/i)).toBeInTheDocument();
  });

  it("shows a neutral 'finalizing' banner — never 'active' — when success but still free", () => {
    // The spoof / webhook-lag case: success URL but the refetch says free.
    render(<StripePaywall sub={FREE} checkout="success" />);
    expect(screen.getByText(/finalizing your subscription/i)).toBeInTheDocument();
    // Must NOT claim the subscription is active.
    expect(screen.queryByText(/subscription is active/i)).not.toBeInTheDocument();
    expect(screen.queryByText(/welcome to Pro/i)).not.toBeInTheDocument();
    // Still a free user, so the sales paywall (Subscribe) is shown beneath it.
    expect(screen.getAllByRole("button", { name: /^Subscribe/i }).length).toBeGreaterThan(0);
  });

  it("shows the URL-driven cancelled banner without asserting any access", () => {
    render(<StripePaywall sub={FREE} checkout="cancelled" />);
    expect(screen.getByText(/No charge was made/i)).toBeInTheDocument();
  });

  it("refetches once on a success return to pick up the now-active subscription", () => {
    render(<StripePaywall sub={FREE} checkout="success" />);
    expect(refresh).toHaveBeenCalledTimes(1);
  });

  it("renders the honest error state (no Subscribe buttons) on a status-fetch failure", () => {
    const errored: SubscriptionVM = { ...FREE, state: "error" };
    render(<StripePaywall sub={errored} checkout={null} />);
    expect(screen.getByText(/couldn.t load your subscription/i)).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /^Subscribe/i })).not.toBeInTheDocument();
  });
});
