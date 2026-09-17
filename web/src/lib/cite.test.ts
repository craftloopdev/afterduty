import { describe, it, expect } from "vitest";
import { parseCiteHref } from "./cite";

describe("parseCiteHref", () => {
  it("classifies a Part 4 CFR citation", () => {
    expect(parseCiteHref("cite:cfr/4.71a")).toEqual({
      kind: "cfr",
      section: "4.71a",
      href: "https://www.ecfr.gov/current/title-38/chapter-I/part-4/section-4.71a",
    });
  });
  it("classifies a Part 3 CFR citation", () => {
    expect(parseCiteHref("cite:cfr/3.309")).toEqual({
      kind: "cfr",
      section: "3.309",
      href: "https://www.ecfr.gov/current/title-38/chapter-I/part-3/section-3.309",
    });
  });
  it("classifies a doc citation to the documents page", () => {
    expect(parseCiteHref("cite:doc/42")).toEqual({
      kind: "doc",
      evidenceId: 42,
      href: "/documents?focus=42",
    });
  });
  it("returns null for a non-cite href", () => {
    expect(parseCiteHref("https://example.com")).toBeNull();
    expect(parseCiteHref("/documents")).toBeNull();
  });
  it("returns null for unknown cite kinds", () => {
    expect(parseCiteHref("cite:foo/bar")).toBeNull();
  });
  it("returns null for a CFR section in neither Part 3 nor 4", () => {
    expect(parseCiteHref("cite:cfr/5.1")).toBeNull();
  });
  it("returns null for a non-numeric doc id", () => {
    expect(parseCiteHref("cite:doc/abc")).toBeNull();
    expect(parseCiteHref("cite:doc/0")).toBeNull();
  });
  it("returns null for malformed forms", () => {
    expect(parseCiteHref("cite:cfr")).toBeNull();
    expect(parseCiteHref("cite:doc/")).toBeNull();
    expect(parseCiteHref(undefined)).toBeNull();
    expect(parseCiteHref(null)).toBeNull();
  });
});
