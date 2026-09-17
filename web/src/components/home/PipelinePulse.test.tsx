import { describe, it, expect, vi, beforeEach, afterEach } from "vitest";
import { act, render, screen } from "@testing-library/react";
import { armAnalysisPulse, disarmAnalysisPulse } from "@/lib/jobs";
import { PipelinePulse } from "./PipelinePulse";

// PipelinePulse falls back to router.refresh() when no onComplete is passed.
const refresh = vi.fn();
vi.mock("next/navigation", () => ({ useRouter: () => ({ refresh }) }));

const POLL_MS = 12_000;

function jobsResponse(activeCount: number) {
  return new Response(JSON.stringify({ active_count: activeCount }), {
    status: 200,
    headers: { "content-type": "application/json" },
  });
}

/** Queue of jobs snapshots the mocked BFF returns, then repeats the last. */
function stubJobs(counts: number[]) {
  let i = 0;
  const fetchMock = vi.fn(async () => jobsResponse(counts[Math.min(i++, counts.length - 1)]));
  vi.stubGlobal("fetch", fetchMock);
  return fetchMock;
}

async function tick(ms = POLL_MS) {
  // Advance the interval, then flush the fetch promise chain.
  await act(async () => {
    await vi.advanceTimersByTimeAsync(ms);
  });
}

beforeEach(() => {
  vi.useFakeTimers();
  refresh.mockReset();
  disarmAnalysisPulse(); // clear both the storage flag and in-memory fallback
});

afterEach(() => {
  vi.unstubAllGlobals();
  vi.useRealTimers();
});

describe("PipelinePulse — banner", () => {
  it("renders nothing (and never polls) when disabled — free users' pipeline is gated", async () => {
    const fetchMock = stubJobs([1]);
    render(<PipelinePulse variant="banner" initialActive enabled={false} />);
    await tick();
    expect(fetchMock).not.toHaveBeenCalled();
    expect(screen.queryByRole("status")).not.toBeInTheDocument();
  });

  it("renders nothing when idle and not armed", async () => {
    const fetchMock = stubJobs([0]);
    render(<PipelinePulse variant="banner" />);
    await tick();
    expect(fetchMock).not.toHaveBeenCalled();
    expect(screen.queryByText(/analyzing your new evidence/i)).not.toBeInTheDocument();
  });

  it("shows the banner while a run is active, then completes with an acknowledgment refresh", async () => {
    const onComplete = vi.fn();
    stubJobs([1, 1, 0, 0]);
    render(<PipelinePulse variant="banner" initialActive onComplete={onComplete} />);

    await tick(0); // initial poll: active
    expect(screen.getByText(/analyzing your new evidence/i)).toBeInTheDocument();

    await tick(); // still active
    expect(onComplete).not.toHaveBeenCalled();

    await tick(); // first idle — debounced, no completion yet
    expect(onComplete).not.toHaveBeenCalled();

    await tick(); // second consecutive idle — run complete
    expect(onComplete).toHaveBeenCalledTimes(1);
    expect(screen.queryByText(/analyzing your new evidence/i)).not.toBeInTheDocument();
  });

  it("falls back to router.refresh() when no onComplete is provided", async () => {
    stubJobs([1, 0, 0]);
    render(<PipelinePulse variant="banner" initialActive />);
    await tick(0);
    await tick();
    await tick();
    expect(refresh).toHaveBeenCalledTimes(1);
  });

  it("stands down quietly when armed but nothing was ever running (stale flag)", async () => {
    const onComplete = vi.fn();
    stubJobs([0, 0]);
    render(<PipelinePulse variant="banner" initialActive onComplete={onComplete} />);
    await tick(0);
    await tick();
    // No false acknowledgment, no refresh loop.
    expect(onComplete).not.toHaveBeenCalled();
    expect(refresh).not.toHaveBeenCalled();
    expect(screen.queryByText(/analyzing your new evidence/i)).not.toBeInTheDocument();
  });

  it("arms when an upload succeeds (UploadCard calls armAnalysisPulse)", async () => {
    stubJobs([1]);
    render(<PipelinePulse variant="banner" />);
    expect(screen.queryByText(/analyzing your new evidence/i)).not.toBeInTheDocument();

    await act(async () => {
      armAnalysisPulse();
    });
    await tick(0);
    expect(screen.getByText(/analyzing your new evidence/i)).toBeInTheDocument();
  });
});

describe("PipelinePulse — first-analysis screen", () => {
  it("renders honest indeterminate copy — no percentage anywhere", async () => {
    stubJobs([1]);
    render(<PipelinePulse variant="screen" initialActive />);
    await tick(0);
    expect(screen.getByText(/analyzing your records/i)).toBeInTheDocument();
    expect(screen.getByText(/usually takes 5–15 minutes/i)).toBeInTheDocument();
    const bar = screen.getByRole("progressbar");
    expect(bar).not.toHaveAttribute("aria-valuenow"); // indeterminate, not a fake 5%
    expect(screen.queryByText(/%/)).not.toBeInTheDocument();
  });

  it("shows the per-stage checklist from the jobs snapshot", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn(async () =>
        new Response(
          JSON.stringify({
            active_count: 1,
            extraction: { pending: 0, processing: 0, processed: 2, errored: 0 },
            synthesis: { state: "running" },
            gap_analysis: { state: "idle" },
          }),
          { status: 200, headers: { "content-type": "application/json" } },
        ),
      ),
    );
    render(<PipelinePulse variant="screen" initialActive />);
    await tick(0);
    expect(screen.getByText(/reading your documents/i)).toBeInTheDocument();
    expect(screen.getByText(/identifying conditions/i)).toBeInTheDocument();
    expect(screen.getByText(/finding your evidence gaps/i)).toBeInTheDocument();
  });

  it("refreshes when the observed run completes so results arrive together", async () => {
    const onComplete = vi.fn();
    stubJobs([1, 0, 0]);
    render(<PipelinePulse variant="screen" initialActive onComplete={onComplete} />);
    await tick(0);
    await tick();
    await tick();
    expect(onComplete).toHaveBeenCalledTimes(1);
  });
});
