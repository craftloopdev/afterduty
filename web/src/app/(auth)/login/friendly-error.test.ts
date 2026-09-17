import { describe, it, expect } from "vitest";
import { friendlyError, MFA_MESSAGE, GENERIC_ERROR } from "./friendly-error";

describe("friendlyError", () => {
  it("gives MFA-enrolled veterans an honest, actionable message", () => {
    const msg = friendlyError({ code: "auth/multi-factor-auth-required" });
    expect(msg).toBe(MFA_MESSAGE);
    expect(msg).toMatch(/two-step verification/i);
    // Points at the path we KNOW works (the mobile app)…
    expect(msg).toMatch(/on your phone/i);
    // …and a REACHABLE support fallback (P2-4: naming the real mailbox — the
    // paywall hardship note's address — instead of promising "contact support"
    // with no channel anywhere), without promising a specific web sign-in
    // method as an MFA escape hatch (phone may itself be the enrolled second
    // factor).
    expect(msg).toMatch(/support@afterduty\.app/);
    expect(msg).not.toMatch(/contact support/i);
  });

  it("maps known Firebase codes to plain copy", () => {
    expect(friendlyError({ code: "auth/popup-closed-by-user" })).toBe("Sign-in was cancelled.");
    expect(friendlyError({ code: "auth/popup-blocked" })).toMatch(/blocked the sign-in window/i);
    expect(friendlyError({ code: "auth/network-request-failed" })).toMatch(/network error/i);
    expect(friendlyError({ code: "auth/invalid-verification-code" })).toMatch(/didn't match/i);
    expect(friendlyError({ code: "auth/too-many-requests" })).toMatch(/too many attempts/i);
  });

  it("never leaks a raw Firebase error.message in the fallback", () => {
    const leaky = Object.assign(new Error("Firebase: internal error (auth/internal-error)."), {
      code: "auth/internal-error",
    });
    expect(friendlyError(leaky)).toBe(GENERIC_ERROR);

    // Unknown shape (no code) also falls back generically, not to e.message.
    expect(friendlyError(new Error("boom secret stack"))).toBe(GENERIC_ERROR);
    expect(friendlyError("just a string")).toBe(GENERIC_ERROR);
    expect(friendlyError(null)).toBe(GENERIC_ERROR);
  });
});
