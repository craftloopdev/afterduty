import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import {
  RecoveryError,
  RECOVERY_NEEDS_SUPPORT,
  startRecovery,
  verifyRecovery,
} from "./recovery-client";

// Client transport for dual-channel factor-2 recovery (auth program P1.5). Asserts
// the two calls' bodies + the {detail}/{code} error mapping — crucially the 409
// {code:"recovery_needs_support"} surfaces as `needsSupport` so the page can route
// to the support-hold path, and a generic 400 surfaces as {detail}.

describe("recovery-client", () => {
  beforeEach(() => vi.unstubAllGlobals());
  afterEach(() => vi.restoreAllMocks());

  describe("startRecovery", () => {
    it("POSTs the identifier and resolves on the anti-enumeration 200", async () => {
      const fetchMock = vi
        .fn()
        .mockResolvedValue(new Response(JSON.stringify({ ok: true }), { status: 200 }));
      vi.stubGlobal("fetch", fetchMock);

      await expect(startRecovery("vet@example.com")).resolves.toBeUndefined();

      const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit];
      expect(url).toBe("/api/auth/recovery/start");
      expect(init.method).toBe("POST");
      expect(JSON.parse(String(init.body))).toEqual({ identifier: "vet@example.com" });
    });
  });

  describe("verifyRecovery", () => {
    it("POSTs BOTH proofs and returns the custom token + revoked factors", async () => {
      const fetchMock = vi.fn().mockResolvedValue(
        new Response(
          JSON.stringify({ custom_token: "ct-abc", revokedFactors: ["passkey", "biometric"] }),
          { status: 200 },
        ),
      );
      vi.stubGlobal("fetch", fetchMock);

      const out = await verifyRecovery("vet@example.com", "246810", "phone-tok");

      expect(out.custom_token).toBe("ct-abc");
      expect(out.revokedFactors).toEqual(["passkey", "biometric"]);
      const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit];
      expect(url).toBe("/api/auth/recovery/verify");
      expect(JSON.parse(String(init.body))).toEqual({
        email: "vet@example.com",
        emailCode: "246810",
        phoneIdToken: "phone-tok",
      });
    });

    it("maps a generic 400 to a RecoveryError with the backend {detail} (not needsSupport)", async () => {
      vi.stubGlobal(
        "fetch",
        vi.fn().mockResolvedValue(
          new Response(JSON.stringify({ detail: "Recovery failed." }), { status: 400 }),
        ),
      );

      const err = await verifyRecovery("v@e.com", "0", "t").catch((e) => e);
      expect(err).toBeInstanceOf(RecoveryError);
      expect((err as RecoveryError).status).toBe(400);
      expect((err as RecoveryError).detail).toBe("Recovery failed.");
      expect((err as RecoveryError).needsSupport).toBe(false);
    });

    it("maps the 409 {code:recovery_needs_support} to needsSupport=true", async () => {
      vi.stubGlobal(
        "fetch",
        vi.fn().mockResolvedValue(
          new Response(JSON.stringify({ code: RECOVERY_NEEDS_SUPPORT }), { status: 409 }),
        ),
      );

      const err = await verifyRecovery("v@e.com", "246810", "t").catch((e) => e);
      expect(err).toBeInstanceOf(RecoveryError);
      expect((err as RecoveryError).status).toBe(409);
      expect((err as RecoveryError).needsSupport).toBe(true);
    });
  });
});
