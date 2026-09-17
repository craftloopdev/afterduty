import { describe, it, expect, vi } from "vitest";
import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { render, screen } from "@testing-library/react";
import { PaywallView } from "./PaywallView";
import type { SubscriptionVM } from "@/lib/models/vm";

// PaywallView is a thin dispatcher (§C.3/§H.4): it routes to the native
// RevenueCat branch, or to the web Stripe branch which it loads via a DCE-gated
// `require("@/components/paywall/StripePaywall")` — so the dispatcher module
// carries NO Stripe import and the native export can tree-shake the Stripe module
// (and its "/api/subscription" / "billing portal" / requestSubscriptionUrl
// strings) out entirely. The §H.4 audit greps web/out/ for those strings.
//
// The native render path is asserted live here (it never touches the Stripe
// require). The web Stripe branch is rendered/asserted directly in
// PaywallView.test.tsx (the runtime `require` of an aliased .tsx isn't resolvable
// under vite-node, so the dispatcher's web route is exercised at build/runtime,
// not in this unit). We additionally assert at the SOURCE level that the
// dispatcher imports Stripe only through the dead-branch require — never a static
// top-level import — which is the property that actually keeps web/out/ clean.

vi.mock("@/lib/native/revenuecat", () => ({
  rcGetPlans: () => Promise.resolve([]),
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
  plans: [],
  features: [],
};

describe("PaywallView dispatcher", () => {
  it("routes to the native (RevenueCat) branch when `native` is set — no Stripe leaf", () => {
    render(<PaywallView sub={FREE} native onRefetch={vi.fn()} />);
    // The native paywall shows its sales hero (StripePaywall would show the same
    // hero but ALSO live via the require; the assertion below proves the native
    // path never imports Stripe). §6.5 outcome copy.
    expect(
      screen.getByText(/See exactly how to close your evidence gaps/i),
    ).toBeInTheDocument();
    // MANDATORY native furniture — proves we rendered the native branch (§H.4).
    expect(screen.getByRole("button", { name: /Restore Purchases/i })).toBeInTheDocument();
  });

  it("imports the Stripe branch ONLY through a dead-branch require, never a static import (§H.4)", () => {
    // vitest runs from web/; the dispatcher source is read from the repo path.
    const src = readFileSync(
      resolve(process.cwd(), "src/components/paywall/PaywallView.tsx"),
      "utf8",
    );
    // No static ES import of StripePaywall or any Stripe action module.
    expect(src).not.toMatch(/^\s*import\s+\{?\s*StripePaywall/m);
    expect(src).not.toMatch(/import\s+.*from\s+["'][^"']*stripe-actions["']/);
    expect(src).not.toMatch(/import\s+.*from\s+["'][^"']*subscription-actions["']/);
    // It DOES load Stripe via the gated require inside the `!== "1"` dead branch.
    expect(src).toMatch(/require\(["']@\/components\/paywall\/StripePaywall["']\)/);
  });
});
