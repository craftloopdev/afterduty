import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

// Biometric device-secret facade tests (§B2 / auth-program-plan P1.4). The plugin
// is mocked; these lock the SECURITY-load-bearing behaviors:
//   1. WEB build never imports the plugin — every method is an inert no-op/false.
//   2. Native store uses accessControl BIOMETRY_CURRENT_SET (=1) so the secret is
//      hardware-gated (not a bypassable JS gate), and clears any stale item first.
//   3. Native read goes through getSecureCredentials (FRESH prompt at the keychain
//      layer), NOT a bare verifyIdentity + getCredentials.
//   4. Availability requires STRONG biometry; every error path collapses to a safe
//      false/null (never throws) so the caller always has an OTP fallback.
//
// `platform.ts` reads NEXT_PUBLIC_NATIVE at module-eval, and `biometric.ts` gates
// its require() on that constant — so each case stubs the env, resets the module
// registry, and imports fresh (same pattern as resume.test.ts).

const NativeBiometric = vi.hoisted(() => ({
  isAvailable: vi.fn(),
  setCredentials: vi.fn(),
  getSecureCredentials: vi.fn(),
  getCredentials: vi.fn(),
  isCredentialsSaved: vi.fn(),
  deleteCredentials: vi.fn(),
  verifyIdentity: vi.fn(),
}));

let pluginImported = false;

vi.mock("@capgo/capacitor-native-biometric", () => {
  pluginImported = true;
  return { NativeBiometric, AccessControl: { NONE: 0, BIOMETRY_CURRENT_SET: 1, BIOMETRY_ANY: 2 } };
});

const SERVER = "com.afterduty.app.deviceLogin";

async function loadBiometric() {
  // Import platform FIRST so `NATIVE` is (re)evaluated against the freshly-stubbed
  // env before biometric.ts closes over it (biometric statically imports platform;
  // forcing the order avoids a stale NATIVE from a prior describe's module cache).
  await import("@/lib/platform");
  return import("./biometric");
}

beforeEach(() => {
  // Sensible native defaults; individual cases override.
  NativeBiometric.isAvailable.mockResolvedValue({
    isAvailable: true,
    strongBiometryIsAvailable: true,
  });
  NativeBiometric.setCredentials.mockResolvedValue(undefined);
  NativeBiometric.deleteCredentials.mockResolvedValue(undefined);
  NativeBiometric.isCredentialsSaved.mockResolvedValue({ isSaved: true });
});

afterEach(() => {
  vi.resetModules();
  vi.unstubAllEnvs();
  vi.clearAllMocks();
  pluginImported = false;
});

describe("biometric facade — web build (no-op, plugin never imported)", () => {
  beforeEach(() => vi.stubEnv("NEXT_PUBLIC_NATIVE", ""));

  it("isStrongBiometricAvailable resolves false and never imports the plugin", async () => {
    const { isStrongBiometricAvailable } = await loadBiometric();
    await expect(isStrongBiometricAvailable()).resolves.toBe(false);
    expect(pluginImported).toBe(false);
    expect(NativeBiometric.isAvailable).not.toHaveBeenCalled();
  });

  it("readDeviceSecret resolves null; hasDeviceSecret false; clear/store no-op-safe", async () => {
    const { readDeviceSecret, hasDeviceSecret, clearDeviceSecret } = await loadBiometric();
    await expect(readDeviceSecret("x")).resolves.toBeNull();
    await expect(hasDeviceSecret()).resolves.toBe(false);
    await expect(clearDeviceSecret()).resolves.toBeUndefined();
    expect(pluginImported).toBe(false);
  });

  it("storeDeviceSecret throws unsupported on web (never silently succeeds)", async () => {
    const { storeDeviceSecret } = await loadBiometric();
    await expect(
      storeDeviceSecret({ deviceSecret: "s", deviceCredentialId: "1" }),
    ).rejects.toThrow(/unsupported/);
    expect(pluginImported).toBe(false);
  });
});

describe("biometric facade — native build", () => {
  beforeEach(() => vi.stubEnv("NEXT_PUBLIC_NATIVE", "1"));

  it("isStrongBiometricAvailable requires isAvailable AND strong biometry", async () => {
    const { isStrongBiometricAvailable } = await loadBiometric();
    // Both true → available.
    await expect(isStrongBiometricAvailable()).resolves.toBe(true);
    // isAvailable checks with useFallback:false (a passcode must NOT count).
    expect(NativeBiometric.isAvailable).toHaveBeenCalledWith({ useFallback: false });
  });

  it("isStrongBiometricAvailable false when only weak biometry is present", async () => {
    NativeBiometric.isAvailable.mockResolvedValueOnce({
      isAvailable: true,
      strongBiometryIsAvailable: false,
    });
    const { isStrongBiometricAvailable } = await loadBiometric();
    await expect(isStrongBiometricAvailable()).resolves.toBe(false);
  });

  it("isStrongBiometricAvailable false (never throws) when the plugin errors", async () => {
    NativeBiometric.isAvailable.mockRejectedValueOnce(new Error("no hardware"));
    const { isStrongBiometricAvailable } = await loadBiometric();
    await expect(isStrongBiometricAvailable()).resolves.toBe(false);
  });

  it("storeDeviceSecret clears stale item then stores behind BIOMETRY_CURRENT_SET", async () => {
    const { storeDeviceSecret } = await loadBiometric();
    await storeDeviceSecret({ deviceSecret: "sekret", deviceCredentialId: "42" });

    // Cleared first (re-enroll must overwrite), THEN stored.
    expect(NativeBiometric.deleteCredentials).toHaveBeenCalledWith({ server: SERVER });
    expect(NativeBiometric.setCredentials).toHaveBeenCalledWith({
      server: SERVER,
      username: "42", // credential id
      password: "sekret", // the device secret
      accessControl: 1, // AccessControl.BIOMETRY_CURRENT_SET — hardware-gated
    });
    // deleteCredentials ran before setCredentials.
    const clearOrder = NativeBiometric.deleteCredentials.mock.invocationCallOrder[0];
    const storeOrder = NativeBiometric.setCredentials.mock.invocationCallOrder[0];
    expect(clearOrder).toBeLessThan(storeOrder);
  });

  it("storeDeviceSecret propagates a keychain failure (nothing left half-stored)", async () => {
    NativeBiometric.setCredentials.mockRejectedValueOnce(new Error("user backed out"));
    const { storeDeviceSecret } = await loadBiometric();
    await expect(
      storeDeviceSecret({ deviceSecret: "s", deviceCredentialId: "1" }),
    ).rejects.toThrow();
  });

  it("readDeviceSecret uses getSecureCredentials (FRESH prompt), never bare getCredentials", async () => {
    NativeBiometric.getSecureCredentials.mockResolvedValueOnce({ username: "42", password: "sekret" });
    const { readDeviceSecret } = await loadBiometric();

    const bundle = await readDeviceSecret("Sign in to After Duty with Face ID.");
    expect(bundle).toEqual({ deviceSecret: "sekret", deviceCredentialId: "42" });
    // The read forces a fresh biometric at the keychain layer.
    expect(NativeBiometric.getSecureCredentials).toHaveBeenCalledWith({
      server: SERVER,
      reason: "Sign in to After Duty with Face ID.",
    });
    // NEVER the unprotected read (that path is bypassable on a rooted device).
    expect(NativeBiometric.getCredentials).not.toHaveBeenCalled();
  });

  it("readDeviceSecret returns null (never throws) on a cancelled/invalidated prompt", async () => {
    NativeBiometric.getSecureCredentials.mockRejectedValueOnce(new Error("USER_CANCEL"));
    const { readDeviceSecret } = await loadBiometric();
    await expect(readDeviceSecret("x")).resolves.toBeNull();
  });

  it("hasDeviceSecret reflects isCredentialsSaved and never throws", async () => {
    const { hasDeviceSecret } = await loadBiometric();
    await expect(hasDeviceSecret()).resolves.toBe(true);

    NativeBiometric.isCredentialsSaved.mockRejectedValueOnce(new Error("boom"));
    await expect(hasDeviceSecret()).resolves.toBe(false);
  });

  it("clearDeviceSecret deletes the item and swallows a missing-item error", async () => {
    const { clearDeviceSecret } = await loadBiometric();
    await clearDeviceSecret();
    expect(NativeBiometric.deleteCredentials).toHaveBeenCalledWith({ server: SERVER });

    NativeBiometric.deleteCredentials.mockRejectedValueOnce(new Error("no item"));
    await expect(clearDeviceSecret()).resolves.toBeUndefined();
  });
});
