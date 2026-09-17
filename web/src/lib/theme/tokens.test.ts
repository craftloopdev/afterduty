import { describe, it, expect } from "vitest";
import { readFileSync, readdirSync, statSync } from "node:fs";
import { join } from "node:path";
import {
  statusColors,
  pillColors,
  triadLeg,
  resolveStatusIcon,
  resolveDocIcon,
} from "./tokens";

const HEX = /#[0-9a-fA-F]{3,8}/;
const isVarOrTransparent = (v: string) => v.startsWith("var(--") || v === "transparent";

describe("tokens (no raw hex — design decisions resolve to CSS vars)", () => {
  it("statusColors returns only CSS vars, never hex", () => {
    for (const lvl of ["strong", "partial", "missing"] as const) {
      const c = statusColors(lvl);
      for (const v of [c.fg, c.bg, c.dot]) {
        expect(v).not.toMatch(HEX);
        expect(isVarOrTransparent(v)).toBe(true);
      }
      expect(c.label).toBeTruthy();
    }
  });

  it("pillColors returns only CSS vars or transparent", () => {
    for (const tone of ["green", "indigo", "amber", "line"] as const) {
      const { fg, bg } = pillColors(tone);
      expect(fg).not.toMatch(HEX);
      expect(bg).not.toMatch(HEX);
      expect(isVarOrTransparent(fg)).toBe(true);
      expect(isVarOrTransparent(bg)).toBe(true);
    }
  });

  it("triadLeg colors are leg CSS vars with icons + labels", () => {
    for (const k of ["dx", "is", "nx"] as const) {
      const leg = triadLeg(k);
      expect(leg.color).toMatch(/^var\(--leg-/);
      expect(leg.icon).toBeTruthy();
      expect(leg.label).toBeTruthy();
    }
  });

  it("resolveStatusIcon maps each level", () => {
    expect(resolveStatusIcon("strong")).toBe("check");
    expect(resolveStatusIcon("partial")).toBe("alert");
    expect(resolveStatusIcon("missing")).toBe("dash");
  });

  it("resolveDocIcon classifies kinds and returns var colors", () => {
    expect(resolveDocIcon("Service").icon).toBe("flag");
    expect(resolveDocIcon("VA-Blue-Button-LABS").icon).toBe("medical");
    expect(resolveDocIcon("Personal Statement").icon).toBe("letter");
    expect(resolveDocIcon("whatever").icon).toBe("file");
    expect(resolveDocIcon(null).color).toMatch(/^var\(--/);
  });
});

/* ── P1-24: the CSS token layer itself is part of the contract ──────────── */

const SRC = join(__dirname, "..", "..");
const stylesCss = readFileSync(join(SRC, "styles", "styles.css"), "utf8");

/** The `.ad-dark { … }` block (dark palette) as raw text. */
function darkBlock(): string {
  // Not a bare indexOf(".ad-dark") — the file's header comment mentions it too.
  const start = stylesCss.search(/^\.ad-dark\s*\{/m);
  expect(start, "styles.css must have a .ad-dark rule").toBeGreaterThan(-1);
  const open = stylesCss.indexOf("{", start);
  const close = stylesCss.indexOf("}", open);
  return stylesCss.slice(open + 1, close);
}

function* cssFiles(dir: string): Generator<string> {
  for (const name of readdirSync(dir)) {
    const p = join(dir, name);
    if (statSync(p).isDirectory()) {
      if (name === "node_modules") continue;
      yield* cssFiles(p);
    } else if (name.endsWith(".css")) {
      yield p;
    }
  }
}

describe("dark palette (P1-24) — navy scale + focus must remap", () => {
  it(".ad-dark remaps --navy away from the light-theme value", () => {
    const dark = darkBlock();
    const darkNavy = dark.match(/--navy:\s*([^;]+);/)?.[1]?.trim();
    expect(darkNavy, ".ad-dark must remap --navy (P1-24)").toBeTruthy();
    // The light value (#1B2A4A on a #111a30 card ≈ 1.2:1) must not survive.
    const rootNavy = stylesCss.match(/:root[^}]*--navy:\s*([^;]+);/)?.[1]?.trim();
    expect(darkNavy).not.toBe(rootNavy);
  });

  it(".ad-dark remaps the companion navy scale and the focus ring", () => {
    const dark = darkBlock();
    for (const token of ["--navy500:", "--navy700:", "--navy50:", "--focus:"]) {
      expect(dark, `.ad-dark must remap ${token}`).toContain(token);
    }
  });

  it("no stylesheet uses the color-only `outline: var(--focus)` shorthand (renders NO ring)", () => {
    for (const file of cssFiles(SRC)) {
      const css = readFileSync(file, "utf8");
      expect(
        /outline:\s*var\(--focus\)\s*;/.test(css),
        `${file} uses the outline color-only shorthand — outline-style defaults to none, so no focus ring renders; use \`outline: 2px solid var(--focus)\``,
      ).toBe(false);
    }
  });
});
