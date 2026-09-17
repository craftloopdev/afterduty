import { describe, it, expect, vi, beforeEach } from "vitest";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { NameCaptureCard } from "./NameCaptureCard";

const refresh = vi.fn();
vi.mock("next/navigation", () => ({
  useRouter: () => ({ refresh: () => refresh() }),
}));

const updatePreferredName = vi.fn();
vi.mock("@/lib/api/mutations", async (importOriginal) => ({
  ...(await importOriginal<Record<string, unknown>>()),
  updatePreferredName: (...a: unknown[]) => updatePreferredName(...a),
}));

beforeEach(() => {
  refresh.mockReset();
  updatePreferredName.mockReset();
  sessionStorage.clear();
});

describe("NameCaptureCard — shows only when nameless", () => {
  it("renders the ask when show=true and not dismissed", async () => {
    render(<NameCaptureCard show />);
    expect(await screen.findByText(/what should we call you\?/i)).toBeInTheDocument();
  });

  it("renders NOTHING when the account already has a name (show=false)", () => {
    const { container } = render(<NameCaptureCard show={false} />);
    expect(container).toBeEmptyDOMElement();
  });

  it("stays hidden for the rest of the tab after a dismissal (sessionStorage)", async () => {
    render(<NameCaptureCard show />);
    await userEvent.click(await screen.findByRole("button", { name: /dismiss/i }));
    expect(screen.queryByText(/what should we call you/i)).not.toBeInTheDocument();
    expect(sessionStorage.getItem("cp_name_card_dismissed")).toBe("1");

    // A fresh mount in the same tab respects the flag — never nags again.
    const { container } = render(<NameCaptureCard show />);
    await waitFor(() => expect(container).toBeEmptyDOMElement());
  });
});

describe("NameCaptureCard — saving", () => {
  it("PATCHes the trimmed first name, thanks the veteran, and refreshes (web)", async () => {
    updatePreferredName.mockResolvedValue(
      new Response(JSON.stringify({ ok: true, preferredName: "Griff" }), { status: 200 }),
    );
    render(<NameCaptureCard show />);
    await userEvent.type(await screen.findByRole("textbox", { name: /first name/i }), "  Griff ");
    await userEvent.click(screen.getByRole("button", { name: /^save$/i }));

    expect(await screen.findByText(/good to meet you/i)).toBeInTheDocument();
    expect(updatePreferredName).toHaveBeenCalledWith("Griff");
    expect(refresh).toHaveBeenCalled();
  });

  it("prefers the native onSaved refetch over router.refresh when provided", async () => {
    updatePreferredName.mockResolvedValue(new Response(null, { status: 200 }));
    const onSaved = vi.fn();
    render(<NameCaptureCard show onSaved={onSaved} />);
    await userEvent.type(await screen.findByRole("textbox", { name: /first name/i }), "Griff");
    await userEvent.click(screen.getByRole("button", { name: /^save$/i }));

    await waitFor(() => expect(onSaved).toHaveBeenCalled());
    expect(refresh).not.toHaveBeenCalled();
  });

  it("keeps the card + shows honest copy when the save fails", async () => {
    updatePreferredName.mockResolvedValue(new Response(null, { status: 502 }));
    render(<NameCaptureCard show />);
    await userEvent.type(await screen.findByRole("textbox", { name: /first name/i }), "Griff");
    await userEvent.click(screen.getByRole("button", { name: /^save$/i }));

    expect(await screen.findByText(/couldn't save your name/i)).toBeInTheDocument();
    expect(screen.getByRole("textbox", { name: /first name/i })).toBeInTheDocument();
    expect(refresh).not.toHaveBeenCalled();
  });

  it("rejects an empty submit client-side without calling the API", async () => {
    render(<NameCaptureCard show />);
    await screen.findByRole("textbox", { name: /first name/i });
    await userEvent.click(screen.getByRole("button", { name: /^save$/i }));
    expect(await screen.findByText(/enter a name/i)).toBeInTheDocument();
    expect(updatePreferredName).not.toHaveBeenCalled();
  });
});
