#!/usr/bin/env node
// Turn the raw simulator/emulator captures into store-ready screenshot sets.
// Run from web/ after capture-screenshots.sh and capture-screenshots-android.sh:
//   node scripts/store-assets.mjs
//
// Reads   web/screenshots/out/<set>/<slot>/*.png   (raw captures, gitignored)
// Writes  web/screenshots/store/apple/<slot-set>/NN-<slot>.png
//         web/screenshots/store/google/<slot-set>/NN-<slot>.png
//         web/screenshots/store/MANIFEST.md
//
// Derived sets:
//   apple/iphone-6.5   1242×2688  from iphone69 (1320×2868) — ASC normally falls
//                      back to the 6.9" set, but an explicit set removes that
//                      dependency. Aspect ratios differ by 0.4%, so this is a
//                      cover-resize (crops ≤6 px at the edges), never a stretch.
//   google/tablet-7    1920×1200  from tablet-10 (2560×1600), same 16:10 aspect.
//
// Every output is flattened to opaque RGB (both stores reject alpha) and checked
// against the stores' 8 MB ceiling.

import sharp from "sharp";
import { existsSync, mkdirSync, readdirSync, statSync, writeFileSync } from "node:fs";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const WEB = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const RAW = join(WEB, "screenshots", "out");
const STORE = join(WEB, "screenshots", "store");
const MAX_BYTES = 8 * 1024 * 1024;

// raw set → { store, slotSet, size, derive? }
const TARGETS = [
  { raw: "iphone69", store: "apple", slotSet: "iphone-6.9", size: [1320, 2868] },
  { raw: "iphone69", store: "apple", slotSet: "iphone-6.5", size: [1242, 2688], derived: true },
  { raw: "ipad-13", store: "apple", slotSet: "ipad-13", size: [2064, 2752] },
  { raw: "phone", store: "google", slotSet: "phone", size: [1080, 1920] },
  { raw: "tablet-10", store: "google", slotSet: "tablet-10", size: [2560, 1600] },
  { raw: "tablet-10", store: "google", slotSet: "tablet-7", size: [1920, 1200], derived: true },
];

// The listing convention is five per slot; these are the recommended five, in
// order. The others are captured for choice at upload time.
const RECOMMENDED = ["00-home", "01-conditions", "02-condition-detail", "03-steps", "05-ask"];

function rawShots(rawSet) {
  const dir = join(RAW, rawSet);
  if (!existsSync(dir)) return [];
  return readdirSync(dir)
    .filter((slot) => statSync(join(dir, slot)).isDirectory())
    .sort()
    .map((slot) => {
      const files = readdirSync(join(dir, slot)).filter((f) => f.endsWith(".png"));
      return files.length ? { slot, file: join(dir, slot, files[0]) } : null;
    })
    .filter(Boolean);
}

async function main() {
  const rows = [];
  for (const t of TARGETS) {
    const shots = rawShots(t.raw);
    if (!shots.length) {
      console.warn(`skip ${t.store}/${t.slotSet}: no raw captures under ${t.raw}/`);
      continue;
    }
    const outDir = join(STORE, t.store, t.slotSet);
    mkdirSync(outDir, { recursive: true });
    for (const { slot, file } of shots) {
      const [w, h] = t.size;
      const out = join(outDir, `${slot}.png`);
      let img = sharp(file);
      if (t.derived) img = img.resize(w, h, { fit: "cover", position: "centre" });
      const buf = await img.flatten({ background: "#1B2A4A" }).png({ compressionLevel: 9 }).toBuffer();
      const m = await sharp(buf).metadata();
      if (m.width !== w || m.height !== h) throw new Error(`${out}: got ${m.width}×${m.height}, want ${w}×${h}`);
      if (m.channels !== 3) throw new Error(`${out}: expected opaque RGB, got ${m.channels} channels`);
      if (buf.length > MAX_BYTES) throw new Error(`${out}: ${buf.length} bytes exceeds the 8 MB store limit`);
      writeFileSync(out, buf);
      rows.push({ store: t.store, slotSet: t.slotSet, slot, w, h, bytes: buf.length, derived: !!t.derived });
      console.log(`${t.store}/${t.slotSet}/${slot}.png`.padEnd(46), `${w}×${h}`, `${(buf.length / 1024).toFixed(0)} KB`);
    }
  }
  writeFileSync(join(STORE, "MANIFEST.md"), manifest(rows));
  console.log(`\n${rows.length} store screenshots → ${STORE}`);
}

function manifest(rows) {
  const date = new Date().toISOString().slice(0, 10);
  const byStore = (store) => rows.filter((r) => r.store === store);
  const table = (rs) =>
    ["| Set | File | Size | Bytes | Recommended |", "|---|---|---|---|---|"]
      .concat(
        rs.map(
          (r) =>
            `| ${r.slotSet}${r.derived ? " (derived)" : ""} | ${r.slot}.png | ${r.w}×${r.h} | ${(r.bytes / 1024).toFixed(0)} KB | ${RECOMMENDED.includes(r.slot) ? "✓" : ""} |`,
        ),
      )
      .join("\n");
  return `# Store screenshots — generated ${date}

Captured from the \`/dev/*\` fixture screens (synthetic veteran **D. Griff**, U.S. Army SGT 11B,
2004–2010) on the iOS simulator and Android emulator by \`capture-screenshots.sh\` /
\`capture-screenshots-android.sh\`, then converted by \`store-assets.mjs\`. All files are opaque
RGB PNG under 8 MB. Upload is manual in both consoles.

Recommended five per slot, in order: ${RECOMMENDED.join(", ")}. The rest are extras.

## App Store Connect (Apple)

- **iPhone 6.9"** — required. \`iphone-6.9/\` (1320×2868).
- **iPhone 6.5"** — ASC falls back to 6.9" automatically; \`iphone-6.5/\` (1242×2688) is provided so the listing does not depend on that fallback.
- **iPad 13"** — \`ipad-13/\` (2064×2752).

${table(byStore("apple"))}

## Google Play Console

- **Phone** — required, 2–8 shots. \`phone/\` (1080×1920, 9:16). The emulator display is forced to 9:16 at capture time because Play rejects screenshots whose long side exceeds twice the short side (a 1080×2340 device capture fails that rule).
- **10-inch tablet** — \`tablet-10/\` (2560×1600, 16:10 landscape — the Pixel Tablet's natural orientation; at its 800 CSS px portrait width the desktop layout engages and the hero card overflows, a known layout defect).
- **7-inch tablet** — \`tablet-7/\` (1920×1200), a same-aspect downscale of the 10-inch set.

${table(byStore("google"))}
`;
}

main().catch((e) => { console.error(e); process.exit(1); });
