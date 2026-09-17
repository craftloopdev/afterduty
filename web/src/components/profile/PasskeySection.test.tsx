import { describe, it, expect, beforeEach, vi } from "vitest";
import { render, screen, waitFor, fireEvent } from "@testing-library/react";
import userEvent from "@testing-library/user-event";

// PasskeySection (auth-program-plan P1.3 MANAGE). We stub the AuthDriver seam so
// this is pure UI logic: it renders the list, add/rename/revoke call the driver,
// and a step-up CANCEL on revoke surfaces a gentle "cancelled" note (not an
// error). The driver's own revoke→step-up-403 wiring is covered in the driver
// suite; here we prove the component handles the typed cancel.

const isPasskeySupported = vi.fn();
const listPasskeys = vi.fn();
const enrollPasskey = vi.fn();
const renamePasskey = vi.fn();
const revokePasskey = vi.fn();

vi.mock("@/lib/auth", async () => {
  const actual = await vi.importActual<typeof import("@/lib/auth")>("@/lib/auth");
  return {
    ...actual,
    authDriver: {
      isPasskeySupported: () => isPasskeySupported(),
      listPasskeys: () => listPasskeys(),
      enrollPasskey: (...a: unknown[]) => enrollPasskey(...a),
      renamePasskey: (...a: unknown[]) => renamePasskey(...a),
      revokePasskey: (...a: unknown[]) => revokePasskey(...a),
    },
  };
});

vi.mock("@/lib/native/haptics", () => ({
  tapLight: vi.fn(),
  notifySuccess: vi.fn(),
  notifyError: vi.fn(),
}));

import { PasskeySection } from "./PasskeySection";
import { StepUpCancelledError } from "@/lib/auth/step-up";

const CREDS = [
  { id: "c1", nickname: "My laptop", createdAt: "2026-07-04", lastUsedAt: null, deviceHint: "This device" },
  { id: "c2", nickname: null, createdAt: "2026-07-01", lastUsedAt: null, deviceHint: "Security key" },
];

beforeEach(() => {
  vi.clearAllMocks();
  isPasskeySupported.mockReturnValue(true);
  listPasskeys.mockResolvedValue(CREDS);
});

describe("PasskeySection", () => {
  it("renders nothing when WebAuthn is unsupported (native / old browser)", () => {
    isPasskeySupported.mockReturnValue(false);
    const { container } = render(<PasskeySection />);
    expect(container).toBeEmptyDOMElement();
    expect(listPasskeys).not.toHaveBeenCalled();
  });

  it("lists the account's passkeys (nickname, else deviceHint)", async () => {
    render(<PasskeySection />);
    expect(await screen.findByText("My laptop")).toBeInTheDocument();
    // c2 has no nickname → falls back to its deviceHint.
    expect(screen.getByText("Security key")).toBeInTheDocument();
  });

  it("shows an empty state when there are no passkeys yet", async () => {
    listPasskeys.mockResolvedValue([]);
    render(<PasskeySection />);
    expect(await screen.findByText(/no passkeys yet/i)).toBeInTheDocument();
  });

  it("adds a passkey then reloads the list", async () => {
    enrollPasskey.mockResolvedValue(undefined);
    render(<PasskeySection />);
    await screen.findByText("My laptop");

    await userEvent.click(screen.getByRole("button", { name: /add a passkey/i }));
    await waitFor(() => expect(enrollPasskey).toHaveBeenCalled());
    // list refetched (initial + after add).
    expect(listPasskeys).toHaveBeenCalledTimes(2);
    expect(await screen.findByText(/passkey added/i)).toBeInTheDocument();
  });

  it("revokes a passkey and removes its row on success", async () => {
    revokePasskey.mockResolvedValue(undefined);
    render(<PasskeySection />);
    await screen.findByText("My laptop");

    const removeButtons = screen.getAllByRole("button", { name: /^remove$/i });
    fireEvent.click(removeButtons[0]);

    await waitFor(() => expect(revokePasskey).toHaveBeenCalledWith("c1"));
    await waitFor(() => expect(screen.queryByText("My laptop")).not.toBeInTheDocument());
  });

  it("surfaces a gentle 'cancelled' note when the step-up ceremony is cancelled on revoke", async () => {
    // The driver's revoke routes through the step-up seam; a user dismissing the
    // "Confirm it's you" modal rejects with StepUpCancelledError.
    revokePasskey.mockRejectedValue(new StepUpCancelledError());
    render(<PasskeySection />);
    await screen.findByText("My laptop");

    fireEvent.click(screen.getAllByRole("button", { name: /^remove$/i })[0]);

    expect(await screen.findByText(/removal cancelled/i)).toBeInTheDocument();
    // The row stays — nothing was removed.
    expect(screen.getByText("My laptop")).toBeInTheDocument();
  });

  it("renames a passkey inline", async () => {
    renamePasskey.mockResolvedValue(undefined);
    render(<PasskeySection />);
    await screen.findByText("My laptop");

    await userEvent.click(screen.getByRole("button", { name: /rename my laptop/i }));
    const input = screen.getByLabelText(/passkey name/i);
    fireEvent.change(input, { target: { value: "Work MacBook" } });
    await userEvent.click(screen.getByRole("button", { name: /^save$/i }));

    await waitFor(() => expect(renamePasskey).toHaveBeenCalledWith("c1", "Work MacBook"));
    expect(await screen.findByText("Work MacBook")).toBeInTheDocument();
  });
});
