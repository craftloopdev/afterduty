import { describe, it, expect } from "vitest";
import { toTriadLevel } from "./triad";

describe("toTriadLevel (backend strength → redesign 3 levels)", () => {
  it("maps strong (any case)", () => {
    expect(toTriadLevel("strong")).toBe("strong");
    expect(toTriadLevel("STRONG")).toBe("strong");
    expect(toTriadLevel(" Strong ")).toBe("strong");
  });

  it("collapses moderate/weak/partial → partial", () => {
    expect(toTriadLevel("moderate")).toBe("partial");
    expect(toTriadLevel("weak")).toBe("partial");
    expect(toTriadLevel("partial")).toBe("partial");
    expect(toTriadLevel("WEAK")).toBe("partial");
  });

  it("fail-loud default: missing/unknown/empty/null → missing", () => {
    expect(toTriadLevel("missing")).toBe("missing");
    expect(toTriadLevel("unknown")).toBe("missing");
    expect(toTriadLevel("")).toBe("missing");
    expect(toTriadLevel(null)).toBe("missing");
    expect(toTriadLevel(undefined)).toBe("missing");
    expect(toTriadLevel("garbage")).toBe("missing");
  });
});
