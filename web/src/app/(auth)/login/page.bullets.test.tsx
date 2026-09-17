import { describe, it, expect, vi } from "vitest";
import { render, screen } from "@testing-library/react";
import LoginPage from "./page";

// P1-17: the cold landing must say what the product does BEFORE asking for a
// phone/email — three value bullets + "Free to start" — while leaving the OTP
// flow untouched (single box, instruction line, send button all still there).

vi.mock("next/navigation", () => ({
  useRouter: () => ({ replace: vi.fn() }),
}));

// The AuthDriver pulls the Firebase/plugin graph — stub the seam entirely.
vi.mock("@/lib/auth", () => ({
  authDriver: {
    requestEmailCode: vi.fn(),
    verifyEmailCode: vi.fn(),
    attachEmailCode: vi.fn(),
    startPhone: vi.fn(),
    startLinkPhone: vi.fn(),
    resetPhoneVerifier: vi.fn(),
  },
}));

describe("Login cold landing (P1-17)", () => {
  it("shows the three value bullets and the free-to-start line above the box", () => {
    render(<LoginPage />);
    expect(screen.getByText("Organize your medical evidence")).toBeInTheDocument();
    expect(
      screen.getByText("Understand your conditions and what VA looks for"),
    ).toBeInTheDocument();
    expect(screen.getByText("See exactly what to do next")).toBeInTheDocument();
    expect(screen.getByText(/free to start — no card required/i)).toBeInTheDocument();
  });

  it("leaves the OTP flow untouched: single box, instruction, send button", () => {
    render(<LoginPage />);
    expect(screen.getByLabelText(/phone or email/i)).toBeInTheDocument();
    expect(
      screen.getByText(/we only use your phone and email for passwordless sign-in/i),
    ).toBeInTheDocument();
    expect(screen.getByRole("button", { name: /send me a code/i })).toBeInTheDocument();
  });
});
