import { describe, it, expect, vi, beforeEach, afterEach } from "vitest";
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { ViewerBanner } from "./ViewerBanner";
import type { ViewerVM } from "@/lib/models/vm";

const VIEWING: ViewerVM = {
  viewing: true,
  claimId: 42,
  ownerName: "Dana Vet",
  canViewAnalysis: true,
  canUploadDocs: false,
  analysisBlocked: false,
};

let assign: ReturnType<typeof vi.fn>;
const fetchMock = vi.fn();

beforeEach(() => {
  assign = vi.fn();
  // jsdom's window.location.assign is a non-configurable no-op; replace it.
  Object.defineProperty(window, "location", {
    configurable: true,
    value: { assign, href: "http://localhost/" },
  });
  fetchMock.mockReset();
  fetchMock.mockResolvedValue(new Response(null, { status: 204 }));
  vi.stubGlobal("fetch", fetchMock);
});

afterEach(() => {
  vi.unstubAllGlobals();
});

describe("ViewerBanner (P0-8)", () => {
  it("names the owner and states read-only", () => {
    render(<ViewerBanner viewer={VIEWING} />);
    const banner = screen.getByTestId("viewer-banner");
    expect(banner).toHaveTextContent("Viewing Dana Vet's claim");
    expect(banner).toHaveTextContent("read-only");
  });

  it("web Exit DELETEs /api/view-as then full-navigates home", async () => {
    render(<ViewerBanner viewer={VIEWING} />);
    await userEvent.click(screen.getByRole("button", { name: /stop viewing dana vet/i }));
    expect(fetchMock).toHaveBeenCalledWith("/api/view-as", { method: "DELETE" });
    expect(assign).toHaveBeenCalledWith("/");
  });

  it("native onExit override replaces the web path entirely", async () => {
    const onExit = vi.fn();
    render(<ViewerBanner viewer={VIEWING} onExit={onExit} />);
    await userEvent.click(screen.getByRole("button", { name: /stop viewing/i }));
    expect(onExit).toHaveBeenCalledTimes(1);
    expect(fetchMock).not.toHaveBeenCalled();
    expect(assign).not.toHaveBeenCalled();
  });

  it("owner-Pro dependency notice: analysis granted but blocked → honest copy", () => {
    render(<ViewerBanner viewer={{ ...VIEWING, analysisBlocked: true }} />);
    expect(screen.getByTestId("viewer-banner")).toHaveTextContent(
      "Dana Vet needs an active Pro subscription for analysis sharing — documents are still available.",
    );
  });

  it("docs-only share → the docs-only notice, not the owner-Pro one", () => {
    render(
      <ViewerBanner viewer={{ ...VIEWING, canViewAnalysis: false, analysisBlocked: true }} />,
    );
    const banner = screen.getByTestId("viewer-banner");
    expect(banner).toHaveTextContent("This share includes documents only");
    expect(banner).not.toHaveTextContent("Pro subscription");
  });

  it("no notice when analysis is fully accessible", () => {
    render(<ViewerBanner viewer={VIEWING} />);
    const banner = screen.getByTestId("viewer-banner");
    expect(banner).not.toHaveTextContent("documents only");
    expect(banner).not.toHaveTextContent("Pro subscription");
  });
});
