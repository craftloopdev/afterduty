import { describe, it, expect } from "vitest";
import {
  displayName,
  firstName,
  formatPhoneUS,
  isSyntheticEmail,
  serviceTotalYears,
} from "./format";

describe("displayName", () => {
  it("returns a real name as-is", () => {
    expect(displayName({ name: "Sean OBryan", email: "s@x.com" })).toBe("Sean OBryan");
  });
  it("falls back from a Firebase UID to the email local part", () => {
    expect(displayName({ name: "dB34I0uAgdXTBTL7bDq5kzrAb0M2", email: "sean@x.com" })).toBe("sean");
  });
  it("returns empty when only a UID and no email (phone sign-in)", () => {
    expect(displayName({ name: "dB34I0uAgdXTBTL7bDq5kzrAb0M2", email: "" })).toBe("");
  });
  it("firstName takes the first token", () => {
    expect(firstName({ name: "Sean OBryan", email: "" })).toBe("Sean");
    expect(firstName({ name: "dB34I0uAgdXTBTL7bDq5kzrAb0M2", email: "" })).toBe("");
  });
});

describe("looksLikeUid hardening (overflow/greeting bug 2026-07-02)", () => {
  it("drops ANY 20+ char single token, charset-agnostic", () => {
    // The strict [A-Za-z0-9_-] charset let a near-uid variant reach the
    // "Welcome back, dB34I0uAg…" greeting in prod.
    expect(displayName({ name: "dB34I0uAgdXTBTL7bDq5kzrAb0M2", email: "" })).toBe("");
    expect(displayName({ name: "dB34I0uAgdXTBTL7bDq5kzr·b0M2", email: "" })).toBe("");
  });

  it("keeps long REAL names (they contain spaces)", () => {
    expect(displayName({ name: "Bartholomew Montgomery Fitzgerald", email: "" })).toBe(
      "Bartholomew Montgomery Fitzgerald",
    );
  });
});

describe("displayName — preferredName + synthetic-email hardening (profile 2026-07-03)", () => {
  const UID = "dB34I0uAgdXTBTL7bDq5kzrAb0M2";

  it("prefers preferredName over everything else", () => {
    expect(displayName({ name: "Sean OBryan", email: "s@x.com", preferredName: "Gunny" })).toBe(
      "Gunny",
    );
  });

  it("ignores a blank/whitespace preferredName", () => {
    expect(displayName({ name: "Sean OBryan", email: "", preferredName: "  " })).toBe(
      "Sean OBryan",
    );
    expect(displayName({ name: "Sean OBryan", email: "", preferredName: null })).toBe(
      "Sean OBryan",
    );
  });

  it("NEVER falls back to a synthetic @firebase.local email — the uid leaked via its local part", () => {
    expect(displayName({ name: UID, email: `${UID}@firebase.local` })).toBe("");
    // Any-case suffix — the guard is structural, not literal.
    expect(displayName({ name: UID, email: `${UID}@Firebase.LOCAL` })).toBe("");
  });

  it("drops a synthetic email COPIED into name (contains '@', so the uid guard misses it)", () => {
    expect(displayName({ name: `${UID}@firebase.local`, email: null })).toBe("");
  });

  it("preferredName rescues an otherwise-synthetic account", () => {
    expect(
      displayName({ name: UID, email: `${UID}@firebase.local`, preferredName: "Sean" }),
    ).toBe("Sean");
  });

  it("still uses a REAL email's local part", () => {
    expect(displayName({ name: UID, email: "sean@example.com" })).toBe("sean");
  });
});

describe("isSyntheticEmail", () => {
  it("flags @firebase.local in any case, and nothing else", () => {
    expect(isSyntheticEmail("abc@firebase.local")).toBe(true);
    expect(isSyntheticEmail("ABC@FIREBASE.LOCAL")).toBe(true);
    expect(isSyntheticEmail("vet@example.com")).toBe(false);
    expect(isSyntheticEmail("")).toBe(false);
    expect(isSyntheticEmail(null)).toBe(false);
    expect(isSyntheticEmail(undefined)).toBe(false);
  });
});

describe("formatPhoneUS", () => {
  it("formats an E.164 US number", () => {
    expect(formatPhoneUS("+12025550147")).toBe("(202) 555-0147");
  });
  it("renders non-US numbers as-is", () => {
    expect(formatPhoneUS("+447911123456")).toBe("+447911123456");
  });
  it("renders unparseable strings as-is (never mangled)", () => {
    expect(formatPhoneUS("202-555-0147")).toBe("202-555-0147");
    expect(formatPhoneUS("+1202555014")).toBe("+1202555014"); // 9 digits — not US-shaped
  });
  it("returns null for absent/blank input", () => {
    expect(formatPhoneUS(null)).toBeNull();
    expect(formatPhoneUS(undefined)).toBeNull();
    expect(formatPhoneUS("  ")).toBeNull();
  });
});

describe("serviceTotalYears", () => {
  const NOW = new Date("2026-07-03T00:00:00Z");

  it("sums closed periods to whole years", () => {
    expect(
      serviceTotalYears(
        [
          { startDate: "2003-01-15", endDate: "2007-01-20" },
          { startDate: "2008-03-01", endDate: "2012-02-28" },
        ],
        NOW,
      ),
    ).toBe(8);
  });

  it("treats a null endDate as 'still serving' (now)", () => {
    expect(serviceTotalYears([{ startDate: "2020-07-01", endDate: null }], NOW)).toBe(6);
  });

  it("returns null when no period has a usable start date", () => {
    expect(serviceTotalYears([{ startDate: null, endDate: "2012-01-01" }], NOW)).toBeNull();
    expect(serviceTotalYears([], NOW)).toBeNull();
    expect(serviceTotalYears([{ startDate: "garbage", endDate: null }], NOW)).toBeNull();
  });

  it("returns null when the total rounds below one year", () => {
    expect(
      serviceTotalYears([{ startDate: "2026-01-01", endDate: "2026-04-01" }], NOW),
    ).toBeNull();
  });
});
