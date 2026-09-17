import { describe, it, expect } from "vitest";
import { readCheckoutOutcome, readHomeCheckoutOutcome } from "./checkout-return";

describe("readCheckoutOutcome — canonical /upgrade?checkout=…", () => {
  it("reads the explicit success/cancelled signal", () => {
    expect(readCheckoutOutcome({ checkout: "success" })).toBe("success");
    expect(readCheckoutOutcome({ checkout: "cancelled" })).toBe("cancelled");
  });

  it("treats a bare session_id as success (default success_url appends it)", () => {
    expect(readCheckoutOutcome({ session_id: "cs_test_123" })).toBe("success");
  });

  it("returns null when no checkout param is present", () => {
    expect(readCheckoutOutcome({})).toBeNull();
    expect(readCheckoutOutcome({ foo: "bar" })).toBeNull();
  });

  it("ignores unknown checkout values", () => {
    expect(readCheckoutOutcome({ checkout: "weird" })).toBeNull();
  });

  it("handles array-valued params (Next can pass string[])", () => {
    expect(readCheckoutOutcome({ checkout: ["success"] })).toBe("success");
    expect(readCheckoutOutcome({ session_id: ["cs_1", "cs_2"] })).toBe("success");
  });
});

describe("readHomeCheckoutOutcome — deployed default lands on HOME", () => {
  // The deployed Stripe success_url defaults to https://app.afterduty.app/?upgraded=true
  // (+ &session_id=…) and cancel_url to …/?upgrade_cancelled=true — both the
  // HOME route. The home page reads these and redirects to /upgrade?checkout=….
  it("maps ?upgraded=true to success", () => {
    expect(readHomeCheckoutOutcome({ upgraded: "true" })).toBe("success");
  });

  it("maps an appended session_id to success even without upgraded=true", () => {
    expect(readHomeCheckoutOutcome({ session_id: "cs_test_123" })).toBe("success");
  });

  it("maps ?upgrade_cancelled=true to cancelled", () => {
    expect(readHomeCheckoutOutcome({ upgrade_cancelled: "true" })).toBe("cancelled");
  });

  it("returns null for an ordinary home visit (no redirect loop)", () => {
    expect(readHomeCheckoutOutcome({})).toBeNull();
    expect(readHomeCheckoutOutcome({ tab: "plan" })).toBeNull();
    // A canonical /upgrade-style param does NOT trigger a home redirect.
    expect(readHomeCheckoutOutcome({ checkout: "success" })).toBeNull();
  });

  it("does not treat a falsey upgraded value as success", () => {
    expect(readHomeCheckoutOutcome({ upgraded: "false" })).toBeNull();
    expect(readHomeCheckoutOutcome({ upgrade_cancelled: "false" })).toBeNull();
  });
});
