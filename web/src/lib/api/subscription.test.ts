import { describe, it, expect, vi, beforeEach } from "vitest";
import { UnauthorizedError, UpstreamError } from "./errors";
import type { SubscriptionStatus, UserResponse } from "@/lib/models/api";

// endpoints.ts is `server-only` and routes every call through serverFetch
// (which reads cookies). Stub both so we can unit-test the tri-state billing
// truth + expires_at plumbing as pure logic.
vi.mock("server-only", () => ({}));
const serverFetch = vi.fn();
vi.mock("./client", () => ({ serverFetch: (...args: unknown[]) => serverFetch(...args) }));

import {
  getSubscriptionResult,
  loadSubscription,
  loadProfilePage,
} from "./endpoints";

const ME: UserResponse = { id: 1, email: "vet@example.com", name: "Test Vet", role: "veteran" };

const PRO_STATUS: SubscriptionStatus = {
  active: true,
  current_tier: "annual",
  expires_at: "2027-01-15T00:00:00Z",
};

beforeEach(() => {
  serverFetch.mockReset();
});

/** Route serverFetch by path so multi-read loaders (profile) resolve correctly. */
function routeBy(map: Record<string, unknown | (() => unknown)>) {
  serverFetch.mockImplementation((path: string) => {
    for (const key of Object.keys(map)) {
      if (path.startsWith(key)) {
        const v = map[key];
        return Promise.resolve(typeof v === "function" ? (v as () => unknown)() : v);
      }
    }
    return Promise.resolve(null);
  });
}

describe("getSubscriptionResult — tri-state billing truth", () => {
  it("reports 'pro' for an active subscription", async () => {
    serverFetch.mockResolvedValue(PRO_STATUS);
    const r = await getSubscriptionResult();
    expect(r.state).toBe("pro");
    expect(r.status?.expires_at).toBe("2027-01-15T00:00:00Z");
  });

  it("reports 'free' when inactive", async () => {
    serverFetch.mockResolvedValue({ active: false });
    expect((await getSubscriptionResult()).state).toBe("free");
  });

  it("reports 'free' on a 404 (no subscription record)", async () => {
    serverFetch.mockResolvedValue(null); // allow404AsNull collapses 404 → null
    expect((await getSubscriptionResult()).state).toBe("free");
  });

  it("reports 'error' on an upstream failure — NOT 'free' (no double-charge bait)", async () => {
    serverFetch.mockRejectedValue(new UpstreamError(503, "down"));
    const r = await getSubscriptionResult();
    expect(r.state).toBe("error");
    expect(r.status).toBeNull();
  });

  it("rethrows 401 so the auth gate handles it (never mislabels as free)", async () => {
    serverFetch.mockRejectedValue(new UnauthorizedError());
    await expect(getSubscriptionResult()).rejects.toBeInstanceOf(UnauthorizedError);
  });
});

describe("loadSubscription — paywall VM", () => {
  it("carries state + expiresAt on the Pro VM", async () => {
    serverFetch.mockResolvedValue(PRO_STATUS);
    const vm = await loadSubscription();
    expect(vm.state).toBe("pro");
    expect(vm.active).toBe(true);
    expect(vm.currentTier).toBe("annual");
    expect(vm.expiresAt).toBe("2027-01-15T00:00:00Z");
  });

  it("surfaces 'error' state (so PaywallView hides Subscribe buttons)", async () => {
    serverFetch.mockRejectedValue(new UpstreamError(500, "boom"));
    const vm = await loadSubscription();
    expect(vm.state).toBe("error");
    expect(vm.active).toBe(false);
  });

  it("falls back to default plans/features when none are sent", async () => {
    serverFetch.mockResolvedValue({ active: false });
    const vm = await loadSubscription();
    expect(vm.plans.length).toBeGreaterThan(0);
    expect(vm.features.length).toBeGreaterThan(0);
  });

  // Regression: the backend's StripeService.describePlans() returns `plans` as an
  // OBJECT keyed by tier ({monthly:{…},annual:{…}}), not an array. A bare `.map`
  // over it threw `TypeError: …map is not a function`, which escaped the RSC and
  // rendered the /upgrade "Something went wrong" boundary. The loader must coerce
  // a non-array to [] and fall back to defaults instead of crashing.
  it("does not throw when `plans` arrives as an object (real backend shape)", async () => {
    serverFetch.mockResolvedValue({
      active: false,
      plans: {
        monthly: { tier: "monthly", name: "Monthly", price_cents: 1199, interval: "month" },
        annual: { tier: "annual", name: "Annual", price_cents: 11999, interval: "year" },
      },
      features: [],
    });
    const vm = await loadSubscription();
    expect(vm.state).toBe("free");
    expect(vm.plans.length).toBeGreaterThan(0); // DEFAULT_PLANS — rendered, not crashed
    expect(vm.features.length).toBeGreaterThan(0);
  });
});

describe("loadProfilePage — Plan row plumbing", () => {
  it("plumbs subState, planTier, and planExpiresAt for a Pro user", async () => {
    routeBy({
      "/auth/me": ME,
      "/auth/profile": { branch: "Army" },
      "/subscription/status": PRO_STATUS,
    });
    const vm = await loadProfilePage();
    expect(vm.isPro).toBe(true);
    expect(vm.subState).toBe("pro");
    expect(vm.planTier).toBe("annual");
    expect(vm.planExpiresAt).toBe("2027-01-15T00:00:00Z");
  });

  it("reports 'error' subState (no false 'Free') when status fails", async () => {
    routeBy({
      "/auth/me": ME,
      "/auth/profile": null,
      "/subscription/status": () => {
        throw new UpstreamError(502, "bad gateway");
      },
    });
    const vm = await loadProfilePage();
    expect(vm.subState).toBe("error");
    expect(vm.isPro).toBe(false);
    expect(vm.planTier).toBeNull();
  });
});
