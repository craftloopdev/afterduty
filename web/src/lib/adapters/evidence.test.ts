import { describe, it, expect } from "vitest";
import { toDoc, toFact, toFacts } from "./evidence";

describe("toDoc", () => {
  it("maps a processed upload to the done state", () => {
    const d = toDoc({
      id: 1,
      sourceType: "upload",
      filename: "dd214.pdf",
      aiClassification: "Service",
      processingStatus: "processed",
    });
    expect(d.name).toBe("dd214.pdf");
    expect(d.kind).toBe("Service");
    expect(d.status).toBe("done");
    expect(d.statusLabel).toBe("Processed");
    expect(d.processed).toBe(true);
    expect(d.icon).toBeTruthy();
    expect(d.color).toMatch(/^var\(--/);
  });

  it("hides the pipeline's internal message on processed docs", () => {
    const d = toDoc({ id: 1, processingStatus: "processed", processingMessage: "Queued for async extraction" });
    expect(d.statusDetail).toBeNull();
  });

  // P0-3: a doc that has only been UPLOADED must never wear the green done
  // badge — "queued" (and legacy "pending") are a neutral "Uploaded".
  it.each(["queued", "pending", undefined])(
    "maps %s to a neutral Uploaded badge — not done, not spinning",
    (processingStatus) => {
      const d = toDoc({ id: 7, sourceType: "upload", filename: "str.pdf", processingStatus });
      expect(d.status).toBe("queued");
      expect(d.statusLabel).toBe("Uploaded");
      expect(d.statusDetail).toBeNull();
      expect(d.processed).toBe(false);
    },
  );

  it("maps processing to an in-flight Reading badge", () => {
    const d = toDoc({ id: 8, sourceType: "upload", filename: "cp-exam.pdf", processingStatus: "processing" });
    expect(d.status).toBe("processing");
    expect(d.statusLabel).toBe("Reading…");
    expect(d.processed).toBe(false);
  });

  // P0-3/P0-7: a free user's documents are stored, never AI-read — Documents
  // must agree with Home's upgrade framing instead of claiming "Processed".
  it.each(["queued", "pending", "processed"])(
    "shows free users an honest stored badge for %s docs",
    (processingStatus) => {
      const d = toDoc(
        { id: 9, sourceType: "upload", filename: "dd214.jpg", processingStatus },
        { free: true },
      );
      expect(d.status).toBe("queued");
      expect(d.statusLabel).toBe("Stored — AI analysis is a Pro feature");
      expect(d.processed).toBe(false);
    },
  );

  it("keeps error and paused honest for free users too", () => {
    const err = toDoc(
      { id: 10, processingStatus: "error", processingMessage: "Couldn't read the file." },
      { free: true },
    );
    expect(err.status).toBe("error");
    expect(err.statusDetail).toBe("Couldn't read the file.");
    const paused = toDoc({ id: 11, processingStatus: "deferred_usage_limit" }, { free: true });
    expect(paused.status).toBe("paused");
  });

  it("maps error to a friendly label and surfaces the pipeline's message", () => {
    const d = toDoc({
      id: 2,
      sourceType: "upload",
      filename: "blurry-scan.pdf",
      processingStatus: "error",
      processingMessage: "Extraction failed: the file couldn't be read.",
    });
    expect(d.status).toBe("error");
    expect(d.statusLabel).toBe("Couldn't process");
    expect(d.statusDetail).toBe("Extraction failed: the file couldn't be read.");
    expect(d.processed).toBe(false);
  });

  it("maps deferred_usage_limit to a paused state without leaking the raw enum", () => {
    const d = toDoc({
      id: 3,
      sourceType: "upload",
      filename: "buddy-letter.pdf",
      processingStatus: "deferred_usage_limit",
      processingMessage: "Paused — plan limit reached. Resumes 2026-07-01.",
    });
    expect(d.status).toBe("paused");
    expect(d.statusLabel).toBe("Paused — monthly AI limit reached");
    expect(d.statusDetail).toBe("Paused — plan limit reached. Resumes 2026-07-01.");
    expect(d.statusLabel).not.toMatch(/deferred|usage_limit/i);
    expect(d.processed).toBe(false);
  });

  it("treats an unknown future status as in flight", () => {
    const d = toDoc({ id: 4, sourceType: "upload", filename: "orders.pdf", processingStatus: "some_future_status" });
    expect(d.status).toBe("processing");
    expect(d.statusLabel).toBe("Processing");
    expect(d.processed).toBe(false);
  });

  it("falls back for missing fields and infers kind from sourceType", () => {
    const d = toDoc({ id: 5, sourceType: "chat", processingStatus: "pending" });
    expect(d.name).toBe("Untitled");
    expect(d.kind).toBe("Statement");
    expect(d.processed).toBe(false);
  });

  it("defaults kind to Document for an unknown source", () => {
    expect(toDoc({ id: 6 }).kind).toBe("Document");
  });
});

// P1-30 "What we found": AtomDto → FactVM. Deterministic mapping of what the
// API returned — never client synthesis, never the LLM confidence number.
describe("toFact / toFacts", () => {
  it("maps the wire fact and prettifies the snake_case type", () => {
    const f = toFact({
      type: "service_period",
      value: "Active duty 2008–2012, U.S. Army",
      source: "dd214.pdf",
      confidence: 0.97,
      date: "2012-06-01",
    });
    expect(f).toEqual({
      label: "Service period",
      value: "Active duty 2008–2012, U.S. Army",
      date: "2012-06-01",
    });
    // The VM must not carry the LLM-derived confidence at all.
    expect(f && "confidence" in f).toBe(false);
  });

  it("drops facts with no value — they carry no information", () => {
    expect(toFact({ type: "diagnosis", value: "  " })).toBeNull();
    expect(toFact({ type: "diagnosis" })).toBeNull();
  });

  it("falls back to a generic label for a missing type and null for a blank date", () => {
    const f = toFact({ value: "Tinnitus noted at separation exam", date: "" });
    expect(f).toEqual({ label: "Fact", value: "Tinnitus noted at separation exam", date: null });
  });

  it("toFacts maps arrays defensively and ignores junk entries", () => {
    const facts = toFacts([
      { type: "diagnosis", value: "PTSD" },
      { type: "empty", value: "" },
      null,
      "junk",
      { type: "medication", value: "Sertraline 50mg", date: "2024-01-10" },
    ]);
    expect(facts).toEqual([
      { label: "Diagnosis", value: "PTSD", date: null },
      { label: "Medication", value: "Sertraline 50mg", date: "2024-01-10" },
    ]);
  });

  it("toFacts returns an empty list for non-array bodies", () => {
    expect(toFacts(null)).toEqual([]);
    expect(toFacts({ error: "upstream" })).toEqual([]);
    expect(toFacts(undefined)).toEqual([]);
  });
});
