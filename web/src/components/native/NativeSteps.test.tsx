import { describe, it, expect, vi, beforeEach } from "vitest";
import { render, screen } from "@testing-library/react";
import type { NextStepsVM, SubscriptionState } from "@/lib/models/vm";

// Native Steps empty-state truthfulness: (a) P1-16 — a free user with stored
// documents gets the Pro boundary + upgrade link, an outage gets the neutral
// default; (b) parity with the web page — while the gap re-check is pending an
// empty list renders the honest "Re-checking…" tabs, never "add evidence".

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
vi.mock("next/navigation", () => ({
  useRouter: () => ({ refresh: vi.fn(), push: vi.fn(), replace: vi.fn() }),
}));

const loadNextSteps = vi.fn();

vi.mock("@/lib/api/endpoints.native", async () => {
  const { useLoader } = await vi.importActual<typeof import("@/lib/hooks/useLoader")>(
    "@/lib/hooks/useLoader",
  );
  return {
    useLoader,
    loadNextSteps: () => loadNextSteps(),
  };
});

type StepsData = NextStepsVM & { subState: SubscriptionState; documentsCount: number };

function vm(overrides: Partial<StepsData> = {}): StepsData {
  return {
    steps: [],
    highCount: 0,
    medCount: 0,
    readyCount: 0,
    scenarios: [],
    estimateUnavailable: false,
    gapAnalysisPending: false,
    pipelineActive: false,
    isPro: false,
    subState: "free",
    documentsCount: 0,
    ...overrides,
  };
}

beforeEach(() => {
  loadNextSteps.mockReset();
});

async function renderNativeSteps() {
  const { NativeSteps } = await import("./NativeSteps");
  return render(<NativeSteps />);
}

describe("NativeSteps empty state", () => {
  it("free user with documents: Pro boundary + upgrade link (P1-16)", async () => {
    loadNextSteps.mockResolvedValue(vm({ subState: "free", documentsCount: 2 }));
    await renderNativeSteps();

    expect(await screen.findByText("Your documents are stored")).toBeInTheDocument();
    expect(screen.getByRole("link", { name: /Upgrade/ })).toHaveAttribute("href", "/upgrade");
    expect(screen.queryByText(/Add your evidence/)).not.toBeInTheDocument();
  });

  it("subscription outage: neutral default copy, never the upsell (tri-state)", async () => {
    loadNextSteps.mockResolvedValue(vm({ subState: "error", documentsCount: 2 }));
    await renderNativeSteps();

    expect(await screen.findByText("No steps yet")).toBeInTheDocument();
    expect(screen.queryByRole("link", { name: /Upgrade/ })).not.toBeInTheDocument();
  });

  it("gap re-check pending: renders the honest 'Re-checking…' tabs, not 'add evidence' (web parity)", async () => {
    loadNextSteps.mockResolvedValue(vm({ gapAnalysisPending: true }));
    await renderNativeSteps();

    expect(await screen.findByText(/Re-checking your next steps/)).toBeInTheDocument();
    expect(screen.queryByText("No steps yet")).not.toBeInTheDocument();
  });
});
