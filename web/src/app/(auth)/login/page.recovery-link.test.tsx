import { beforeEach, describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import LoginPage from "./page";

// The factor-2 recovery link is a MINIMAL, additive addition to the login page
// (auth program P1.5): a "Can't use your passkey or Face ID?" link to /recovery,
// shown ONLY on the identifier step so it never disturbs an in-progress OTP flow.
// This asserts the link is present on load, points at /recovery, disappears once
// the flow advances to the code step, and that the normal OTP request still fires.

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

beforeEach(() => {
  vi.clearAllMocks();
  Object.defineProperty(window, "location", {
    value: { ...window.location, assign: vi.fn(), search: "" },
    writable: true,
  });
  driver.isPasskeySupported.mockReturnValue(false); // keep the OTP path deterministic
  driver.passkeyLogin.mockResolvedValue(false);
  driver.requestEmailCode.mockResolvedValue(undefined);
});

describe("login page recovery link", () => {
  it("shows a /recovery link on the identifier step", () => {
    render(<LoginPage />);
    const link = screen.getByRole("link", { name: /can't use your passkey or face id/i });
    expect(link).toHaveAttribute("href", "/recovery");
  });

  it("hides the recovery link once the flow advances (and OTP still fires)", async () => {
    render(<LoginPage />);
    fireEvent.change(screen.getByPlaceholderText(/you@example.com/), {
      target: { value: "vet@example.com" },
    });
    fireEvent.click(screen.getByRole("button", { name: /send me a code/i }));

    // Normal OTP request still happens — the link is purely additive.
    await waitFor(() => expect(driver.requestEmailCode).toHaveBeenCalledWith("vet@example.com", "signin"));
    // On the code step the recovery link is gone (it's identifier-step only).
    await waitFor(() =>
      expect(
        screen.queryByRole("link", { name: /can't use your passkey or face id/i }),
      ).not.toBeInTheDocument(),
    );
  });
});
