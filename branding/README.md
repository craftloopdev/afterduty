# After Duty brand mark — "Chevron A"

Palette: navy `#1B2A4A`, gold `#E8A33D`. The two lower chevrons are the gold at 50% and 25%.

| File | Use |
|---|---|
| `app-icon.svg` | The composed app icon: mark on the navy ground. Master for every launcher/store icon. |
| `mark.svg` | Transparent mark — marketing, splash screens, in-app brand mark. |
| `mark-simple.svg` | Single chevron. Use below ~64 px (favicon.ico). |
| `mark-mono-light.svg` / `mark-mono-dark.svg` | White / navy single-color variants (Android themed icon, print). |
| `favicon.svg` | Simple mark on navy, for `<link rel="icon" type="image/svg+xml">`. |
| `app-icon-1024.png`, `preview-sheet.png` | Raster reference renders. |

Rules: simple variant under 64 px; mono variants on single-color surfaces; never recolor red/white/blue.

Regenerate every derived PNG/ICO (web metadata icons, iOS AppIcon, Android
adaptive + legacy launchers at all densities, every splash, Play Store icon and
feature graphic) with:

    cd web && node scripts/generate-icons.mjs

The in-app `<Icon name="shield">` glyph in `web/src/components/ui/Icon.tsx` is a
stroke rendering of `mark.svg` on a 24-unit grid; keep it in step with the mark.
