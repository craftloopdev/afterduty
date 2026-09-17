import { describe, it, expect, vi } from "vitest";
import { render, screen } from "@testing-library/react";
import { StripePaywall } from "./StripePaywall";
import { PaywallView } from "./PaywallView";
import type { SubscriptionVM } from "@/lib/models/vm";

// §6.5 paywall rewrite (P1-18): outcome language, the "Free forever" block,
// mission framing, and the hardship note — asserted on BOTH branches. The §H.4
// anti-steering property (no Stripe strings / "web" steering copy in the native
// render) is re-asserted here so the copy rewrite can never regress it.

vi.mock("next/navigation", () => ({ useRouter: () => ({ refresh: vi.fn() }) }));
vi.mock("@/lib/web-only/stripe-actions", () => ({ requestSubscriptionUrl: vi.fn() }));

vi.mock("@/lib/native/revenuecat", () => ({
  rcGetPlans: () =>
    Promise.resolve([
      {
        identifier: "$rc_monthly",
        productId: "pro_monthly",
        priceString: "$11.99",
        period: "month",
        pkg: { id: "m" },
      },
    ]),
  rcGetEntitlement: () => Promise.resolve(null),
  rcPurchase: vi.fn(),
  rcRestore: vi.fn(),
  rcManageSubscriptions: vi.fn(),
}));
vi.mock("@/lib/native/haptics", () => ({
  tapLight: vi.fn(),
  notifySuccess: vi.fn(),
  notifyError: vi.fn(),
}));
vi.mock("@/lib/api/mutations", () => ({ syncRevenueCat: vi.fn() }));

const FREE: SubscriptionVM = {
  state: "free",
  active: false,
  currentTier: null,
  expiresAt: null,
  plans: [{ tier: "monthly", price: 11.99, period: "month", label: "Monthly" }],
  features: [{ key: "gaps", name: "Gap guidance", desc: "..." }],
};

/** The §6.5 copy contract, shared by both branches. */
function assertOutcomeCopy() {
  // Outcome hero — not engineering nouns.
  expect(
    screen.getByText(/see exactly how to close your evidence gaps/i),
  ).toBeInTheDocument();
  expect(screen.queryByText(/unlock the full claim engine/i)).not.toBeInTheDocument();
  expect(screen.queryByText(/AI extraction, condition synthesis/i)).not.toBeInTheDocument();
  // "Free forever" block: what stays free, stated plainly.
  expect(screen.getByText(/free forever/i)).toBeInTheDocument();
  expect(screen.getByText(/no card required/i)).toBeInTheDocument();
  expect(screen.getByText(/every guide in learn/i)).toBeInTheDocument();
  // Mission framing.
  expect(
    screen.getByText(/pro keeps after duty free for other veterans/i),
  ).toBeInTheDocument();
  // Hardship note with a working support mailto.
  expect(screen.getByText(/no veteran gets turned away/i)).toBeInTheDocument();
  const mail = screen.getByRole("link", { name: /email us/i });
  expect(mail).toHaveAttribute("href", "mailto:support@afterduty.app");
}

describe("Paywall §6.5 outcome copy — web (Stripe) branch", () => {
  it("shows outcome hero, Free forever block, mission line, and hardship mailto", () => {
    render(<StripePaywall sub={FREE} checkout={null} />);
    assertOutcomeCopy();
    // Commerce mechanics intact: the plan card still subscribes.
    expect(screen.getAllByRole("button", { name: /^Subscribe/i }).length).toBeGreaterThan(0);
  });

  it("does not render the sales blocks for an active subscriber", () => {
    const pro: SubscriptionVM = {
      ...FREE,
      state: "pro",
      active: true,
      currentTier: "monthly",
    };
    render(<StripePaywall sub={pro} checkout={null} />);
    expect(screen.queryByText(/free forever/i)).not.toBeInTheDocument();
    expect(screen.queryByText(/see exactly how to close/i)).not.toBeInTheDocument();
  });
});

describe("Paywall §6.5 outcome copy — native (RevenueCat) branch", () => {
  it("shows the same copy AND holds the §H.4 anti-steering + furniture assertions", async () => {
    render(<PaywallView sub={FREE} native onRefetch={vi.fn()} />);
    await screen.findByText("$11.99"); // StoreKit plans resolved
    assertOutcomeCopy();
    // §H.4 must not regress: mandatory Restore, zero Stripe/steering strings.
    expect(screen.getByRole("button", { name: /restore purchases/i })).toBeInTheDocument();
    expect(screen.queryByText(/stripe/i)).not.toBeInTheDocument();
    expect(screen.queryByText(/web/i)).not.toBeInTheDocument();
  });
});
