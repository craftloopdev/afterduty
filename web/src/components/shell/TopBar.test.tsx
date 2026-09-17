import { describe, it, expect } from "vitest";
import { render, screen } from "@testing-library/react";
import { TopBar } from "./TopBar";

describe("TopBar (P2-1: no top-right add button)", () => {
  it("keeps the disclaimer pill + profile avatar and has NO Add-evidence button", () => {
    render(<TopBar title="Home" sub={null} initial="T" onDisclaimer={() => {}} />);
    // "Add evidence" dissolved into the Documents page — the top bar no longer
    // carries the "+" button.
    expect(screen.queryByRole("button", { name: /add evidence/i })).not.toBeInTheDocument();
    expect(screen.queryByText(/add evidence/i)).not.toBeInTheDocument();
    // The remaining affordances stay.
    expect(screen.getByRole("link", { name: "Profile & settings" })).toBeInTheDocument();
    expect(screen.getByText("T")).toBeInTheDocument();
  });
});
