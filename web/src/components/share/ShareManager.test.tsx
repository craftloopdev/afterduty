import { beforeEach, describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { ShareManager } from "./ShareManager";
import type { ShareVM } from "@/lib/models/vm";

// Phase G: viewer mode ships this cycle, so the P0-8 "coming soon" gate comes
// OFF — the analysis toggle works again and invites may grant VIEW_ANALYSIS.
// The owner-Pro dependency (analysis sharing rides on the OWNER's subscription)
// stays disclosed up front. P2-2 adds the share lifecycle: per-status rows,
// re-invite on pending/expired, confirmed revoke, re-copyable pending links.

const createShare = vi.fn();
const revokeShare = vi.fn();
vi.mock("@/lib/api/mutations", () => ({
  createShare: (...a: unknown[]) => createShare(...a),
  revokeShare: (...a: unknown[]) => revokeShare(...a),
}));

const refresh = vi.fn();
vi.mock("next/navigation", () => ({
  useRouter: () => ({ refresh }),
}));

const SHARES: ShareVM[] = [
  {
    id: 1,
    email: "rep@vso.org",
    canViewAnalysis: false,
    canUploadDocs: false,
    status: "accepted",
    // Noon UTC keeps the rendered calendar day stable across test timezones.
    acceptedAt: "2026-06-05T12:00:00Z",
  },
];

const PENDING: ShareVM = {
  id: 2,
  email: "pending@vso.org",
  canViewAnalysis: true,
  canUploadDocs: true,
  status: "pending",
  expiresAt: "2026-06-12T12:00:00Z",
  acceptUrl: "https://app.afterduty.app/accept-share/PENDTOK",
  inviteToken: "PENDTOK",
};

const EXPIRED: ShareVM = {
  id: 3,
  email: "expired@vso.org",
  canViewAnalysis: false,
  canUploadDocs: false,
  status: "expired",
  expiresAt: "2026-05-01T00:00:00Z",
};

function shareDtoResponse() {
  return new Response(
    JSON.stringify({
      id: 7,
      viewerEmail: "rep@vso.org",
      invitationToken: "TOK123",
      invitationExpiresAt: new Date(Date.now() + 7 * 86_400_000).toISOString(),
    }),
    { status: 201, headers: { "content-type": "application/json" } },
  );
}

beforeEach(() => {
  createShare.mockReset();
  revokeShare.mockReset();
  refresh.mockReset();
});

describe("ShareManager analysis sharing re-enabled (Phase G)", () => {
  it("the analysis toggle works and its grant is sent on create", async () => {
    createShare.mockResolvedValue(shareDtoResponse());
    render(<ShareManager shares={[]} />);

    const analysisToggle = screen
      .getByText("Share analysis access")
      .closest("button") as HTMLButtonElement;
    expect(analysisToggle).not.toBeDisabled();
    fireEvent.click(analysisToggle);
    expect(analysisToggle).toHaveAttribute("aria-pressed", "true");

    fireEvent.change(screen.getByLabelText("Email address"), {
      target: { value: "rep@vso.org" },
    });
    fireEvent.click(screen.getByText("Allow document uploads").closest("button")!);
    fireEvent.click(screen.getByRole("button", { name: /create secure invite/i }));

    await waitFor(() => expect(createShare).toHaveBeenCalledTimes(1));
    expect(createShare).toHaveBeenCalledWith({
      viewerEmail: "rep@vso.org",
      canViewAnalysis: true,
      canUploadDocs: true,
    });
  });

  it("defaults the analysis grant OFF and discloses the owner-Pro dependency", () => {
    render(<ShareManager shares={[]} />);
    expect(screen.queryByText(/coming soon/i)).not.toBeInTheDocument();
    const analysisToggle = screen
      .getByText("Share analysis access")
      .closest("button") as HTMLButtonElement;
    expect(analysisToggle).toHaveAttribute("aria-pressed", "false");
    // The disclosure is up-front, not buried behind a rep's 403.
    expect(
      screen.getByText(/analysis sharing requires your active pro subscription/i),
    ).toBeInTheDocument();
  });

  it("documents invites keep working end-to-end: invite link renders", async () => {
    createShare.mockResolvedValue(shareDtoResponse());
    render(<ShareManager shares={[]} />);

    fireEvent.change(screen.getByLabelText("Email address"), {
      target: { value: "rep@vso.org" },
    });
    fireEvent.click(screen.getByRole("button", { name: /create secure invite/i }));

    await waitFor(() =>
      expect(screen.getByText(/invite ready for rep@vso.org/i)).toBeInTheDocument(),
    );
    const link = screen.getByLabelText("Invite link") as HTMLInputElement;
    expect(link.value).toContain("/accept-share/TOK123");
    expect(refresh).toHaveBeenCalled();
  });
});

describe("ShareManager lifecycle rendering (P2-2)", () => {
  it("accepted: 'Accepted <date>' with no re-invite affordance", () => {
    render(<ShareManager shares={SHARES} />);
    expect(screen.getByText(/accepted june 5, 2026/i)).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /re-invite/i })).not.toBeInTheDocument();
  });

  it("pending: 'Invite sent — expires <date>' + copy + re-invite", () => {
    render(<ShareManager shares={[PENDING]} />);
    expect(screen.getByText(/invite sent — expires june 12, 2026/i)).toBeInTheDocument();
    expect(screen.getByRole("button", { name: /copy link/i })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: /re-invite/i })).toBeInTheDocument();
  });

  it("expired: labeled expired with a re-invite button (no copy — the link is dead)", () => {
    render(<ShareManager shares={[EXPIRED]} />);
    expect(screen.getByText(/invite expired/i)).toBeInTheDocument();
    expect(screen.getByRole("button", { name: /re-invite/i })).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /copy link/i })).not.toBeInTheDocument();
  });

  it("re-copies a pending invite link (backend acceptUrl)", async () => {
    const writeText = vi.fn().mockResolvedValue(undefined);
    Object.defineProperty(navigator, "clipboard", {
      configurable: true,
      value: { writeText },
    });
    render(<ShareManager shares={[PENDING]} />);
    fireEvent.click(screen.getByRole("button", { name: /copy link/i }));
    await waitFor(() =>
      expect(writeText).toHaveBeenCalledWith("https://app.afterduty.app/accept-share/PENDTOK"),
    );
  });

  it("re-invite calls the same create endpoint with the row's email and grants", async () => {
    createShare.mockResolvedValue(
      new Response(
        JSON.stringify({
          id: 9,
          viewerEmail: "pending@vso.org",
          invitationToken: "FRESH",
          acceptUrl: "https://app.afterduty.app/accept-share/FRESH",
          invitationExpiresAt: new Date(Date.now() + 7 * 86_400_000).toISOString(),
        }),
        { status: 201 },
      ),
    );
    const onChanged = vi.fn();
    render(<ShareManager shares={[PENDING]} onChanged={onChanged} />);

    fireEvent.click(screen.getByRole("button", { name: /re-invite/i }));

    await waitFor(() => expect(createShare).toHaveBeenCalledTimes(1));
    expect(createShare).toHaveBeenCalledWith({
      viewerEmail: "pending@vso.org",
      canViewAnalysis: true,
      canUploadDocs: true,
    });
    // The fresh single-use link surfaces for the veteran to send.
    await waitFor(() =>
      expect(screen.getByText(/invite ready for pending@vso.org/i)).toBeInTheDocument(),
    );
    expect((screen.getByLabelText("Invite link") as HTMLInputElement).value).toContain("FRESH");
    expect(refresh).toHaveBeenCalled();
    expect(onChanged).toHaveBeenCalled();
  });
});

describe("ShareManager revoke confirm (P2-2)", () => {
  it("asks before revoking — '<email> will immediately lose access' — and cancel is a no-op", () => {
    render(<ShareManager shares={SHARES} />);
    fireEvent.click(screen.getByRole("button", { name: /revoke access for rep@vso.org/i }));

    const dialog = screen.getByRole("dialog", { name: /revoke access/i });
    expect(within(dialog).getByText(/will immediately lose access/i)).toBeInTheDocument();
    expect(within(dialog).getByText("rep@vso.org")).toBeInTheDocument();

    fireEvent.click(within(dialog).getByRole("button", { name: /cancel/i }));
    expect(revokeShare).not.toHaveBeenCalled();
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
  });

  it("confirms, revokes, and shows the post-revoke confirmation state", async () => {
    revokeShare.mockResolvedValue(new Response(null, { status: 204 }));
    const onChanged = vi.fn();
    render(<ShareManager shares={SHARES} onChanged={onChanged} />);

    fireEvent.click(screen.getByRole("button", { name: /revoke access for rep@vso.org/i }));
    fireEvent.click(
      within(screen.getByRole("dialog")).getByRole("button", { name: /revoke access/i }),
    );

    await waitFor(() => expect(revokeShare).toHaveBeenCalledWith(1));
    // Post-revoke confirmation: an explicit notice…
    expect(await screen.findByText(/no longer has access to your claim/i)).toBeInTheDocument();
    // …and the row flips to Revoked with its actions gone until the refresh drops it.
    expect(screen.getByText("Revoked")).toBeInTheDocument();
    expect(
      screen.queryByRole("button", { name: /revoke access for rep@vso.org/i }),
    ).not.toBeInTheDocument();
    expect(refresh).toHaveBeenCalled();
    expect(onChanged).toHaveBeenCalled();
  });
});

describe("ShareManager expired-session dead-ends (P2-4)", () => {
  it("a 401 on create offers 'Sign in again' linking back to /share", async () => {
    createShare.mockResolvedValue(new Response(null, { status: 401 }));
    render(<ShareManager shares={[]} />);

    fireEvent.change(screen.getByLabelText("Email address"), {
      target: { value: "rep@vso.org" },
    });
    fireEvent.click(screen.getByRole("button", { name: /create secure invite/i }));

    const link = await screen.findByRole("link", { name: /sign in again/i });
    expect(link).toHaveAttribute("href", "/login?next=%2Fshare");
  });

  it("a 401 on revoke closes the confirm and offers the sign-in link", async () => {
    revokeShare.mockResolvedValue(new Response(null, { status: 401 }));
    render(<ShareManager shares={SHARES} />);

    fireEvent.click(screen.getByRole("button", { name: /revoke access for rep@vso.org/i }));
    fireEvent.click(
      within(screen.getByRole("dialog")).getByRole("button", { name: /revoke access/i }),
    );

    const link = await screen.findByRole("link", { name: /sign in again/i });
    expect(link).toHaveAttribute("href", "/login?next=%2Fshare");
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
  });
});
