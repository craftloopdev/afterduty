# Service History Reconciliation — design

**Date:** 2026-07-05 · **Status:** approved-in-principle (owner chose all recommended options), pending Phase-1 go-ahead.

## Problem

`GET /api/auth/profile` derives `servicePeriods[]` via `ServicePeriodDeriver`, which dedupes on an **exact** key: `branch string + startDate + endDate` (`ServicePeriodDto.dedupeKey()`). Real government records defeat exact-match: one 2001–2009 Navy enlistment surfaces as `"NAVY USN"` / `"US Navy"` / `"Navy"`, with different MOS lists, a rate on some records, and end dates 2008 vs 2009 — so the six rows never collapse (see profile screenshot 2026-07-05), and "≈24 years total" is the un-merged periods **summed** (overlaps double-counted). Records are duplicative by nature and that duplication is visible on VA paperwork, so the app must reconcile transparently — show the conclusion, keep the receipts.

## Owner decisions (2026-07-05)

1. **Reconcile engine:** deterministic rules + LLM only for genuine conflicts.
2. **Authority:** VA-style document-type hierarchy, with veteran override in the drill-down.
3. **Placement:** dedicated "Service History" screen (top-level workflow), linked from Profile.
4. **Chat:** records/corrects a service *fact* as another source and re-derives — never silently overwrites a conclusion.

## Architecture

- **`ServiceHistoryReconciler`** (new backend service) consumes the per-document raw periods that `ServicePeriodDeriver.periodFromGroup` already extracts (+ manual `ServiceProfile` + chat-recorded facts) and returns reconciled **conclusions**.
  - **Normalize:** canonicalize branch via a synonym map (`NAVY USN|US Navy|USN → Navy`, etc.); normalize component.
  - **Cluster:** group raw periods into *enlistments* by `normalized branch + component + overlapping-or-adjacent date ranges` (interval-merge, not string-equality).
  - **Resolve per field** by **doc-type precedence** (see hierarchy); widest confidently-sourced date range; union of MOS; best-authority rank.
  - **Reasoning trace:** every conclusion records which source won each field and why (deterministic explanation string), plus any LLM adjudication text.
- **LLM conflict adjudication** runs ONLY for clusters with a genuine unresolved conflict (equal-authority contradictory dates, ambiguous "same enlistment?"). Runs in the **analysis pipeline** (not at read time) and its result + reasoning is **persisted**, so reads stay fast + deterministic. Flagged behind a config flag.
- **Doc-type hierarchy (VA evidence weight, highest→lowest):** DD-214 (Certificate of Release/Discharge) → NGB-22 (Guard) → service personnel/treatment records → orders → self-statement/manual. Source type comes from the atom's owning `EvidenceItem` classification (`aiClassification`); manual profile = self-statement; chat fact = self-statement unless it cites a document.
- **Conclusion shape (per enlistment):** `{ branch, component, startDate, endDate, mos[], rank, totalYears, sources[], reasoning, veteranOverride? }` where `sources[] = { evidenceId, docType, authorityRank, contributedFields[], rawValues }`.
- **Total years** = union of reconciled (non-overlapping) intervals — fixes the inflation.

## Data approach

- **Phase 1** derives on read (extends `ServicePeriodDeriver`), purely deterministic — no migration, always fresh. This alone fixes the six-duplicates + "24 years" bug.
- **Phase 3** adds a persisted `service_history_resolution` (LLM adjudication result + veteran override), re-computed on evidence change, so reads never pay for the LLM.

## UI (dedicated screen)

- **List:** one row per reconciled enlistment — the **conclusion only** (branch · component · dates · rate/MOS summary), plus a `[ N sources ▸ ]` affordance and the corrected total header.
- **Modal (drill-down):** two columns — **Facts** (each source: doc type, raw values, ✓ which the conclusion took) and **Reasoning** (how many merged, which source won each field + why, any conflict the LLM adjudicated) + **"This is wrong — correct it"** (veteran override, persisted, top authority).
- Nav entry from Profile → Service History; reuses the token system + overflow discipline.

## Chat integration

- Chat gets a `record_service_fact` action (Increment C sibling): writes a service fact (branch/dates/deployment/exposure/MOS) as a **source** (self-statement authority, or higher if it cites a doc) → triggers re-derive. The fact appears in the modal's Facts + Reasoning. Never writes a conclusion directly. Ties into the presumptive `ServiceProfile` (deployments/exposure_risks) so confirming service also finalizes provisional presumptives.

## Phasing

- **P1 — deterministic reconciler + total fix (backend).** Normalize + cluster + doc-type precedence + union total, derive-on-read, reasoning trace. Ships the core bug fix. Tests: real-world duplicate corpus (the screenshot's 6 rows) → 1 conclusion, correct total.
- **P2 — dedicated Service History screen + evidence/reasoning modal (web).** Conclusion list + drill-down + nav.
- **P3 — LLM conflict adjudication (persisted) + veteran override.**
- **P4 — chat `record_service_fact` → re-derive** (+ presumptive finalize).

## Regression / risks

- Branch synonym map must be conservative (don't merge genuinely different branches). Interval-merge must not swallow a real second enlistment (require same normalized branch+component AND overlap/adjacency, not just proximity).
- `ServicePeriodDto` wire shape changes (add sources/reasoning) — keep back-compat for existing profile card until P2 lands.
- Doc-type classification may be missing/wrong on some evidence → fall back to lowest authority, never crash.
