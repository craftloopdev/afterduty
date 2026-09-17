import { describe, it, expect, vi } from "vitest";
import { fireEvent, render, screen } from "@testing-library/react";
import { ConditionsList } from "./ConditionsList";
import type { CondVM } from "@/lib/models/vm";

vi.mock("next/navigation", () => ({ useRouter: () => ({ refresh: vi.fn() }) }));

// P1-5 / P1-2 list rendering: null ratings say "Not yet rated" (never a dash
// pretending 0), and a presumptive condition's nexus column shows "Covered"
// instead of a scare tag.

function cond(overrides: Partial<CondVM>): CondVM {
  const leg = { level: "strong" as const, items: ["Evidence on file"] };
  return {
    id: 1,
    name: "PTSD",
    fullName: "PTSD",
    system: "Mental Disorders",
    vasrdCode: "9411",
    rating: 70,
    confidence: 95,
    presumptive: null,
    triad: { dx: "strong", is: "strong", nx: "strong" },
    ready: true,
    rationale: null,
    ratingEvidenceNote: null,
    pyramidGroup: null,
    pyramidPrimary: false,
    pyramidReason: null,
    pyramidGroupRating: null,
    excludedFromClaim: false,
    legs: { dx: leg, is: leg, nx: leg },
    weakestLeg: null,
    ...overrides,
  };
}

describe("ConditionsList — rating & presumptive-nexus rendering", () => {
  it("renders a null rating as 'Not yet rated' on both the table row and the card", () => {
    render(<ConditionsList conditions={[cond({ rating: null })]} />);
    expect(screen.getAllByText("Not yet rated")).toHaveLength(2);
  });

  it("renders a true 0% as '0%' — a real outcome, not a dash", () => {
    render(<ConditionsList conditions={[cond({ rating: 0 })]} />);
    expect(screen.getAllByText("0%").length).toBeGreaterThan(0);
    expect(screen.queryByText("Not yet rated")).not.toBeInTheDocument();
  });

  it("shows 'Covered' (not a nexus scare tag) for a presumptive condition", () => {
    render(
      <ConditionsList
        conditions={[
          cond({
            presumptive: "PACT Act",
            triad: { dx: "strong", is: "strong", nx: "missing" },
            ready: false,
          }),
        ]}
      />,
    );
    // Default filter is "ready" and this condition isn't — switch to "All".
    fireEvent.click(screen.getByRole("button", { name: /All/ }));
    expect(screen.getAllByText("Covered").length).toBeGreaterThan(0);
    // The nexus column must not render "Missing" for the covered leg.
    expect(screen.queryByText("Missing")).not.toBeInTheDocument();
  });
});
