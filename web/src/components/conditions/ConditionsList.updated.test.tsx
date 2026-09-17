import { describe, it, expect, vi } from "vitest";
import { render, screen } from "@testing-library/react";
import { ConditionsList } from "./ConditionsList";
import type { CondVM } from "@/lib/models/vm";

vi.mock("next/navigation", () => ({ useRouter: () => ({ refresh: vi.fn() }) }));

// "Updated" pills (roadmap §5 item 6): rows whose id is in the latest unread
// analysis_updated metadata.changedConditionIds get a small pill on both the
// desktop table row and the mobile card.

function cond(id: number, name: string): CondVM {
  const leg = { level: "strong" as const, items: ["Evidence on file"] };
  return {
    id,
    name,
    fullName: name,
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
  };
}

describe("ConditionsList — Updated pills", () => {
  it("pills the changed conditions on both the table row and the mobile card", () => {
    render(<ConditionsList conditions={[cond(93, "PTSD"), cond(94, "Asthma")]} updatedIds={[93]} />);
    // Desktop table row + mobile card for id 93 — and only for id 93.
    expect(screen.getAllByText("Updated")).toHaveLength(2);
    const row = screen.getAllByRole("link", { name: /PTSD/ })[0];
    expect(row.textContent).toContain("Updated");
    for (const asthma of screen.getAllByRole("link", { name: /Asthma/ })) {
      expect(asthma.textContent).not.toContain("Updated");
    }
  });

  it("renders no pills when updatedIds is absent (dev fixtures, journal outage)", () => {
    render(<ConditionsList conditions={[cond(93, "PTSD")]} />);
    expect(screen.queryByText("Updated")).not.toBeInTheDocument();
  });
});
