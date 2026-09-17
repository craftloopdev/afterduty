#!/usr/bin/env node
// Native export wrapper (capacitor-ios-spec §A.1/§A.5). The static `output:"export"`
// build cannot include the BFF route handlers under `app/api/*` (they read
// `Request`/`cookies()` and have dynamic `[token]` params — none of which export)
// nor the web-only `/__/auth` proxy. Natively these don't exist: every consumer
// talks straight to Spring via the DirectApiClient (§A.5). Rather than fork the
// page tree, we temporarily MOVE the web-only route trees out of `app/` for the
// duration of the export build, then ALWAYS restore them (even on failure) so the
// working tree is untouched and the web build stays byte-identical.
//
// Usage: node scripts/native-export.mjs [--capture]
//   default  → build:native        (release export, dev routes 404)
//   --capture→ build:native:capture (NEXT_PUBLIC_ENABLE_DEV_ROUTES=true)
//
// After a successful release export it also asserts there is NO `out/dev` and NO
// `out/api` directory (the §F.2 belt-and-braces gate).

import { spawnSync } from "node:child_process";
import {
  existsSync,
  mkdirSync,
  renameSync,
  rmSync,
  readdirSync,
  readFileSync,
  statSync,
} from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const webRoot = dirname(dirname(fileURLToPath(import.meta.url)));
const appDir = join(webRoot, "src", "app");
const stash = join(webRoot, ".native-stash");

const capture = process.argv.includes("--capture");

// Web-only trees that must not enter the export build (paths relative to
// src/app). These are the mechanisms §A says don't exist natively:
//   - api/                       → the 8 BFF route handlers (Request/cookies —
//                                   the DirectApiClient replaces them, §A.5).
//   - (app)/conditions/[id]      → per-user dynamic route; native uses the
//   - accept-share/[token]         query-param twins (`/conditions/detail`,
//                                   `/accept-share`) instead (§A.3a), so the
//                                   `[param]` routes (which can't export without
//                                   a meaningful generateStaticParams) are
//                                   excluded. Their twins ARE exported.
//   - .well-known/…              → the AASA route handler is served by the WEB
//                                   origin (Apple fetches it there); the native
//                                   app never serves it (§B.5).
const WEB_ONLY = ["api", "(app)/conditions/[id]", "accept-share/[token]", ".well-known"];

// The `/dev/*` fixture routes ship ONLY in the capture build (§F.2). In a release
// export they are excluded outright (defense in depth beyond the dev layout's
// production `notFound()` gate, and so the dynamic dev routes never need to be
// collected). The capture build keeps them — their `generateStaticParams` over
// fixture keys makes them export cleanly.
const RELEASE_ONLY_EXCLUDE = ["dev"];

function move(from, to) {
  if (!existsSync(from)) return false;
  mkdirSync(dirname(to), { recursive: true });
  renameSync(from, to);
  return true;
}

const moved = [];
function stashWebOnly() {
  const exclude = capture ? WEB_ONLY : [...WEB_ONLY, ...RELEASE_ONLY_EXCLUDE];
  for (const rel of exclude) {
    const src = join(appDir, rel);
    const dst = join(stash, rel);
    if (move(src, dst)) moved.push(rel);
  }
}

function restoreWebOnly() {
  for (const rel of moved) {
    const src = join(stash, rel);
    const dst = join(appDir, rel);
    move(src, dst);
  }
  if (existsSync(stash)) rmSync(stash, { recursive: true, force: true });
}

function assertReleaseClean() {
  const out = join(webRoot, "out");
  for (const forbidden of ["dev", "api"]) {
    const p = join(out, forbidden);
    if (existsSync(p)) {
      throw new Error(
        `Native export leaked '${forbidden}/' into out/ — release builds must exclude it (§F.2).`,
      );
    }
  }
  assertNoStripeStrings(out);
}

// §H.4 anti-steering RELEASE GATE. The native build must contain ZERO Stripe
// UI/flows: the PaywallView/WebBillingManage Stripe code is loaded via DCE-gated
// `require`s behind the inlined `NEXT_PUBLIC_NATIVE` literal, so webpack drops the
// Stripe module (and its network strings) from the export. This makes that
// guarantee a build failure instead of a manual grep — a refactor that turns a
// dead-branch require back into a static import (or otherwise drags Stripe in)
// can't silently regress the App-Review posture. Scans the emitted JS/HTML in
// out/ for the forbidden markers.
const STRIPE_MARKERS = [
  "/api/subscription", // the Stripe network endpoint (subscription-actions.ts)
  "requestSubscriptionUrl", // the Stripe action accessor symbol
  "billing portal", // Stripe portal copy (StripePaywall / WebBillingManage)
  "stripe", // catch-all: any stripe SDK/string/identifier
];

function assertNoStripeStrings(outDir) {
  if (!existsSync(outDir)) return;
  const hits = [];
  const walk = (dir) => {
    for (const name of readdirSync(dir)) {
      const p = join(dir, name);
      const st = statSync(p);
      if (st.isDirectory()) {
        walk(p);
        continue;
      }
      if (!/\.(js|mjs|html|json|txt)$/i.test(name)) continue;
      const text = readFileSync(p, "utf8").toLowerCase();
      for (const marker of STRIPE_MARKERS) {
        if (text.includes(marker.toLowerCase())) {
          hits.push(`${p.slice(outDir.length + 1)} :: "${marker}"`);
        }
      }
    }
  };
  walk(outDir);
  if (hits.length) {
    throw new Error(
      "Native export leaked Stripe anti-steering markers into out/ (§H.4). " +
        "The Stripe rail must be tree-shaken from the native bundle:\n  " +
        hits.join("\n  "),
    );
  }
  console.log("✓ §H.4 anti-steering: no Stripe markers in out/.");
}

let code = 1;
try {
  if (existsSync(stash)) rmSync(stash, { recursive: true, force: true });
  stashWebOnly();

  const env = { ...process.env, NEXT_PUBLIC_NATIVE: "1" };
  if (capture) env.NEXT_PUBLIC_ENABLE_DEV_ROUTES = "true";

  const result = spawnSync("npx", ["next", "build"], {
    cwd: webRoot,
    stdio: "inherit",
    env,
  });
  code = result.status ?? 1;

  if (code === 0 && !capture) assertReleaseClean();
  if (code === 0) {
    const outDir = join(webRoot, "out");
    const top = existsSync(outDir) ? readdirSync(outDir).length : 0;
    console.log(`\n✓ Native export complete → out/ (${top} top-level entries).`);
  }
} finally {
  restoreWebOnly();
  cleanNativeDevTypes();
}

// The export build regenerates `.next/dev/types/validator.ts` against the NATIVE
// route set (web-only routes are stashed out), which `tsconfig.json` includes via
// `.next/dev/types/**/*.ts`. Left in place it breaks a subsequent `tsc --noEmit`
// (the stashed `/dev` route resolves to a non-route type). It is pure build
// output (`.next` is gitignored) regenerated by any `next build`, so we drop the
// native-shaped validator on exit — keeping the SACRED `tsc && vitest && build`
// gate green regardless of which build ran last.
function cleanNativeDevTypes() {
  const validator = join(webRoot, ".next", "dev", "types", "validator.ts");
  try {
    if (existsSync(validator)) rmSync(validator, { force: true });
  } catch {
    /* best-effort; a web build regenerates a correct one anyway */
  }
}

process.exit(code);
