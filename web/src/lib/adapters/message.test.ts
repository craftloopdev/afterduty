import { describe, it, expect } from "vitest";
import { toMessage } from "./message";

describe("toMessage", () => {
  it("maps a user message", () => {
    expect(
      toMessage({ id: 1, role: "USER", content: "  hi  ", createdAt: "2026-06-11T00:00:00Z" }, 0),
    ).toEqual({ id: "1", role: "user", content: "hi", createdAt: "2026-06-11T00:00:00Z" });
  });
  it("treats any non-user role as assistant", () => {
    expect(toMessage({ id: 2, role: "assistant", content: "yo" }, 0).role).toBe("assistant");
    expect(toMessage({ id: 3, role: "system", content: "x" }, 0).role).toBe("assistant");
  });
  it("falls back to an index-based id when none is given", () => {
    expect(toMessage({ role: "user", content: "q" }, 5).id).toBe("m-5");
  });
  it("trims content and tolerates missing fields", () => {
    expect(toMessage({}, 0)).toEqual({ id: "m-0", role: "assistant", content: "", createdAt: undefined });
  });
});
