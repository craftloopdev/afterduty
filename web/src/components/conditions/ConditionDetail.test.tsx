import { describe, it, expect, vi, beforeEach } from "vitest";
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { ConditionDetail } from "./ConditionDetail";
import { toCondition } from "@/lib/adapters/condition";
import type { ConditionResponse } from "@/lib/models/api";

// ConditionDetail calls useRouter().refresh() after an exclude/include toggle.
const refresh = vi.fn();
vi.mock("next/navigation", () => ({ useRouter: () => ({ refresh }) }));

// The exclude/include mutation is mocked so the toggle tests assert the exact
// (id, excluded) call without a real network hit.
const setConditionExcluded = vi.fn();
vi.mock("@/lib/api/mutations", () => ({
  setConditionExcluded: (...args: unknown[]) => setConditionExcluded(...args),
}));

// P1-5 (null-vs-0 rating, hidden missing confidence) and P1-2 (presumptive
// nexus renders "Covered by presumption", never a strengthen-the-nexus
// callout). Raw DTOs flow through the REAL adapter so these tests pin the
// whole render path, not a hand-built VM.

const strong = { status: "strong", evidence: ["on file"] };
const base: ConditionResponse = {
  id: 7,
  name: "Sleep Apnea",
  vasrdCode: "6847",
  bodySystem: "Respiratory System",
  estimatedRating: 50,
  confidence: 0.9,
  triadDiagnosis: strong,
  triadInService: strong,
  triadNexus: strong,
};

const renderDetail = (raw: Partial<ConditionResponse>) =>
  render(<ConditionDetail cond={toCondition({ ...base, ...raw })} relatedStep={null} />);

describe("ConditionDetail — rating null-vs-0 (P1-5)", () => {
  it("renders a null rating as 'Not yet rated' (never a fake 0%)", () => {
    renderDetail({ estimatedRating: null });
    expect(screen.getByText("Not yet rated")).toBeInTheDocument();
    expect(screen.queryByText(/still worth filing/)).not.toBeInTheDocument();
  });

  it("renders a true 0% with the still-worth-filing explainer", () => {
    renderDetail({ estimatedRating: 0 });
    expect(screen.getByText("0%")).toBeInTheDocument();
    expect(screen.getByText(/0% — still worth filing/)).toBeInTheDocument();
    expect(screen.queryByText("Not yet rated")).not.toBeInTheDocument();
  });

  it("renders a positive rating plainly, without the zero explainer", () => {
    renderDetail({});
    expect(screen.getByText("50%")).toBeInTheDocument();
    expect(screen.queryByText(/still worth filing/)).not.toBeInTheDocument();
  });
});

describe("ConditionDetail — confidence (P1-5)", () => {
  it("shows the badge when the pipeline sent a confidence", () => {
    renderDetail({ confidence: 0.9 });
    expect(screen.getByText("confidence")).toBeInTheDocument();
    expect(screen.getByText("90")).toBeInTheDocument();
  });

  it("renders NOTHING for a missing confidence — never '0% confidence'", () => {
    renderDetail({ confidence: null });
    expect(screen.queryByText("confidence")).not.toBeInTheDocument();
  });

  it("treats the DTO's primitive-double 0.0 as missing too", () => {
    renderDetail({ confidence: 0 });
    expect(screen.queryByText("confidence")).not.toBeInTheDocument();
  });
});

describe("ConditionDetail — presumptive nexus (P1-2, defensive web half)", () => {
  it("renders 'Covered by presumption' on the nexus leg whatever the pipeline scored", () => {
    renderDetail({
      presumptiveBasis: "PACT Act",
      triadNexus: { status: "missing", evidence: [] },
    });
    expect(screen.getByText("Covered by presumption")).toBeInTheDocument();
    expect(screen.getByText(/Covered by presumption \(PACT Act\)/)).toBeInTheDocument();
    expect(screen.getByText(/no nexus letter is needed/)).toBeInTheDocument();
    // The pipeline's scare tag must not render on the covered leg.
    expect(screen.queryByText("Missing")).not.toBeInTheDocument();
  });

  it("never points the strengthen callout at a presumptive nexus", () => {
    renderDetail({
      presumptiveBasis: "PACT Act",
      triadNexus: { status: "missing", evidence: [] },
    });
    expect(screen.queryByText("Strengthen this claim")).not.toBeInTheDocument();
  });

  it("still calls out a genuinely weak NON-nexus leg on a presumptive condition", () => {
    renderDetail({
      presumptiveBasis: "PACT Act",
      triadInService: { status: "weak", evidence: [] },
      triadNexus: { status: "missing", evidence: [] },
    });
    expect(screen.getByText("Strengthen this claim")).toBeInTheDocument();
    expect(screen.getByText(/The in-service leg is the weakest/)).toBeInTheDocument();
  });

  it("non-presumptive: a weak nexus still gets the honest callout", () => {
    renderDetail({ triadNexus: { status: "missing", evidence: [] } });
    expect(screen.getByText("Missing")).toBeInTheDocument();
    expect(screen.getByText(/The nexus leg is the weakest/)).toBeInTheDocument();
    expect(screen.queryByText("Covered by presumption")).not.toBeInTheDocument();
  });
});

describe("ConditionDetail — rating honesty (needs-PFT note + tempered confidence)", () => {
  it("renders the amber 'needs a PFT' note when the backend tags a missing objective test", () => {
    renderDetail({
      vasrdCode: "6602",
      ratingEvidenceNote:
        "Estimate — needs a pulmonary function test (PFT/FEV-1) to confirm. This rating is inferred from the other evidence on file.",
    });
    expect(
      screen.getByText(/needs a pulmonary function test \(PFT\/FEV-1\) to confirm/),
    ).toBeInTheDocument();
    // Reads as an estimate, not an alarm.
    expect(screen.getByText(/^Estimate —/)).toBeInTheDocument();
  });

  it("displays the TEMPERED confidence the backend sent (below the LLM self-report)", () => {
    // Backend already tempered 0.88 → 0.53 for the missing PFT; the DTO carries the
    // tempered value, so the badge shows 53, not 88.
    renderDetail({
      vasrdCode: "6602",
      confidence: 0.53,
      ratingEvidenceNote: "Estimate — needs a pulmonary function test (PFT/FEV-1) to confirm.",
    });
    expect(screen.getByText("confidence")).toBeInTheDocument();
    expect(screen.getByText("53")).toBeInTheDocument();
    expect(screen.queryByText("88")).not.toBeInTheDocument();
  });

  it("renders NO note when the backend sent none (measure present or unmapped code)", () => {
    renderDetail({ vasrdCode: "6602", confidence: 0.88 }); // no ratingEvidenceNote
    expect(screen.queryByText(/needs a pulmonary function test/)).not.toBeInTheDocument();
    expect(screen.queryByText(/^Estimate —/)).not.toBeInTheDocument();
    // Un-tempered confidence flows straight through.
    expect(screen.getByText("88")).toBeInTheDocument();
  });
});

describe("fixbar empty-content guards (blank green pill bug 2026-07-02)", () => {
  const step = {
    id: "s1", condId: 1, cond: "PTSD", type: "Nexus letter",
    gap: "Missing nexus letter", why: "w", suggest: "Ask your doctor", impact: "+20%",
    impactStrong: true, priority: "high" as const, status: "open",
    gapIndex: 0, triadLeg: "nexus", targetRating: 70, estimatedTime: null, estimatedCostUsd: null,
  };

  it("renders the fixbar with impact pill when the step has content", () => {
    render(<ConditionDetail cond={toCondition(base)} relatedStep={step} />);
    expect(screen.getByText("Missing nexus letter")).toBeInTheDocument();
    expect(screen.getByText("+20%")).toBeInTheDocument();
  });

  it("empty impact + target rating renders the deterministic chip, not a blank blob", () => {
    render(<ConditionDetail cond={toCondition(base)} relatedStep={{ ...step, impact: "" }} />);
    expect(screen.getByText("Missing nexus letter")).toBeInTheDocument();
    expect(screen.getByText("\u2192 70%")).toBeInTheDocument();
  });

  it("hides the impact pill entirely when there is nothing to chip", () => {
    render(
      <ConditionDetail
        cond={toCondition(base)}
        relatedStep={{ ...step, impact: "", targetRating: null }}
      />,
    );
    expect(document.querySelector("[class*='fixbarImpact']")).toBeNull();
  });

  it("skips the fixbar entirely when the step has no gap text", () => {
    render(<ConditionDetail cond={toCondition(base)} relatedStep={{ ...step, gap: "", impact: "" }} />);
    expect(document.querySelector("[class*='fixbar']")).toBeNull();
  });
});

describe("ConditionDetail — 'Don't include in my claim' toggle", () => {
  beforeEach(() => {
    refresh.mockReset();
    setConditionExcluded.mockReset();
    setConditionExcluded.mockResolvedValue({ ok: true } as Response);
  });

  it("an INCLUDED condition offers 'Don't include' and excludes with (id, true)", async () => {
    render(<ConditionDetail cond={toCondition(base)} relatedStep={null} />);
    // No "Not filing" hero pill when included.
    expect(screen.queryByText("Not filing")).not.toBeInTheDocument();

    await userEvent.click(
      screen.getByRole("button", { name: /Don.t include in my claim/ }),
    );
    expect(setConditionExcluded).toHaveBeenCalledWith(7, true);
    expect(refresh).toHaveBeenCalledTimes(1);
  });

  it("an EXCLUDED condition shows the 'Not filing' pill and re-includes with (id, false)", async () => {
    render(
      <ConditionDetail
        cond={toCondition({ ...base, excludedFromClaim: true })}
        relatedStep={null}
      />,
    );
    expect(screen.getByText("Not filing")).toBeInTheDocument();

    await userEvent.click(
      screen.getByRole("button", { name: /Include in my claim/ }),
    );
    expect(setConditionExcluded).toHaveBeenCalledWith(7, false);
    expect(refresh).toHaveBeenCalledTimes(1);
  });

  it("uses onChanged (native refetch) instead of router.refresh when provided", async () => {
    const onChanged = vi.fn();
    render(<ConditionDetail cond={toCondition(base)} relatedStep={null} onChanged={onChanged} />);
    await userEvent.click(
      screen.getByRole("button", { name: /Don.t include in my claim/ }),
    );
    expect(onChanged).toHaveBeenCalledTimes(1);
    expect(refresh).not.toHaveBeenCalled();
  });

  it("surfaces an error and does not refetch when the mutation fails", async () => {
    setConditionExcluded.mockResolvedValue({ ok: false, status: 500 } as Response);
    render(<ConditionDetail cond={toCondition(base)} relatedStep={null} />);
    await userEvent.click(
      screen.getByRole("button", { name: /Don.t include in my claim/ }),
    );
    expect(await screen.findByRole("alert")).toBeInTheDocument();
    expect(refresh).not.toHaveBeenCalled();
  });
});
