# UI Regression — Ask AI (web/) · cycle 8

Conversational Q&A about the claim.

- **First recorded:** 2026-06-07
- **Replay:** `cd web && npm test && npm run test:e2e`.

## Correct-actor matrix

| Case | Asserted outcome | Status |
|------|------------------|--------|
| Unauthenticated visitor | `/ask` → redirect to `/login`; `GET` and `POST /api/chat` with no session → **401** | ✅ e2e |
| Thread + composer | history bubbles (user right / assistant left) + suggestions when empty + composer | ✅ e2e + screenshot |
| Send (authed) | optimistic user bubble → `POST /api/chat {message}` → BFF forwards to `/claim/chat`, refetches `/claim/messages`, returns the thread; 402 → "needs Pro" | ⚠️ wired + route-auth tested; full authed turn needs a real token |
| Console hygiene | zero console errors | ✅ e2e |

## Screenshot
| View | File |
|------|------|
| Ask AI chat | `chat.png` |

## Notes
- BFF `/api/chat`: GET returns the thread; POST sends then returns the refreshed thread (one round-trip for the client). 402 (usage cap) surfaces a Pro prompt.
- The condition-detail "Ask AI about this" button links to `/ask`.
- Educational disclaimer shown in the empty state ("not legal advice").
