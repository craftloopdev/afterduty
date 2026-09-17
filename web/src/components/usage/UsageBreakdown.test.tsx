import { describe, it, expect } from "vitest";
import { render, screen } from "@testing-library/react";
import { UsageBreakdown } from "./UsageBreakdown";
import type { UsageBreakdownVM } from "@/lib/models/vm";

const base: UsageBreakdownVM = {
  periodStart: "2026-07-01T00:00:00Z",
  periodEnd: "2026-08-01T00:00:00Z",
  resetAt: "2026-08-01T00:00:00Z",
  limitCents: 800,
  spentCents: 342,
  remainingCents: 458,
  atLimit: false,
  unlimited: false,
  byFeature: [
    { feature: "gap_analysis", label: "Gap analysis", cents: 180, calls: 4 },
    { feature: "synthesis", label: "Condition synthesis", cents: 120, calls: 2 },
    { feature: "chat", label: "Ask AI chat", cents: 42, calls: 7 },
  ],
};

describe("UsageBreakdown", () => {
  it("renders spent-of-cap, reset date, and a bar per feature with server dollars", () => {
    render(<UsageBreakdown data={base} />);
    expect(screen.getByText(/\$3\.42/)).toBeInTheDocument();
    expect(screen.getByText(/of \$8\.00 used/)).toBeInTheDocument();
    expect(screen.getByText(/resets August 1, 2026/)).toBeInTheDocument();
    expect(screen.getByText("Gap analysis")).toBeInTheDocument();
    expect(screen.getByText(/\$1\.80/)).toBeInTheDocument();
    expect(screen.getByText(/4 calls/)).toBeInTheDocument();
    expect(screen.getByText(/7 calls/)).toBeInTheDocument();
  });

  it("renders the unlimited state without a $-1 cap", () => {
    render(<UsageBreakdown data={{ ...base, unlimited: true, limitCents: -1, remainingCents: 0 }} />);
    expect(screen.getByText(/Unlimited/)).toBeInTheDocument();
    expect(screen.queryByText(/-1/)).not.toBeInTheDocument();
    expect(screen.queryByText(/\$-0\.01/)).not.toBeInTheDocument();
  });

  it("renders an empty state when nothing was spent", () => {
    render(<UsageBreakdown data={{ ...base, spentCents: 0, byFeature: [] }} />);
    expect(screen.getByText("No AI usage yet this month.")).toBeInTheDocument();
  });

  it("shows the at-limit note", () => {
    render(<UsageBreakdown data={{ ...base, spentCents: 800, remainingCents: 0, atLimit: true }} />);
    expect(screen.getByText(/reached this month's AI limit/)).toBeInTheDocument();
  });

  it("renders nothing when the load failed (null)", () => {
    const { container } = render(<UsageBreakdown data={null} />);
    expect(container).toBeEmptyDOMElement();
  });
});
