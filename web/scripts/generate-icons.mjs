#!/usr/bin/env node
// Regenerate every app icon, favicon, splash, and store graphic from the branding
// masters in ../branding/. Run from web/:  node scripts/generate-icons.mjs
//
// Sources (all vector, all committed):
//   branding/app-icon.svg         the composed icon — mark on the brand navy ground
//   branding/mark.svg             the transparent chevron mark (marketing, splash)
//   branding/mark-simple.svg      single chevron; used below ~64px (favicon.ico)
//   branding/mark-mono-light.svg  white mark for dark single-color surfaces
//   branding/mark-mono-dark.svg   navy mark for light single-color surfaces
//
// Why a script rather than @capacitor/assets: the delivered set fixes the exact
// glyph scale per surface (80% for Android's adaptive safe circle, full-bleed for
// iOS), and this keeps every derived PNG reproducible from the SVGs at those
// scales. Re-run after any change to branding/*.svg and commit the outputs.

import sharp from "sharp";
import { mkdirSync, readFileSync, writeFileSync } from "node:fs";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const WEB = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const ROOT = resolve(WEB, "..");
const BRAND = join(ROOT, "branding");

// Palette per branding/README.md — the icon set's own values, which the app's
// --navy token matches.
const NAVY = "#1B2A4A";
const GOLD = "#E8A33D";

const svg = (name) => readFileSync(join(BRAND, name));

/** Render an SVG to a square PNG buffer at `px`. */
const renderSquare = (name, px) => sharp(svg(name), { density: 300 }).resize(px, px).png().toBuffer();

/** A solid, mark-less layer (the adaptive-icon background). */
const solid = (background, width, height) =>
  sharp({ create: { width, height, channels: 4, background } }).png().toBuffer();

/** A solid ground with the mark composited at `scale` of the canvas (centered). */
async function markOn(background, width, height, markName, scale, opts = {}) {
  const side = Math.round(Math.min(width, height) * scale);
  const mark = await renderSquare(markName, side);
  return sharp({ create: { width, height, channels: 4, background } })
    .composite([{ input: mark, gravity: "centre" }])
    .png(opts)
    .toBuffer();
}

async function write(relPath, buffer) {
  const abs = join(ROOT, relPath);
  mkdirSync(dirname(abs), { recursive: true });
  writeFileSync(abs, buffer);
  // .ico and .svg are not sharp-readable; report their byte size instead.
  if (/\.(ico|svg)$/.test(relPath)) {
    console.log(`${relPath.padEnd(78)} ${buffer.length} bytes`);
    return;
  }
  const m = await sharp(buffer).metadata();
  console.log(`${relPath.padEnd(78)} ${m.width}x${m.height}`);
}

// Flatten alpha onto the navy ground: iOS and the stores reject transparency.
const opaque = (buf) => sharp(buf).flatten({ background: NAVY }).png().toBuffer();

async function main() {
  // ── Branding masters (raster reference) ────────────────────────────────────
  await write("branding/app-icon-1024.png", await opaque(await renderSquare("app-icon.svg", 1024)));

  // ── Web (Next.js metadata routes) ──────────────────────────────────────────
  await write("web/src/app/icon.png", await opaque(await renderSquare("app-icon.svg", 512)));
  await write("web/src/app/apple-icon.png", await opaque(await renderSquare("app-icon.svg", 180)));
  // favicon.ico: the simple (single-chevron) variant is legible at 16–48px.
  await write("web/src/app/favicon.ico", await ico([16, 32, 48], (px) => markOn(NAVY, px, px, "mark-simple.svg", 1.0)));
  // PWA / manifest-sized icons for any future manifest, plus a maskable safe-zone variant.
  await write("web/public/icon-192.png", await opaque(await renderSquare("app-icon.svg", 192)));
  await write("web/public/icon-512.png", await opaque(await renderSquare("app-icon.svg", 512)));
  await write("web/public/icon-maskable-512.png", await markOn(NAVY, 512, 512, "mark.svg", 0.8));
  await write("web/public/favicon.svg", svg("favicon.svg"));

  // ── iOS (single 1024 marketing icon; Xcode derives the rest) ───────────────
  await write("web/ios/App/App/Assets.xcassets/AppIcon.appiconset/AppIcon-512@2x.png",
    await opaque(await renderSquare("app-icon.svg", 1024)));

  // ── Capacitor source assets (kept for @capacitor/assets compatibility) ─────
  await write("web/assets/icon.png", await opaque(await renderSquare("app-icon.svg", 1024)));
  await write("web/assets/icon-background.png", await solid(NAVY, 1024, 1024));
  await write("web/assets/icon-foreground.png", await markOn("#00000000", 1024, 1024, "mark.svg", 0.8));

  // ── Android launcher: adaptive layers per density ──────────────────────────
  // The adaptive-icon XML insets each layer 16.7%, so the layer files are the
  // full 108dp canvas; the glyph sits at 80% of it for the 66dp safe circle.
  const densities = { ldpi: 36, mdpi: 48, hdpi: 72, xhdpi: 96, xxhdpi: 144, xxxhdpi: 192 };
  for (const [d, px] of Object.entries(densities)) {
    const dir = `web/android/app/src/main/res/mipmap-${d}`;
    await write(`${dir}/ic_launcher_background.png`, await solid(NAVY, px, px));
    await write(`${dir}/ic_launcher_foreground.png`, await markOn("#00000000", px, px, "mark.svg", 0.8));
    await write(`${dir}/ic_launcher_monochrome.png`, await markOn("#00000000", px, px, "mark-mono-light.svg", 0.8));
    // Legacy (pre-API-26) launcher: the composed icon, square and round.
    const legacy = await opaque(await renderSquare("app-icon.svg", px));
    await write(`${dir}/ic_launcher.png`, legacy);
    await write(`${dir}/ic_launcher_round.png`, await roundMask(legacy, px));
  }

  // ── Splash screens: mark centered on navy, both themes ─────────────────────
  // Capacitor's splash view is the navy ground; the image only carries the mark
  // at a modest scale so it reads as a launch mark, not a wall of chevrons.
  const splash = (w, h) => markOn(NAVY, w, h, "mark.svg", 0.28);
  await write("web/assets/splash.png", await splash(2732, 2732));
  await write("web/assets/splash-dark.png", await splash(2732, 2732));
  for (const f of ["splash-2732x2732.png", "splash-2732x2732-1.png", "splash-2732x2732-2.png"]) {
    await write(`web/ios/App/App/Assets.xcassets/Splash.imageset/${f}`, await splash(2732, 2732));
  }
  const port = { ldpi: [240, 320], mdpi: [320, 480], hdpi: [480, 800], xhdpi: [720, 1280], xxhdpi: [960, 1600], xxxhdpi: [1280, 1920] };
  const res = "web/android/app/src/main/res";
  await write(`${res}/drawable/splash.png`, await splash(320, 480));
  await write(`${res}/drawable-night/splash.png`, await splash(320, 240));
  for (const [d, [w, h]] of Object.entries(port)) {
    for (const theme of ["", "night-"]) {
      await write(`${res}/drawable-port-${theme}${d}/splash.png`, await splash(w, h));
      await write(`${res}/drawable-land-${theme}${d}/splash.png`, await splash(h, w));
    }
  }

  // ── Store listing assets ───────────────────────────────────────────────────
  await write("docs/playstore/assets/play-icon-512.png", await opaque(await renderSquare("app-icon.svg", 512)));
  await write("docs/playstore/assets/feature-graphic.png", await featureGraphic());
  console.log("\nDone. Palette:", { NAVY, GOLD });
}

/** Circle-mask a square PNG (legacy round launcher). */
async function roundMask(buf, px) {
  const circle = Buffer.from(
    `<svg width="${px}" height="${px}"><circle cx="${px / 2}" cy="${px / 2}" r="${px / 2}" fill="#fff"/></svg>`,
  );
  return sharp(buf).composite([{ input: circle, blend: "dest-in" }]).png().toBuffer();
}

/** Play Store feature graphic, 1024×500: mark left, wordmark right, on navy. */
async function featureGraphic() {
  const mark = await renderSquare("mark.svg", 360);
  const text = Buffer.from(`<svg width="1024" height="500" xmlns="http://www.w3.org/2000/svg">
    <text x="420" y="232" font-family="Inter Tight, Inter, Helvetica, Arial, sans-serif" font-size="88" font-weight="800" fill="#FFFFFF" letter-spacing="-2">After Duty</text>
    <text x="422" y="292" font-family="Inter Tight, Inter, Helvetica, Arial, sans-serif" font-size="30" fill="#D9E0EC">Organize. Understand. Move forward.</text>
    <rect x="422" y="322" width="150" height="6" rx="3" fill="${GOLD}"/>
  </svg>`);
  return sharp({ create: { width: 1024, height: 500, channels: 4, background: NAVY } })
    .composite([{ input: mark, left: 60, top: 70 }, { input: text, left: 0, top: 0 }])
    .png().toBuffer();
}

/** Build a multi-size .ico from PNG-encoded images (ICO permits PNG entries). */
async function ico(sizes, render) {
  const pngs = [];
  for (const px of sizes) pngs.push({ px, data: await render(px) });
  const headerSize = 6 + 16 * pngs.length;
  let offset = headerSize;
  const header = Buffer.alloc(headerSize);
  header.writeUInt16LE(0, 0); header.writeUInt16LE(1, 2); header.writeUInt16LE(pngs.length, 4);
  pngs.forEach(({ px, data }, i) => {
    const e = 6 + i * 16;
    header.writeUInt8(px >= 256 ? 0 : px, e); header.writeUInt8(px >= 256 ? 0 : px, e + 1);
    header.writeUInt8(0, e + 2); header.writeUInt8(0, e + 3);
    header.writeUInt16LE(1, e + 4); header.writeUInt16LE(32, e + 6);
    header.writeUInt32LE(data.length, e + 8); header.writeUInt32LE(offset, e + 12);
    offset += data.length;
  });
  return Buffer.concat([header, ...pngs.map((p) => p.data)]);
}

main().catch((e) => { console.error(e); process.exit(1); });
