# UI Regression — Paywall / Subscription (web/) · cycle 7

Plans + Stripe checkout/portal redirect.

- **First recorded:** 2026-06-07
- **Replay:** `cd web && npm test && npm run test:e2e`.

## Correct-actor matrix

| Case | Asserted outcome | Status |
|------|------------------|--------|
| Unauthenticated visitor | `/upgrade` → redirect to `/login`; `POST /api/subscription` with no session → **401** | ✅ e2e |
| Not subscribed | hero + plan cards (Monthly $11.99 / Annual $119.99 "Best value") + Pro feature list; Subscribe → `POST /api/subscription {action:checkout}` → redirect to the returned Stripe URL | ✅ e2e + screenshot |
| Subscribed | "You're on Pro" + Manage subscription → `POST {action:portal}` → Stripe billing portal | ✅ component (active branch) |
| Console hygiene | zero console errors | ✅ e2e |

## Screenshot
| View | File |
|------|------|
| Paywall (plans + features) | `paywall.png` |

## Notes
- BFF `/api/subscription` returns the Stripe `{ url }` for checkout/portal; the client redirects (`window.location.assign`). Stripe + webhooks remain owned by the Spring backend.
- The sidebar "Upgrade to Pro" card links to `/upgrade`. Defaults to $11.99/mo + $119.99/yr if the backend returns no plan list.
