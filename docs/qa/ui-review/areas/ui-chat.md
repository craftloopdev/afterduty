# UI Review — Ask AI Chat (`/ask`)

Scope: `web/src/app/(app)/ask/page.tsx`, `web/src/components/chat/ChatView.tsx` (+ `.module.css`),
`web/src/app/api/chat/route.ts`, `web/src/lib/adapters/message.ts`, plus route chrome
(`AppShell`/`TopBar` title for `/ask`), the entry point in `ConditionDetail`, the dev fixture
(`web/src/app/dev/ask/page.tsx`, `web/src/lib/fixtures/messages.ts`), and the route-group
`loading.tsx`/`error.tsx` that wrap this page.

Architecture note: `page.tsx` is a 7-line server component that loads history via
`loadMessages()` (`web/src/lib/api/endpoints.ts:178`) and hands it to the client `ChatView`.
Sending POSTs to the BFF `/api/chat`, which proxies to Spring `/claim/chat` then returns the
**entire refreshed thread** (`route.ts:44-45`) — no streaming. E2E coverage exists at
`web/tests/e2e/chat.spec.ts`.

---

## Element Catalog

| # | Element | file:line | User story | States covered | Gaps |
|---|---------|-----------|------------|----------------|------|
| 1 | Route title "Ask AI" + sub "AI-assisted answers about your claim — not legal advice." (TopBar) | `web/src/components/shell/AppShell.tsx:41-42`, rendered by `shell/TopBar.tsx:20-21` | As a veteran, I want to know I'm in the AI Q&A area and that answers are educational, so I orient myself and don't mistake output for legal advice. | All thread states; desktop + mobile; dark via tokens | Sub line is the only persistent disclaimer once messages exist (intro disclaimer disappears) |
| 2 | DisclaimerPill in TopBar (opens DisclaimerModal) | `web/src/components/shell/TopBar.tsx:24`, wired `AppShell.tsx:74,80` | As a veteran, I want the full educational-tool disclaimer one tap away so I understand the app's limits before acting on AI answers. | All states (global chrome) | Shared chrome, not chat-specific; fine |
| 3 | Empty-state sparkle icon tile | `ChatView.tsx:61-63`; css `.introIc` `ChatView.module.css:27-35` (`--ai-bg`/`--ai-fg`) | As a brand-new Pro user opening an empty thread, I want a friendly AI-branded visual anchor so the blank screen reads as "start here" rather than broken. | Empty only; light + dark (`--ai-bg` overridden in `.cp-dark`, `styles.css:171-172`) | Decorative; correctly `aria-hidden` via `Icon` default (`Icon.tsx:220`) |
| 4 | Empty-state heading "Ask about your claim" | `ChatView.tsx:64` | As a brand-new user, I want a plain statement of what this screen does so I know what to type. | Empty only | h2 under TopBar's title — heading order OK |
| 5 | Empty-state disclaimer "AI-assisted & educational — not legal advice." | `ChatView.tsx:65` | As a veteran, I want the legal-advice boundary stated at the moment I'm about to ask, so I calibrate trust. | Empty only | Vanishes after first message (TopBar sub remains, #1); `--faint` at 0.85rem is borderline AA |
| 6 | Suggestion chips ×3 ("What's the strongest part of my claim?" etc.) | `ChatView.tsx:9-13,66-72`; css `.chip` `ChatView.module.css:52-63` | As a veteran new to AI chat, I want one-tap starter questions tied to my claim so I get value without having to compose a prompt. | Empty only; hover; dark via tokens | Never reappear after first message; **for a free user they fire a request guaranteed to 402** — no Pro hint on the chip; ~36px tall (<44px tap target); hover-only affordance, no chip-specific focus style (global `:focus-visible` in `base.css:55` does cover it) |
| 7 | User message bubble (right-aligned, navy) | `ChatView.tsx:75-81`; css `.row[data-role="user"]`, `.bubble[data-role="user"]` `ChatView.module.css:68-83` | As a veteran, I want my own questions visually distinct and in order so I can follow the conversation thread. | Light + dark (`--navy`/`--on-navy`); pre-wrap for line breaks; mobile (max-width 78%) | No timestamp; sender conveyed **only** by alignment/color — nothing for screen readers; no copy action; optimistic bubble persists even when send fails (see Orphans) |
| 8 | Assistant message bubble (left-aligned, card) | `ChatView.tsx:75-81`; css `.bubble[data-role="assistant"]` `ChatView.module.css:84-89` | As a Pro user, I want the AI's answer visually distinct from my question so I can scan Q→A pairs. | Light + dark; pre-wrap | Plain text only — markdown from the model would render raw (`m.content` string, `message.ts:6`); no copy button; no "AI" label for AT |
| 9 | "Thinking…" typing indicator | `ChatView.tsx:82-88`; css `.typing` `ChatView.module.css:90-92` | As a Pro user, I want to see the AI is working so I don't resend or assume a hang. | Busy state only | Not announced to screen readers (no `aria-live`); whole thread refetch means long silent waits with no progress |
| 10 | Error banner (`role="alert"`) — two variants: 402 "AI chat needs a Pro subscription." / generic "Couldn't get a response. Please try again." | `ChatView.tsx:39-48,92-96`; css `.error` `ChatView.module.css:94-100` | As a free user, I want to learn chat is a Pro feature when I try to use it; as a Pro user, I want to know a send failed so I can retry. | POST 402, POST non-OK, network throw; light + dark (`--missing`/`--missing-bg`) | **402 variant has no link/CTA to `/upgrade`** — dead end; generic variant says "try again" but the input was already cleared (`ChatView.tsx:29`) so retry means retyping; banner persists until next send, no dismiss |
| 11 | Composer text input | `ChatView.tsx:105-111`; css `.input` `ChatView.module.css:107-119` | As a veteran, I want to type a free-form question about my conditions, evidence, or ratings so I get tailored guidance. | Disabled-while-busy; focus ring via `border-color: var(--focus)`; light + dark | **No accessible name** (placeholder only); single-line `<input>` — no multiline composing; disabling during busy drops keyboard focus; 0.95rem (<16px) triggers iOS zoom-on-focus; no max-length feedback |
| 12 | Send button (primary, send icon, spinner when loading) | `ChatView.tsx:112-114`; `ui/Button.tsx:36-43`; css `Button.module.css` | As a veteran, I want an explicit submit control with visible progress so I know my question went out. | Loading (spinner + `aria-busy`), disabled-when-empty, 44px min-height tap target, dark via tokens | Disabled state is unfocusable — SR users get no hint *why* it's disabled |
| 13 | Auto-scroll-to-latest behavior (anchor div) | `ChatView.tsx:20-24,89` | As a veteran in a long thread, I want the newest message in view automatically so I don't scroll after every reply. | Fires on messages/busy change | `behavior:"smooth"` ignores `prefers-reduced-motion`; no manual "jump to latest" for users who scrolled up |
| 14 | Entry point: "Ask AI about this" ghost button on condition detail | `web/src/components/conditions/ConditionDetail.tsx:180-187` | As a veteran reviewing a condition, I want to ask the AI about *this condition* so I don't restate the context. | Renders for all users | **Drops the context it promises** — navigates to bare `/ask` with no condition param or prefill; uses `window.location.assign` (full page reload) instead of Next `Link`/router |
| 15 | Route loading skeleton (shared) | `web/src/app/(app)/loading.tsx:4-23` | As a veteran on a slow connection, I want a loading placeholder so the page doesn't appear frozen while history loads. | SSR suspense for `/ask`; `aria-busy`/`aria-label` present | Skeleton is shaped like the **Home** page (hero + 2-col grid), not a chat thread — brief layout lie on `/ask` |
| 16 | Route error boundary (shared ErrorState + retry) | `web/src/app/(app)/error.tsx:12-17` | As a veteran, I want a recoverable error screen if history fails to load so I can retry without losing my place. | SSR failures of `loadMessages` | If the backend ever 402s the **GET** `/claim/messages` for free users, they'd land here with a generic "couldn't load" instead of a paywall (`client.ts:49` throws `SubscriptionRequiredError`, nothing catches it on this page) |

Dev-only: `web/src/app/dev/ask/page.tsx` renders `ChatView` with `messagesFixture`
(`web/src/lib/fixtures/messages.ts:3-11`) — 1 user + 1 assistant message, happy path only. No
fixture variant for empty, error, long-thread, or free-tier states.

---

## Orphans

1. **GET `/api/chat` handler** — `web/src/app/api/chat/route.ts:25-31`. No UI consumer anywhere:
   the page loads history server-side via `loadMessages()` and `ChatView` only POSTs. It's a stub
   for a refresh/poll affordance that was never built. Either wire a client-side refresh to it or
   delete it.
2. **"Ask AI about this" context promise** — `ConditionDetail.tsx:184`. The label promises
   condition-scoped help ("about **this**"); the implementation is a bare redirect to `/ask`. As
   shipped, the honest label would be "Open Ask AI". Half-orphan: keep the button, make it pass
   context (e.g. `/ask?topic=<conditionId>` prefilling the composer).
3. **Optimistic user bubble after failed send** — `ChatView.tsx:32` + `39-48`. On a 402 or network
   failure, the user's message bubble stays in the thread even though the server never accepted
   it; a reload silently drops it. The element visually asserts a message exists that doesn't.
   (Related nit: temp key `tmp-${m.length}-${msg.length}` can collide across retries.)
4. **Suggestion chips for free users** — `ChatView.tsx:66-72`. For the free persona the chips are
   bait into a guaranteed 402; there is no honest free-tier story for them as rendered. They need
   either a lock/Pro affordance or a pre-send gate (see Missing Affordances #2).

Everything else in the catalog has a defensible story.

---

## Missing Affordances

1. **Find Ask AI from main navigation.** "As a Pro user, I want to reach AI chat from the nav so I
   don't have to remember a URL." `/ask` appears in `AppShell` route-title mapping
   (`AppShell.tsx:21,41`) but in **neither** `Sidebar.tsx:7-13` (Home/Conditions/Next
   Steps/Documents) nor `MobileNav.tsx:7-13`. The only in-app path is the single button on
   condition detail (`ConditionDetail.tsx:184`). A flagship paid feature is effectively hidden.
2. **See the Pro requirement before typing.** "As a free user, I want to know chat is Pro-gated
   before I compose a question so I'm not tricked into a dead end." `ChatView` receives no
   `isPro` (the layout already has it, `(app)/layout.tsx:28`) and renders the full composer +
   chips to everyone; gating is purely the reactive 402 banner. A paywall banner/locked composer
   with an Upgrade CTA is the standard pattern — `/upgrade` already exists.
3. **Tappable upgrade path from the 402 error.** The "needs a Pro subscription" banner
   (`ChatView.tsx:40`) is plain text — no link to `/upgrade`.
4. **Retry a failed send without retyping.** Input is cleared optimistically (`ChatView.tsx:29`);
   on failure the text is gone from the composer. Restore input on error, or add a per-bubble
   "retry" affordance.
5. **Ask about a specific condition with context carried over** (pairs with Orphan #2).
6. **Clear/delete chat history.** Medical-adjacent Q&A is privacy-sensitive; there is no way to
   clear the thread (no UI, and no DELETE on `route.ts`).
7. **Copy an AI answer.** "As a veteran, I want to copy an answer so I can paste it into a note
   or send it to my VSO." No copy button on bubbles.
8. **Multiline composing.** `<input>` not `<textarea>` (`ChatView.tsx:105`) — Enter always
   submits; no Shift+Enter; long questions about claim history are the norm for this persona.
9. **Rendered formatting in answers.** Assistant content is plain text (`ChatView.tsx:78`); lists
   and emphasis from the model render as raw markdown characters.
10. **Progress for long generations.** Full-thread refetch after POST (`route.ts:44-45`) means a
    Pro user stares at a static "Thinking…" with no streaming, no token-by-token output, no
    timeout messaging.
11. **Timestamps / session boundaries.** `MessageVM` (`vm.ts:7-11`) has no timestamp; a veteran
    returning after a week cannot tell old advice from new.
12. **VSO viewer story is undefined.** Memory says viewer-aware is parked, but nothing in this
    area hides or scopes chat for a `role=viewer` account — whose claim does the AI discuss, and
    should a viewer be able to spend the veteran's AI quota? Needs an explicit decision.
13. **Re-surface suggestions after the thread starts.** Chips exist only in the empty state
    (`ChatView.tsx:59`); a stuck user mid-thread gets no prompt ideas.

---

## A11y & Consistency

**A11y**
- Composer input has no accessible name — placeholder only (`ChatView.tsx:105-111`). Add
  `aria-label="Ask a question about your claim"` or a visually hidden `<label>`.
- Thread has no live region: new assistant messages and the "Thinking…" indicator are silent for
  screen-reader users (`ChatView.tsx:58-90`). Wrap the thread in `role="log"` /
  `aria-live="polite"`.
- Message sender is conveyed only by alignment + color (`ChatView.module.css:68-89`); add visually
  hidden "You:" / "AI:" prefixes so the transcript is intelligible to AT.
- Suggestion chips are ~36px tall (`ChatView.module.css:53`, 9px padding + 0.84rem text) — below
  the 44px tap-target convention the project's own `Button` enforces (`Button.module.css:13`).
- Disabling the input during busy (`ChatView.tsx:109`) throws keyboard focus to `<body>`; after
  the reply, focus does not return to the composer.
- `scrollIntoView({behavior:"smooth"})` (`ChatView.tsx:23`) ignores `prefers-reduced-motion`.
- Input font-size 0.95rem ≈ 15.2px (`ChatView.module.css:109`) triggers iOS Safari zoom-on-focus.
- Positives: error banner has `role="alert"` (`ChatView.tsx:93`); `Icon` defaults to
  `aria-hidden` when unlabeled (`Icon.tsx:218-220`); `Button` sets `aria-busy` and hides the
  spinner from AT (`Button.tsx:36-38`); global `:focus-visible` ring exists (`base.css:55-59`);
  Send button has visible text, not icon-only.

**Design tokens / consistency**
- `height: calc(100dvh - 180px)` (`ChatView.module.css:5`) — magic 180px coupling to TopBar +
  padding heights; `min-height: 420px` fallback can produce double scrollbars on short mobile
  viewports. No token or shared var for chrome height.
- `.introIc` 56×56px hardcoded (`ChatView.module.css:28-29`); raw px paddings (`9px 14px`,
  `11px 14px`, `13px 14px`) and `1.5px` borders match the repo's existing convention
  (`Button.module.css` does the same), so low severity — but none are tokenized.
- Colors are fully tokenized (`--ai-bg`, `--navy`, `--missing` etc.) and the `.cp-dark` palette
  covers every token used here (`styles.css:150-175`) — dark mode is genuinely covered. No raw
  hex anywhere in this area's CSS or TSX.
- Inconsistency: navigation to `/ask` via `window.location.assign` (`ConditionDetail.tsx:184`)
  while every other nav uses Next `Link` (`Sidebar.tsx:43`, `MobileNav.tsx:26`).
- BFF route consistency is good: error mapping mirrors other routes
  (`route.ts:12-18`), `allow404AsNull` history pattern matches `endpoints.ts:178-182`, adapter
  defends against null/casing (`message.ts:4-7`).

---

## Verdict

The chat surface itself is lean and almost everything visible earns its place — the empty state,
chips, bubbles, typing indicator, error banner, and composer all map to real veteran stories, and
tokens/dark mode are properly handled. The failures are around the edges of the surface, not on
it: **the feature is unreachable from primary navigation**, the **free-tier story is a trap**
(full composer rendered, reactive 402 with no upgrade link, chips that bait a paywall error), the
one contextual entry point **drops its promised context**, and the transcript is **invisible to
screen readers** (no live region, no sender labels, unnamed input). Ship-blocking for a paid
flagship feature: nav entry, pre-send Pro gating (or 402→/upgrade CTA), and the aria-live/label
fixes. Fast follows: restore input on failed send, textarea composer, markdown rendering,
clear-history, and an explicit VSO-viewer decision. Cut or finish the stubs: GET `/api/chat`
(dead) and the contextless "Ask AI about this" redirect.
