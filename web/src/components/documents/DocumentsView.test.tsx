import { beforeEach, describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { DocumentsView } from "./DocumentsView";
import type { DocVM } from "@/lib/models/vm";

// P1-29 (per-doc Download/Delete with a consequence-naming confirm + pulse arm)
// and P1-30 ("What we found" facts expandable; ?focus= citation deep link).

const deleteEvidence = vi.fn();
const fetchEvidenceFacts = vi.fn();
vi.mock("./evidence-actions", () => ({
  deleteEvidence: (...a: unknown[]) => deleteEvidence(...a),
  fetchEvidenceFacts: (...a: unknown[]) => fetchEvidenceFacts(...a),
  evidenceDownloadHref: (id: number) => `/api/claim/evidence/${id}/download`,
}));

const armAnalysisPulse = vi.fn();
vi.mock("@/lib/jobs", () => ({
  armAnalysisPulse: (...a: unknown[]) => armAnalysisPulse(...a),
}));

const refresh = vi.fn();
let search = "";
vi.mock("next/navigation", () => ({
  useRouter: () => ({ refresh }),
  useSearchParams: () => new URLSearchParams(search),
}));

vi.mock("@/lib/native/haptics", () => ({
  tapLight: () => {},
  notifySuccess: () => {},
  notifyError: () => {},
}));

const doc = (over: Partial<DocVM> = {}): DocVM => ({
  id: 1,
  name: "dd214.pdf",
  kind: "Service",
  icon: "file",
  color: "var(--navy)",
  status: "done",
  statusLabel: "Processed",
  statusDetail: null,
  processed: true,
  ...over,
});

beforeEach(() => {
  deleteEvidence.mockReset();
  fetchEvidenceFacts.mockReset();
  armAnalysisPulse.mockReset();
  refresh.mockReset();
  search = "";
});

const openMenu = (name: string) =>
  fireEvent.click(screen.getByRole("button", { name: `More options for ${name}` }));

describe("DocumentsView overflow menu (P1-29)", () => {
  it("offers Download (same-origin BFF href) and Delete per document", () => {
    render(<DocumentsView docs={[doc()]} />);
    openMenu("dd214.pdf");
    const download = screen.getByRole("menuitem", { name: "Download" });
    expect(download).toHaveAttribute("href", "/api/claim/evidence/1/download");
    expect(download).toHaveAttribute("download");
    expect(screen.getByRole("menuitem", { name: "Delete" })).toBeInTheDocument();
  });

  it("confirms with the consequence named, deletes, arms the pulse, and refetches", async () => {
    deleteEvidence.mockResolvedValue(new Response(null, { status: 204 }));
    const onChanged = vi.fn();
    render(<DocumentsView docs={[doc()]} onChanged={onChanged} />);

    openMenu("dd214.pdf");
    fireEvent.click(screen.getByRole("menuitem", { name: "Delete" }));

    // The confirm names the consequence — never a bare "Are you sure?".
    const dialog = screen.getByRole("dialog");
    expect(
      within(dialog).getByText(/removes it from your analysis .* your conditions will update/i),
    ).toBeInTheDocument();
    expect(within(dialog).getByText(/dd214\.pdf/)).toBeInTheDocument();

    fireEvent.click(screen.getByRole("button", { name: "Delete document" }));

    await waitFor(() => expect(deleteEvidence).toHaveBeenCalledWith(1));
    // The backend forces re-analysis on delete; the pulse makes that visible.
    await waitFor(() => expect(armAnalysisPulse).toHaveBeenCalledTimes(1));
    expect(onChanged).toHaveBeenCalledTimes(1);
    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
  });

  it("cancel closes the confirm without deleting", () => {
    render(<DocumentsView docs={[doc()]} />);
    openMenu("dd214.pdf");
    fireEvent.click(screen.getByRole("menuitem", { name: "Delete" }));
    fireEvent.click(screen.getByRole("button", { name: "Cancel" }));
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
    expect(deleteEvidence).not.toHaveBeenCalled();
  });

  it("keeps the modal open with an error when the delete fails — and never arms the pulse", async () => {
    deleteEvidence.mockResolvedValue(new Response("{}", { status: 500 }));
    const onChanged = vi.fn();
    render(<DocumentsView docs={[doc()]} onChanged={onChanged} />);
    openMenu("dd214.pdf");
    fireEvent.click(screen.getByRole("menuitem", { name: "Delete" }));
    fireEvent.click(screen.getByRole("button", { name: "Delete document" }));

    expect(
      await screen.findByText("Couldn't delete the document. Please try again."),
    ).toBeInTheDocument();
    expect(screen.getByRole("dialog")).toBeInTheDocument();
    expect(armAnalysisPulse).not.toHaveBeenCalled();
    expect(onChanged).not.toHaveBeenCalled();
  });
});

describe("What we found (P1-30 facts expandable)", () => {
  const FACTS = [
    { type: "diagnosis", value: "PTSD", source: "dd214.pdf", confidence: 0.93, date: "2020-02-02" },
    { type: "service_period", value: "Active duty 2008–2012", confidence: 0.99 },
  ];

  it("only processed documents offer the expandable", () => {
    render(
      <DocumentsView
        docs={[
          doc(),
          doc({ id: 2, name: "str.pdf", status: "queued", statusLabel: "Uploaded", processed: false }),
        ]}
      />,
    );
    expect(screen.getAllByRole("button", { name: /what we found/i })).toHaveLength(1);
  });

  it("expands to the API's extracted facts — verbatim values, no confidence number", async () => {
    fetchEvidenceFacts.mockResolvedValue(
      new Response(JSON.stringify(FACTS), {
        status: 200,
        headers: { "content-type": "application/json" },
      }),
    );
    render(<DocumentsView docs={[doc()]} />);

    const toggle = screen.getByRole("button", { name: /what we found/i });
    expect(toggle).toHaveAttribute("aria-expanded", "false");
    fireEvent.click(toggle);

    expect(fetchEvidenceFacts).toHaveBeenCalledWith(1);
    expect(await screen.findByText("PTSD")).toBeInTheDocument();
    expect(screen.getByText(/Diagnosis · 2020-02-02/)).toBeInTheDocument();
    expect(screen.getByText("Active duty 2008–2012")).toBeInTheDocument();
    expect(screen.getByText("Service period")).toBeInTheDocument();
    // LLM-derived confidence must never render.
    expect(screen.queryByText(/0\.9|93%|99%/)).not.toBeInTheDocument();
    expect(toggle).toHaveAttribute("aria-expanded", "true");
  });

  it("states plainly when nothing was extracted, and shows a retryable error on failure", async () => {
    fetchEvidenceFacts.mockResolvedValueOnce(new Response("[]", { status: 200 }));
    const { unmount } = render(<DocumentsView docs={[doc()]} />);
    fireEvent.click(screen.getByRole("button", { name: /what we found/i }));
    expect(
      await screen.findByText("No facts were extracted from this document."),
    ).toBeInTheDocument();
    unmount();

    fetchEvidenceFacts.mockResolvedValueOnce(new Response("{}", { status: 500 }));
    render(<DocumentsView docs={[doc()]} />);
    fireEvent.click(screen.getByRole("button", { name: /what we found/i }));
    expect(await screen.findByText(/couldn't load what we found/i)).toBeInTheDocument();
  });
});

describe("?focus= citation deep link (P1-30)", () => {
  it("scrolls to and highlights the cited card (param name matches lib/cite.ts)", async () => {
    search = "focus=2";
    const scrollIntoView = vi.fn();
    Element.prototype.scrollIntoView = scrollIntoView;

    const { container } = render(
      <DocumentsView docs={[doc(), doc({ id: 2, name: "nexus-letter.pdf" })]} />,
    );

    await waitFor(() => expect(scrollIntoView).toHaveBeenCalled());
    const focused = container.querySelector("[data-focused]");
    expect(focused).not.toBeNull();
    expect(focused?.textContent).toContain("nexus-letter.pdf");
  });

  it("ignores a junk focus param", async () => {
    search = "focus=banana";
    const scrollIntoView = vi.fn();
    Element.prototype.scrollIntoView = scrollIntoView;
    const { container } = render(<DocumentsView docs={[doc()]} />);
    await new Promise((r) => setTimeout(r, 0));
    expect(scrollIntoView).not.toHaveBeenCalled();
    expect(container.querySelector("[data-focused]")).toBeNull();
  });
});
