import { describe, it, expect, vi, beforeEach } from "vitest";
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { ShareManager } from "./ShareManager";

// P1-20: under Capacitor, window.location.origin is https://localhost — an
// invite link built from it is dead on arrival for the recipient. The backend
// computes the canonical acceptUrl on the DTO; the UI must prefer it and only
// fall back to the origin for older backends that omit it. (jsdom's
// http://localhost origin conveniently plays the Capacitor webview here.)

const createShare = vi.fn();
vi.mock("@/lib/api/mutations", () => ({
  createShare: (...args: unknown[]) => createShare(...args),
  revokeShare: vi.fn(),
}));
vi.mock("next/navigation", () => ({
  useRouter: () => ({ refresh: vi.fn(), push: vi.fn(), replace: vi.fn() }),
}));

const dtoResponse = (dto: Record<string, unknown>) =>
  new Response(JSON.stringify(dto), { status: 201 });

async function createInvite(user: ReturnType<typeof userEvent.setup>) {
  await user.type(screen.getByPlaceholderText("rep@vso.org"), "rep@vso.org");
  await user.click(screen.getByRole("button", { name: /Create secure invite/ }));
  return screen.findByLabelText("Invite link");
}

beforeEach(() => {
  createShare.mockReset();
});

describe("ShareManager invite link (P1-20)", () => {
  it("prefers the backend-computed acceptUrl over window.location.origin", async () => {
    createShare.mockResolvedValue(
      dtoResponse({
        id: 7,
        viewerEmail: "rep@vso.org",
        invitationToken: "TOK123",
        acceptUrl: "https://app.afterduty.app/accept-share/TOK123",
        invitationExpiresAt: new Date(Date.now() + 3 * 86_400_000).toISOString(),
      }),
    );
    const user = userEvent.setup();
    render(<ShareManager shares={[]} />);

    const linkInput = (await createInvite(user)) as HTMLInputElement;
    expect(linkInput.value).toBe("https://app.afterduty.app/accept-share/TOK123");
    // Never the webview origin (jsdom = http://localhost, same failure shape).
    expect(linkInput.value).not.toContain(window.location.origin);
  });

  it("falls back to the origin-built link only when acceptUrl is absent", async () => {
    createShare.mockResolvedValue(
      dtoResponse({
        id: 8,
        viewerEmail: "rep@vso.org",
        invitationToken: "TOK456",
        invitationExpiresAt: new Date(Date.now() + 3 * 86_400_000).toISOString(),
      }),
    );
    const user = userEvent.setup();
    render(<ShareManager shares={[]} />);

    const linkInput = (await createInvite(user)) as HTMLInputElement;
    expect(linkInput.value).toBe(`${window.location.origin}/accept-share/TOK456`);
  });
});
