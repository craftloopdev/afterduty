# UI Regression — Documents + Upload (web/) · cycle 4

Source-documents grid + evidence upload — the first feature with a BFF **write** path.

- **First recorded:** 2026-06-07
- **Replay:** `cd web && npm test && npm run test:e2e`.

## Correct-actor matrix

| Case | Asserted outcome | Status |
|------|------------------|--------|
| Unauthenticated visitor | `/documents` → redirect to `/login`; `POST /api/upload` with no session → **401** (no upload reaches Spring) | ✅ e2e |
| Documents grid | doc cards with kind-resolved icon/color (Service/Medical/Statement), processed status, name truncation | ✅ e2e + screenshot |
| Upload (authed) | file picked → `POST /api/upload` (multipart) → BFF forwards to `/api/claim/evidence` with Bearer; 402/409/413 surface friendly errors; success refreshes the grid | ⚠️ wired + route-auth tested; full authed upload needs a real token (manual/CI) |
| Empty | no evidence → "No documents yet" + the upload card | ✅ route |
| Console hygiene | zero console errors on the grid | ✅ e2e |

## Screenshot
| View | File |
|------|------|
| Documents grid + upload card | `documents.png` |

## Notes
- The BFF **mutation lane**: `web/src/app/api/upload/route.ts` reads the httpOnly cookie, forwards the multipart body to Spring with the Bearer token (no Content-Type override so the boundary is preserved), and passes status codes through.
- Describe-what-you-remember (AI chat) and quick-add-a-condition remain stubbed in the Add modal (chat = cycle 8; quick-add = later) — only document upload is wired this cycle.
