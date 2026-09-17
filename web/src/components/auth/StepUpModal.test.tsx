import { describe, it, expect, vi, beforeEach } from "vitest";
import { render, screen, waitFor, fireEvent } from "@testing-library/react";
import userEvent from "@testing-library/user-event";

/** Set a controlled input's value in one commit (deterministic vs per-keystroke
 *  userEvent.type on a re-rendering controlled field). */
async function enterCode(value: string) {
  const input = await screen.findByLabelText(/verification code/i);
  fireEvent.change(input, { target: { value } });
  return input;
}

// ── Mocks ────────────────────────────────────────────────────────────────────
// The modal reuses the authDriver (account channels + phone verify) and the
// email-code {detail} POST helper. Stub both so the ceremony is pure UI logic.

const watchAccount = vi.fn();
const startPhone = vi.fn();
const getToken = vi.fn();
const resetPhoneVerifier = vi.fn();

vi.mock("@/lib/auth", () => ({
  authDriver: {
    watchAccount: (cb: (d: unknown) => void) => watchAccount(cb),
    startPhone: (...a: unknown[]) => startPhone(...a),
    getToken: (...a: unknown[]) => getToken(...a),
    resetPhoneVerifier: () => resetPhoneVerifier(),
  },
}));

const emailCodePost = vi.fn();
vi.mock("@/lib/auth/email-code-client", async () => {
  const actual = await vi.importActual<typeof import("@/lib/auth/email-code-client")>(
    "@/lib/auth/email-code-client",
  );
  return { ...actual, emailCodePost: (...a: unknown[]) => emailCodePost(...a) };
});

import { StepUpHost } from "./StepUpModal";
import {
  runStepUp,
  clearStepUpToken,
  StepUpCancelledError,
  __resetStepUpForTests,
} from "@/lib/auth/step-up";

// Feed the host a mounted account with both channels.
function withAccount(account: { email: string | null; phoneNumber: string | null }) {
  watchAccount.mockImplementation((cb: (d: unknown) => void) => {
    cb({ ...account, mfaFactors: [] });
    return () => {};
  });
}

beforeEach(() => {
  vi.clearAllMocks();
  __resetStepUpForTests();
  clearStepUpToken();
});

describe("StepUpModal — ceremony + a11y (P1.2)", () => {
  it("stays hidden until a step-up is requested, then opens a named dialog", async () => {
    withAccount({ email: "vet@example.com", phoneNumber: "+15555550123" });
    render(<StepUpHost />);
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();

    runStepUp({ acceptedFactors: ["otp"] }).catch(() => {});

    const dialog = await screen.findByRole("dialog", { name: "Confirm it's you" });
    expect(dialog).toHaveAttribute("aria-modal", "true");
    // Focus moved into the dialog (Modal a11y trio).
    expect(dialog).toHaveFocus();
  });

  it("runs the EMAIL OTP lane end-to-end and resolves runStepUp with the token", async () => {
    withAccount({ email: "vet@example.com", phoneNumber: null });
    emailCodePost
      .mockResolvedValueOnce(undefined) // request purpose:"stepup"
      .mockResolvedValueOnce({ stepUpToken: "STEP123", expiresInSec: 300 }); // verify

    render(<StepUpHost />);
    const tokenP = runStepUp({ acceptedFactors: ["otp"] });
    await screen.findByRole("dialog");

    await userEvent.click(screen.getByRole("button", { name: /send me a code/i }));

    // Request lane hit with the stepup purpose (server-verified, like sign-in).
    await waitFor(() =>
      expect(emailCodePost).toHaveBeenCalledWith("/api/auth/email-code/request", {
        email: "vet@example.com",
        purpose: "stepup",
      }),
    );

    await enterCode("654321");
    await userEvent.click(screen.getByRole("button", { name: /^confirm$/i }));

    await expect(tokenP).resolves.toBe("STEP123");
    expect(emailCodePost).toHaveBeenLastCalledWith("/api/auth/step-up/verify", {
      factor: "otp",
      channel: "email",
      code: "654321",
    });
    // Dialog closed after success.
    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
  });

  it("runs the PHONE lane: confirm SMS → fresh id token → verify {channel:phone,idToken}", async () => {
    withAccount({ email: null, phoneNumber: "+15555550123" });
    const confirm = vi.fn().mockResolvedValue({ isNewUser: false, hasEmail: true });
    startPhone.mockResolvedValue({ confirm });
    getToken.mockResolvedValue("FRESH_ID_TOKEN");
    emailCodePost.mockResolvedValueOnce({ stepUpToken: "PHTOK", expiresInSec: 300 });

    render(<StepUpHost />);
    const tokenP = runStepUp({ acceptedFactors: ["otp"] });
    await screen.findByRole("dialog");

    await userEvent.click(screen.getByRole("button", { name: /send me a code/i }));
    await waitFor(() => expect(startPhone).toHaveBeenCalledWith("+15555550123", expect.any(String)));

    await enterCode("112233");
    await userEvent.click(screen.getByRole("button", { name: /^confirm$/i }));

    await expect(tokenP).resolves.toBe("PHTOK");
    expect(confirm).toHaveBeenCalledWith("112233");
    expect(getToken).toHaveBeenCalledWith({ forceRefresh: true });
    expect(emailCodePost).toHaveBeenCalledWith("/api/auth/step-up/verify", {
      factor: "otp",
      channel: "phone",
      idToken: "FRESH_ID_TOKEN",
    });
  });

  it("rejects runStepUp with StepUpCancelledError when the user cancels", async () => {
    withAccount({ email: "vet@example.com", phoneNumber: "+15555550123" });
    render(<StepUpHost />);
    const tokenP = runStepUp({ acceptedFactors: ["otp"] });
    // Attach the rejection expectation BEFORE the cancel fires so the rejection
    // is always handled (no unhandled-rejection noise from the event dispatch).
    const rejects = expect(tokenP).rejects.toBeInstanceOf(StepUpCancelledError);
    await screen.findByRole("dialog");

    await userEvent.click(screen.getByRole("button", { name: /cancel/i }));
    await rejects;
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
  });

  it("surfaces the backend {detail} on a bad code without leaking a raw error", async () => {
    withAccount({ email: "vet@example.com", phoneNumber: null });
    const { EmailCodeError } = await import("@/lib/auth/email-code-client");
    emailCodePost
      .mockResolvedValueOnce(undefined) // request
      .mockRejectedValueOnce(new EmailCodeError(400, "That code didn't match. Try again.")); // verify

    render(<StepUpHost />);
    runStepUp({ acceptedFactors: ["otp"] }).catch(() => {});
    await screen.findByRole("dialog");
    await userEvent.click(screen.getByRole("button", { name: /send me a code/i }));
    await enterCode("000000");
    await userEvent.click(screen.getByRole("button", { name: /^confirm$/i }));

    expect(await screen.findByRole("alert")).toHaveTextContent("That code didn't match. Try again.");
    // Still open so the user can retry.
    expect(screen.getByRole("dialog")).toBeInTheDocument();
  });
});
