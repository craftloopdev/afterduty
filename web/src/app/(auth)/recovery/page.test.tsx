import { beforeEach, describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import RecoveryPage from "./page";
import { RecoveryError } from "@/lib/auth/recovery-client";

// Dual-channel factor-2 recovery page (auth program P1.5). Asserts the calm flow:
// collect email+phone → send BOTH codes → verify BOTH (confirm phone → fresh id
// token → POST /verify) → establish session → "done". BOTH channels are required;
// the single-channel 409 routes to the support-hold copy; the OTP login flow is a
// separate page and is not exercised here.

const driver = vi.hoisted(() => ({
  startPhone: vi.fn(),
  resetPhoneVerifier: vi.fn(),
  getToken: vi.fn(),
}));
vi.mock("@/lib/auth", () => ({ authDriver: driver }));

const recovery = vi.hoisted(() => ({
  startRecovery: vi.fn(),
  verifyRecovery: vi.fn(),
}));
vi.mock("@/lib/auth/recovery-client", async () => {
  const actual = await vi.importActual<typeof import("@/lib/auth/recovery-client")>(
    "@/lib/auth/recovery-client",
  );
  return {
    ...actual,
    startRecovery: (...a: unknown[]) => recovery.startRecovery(...a),
    verifyRecovery: (...a: unknown[]) => recovery.verifyRecovery(...a),
  };
});

const establish = vi.hoisted(() => vi.fn());
vi.mock("@/lib/firebase/session", () => ({
  signInWithCustomTokenAndEstablish: (...a: unknown[]) => establish(...a),
}));

vi.mock("@/lib/platform", () => ({ NATIVE: false }));
vi.mock("next/navigation", () => ({ useRouter: () => ({ replace: vi.fn() }) }));

const assign = vi.fn();
beforeEach(() => {
  vi.clearAllMocks();
  Object.defineProperty(window, "location", {
    value: { ...window.location, assign, search: "" },
    writable: true,
  });
  recovery.startRecovery.mockResolvedValue(undefined);
  driver.startPhone.mockResolvedValue({ confirm: vi.fn().mockResolvedValue(undefined) });
  driver.getToken.mockResolvedValue("phone-id-token");
});

function fillStart(email = "vet@example.com", phone = "5555550123") {
  fireEvent.change(screen.getByPlaceholderText(/you@example.com/), { target: { value: email } });
  fireEvent.change(screen.getByPlaceholderText(/555.*0123/), { target: { value: phone } });
  fireEvent.click(screen.getByRole("button", { name: /send me codes/i }));
}

describe("recovery flow (dual-channel)", () => {
  it("sends BOTH codes then completes recovery with BOTH proofs and signs in", async () => {
    const confirm = vi.fn().mockResolvedValue(undefined);
    driver.startPhone.mockResolvedValue({ confirm });
    recovery.verifyRecovery.mockResolvedValue({
      custom_token: "ct-abc",
      revokedFactors: ["passkey", "biometric"],
    });

    render(<RecoveryPage />);
    fillStart();

    // Emails a recovery code (anti-enum) AND texts a phone code.
    await waitFor(() => expect(recovery.startRecovery).toHaveBeenCalledWith("vet@example.com"));
    await waitFor(() => expect(driver.startPhone).toHaveBeenCalled());
    expect(driver.startPhone.mock.calls[0][0]).toBe("+15555550123");

    // Enter BOTH codes.
    await screen.findByText(/Emailed code/i);
    const codeInputs = screen.getAllByPlaceholderText(/123456/);
    fireEvent.change(codeInputs[0], { target: { value: "246810" } }); // email
    fireEvent.change(codeInputs[1], { target: { value: "135790" } }); // sms
    fireEvent.click(screen.getByRole("button", { name: /verify & recover/i }));

    // Phone confirmed → fresh id token → POST verify with BOTH proofs.
    await waitFor(() => expect(confirm).toHaveBeenCalledWith("135790"));
    await waitFor(() =>
      expect(recovery.verifyRecovery).toHaveBeenCalledWith(
        "vet@example.com",
        "246810",
        "phone-id-token",
      ),
    );
    // Session established from the minted custom token.
    await waitFor(() => expect(establish).toHaveBeenCalledWith("ct-abc"));
    // Landed on the "you're back in" screen.
    await screen.findByText(/back in/i);
    expect(screen.getByText(/removed your saved sign-in methods/i)).toBeInTheDocument();
  });

  it("blocks submit until BOTH codes are entered (neither alone)", async () => {
    render(<RecoveryPage />);
    fillStart();
    await screen.findByText(/Emailed code/i);

    // Only the email code — must not call verify; the alert nudges for the text code.
    fireEvent.change(screen.getAllByPlaceholderText(/123456/)[0], { target: { value: "246810" } });
    fireEvent.click(screen.getByRole("button", { name: /verify & recover/i }));
    await waitFor(() =>
      expect(screen.getByRole("alert")).toHaveTextContent(/code we texted/i),
    );
    expect(recovery.verifyRecovery).not.toHaveBeenCalled();
  });

  it("routes a single-channel 409 to the support-hold path", async () => {
    driver.startPhone.mockResolvedValue({ confirm: vi.fn().mockResolvedValue(undefined) });
    recovery.verifyRecovery.mockRejectedValue(new RecoveryError(409, null, true));

    render(<RecoveryPage />);
    fillStart();
    await screen.findByText(/Emailed code/i);
    fireEvent.change(screen.getAllByPlaceholderText(/123456/)[0], { target: { value: "246810" } });
    fireEvent.change(screen.getAllByPlaceholderText(/123456/)[1], { target: { value: "135790" } });
    fireEvent.click(screen.getByRole("button", { name: /verify & recover/i }));

    await screen.findByText(/only has one sign-in channel/i);
    expect(establish).not.toHaveBeenCalled();
  });

  it("shows the backend {detail} on a generic factor failure and does not sign in", async () => {
    recovery.verifyRecovery.mockRejectedValue(new RecoveryError(400, "Recovery failed.", false));

    render(<RecoveryPage />);
    fillStart();
    await screen.findByText(/Emailed code/i);
    fireEvent.change(screen.getAllByPlaceholderText(/123456/)[0], { target: { value: "000000" } });
    fireEvent.change(screen.getAllByPlaceholderText(/123456/)[1], { target: { value: "111111" } });
    fireEvent.click(screen.getByRole("button", { name: /verify & recover/i }));

    await screen.findByText("Recovery failed.");
    expect(establish).not.toHaveBeenCalled();
  });
});
