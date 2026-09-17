import { describe, it, expect, vi } from "vitest";

// The WEB driver's biometric device-login methods (§B2) are inert no-op stubs —
// web has no biometric keychain (passkeys ARE the web factor-2). These lock that:
//   - isBiometricAvailable() is always false (so the shared UI never offers it);
//   - deviceLogin() resolves false (a shared launch path falls through to OTP);
//   - enrollDeviceCredential() rejects "unsupported" (never silently succeeds);
// and that NO native plugin is imported into the web bundle to make any of this
// work. We stub the Firebase machinery the module references at load, exactly like
// driver.web.passkey.test.ts.

vi.mock("@/lib/firebase/session", () => ({
  signInWithCustomTokenAndEstablish: vi.fn(),
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
vi.mock("./passkey", () => ({
  isWebAuthnSupported: () => false,
  createPasskey: vi.fn(),
  getPasskeyAssertion: vi.fn(),
}));

import { webAuthDriver } from "./driver.web";

describe("webAuthDriver biometric stubs (web has no device-login)", () => {
  it("isBiometricAvailable resolves false", async () => {
    await expect(webAuthDriver.isBiometricAvailable()).resolves.toBe(false);
  });

  it("deviceLogin resolves false (OTP fallback), never throws", async () => {
    await expect(webAuthDriver.deviceLogin()).resolves.toBe(false);
  });

  it("enrollDeviceCredential rejects unsupported (never a silent success)", async () => {
    await expect(webAuthDriver.enrollDeviceCredential()).rejects.toThrow(/unsupported/);
  });
});
