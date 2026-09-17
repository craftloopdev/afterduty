# UI Regression — Share / VSO (web/) · cycle 5

Invite + manage accredited-rep access to the claim (owner side).

- **First recorded:** 2026-06-07
- **Replay:** `cd web && npm test && npm run test:e2e`.

## Correct-actor matrix

| Case | Asserted outcome | Status |
|------|------------------|--------|
| Unauthenticated visitor | `/share` → redirect to `/login`; `POST /api/share` with no session → **401** (no invite reaches Spring) | ✅ e2e |
| Invite form | email + "Share analysis access" / "Allow document uploads" toggles + "Send secure invite" → `POST /api/share`; 403 → "needs Pro" message; success refreshes the list | ✅ e2e + screenshot |
| Shared-with list | existing shares show email, access summary, status pill (Active/Invited/Revoked/Expired) + revoke (`DELETE /api/share?id=`) | ✅ e2e + unit |
| Console hygiene | zero console errors | ✅ e2e |

## Screenshot
| View | File |
|------|------|
| Share manager (invite + shared-with) | `share.png` |

## Notes
- Owner-side only this cycle. The **viewer accept journey** (`/api/shares/accept/{token}` → then viewing the shared claim via the `X-View-As` header the BFF already forwards) is deferred to its own cycle.
- Share status derived from the DTO dates (revoked > accepted > expired > pending), unit-tested.
- The Home ShareCard now links to `/share`.
