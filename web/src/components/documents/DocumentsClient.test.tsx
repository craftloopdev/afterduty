import { beforeEach, describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { DocumentsClient } from "./DocumentsClient";
import type { DocVM } from "@/lib/models/vm";

// P2-1: the dissolved top-right "+" AddModal's two evidence entry points now
// live on the Documents page — a FREE "Write a statement" quick-add and a
// "Describe what you remember → Ask AI" card that navigates to the composer
// with an editable opener prefilled (Pro-gated, so a confirmed free user
// carries the honest "Pro" affordance; never on "error").

const quickAddStatement = vi.fn();
vi.mock("@/lib/api/mutations", () => ({
  quickAddStatement: (...a: unknown[]) => quickAddStatement(...a),
}));

const refresh = vi.fn();
vi.mock("next/navigation", () => ({
  useRouter: () => ({ push: vi.fn(), refresh }),
  usePathname: () => "/documents",
}));

vi.mock("next/link", () => ({
  default: ({
    href,
    children,
    ...rest
  }: React.PropsWithChildren<{ href: string } & Record<string, unknown>>) => (
    <a href={typeof href === "string" ? href : String(href)} {...rest}>
      {children}
    </a>
  ),
}));

// Keep the test focused on the two relocated sections — stub the upload card
// and the doc list (each has its own suite).
vi.mock("./UploadCard", () => ({
  UploadCard: () => <div data-testid="upload-card" />,
}));
vi.mock("./DocumentsView", () => ({
  DocumentsView: ({ docs }: { docs: DocVM[] }) => (
    <div data-testid="documents-view">{docs.length}</div>
  ),
}));

const doc: DocVM = {
  id: 1,
  name: "dd214.pdf",
  kind: "Service",
  icon: "file",
  color: "var(--navy)",
  status: "done",
  statusLabel: "Processed",
  statusDetail: null,
  processed: true,
};

beforeEach(() => {
  quickAddStatement.mockReset();
  refresh.mockReset();
  vi.unstubAllGlobals();
});

describe("DocumentsClient — Describe what you remember → Ask AI (P2-1)", () => {
  const STARTER = "Here's what I remember about my service and how my conditions affect me:";

  it("links to /ask with the editable opener prefilled as ?topic", () => {
    render(<DocumentsClient initialDocs={[]} subState="pro" />);
    const link = screen.getByRole("link", { name: /describe to ai/i });
    expect(link).toHaveAttribute("href", `/ask?topic=${encodeURIComponent(STARTER)}`);
  });

  it("badges the card Pro for a CONFIRMED free user", () => {
    render(<DocumentsClient initialDocs={[]} subState="free" />);
    // The badge sits on the Describe card, not the free statement section.
    expect(
      screen.getByText("Describe what you remember").closest("section")?.textContent,
    ).toContain("Pro");
    expect(
      screen.getByText("Write a statement").closest("section")?.textContent,
    ).not.toContain("Pro");
  });

  it("never badges a Pro user", () => {
    render(<DocumentsClient initialDocs={[]} subState="pro" />);
    expect(screen.queryByText("Pro")).not.toBeInTheDocument();
  });

  it("never badges on an unknown state (an outage is not 'free')", () => {
    render(<DocumentsClient initialDocs={[]} subState="error" />);
    expect(screen.queryByText("Pro")).not.toBeInTheDocument();
  });
});

describe("DocumentsClient — Write a statement quick-add (P2-1)", () => {
  function box() {
    return screen.getByLabelText("Your statement") as HTMLTextAreaElement;
  }

  it("keeps the submit disabled until there is text", () => {
    render(<DocumentsClient initialDocs={[]} subState="free" />);
    expect(screen.getByRole("button", { name: /add statement/i })).toBeDisabled();
  });

  it("posts the trimmed statement, confirms, clears the box and refreshes", async () => {
    quickAddStatement.mockResolvedValue(
      new Response(JSON.stringify({ id: 42 }), { status: 201 }),
    );
    render(<DocumentsClient initialDocs={[]} subState="free" />);

    fireEvent.change(box(), { target: { value: "  My knee pain started in 2009.  " } });
    fireEvent.click(screen.getByRole("button", { name: /add statement/i }));

    await waitFor(() =>
      expect(quickAddStatement).toHaveBeenCalledWith("My knee pain started in 2009."),
    );
    expect(await screen.findByText(/added to your evidence/i)).toBeInTheDocument();
    // Web path: refresh re-fetches the BFF list (fetch is stubbed to a no-op ok).
    expect(box().value).toBe("");
  });

  it("web refresh re-fetches the BFF documents list after an add", async () => {
    quickAddStatement.mockResolvedValue(new Response("{}", { status: 201 }));
    const fetchMock = vi.fn().mockResolvedValue(
      new Response(JSON.stringify({ docs: [] }), { status: 200 }),
    );
    vi.stubGlobal("fetch", fetchMock);
    render(<DocumentsClient initialDocs={[]} subState="free" />);

    fireEvent.change(box(), { target: { value: "Statement text" } });
    fireEvent.click(screen.getByRole("button", { name: /add statement/i }));

    await waitFor(() =>
      expect(fetchMock).toHaveBeenCalledWith("/api/claim/documents", { cache: "no-store" }),
    );
  });

  it("a 401 offers 'Sign in again' back to the current page and keeps the draft", async () => {
    quickAddStatement.mockResolvedValue(new Response(null, { status: 401 }));
    render(<DocumentsClient initialDocs={[]} subState="free" />);

    fireEvent.change(box(), { target: { value: "Statement text" } });
    fireEvent.click(screen.getByRole("button", { name: /add statement/i }));

    const link = await screen.findByRole("link", { name: /sign in again/i });
    expect(link).toHaveAttribute("href", "/login?next=%2Fdocuments");
    // The veteran's words are not thrown away.
    expect(box().value).toBe("Statement text");
  });

  it("surfaces a retryable error on other failures", async () => {
    quickAddStatement.mockResolvedValue(new Response(null, { status: 502 }));
    render(<DocumentsClient initialDocs={[]} subState="free" />);

    fireEvent.change(box(), { target: { value: "Statement text" } });
    fireEvent.click(screen.getByRole("button", { name: /add statement/i }));
    expect(await screen.findByText(/couldn't add your statement/i)).toBeInTheDocument();
  });
});

describe("DocumentsClient — native onChanged wiring (P2-1)", () => {
  it("defers refresh to the loader's refetch instead of hitting the BFF", async () => {
    quickAddStatement.mockResolvedValue(new Response("{}", { status: 201 }));
    const onChanged = vi.fn();
    const fetchMock = vi.fn();
    vi.stubGlobal("fetch", fetchMock);
    render(<DocumentsClient initialDocs={[doc]} subState="pro" onChanged={onChanged} />);

    fireEvent.change(screen.getByLabelText("Your statement"), {
      target: { value: "Statement text" },
    });
    fireEvent.click(screen.getByRole("button", { name: /add statement/i }));

    await waitFor(() => expect(onChanged).toHaveBeenCalled());
    expect(fetchMock).not.toHaveBeenCalled();
  });
});
