// Native AuthDriver contract tests (capacitor-ios-spec §B.1/§B.2/§B.4, OTP
// port). These lock the load-bearing behaviors the scoping pass flagged:
//   1. email-code calls go to Spring DIRECTLY (the BFF routes 404 in the
//      static export), with Bearer on attach only;
//   2. the custom token signs in via the plugin's NATIVE path (keychain →
//      DirectApiClient Bearer source), never the JS SDK;
//   3. phone verification rides the plugin's phoneCodeSent event (silent APNs)
//      and confirm reports isNewUser for the dual-verify branch;
//   4. getUid is synchronous off the plugin-fed cache (RevenueCat appUserID).

import { beforeEach, describe, expect, it, vi } from "vitest";

const listeners = vi.hoisted(() => ({}) as Record<string, (event: never) => void>);

vi.mock("@capacitor-firebase/authentication", () => ({
  FirebaseAuthentication: {
    addListener: vi.fn((name: string, cb: (event: never) => void) => {
      listeners[name] = cb;
      return Promise.resolve({ remove: vi.fn() });
    }),
    getCurrentUser: vi.fn(() => Promise.resolve({ user: null })),
    getIdToken: vi.fn(() => Promise.resolve({ token: "keychain-token" })),
    signInWithCustomToken: vi.fn(() =>
      Promise.resolve({ user: { uid: "uid-1", email: "vet@example.com", phoneNumber: null } }),
    ),
    signInWithPhoneNumber: vi.fn(() => Promise.resolve()),
    linkWithPhoneNumber: vi.fn(() => Promise.resolve()),
    confirmVerificationCode: vi.fn(() =>
      Promise.resolve({
        user: { uid: "uid-2", email: null, phoneNumber: "+15551230000" },
        additionalUserInfo: { isNewUser: true },
      }),
    ),
    signOut: vi.fn(() => Promise.resolve()),
    reload: vi.fn(() => Promise.resolve()),
  },
}));

// Biometric device-secret facade (§B2) — mocked so the driver test asserts the
// ORCHESTRATION (enroll → /enroll → store; deviceLogin → fresh read → /exchange →
// signInWithCustomToken; fallbacks). The keychain/plugin behavior itself is
// covered by biometric.test.ts.
const bio = vi.hoisted(() => ({
  isStrongBiometricAvailable: vi.fn(),
  storeDeviceSecret: vi.fn(),
  readDeviceSecret: vi.fn(),
  hasDeviceSecret: vi.fn(),
  clearDeviceSecret: vi.fn(),
}));
vi.mock("@/lib/native/biometric", () => bio);

import { FirebaseAuthentication } from "@capacitor-firebase/authentication";
import { nativeAuthDriver } from "./driver.native";
import { EmailCodeError } from "./email-code-client";

const plugin = vi.mocked(FirebaseAuthentication);

function mockFetchOnce(status: number, body: unknown): void {
  vi.stubGlobal(
    "fetch",
    vi.fn(() =>
      Promise.resolve(
        new Response(body == null ? null : JSON.stringify(body), { status }),
      ),
    ),
  );
}

function lastFetch(): { url: string; init: RequestInit } {
  const calls = vi.mocked(fetch).mock.calls;
  const [url, init] = calls[calls.length - 1];
  return { url: String(url), init: init as RequestInit };
}

beforeEach(() => {
  vi.clearAllMocks();
  // Biometric facade defaults: a device with strong biometrics and a stored
  // secret. Individual cases override.
  bio.isStrongBiometricAvailable.mockResolvedValue(true);
  bio.storeDeviceSecret.mockResolvedValue(undefined);
  bio.clearDeviceSecret.mockResolvedValue(undefined);
  bio.hasDeviceSecret.mockResolvedValue(true);
  bio.readDeviceSecret.mockResolvedValue({ deviceSecret: "dev-secret", deviceCredentialId: "7" });
});

describe("nativeAuthDriver email codes (Spring-direct — §A.2)", () => {
  it("requestEmailCode POSTs to Spring's request endpoint with no Authorization", async () => {
    mockFetchOnce(200, { ok: true });
    await nativeAuthDriver.requestEmailCode("vet@example.com", "signin");
    const { url, init } = lastFetch();
    expect(url).toMatch(/\/auth\/email-code\/request$/);
    expect(url).not.toContain("/api/auth/email-code"); // never the BFF twin
    expect((init.headers as Record<string, string>).Authorization).toBeUndefined();
    expect(JSON.parse(String(init.body))).toEqual({ email: "vet@example.com", purpose: "signin" });
  });

  it("verifyEmailCode exchanges the custom token via the plugin's NATIVE path", async () => {
    mockFetchOnce(200, { custom_token: "ct-123", isNewUser: true, hasPhone: false });
    const result = await nativeAuthDriver.verifyEmailCode("vet@example.com", "123456");
    expect(lastFetch().url).toMatch(/\/auth\/email-code\/verify$/);
    expect(plugin.signInWithCustomToken).toHaveBeenCalledWith({ token: "ct-123" });
    expect(result).toEqual({ isNewUser: true, hasPhone: false });
    // The plugin-fed cache now answers getUid synchronously (RC appUserID).
    expect(nativeAuthDriver.getUid()).toBe("uid-1");
  });

  it("requestEmailCode for purpose=attach carries the keychain token as Bearer", async () => {
    // The backend fail-safes an UNAUTHED purpose=attach request down to SIGNIN,
    // and /attach only redeems ATTACH codes — so the dual-verify second step
    // must request its code authed or the emailed code can never be verified.
    mockFetchOnce(200, { ok: true });
    await nativeAuthDriver.requestEmailCode("vet@example.com", "attach");
    const { url, init } = lastFetch();
    expect(url).toMatch(/\/auth\/email-code\/request$/);
    expect((init.headers as Record<string, string>).Authorization).toBe("Bearer keychain-token");
    expect(JSON.parse(String(init.body))).toEqual({ email: "vet@example.com", purpose: "attach" });
  });

  it("attachEmailCode sends the keychain token as an explicit Bearer", async () => {
    mockFetchOnce(200, { status: "attached", email: "vet@example.com", emailVerified: true });
    await nativeAuthDriver.attachEmailCode("vet@example.com", "654321");
    const { url, init } = lastFetch();
    expect(url).toMatch(/\/auth\/email-code\/attach$/);
    expect((init.headers as Record<string, string>).Authorization).toBe("Bearer keychain-token");
    // Server-side attach → the native user is re-read for display freshness.
    expect(plugin.reload).toHaveBeenCalled();
  });

  it("surfaces the backend's user-facing {detail} as EmailCodeError", async () => {
    mockFetchOnce(429, { detail: "Too many codes requested. Try again later." });
    await expect(nativeAuthDriver.requestEmailCode("vet@example.com", "signin")).rejects.toThrow(
      EmailCodeError,
    );
  });
});

describe("nativeAuthDriver phone OTP (silent APNs — §B.4)", () => {
  async function fireCodeSent(verificationId: string): Promise<void> {
    // Let startPhoneVerification register its phoneCodeSent listener first.
    await Promise.resolve();
    await Promise.resolve();
    listeners.phoneCodeSent?.({ verificationId } as never);
  }

  it("startPhone resolves via the phoneCodeSent event; confirm reports isNewUser", async () => {
    const pending = nativeAuthDriver.startPhone("+15551230000");
    await fireCodeSent("vid-1");
    const confirmation = await pending;
    expect(plugin.signInWithPhoneNumber).toHaveBeenCalledWith({ phoneNumber: "+15551230000" });

    const { isNewUser, hasEmail } = await confirmation.confirm("111222");
    expect(plugin.confirmVerificationCode).toHaveBeenCalledWith({
      verificationId: "vid-1",
      verificationCode: "111222",
    });
    expect(isNewUser).toBe(true);
    // The mock user carries no email — the login flow owes the email channel.
    expect(hasEmail).toBe(false);
    expect(nativeAuthDriver.getUid()).toBe("uid-2");
  });

  it("startLinkPhone links to the CURRENT uid (dual-verify second channel)", async () => {
    const pending = nativeAuthDriver.startLinkPhone("+15559998888");
    await fireCodeSent("vid-2");
    await pending;
    expect(plugin.linkWithPhoneNumber).toHaveBeenCalledWith({ phoneNumber: "+15559998888" });
    expect(plugin.signInWithPhoneNumber).not.toHaveBeenCalled();
  });

  it("rejects when the plugin cannot start verification", async () => {
    plugin.signInWithPhoneNumber.mockRejectedValueOnce(new Error("auth/invalid-phone-number"));
    await expect(nativeAuthDriver.startPhone("+1000")).rejects.toThrow("auth/invalid-phone-number");
  });
});

describe("nativeAuthDriver session", () => {
  it("getToken reads the keychain ID token with the requested refresh mode", async () => {
    await expect(nativeAuthDriver.getToken({ forceRefresh: true })).resolves.toBe("keychain-token");
    expect(plugin.getIdToken).toHaveBeenCalledWith({ forceRefresh: true });
  });

  it("signOut clears the plugin and the uid cache", async () => {
    mockFetchOnce(200, { custom_token: "ct-1", isNewUser: false, hasPhone: true });
    await nativeAuthDriver.verifyEmailCode("vet@example.com", "123456");
    expect(nativeAuthDriver.getUid()).toBe("uid-1");

    await nativeAuthDriver.signOut();
    expect(plugin.signOut).toHaveBeenCalled();
    expect(nativeAuthDriver.getUid()).toBeNull();
  });

  it("watchAccount reflects the plugin-fed cache (no JS-SDK mirror)", async () => {
    mockFetchOnce(200, { custom_token: "ct-1", isNewUser: false, hasPhone: true });
    await nativeAuthDriver.verifyEmailCode("vet@example.com", "123456");

    const seen: unknown[] = [];
    const unsub = nativeAuthDriver.watchAccount((d) => seen.push(d));
    expect(seen[0]).toEqual({ email: "vet@example.com", phoneNumber: null, mfaFactors: [] });
    unsub();
  });
});

describe("nativeAuthDriver biometric device-login (§B2)", () => {
  it("isBiometricAvailable delegates to the strong-biometry check", async () => {
    bio.isStrongBiometricAvailable.mockResolvedValueOnce(true);
    await expect(nativeAuthDriver.isBiometricAvailable()).resolves.toBe(true);
    bio.isStrongBiometricAvailable.mockResolvedValueOnce(false);
    await expect(nativeAuthDriver.isBiometricAvailable()).resolves.toBe(false);
  });

  it("enrollDeviceCredential Bearer-POSTs /enroll then stores the returned secret", async () => {
    mockFetchOnce(200, { deviceSecret: "s3cret", deviceCredentialId: "42" });
    await nativeAuthDriver.enrollDeviceCredential("This iPhone");

    const { url, init } = lastFetch();
    // Authed enroll → Spring directly with the keychain Bearer.
    expect(url).toMatch(/\/auth\/device\/enroll$/);
    expect((init.headers as Record<string, string>).Authorization).toBe("Bearer keychain-token");
    expect(JSON.parse(String(init.body))).toEqual({ deviceName: "This iPhone", platform: "ios" });
    // The once-returned secret is handed to the biometric-gated store.
    expect(bio.storeDeviceSecret).toHaveBeenCalledWith({
      deviceSecret: "s3cret",
      deviceCredentialId: "42",
    });
  });

  it("enrollDeviceCredential propagates a store failure (retry-able, nothing signed in)", async () => {
    mockFetchOnce(200, { deviceSecret: "s", deviceCredentialId: "1" });
    bio.storeDeviceSecret.mockRejectedValueOnce(new Error("user backed out of Face ID"));
    await expect(nativeAuthDriver.enrollDeviceCredential()).rejects.toThrow();
  });

  it("deviceLogin: fresh read → PUBLIC /exchange → native signInWithCustomToken", async () => {
    bio.hasDeviceSecret.mockResolvedValueOnce(true);
    bio.readDeviceSecret.mockResolvedValueOnce({ deviceSecret: "dev-secret", deviceCredentialId: "7" });
    mockFetchOnce(200, { custom_token: "ct-device" });

    await expect(nativeAuthDriver.deviceLogin()).resolves.toBe(true);

    // A FRESH biometric prompt gated the read (reason string surfaced to the sheet).
    expect(bio.readDeviceSecret).toHaveBeenCalledWith("Sign in to After Duty with Face ID.");
    const { url, init } = lastFetch();
    // PUBLIC exchange — no Authorization header (pre-session).
    expect(url).toMatch(/\/auth\/device\/exchange$/);
    expect((init.headers as Record<string, string>).Authorization).toBeUndefined();
    expect(JSON.parse(String(init.body))).toEqual({ deviceCredentialId: "7", deviceSecret: "dev-secret" });
    // Same NATIVE custom-token sign-in as the email path (keychain populated).
    expect(plugin.signInWithCustomToken).toHaveBeenCalledWith({ token: "ct-device" });
    expect(nativeAuthDriver.getUid()).toBe("uid-1");
  });

  it("deviceLogin: no stored credential → false, no prompt, no exchange (OTP fallback)", async () => {
    bio.hasDeviceSecret.mockResolvedValueOnce(false);
    vi.stubGlobal("fetch", vi.fn());

    await expect(nativeAuthDriver.deviceLogin()).resolves.toBe(false);
    expect(bio.readDeviceSecret).not.toHaveBeenCalled();
    expect(fetch).not.toHaveBeenCalled();
    expect(plugin.signInWithCustomToken).not.toHaveBeenCalled();
  });

  it("deviceLogin: cancelled/biometryChange read → false, never throws (OTP fallback)", async () => {
    bio.hasDeviceSecret.mockResolvedValueOnce(true).mockResolvedValueOnce(false); // gone after invalidation
    bio.readDeviceSecret.mockResolvedValueOnce(null); // cancel / biometryChange
    vi.stubGlobal("fetch", vi.fn());

    await expect(nativeAuthDriver.deviceLogin()).resolves.toBe(false);
    // Invalidated item is dropped so we don't re-prompt a dead credential.
    expect(bio.clearDeviceSecret).toHaveBeenCalled();
    expect(fetch).not.toHaveBeenCalled();
    expect(plugin.signInWithCustomToken).not.toHaveBeenCalled();
  });

  it("deviceLogin: revoked credential (/exchange 4xx) → false + clears the dead secret", async () => {
    bio.hasDeviceSecret.mockResolvedValueOnce(true);
    bio.readDeviceSecret.mockResolvedValueOnce({ deviceSecret: "dev-secret", deviceCredentialId: "7" });
    mockFetchOnce(401, { detail: "Device login failed." });

    await expect(nativeAuthDriver.deviceLogin()).resolves.toBe(false);
    // The secret is useless now — drop it so launch stops prompting for it.
    expect(bio.clearDeviceSecret).toHaveBeenCalled();
    expect(plugin.signInWithCustomToken).not.toHaveBeenCalled();
  });

  it("signOut also clears the device secret (cross-account hygiene)", async () => {
    await nativeAuthDriver.signOut();
    expect(plugin.signOut).toHaveBeenCalled();
    expect(bio.clearDeviceSecret).toHaveBeenCalled();
  });
});
