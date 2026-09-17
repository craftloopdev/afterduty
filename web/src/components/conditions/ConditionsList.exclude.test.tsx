import { describe, it, expect, vi, beforeEach } from "vitest";
import { render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { ConditionsList } from "./ConditionsList";
import { toCondition } from "@/lib/adapters/condition";
import type { CondVM } from "@/lib/models/vm";
import type { ConditionResponse } from "@/lib/models/api";

// "Don't include in my claim" (owner-set, reversible): excluded conditions move
// OUT of the main list into a collapsible "Not filing" section with a one-tap
// Include; the detail-primary control lives in ConditionDetail. The mutation is
// mocked so we assert the exact (id, excluded) call + the refetch (router.refresh).

const refresh = vi.fn();
vi.mock("next/navigation", () => ({ useRouter: () => ({ refresh }) }));

const setConditionExcluded = vi.fn();
vi.mock("@/lib/api/mutations", () => ({
  setConditionExcluded: (...args: unknown[]) => setConditionExcluded(...args),
}));

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

beforeEach(() => {
  refresh.mockReset();
  setConditionExcluded.mockReset();
  setConditionExcluded.mockResolvedValue({ ok: true } as Response);
});

describe("ConditionsList — exclude / include toggle", () => {
  it("adapter threads excludedFromClaim through the raw DTO", () => {
    const raw: ConditionResponse = { id: 1, name: "PTSD", excludedFromClaim: true };
    expect(toCondition(raw).excludedFromClaim).toBe(true);
    // Legacy rows (absent flag) read as included.
    expect(toCondition({ id: 2, name: "GERD" }).excludedFromClaim).toBe(false);
  });

  it("renders an excluded condition in the 'Not filing' section, not the main list", async () => {
    render(
      <ConditionsList
        conditions={[
          cond({ id: 1, name: "PTSD" }),
          cond({ id: 2, name: "Sleep Apnea", excludedFromClaim: true }),
        ]}
      />,
    );

    // The "Not filing (1)" section exists and is collapsed by default.
    const toggle = screen.getByRole("button", { name: /Not filing \(1\)/ });
    expect(toggle).toHaveAttribute("aria-expanded", "false");
    // Sleep Apnea is NOT shown in the (collapsed) main list yet.
    expect(screen.queryByText("Sleep Apnea")).not.toBeInTheDocument();

    // Expand → the excluded condition + its one-tap Include appear.
    await userEvent.click(toggle);
    expect(screen.getByText("Sleep Apnea")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: /Include Sleep Apnea in my claim/ })).toBeInTheDocument();
  });

  it("clicking Include calls the mutation with (id, false) and refetches", async () => {
    render(
      <ConditionsList
        conditions={[cond({ id: 2, name: "Sleep Apnea", excludedFromClaim: true })]}
      />,
    );
    await userEvent.click(screen.getByRole("button", { name: /Not filing \(1\)/ }));
    await userEvent.click(screen.getByRole("button", { name: /Include Sleep Apnea in my claim/ }));

    expect(setConditionExcluded).toHaveBeenCalledWith(2, false);
    expect(refresh).toHaveBeenCalledTimes(1);
  });

  it("the row overflow menu excludes an included condition with (id, true)", async () => {
    render(<ConditionsList conditions={[cond({ id: 1, name: "PTSD" })]} />);

    // Open the row's overflow menu, then "Don't include in my claim".
    await userEvent.click(screen.getAllByRole("button", { name: /More options for PTSD/ })[0]);
    await userEvent.click(
      screen.getAllByRole("menuitem", { name: /Don.t include in my claim/ })[0],
    );

    expect(setConditionExcluded).toHaveBeenCalledWith(1, true);
    expect(refresh).toHaveBeenCalledTimes(1);
  });

  it("calls onChanged (native refetch) instead of router.refresh when provided", async () => {
    const onChanged = vi.fn();
    render(
      <ConditionsList
        conditions={[cond({ id: 2, name: "Sleep Apnea", excludedFromClaim: true })]}
        onChanged={onChanged}
      />,
    );
    await userEvent.click(screen.getByRole("button", { name: /Not filing \(1\)/ }));
    await userEvent.click(screen.getByRole("button", { name: /Include Sleep Apnea in my claim/ }));

    expect(setConditionExcluded).toHaveBeenCalledWith(2, false);
    expect(onChanged).toHaveBeenCalledTimes(1);
    expect(refresh).not.toHaveBeenCalled();
  });

  it("shows an error and does not refetch when the mutation fails", async () => {
    setConditionExcluded.mockResolvedValue({ ok: false, status: 500 } as Response);
    render(<ConditionsList conditions={[cond({ id: 1, name: "PTSD" })]} />);
    await userEvent.click(screen.getAllByRole("button", { name: /More options for PTSD/ })[0]);
    await userEvent.click(
      screen.getAllByRole("menuitem", { name: /Don.t include in my claim/ })[0],
    );

    expect(await screen.findByRole("alert")).toBeInTheDocument();
    expect(refresh).not.toHaveBeenCalled();
  });
});
