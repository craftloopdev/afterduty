import { describe, it, expect } from "vitest";
import type { ServicePeriodVM, ServiceSourceVM } from "@/lib/models/vm";
import {
  adoptedFlags,
  authorityLabel,
  correctedTotalYears,
  docTypeLabel,
  isCorrectedByYou,
  sourceHeading,
} from "./service-history";

const src = (over: Partial<ServiceSourceVM> = {}): ServiceSourceVM => ({
  evidenceId: 1,
  docType: "DD-214",
  authorityRank: 100,
  rawBranch: "Army",
  rawStart: "2003-06-10",
  rawEnd: "2007-06-09",
  rawMos: "11B",
  rawRank: "SGT",
  ...over,
});

const period = (over: Partial<ServicePeriodVM> = {}): ServicePeriodVM => ({
  branch: "Army",
  component: "active",
  startDate: "2003-06-10",
  endDate: "2007-06-09",
  mos: "11B",
  rank: "SGT",
  source: "documents",
  sources: null,
  reasoning: null,
  totalYears: 4,
  clusterKey: "Army|active|2003",
  ...over,
});

describe("isCorrectedByYou — reads the override marker off the reasoning trace", () => {
  it("is true when the reasoning carries 'Corrected by you'", () => {
    expect(isCorrectedByYou({ reasoning: "Merged 2 records. Corrected by you." })).toBe(true);
    expect(isCorrectedByYou({ reasoning: "corrected by you" })).toBe(true); // case-insensitive
  });
  it("is false without the marker, or with no reasoning", () => {
    expect(isCorrectedByYou({ reasoning: "Merged 2 records for this enlistment." })).toBe(false);
    expect(isCorrectedByYou({ reasoning: null })).toBe(false);
  });
});

describe("docTypeLabel — calm, familiar doc names", () => {
  it("maps well-known classifier tokens (case/underscore-insensitive)", () => {
    expect(docTypeLabel("DD-214")).toBe("DD-214");
    expect(docTypeLabel("dd214")).toBe("DD-214");
    expect(docTypeLabel("dd_form_214")).toBe("DD-214");
    expect(docTypeLabel("personnel_record")).toBe("Personnel record");
    expect(docTypeLabel("orders")).toBe("Orders");
    expect(docTypeLabel("manual")).toBe("Self-statement");
    expect(docTypeLabel("NGB-22")).toBe("NGB-22");
  });

  it("title-cases an unknown token rather than showing a raw slug", () => {
    expect(docTypeLabel("enlistment_contract")).toBe("Enlistment Contract");
  });

  it("keeps short acronyms intact", () => {
    expect(docTypeLabel("VA form")).toBe("VA Form");
  });

  it("falls back to 'Document' when absent", () => {
    expect(docTypeLabel(null)).toBe("Document");
    expect(docTypeLabel("  ")).toBe("Document");
  });
});

describe("authorityLabel — buckets, not raw scores", () => {
  it("buckets by rank", () => {
    expect(authorityLabel(100)).toBe("authoritative");
    expect(authorityLabel(90)).toBe("authoritative");
    expect(authorityLabel(60)).toBe("official record");
    expect(authorityLabel(40)).toBe("supporting");
  });
  it("names a self-statement (manual doc) as self-reported regardless of rank", () => {
    expect(authorityLabel(0, "manual")).toBe("self-reported");
    expect(authorityLabel(null, "manual")).toBe("self-reported");
  });
  it("returns null when there is no rank to describe", () => {
    expect(authorityLabel(null)).toBeNull();
  });
});

describe("sourceHeading — 'DD-214 · authoritative'", () => {
  it("joins doc type and authority", () => {
    expect(sourceHeading(src())).toBe("DD-214 · authoritative");
    expect(sourceHeading(src({ docType: "personnel_record", authorityRank: 40 }))).toBe(
      "Personnel record · supporting",
    );
  });
  it("drops the authority half when the rank is unknown", () => {
    expect(sourceHeading(src({ docType: "orders", authorityRank: null }))).toBe("Orders");
  });
});

describe("adoptedFlags — ✓ on the values the conclusion took from this source", () => {
  it("marks every field the DD-214 supplied when the conclusion adopted them", () => {
    const flags = adoptedFlags(src(), period());
    expect(flags).toEqual({ branch: true, dates: true, mos: true, rank: true });
  });

  it("does not mark values the conclusion did NOT adopt (the lower-authority record)", () => {
    // A personnel record that disagreed on branch text, start date, MOS, rank —
    // the conclusion took the DD-214's values, so none of these are adopted.
    const personnel = src({
      docType: "personnel_record",
      authorityRank: 40,
      rawBranch: "U.S. Army",
      rawStart: "2003-06-01",
      rawEnd: "2007-06-09",
      rawMos: "11 Bravo",
      rawRank: "Sergeant",
    });
    const flags = adoptedFlags(personnel, period());
    expect(flags.branch).toBe(false); // "U.S. Army" ≠ adopted "Army"
    expect(flags.mos).toBe(false); // "11 Bravo" ≠ "11B"
    expect(flags.rank).toBe(false); // "Sergeant" ≠ "SGT"
    // Start disagreed (06-01 vs 06-10), so the whole date span is not adopted
    // even though the end matched.
    expect(flags.dates).toBe(false);
  });

  it("matches dates on the calendar day, ignoring time/format noise", () => {
    const s = src({ rawStart: "2003-06-10T00:00:00Z", rawEnd: "2007-06-09" });
    expect(adoptedFlags(s, period()).dates).toBe(true);
  });

  it("matches text case-insensitively after collapsing whitespace", () => {
    const s = src({ rawRank: " sgt " });
    expect(adoptedFlags(s, period({ rank: "SGT" })).rank).toBe(true);
  });

  it("never marks a raw value the source didn't carry", () => {
    const s = src({ rawMos: null, rawRank: null });
    const flags = adoptedFlags(s, period());
    expect(flags.mos).toBe(false);
    expect(flags.rank).toBe(false);
  });
});

describe("correctedTotalYears — reconciled sum with a date fallback", () => {
  it("sums the conclusions' own totalYears when reconciled", () => {
    const periods = [
      period({ totalYears: 4 }),
      period({ startDate: "2008-03-01", endDate: "2012-03-01", totalYears: 4 }),
    ];
    expect(correctedTotalYears(periods)).toBe(8);
  });

  it("falls back to date arithmetic when no period carries a totalYears", () => {
    const periods = [
      period({ totalYears: null }),
      period({ startDate: "2008-03-01", endDate: "2012-03-01", totalYears: null }),
    ];
    // ~4 + ~4 years by date span.
    expect(correctedTotalYears(periods)).toBe(8);
  });

  it("returns null when nothing yields a ≥1-year figure", () => {
    expect(
      correctedTotalYears([period({ startDate: null, endDate: null, totalYears: null })]),
    ).toBeNull();
  });
});
