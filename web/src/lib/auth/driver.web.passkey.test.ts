import { describe, it, expect, beforeEach, vi } from "vitest";

// The WEB driver's passkey methods (auth-program-plan P1.3). We stub the Firebase
// session machinery + the browser ceremony so these tests are pure driver logic:
// which BFF routes get called, that a session is minted on assert, that
// passkeyLogin NEVER throws (falls back), and that revoke rides the step-up seam.

const signInWithCustomTokenAndEstablish = vi.fn();
vi.mock("@/lib/firebase/session", () => ({
  signInWithCustomTokenAndEstablish: (...a: unknown[]) => signInWithCustomTokenAndEstablish(...a),
  // The rest of the driver's session imports — unused here but referenced at module load.
  getRecaptcha: vi.fn(),
  resetRecaptcha: vi.fn(),
  startPhoneSignIn: vi.fn(),
  confirmPhoneSignIn: vi.fn(),
  startLinkPhone: vi.fn(),
  confirmLinkPhone: vi.fn(),
  signOut: vi.fn(),
  watchAccount: vi.fn(),
}));
vi.mock("@/lib/firebase/client", () => ({ getFirebaseAuth: () => ({ currentUser: null }) }));

const isWebAuthnSupported = vi.fn();
const createPasskey = vi.fn();
const getPasskeyAssertion = vi.fn();
vi.mock("./passkey", () => ({
  isWebAuthnSupported: () => isWebAuthnSupported(),
  createPasskey: (...a: unknown[]) => createPasskey(...a),
  getPasskeyAssertion: (...a: unknown[]) => getPasskeyAssertion(...a),
}));

import { webAuthDriver } from "./driver.web";
import {
  registerStepUpOpener,
  clearStepUpToken,
  __resetStepUpForTests,
  STEP_UP_HEADER,
} from "./step-up";

function jsonResponse(status: number, body: unknown): Response {
  return new Response(body === null ? null : JSON.stringify(body), { status });
}

beforeEach(() => {
  vi.clearAllMocks();
  isWebAuthnSupported.mockReturnValue(true);
});

describe("webAuthDriver.passkeyLogin", () => {
  it("runs the assertion and mints the session when the account has credentials", async () => {
    const fetchMock = vi
      .fn()
      // assert/options → the real Yubico `{ publicKey: {...} }` envelope WITH allowCredentials
      .mockResolvedValueOnce(
        jsonResponse(200, {
          publicKey: {
            challenge: "Y2hhbGxlbmdl",
            rpId: "afterduty.app",
            allowCredentials: [{ type: "public-key", id: "Y3JlZElk" }],
          },
        }),
      )
      // assert/verify → { custom_token }
      .mockResolvedValueOnce(jsonResponse(200, { custom_token: "CUSTOM123", credentialId: "cred" }));
    vi.stubGlobal("fetch", fetchMock);
    getPasskeyAssertion.mockResolvedValue({ id: "cred", response: {} });

    const ok = await webAuthDriver.passkeyLogin("vet@example.com");

    expect(ok).toBe(true);
    // assert/options POSTed the identifier.
    expect(fetchMock.mock.calls[0][0]).toContain("/api/auth/webauthn/assert/options");
    expect(JSON.parse(fetchMock.mock.calls[0][1].body)).toEqual({ identifier: "vet@example.com" });
    // The `{ publicKey: {...} }` envelope was UNWRAPPED before the ceremony (else
    // options.challenge is undefined and the ceremony throws a TypeError).
    expect(getPasskeyAssertion).toHaveBeenCalledWith({
      challenge: "Y2hhbGxlbmdl",
      rpId: "afterduty.app",
      allowCredentials: [{ type: "public-key", id: "Y3JlZElk" }],
    });
    // The ceremony result was POSTed to assert/verify.
    expect(fetchMock.mock.calls[1][0]).toContain("/api/auth/webauthn/assert/verify");
    // Session minted with the SAME custom-token exchange as email-code verify.
    expect(signInWithCustomTokenAndEstablish).toHaveBeenCalledWith("CUSTOM123");
  });

  it("returns false (no ceremony, no session) when the account has NO credentials (decoy)", async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(jsonResponse(200, { publicKey: { challenge: "x", allowCredentials: [] } }));
    vi.stubGlobal("fetch", fetchMock);

    const ok = await webAuthDriver.passkeyLogin("nobody@example.com");

    expect(ok).toBe(false);
    expect(getPasskeyAssertion).not.toHaveBeenCalled();
    expect(signInWithCustomTokenAndEstablish).not.toHaveBeenCalled();
  });

  it("returns false when WebAuthn is unsupported — never throws", async () => {
    isWebAuthnSupported.mockReturnValue(false);
    const fetchMock = vi.fn();
    vi.stubGlobal("fetch", fetchMock);

    await expect(webAuthDriver.passkeyLogin("vet@example.com")).resolves.toBe(false);
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it("returns false when the user cancels the OS prompt — falls back to OTP", async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(
        jsonResponse(200, {
          publicKey: { challenge: "x", allowCredentials: [{ type: "public-key", id: "a" }] },
        }),
      );
    vi.stubGlobal("fetch", fetchMock);
    getPasskeyAssertion.mockRejectedValue(new Error("webauthn-no-credential"));

    await expect(webAuthDriver.passkeyLogin("vet@example.com")).resolves.toBe(false);
    expect(signInWithCustomTokenAndEstablish).not.toHaveBeenCalled();
  });

  it("returns false when assert/verify fails (never throws to the login page)", async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(
        jsonResponse(200, {
          publicKey: { challenge: "x", allowCredentials: [{ type: "public-key", id: "a" }] },
        }),
      )
      .mockResolvedValueOnce(jsonResponse(400, { detail: "bad assertion" }));
    vi.stubGlobal("fetch", fetchMock);
    getPasskeyAssertion.mockResolvedValue({ id: "a", response: {} });

    await expect(webAuthDriver.passkeyLogin("vet@example.com")).resolves.toBe(false);
    expect(signInWithCustomTokenAndEstablish).not.toHaveBeenCalled();
  });
});

describe("webAuthDriver.enrollPasskey", () => {
  it("posts the attestation from the create ceremony to register/verify", async () => {
    const fetchMock = vi
      .fn()
      // register/options → the real Yubico `{ publicKey: {...} }` envelope
      .mockResolvedValueOnce(jsonResponse(200, { publicKey: { challenge: "aGVsbG8", rp: {}, user: {} } }))
      .mockResolvedValueOnce(jsonResponse(200, { credentialId: "new", nickname: "My laptop" })); // verify
    vi.stubGlobal("fetch", fetchMock);
    createPasskey.mockResolvedValue({ id: "new", response: { attestationObject: "att" } });

    await webAuthDriver.enrollPasskey("My laptop");

    expect(fetchMock.mock.calls[0][0]).toContain("/api/auth/webauthn/register/options");
    // The envelope was UNWRAPPED before the ceremony (regression guard for the
    // TypeError that killed enrollment before register/verify was ever reached).
    expect(createPasskey).toHaveBeenCalledWith({ challenge: "aGVsbG8", rp: {}, user: {} });
    expect(fetchMock.mock.calls[1][0]).toContain("/api/auth/webauthn/register/verify");
    const verifyBody = JSON.parse(fetchMock.mock.calls[1][1].body);
    expect(verifyBody.nickname).toBe("My laptop");
    expect(verifyBody.credential).toEqual({ id: "new", response: { attestationObject: "att" } });
  });

  it("rejects when the ceremony is cancelled (enroll UI shows retry)", async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(jsonResponse(200, { publicKey: { challenge: "aGVsbG8", rp: {}, user: {} } }));
    vi.stubGlobal("fetch", fetchMock);
    createPasskey.mockRejectedValue(new Error("webauthn-no-credential"));

    await expect(webAuthDriver.enrollPasskey()).rejects.toThrow();
  });
});

describe("webAuthDriver credential management", () => {
  it("listPasskeys returns the credentials array", async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      jsonResponse(200, {
        credentials: [{ id: "a", nickname: "Phone", createdAt: "2026-07-04", lastUsedAt: null, deviceHint: "This device" }],
      }),
    );
    vi.stubGlobal("fetch", fetchMock);

    const list = await webAuthDriver.listPasskeys();
    expect(list).toHaveLength(1);
    expect(list[0].nickname).toBe("Phone");
    expect(fetchMock.mock.calls[0][0]).toContain("/api/auth/webauthn/credentials");
  });

  it("renamePasskey PATCHes the nickname", async () => {
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse(200, { ok: true }));
    vi.stubGlobal("fetch", fetchMock);

    await webAuthDriver.renamePasskey("cred-1", "New name");
    expect(fetchMock.mock.calls[0][0]).toContain("/api/auth/webauthn/credentials/cred-1");
    expect(fetchMock.mock.calls[0][1].method).toBe("PATCH");
    expect(JSON.parse(fetchMock.mock.calls[0][1].body)).toEqual({ nickname: "New name" });
  });

  it("revokePasskey DELETEs and resolves on 204 (no step-up needed)", async () => {
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse(204, null));
    vi.stubGlobal("fetch", fetchMock);

    await expect(webAuthDriver.revokePasskey("cred-1")).resolves.toBeUndefined();
    expect(fetchMock.mock.calls[0][1].method).toBe("DELETE");
  });

  it("revokePasskey surfaces an error when the DELETE stays non-2xx", async () => {
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse(500, { error: "upstream" }));
    vi.stubGlobal("fetch", fetchMock);

    await expect(webAuthDriver.revokePasskey("cred-1")).rejects.toThrow();
  });

  it("revokePasskey runs the STEP-UP ceremony on a 403 and retries with X-Step-Up", async () => {
    __resetStepUpForTests();
    clearStepUpToken();
    // A ceremony opener that mints a token (stands in for the Confirm-it's-you modal).
    const unregister = registerStepUpOpener(async () => ({ token: "STEP-TOK", expiresInSec: 300 }));

    const fetchMock = vi
      .fn()
      // First DELETE → step-up challenge (removing a factor is guarded).
      .mockResolvedValueOnce(jsonResponse(403, { code: "step_up_required", acceptedFactors: ["otp"] }))
      // Retry with the token → 204.
      .mockResolvedValueOnce(jsonResponse(204, null));
    vi.stubGlobal("fetch", fetchMock);

    await expect(webAuthDriver.revokePasskey("cred-1")).resolves.toBeUndefined();

    // Two DELETEs: the guarded first, then the retry carrying the step-up header.
    expect(fetchMock).toHaveBeenCalledTimes(2);
    expect(fetchMock.mock.calls[0][1].headers[STEP_UP_HEADER]).toBeUndefined();
    expect(fetchMock.mock.calls[1][1].headers[STEP_UP_HEADER]).toBe("STEP-TOK");
    unregister();
  });
});
