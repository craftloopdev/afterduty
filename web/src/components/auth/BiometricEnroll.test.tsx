import { describe, it, expect, beforeEach, vi } from "vitest";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";

// Post-OTP biometric enroll card (§B2 / P1.4) — the native "faster next time"
// offer. These lock the NON-BLOCKING contract: Enable enrolls then onDone; Not now
// onDone WITHOUT enrolling; a failure surfaces a retry and NEVER calls onDone (the
// caller only navigates on onDone, so a failed enroll must not strand a signed-in
// user OR silently proceed); and shouldOfferBiometricEnroll gates on capability +
// the once-per-device flag.

const authDriver = vi.hoisted(() => ({
  isBiometricAvailable: vi.fn(),
  enrollDeviceCredential: vi.fn(),
}));
vi.mock("@/lib/auth", () => ({ authDriver }));

import { BiometricEnroll, shouldOfferBiometricEnroll } from "./BiometricEnroll";

const OFFERED_KEY = "vcp.biometric.enroll.offered";

// jsdom here ships no localStorage — install a minimal in-memory one (same
// approach as page.passkey.test.tsx) so the once-per-device flag works.
function installLocalStorage(): void {
  const store = new Map<string, string>();
  const ls: Storage = {
    getItem: (k) => (store.has(k) ? store.get(k)! : null),
    setItem: (k, v) => void store.set(k, String(v)),
    removeItem: (k) => void store.delete(k),
    clear: () => store.clear(),
    key: (i) => Array.from(store.keys())[i] ?? null,
    get length() {
      return store.size;
    },
  };
  Object.defineProperty(window, "localStorage", { value: ls, writable: true, configurable: true });
}

beforeEach(() => {
  vi.clearAllMocks();
  installLocalStorage();
  authDriver.isBiometricAvailable.mockResolvedValue(true);
  authDriver.enrollDeviceCredential.mockResolvedValue(undefined);
});

describe("shouldOfferBiometricEnroll", () => {
  it("true when biometrics available AND not offered yet on this device", async () => {
    await expect(shouldOfferBiometricEnroll()).resolves.toBe(true);
  });

  it("false once already offered on this device (once-per-device)", async () => {
    window.localStorage.setItem(OFFERED_KEY, "1");
    await expect(shouldOfferBiometricEnroll()).resolves.toBe(false);
    // Doesn't even bother asking the driver when already offered.
    expect(authDriver.isBiometricAvailable).not.toHaveBeenCalled();
  });

  it("false when biometrics are not available", async () => {
    authDriver.isBiometricAvailable.mockResolvedValueOnce(false);
    await expect(shouldOfferBiometricEnroll()).resolves.toBe(false);
  });
});

describe("BiometricEnroll", () => {
  it("Enable enrolls the device credential then fires onDone", async () => {
    const onDone = vi.fn();
    const user = userEvent.setup();
    render(<BiometricEnroll onDone={onDone} />);

    await user.click(screen.getByRole("button", { name: /enable biometric unlock/i }));

    await waitFor(() => expect(authDriver.enrollDeviceCredential).toHaveBeenCalledTimes(1));
    expect(onDone).toHaveBeenCalledTimes(1);
    // Marked offered so the prompt is one-and-done.
    expect(window.localStorage.getItem(OFFERED_KEY)).toBe("1");
  });

  it("Not now fires onDone WITHOUT enrolling", async () => {
    const onDone = vi.fn();
    const user = userEvent.setup();
    render(<BiometricEnroll onDone={onDone} />);

    await user.click(screen.getByRole("button", { name: /not now/i }));

    expect(authDriver.enrollDeviceCredential).not.toHaveBeenCalled();
    expect(onDone).toHaveBeenCalledTimes(1);
    expect(window.localStorage.getItem(OFFERED_KEY)).toBe("1");
  });

  it("a failed enroll shows a retry and does NOT call onDone (never strands/skips)", async () => {
    authDriver.enrollDeviceCredential.mockRejectedValueOnce(new Error("Face ID cancelled"));
    const onDone = vi.fn();
    const user = userEvent.setup();
    render(<BiometricEnroll onDone={onDone} />);

    await user.click(screen.getByRole("button", { name: /enable biometric unlock/i }));

    await waitFor(() => expect(screen.getByRole("alert")).toBeInTheDocument());
    expect(onDone).not.toHaveBeenCalled();
    // The button is enabled again for a retry; the user can still "Not now" out.
    expect(screen.getByRole("button", { name: /enable biometric unlock/i })).toBeEnabled();
  });
});
