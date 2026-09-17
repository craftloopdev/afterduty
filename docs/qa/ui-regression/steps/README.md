# UI Regression — Next Steps + Scenarios (web/) · cycle 3

Prioritized gaps + scenario modeling, built on the cycle-1 foundation.

- **First recorded:** 2026-06-07
- **Replay:** `cd web && npm test && npm run test:e2e`.

## Correct-actor matrix

| Case | Asserted outcome | Status |
|------|------------------|--------|
| Unauthenticated visitor | `/steps` → redirect to `/login` | ✅ e2e |
| Steps view | "estimate" reframe banner + high-priority count; prioritized steps with "Highest value" ribbon on the top step, priority dots, impact tags; clicking a step opens its detail modal | ✅ e2e + screenshot |
| Scenarios view | toggling the segmented control shows scenario cards with **server-computed** combined rating + monthly pay (via `/api/scenarios/calculate`) and the delta of closing high-priority gaps | ✅ e2e |
| Empty | no steps and no ready conditions → "No steps yet" + Add CTA | ✅ route |
| Console hygiene | zero console errors | ✅ e2e |

## Screenshot
| View | File |
|------|------|
| Steps (banner + prioritized list) | `steps-list.png` |

## Notes
- Scenario pay/rating are server-authoritative (`/api/scenarios/calculate` over the ready set, and ready + high-priority-gap conditions), not recomputed client-side.
- Step "Add evidence"/"View condition" actions are no-ops pending the evidence + condition-detail cross-links in later cycles.
