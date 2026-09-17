import { describe, it, expect } from "vitest";
import { render, screen } from "@testing-library/react";
import { PayBreakdown } from "./PayBreakdown";
import { ESTIMATE_HEDGE } from "@/lib/adapters/home";
import type { CombineStepVM, RatingScopeVM } from "@/lib/models/vm";

// task #185: the pay card LEADS with the labeled sequential VA-math combine —
// one ordered list that reads like the running math ("Mental health — 70% →
// combined 70%, 30% left" … "Rounded to nearest 10 → 90%"), a pyramiding
// explainer link on grouped rows, the excluded line reworded + moved into the
// summary near the headline, and the abstract "Apply X%" steps preserved as a
// secondary "Show the exact point math" collapsible. When combineSteps is empty
// (older data / divergence fallback) the card reverts to the grouped contributor
// list + abstract "exact VA math" steps.

const COMBINE: CombineStepVM[] = [
  {
    label: "Mental health",
    rating: 70,
    pointsAdded: 70,
    combinedAfter: 70,
    remainingAfter: 30,
    absorbedMembers: ["Major Depressive Disorder", "Anxiety"],
    rounding: false,
  },
  {
    label: "Asthma",
    rating: 60,
    pointsAdded: 18,
    combinedAfter: 88,
    remainingAfter: 12,
    absorbedMembers: [],
    rounding: false,
  },
  {
    label: "Rounded to nearest 10",
    rating: null,
    pointsAdded: 0,
    combinedAfter: 90,
    remainingAfter: 12,
    absorbedMembers: [],
    rounding: true,
  },
];

const READY: RatingScopeVM = {
  rating: 90,
  monthly: 2297,
  steps: ["Apply 70%: 70.00 points, remaining capacity: 30.00%", "Rounded to nearest 10: 90%"],
  combineSteps: COMBINE,
  notes: [],
  excludedCount: 0,
  inputs: [
    { conditionId: 1, name: "PTSD", rating: 70, counted: true, reason: null, group: "mental health" },
    {
      conditionId: 2,
      name: "Major Depressive Disorder",
      rating: 50,
      counted: false,
      reason: "pyramided-into:PTSD",
      group: "mental health",
    },
    { conditionId: 4, name: "Asthma", rating: 60, counted: true, reason: null, group: null },
  ],
};
const ALL: RatingScopeVM = {
  rating: 90,
  monthly: 2297,
  steps: ["Apply 70%: 70.00 points, remaining capacity: 30.00%", "Rounded to nearest 10: 90%"],
  combineSteps: COMBINE,
  notes: ["Anxiety is rated inside your PTSD evaluation — VA won't pay it twice."],
  excludedCount: 0,
  inputs: READY.inputs,
};

function renderCard(overrides: Partial<Parameters<typeof PayBreakdown>[0]> = {}) {
  return render(
    <PayBreakdown readyScope={READY} allScope={ALL} totalConditions={9} ratesYear={2026} {...overrides} />,
  );
}

describe("PayBreakdown (#185 — labeled sequential combine)", () => {
  it("headlines the ready-today dollars with the pinned hedge line", () => {
    renderCard();
    expect(screen.getByText("$2,297")).toBeInTheDocument();
    expect(screen.getByText("/mo ready today")).toBeInTheDocument();
    expect(screen.getByText(ESTIMATE_HEDGE)).toBeInTheDocument();
  });

  it("shows both scopes' ratings — ready row and if-all-granted row", () => {
    renderCard();
    expect(screen.getByText("Combined rating — ready today")).toBeInTheDocument();
    expect(screen.getByText("If all 9 are granted")).toBeInTheDocument();
    expect(screen.getByText(/90% · \$2,297\/mo/)).toBeInTheDocument();
  });

  it("renders the sequential combine: label + its % + the running combined/remaining", () => {
    renderCard();
    expect(screen.getByText("What makes up your 90%")).toBeInTheDocument();
    // Group step: label "Mental health" with its 70% and the first running line.
    expect(screen.getByText(/Mental health/)).toBeInTheDocument();
    expect(screen.getByText(/^combined 70%, 30% left$/)).toBeInTheDocument();
    // Next contributor: Asthma adds 18 points → combined 88, 12 left.
    expect(screen.getByText(/Asthma/)).toBeInTheDocument();
    expect(screen.getByText(/\+18 pts → combined 88%, 12% left/)).toBeInTheDocument();
    // Final rounding step reads "→ 90%".
    expect(screen.getByText("Rounded to nearest 10")).toBeInTheDocument();
    expect(screen.getByText("→ 90%")).toBeInTheDocument();
  });

  it("the group step shows its absorbed members as a 'they don't add' sub-note", () => {
    renderCard();
    expect(
      screen.getByText(/Major Depressive Disorder, Anxiety rated together — they don’t add\./),
    ).toBeInTheDocument();
  });

  it("shows a 'What is pyramiding?' link to the explainer near the grouped row", () => {
    renderCard();
    const link = screen.getByRole("link", { name: /What is pyramiding\?/ });
    expect(link).toHaveAttribute("href", "/learn/how-va-rates#pyramiding");
  });

  it("excluded line: reworded, correct plural, and placed in the summary", () => {
    renderCard({
      readyScope: { ...READY, excludedCount: 2 },
      allScope: { ...ALL, excludedCount: 2 },
    });
    expect(
      screen.getByText("2 conditions excluded by you — not included in this calculation."),
    ).toBeInTheDocument();
    // It sits in the summary, ABOVE the "What makes up" combine list.
    const excluded = screen.getByText(/excluded by you/);
    const contribHead = screen.getByText("What makes up your 90%");
    // DOCUMENT_POSITION_FOLLOWING (4) means contribHead comes AFTER the excluded line.
    expect(
      excluded.compareDocumentPosition(contribHead) & Node.DOCUMENT_POSITION_FOLLOWING,
    ).toBeTruthy();
  });

  it("excluded line reads in the singular when excludedCount === 1", () => {
    renderCard({
      readyScope: { ...READY, excludedCount: 1 },
      allScope: { ...ALL, excludedCount: 1 },
    });
    expect(
      screen.getByText("1 condition excluded by you — not included in this calculation."),
    ).toBeInTheDocument();
  });

  it("omits the excluded line when excludedCount === 0", () => {
    renderCard();
    expect(screen.queryByText(/excluded by you/)).not.toBeInTheDocument();
  });

  it("keeps the abstract point math as a secondary 'Show the exact point math' collapsible", () => {
    renderCard();
    expect(screen.getByText("Show the exact point math")).toBeInTheDocument();
    expect(
      screen.getAllByText("Apply 70%: 70.00 points, remaining capacity: 30.00%").length,
    ).toBeGreaterThanOrEqual(1);
  });

  it("renders pyramiding/bilateral notes as small print", () => {
    renderCard();
    expect(
      screen.getByText("Anxiety is rated inside your PTSD evaluation — VA won't pay it twice."),
    ).toBeInTheDocument();
  });

  it("states the rates vintage the server used", () => {
    renderCard();
    expect(
      screen.getByText("Assumes a veteran with no dependents, at 2026 VA rates."),
    ).toBeInTheDocument();
  });

  // --- Fallback path: no combineSteps (older data / divergence) ---

  it("falls back to the grouped contributor list + 'exact VA math' when combineSteps is empty", () => {
    renderCard({
      readyScope: { ...READY, combineSteps: [] },
      allScope: { ...ALL, combineSteps: [] },
    });
    // No sequential-combine running text.
    expect(screen.queryByText(/combined 70%, 30% left/)).not.toBeInTheDocument();
    // The grouped contributor list renders instead (group line + its %).
    expect(screen.getByText("What makes up your 90%")).toBeInTheDocument();
    expect(screen.getByText("Mental health")).toBeInTheDocument();
    expect(screen.getByText("Asthma")).toBeInTheDocument();
    // The abstract steps use the fallback "exact VA math" summary, not "point math".
    expect(screen.getByText(/The exact VA math — how 90% is combined/)).toBeInTheDocument();
    expect(screen.queryByText("Show the exact point math")).not.toBeInTheDocument();
  });

  it("fallback still shows the pyramiding link on an absorbed group row", () => {
    renderCard({
      readyScope: { ...READY, combineSteps: [] },
      allScope: { ...ALL, combineSteps: [] },
    });
    expect(
      screen.getByRole("link", { name: /What is pyramiding\?/ }),
    ).toHaveAttribute("href", "/learn/how-va-rates#pyramiding");
  });

  it("dead SMC / 'Potential total' rows are gone", () => {
    renderCard();
    expect(screen.queryByText(/Potential total/)).not.toBeInTheDocument();
    expect(screen.queryByText(/SMC/)).not.toBeInTheDocument();
  });

  it("fetch failure: renders the unavailable state, never $0", () => {
    renderCard({
      readyScope: {
        rating: 0,
        monthly: 0,
        steps: [],
        combineSteps: [],
        notes: [],
        inputs: [],
        excludedCount: 0,
      },
      allScope: {
        rating: 0,
        monthly: 0,
        steps: [],
        combineSteps: [],
        notes: [],
        inputs: [],
        excludedCount: 0,
      },
      estimateUnavailable: true,
    });
    expect(screen.getByRole("status")).toHaveTextContent(
      "Estimate unavailable right now. Reload the page to try again.",
    );
    expect(screen.queryByText(/\$0/)).not.toBeInTheDocument();
  });
});
