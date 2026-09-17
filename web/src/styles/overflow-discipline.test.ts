/*
 * Overflow discipline (drift net for the 2026-07 truncation sweep).
 *
 * Long pipeline-generated content (condition names, gap titles, presumptive
 * bases, filenames, emails) meets CSS written against short fixture strings.
 * The house rules:
 *
 *   1. `white-space: nowrap` on DATA-BEARING text must also carry
 *      `overflow: hidden` + `text-overflow: ellipsis` (and sit in a bounded
 *      min-width:0 chain) so it truncates instead of blowing the row width.
 *   2. Fixed UI copy (button/tab/chip labels, stat captions, money figures)
 *      may keep a plain nowrap — but ONLY via the explicit allowlist below,
 *      so every future nowrap is a conscious decision.
 *
 * This test reads every web/src CSS Module at test time, parses its rule
 * blocks, and fails on any block that declares `white-space: nowrap` without
 * the ellipsis pair unless "<relative-path>#<first-class>" is allowlisted.
 */

import { describe, expect, it } from "vitest";
import { readdirSync, readFileSync } from "node:fs";
import { join, relative, sep } from "node:path";

/**
 * Audited plain-nowrap blocks (classification “(a) fixed UI copy” from the
 * sweep). Key = `<path relative to src, posix slashes>#<first class in the
 * selector>`; value = one-word reason.
 */
const ALLOWLIST: Record<string, string> = {
  "components/usage/UsageBreakdown.module.css#amount": "numeric", // short "$X.XX · N calls", flex-shrink:0
  "components/ui/Button.module.css#btn": "button",
  "components/ui/Chip.module.css#chip": "filter",
  "components/ui/Pill.module.css#pill": "badge", // wrap variant exists for long data
  "components/ui/StatusTag.module.css#tag": "status",
  "components/home/DoThisNext.module.css#primaryCta": "button",
  "components/steps/ScenariosPanel.module.css#pay": "money",
  "components/steps/ScenariosPanel.module.css#unavail": "fixed-copy",
  "components/profile/ServicePeriodsCard.module.css#totalYears": "numeric",
  "components/profile/ServicePeriodsCard.module.css#sourceChip": "fixed-copy",
  "components/profile/ServiceHistoryView.module.css#sourcesCtl": "fixed-copy", // "N sources ▸" pill, flex-shrink:0
  "components/profile/ServiceHistoryView.module.css#srOnly": "sr-only", // visually-hidden a11y text, not rendered
};

const SRC_ROOT = join(__dirname, "..");

function cssModuleFiles(dir: string): string[] {
  const out: string[] = [];
  for (const entry of readdirSync(dir, { withFileTypes: true })) {
    const full = join(dir, entry.name);
    if (entry.isDirectory()) out.push(...cssModuleFiles(full));
    else if (entry.isFile() && entry.name.endsWith(".module.css")) out.push(full);
  }
  return out;
}

interface RuleBlock {
  selector: string;
  body: string;
}

/**
 * Tiny CSS block parser: strips comments, then walks braces. Grouping at-rules
 * (@media, @supports, @keyframes, …) are descended into; leaf blocks are
 * captured as selector+body. CSS Modules in this repo don't nest style rules,
 * so a leaf block is any `selector { declarations }` without inner braces.
 */
function parseBlocks(css: string): RuleBlock[] {
  const src = css.replace(/\/\*[\s\S]*?\*\//g, "");
  const blocks: RuleBlock[] = [];
  let selStart = 0;
  let i = 0;

  function walk(): void {
    while (i < src.length) {
      const ch = src[i];
      if (ch === "{") {
        const selector = src.slice(selStart, i).trim();
        i += 1;
        if (selector.startsWith("@") && !selector.startsWith("@font-face")) {
          // grouping at-rule — recurse into its contents
          selStart = i;
          walk();
          selStart = i;
        } else {
          const bodyStart = i;
          while (i < src.length && src[i] !== "}" && src[i] !== "{") i += 1;
          if (src[i] === "{") {
            // unexpected nesting — treat the outer prelude as grouping
            selStart = bodyStart;
            continue;
          }
          blocks.push({ selector, body: src.slice(bodyStart, i) });
          i += 1; // past '}'
          selStart = i;
        }
      } else if (ch === "}") {
        i += 1; // close of a grouping at-rule
        selStart = i;
        return;
      } else {
        i += 1;
      }
    }
  }

  walk();
  return blocks;
}

const has = (body: string, re: RegExp) => re.test(body);

describe("overflow discipline: white-space nowrap needs ellipsis or an allowlist entry", () => {
  const files = cssModuleFiles(SRC_ROOT);

  it("finds the CSS modules (sanity)", () => {
    expect(files.length).toBeGreaterThan(30);
  });

  it("every nowrap block is truncation-safe or consciously allowlisted", () => {
    const violations: string[] = [];
    const usedAllowlist = new Set<string>();

    for (const file of files) {
      const rel = relative(SRC_ROOT, file).split(sep).join("/");
      for (const block of parseBlocks(readFileSync(file, "utf8"))) {
        if (!has(block.body, /white-space\s*:\s*nowrap/)) continue;
        const truncates =
          has(block.body, /overflow\s*:\s*hidden/) &&
          has(block.body, /text-overflow\s*:\s*ellipsis/);
        if (truncates) continue;
        const firstClass = /\.([A-Za-z0-9_-]+)/.exec(block.selector)?.[1] ?? block.selector;
        const key = `${rel}#${firstClass}`;
        if (key in ALLOWLIST) {
          usedAllowlist.add(key);
          continue;
        }
        violations.push(
          `${key} (selector "${block.selector}") declares white-space: nowrap without ` +
            `overflow: hidden + text-overflow: ellipsis. Either make it truncate, let it ` +
            `wrap, or — if it renders FIXED UI copy only — add it to the ALLOWLIST in ` +
            `overflow-discipline.test.ts with a reason.`,
        );
      }
    }

    expect(violations, violations.join("\n")).toEqual([]);

    // Keep the allowlist honest: every entry must still exist in the CSS.
    const stale = Object.keys(ALLOWLIST).filter((k) => !usedAllowlist.has(k));
    expect(stale, `stale ALLOWLIST entries (nowrap removed or renamed): ${stale.join(", ")}`).toEqual(
      [],
    );
  });
});
