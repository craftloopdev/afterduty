import { describe, it, expect, vi, beforeEach } from "vitest";
import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { StepsPanel } from "./StepsPanel";
import type { StepVM } from "@/lib/models/vm";

// Checkable steps PATCH through the mutations facade (durable user_gap_state).
const setGapStatus = vi.fn();
vi.mock("@/lib/api/mutations", () => ({
  setGapStatus: (...args: unknown[]) => setGapStatus(...args),
}));

const ok = () => new Response(JSON.stringify({ ok: true }), { status: 200 });
const notFound = () => new Response(JSON.stringify({ error: "not_found" }), { status: 404 });

function step(overrides: Partial<StepVM> = {}): StepVM {
  return {
    id: "99-Nexusletter-0",
    condId: 99,
    cond: "Sleep Apnea",
    type: "Nexus letter",
    gap: "Missing nexus letter",
    why: "No medical opinion linking your sleep study to service.",
    suggest: "Ask your pulmonologist for a nexus letter.",
    impact: "Confirms the nexus leg.",
    impactStrong: true,
    priority: "high",
    status: "open",
    gapIndex: 0,
    triadLeg: "nexus",
    targetRating: 50,
    estimatedTime: "2–4 weeks",
    estimatedCostUsd: 800,
    ...overrides,
  };
}

beforeEach(() => {
  setGapStatus.mockReset();
});

describe("StepsPanel empty states", () => {
  it("says 'all caught up' only when the gap re-check is NOT pending", () => {
    render(<StepsPanel steps={[]} highCount={0} />);
    expect(screen.getByText(/all caught up/i)).toBeInTheDocument();
  });

  // P0-5: during a re-analysis the new generation briefly has no gap data —
  // an empty list must read as "re-checking", never a false completion.
  it("says 'Re-checking your next steps…' while gapAnalysisPending", () => {
    render(<StepsPanel steps={[]} highCount={0} gapAnalysisPending />);
    expect(screen.getByText(/re-checking your next steps/i)).toBeInTheDocument();
    expect(screen.queryByText(/all caught up/i)).not.toBeInTheDocument();
  });
});

describe("StepsPanel — checkable steps (P1-6)", () => {
  it("'Mark done' PATCHes resolved and optimistically moves the row to Done / dismissed", async () => {
    setGapStatus.mockResolvedValue(ok());
    const user = userEvent.setup();
    render(<StepsPanel steps={[step()]} highCount={1} />);

    await user.click(screen.getByRole("button", { name: /mark "missing nexus letter" done/i }));

    expect(setGapStatus).toHaveBeenCalledWith(99, 0, "resolved");
    // Out of the open list, into the collapsed section.
    const toggle = await screen.findByRole("button", { name: /done \/ dismissed \(1\)/i });
    expect(screen.getByText(/all caught up/i)).toBeInTheDocument();
    await user.click(toggle);
    const done = screen.getByRole("list");
    expect(within(done).getByText("Missing nexus letter")).toBeInTheDocument();
    expect(within(done).getByText(/marked done/i)).toBeInTheDocument();
  });

  it("'Doesn't apply' PATCHes dismissed", async () => {
    setGapStatus.mockResolvedValue(ok());
    const user = userEvent.setup();
    render(<StepsPanel steps={[step()]} highCount={1} />);

    await user.click(
      screen.getByRole("button", { name: /"missing nexus letter" doesn't apply/i }),
    );
    expect(setGapStatus).toHaveBeenCalledWith(99, 0, "dismissed");
    expect(await screen.findByRole("button", { name: /done \/ dismissed \(1\)/i })).toBeInTheDocument();
  });

  it("ROLLS BACK the optimistic move and says so when the PATCH fails", async () => {
    setGapStatus.mockResolvedValue(notFound());
    const user = userEvent.setup();
    render(<StepsPanel steps={[step()]} highCount={1} />);

    await user.click(screen.getByRole("button", { name: /mark "missing nexus letter" done/i }));

    // Back in the open list, no Done section, honest error.
    await waitFor(() =>
      expect(screen.getByRole("alert")).toHaveTextContent(/couldn't save/i),
    );
    expect(screen.getByRole("button", { name: /mark "missing nexus letter" done/i })).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /done \/ dismissed/i })).not.toBeInTheDocument();
  });

  it("Undo reopens a resolved step (PATCH back to open)", async () => {
    setGapStatus.mockResolvedValue(ok());
    const user = userEvent.setup();
    render(<StepsPanel steps={[step({ status: "resolved" })]} highCount={0} />);

    // Server-resolved step starts in the collapsed section.
    await user.click(screen.getByRole("button", { name: /done \/ dismissed \(1\)/i }));
    await user.click(screen.getByRole("button", { name: /reopen "missing nexus letter"/i }));

    expect(setGapStatus).toHaveBeenCalledWith(99, 0, "open");
    // Back in the open list with its affordances.
    expect(
      await screen.findByRole("button", { name: /mark "missing nexus letter" done/i }),
    ).toBeInTheDocument();
  });

  it("hides the affordances on legacy steps without a gapIndex", () => {
    render(<StepsPanel steps={[step({ gapIndex: null })]} highCount={1} />);
    expect(screen.getByText("Missing nexus letter")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /mark .* done/i })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /doesn't apply/i })).not.toBeInTheDocument();
  });
});

describe("StepsPanel — step detail action script (P1-18)", () => {
  it("pipeline-shaped step with a known type renders the 'What to do' block + Learn more link", async () => {
    const user = userEvent.setup();
    render(
      <StepsPanel
        steps={[step({ type: "c_and_p_exam_request", gap: "Attend your C&P exam" })]}
        highCount={1}
      />,
    );

    // The main row (not the Mark-done affordance) opens the detail modal.
    await user.click(screen.getByRole("button", { name: /^attend your c&p exam/i }));

    const dialog = screen.getByRole("dialog", { name: /next step/i });
    expect(within(dialog).getByText("What to do")).toBeInTheDocument();
    expect(within(dialog).getByText(/VA orders it after you file/i)).toBeInTheDocument();
    expect(within(dialog).getByText(/free — va pays/i)).toBeInTheDocument();
    expect(within(dialog).getByRole("link", { name: /learn more/i })).toHaveAttribute(
      "href",
      "/learn/cp-exam",
    );
  });
});
