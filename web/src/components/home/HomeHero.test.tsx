import { describe, it, expect } from "vitest";
import { render, screen } from "@testing-library/react";
import { HomeHero } from "./HomeHero";
import { ESTIMATE_HEDGE } from "@/lib/adapters/home";
import type { RatingScopeVM } from "@/lib/models/vm";

// P1-4: the hero carries BOTH expectation-calibrated numbers — "Ready today"
// (headline) and "If all N are granted" — each with its monthly dollars, plus
// the one pinned hedge line. On a failed fetch it must say "unavailable",
// never a fake 0% / $0.

const READY: RatingScopeVM = { rating: 40, monthly: 755, steps: [], combineSteps: [], notes: [], inputs: [], excludedCount: 0 };
const ALL: RatingScopeVM = { rating: 80, monthly: 1995, steps: [], combineSteps: [], notes: [], inputs: [], excludedCount: 0 };

function renderHero(overrides: Partial<Parameters<typeof HomeHero>[0]> = {}) {
  return render(
    <HomeHero
      readyScope={READY}
      allScope={ALL}
      readyCount={3}
      needsWorkCount={6}
      presumptiveCount={2}
      totalConditions={9}
      {...overrides}
    />,
  );
}

describe("HomeHero — dual calibrated numbers (P1-4)", () => {
  it("shows the ready-today rating AND dollars as the headline", () => {
    renderHero();
    expect(
      screen.getByLabelText("Ready today: estimated combined rating 40 percent, $755 per month"),
    ).toBeInTheDocument();
    expect(screen.getByText("$755/mo")).toBeInTheDocument();
  });

  it("shows the if-all-granted companion line with its own rating and dollars", () => {
    renderHero();
    expect(screen.getByText(/If all 9 are granted:/)).toBeInTheDocument();
    expect(screen.getByText("80%")).toBeInTheDocument();
    expect(screen.getByText("$1,995/mo")).toBeInTheDocument();
  });

  it("renders the ONE pinned hedge line under the headline", () => {
    renderHero();
    expect(screen.getByText(ESTIMATE_HEDGE)).toBeInTheDocument();
  });

  it("shows both scopes in the monthly-pay column", () => {
    renderHero();
    expect(screen.getByText("/mo ready today")).toBeInTheDocument();
    expect(screen.getByText("/mo if all are granted")).toBeInTheDocument();
  });

  it("fetch failure: renders the unavailable state, never $0 or 0%", () => {
    renderHero({
      readyScope: { rating: 0, monthly: 0, steps: [], combineSteps: [], notes: [], inputs: [], excludedCount: 0 },
      allScope: { rating: 0, monthly: 0, steps: [], combineSteps: [], notes: [], inputs: [], excludedCount: 0 },
      estimateUnavailable: true,
    });
    expect(screen.getByRole("status")).toHaveTextContent("Estimate unavailable right now");
    expect(screen.queryByText(/\$0/)).not.toBeInTheDocument();
    expect(screen.queryByText(/If all 9 are granted/)).not.toBeInTheDocument();
    // The hedge still frames the (unavailable) estimate.
    expect(screen.getByText(ESTIMATE_HEDGE)).toBeInTheDocument();
  });
});
