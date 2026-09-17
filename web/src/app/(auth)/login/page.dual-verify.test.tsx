import { beforeEach, describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import LoginPage from "./page";

// Onboarding collects BOTH sign-in channels (email + phone, each OTP-verified):
// brand-new users must complete the second channel; returning accounts missing
// one (abandoned dual-verify, pre-cutover legacy) get the same step with a
// "Skip for now" escape. The copy explains the channels are for signing in.

const driver = vi.hoisted(() => ({
  requestEmailCode: vi.fn(),
  verifyEmailCode: vi.fn(),
  attachEmailCode: vi.fn(),
  startPhone: vi.fn(),
  startLinkPhone: vi.fn(),
  resetPhoneVerifier: vi.fn(),
}));
vi.mock("@/lib/auth", () => ({ authDriver: driver }));
vi.mock("@/lib/platform", () => ({ NATIVE: false }));
vi.mock("next/navigation", () => ({ useRouter: () => ({ replace: vi.fn() }) }));

const assign = vi.fn();
beforeEach(() => {
  vi.clearAllMocks();
  // jsdom's window.location.assign is a non-configurable no-op; replace it.
  Object.defineProperty(window, "location", {
    value: { ...window.location, assign, search: "" },
    writable: true,
  });
});

async function signInWithEmail(verifyResult: { isNewUser: boolean; hasPhone: boolean }) {
  driver.requestEmailCode.mockResolvedValue(undefined);
  driver.verifyEmailCode.mockResolvedValue(verifyResult);
  render(<LoginPage />);
  fireEvent.change(screen.getByPlaceholderText(/you@example.com/), {
    target: { value: "vet@example.com" },
  });
  fireEvent.click(screen.getByRole("button", { name: /send me a code/i }));
  await waitFor(() => expect(driver.requestEmailCode).toHaveBeenCalled());
  fireEvent.change(screen.getByPlaceholderText("123456"), { target: { value: "111222" } });
  fireEvent.click(screen.getByRole("button", { name: /verify & sign in/i }));
  await waitFor(() => expect(driver.verifyEmailCode).toHaveBeenCalled());
}

async function signInWithPhone(confirmResult: { isNewUser: boolean; hasEmail: boolean }) {
  driver.startPhone.mockResolvedValue({ confirm: vi.fn().mockResolvedValue(confirmResult) });
  render(<LoginPage />);
  fireEvent.change(screen.getByPlaceholderText(/you@example.com/), {
    target: { value: "(202) 555-0147" },
  });
  fireEvent.click(screen.getByRole("button", { name: /send me a code/i }));
  await waitFor(() => expect(driver.startPhone).toHaveBeenCalled());
  fireEvent.change(screen.getByPlaceholderText("123456"), { target: { value: "111222" } });
  fireEvent.click(screen.getByRole("button", { name: /verify & sign in/i }));
}

describe("dual-verify collection (both sign-in channels)", () => {
  it("brand-new email user must add a phone — sign-in purpose explained, NO skip", async () => {
    await signInWithEmail({ isNewUser: true, hasPhone: false });
    expect(await screen.findByText("How you'll sign in")).toBeInTheDocument();
    expect(screen.getByText(/sign in with either your email or your phone/i)).toBeInTheDocument();
    expect(screen.queryByText("Skip for now")).not.toBeInTheDocument();
    expect(assign).not.toHaveBeenCalled();
  });

  it("returning phone-less account gets the same step WITH Skip for now", async () => {
    await signInWithEmail({ isNewUser: false, hasPhone: false });
    expect(await screen.findByText("How you'll sign in")).toBeInTheDocument();
    const skip = screen.getByText("Skip for now");
    fireEvent.click(skip);
    expect(assign).toHaveBeenCalled(); // proceeds — already signed in, never blocked
  });

  it("returning user with both channels goes straight in", async () => {
    await signInWithEmail({ isNewUser: false, hasPhone: true });
    await waitFor(() => expect(assign).toHaveBeenCalled());
    expect(screen.queryByText("How you'll sign in")).not.toBeInTheDocument();
  });

  it("brand-new phone user must add an email — NO skip", async () => {
    await signInWithPhone({ isNewUser: true, hasEmail: false });
    expect(await screen.findByText("How you'll sign in")).toBeInTheDocument();
    expect(screen.getByText(/sign in with either your phone or your email/i)).toBeInTheDocument();
    expect(screen.queryByText("Skip for now")).not.toBeInTheDocument();
  });

  it("legacy phone-only account (pre-cutover) is prompted for email with Skip", async () => {
    await signInWithPhone({ isNewUser: false, hasEmail: false });
    expect(await screen.findByText("How you'll sign in")).toBeInTheDocument();
    expect(screen.getByText("Skip for now")).toBeInTheDocument();
  });

  it("collecting the second channel verifies it with an OTP (email attach path)", async () => {
    driver.startLinkPhone.mockResolvedValue({
      confirm: vi.fn().mockResolvedValue({ isNewUser: false, hasEmail: true }),
    });
    await signInWithEmail({ isNewUser: true, hasPhone: false });
    fireEvent.change(screen.getByPlaceholderText("(555) 555-0123"), {
      target: { value: "2025550147" },
    });
    fireEvent.click(screen.getByRole("button", { name: /send me a code/i }));
    await waitFor(() => expect(driver.startLinkPhone).toHaveBeenCalledWith("+12025550147", "recaptcha-container"));
    // A code entry step follows — the second channel is OTP-verified, not just stored.
    expect(await screen.findByText(/6-digit code we just texted you/i)).toBeInTheDocument();
  });
});
