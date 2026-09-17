import { describe, it, expect, vi, beforeEach } from "vitest";
import { render, screen } from "@testing-library/react";
import type { ConditionsPageData } from "@/lib/models/vm";

// P1-16 integration on the native Conditions screen: the empty state must be
// subscription-aware. Free-with-docs → the honest Pro boundary + upgrade link;
// a subscription outage ("error") → the neutral default, never the upsell.
// The loader COMPOSITION rules (tri-state, /auth/me degrade → documentsCount 0)
// are covered at the seam in endpoints-core.substate.test.ts — here we verify
// the screen renders each `loadConditionsPage` outcome honestly.

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

const loadConditionsPage = vi.fn();
const loadAnalysisUpdates = vi.fn();

vi.mock("@/lib/api/endpoints.native", async () => {
  // The REAL useLoader drives the component exactly as it does in the app;
  // only the transport-bound loaders are faked.
  const { useLoader } = await vi.importActual<typeof import("@/lib/hooks/useLoader")>(
    "@/lib/hooks/useLoader",
  );
  return {
    useLoader,
    loadConditionsPage: () => loadConditionsPage(),
    loadAnalysisUpdates: () => loadAnalysisUpdates(),
  };
});

const EMPTY_UPDATES = { digest: null, digestIds: [], changedConditionIds: [], hasAny: false };

const page = (overrides: Partial<ConditionsPageData>): ConditionsPageData => ({
  conditions: [],
  subState: "free",
  documentsCount: 0,
  ...overrides,
});

beforeEach(() => {
  loadConditionsPage.mockReset();
  loadAnalysisUpdates.mockReset().mockResolvedValue(EMPTY_UPDATES);
});

async function renderNativeConditions() {
  const { NativeConditions } = await import("./NativeConditions");
  return render(<NativeConditions />);
}

describe("NativeConditions empty state (P1-16)", () => {
  it("free user with documents: stored-docs copy + upgrade link (no dead loop)", async () => {
    loadConditionsPage.mockResolvedValue(page({ subState: "free", documentsCount: 3 }));
    await renderNativeConditions();

    expect(await screen.findByText("Your documents are stored")).toBeInTheDocument();
    expect(screen.getByRole("link", { name: /Upgrade/ })).toHaveAttribute("href", "/upgrade");
    expect(screen.queryByText(/Add your evidence/)).not.toBeInTheDocument();
  });

  it("subscription outage: neutral default copy — NEVER the free upsell", async () => {
    loadConditionsPage.mockResolvedValue(page({ subState: "error", documentsCount: 3 }));
    await renderNativeConditions();

    expect(await screen.findByText("No conditions yet")).toBeInTheDocument();
    expect(screen.queryByText(/Pro feature/)).not.toBeInTheDocument();
    expect(screen.queryByRole("link", { name: /Upgrade/ })).not.toBeInTheDocument();
  });

  it("free user with no documents (incl. /auth/me degrade → 0): default copy", async () => {
    loadConditionsPage.mockResolvedValue(page({ subState: "free", documentsCount: 0 }));
    await renderNativeConditions();

    expect(await screen.findByText("No conditions yet")).toBeInTheDocument();
    expect(screen.queryByText(/Pro feature/)).not.toBeInTheDocument();
  });
});
