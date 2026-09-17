import { describe, it, expect } from "vitest";
import {
  detectUsageLimit,
  formatResumeDate,
  usageLimitBody,
  usageLimitCopy,
} from "./usageLimit";

// P1-10: the monthly AI cap must be recognized from EITHER wire shape the
// backend may emit — a bare 429 status, or a body code USAGE_LIMIT_REACHED
// (today's deployed backend sends the code on a 402) — and must never be
// confused with an ordinary subscription 402 or a 5xx.

describe("detectUsageLimit", () => {
  it("classifies a 429 status as the cap regardless of body", () => {
    expect(detectUsageLimit(429, null)).toEqual({});
    expect(detectUsageLimit(429, { error: "rate_limited" })).toEqual({});
  });

  it("classifies a 402 with body code USAGE_LIMIT_REACHED as the cap (today's backend)", () => {
    expect(detectUsageLimit(402, { code: "USAGE_LIMIT_REACHED" })).toEqual({});
  });

  it("accepts the code in the `error` field and any casing", () => {
    expect(detectUsageLimit(402, { error: "usage_limit_reached" })).toEqual({});
    expect(detectUsageLimit(402, { error: "Usage_Limit_Reached" })).toEqual({});
  });

  it("extracts resumesAt (camel and snake case)", () => {
    expect(detectUsageLimit(429, { resumesAt: "2026-08-01T00:00:00Z" })).toEqual({
      resumesAt: "2026-08-01T00:00:00Z",
    });
    expect(
      detectUsageLimit(402, { code: "USAGE_LIMIT_REACHED", resumes_at: "2026-08-01T00:00:00Z" }),
    ).toEqual({ resumesAt: "2026-08-01T00:00:00Z" });
  });

  it("classifies an SSE error payload alone (status 0)", () => {
    expect(detectUsageLimit(0, { code: "USAGE_LIMIT_REACHED", resumesAt: "2026-08-01T00:00:00Z" }))
      .toEqual({ resumesAt: "2026-08-01T00:00:00Z" });
    expect(detectUsageLimit(0, { code: "rate_limited" })).toBeNull();
  });

  it("does NOT classify a plain subscription 402 or a 5xx as the cap", () => {
    expect(detectUsageLimit(402, { error: "subscription_required" })).toBeNull();
    expect(detectUsageLimit(402, null)).toBeNull();
    expect(detectUsageLimit(502, { error: "upstream" })).toBeNull();
  });
});

describe("formatResumeDate", () => {
  const now = new Date("2026-07-02T12:00:00Z");

  it("humanizes an ISO timestamp to 'Month D' (never raw ISO)", () => {
    expect(formatResumeDate("2026-08-01T12:00:00Z", now)).toBe("August 1");
  });

  it("appends the year when it differs from the current one", () => {
    expect(formatResumeDate("2027-01-15T12:00:00Z", now)).toBe("January 15, 2027");
  });

  it("returns null for absent or unparseable input", () => {
    expect(formatResumeDate(undefined, now)).toBeNull();
    expect(formatResumeDate(null, now)).toBeNull();
    expect(formatResumeDate("not-a-date", now)).toBeNull();
  });
});

describe("usageLimitCopy", () => {
  it("interpolates the humanized reset date", () => {
    expect(usageLimitCopy("2099-08-01T12:00:00Z")).toBe(
      "You've reached this month's AI limit — chat resumes August 1, 2099.",
    );
  });

  it("degrades to 'next month' when the backend sent no date", () => {
    expect(usageLimitCopy(undefined)).toBe(
      "You've reached this month's AI limit — chat resumes next month.",
    );
  });
});

describe("usageLimitBody", () => {
  it("emits the uniform BFF wire shape, omitting resumesAt when absent", () => {
    expect(usageLimitBody("2026-08-01T00:00:00Z")).toEqual({
      error: "usage_limit_reached",
      code: "USAGE_LIMIT_REACHED",
      resumesAt: "2026-08-01T00:00:00Z",
    });
    expect(usageLimitBody()).toEqual({
      error: "usage_limit_reached",
      code: "USAGE_LIMIT_REACHED",
    });
  });
});
