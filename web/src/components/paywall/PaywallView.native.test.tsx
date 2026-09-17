import { describe, it, expect, vi, beforeEach } from "vitest";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { PaywallView } from "./PaywallView";
import type { SubscriptionVM } from "@/lib/models/vm";
import { EULA_URL, PRIVACY_URL } from "@/lib/constants";

// The native paywall branch (§C.3/§H.4). These assert the App-Review-mandatory
// furniture renders and that ZERO Stripe-rail actions are reachable, plus the
// purchase → sync → refetch and Restore flows.

vi.mock("next/navigation", () => ({ useRouter: () => ({ refresh: vi.fn() }) }));

// Stripe action must never be invoked on native — fail loudly if it is.
const requestSubscriptionUrl = vi.fn();
vi.mock("@/lib/subscription-actions", () => ({
  requestSubscriptionUrl: (...args: unknown[]) => requestSubscriptionUrl(...args),
}));

const rcGetPlans = vi.fn();
const rcGetEntitlement = vi.fn();
const rcPurchase = vi.fn();
const rcRestore = vi.fn();
const rcManageSubscriptions = vi.fn();
vi.mock("@/lib/native/revenuecat", () => ({
  rcGetPlans: () => rcGetPlans(),
  rcGetEntitlement: () => rcGetEntitlement(),
  rcPurchase: (...a: unknown[]) => rcPurchase(...a),
  rcRestore: () => rcRestore(),
  rcManageSubscriptions: () => rcManageSubscriptions(),
}));

const syncRevenueCat = vi.fn();
vi.mock("@/lib/api/mutations", () => ({
  syncRevenueCat: () => syncRevenueCat(),
}));

vi.mock("@/lib/native/haptics", () => ({
  tapLight: vi.fn(),
  notifySuccess: vi.fn(),
  notifyError: vi.fn(),
}));

const PLAN_MONTHLY = {
  identifier: "$rc_monthly",
  productId: "pro_monthly",
  priceString: "$11.99",
  period: "month" as const,
  pkg: { id: "m" },
};
const PLAN_ANNUAL = {
  identifier: "$rc_annual",
  productId: "pro_annual",
  priceString: "$119.99",
  period: "year" as const,
  pkg: { id: "a" },
};

const FREE: SubscriptionVM = {
  state: "free",
  active: false,
  currentTier: null,
  expiresAt: null,
  plans: [],
  features: [{ key: "x", name: "AI extraction", desc: "..." }],
};
const PRO: SubscriptionVM = {
  ...FREE,
  state: "pro",
  active: true,
  currentTier: "annual",
  expiresAt: "2027-01-15T00:00:00Z",
};
const ERROR: SubscriptionVM = { ...FREE, state: "error" };

beforeEach(() => {
  vi.clearAllMocks();
  rcGetPlans.mockResolvedValue([PLAN_MONTHLY, PLAN_ANNUAL]);
  rcGetEntitlement.mockResolvedValue(null);
  rcPurchase.mockResolvedValue({ kind: "purchased" });
  rcRestore.mockResolvedValue({ kind: "purchased" });
  syncRevenueCat.mockResolvedValue(true);
});

describe("PaywallView native branch — App Review furniture (§H.4)", () => {
  it("renders StoreKit-localized prices (never literals), Restore, disclosure + both legal links", async () => {
    render(<PaywallView sub={FREE} native onRefetch={vi.fn()} />);

    // StoreKit price strings from the RC packages.
    expect(await screen.findByText("$11.99")).toBeInTheDocument();
    expect(screen.getByText("$119.99")).toBeInTheDocument();

    // Mandatory Restore Purchases button.
    expect(screen.getByRole("button", { name: /restore purchases/i })).toBeInTheDocument();

    // Auto-renewal disclosure interpolates the StoreKit prices.
    expect(
      screen.getByText(/auto-renewing subscription: \$11\.99\/month or \$119\.99\/year/i),
    ).toBeInTheDocument();
    expect(screen.getByText(/charged to your Apple Account/i)).toBeInTheDocument();

    // Functional Terms (EULA) + Privacy links pointing at the canonical URLs.
    const terms = screen.getByRole("link", { name: /terms of use/i });
    const privacy = screen.getByRole("link", { name: /privacy policy/i });
    expect(terms).toHaveAttribute("href", EULA_URL);
    expect(privacy).toHaveAttribute("href", PRIVACY_URL);
  });

  it("renders ZERO Stripe-rail actions and never calls requestSubscriptionUrl", async () => {
    render(<PaywallView sub={FREE} native onRefetch={vi.fn()} />);
    await screen.findByText("$11.99");
    // No Stripe steering copy.
    expect(screen.queryByText(/web/i)).not.toBeInTheDocument();
    expect(screen.queryByText(/stripe/i)).not.toBeInTheDocument();
    const user = userEvent.setup();
    await user.click(screen.getAllByRole("button", { name: /^Subscribe/i })[0]);
    expect(requestSubscriptionUrl).not.toHaveBeenCalled();
  });

  it("purchase → syncRevenueCat → refetch", async () => {
    const onRefetch = vi.fn();
    render(<PaywallView sub={FREE} native onRefetch={onRefetch} />);
    const user = userEvent.setup();
    await user.click((await screen.findAllByRole("button", { name: /^Subscribe/i }))[0]);
    await waitFor(() => expect(rcPurchase).toHaveBeenCalledWith(PLAN_MONTHLY));
    await waitFor(() => expect(syncRevenueCat).toHaveBeenCalled());
    await waitFor(() => expect(onRefetch).toHaveBeenCalled());
  });

  it("a user-cancelled purchase is a silent reset — no sync, no refetch", async () => {
    rcPurchase.mockResolvedValue({ kind: "cancelled" });
    const onRefetch = vi.fn();
    render(<PaywallView sub={FREE} native onRefetch={onRefetch} />);
    const user = userEvent.setup();
    await user.click((await screen.findAllByRole("button", { name: /^Subscribe/i }))[0]);
    await waitFor(() => expect(rcPurchase).toHaveBeenCalled());
    expect(syncRevenueCat).not.toHaveBeenCalled();
    expect(onRefetch).not.toHaveBeenCalled();
  });

  it("Restore → syncRevenueCat → refetch", async () => {
    const onRefetch = vi.fn();
    render(<PaywallView sub={FREE} native onRefetch={onRefetch} />);
    const user = userEvent.setup();
    await user.click(await screen.findByRole("button", { name: /restore purchases/i }));
    await waitFor(() => expect(rcRestore).toHaveBeenCalled());
    await waitFor(() => expect(syncRevenueCat).toHaveBeenCalled());
    await waitFor(() => expect(onRefetch).toHaveBeenCalled());
  });

  it("active subscriber sees ActiveCard, no Subscribe buttons", async () => {
    rcGetEntitlement.mockResolvedValue({ active: true, store: "APP_STORE", expiresAt: null });
    render(<PaywallView sub={PRO} native onRefetch={vi.fn()} />);
    expect(await screen.findByText(/you.re on pro/i)).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /^Subscribe/i })).not.toBeInTheDocument();
  });

  it("error + active RC entitlement (oracle) upgrades to ActiveCard — never the free paywall (§C.4)", async () => {
    rcGetEntitlement.mockResolvedValue({ active: true, store: "APP_STORE", expiresAt: null });
    render(<PaywallView sub={ERROR} native onRefetch={vi.fn()} />);
    expect(await screen.findByText(/you.re on pro/i)).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /^Subscribe/i })).not.toBeInTheDocument();
  });

  it("error + no RC entitlement → purchase buttons shown (safe; StoreKit dedupes)", async () => {
    rcGetEntitlement.mockResolvedValue(null);
    render(<PaywallView sub={ERROR} native onRefetch={vi.fn()} />);
    expect(await screen.findByText("$11.99")).toBeInTheDocument();
    expect(screen.getAllByRole("button", { name: /^Subscribe/i }).length).toBeGreaterThan(0);
  });
});
