# UI Regression — Profile / Settings (web/) · cycle 6

Profile + appearance settings (ships the theme UI) + account deletion.

- **First recorded:** 2026-06-07
- **Replay:** `cd web && npm test && npm run test:e2e`.

## Correct-actor matrix

| Case | Asserted outcome | Status |
|------|------------------|--------|
| Unauthenticated visitor | `/profile` → redirect to `/login`; `DELETE /api/account` with no session → **401** | ✅ e2e |
| Profile | header (name/branch/role), service summary (branch/service/MOS), Pro upsell when free | ✅ e2e + screenshot |
| Appearance | dark toggle + Navy/Warm/AI theme + A−/A/A+ text-size, wired to ThemeProvider (persists to cookie, applied to `<html>`) | ✅ screenshot |
| Account | Share link → `/share`; Sign out (clears Firebase + cookie → `/login`) | ✅ e2e |
| Delete account | confirm modal → `DELETE /api/account` (cascade at Spring) → sign out → `/login`; user-triggered only | ✅ route + UI |
| Console hygiene | zero console errors | ✅ e2e |

## Screenshot
| View | File |
|------|------|
| Profile + appearance + account | `profile.png` |

## Notes
- This cycle ships the **theme/scale/dark UI** whose plumbing was built in cycle 1 (ThemeProvider + SSR no-FOUC).
- Account deletion is destructive and **user-initiated** from the confirm modal; the BFF `DELETE /api/account` route forwards to Spring `DELETE /auth/account` and clears the session cookie.
