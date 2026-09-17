import { describe, expect, it } from "vitest";
import { activeKey } from "./AppShell";

// The shell derives its title + active nav tab from the pathname. The capture
// build's /dev/* fixture pages must resolve exactly like the routes they mirror,
// or every store screenshot carries the Home header and Home highlighted.
describe("activeKey", () => {
  it("maps real routes to their tab", () => {
    expect(activeKey("/")).toBe("/");
    expect(activeKey("/conditions")).toBe("/conditions");
    expect(activeKey("/conditions/detail?id=93")).toBe("/conditions");
    expect(activeKey("/steps")).toBe("/steps");
    expect(activeKey("/learn/cp-exam")).toBe("/learn");
  });

  it("treats /dev/* fixture pages like the routes they mirror", () => {
    expect(activeKey("/dev/home/populated")).toBe("/");
    expect(activeKey("/dev/conditions")).toBe("/conditions");
    expect(activeKey("/dev/conditions/93")).toBe("/conditions");
    expect(activeKey("/dev/steps")).toBe("/steps");
    expect(activeKey("/dev/documents")).toBe("/documents");
    expect(activeKey("/dev/ask")).toBe("/ask");
    expect(activeKey("/dev/share")).toBe("/share");
  });

  it("does not mistake a route that merely starts with 'dev' for the prefix", () => {
    expect(activeKey("/devices")).toBe("/");
  });
});
