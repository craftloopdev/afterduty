import { describe, it, expect, vi } from "vitest";
import { render, screen } from "@testing-library/react";
import { AppShell } from "./AppShell";
import { useViewer } from "@/components/viewer/ViewerContext";
import type { SharedClaimRef, ViewerVM } from "@/lib/models/vm";

// P0-8: while VIEWING a shared claim the shell is read-only — the add-evidence
// affordances disappear at the shell level, the persistent banner appears, and
// `useViewer()` tells every downstream component to hide its own mutations.

vi.mock("next/navigation", () => ({
  usePathname: () => "/",
  useRouter: () => ({ push: vi.fn(), replace: vi.fn(), refresh: vi.fn() }),
}));

const VIEWER: ViewerVM = {
  viewing: true,
  claimId: 42,
  ownerName: "Dana Vet",
  canViewAnalysis: true,
  canUploadDocs: false,
  analysisBlocked: false,
};
const SHARES: SharedClaimRef[] = [{ claimId: 42, ownerName: "Dana Vet" }];

function Probe() {
  const v = useViewer();
  return <output data-testid="probe">{v.viewing ? `viewing:${v.ownerName}` : "own"}</output>;
}

describe("AppShell viewer mode (P0-8)", () => {
  it("own claim: the sidebar Add-evidence link is present, no banner, no data-viewer", () => {
    const { container } = render(
      <AppShell userName="Sam Vet" subState="free">
        <Probe />
      </AppShell>,
    );
    // P2-1: the only add affordance left is the sidebar CTA, now a LINK to the
    // Documents evidence hub (the top-right "+" button was removed).
    const add = screen.getByRole("link", { name: /add evidence/i });
    expect(add).toHaveAttribute("href", "/documents");
    expect(screen.queryByRole("button", { name: /add evidence/i })).not.toBeInTheDocument();
    expect(screen.queryByTestId("viewer-banner")).not.toBeInTheDocument();
    expect(container.querySelector("[data-viewer]")).toBeNull();
    expect(screen.getByTestId("probe")).toHaveTextContent("own");
  });

  it("offers the claim switcher when shares exist and not viewing", () => {
    render(
      <AppShell userName="Sam Vet" subState="free" sharedClaims={SHARES}>
        <div />
      </AppShell>,
    );
    expect(screen.getByTestId("viewer-switcher")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "View Dana Vet's claim" })).toBeInTheDocument();
  });

  it("viewing: hides EVERY add affordance, shows the banner, sets data-viewer", () => {
    const { container } = render(
      <AppShell userName="Sam Vet" subState="free" viewer={VIEWER} sharedClaims={SHARES}>
        <Probe />
      </AppShell>,
    );
    expect(screen.queryByRole("link", { name: /add evidence/i })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /add evidence/i })).not.toBeInTheDocument();
    expect(screen.getByTestId("viewer-banner")).toHaveTextContent("Viewing Dana Vet's claim");
    expect(container.querySelector('[data-viewer="1"]')).not.toBeNull();
    // The switcher hides while viewing (the banner's Exit is the way back).
    expect(screen.queryByTestId("viewer-switcher")).not.toBeInTheDocument();
    // Downstream components learn the state via context.
    expect(screen.getByTestId("probe")).toHaveTextContent("viewing:Dana Vet");
  });
});
