import { describe, it, expect, vi } from "vitest";
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { ConditionsList } from "./ConditionsList";
import type { CondVM } from "@/lib/models/vm";

// ConditionsList now calls useRouter().refresh() after an exclude/include toggle.
vi.mock("next/navigation", () => ({ useRouter: () => ({ refresh: vi.fn() }) }));

// P1-27: the default "Ready" filter used to hide most/all conditions on first
// visit. The default is now "Ready" ONLY when at least one condition is ready,
// otherwise "All" — and an empty filter result gets stateful copy with a reset
// affordance (distinct from the page-level "No conditions yet").

function cond(id: number, name: string, ready: boolean): CondVM {
  const leg = { level: "strong" as const, items: ["Evidence on file"] };
  return {
    id,
    name,
    fullName: name,
    system: "Mental Disorders",
    vasrdCode: "9411",
    rating: ready ? 70 : null,
    confidence: 95,
    presumptive: null,
    triad: ready
      ? { dx: "strong", is: "strong", nx: "strong" }
      : { dx: "strong", is: "partial", nx: "missing" },
    ready,
    rationale: null,
    ratingEvidenceNote: null,
    pyramidGroup: null,
    pyramidPrimary: false,
    pyramidReason: null,
    pyramidGroupRating: null,
    excludedFromClaim: false,
    legs: { dx: leg, is: leg, nx: leg },
    weakestLeg: ready ? null : "nx",
  };
}

describe("ConditionsList default filter (P1-27)", () => {
  it("defaults to All when NO condition is ready — every condition stays visible", () => {
    render(
      <ConditionsList conditions={[cond(1, "PTSD", false), cond(2, "Asthma", false)]} />,
    );
    expect(screen.getByRole("button", { name: /All/ })).toHaveAttribute("data-active");
    expect(screen.getByRole("button", { name: /^Ready/ })).not.toHaveAttribute("data-active");
    // Desktop row + mobile card per condition — nothing hidden.
    expect(screen.getAllByRole("link", { name: /PTSD/ })).toHaveLength(2);
    expect(screen.getAllByRole("link", { name: /Asthma/ })).toHaveLength(2);
    expect(screen.queryByText(/No conditions match this filter/)).not.toBeInTheDocument();
  });

  it("defaults to Ready when at least one condition is ready", () => {
    render(
      <ConditionsList conditions={[cond(1, "PTSD", true), cond(2, "Asthma", false)]} />,
    );
    expect(screen.getByRole("button", { name: /^Ready/ })).toHaveAttribute("data-active");
    expect(screen.getAllByRole("link", { name: /PTSD/ })).toHaveLength(2);
    expect(screen.queryByRole("link", { name: /Asthma/ })).not.toBeInTheDocument();
  });

  it("shows stateful empty-filter copy with a reset affordance, and reset shows all", async () => {
    const user = userEvent.setup();
    render(
      <ConditionsList conditions={[cond(1, "PTSD", false), cond(2, "Asthma", false)]} />,
    );
    // No presumptive conditions — that filter is empty.
    await user.click(screen.getByRole("button", { name: /Presumptive/ }));
    expect(screen.getByText("No conditions match this filter.")).toBeInTheDocument();
    // Distinct from the page-level "No conditions yet" empty state.
    expect(screen.queryByText(/No conditions yet/)).not.toBeInTheDocument();

    await user.click(screen.getByRole("button", { name: /Show all 2 conditions/ }));
    expect(screen.getByRole("button", { name: /All/ })).toHaveAttribute("data-active");
    expect(screen.getAllByRole("link", { name: /PTSD/ })).toHaveLength(2);
    expect(screen.getAllByRole("link", { name: /Asthma/ })).toHaveLength(2);
  });
});
