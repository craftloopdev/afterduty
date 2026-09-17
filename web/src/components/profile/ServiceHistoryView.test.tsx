import { describe, it, expect, vi, beforeEach } from "vitest";
import { render, screen, within, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { ServiceHistoryView } from "./ServiceHistoryView";
import type { ServicePeriodVM } from "@/lib/models/vm";

vi.mock("next/link", () => ({
  default: ({
    href,
    children,
    ...rest
  }: React.PropsWithChildren<{ href: string } & Record<string, unknown>>) => (
    <a href={href} {...rest}>
      {children}
    </a>
  ),
}));

const refresh = vi.fn();
vi.mock("next/navigation", () => ({
  useRouter: () => ({ refresh: () => refresh() }),
}));

const setServiceHistoryOverride = vi.fn();
const clearServiceHistoryOverride = vi.fn();
vi.mock("@/lib/api/mutations", () => ({
  setServiceHistoryOverride: (...a: unknown[]) => setServiceHistoryOverride(...a),
  clearServiceHistoryOverride: (...a: unknown[]) => clearServiceHistoryOverride(...a),
}));

beforeEach(() => {
  refresh.mockReset();
  setServiceHistoryOverride.mockReset();
  clearServiceHistoryOverride.mockReset();
  setServiceHistoryOverride.mockResolvedValue({ ok: true, status: 200 });
  clearServiceHistoryOverride.mockResolvedValue({ ok: true, status: 200 });
});

// A reconciled Active period (two sources: DD-214 adopted, personnel record not)
// + a still-running Guard period with no reconciliation receipts.
const RECONCILED: ServicePeriodVM = {
  branch: "Army",
  component: "active",
  startDate: "2003-06-10",
  endDate: "2007-06-09",
  mos: "11B",
  rank: "SGT",
  source: "documents",
  sources: [
    {
      evidenceId: 101,
      docType: "DD-214",
      authorityRank: 100,
      rawBranch: "Army",
      rawStart: "2003-06-10",
      rawEnd: "2007-06-09",
      rawMos: "11B",
      rawRank: "SGT",
    },
    {
      evidenceId: 102,
      docType: "personnel_record",
      authorityRank: 40,
      rawBranch: "U.S. Army",
      rawStart: "2003-06-01",
      rawEnd: "2007-06-09",
      rawMos: "11 Bravo",
      rawRank: "Sergeant",
    },
  ],
  reasoning:
    "Merged 2 records for this enlistment. Dates, branch, MOS, and rank taken from the DD-214.",
  totalYears: 4,
  clusterKey: "Army|active|2003",
};

const UNRECONCILED: ServicePeriodVM = {
  branch: "Army National Guard",
  component: "guard",
  startDate: "2008-03-01",
  endDate: null,
  mos: null,
  rank: "SSG",
  source: "documents",
  sources: null,
  reasoning: null,
  totalYears: 4,
  clusterKey: null,
};

describe("ServiceHistoryView — one row per conclusion", () => {
  it("renders a row per reconciled conclusion with the conclusion text + source count", () => {
    render(<ServiceHistoryView periods={[UNRECONCILED, RECONCILED]} />);
    expect(screen.getByText("Army National Guard · National Guard")).toBeInTheDocument();
    expect(screen.getByText("Army · Active Duty")).toBeInTheDocument();
    // Source count control: 2 for the reconciled row, 1 (default) for the bare one.
    expect(screen.getByText(/2 sources/)).toBeInTheDocument();
    expect(screen.getByText(/1 source\b/)).toBeInTheDocument();
  });

  it("shows the corrected total (sum of reconciled totalYears) in the header", () => {
    render(<ServiceHistoryView periods={[UNRECONCILED, RECONCILED]} />);
    // 4 + 4 = 8, NOT the inflated pre-reconciliation figure.
    expect(screen.getByText(/About/i).textContent).toMatch(/8\s+years/);
  });

  it("renders the empty state when there are no periods", () => {
    render(<ServiceHistoryView periods={[]} />);
    expect(screen.getByText(/no service history yet/i)).toBeInTheDocument();
    expect(screen.getByText(/upload your dd-214/i)).toBeInTheDocument();
  });
});

describe("ServiceHistoryView — evidence/reasoning modal", () => {
  it("opens a modal with per-source Facts, ✓ on adopted values, and the reasoning", async () => {
    render(<ServiceHistoryView periods={[RECONCILED]} />);
    await userEvent.click(screen.getByRole("button", { name: /army — view sources/i }));

    const dialog = screen.getByRole("dialog");
    // Both sources appear, humanized with authority.
    expect(within(dialog).getByText("DD-214 · authoritative")).toBeInTheDocument();
    expect(within(dialog).getByText("Personnel record · supporting")).toBeInTheDocument();
    // Reasoning renders.
    expect(within(dialog).getByText(/merged 2 records/i)).toBeInTheDocument();

    // The DD-214's rank value was adopted → carries the a11y "used in the final
    // record" marker; the personnel record's "Sergeant" was NOT.
    const adoptedSgt = within(dialog).getByText("SGT").closest("[data-adopted='1']");
    expect(adoptedSgt).not.toBeNull();
    const rejectedSergeant = within(dialog).getByText("Sergeant").closest("[data-adopted='1']");
    expect(rejectedSergeant).toBeNull();
  });

  it("gracefully shows 'Reconciled from your documents' when a period has no sources", async () => {
    render(<ServiceHistoryView periods={[UNRECONCILED]} />);
    await userEvent.click(screen.getByRole("button", { name: /national guard — view sources/i }));
    const dialog = screen.getByRole("dialog");
    expect(within(dialog).getByText(/reconciled from your documents/i)).toBeInTheDocument();
    // No reasoning section when reasoning is absent.
    expect(within(dialog).queryByText(/how we reconciled this/i)).not.toBeInTheDocument();
  });
});

// A conclusion the veteran already corrected (backend appended "Corrected by you.").
const CORRECTED: ServicePeriodVM = {
  ...RECONCILED,
  endDate: "2010-06-09",
  reasoning: RECONCILED.reasoning + " Corrected by you.",
};

describe("ServiceHistoryView — veteran override (Service History P3)", () => {
  it("offers the correct-it affordance only when a clusterKey is present", async () => {
    render(<ServiceHistoryView periods={[UNRECONCILED, RECONCILED]} />);
    // Reconciled row (has clusterKey) → affordance present.
    await userEvent.click(screen.getByRole("button", { name: /army — view sources/i }));
    expect(
      within(screen.getByRole("dialog")).getByText(/this is wrong — correct it/i),
    ).toBeInTheDocument();
    await userEvent.keyboard("{Escape}");

    // Unreconciled row (clusterKey null) → NO affordance.
    await userEvent.click(screen.getByRole("button", { name: /national guard — view sources/i }));
    expect(
      within(screen.getByRole("dialog")).queryByText(/this is wrong — correct it/i),
    ).not.toBeInTheDocument();
  });

  it("submits the changed fields through the override mutation and refetches", async () => {
    render(<ServiceHistoryView periods={[RECONCILED]} />);
    await userEvent.click(screen.getByRole("button", { name: /army — view sources/i }));
    const dialog = screen.getByRole("dialog");

    await userEvent.click(within(dialog).getByText(/this is wrong — correct it/i));
    // Change the rank, then save.
    const rank = within(dialog).getByLabelText(/rank/i);
    await userEvent.clear(rank);
    await userEvent.type(rank, "CPO");
    await userEvent.click(within(dialog).getByRole("button", { name: /save correction/i }));

    await waitFor(() => expect(setServiceHistoryOverride).toHaveBeenCalledTimes(1));
    const payload = setServiceHistoryOverride.mock.calls[0][0];
    expect(payload).toMatchObject({ clusterKey: "Army|active|2003", rank: "CPO" });
    // Untouched fields aren't sent (only the changed one + the key).
    expect(payload.branch).toBeUndefined();
    expect(refresh).toHaveBeenCalledTimes(1);
  });

  it("blocks an empty save (no field changed)", async () => {
    render(<ServiceHistoryView periods={[RECONCILED]} />);
    await userEvent.click(screen.getByRole("button", { name: /army — view sources/i }));
    const dialog = screen.getByRole("dialog");
    await userEvent.click(within(dialog).getByText(/this is wrong — correct it/i));
    await userEvent.click(within(dialog).getByRole("button", { name: /save correction/i }));

    expect(within(dialog).getByRole("alert")).toHaveTextContent(/change at least one field/i);
    expect(setServiceHistoryOverride).not.toHaveBeenCalled();
  });

  it("shows the 'Corrected by you' marker + offers Undo on an overridden conclusion", async () => {
    render(<ServiceHistoryView periods={[CORRECTED]} />);
    await userEvent.click(screen.getByRole("button", { name: /army — view sources/i }));
    const dialog = screen.getByRole("dialog");
    // The badge (exact "Corrected by you", no trailing period — the reasoning
    // paragraph also mentions it, so match exactly).
    expect(within(dialog).getByText("Corrected by you")).toBeInTheDocument();

    await userEvent.click(within(dialog).getByRole("button", { name: /undo correction/i }));
    await waitFor(() => expect(clearServiceHistoryOverride).toHaveBeenCalledWith("Army|active|2003"));
    expect(refresh).toHaveBeenCalledTimes(1);
  });

  it("passes onSaved (native refetch) instead of router.refresh when provided", async () => {
    const onSaved = vi.fn();
    render(<ServiceHistoryView periods={[RECONCILED]} onSaved={onSaved} />);
    await userEvent.click(screen.getByRole("button", { name: /army — view sources/i }));
    const dialog = screen.getByRole("dialog");
    await userEvent.click(within(dialog).getByText(/this is wrong — correct it/i));
    const rank = within(dialog).getByLabelText(/rank/i);
    await userEvent.clear(rank);
    await userEvent.type(rank, "CPO");
    await userEvent.click(within(dialog).getByRole("button", { name: /save correction/i }));

    await waitFor(() => expect(onSaved).toHaveBeenCalledTimes(1));
    expect(refresh).not.toHaveBeenCalled();
  });
});
