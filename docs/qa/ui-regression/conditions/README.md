# UI Regression — Conditions (web/) · cycle 2

Conditions list + detail, built on the cycle-1 foundation. Replayable via the test suite.

- **First recorded:** 2026-06-07
- **Replay:** `cd web && npm test && npm run test:e2e` (e2e auto-starts the dev server).

## Correct-actor matrix

| Actor / case | Asserted outcome | Status |
|--------------|------------------|--------|
| Unauthenticated visitor | `/conditions` and `/conditions/93` → redirect to `/login` | ✅ e2e |
| List filters | default "Ready" hides needs-work conditions; "All" reveals Sleep Apnea; "Needs work" drops ready ones (counts on chips) | ✅ e2e |
| Detail | `/conditions/[id]` shows the condition name, VASRD/system tags, estimated rating + confidence, the 3 triad legs (Strong/Partial/Missing) with evidence bullets, strengthen-CTA on the weakest leg, and the related next-step | ✅ e2e + screenshot |
| Detail (not found) | unknown id → `notFound()` (404) | ✅ route |
| Console hygiene | zero console errors on the detail page | ✅ e2e |

## Screenshots
| View | File |
|------|------|
| Conditions list (filters + triad table) | `conditions-desktop.png` |
| Condition detail (triad legs + strengthen + related step) | `condition-detail.png` |

## Notes
- Fixed during this cycle: the detail page wasn't rendering the condition name (you couldn't tell which condition you were viewing) — added a name heading, regression-locked by the detail e2e.
- Condition rows link to `/conditions/[id]`; detail's "Add evidence"/"Ask AI" are wired to no-ops pending later cycles (evidence upload, AI chat).
