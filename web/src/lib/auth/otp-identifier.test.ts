import { describe, it, expect } from "vitest";
import { classify, toE164US } from "./otp-identifier";

describe("toE164US", () => {
  it("prepends +1 to a bare 10-digit US number", () => {
    expect(toE164US("5555550123")).toBe("+15555550123");
    expect(toE164US("(555) 555-0123")).toBe("+15555550123");
    expect(toE164US("555-555-0123")).toBe("+15555550123");
  });

  it("normalizes an 11-digit number that leads with 1", () => {
    expect(toE164US("15555550123")).toBe("+15555550123");
    expect(toE164US("1 (555) 555-0123")).toBe("+15555550123");
  });

  it("keeps an already-+ number when 8–15 digits", () => {
    expect(toE164US("+15555550123")).toBe("+15555550123");
    expect(toE164US("+44 20 7946 0958")).toBe("+442079460958");
  });

  it("rejects too-short / too-long / non-US-shaped input", () => {
    expect(toE164US("")).toBeNull();
    expect(toE164US("12345")).toBeNull(); // 5 digits, no +
    expect(toE164US("25555550123")).toBeNull(); // 11 digits not leading 1
    expect(toE164US("+1")).toBeNull(); // only 1 digit after +
    expect(toE164US("+1234567890123456")).toBeNull(); // 16 digits after +
  });
});

describe("classify", () => {
  it("routes anything containing @ to email (lower-cased), even with a digit run", () => {
    expect(classify("You@Example.com")).toEqual({ kind: "email", email: "you@example.com" });
    // An email whose local part is a 10-digit run must NOT be mis-read as a phone.
    expect(classify("5551234567@gmail.com")).toEqual({
      kind: "email",
      email: "5551234567@gmail.com",
    });
    expect(classify("name.5551234567@gmail.com")).toEqual({
      kind: "email",
      email: "name.5551234567@gmail.com",
    });
  });

  it("routes a @-free, phone-shaped value to phone (E.164)", () => {
    expect(classify("(555) 555-0123")).toEqual({ kind: "phone", e164: "+15555550123" });
    expect(classify("+15555550123")).toEqual({ kind: "phone", e164: "+15555550123" });
  });

  it("returns invalid for a @-free value that is not a valid phone", () => {
    expect(classify("not-a-thing")).toEqual({ kind: "invalid" });
    expect(classify("12345")).toEqual({ kind: "invalid" });
    expect(classify("   ")).toEqual({ kind: "invalid" });
  });
});
