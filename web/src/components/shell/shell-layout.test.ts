import { describe, it, expect } from "vitest";
import { readFileSync, readdirSync } from "node:fs";
import { join } from "node:path";

/*
 * P1-23 — mobile shell geometry, locked at the stylesheet level (jsdom does
 * not compute CSS-module layout, so the contract is asserted on the source):
 *  1. The bottom nav's safe-area inset must ADD to the bar height, never eat
 *     into it (the old fixed `height: 72px` + border-box padding squeezed the
 *     tabs to ~26px on notched phones).
 *  2. The scroll content must clear the nav INCLUDING its safe-area growth.
 *  3. Form controls hold ≥16px effective font at the A− text scale so iOS
 *     never zooms on focus.
 */

const SRC = join(__dirname, "..", "..");
const read = (...p: string[]) => readFileSync(join(SRC, ...p), "utf8");

describe("MobileNav safe-area math (P1-23)", () => {
  const tokens = read("styles", "styles.css");
  const nav = read("components", "shell", "MobileNav.module.css");
  const shell = read("components", "shell", "AppShell.module.css");

  it("styles.css defines --mobile-nav-h as 72px PLUS the safe-area inset", () => {
    const def = tokens.match(/--mobile-nav-h:\s*([^;]+);/)?.[1];
    expect(def, "token layer must define --mobile-nav-h").toBeTruthy();
    expect(def).toContain("72px");
    expect(def).toContain("env(safe-area-inset-bottom");
  });

  it("the nav bar grows with the inset (min-height token) — no fixed height to squeeze the tabs", () => {
    expect(nav).toContain("min-height: var(--mobile-nav-h)");
    // The regression: a fixed height made border-box padding eat the tabs.
    expect(nav).not.toMatch(/[^-]height:\s*72px/);
    // The inset still pads the tab row off the home indicator.
    expect(nav).toMatch(/padding:[^;]*env\(safe-area-inset-bottom/);
  });

  it("the app content clears the nav including its safe-area growth", () => {
    expect(shell).toMatch(/padding-bottom:\s*calc\(var\(--mobile-nav-h\)/);
    expect(shell).not.toMatch(/padding-bottom:\s*96px/);
  });
});

describe("iOS zoom-on-focus (P1-23) — controls hold ≥16px at every text scale", () => {
  it("base.css pins input/textarea/select to max(1rem, 16px)", () => {
    const base = read("styles", "base.css");
    const controls = base.match(/input,\s*\ntextarea,\s*\nselect\s*\{[^}]+\}/);
    expect(controls, "base.css must style input/textarea/select").toBeTruthy();
    expect(controls![0]).toContain("font-size: max(1rem, 16px)");
  });

  it("no CSS module re-shrinks a control below 16px (the base guard loses on specificity)", () => {
    // Any module rule whose selector targets a control (element selector or a
    // control-named class like .input/.nameInput/.textarea) outranks base.css's
    // bare-element guard, so its font-size must carry its own max(…, 16px).
    // One bare rem here is how the persistent-zoom bug shipped (login .field
    // input at 1rem = 14.7px under data-scale="0").
    const offenders: string[] = [];
    const walk = (dir: string) => {
      for (const e of readdirSync(dir, { withFileTypes: true })) {
        const p = join(dir, e.name);
        if (e.isDirectory()) walk(p);
        else if (e.name.endsWith(".module.css")) {
          const css = readFileSync(p, "utf8");
          for (const m of css.matchAll(/([^{}]+)\{([^}]*)\}/g)) {
            const [, selector, body] = m;
            if (!/input|textarea|select/i.test(selector)) continue;
            const fontSize = body.match(/font-size:\s*([^;]+);/);
            if (fontSize && !/max\([^)]*16px\)/.test(fontSize[1])) {
              offenders.push(`${p.slice(SRC.length + 1)} → ${selector.trim()} → ${fontSize[1]}`);
            }
          }
        }
      }
    };
    walk(SRC);
    expect(offenders, `control font-size without max(…, 16px):\n${offenders.join("\n")}`).toEqual([]);
  });
});
