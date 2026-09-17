import { describe, it, expect, vi, beforeEach } from "vitest";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { HomeUpdates } from "./WhatChangedCard";
import { HomeView } from "./HomeView";
import { toAnalysisUpdates } from "@/lib/notifications";
import { allReadEnvelope, unreadDigestEnvelope } from "@/lib/fixtures/notifications";
import { populatedHomeVM } from "@/lib/fixtures/home";

// The card writes through the mutations facade; HomeView's PipelinePulse pulls
// in next/navigation. Both are seams, mocked the same way the house tests do.
const markNotificationsRead = vi.fn();
vi.mock("@/lib/api/mutations", () => ({
  markNotificationsRead: (...args: unknown[]) => markNotificationsRead(...args),
}));
vi.mock("next/navigation", () => ({ useRouter: () => ({ refresh: vi.fn() }) }));

const ok = () => new Response("{}", { status: 200 });
const fail = () => new Response("{}", { status: 502 });

const UPDATES = toAnalysisUpdates(unreadDigestEnvelope);
const ALL_READ = toAnalysisUpdates(allReadEnvelope);

beforeEach(() => {
  markNotificationsRead.mockReset();
});

describe("HomeUpdates — what-changed digest card", () => {
  it("renders the digest headline + deterministic per-condition lines with condition links", () => {
    render(<HomeUpdates updates={UPDATES} />);

    expect(screen.getByText("Your analysis was updated")).toBeInTheDocument();
    // With metadata present, the run-on backend body is SUPPRESSED — the
    // grouped lines below are the readable rendering (2026-07-03 fix).
    expect(screen.queryByText(/your new evidence updated 2 conditions/i)).not.toBeInTheDocument();
    expect(screen.getByText("Rating estimates")).toBeInTheDocument();

    // Metadata lines — templates over the flip diff, linking via condHref.
    const ptsd = screen.getByRole("link", { name: /PTSD: rating estimate 50% → 70%/ });
    expect(ptsd).toHaveAttribute("href", "/conditions/93");
    const knee = screen.getByRole("link", { name: /Right knee: Nexus evidence weak → strong/ });
    expect(knee).toHaveAttribute("href", "/conditions/97");
    expect(screen.getByText("1 evidence gap closed")).toBeInTheDocument();

    // The journal deep-link.
    expect(screen.getByRole("link", { name: /see all updates/i })).toHaveAttribute(
      "href",
      "/timeline",
    );
  });

  it("'Got it' marks the unread digest rows read and hides the card", async () => {
    markNotificationsRead.mockResolvedValue(ok());
    const user = userEvent.setup();
    render(<HomeUpdates updates={UPDATES} />);

    await user.click(screen.getByRole("button", { name: /got it/i }));

    expect(markNotificationsRead).toHaveBeenCalledWith({ ids: [41] });
    await waitFor(() =>
      expect(screen.queryByText("Your analysis was updated")).not.toBeInTheDocument(),
    );
    // With the digest dismissed, the discreet timeline link takes its place.
    expect(screen.getByRole("link", { name: /claim timeline/i })).toBeInTheDocument();
  });

  it("brings the card BACK when the mark-read write fails (the dismissal didn't stick)", async () => {
    markNotificationsRead.mockResolvedValue(fail());
    const user = userEvent.setup();
    render(<HomeUpdates updates={UPDATES} />);

    await user.click(screen.getByRole("button", { name: /got it/i }));

    await waitFor(() => expect(markNotificationsRead).toHaveBeenCalled());
    expect(await screen.findByText("Your analysis was updated")).toBeInTheDocument();
  });

  it("shows only the discreet timeline link when the journal has no unread digest", () => {
    render(<HomeUpdates updates={ALL_READ} />);
    expect(screen.queryByRole("button", { name: /got it/i })).not.toBeInTheDocument();
    expect(screen.getByRole("link", { name: /claim timeline/i })).toHaveAttribute(
      "href",
      "/timeline",
    );
  });

  it("renders nothing at all for an empty journal", () => {
    const { container } = render(
      <HomeUpdates
        updates={{ digest: null, digestIds: [], changedConditionIds: [], hasAny: false }}
      />,
    );
    expect(container).toBeEmptyDOMElement();
  });
});

describe("HomeView — digest placement", () => {
  it("renders the digest card ABOVE the hero", () => {
    render(<HomeView vm={populatedHomeVM} updates={UPDATES} />);
    const card = screen.getByText("Your analysis was updated");
    const hero = screen.getAllByText(/combined rating/i)[0];
    expect(
      card.compareDocumentPosition(hero) & Node.DOCUMENT_POSITION_FOLLOWING,
    ).toBeTruthy();
  });

  it("renders Home unchanged when no updates are passed (fixtures/dev)", () => {
    render(<HomeView vm={populatedHomeVM} />);
    expect(screen.queryByText("Your analysis was updated")).not.toBeInTheDocument();
    expect(screen.queryByRole("link", { name: /claim timeline/i })).not.toBeInTheDocument();
  });
});
