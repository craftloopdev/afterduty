import { describe, it, expect, vi } from "vitest";
import { render, screen, within } from "@testing-library/react";
import { ConditionsList } from "./ConditionsList";
import type { CondVM } from "@/lib/models/vm";

vi.mock("next/navigation", () => ({ useRouter: () => ({ refresh: vi.fn() }) }));

// Pyramiding grouped view: conditions VA rates TOGETHER under one formula (e.g. the
// §4.130 mental-health group) render under a single inline group treatment — a header,
// the member rows (each keeping its own strength/rating), a combined line, and a "why"
// linking to /learn/how-va-rates. Ungrouped conditions are unaffected.

function cond(overrides: Partial<CondVM> & { id: number; name: string }): CondVM {
  const leg = { level: "strong" as const, items: ["Evidence on file"] };
  return {
    fullName: overrides.name,
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

const MENTAL = "Mental Health (§4.130)";

function mentalHealthSet(): CondVM[] {
  return [
    cond({ id: 1, name: "PTSD", vasrdCode: "9411", rating: 70,
      pyramidGroup: MENTAL, pyramidPrimary: true, pyramidGroupRating: 70 }),
    cond({ id: 2, name: "Major Depressive Disorder", vasrdCode: "9434", rating: 50,
      pyramidGroup: MENTAL, pyramidPrimary: false, pyramidGroupRating: 70,
      pyramidReason:
        "Rated together with your Mental Health (§4.130) conditions under one VA formula (§4.130) — the strongest (PTSD, 70%) sets the rating; these don't add." }),
    cond({ id: 3, name: "Generalized Anxiety Disorder", vasrdCode: "9400", rating: 30,
      pyramidGroup: MENTAL, pyramidPrimary: false, pyramidGroupRating: 70,
      pyramidReason: "Rated together …" }),
  ];
}

describe("ConditionsList — pyramiding grouped view", () => {
  it("renders a group header, all member rows, a combined line, and a why-link", () => {
    render(<ConditionsList conditions={mentalHealthSet()} />);

    // Header (friendly heading derived from the canonical label). Appears on both
    // the desktop table and the mobile card layouts (CSS picks one at runtime).
    expect(screen.getAllByText(/Mental health — VA rates these together/).length).toBeGreaterThan(0);

    // "N rated as one" count badge.
    expect(screen.getAllByText(/3 rated as one/).length).toBeGreaterThan(0);

    // Every member row is still present (desktop + mobile → 2 links each).
    expect(screen.getAllByRole("link", { name: /PTSD/ }).length).toBe(2);
    expect(screen.getAllByRole("link", { name: /Major Depressive Disorder/ }).length).toBe(2);
    expect(screen.getAllByRole("link", { name: /Generalized Anxiety Disorder/ }).length).toBe(2);

    // The combined line names the effective member + the group rating.
    const combined = screen.getAllByText(/These combine to about/);
    expect(combined.length).toBeGreaterThan(0);
    expect(combined[0].textContent).toContain("70%");
    expect(combined[0].textContent).toContain("PTSD");
    expect(combined[0].textContent).toMatch(/don't add/);

    // The "why" links to the explainer.
    const whyLink = screen.getAllByRole("link", { name: /How VA rates conditions/ })[0];
    expect(whyLink).toHaveAttribute("href", "/learn/how-va-rates");

    // Each member keeps its own rating (strongest picture preserved per row).
    expect(screen.getAllByText("70%").length).toBeGreaterThan(0);
    expect(screen.getAllByText("50%").length).toBeGreaterThan(0);
    expect(screen.getAllByText("30%").length).toBeGreaterThan(0);
  });

  it("leaves ungrouped conditions as plain rows (no group treatment)", () => {
    render(
      <ConditionsList
        conditions={[
          cond({ id: 10, name: "Tinnitus", vasrdCode: "6260", rating: 10, system: "Auditory" }),
          cond({ id: 11, name: "Back Strain", vasrdCode: "5237", rating: 20, system: "Musculoskeletal" }),
        ]}
      />,
    );
    expect(screen.queryByText(/VA rates these together/)).not.toBeInTheDocument();
    expect(screen.queryByText(/rated as one/)).not.toBeInTheDocument();
    expect(screen.queryByRole("link", { name: /How VA rates conditions/ })).not.toBeInTheDocument();
    // Rows still render.
    expect(screen.getAllByRole("link", { name: /Tinnitus/ }).length).toBe(2);
    expect(screen.getAllByRole("link", { name: /Back Strain/ }).length).toBe(2);
  });

  it("groups within the active filter — a group of one member left after filtering degrades to a plain row", () => {
    // PTSD is ready; MDD is not. The default "Ready" filter leaves only PTSD, which is
    // a lone group member → renders as a plain row, no header/combined line.
    render(
      <ConditionsList
        conditions={[
          cond({ id: 1, name: "PTSD", rating: 70, ready: true,
            pyramidGroup: MENTAL, pyramidPrimary: true, pyramidGroupRating: 70 }),
          cond({ id: 2, name: "Major Depressive Disorder", vasrdCode: "9434", rating: 50, ready: false,
            triad: { dx: "strong", is: "partial", nx: "missing" }, weakestLeg: "nx",
            pyramidGroup: MENTAL, pyramidPrimary: false, pyramidGroupRating: 70,
            pyramidReason: "Rated together …" }),
        ]}
      />,
    );
    // Default filter is "Ready" (PTSD qualifies) — only PTSD shows, and with no peer in
    // view there is no "rate together" treatment.
    expect(screen.getAllByRole("link", { name: /PTSD/ }).length).toBe(2);
    expect(screen.queryByRole("link", { name: /Major Depressive Disorder/ })).not.toBeInTheDocument();
    expect(screen.queryByText(/VA rates these together/)).not.toBeInTheDocument();
  });

  it("shows a group-of-null-rating combined line without a percent", () => {
    render(
      <ConditionsList
        conditions={[
          cond({ id: 1, name: "PTSD", rating: null, pyramidGroup: MENTAL, pyramidPrimary: true,
            pyramidGroupRating: null, triad: { dx: "strong", is: "partial", nx: "strong" }, ready: false }),
          cond({ id: 2, name: "MDD", vasrdCode: "9434", rating: null, pyramidGroup: MENTAL,
            pyramidGroupRating: null, pyramidReason: "Rated together …",
            triad: { dx: "strong", is: "partial", nx: "strong" }, ready: false }),
        ]}
      />,
    );
    const header = screen.getAllByText(/combined into one rating/);
    expect(header.length).toBeGreaterThan(0);
    expect(within(header[0]).queryByText(/%/)).not.toBeInTheDocument();
  });
});
