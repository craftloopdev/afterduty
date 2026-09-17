import { describe, it, expect, vi, beforeEach, afterEach } from "vitest";
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { ViewerSwitcher } from "./ViewerSwitcher";
import type { SharedClaimRef } from "@/lib/models/vm";

const SHARES: SharedClaimRef[] = [
  { claimId: 42, ownerName: "Dana Vet" },
  { claimId: 43, ownerName: "Rex Marine" },
];

let assign: ReturnType<typeof vi.fn>;
const fetchMock = vi.fn();

beforeEach(() => {
  assign = vi.fn();
  Object.defineProperty(window, "location", {
    configurable: true,
    value: { assign, href: "http://localhost/" },
  });
  fetchMock.mockReset();
  vi.stubGlobal("fetch", fetchMock);
});

afterEach(() => {
  vi.unstubAllGlobals();
});

describe("ViewerSwitcher (P0-8)", () => {
  it("offers a 'View <owner>'s claim' entry per accepted share", () => {
    render(<ViewerSwitcher sharedClaims={SHARES} />);
    expect(screen.getByRole("button", { name: "View Dana Vet's claim" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "View Rex Marine's claim" })).toBeInTheDocument();
  });

  it("renders nothing with no shares", () => {
    const { container } = render(<ViewerSwitcher sharedClaims={[]} />);
    expect(container).toBeEmptyDOMElement();
  });

  it("web select POSTs /api/view-as {claimId} then full-navigates home", async () => {
    fetchMock.mockResolvedValue(new Response(JSON.stringify({ ok: true }), { status: 200 }));
    render(<ViewerSwitcher sharedClaims={SHARES} />);
    await userEvent.click(screen.getByRole("button", { name: "View Dana Vet's claim" }));
    expect(fetchMock).toHaveBeenCalledWith("/api/view-as", {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({ claimId: 42 }),
    });
    expect(assign).toHaveBeenCalledWith("/");
  });

  it("a failed POST surfaces an inline error and does NOT navigate", async () => {
    fetchMock.mockResolvedValue(new Response(JSON.stringify({ error: "no_share_access" }), { status: 403 }));
    render(<ViewerSwitcher sharedClaims={SHARES} />);
    await userEvent.click(screen.getByRole("button", { name: "View Dana Vet's claim" }));
    expect(assign).not.toHaveBeenCalled();
    expect(screen.getByText(/couldn't open that claim/i)).toBeInTheDocument();
  });

  it("native onSelect override replaces the web path entirely", async () => {
    const onSelect = vi.fn();
    render(<ViewerSwitcher sharedClaims={SHARES} onSelect={onSelect} />);
    await userEvent.click(screen.getByRole("button", { name: "View Rex Marine's claim" }));
    expect(onSelect).toHaveBeenCalledWith({ claimId: 43, ownerName: "Rex Marine" });
    expect(fetchMock).not.toHaveBeenCalled();
  });
});
