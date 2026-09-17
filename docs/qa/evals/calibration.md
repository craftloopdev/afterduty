# Judge-vs-human calibration log

The standing record for VSO / domain-expert calibration of the eval harness
(spec §6). The harness ships *around* this open dependency rather than blocking on
it: every golden case carries `needs_expert_review: true` and an `expert_review`
block (`status: unreviewed` for all 24 at v1), and reports footnote
`gap_completeness` + `rating_band_accuracy` as PROVISIONAL until ≥ half the roster
is `confirmed`.

## Status

- Roster size: 24 committed (synthetic) cases + 1 private-tier REAL case (gc-100).
- Confirmed by a domain expert: **1** — gc-100, the private-tier real case
  (a complete C-file: 105 documents, three VA adjudication rounds including a
  supplemental grant and an HLR reversal), reviewed 2026-08-25 by the data
  subject, who is also the domain expert. Its content is PHI and never enters
  this repository: the case lives under a git-ignored external root loaded via
  `GOLDEN_PRIVATE_ROOT` (see `GoldenCaseLoader`), with its snapshot digest kept
  beside it rather than in `golden/snapshots/`. This log records the process
  only, never the case's data.
- The remaining 24 committed cases are confirmed by no expert yet.
- Rating bands are deliberately WIDE (e.g. PTSD 50–70) until corrected by review;
  narrowing a band is itself a golden-set change the snapshot gate makes visible.
  gc-100's bands are tight where the pipeline's estimate matched the VA's
  adjudicated value and span estimate-to-adjudicated where its expert-confirmed
  verification issues explain the difference.

## How calibration works

When a VSO reviews cases:

1. For each reviewed case, record the human's per-line scores beside the judge's
   scores in the table below (one row per case × line).
2. Flip that case's `expert_review.status` to `confirmed` (or `revised`, noting the
   change) in its `case.json`; tighten its bands if the reviewer corrects them.
3. Once ≥ 10 cases have BOTH judge and human scores, compute agreement
   (mean absolute difference per line). If agreement is poor on a line, revise the
   judge prompt → `judge-prompt-v2.md`; the version bump rides the snapshot gate.

## Agreement table (append rows as reviews land)

| case_id | line | judge | human | reviewer | date |
|---|---|---|---|---|---|
| _none yet_ | | | | | |

## Open

- VSO reviewer identification + scheduling is open product work (design Part 4,
  risk 4). Until then all scores on the provisional lines are directional, not
  authoritative — never quote them as production accuracy.
