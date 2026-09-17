#!/usr/bin/env node
// Post-`cap sync` belt-and-braces gate (capacitor-ios-spec §F.2). `build:native`
// already asserts no `out/dev` / `out/api` before the sync, and `cap sync` only
// copies that already-clean `out/` into `ios/App/App/public`. This re-checks the
// SYNCED bundle directly so a release archive can never ship the fixture routes
// even if the export gate were bypassed. Fails the build loudly if `dev/` or
// `api/` slipped into the native public dir.

import { existsSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const webRoot = dirname(dirname(fileURLToPath(import.meta.url)));
const publicDir = join(webRoot, "ios", "App", "App", "public");

if (!existsSync(publicDir)) {
  // Nothing synced yet — not an error in isolation (e.g. iOS platform absent).
  console.log("· No ios/App/App/public to check (skipping synced-clean assert).");
  process.exit(0);
}

const forbidden = ["dev", "api"];
const leaked = forbidden.filter((d) => existsSync(join(publicDir, d)));
if (leaked.length) {
  console.error(
    `✗ Native sync leaked ${leaked.map((d) => `'${d}/'`).join(", ")} into ` +
      `ios/App/App/public — release builds must exclude fixture/BFF routes (§F.2).`,
  );
  process.exit(1);
}
console.log("✓ Synced native bundle is clean (no dev/ or api/ in ios public).");
