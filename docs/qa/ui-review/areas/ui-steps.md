# UI Review — Next Steps + Scenarios ("Plan" area, `/steps`)

Scope: `web/src/app/(app)/steps/page.tsx`, `web/src/components/steps/NextStepsTabs.tsx`,
`web/src/components/steps/StepsPanel.tsx`, `web/src/components/steps/ScenariosPanel.tsx`,
`web/src/lib/adapters/gaps.ts`, sibling `.module.css` files, `web/src/lib/fixtures/steps.ts`,
plus shared chrome that frames this route (`(app)/layout.tsx`, `(app)/loading.tsx`,
`(app)/error.tsx`, `AppShell.tsx` title map, `Modal.tsx`, `Button.tsx`, `Pill.tsx`,
`EmptyState.tsx`, `Icon.tsx`).

Data flow context: `loadNextSteps()` (`web/src/lib/api/endpoints.ts:198-241`) builds the VM from
`/claim/gaps` + `/claim/conditions` + the combined-rating calculator. Gaps only exist after AI
analysis (a **Pro** feature per the upgrade page copy at `AppShell.tsx:40` — "AI extraction,
condition synthesis & gap analysis"), which matters for the free-user empty state below.

---

## Element Catalog

All file paths relative to `web/` unless noted. Personas: **veteran** (returning user with
analyzed claim), **new user** (no evidence yet), **free user** (uploaded docs, no Pro analysis),
**Pro user**, **VSO viewer** (parked feature).

| # | Element | file:line | User story | States covered | Gaps |
|---|---------|-----------|------------|----------------|------|
| 1 | Page h1 "Next Steps" + sub "Close the gaps that confirm your estimate and unlock extra pay." (rendered by shell TopBar) | `src/components/shell/AppShell.tsx:31-32`, `src/components/shell/TopBar.tsx:20` | As a veteran, I want to know I'm on the action-planning page so I can orient before scanning steps. | Desktop + mobile (shell-owned), dark mode via tokens | Sub copy promises "unlock extra pay" even when there are zero gaps or no analysis — overlaps the ceiling banner copy (#9) |
| 2 | Route-level EmptyState card ("No steps yet" / target icon / body copy) | `src/app/(app)/steps/page.tsx:11-23`, `src/components/ui/EmptyState.tsx:11-22` | As a new user with no evidence, I want to be told why this page is empty and what to do next so that I don't think the app is broken. | Empty (the only state it exists for); tokenized for dark mode | Wrong story for **free users**: gap analysis is Pro-only, so a free user who already uploaded docs still sees "Add your evidence and we'll surface…" — a promise the free tier can't keep. No upgrade CTA or differentiated copy (see Missing Affordances) |
| 3 | "Add evidence" CTA link → `/documents` inside the empty state | `src/app/(app)/steps/page.tsx:17-19`, `src/app/(app)/steps/steps.module.css:1-12` | As a new user, I want a one-tap path to upload evidence so the app can start finding steps for me. | Hover implicit; tokens (`--navy`, `--on-navy`, `--r-md`) | No `:focus-visible` style declared in `steps.module.css` (relies on global, unverified for `<a>`); only covers the new-user persona, not free-vs-Pro |
| 4 | Route loading skeleton (shared `(app)/loading.tsx`) | `src/app/(app)/loading.tsx:4-23` | As a veteran on a slow connection, I want a layout placeholder so the page doesn't flash blank while gaps + two combined-rating calls resolve. | Loading; `aria-busy` + `aria-label="Loading"` | Skeleton is **Home-shaped** (hero + 2-col grid), not Steps-shaped (banner + list); minor mismatch flash on `/steps` |
| 5 | Route error boundary with retry | `src/app/(app)/error.tsx:6-18` | As a veteran, I want a retry button when the gaps API fails so I can recover without losing my place. | Error + retry | Generic copy; fine |
| 6 | Segmented control container (`role="tablist"`, label "Next steps view") | `src/components/steps/NextStepsTabs.tsx:13`, `NextStepsTabs.module.css:7-26` | As a veteran, I want to switch between "what to do" (Steps) and "what it's worth" (Scenarios) so I can plan filing strategy in one place. | Desktop + mobile; dark mode via `--sunken`/`--card` | ARIA tabs pattern incomplete (see A11y #1); tab choice not in URL — can't deep-link/share the Scenarios view |
| 7 | "Steps" tab button | `NextStepsTabs.tsx:14-21` | As a veteran, I want the default view to be the prioritized to-do list so I land on actions, not projections. | Active/inactive via `data-active` + `aria-selected` | Missing `type="button"`; ~36px tall (8px pad + 0.875rem) — under 44px mobile tap target |
| 8 | "Scenarios" tab button | `NextStepsTabs.tsx:22-29` | As a veteran deciding *when* to file, I want a comparison of filing now vs. closing gaps first so I can weigh delay against extra pay. | Same as #7 | Same as #7 |
| 9 | Ceiling reframe banner (navy gradient): "Your estimate is exactly that — an estimate." + note | `src/components/steps/StepsPanel.tsx:46-71`, `StepsPanel.module.css:15-59` | As a veteran anchored on the home-page dollar estimate, I want a reframe that the number isn't guaranteed so I understand why these steps matter (educational-tool honesty). | Desktop (row) + mobile (stacked, `StepsPanel.module.css:329-368`); dark mode via `--hero-*` | Only on the **Steps** tab; the Scenarios tab shows dollar figures with no equivalent caveat. Note text uses raw `rgba(255,255,255,.74)` instead of `--on-navy-muted` |
| 10 | Info icon inside ceiling banner | `StepsPanel.tsx:48-50` | As a veteran, I want a visual cue that this banner is informational, not an alert. | aria-hidden by default (`Icon.tsx` exposes only with `title`) | Decorative — acceptable |
| 11 | "high-priority gaps" stat tile (count) | `StepsPanel.tsx:60-63`, `StepsPanel.module.css:61-83` | As a veteran, I want a count of urgent gaps so I can gauge how much work stands between me and a defensible claim. | Renders `0` gracefully; tabular-nums mono font | `statLabel` is 11px white at 66% opacity on gradient — likely contrast failure (see A11y #6) |
| 12 | "+$X/mo SMC available" stat tile (conditional) | `StepsPanel.tsx:64-69` | As a Pro veteran with SMC-eligible conditions, I want to see the extra monthly pay at stake so the steps feel worth doing. | Hidden when 0/undefined | **ORPHAN on this route**: `NextStepsTabs.tsx:32` never passes `smcAvailable` and `NextStepsVM` (`src/lib/models/vm.ts:129-135`) has no such field — unreachable dead branch on `/steps` (prop exists for a reuse that never happens; `smcAvailable` is Home-only via `HomeView.tsx:68`) |
| 13 | "Prioritized steps" h2 + "Ranked by value to your claim" note | `StepsPanel.tsx:73-76`, `StepsPanel.module.css:85-104` | As a veteran, I want to know the list order is value-ranked so I can just do the top item without second-guessing. | Desktop; mobile restyles to uppercase eyebrow (`StepsPanel.module.css:370-376`) | Claim is honest: `toSteps()` stable-sorts high→med→low (`src/lib/adapters/gaps.ts:30-36`) |
| 14 | Inline empty state "You're all caught up / No open gaps." | `StepsPanel.tsx:78-85`, `StepsPanel.module.css:214-250` | As a veteran who closed every gap, I want positive confirmation so I know absence of steps is success, not an error. | Empty-within-populated (readyCount>0, steps=0 — reachable via `page.tsx:10` guard) | No follow-on action ("See scenarios" / "Share with your VSO") — dead end |
| 15 | Step row (whole-card `<button>`) | `StepsPanel.tsx:88-116`, `StepsPanel.module.css:113-137` | As a veteran, I want to tap a gap to see why it matters and what to do so I can act on it. | Hover lift, `:focus-visible` outline (`StepsPanel.module.css:134-137`), mobile card restyle (`:382-401`), dark mode | Priority encoded by dot color only (A11y #3); no done/dismiss affordance |
| 16 | "Highest value" hero ribbon on first row | `StepsPanel.tsx:96-100`, `StepsPanel.module.css:139-160` | As an overwhelmed veteran, I want the single best next action flagged so I can ignore everything else today. | First row only (`data-hero`), navy border emphasis | 10.5px text (`font-size:.65625rem`) — below comfortable minimum; bolt icon decorative (ok) |
| 17 | Priority dot (red/amber/faint) | `StepsPanel.tsx:101`, `StepsPanel.tsx:18-22` | As a veteran scanning the list, I want a glanceable urgency marker per step. | high/medium/low via `--missing-dot`/`--partial-dot`/`--faint` tokens | **Color-only encoding** — no text/aria equivalent; invisible to screen readers, ambiguous to color-blind users (A11y #3) |
| 18 | Row title (gap label) | `StepsPanel.tsx:103` | As a veteran, I want the missing-evidence item named plainly so I know what the step is about. | Token colors | — |
| 19 | Row subline "{cond} · {type} leg · {suggest}" | `StepsPanel.tsx:104-106`, `StepsPanel.module.css:184-190` | As a veteran, I want condition + evidence-leg context so I can connect the step to my claim structure. | Desktop ellipsis truncation; mobile wraps (`:398-401`) | "leg" is triad jargon never defined on this page (it's a Conditions-page concept); truncation hides `suggest` on desktop with no tooltip |
| 20 | Impact pill (e.g. "+$340/mo"), strong variant for high priority | `StepsPanel.tsx:108-113`, `StepsPanel.module.css:192-207` | As a veteran, I want each step's payoff quantified so I can prioritize by value, not guesswork. | Neutral vs `data-strong` (green) variants; tabular-nums | `impact` comes from API as free text (`gaps.ts:23`) — empty string renders an empty pill (no fallback/omission) |
| 21 | Row chevron icon | `StepsPanel.tsx:114`, `StepsPanel.module.css:209-212` | As a veteran, I want a cue that the row opens detail. | aria-hidden | — |
| 22 | Step detail Modal (dialog "Next step", X close, Esc, scrim-click) | `StepsPanel.tsx:120,131-160`, `src/components/ui/Modal.tsx:15-55` | As a veteran, I want step detail without leaving the ranked list so I keep my scanning context. | Open/close, Esc, scrim, `aria-modal`, initial focus | No focus trap; focus not returned to triggering row on close (A11y #2); heading inversion: dialog title is `h3` (`Modal.tsx:45`) but body uses `h2` (`StepsPanel.tsx:139`) |
| 23 | Sheet impact badge "{impact} · {type} evidence" | `StepsPanel.tsx:136-138`, `StepsPanel.module.css:258-270` | As a veteran, I want the payoff and evidence type restated up top so the modal stands alone. | Always-strong green styling | Unlike the row pill (#20), high/med/low priority distinction is dropped here |
| 24 | Sheet gap title (h2) | `StepsPanel.tsx:139` | As a veteran, I want the gap named as the modal headline. | — | Heading-order issue noted in #22 |
| 25 | "For {cond}" line | `StepsPanel.tsx:140-142` | As a veteran with multiple conditions, I want the affected condition named so I don't act on the wrong one. | — | Plain text — not a link to the condition (see #27) |
| 26 | "Why" paragraph | `StepsPanel.tsx:143` | As a veteran, I want the rationale (educational framing) so I trust the recommendation enough to act. | — | Empty string from API renders an empty `<p>` (no fallback) |
| 27 | "Suggested action" callout box | `StepsPanel.tsx:144-147`, `StepsPanel.module.css:297-316` | As a veteran, I want a concrete instruction (e.g. "get a nexus letter") so I know exactly what to obtain. | Sunken token box, uppercase kicker | Same empty-string risk |
| 28 | "Add evidence" primary button (modal) | `StepsPanel.tsx:149-151` | *Intended:* As a veteran, I want to upload the missing document for this gap right now. | Full-width; stacks on mobile (`StepsPanel.module.css:404-406`) | **ORPHAN/STUB**: `onClick={onClose}` — it only closes the modal. No navigation to `/documents`, no pre-tagged upload. The primary CTA of the entire page is dead |
| 29 | "View condition" ghost button (modal) | `StepsPanel.tsx:152-154` | *Intended:* As a veteran, I want to jump to the condition this gap belongs to. | Full-width; stacks on mobile | **ORPHAN/STUB**: also `onClick={onClose}`. `step.condId` exists (`vm.ts:97`) and `/conditions/[id]` route exists — the link was simply never wired |
| 30 | Scenarios info banner ("We combine ratings with VA math (not simple addition)…") | `src/components/steps/ScenariosPanel.tsx:22-26`, `ScenariosPanel.module.css:16-31` | As a veteran confused why 50%+30% ≠ 80%, I want the combined-rating math explained so the numbers below are credible. | Desktop + mobile radius variant; `--ai-bg`/`--ai-fg` tokens | Explains math but **not** that pay figures are estimates (the caveat lives only on the Steps tab, #9) |
| 31 | Scenario card | `ScenariosPanel.tsx:32-66`, `ScenariosPanel.module.css:34-39` | As a veteran, I want each filing strategy as a discrete card so I can compare options side by side. | Base vs alt; desktop bordered / mobile shadowed (`:138-149`) | Static — no action; no link to the gaps a scenario depends on |
| 32 | Scenario label ("File your 3 ready conditions" / "+ Close 2 high-priority gaps") | `ScenariosPanel.tsx:37`, generated at `endpoints.ts:219,231` | As a veteran, I want each option named by what I'd actually do. | Pluralization handled | Edge case: with 0 ready conditions but open gaps, renders "File your 0 ready conditions" with $0/mo (`endpoints.ts:219`) — nonsense copy that is reachable |
| 33 | Combined-rating badge Pill (green, e.g. "100%") | `ScenariosPanel.tsx:38`, `src/components/ui/Pill.tsx:11-20` | As a veteran, I want the combined rating per scenario since rating drives everything else (pay, benefits). | Token-driven colors | Always `tone="green"` even for the inferior base scenario — green reads as "good/recommended" on both cards, diluting the alt card's accent; `ScenarioVM.combinedRating` (`vm.ts:121`) is otherwise unused |
| 34 | Condition names line (first 3) | `ScenariosPanel.tsx:41-47`, `ScenariosPanel.module.css:64-71` | As a veteran, I want to see which conditions each scenario includes so I can sanity-check it. | Hidden when empty; divider via `--line-soft` | — |
| 35 | "+N more" suffix | `ScenariosPanel.tsx:44-46`, `ScenariosPanel.module.css:73-76` | As a veteran with many conditions, I want the list kept scannable. | — | Not expandable — no way to see the full list (the count is computed but the names are discarded) |
| 36 | "Estimated monthly pay" label + foot row | `ScenariosPanel.tsx:50-51`, `ScenariosPanel.module.css:79-89` | As a veteran, I want the outcome metric labeled as an estimate. | — | Word "Estimated" is the only caveat on this tab |
| 37 | Pay figure `$X` + `/mo` | `ScenariosPanel.tsx:52-55`, `ScenariosPanel.module.css:91-107` | As a veteran, I want the dollar outcome of each strategy so the comparison is concrete. | Mono/tabular; larger on mobile (`:159-161`) | Backend calc failures silently coerce to `$0/mo` (`endpoints.ts:210-214` `.catch(() => zero)`) — a veteran could see "$0/mo" for a 100% claim with no error indication |
| 38 | Delta line "+$X/mo vs filing now" (alt card only) | `ScenariosPanel.tsx:58-65`, `ScenariosPanel.module.css:110-126` | As a veteran, I want the marginal gain of closing gaps quantified so I can decide if the work/wait is worth it. | Only when `alt && deltaPay > 0`; green `--strong` tokens | If closing gaps adds rating but no pay change (VA math plateaus), the alt card shows no delta and no explanation ("why bother?" goes unanswered) |
| 39 | Alt-card dashed green border accent (CSS-only) | `ScenariosPanel.module.css:42-45,146-149` | As a veteran, I want the "stretch" scenario visually distinguished from the baseline. | Desktop + mobile variants | Meaning ("dashed = hypothetical/recommended") is never stated; purely conventional. Combined with #33's both-green badges, the visual hierarchy between the two cards is muddy |
| 40 | ScenariosPanel empty render (`return null`) | `ScenariosPanel.tsx:18` | — (anti-element) | — | **ORPHAN**: a veteran who taps the Scenarios tab when `scenarios` is empty gets a completely blank panel under the tabs — no empty state. Currently unreachable via `loadNextSteps` (always pushes ≥1 scenario, `endpoints.ts:217-227`) but the component contract permits it |
| 41 | Fixture data (3 ready conds / 2 scenarios) | `src/lib/fixtures/steps.ts:4-29` | As a developer, I want a populated VM for dev routes/screenshots. | Dev-only | Not user-facing; consistent with VM shape |
| 42 | `toSteps`/`toStep` adapter defaults (`type ?? "Nexus"`, empty-string fallbacks) | `src/lib/adapters/gaps.ts:13-27` | As a veteran, I want malformed API gaps to still render rather than crash the page. | Null-safety | Defaulting unknown gap type to **"Nexus"** fabricates evidence-type info in the UI ("Nexus leg", "Nexus evidence" in the modal) — an honesty problem for an educational tool; empty `gap`/`impact` render blank elements (#20, #26) |

**Distinct visual elements catalogued: 42** (39 user-facing, 1 anti-element, 1 fixture, 1 adapter behavior surfaced visually).

---

## Orphans (no defensible story as built — cut or fix)

1. **"Add evidence" modal button is a stub** — `StepsPanel.tsx:149-151`. `onClick={onClose}`. This is the page's primary conversion action ("close your gap") and it does nothing but dismiss the modal. Either wire to `/documents` (ideally with `condId`/leg context) or remove until it works. As shipped it actively erodes trust: a veteran taps "Add evidence," the modal vanishes, nothing happens.
2. **"View condition" modal button is a stub** — `StepsPanel.tsx:152-154`. Same `onClick={onClose}`. `/conditions/[id]` exists and `step.condId` is in the VM — should be `<Link href={`/conditions/${step.condId}`}>`.
3. **SMC stat tile unreachable on `/steps`** — `StepsPanel.tsx:14-15,41,64-69`. `NextStepsTabs.tsx:32` doesn't pass `smcAvailable`, and `NextStepsVM` (`vm.ts:129-135`) has no field for it. Dead prop + dead branch on the only route that renders this panel. Either plumb it through the VM or delete the prop.
4. **Blank Scenarios tab** — `ScenariosPanel.tsx:18` `return null` leaves zero UI under an active tab. Replace with an inline empty state ("Add conditions to see filing scenarios").
5. **`ScenarioVM.combinedRating` never rendered** — `vm.ts:121`; the `badge` string duplicates it. Data orphan (minor; not visual).

## Missing Affordances (stories with no supporting element)

1. **As a free user, I want to know that steps come from Pro gap analysis so I understand why this page is empty and what upgrading buys.** The empty state (`page.tsx:14-15`) promises "Add your evidence and we'll surface the highest-value steps" — but gap analysis is Pro (`AppShell.tsx:40`). A free user who already uploaded documents is stuck at a misleading dead end with no upgrade CTA. This is the single most important monetization gap in the area.
2. **As a veteran, I want to actually add evidence for a specific gap** (working upload flow, pre-associated with the condition/leg). The button exists but is dead (Orphan #1).
3. **As a veteran, I want to mark a step done or dismiss one that doesn't apply** ("I already sent the nexus letter," "this condition resolved"). No done/dismiss/snooze anywhere; the list only changes when the backend re-analyzes.
4. **As a veteran on the Scenarios tab, I want the "this is an estimate, not a promise of benefits" disclaimer next to the dollar figures.** The ceiling caveat (#9) is Steps-tab-only; an educational tool showing "$3,967/mo" needs the caveat where the money is.
5. **As a veteran, I want to deep-link or return to the Scenarios tab** — tab state is `useState` only (`NextStepsTabs.tsx:10`), not URL-synced; back/forward/share always lands on Steps.
6. **As a veteran, I want to know when these steps were generated / trigger re-analysis** — no freshness indicator or refresh affordance; stale gaps look identical to current ones.
7. **As a veteran with many conditions, I want the full condition list of a scenario** — "+N more" (`ScenariosPanel.tsx:44-46`) has no expansion.
8. **As a veteran who is all caught up, I want a next action from the success state** — `StepsPanel.tsx:79-85` is a dead end (no "See scenarios" / "Share with your VSO" link).
9. **As a VSO viewer, I want read-only framing of the veteran's plan** — no viewer-aware treatment here (acknowledged as parked per project plans; recorded for completeness).
10. **As a veteran whose pay calc failed, I want an error cue instead of "$0/mo"** — `endpoints.ts:212-213` swallows calculator failures into zeros with no UI signal (#37).

## A11y & Consistency

### Accessibility
1. **Incomplete ARIA tabs pattern** — `NextStepsTabs.tsx:13-30`: `role="tablist"`/`role="tab"` + `aria-selected` are present, but there are no `id`/`aria-controls` links, the panels lack `role="tabpanel"`, and there is no arrow-key navigation (WAI-ARIA APG requires Left/Right + roving tabindex). Screen-reader users are told "tab 1 of 2" but the pattern's keyboard contract is broken. Either implement fully or downgrade to plain toggle buttons with `aria-pressed`.
2. **Modal focus management** — `Modal.tsx:18-26`: initial focus moves to the card, but there is no focus trap (Tab walks into the background page behind the scrim) and focus is not restored to the triggering step row on close. Esc + scrim-click + `aria-modal` + labeled close button are good.
3. **Priority is color-only** — `StepsPanel.tsx:18-22,101`: high/medium/low is conveyed solely by dot color; no text, no `aria-label`. Color-blind and screen-reader users cannot perceive step urgency. Fix: visually-hidden text or include priority in the row's accessible name.
4. **Heading order inversion in modal** — `Modal.tsx:45` renders the dialog title as `h3`, then the sheet body uses `h2` (`StepsPanel.tsx:139`) — h3 followed by h2 inside the dialog.
5. **Tap targets** — segmented tab buttons are ~36px tall (`NextStepsTabs.module.css:15-21`), under the 44px mobile guideline.
6. **Low-contrast / tiny text on navy gradient** — `statLabel` 11px at `rgba(255,255,255,.66)` (`StepsPanel.module.css:78-83`) and `ceilingNote` at `.74` opacity (`:52`); ribbon text is 10.5px (`:155`). Contrast on a gradient varies by theme — likely below 4.5:1 at the light end of `--hero-*`.
7. **Tab buttons missing `type="button"`** — `NextStepsTabs.tsx:14,22` (harmless outside forms, but every other button in the area sets it, e.g. `StepsPanel.tsx:91`).
8. Positives worth keeping: step rows are real `<button>`s with `:focus-visible` outlines (`StepsPanel.module.css:134-137`); icons are `aria-hidden` unless titled (`Icon.tsx`); skeleton sets `aria-busy`; modal close has `aria-label="Close"` (`Modal.tsx:46`).

### Design tokens / consistency
9. **Raw white rgba() on the ceiling** — `StepsPanel.module.css:52,66,82,361` — the file's own header comment says "Every color is a token," but `ceilingNote`/`stat`/`statLabel` hardcode `rgba(255,255,255,…)` while `ceilingIc` correctly uses `--on-navy-muted`. Inconsistent within the same component.
10. **Off-scale radii and spacing** — `border-radius: 13px` (`StepsPanel.module.css:65`) and `14px` (`:361`) instead of `--r-*`; raw gaps/paddings (`9px`, `11px`, `13px`, `14px`, `18px`) throughout `StepsPanel.module.css:30,57,110`, `ScenariosPanel.module.css:11,37,53,69`, `NextStepsTabs.module.css:8-16`, `steps.module.css:5` while `--s4…--s24` tokens exist and are used elsewhere in the same files.
11. **Both scenario badges green** — `ScenariosPanel.tsx:38` hardcodes `tone="green"`; combined with the alt card's green dashed border, the base card inherits "recommended" coloring. Consider neutral tone for the base scenario.
12. **Dual estimate caveats diverge** — shell sub (`AppShell.tsx:32`) and ceiling banner (`StepsPanel.tsx:52-56`) say overlapping but differently-worded things on the same screen ("confirm your estimate and unlock extra pay" vs "lock in the evidence… and unlock extra pay").
13. **Adapter fabricates "Nexus"** — `gaps.ts:22` defaults missing gap type to `"Nexus"`, which then renders as factual copy ("Nexus leg", "Nexus evidence") in `StepsPanel.tsx:105,137`. Prefer an honest "evidence" fallback.
14. **Reachable nonsense copy** — "File your 0 ready conditions" / "$0/mo" scenario when `ready.length === 0` but gaps exist (`endpoints.ts:219`); the `page.tsx:10` guard only catches steps=0 AND ready=0.

---

## Verdict

The information design of this area is strong: the ranked list, hero ribbon, ceiling reframe, and
two-scenario comparison all map cleanly to real veteran stories, the empty/error/loading triad
exists at the route level, and theming is overwhelmingly token-driven with deliberate
mobile/desktop variants. But the area fails its own conversion test: **both action buttons in the
step detail modal are dead stubs** (`StepsPanel.tsx:149-154`) — the page tells a veteran exactly
what to do and then gives them no way to do it — and the **free-user empty state makes a promise
the free tier can't keep** with no upgrade path. Fix those two, plumb or delete the unreachable
SMC stat, add a pay-estimate disclaimer to the Scenarios tab, and close the ARIA-tabs/focus-trap/
color-only-priority gaps, and this becomes one of the most defensible screens in the app.

**Counts:** 42 elements catalogued · 5 orphans · 10 missing affordances · 8 a11y issues · 6 token/consistency issues.
