import { beforeEach, describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { UploadCard, WORD_REJECT_MSG } from "./UploadCard";

// P1-28 upload ergonomics: multi-select with a PER-FILE status list (each file
// succeeds or fails independently with its own copy), drag-drop, camera capture
// input, extraction-supported accept list, and a pre-upload Word rejection.

const uploadEvidence = vi.fn();
vi.mock("@/lib/api/mutations", () => ({
  uploadEvidence: (...a: unknown[]) => uploadEvidence(...a),
}));

const armAnalysisPulse = vi.fn();
vi.mock("@/lib/jobs", () => ({
  armAnalysisPulse: (...a: unknown[]) => armAnalysisPulse(...a),
}));

const refresh = vi.fn();
vi.mock("next/navigation", () => ({
  useRouter: () => ({ refresh }),
}));

vi.mock("@/lib/native/haptics", () => ({
  tapLight: () => {},
  notifySuccess: () => {},
  notifyError: () => {},
}));

const ok = () => new Response("{}", { status: 201 });
const err = (status: number, body = "{}") =>
  new Response(body, { status, headers: { "content-type": "application/json" } });

const file = (name: string, type = "application/pdf") => new File(["x"], name, { type });

function pickerInput(container: HTMLElement): HTMLInputElement {
  const input = container.querySelector('input[type="file"][multiple]');
  if (!input) throw new Error("multi-file input not found");
  return input as HTMLInputElement;
}

beforeEach(() => {
  uploadEvidence.mockReset();
  armAnalysisPulse.mockReset();
  refresh.mockReset();
});

describe("UploadCard (P1-28)", () => {
  it("uploads multiple files with an independent status per file", async () => {
    uploadEvidence
      .mockResolvedValueOnce(ok())
      .mockResolvedValueOnce(err(413))
      .mockResolvedValueOnce(err(409));
    const onUploaded = vi.fn();
    const { container } = render(<UploadCard onUploaded={onUploaded} />);

    fireEvent.change(pickerInput(container), {
      target: { files: [file("dd214.pdf"), file("huge-str.pdf"), file("dup.pdf")] },
    });

    await waitFor(() => expect(uploadEvidence).toHaveBeenCalledTimes(3));

    // Every file shows its own row + outcome — one failure never hides the rest.
    expect(screen.getByText("dd214.pdf")).toBeInTheDocument();
    expect(screen.getByText("Uploaded")).toBeInTheDocument();
    expect(screen.getByText("huge-str.pdf")).toBeInTheDocument();
    expect(await screen.findByText("That file is too large.")).toBeInTheDocument();
    expect(screen.getByText("dup.pdf")).toBeInTheDocument();
    expect(screen.getByText("That file was already uploaded.")).toBeInTheDocument();

    // One success is enough to arm the pipeline pulse + refetch the list.
    expect(armAnalysisPulse).toHaveBeenCalledTimes(1);
    expect(onUploaded).toHaveBeenCalledTimes(1);
    expect(refresh).toHaveBeenCalledTimes(1);
  });

  it("surfaces the server's own message for the multipart 400 too-large path", async () => {
    uploadEvidence.mockResolvedValueOnce(
      err(400, JSON.stringify({ message: "File is too large. Maximum upload size is 50 MB." })),
    );
    const { container } = render(<UploadCard />);
    fireEvent.change(pickerInput(container), { target: { files: [file("big.pdf")] } });
    expect(
      await screen.findByText("File is too large. Maximum upload size is 50 MB."),
    ).toBeInTheDocument();
    expect(armAnalysisPulse).not.toHaveBeenCalled();
  });

  it("rejects .doc/.docx BEFORE upload with save-as-PDF copy, while other files proceed", async () => {
    uploadEvidence.mockResolvedValueOnce(ok());
    const { container } = render(<UploadCard />);

    fireEvent.change(pickerInput(container), {
      target: { files: [file("buddy-letter.docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document"), file("nexus.pdf")] },
    });

    await waitFor(() => expect(uploadEvidence).toHaveBeenCalledTimes(1));
    // Only the PDF went up — the Word doc never hit the network.
    expect(uploadEvidence.mock.calls[0][0]).toMatchObject({ name: "nexus.pdf" });
    expect(screen.getByText(WORD_REJECT_MSG)).toBeInTheDocument();
    expect(await screen.findByText("Uploaded")).toBeInTheDocument();
  });

  it("accepts drag-and-drop onto the card", async () => {
    uploadEvidence.mockResolvedValueOnce(ok());
    const { container } = render(<UploadCard />);
    const card = container.firstElementChild as HTMLElement;

    fireEvent.drop(card, { dataTransfer: { files: [file("cp-exam.pdf")] } });

    await waitFor(() => expect(uploadEvidence).toHaveBeenCalledTimes(1));
    expect(await screen.findByText("Uploaded")).toBeInTheDocument();
  });

  it("offers the extraction-supported accept list (heic/webp in, doc/docx out) and a camera capture input", () => {
    const { container } = render(<UploadCard />);
    const accept = pickerInput(container).getAttribute("accept") ?? "";
    for (const ext of [".pdf", ".png", ".jpg", ".jpeg", ".webp", ".heic", ".txt"]) {
      expect(accept).toContain(ext);
    }
    expect(accept).not.toMatch(/\.docx?\b/);

    // Camera capture path for mobile (visibility is CSS-gated to coarse pointers).
    const camera = container.querySelector('input[type="file"][capture]');
    expect(camera).not.toBeNull();
    expect(camera).toHaveAttribute("accept", "image/*");
    expect(screen.getByRole("button", { name: "Take photo" })).toBeInTheDocument();
  });
});
