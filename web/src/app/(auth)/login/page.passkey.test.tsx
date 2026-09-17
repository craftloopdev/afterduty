import { beforeEach, describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import LoginPage from "./page";

// Passkey-first login + post-OTP enroll (auth-program-plan P1.3), all PROGRESSIVE:
//   - login prompts a passkey when supported + the account has one, else silently
//     falls back to the existing OTP flow (unsupported / cancel / no creds);
//   - after an OTP sign-in on a device with no passkey, the enroll offer appears
//     (skippable, once per device) and NEVER blocks navigation.
// The OTP flow itself is unchanged — the driver stub carries the same methods the
// existing login tests use, plus the passkey ones.

const driver = vi.hoisted(() => ({
  requestEmailCode: vi.fn(),
  verifyEmailCode: vi.fn(),
  attachEmailCode: vi.fn(),
  startPhone: vi.fn(),
  startLinkPhone: vi.fn(),
  resetPhoneVerifier: vi.fn(),
  isPasskeySupported: vi.fn(),
  passkeyLogin: vi.fn(),
  enrollPasskey: vi.fn(),
}));
vi.mock("@/lib/auth", () => ({ authDriver: driver }));
vi.mock("@/lib/platform", () => ({ NATIVE: false }));
vi.mock("next/navigation", () => ({ useRouter: () => ({ replace: vi.fn() }) }));
vi.mock("@/lib/native/haptics", () => ({
  tapLight: vi.fn(),
  notifySuccess: vi.fn(),
  notifyError: vi.fn(),
}));

// In-memory localStorage (jsdom here has none — the "once per device" flag lives
// in localStorage, so we shim a real one).
function installLocalStorage() {
  const store = new Map<string, string>();
  const ls = {
    getItem: (k: string) => (store.has(k) ? store.get(k)! : null),
    setItem: (k: string, v: string) => void store.set(k, String(v)),
    removeItem: (k: string) => void store.delete(k),
    clear: () => store.clear(),
    key: (i: number) => Array.from(store.keys())[i] ?? null,
    get length() {
      return store.size;
    },
  };
  Object.defineProperty(window, "localStorage", { value: ls, writable: true, configurable: true });
}

const assign = vi.fn();
beforeEach(() => {
  vi.clearAllMocks();
  installLocalStorage();
  Object.defineProperty(window, "location", {
    value: { ...window.location, assign, search: "" },
    writable: true,
  });
  // Defaults: passkeys supported, but the account has none (login falls back) and
  // no passkey enrolled yet (enroll offer eligible).
  driver.isPasskeySupported.mockReturnValue(true);
  driver.passkeyLogin.mockResolvedValue(false);
  driver.requestEmailCode.mockResolvedValue(undefined);
});

function typeIdentifier(value: string) {
  fireEvent.change(screen.getByPlaceholderText(/you@example.com/), { target: { value } });
  fireEvent.click(screen.getByRole("button", { name: /send me a code/i }));
}

describe("passkey-first login (Model B, identifier-first)", () => {
  it("signs in with a passkey WITHOUT an OTP when the account has one", async () => {
    driver.passkeyLogin.mockResolvedValue(true); // account has a passkey on this device
    render(<LoginPage />);

    typeIdentifier("vet@example.com");

    await waitFor(() => expect(driver.passkeyLogin).toHaveBeenCalledWith("vet@example.com"));
    // No OTP was sent — we went straight to the app.
    expect(driver.requestEmailCode).not.toHaveBeenCalled();
    await waitFor(() => expect(assign).toHaveBeenCalled());
  });

  it("falls back to the OTP flow when there is no passkey (passkeyLogin=false)", async () => {
    driver.passkeyLogin.mockResolvedValue(false);
    render(<LoginPage />);

    typeIdentifier("vet@example.com");

    await waitFor(() => expect(driver.passkeyLogin).toHaveBeenCalled());
    // Fell through to the email OTP send + landed on the code step.
    await waitFor(() => expect(driver.requestEmailCode).toHaveBeenCalledWith("vet@example.com", "signin"));
    expect(await screen.findByText(/6-digit code we just emailed you/i)).toBeInTheDocument();
    expect(assign).not.toHaveBeenCalled();
  });

  it("falls back to OTP when passkeyLogin throws (never blocks login)", async () => {
    driver.passkeyLogin.mockRejectedValue(new Error("boom"));
    render(<LoginPage />);

    typeIdentifier("vet@example.com");

    await waitFor(() => expect(driver.requestEmailCode).toHaveBeenCalled());
    expect(await screen.findByText(/6-digit code we just emailed you/i)).toBeInTheDocument();
  });

  it("does NOT attempt a passkey when WebAuthn is unsupported — plain OTP as today", async () => {
    driver.isPasskeySupported.mockReturnValue(false);
    render(<LoginPage />);

    typeIdentifier("vet@example.com");

    await waitFor(() => expect(driver.requestEmailCode).toHaveBeenCalled());
    expect(driver.passkeyLogin).not.toHaveBeenCalled();
  });
});

describe("post-OTP passkey enroll offer", () => {
  async function signInReturningUser() {
    driver.verifyEmailCode.mockResolvedValue({ isNewUser: false, hasPhone: true });
    render(<LoginPage />);
    typeIdentifier("vet@example.com");
    await waitFor(() => expect(driver.requestEmailCode).toHaveBeenCalled());
    fireEvent.change(screen.getByPlaceholderText("123456"), { target: { value: "111222" } });
    fireEvent.click(screen.getByRole("button", { name: /verify & sign in/i }));
    await waitFor(() => expect(driver.verifyEmailCode).toHaveBeenCalled());
  }

  it("offers enrollment after an OTP sign-in on a device with no passkey", async () => {
    await signInReturningUser();
    // The enroll step is shown INSTEAD of navigating immediately.
    expect(await screen.findByText(/faster sign-in next time/i)).toBeInTheDocument();
    expect(assign).not.toHaveBeenCalled();
  });

  it("enrolls a passkey then navigates into the app (happy path)", async () => {
    driver.enrollPasskey.mockResolvedValue(undefined);
    await signInReturningUser();
    await screen.findByText(/faster sign-in next time/i);

    fireEvent.click(screen.getByRole("button", { name: /set up faster sign-in/i }));

    await waitFor(() => expect(driver.enrollPasskey).toHaveBeenCalled());
    await waitFor(() => expect(assign).toHaveBeenCalled());
  });

  it("'Not now' skips enrollment and navigates — never blocks sign-in", async () => {
    await signInReturningUser();
    await screen.findByText(/faster sign-in next time/i);

    fireEvent.click(screen.getByRole("button", { name: /not now/i }));
    expect(assign).toHaveBeenCalled();
  });

  it("does not offer enrollment again on the same device (once per device)", async () => {
    window.localStorage.setItem("vcp.passkey.enroll.offered", "1");
    await signInReturningUser();
    // Straight to the app — no enroll card.
    await waitFor(() => expect(assign).toHaveBeenCalled());
    expect(screen.queryByText(/faster sign-in next time/i)).not.toBeInTheDocument();
  });

  it("does not offer enrollment when WebAuthn is unsupported", async () => {
    driver.isPasskeySupported.mockReturnValue(false);
    await signInReturningUser();
    await waitFor(() => expect(assign).toHaveBeenCalled());
    expect(screen.queryByText(/faster sign-in next time/i)).not.toBeInTheDocument();
  });
});
