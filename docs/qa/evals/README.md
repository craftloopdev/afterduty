# Eval harness — conventions, how to run, how to read a report

The analysis-pipeline eval harness (Increment 8). Two tiers share one golden
corpus of synthetic cases:

| Tier | Measures | LLM | Runs | Gate |
|---|---|---|---|---|
| **OFFLINE** (deterministic) | pipeline invariants — abstention honesty, supersede atomicity, carry-forward/dirty-scope, VA-math determinism, pyramiding, reader filters | `FakeLlmAsyncProvider` canned responses | default `./gradlew test` / `check` | hard CI fail |
| **LIVE** (scored) | model accuracy — condition recall/precision, rating bands, gap completeness, citation accuracy, no-legal-advice, plain language | real Vertex Claude + Gemini, Gemini judge | manual / scheduled, `-PliveEval` | scored report committed here; required before model flips |

Full design + rationale: [`docs/architecture/inc8-eval-harness-spec.md`](../../architecture/inc8-eval-harness-spec.md).

Overview tying this loop to the assurance-engine integration, the committed results to
date, and how engine impact will be measured:
[`docs/architecture/assurance-engine-eval-and-impact.md`](../../architecture/assurance-engine-eval-and-impact.md).

## The golden corpus

Lives in `spring-backend/src/test/resources/golden/`:

- `manifest.json` — the case index (id, slug, tags, status).
- `cases/gc-NNN-slug/` — one dir per case:
  - `case.json` — metadata, phases (docs), and the offline canned-response refs.
  - `expected.json` — per-phase expected outcomes (shared by both tiers).
  - `docs/*.txt` — synthetic source documents (PHI rule §1.1: synthetic ONLY).
  - `canned/…` — per-doc DocFacts extraction + per-purpose / per-DC model responses
    the offline tier replays.
- `snapshots/offline-summary.json` — the checked-in end-state digest per case +
  the prompt versions (the prompt-bump gate).
- `judge/judge-prompt-v1.md` — the versioned cross-family judge rubric.

**Every case is synthetic** — invented veterans (`SGT Avery Stone`), invented
providers (`Dr. Lin Patel`), plausible-but-fictional records. A real-looking name
in review is a red flag. Each case carries `needs_expert_review: true` until a
VSO/domain expert confirms its bands + gaps (see `calibration.md`).

## Running the offline tier

```bash
cd spring-backend
JAVA_HOME=/opt/homebrew/opt/openjdk@21 ./gradlew evalOffline      # just the eval suite
JAVA_HOME=/opt/homebrew/opt/openjdk@21 ./gradlew test             # full suite (includes it)
JAVA_HOME=/opt/homebrew/opt/openjdk@21 ./gradlew evalOffline -Peval.cases=gc-001,gc-014  # subset
```

The offline gate is `OfflineGoldenPipelineTest` (one parameterized case each) +
`PromptVersionEvalGateTest`. Both are tagged `eval-offline` AND `regression`.

### Regenerating the snapshot (after an intended pipeline / prompt change)

A pipeline behavior change shows up as a digest diff; a prompt/schema/rating
version bump fails the version gate. Both are regenerated the same way — which
requires naming a live-eval run id (the acknowledgment that the scored tier was
run, or consciously waived):

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@21 ./gradlew evalSnapshot -PevalRunId=<live-run-id>
```

This rewrites `golden/snapshots/offline-summary.json` (digests + current prompt
versions) and stamps the run id. Commit the snapshot diff alongside the change.

## Running the live tier (manual, ADC required)

```bash
GOOGLE_APPLICATION_CREDENTIALS=$HOME/.gcp/default-compute-sa.json \
GCP_PROJECT=craftloop-va-claim JAVA_HOME=/opt/homebrew/opt/openjdk@21 \
./gradlew evalLive -PliveEval [-Peval.cases=gc-001] [-Peval.baseline=<run-id>]
```

The live tier writes a run directory under `runs/<ts>-…/` with `report.md`,
`report.json`, `scores.csv`. Operators commit the run directory — it is the durable
record (same ethos as `.tdd/regression` replay entries).

The run aborts cleanly if it would exceed `EVAL_MAX_SPEND_USD` (default $15, judge
calls included): the spend guard sums every logged `AiCallLog` row (pipeline +
`eval_judge`) before each case and after each stage tick-batch, marks the remaining
cases `skipped`, and finishes the report with `aborted_reason: "spend_cap"`.

## Model-flip recipe — baseline vs candidate (`ROUTE_*` overrides)

Every model/prompt/scoping decision the design flagged is the **same three-step
recipe**: a baseline run, a candidate run with `ROUTE_*` env overrides, and a
committed comparison report (spec §5). `report.json.routing` records the resolved
`purpose → provider/model` table for each run, so the comparison is honest.

1. **Establish (or reuse) a baseline.** The first committed run with no
   `-Peval.baseline` is `verdict: BASELINE`. Note its run id.

   ```bash
   GOOGLE_APPLICATION_CREDENTIALS=$HOME/.gcp/default-compute-sa.json \
   GCP_PROJECT=craftloop-va-claim JAVA_HOME=/opt/homebrew/opt/openjdk@21 \
   ./gradlew evalLive -PliveEval                       # → runs/<ts>-baseline/
   ```

2. **Run the candidate** with the model lever flipped via `ROUTE_*` env vars,
   naming the baseline so the report computes deltas and a verdict:

   ```bash
   # Fable-5 for identify+verify (adopt only if it buys measurable accuracy):
   ROUTE_SYNTHESIS_IDENTIFY_MODEL=claude-fable-5 \
   ROUTE_SYNTHESIS_VERIFY_MODEL=claude-fable-5 \
   GOOGLE_APPLICATION_CREDENTIALS=$HOME/.gcp/default-compute-sa.json \
   GCP_PROJECT=craftloop-va-claim JAVA_HOME=/opt/homebrew/opt/openjdk@21 \
   ./gradlew evalLive -PliveEval -Peval.baseline=<baseline-run-id>

   # Flash-tier extraction swap (cheap extraction fails first on honesty):
   ROUTE_EXTRACTION_DOC_MODEL=gemini-3-flash-preview \
   ./gradlew evalLive -PliveEval -Peval.baseline=<baseline-run-id>

   # Per-condition dirty-scoping control (end-state equivalence vs a full re-run):
   INCREMENTAL_ANALYSIS=false \
   ./gradlew evalLive -PliveEval -Peval.cases=gc-023,gc-024 -Peval.baseline=<baseline-run-id>
   ```

   `ROUTE_*` overrides bind the same `LlmRoutingProperties` the production table
   uses (`LiveEvalProviderConfig` wires the real router), so they swap exactly one
   purpose's model and leave the rest at prod. `AiCostService` already prices
   `claude-fable-5` ($10/$50), so the report's cost section does the premium math.

3. **Read the verdict, commit both run dirs.** `REGRESSION` if any aggregate
   deterministic metric dropped > 0.05 or `gap_completeness` mean dropped > 0.10
   vs the baseline; `FAIL` on any judge hard-fail. Adoption bar (e.g. Fable 5): a
   *measurable* recall/precision gain on the relevant tags, **zero new hard-fails**,
   cost delta acceptable. Commit the candidate run directory beside the baseline —
   the diff is the decision record.

## How to read a report

- **verdict** — `BASELINE` (first committed run / no baseline named) | `PASS` |
  `REGRESSION` (an aggregate deterministic metric dropped > 0.05, or gap_completeness
  mean dropped > 0.10, vs the named baseline) | `FAIL` (any judge hard-fail on any
  case — legal advice or a fabricated citation).
- **aggregate** — means per metric + hard-fail count. `gap_completeness` and
  `rating_band_accuracy` are PROVISIONAL until ≥ half the roster is VSO-confirmed.
- **per-case table** — deterministic scores + judge scores + per-case cost + status.
- **hard-fail violations** — every quoted violating sentence.
- **cost** — pipeline vs judge vs total USD, plus `aborted_reason` if the spend cap
  tripped.

## Adding a golden case (e.g. from a production failure)

1. Re-author it synthetically (PHI rule) — same failure shape, invented facts.
2. Land it `status: draft` with the failing expectation.
3. Flip to `active` once offline-green; regenerate the snapshot; run the live tier.

The authoring tool (`spring-backend/build/gen_*.py`) emits the case files from a
compact spec; the emitted JSON/txt under `golden/cases/` is the committed,
human-reviewed source of truth (the script is a convenience, not committed input).
