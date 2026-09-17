# The assurance engine, the eval loop, and how impact gets measured

**Date:** 2026-09-07 · **Audience:** a reader who has not followed the integration threads.

This is the single entry point for four questions that are otherwise spread across eight
documents and a test tree:

1. What is the assurance engine, and why is it being added?
2. What eval loop exists today?
3. What results has that loop produced?
4. What is the engine's impact on analysis quality?

**Read this status line before anything else, because it governs how to read the rest:**

| Thing | State on 2026-09-07 |
|---|---|
| Eval harness (two tiers, golden corpus, scoring, spend guard) | **Built and running.** 24 committed cases, offline tier gates CI. |
| Committed live-eval results | **Two runs, both 2026-06-12, one case each.** Thin, and they predate the engine. |
| Assurance engine integration | **Approved, not built.** D1–D6 signed off 2026-09-06; zero lines of engine code in this repo. |
| Engine impact on quality | **Not measured, because it cannot be yet.** The measurement design exists; no run has happened. |

Nothing below reports an engine result, because there is no engine result to report. §4 describes
the scoreboard that will produce one and the bar it has to clear. If you came here for a
before/after number, the honest answer is that it does not exist on this date, and any number
presented as one would be fabricated.

---

## 1. The engine: what it is and why

Craftloop's Assurance Evidence Engine becomes a **second verifier** inside After Duty's trust
boundary, running as a separate Cloud Run service.

**It is a verifier, not a rater.** This framing was settled on 2026-09-05 after an earlier draft
posed it as "which conditions does the engine rate," which created a false choice between
verifiability and decidability. The rating path is untouched.

The app already verifies: `SynthesisVerificationAgent` is an LLM checking the LLM's own work.
That is a real check, but it shares a failure mode with the thing it checks — the same class of
model, the same blind spots, the same tendency to agree with a confident wrong answer. The engine
is a **deterministic, rule-driven** second opinion sitting beside it. Where the two disagree, the
disagreement is the signal.

The initial rule pack covers, in order:

| # | Check | Scope | Why first |
|---|---|---|---|
| 1 | **Triad sufficiency** — current diagnosis, in-service event, nexus | All conditions | The triad is identical for every diagnostic code; already modelled as service-connection sufficiency rules. |
| 2 | **Pyramiding** (38 CFR §4.14) | All conditions | Inherently cross-condition. `PyramidingRules` / `PyramidingGroups` give a trusted baseline to diff against. |
| 3 | Per-DC rating criteria, decidable ones first — 6260 (flat 10% cap), 6100 (Table VI/VII lookup) | Per condition | Both codes have real adjudicated ground truth available. |

**Deployment posture (D6):** engine pinned at `1b94993` or later — earlier pins predate endpoint
authentication and would deploy a service with no token gate. Fail-closed startup: no
`ASSURANCE_SERVICE_TOKEN`, no boot. Dedicated runtime service account, `--no-allow-unauthenticated`,
`run.invoker` granted to the API's identity alone. Calls carry two headers — the Google-signed
identity token in `Authorization` (platform control) and the shared token in
`X-Assurance-Service-Token` (second line).

**Zero UI impact in v0, by construction.** The pilot runs beside the eval harness, never touches
live user data, and ships nothing to production. A shadow pilot that leaked into the UI would stop
being a shadow pilot.

Full detail: [`assurance-engine-decisions-memo.md`](assurance-engine-decisions-memo.md) (D1–D6),
[`assurance-engine-owner-runbook-2026-09-07.md`](assurance-engine-owner-runbook-2026-09-07.md)
(the five one-time IAM/secret commands the deploy identity deliberately cannot run).

---

## 2. The eval loop that exists

Built as Increment 8. Two tiers share one golden corpus, because evals that call real models cannot
run in unit CI — cost, credentials, nondeterminism, latency.

| Tier | Measures | LLM | Runs | Gate |
|---|---|---|---|---|
| **OFFLINE** (deterministic) | pipeline invariants — abstention honesty, supersede atomicity, carry-forward/dirty-scope, VA-math determinism, pyramiding, reader filters | `FakeLlmAsyncProvider` canned responses | every `./gradlew test` | **hard CI fail** |
| **LIVE** (scored) | model accuracy — condition recall/precision, rating bands, gap completeness, citation grounding, no-legal-advice, plain language | real Vertex Claude + Gemini, Gemini judge | manual, `-PliveEval` | scored report committed; required before model flips |

**The golden corpus** lives in `spring-backend/src/test/resources/golden/`: 24 active cases
(`gc-001`…`gc-024`) tagged across multi-condition, bilateral, presumptive, abstention-expected,
non-medical-docs, contradictory-evidence, deterministic-rating, pyramiding, and incremental.

**Every committed case is synthetic** — invented veterans (`SGT Avery Stone`), invented providers
(`Dr. Lin Patel`), plausible-but-fictional records. This is enforced, not merely promised:
`GoldenCorpusValidationTest` fails the build if any committed case document lacks an explicit
`(synthetic)` marker (PHI rule §1.1).

**The one real case is not in this repository.** `gc-100` is a complete C-file — 105 documents,
three VA adjudication rounds including a supplemental grant and an HLR reversal, expert-confirmed
2026-08-25 by the data subject. It is PHI. It loads solely from a git-ignored external root via
`GOLDEN_PRIVATE_ROOT`, with its snapshot digest kept beside it rather than in
`golden/snapshots/`. Zero tracked files in this repo match `gc-100`;
[`calibration.md`](../qa/evals/calibration.md) records the process only, never the data.

**Guard rails the loop carries:**

- **Snapshot gate** — a pipeline behavior change shows up as an end-state digest diff; a
  prompt/schema/rating version bump fails `PromptVersionEvalGateTest`. Regenerating either requires
  naming a live-eval run id, so the scored tier cannot be silently skipped.
- **Spend guard** — the live tier aborts cleanly at `EVAL_MAX_SPEND_USD` (default $15, judge calls
  included), marks remaining cases `skipped`, and stamps `aborted_reason: "spend_cap"`.
- **Model-flip recipe** — baseline run, candidate run with `ROUTE_*` env overrides binding the same
  `LlmRoutingProperties` production uses, committed comparison. `report.json.routing` records the
  resolved purpose → provider/model table so the comparison is honest.

How to run either tier: [`docs/qa/evals/README.md`](../qa/evals/README.md).
Design and rationale: [`inc8-eval-harness-spec.md`](inc8-eval-harness-spec.md).

---

## 3. Results the loop has actually produced

Two committed live runs, both 2026-06-12, both **single-case (`gc-001` only)**. They are a
harness-validation exercise, not a quality measurement of the current pipeline.

| Run | Verdict | Recall | Precision | Rating band | Gap structural | Hard fails | Cost |
|---|---|---|---|---|---|---|---|
| `2026-06-12T0622-baseline` (sha `4f060a2`) | BASELINE | 0.000 | 1.000 | 1.000 | 0.000 | 0 | $0.035 |
| `2026-06-12T0757-candidate` (sha `cace70c`) | REGRESSION | 1.000 | 0.714 | 1.000 | 0.500 | 0 | $0.857 |

**Reading these honestly:**

- The candidate is *better*, not worse, on the thing that matters most: it went from finding **none**
  of the conditions (recall 0.000) to finding **all** of them (1.000), and gap structural score rose
  0.000 → 0.500. The `REGRESSION` verdict fired because precision fell 1.000 → 0.714 — and a
  baseline that identifies nothing scores a trivially perfect precision. This is the verdict rule
  behaving as written, on a baseline too degenerate for the rule to be meaningful.
- The 24× cost increase ($0.035 → $0.857) is the same story: the baseline was barely doing work.
- **n = 1.** No aggregate over one case is a measurement of anything.
- `gap_completeness` and `rating_band_accuracy` are flagged **PROVISIONAL** in both reports, and
  remain so: expert confirmation stands at 1 of 25 (gc-100 only); all 24 committed cases are
  `unreviewed`. Reports footnote these two metrics as provisional until ≥ half the roster is
  confirmed.

**The gap this leaves.** The corpus grew to 24 cases; the committed run record did not grow with it.
There is no full-roster live run on file, and the newest committed run is ~3 months old at a git sha
well behind `main`. A full-roster baseline at current `main` is the prerequisite for every
before/after claim in §4 — the engine pilot has nothing to diff against until it exists.

---

## 4. Impact: not measured yet, and here is exactly how it will be

**No engine has run against this pipeline.** The decisions memo says it plainly: "Nothing below is
built yet." So this section is a measurement design, not a result.

### The comparison, and why this one

"Engine rating vs LLM rating" is not a well-formed comparison — the engine abstains by design, so
the two are often answering different questions. **"Engine issues vs LLM issues, same input"** is
well-formed: both verifiers have the identical job, the identical input, and `gc-100` has a **known
correct answer** — 7 issues, being 2 `rating_mismatch`, 3 `pyramiding`, 2 `insufficient_evidence`.

### The scoreboard

| Metric | Definition | Why it matters |
|---|---|---|
| **Catches** | Issues the engine finds that are genuinely present | Countable against gc-100's known 7 |
| **Misses** | Known issues the engine fails to raise | Expected early, where the pack is thin |
| **False alarms** | Issues the engine raises that are not real | **The metric that decides everything.** A verifier that cries wolf is worse than no verifier |
| **New finds** | Real issues the engine raises that the LLM missed | The strongest possible result, and the entire argument for the integration |

### The acceptance bar (proposed, needs Sean)

Two gates, both judgment calls, **deliberately not percentages**:

1. **Zero unexplained false alarms on gc-100.** Every issue the engine raises outside the known 7
   must have a written explanation, and none of those explanations may be "the pack is wrong in a
   way we can't fix."
2. **The engine catches the pyramiding and insufficient-evidence issues** — the 5 of 7 that pack
   steps 1 and 2 are built to reach. Missing a `rating_mismatch` is acceptable in v0 if the DC is
   not yet encoded; missing a pyramiding issue is not, because that rule is supposed to be complete.

**Why not a percentage:** we author the pack, so any threshold is one we can hit by editing the pack
until it agrees — fitting to the answer key. With n=1 on real ground truth a percentage is
decoration. "Did it catch the 5 it was built to catch, without inventing others" is a real question
with a real answer.

### The binding constraint on the pilot

**The pilot must assert the private tier loaded and fail loudly at 24 cases.** The committed
classpath tier holds exactly `gc-001`…`gc-024`; `gc-100` loads solely under `GOLDEN_PRIVATE_ROOT`.
A silent 24-case run drops the one case the entire scoreboard depends on.

### Sequence to a real impact number

1. Owner runs the five one-time IAM/secret commands (runbook) — engine SA, token secret, accessor
   binding, registry, IAM audit.
2. Build and deploy the engine image at pinned SHA; prove `GET /healthz` reports `"auth": "token"`
   and one authenticated call from the API's identity succeeds.
3. **Establish a full-roster live baseline at current `main`** (§3's gap). Commit the run directory.
4. Build the pack generator and the scenario serializer; run the shadow pilot with the private tier
   asserted.
5. Commit the comparison report. That report — catches / misses / false alarms / new finds against
   gc-100's known 7 — is the first honest statement of impact this project will have.

Until step 5 lands, **this project's claim about the assurance engine is a hypothesis with an
approved design and a defined test, not a demonstrated improvement.** Say it that way externally.

---

## Where everything lives

| | |
|---|---|
| Engine tasking, clarifications, decisions D1–D6 | [`assurance-engine-integration.md`](assurance-engine-integration.md), [`assurance-engine-answers-2026-08-31.md`](assurance-engine-answers-2026-08-31.md), [`assurance-engine-decisions-memo.md`](assurance-engine-decisions-memo.md) |
| Sign-off, auth contract, Cloud Run readiness, owner runbook | [`assurance-engine-GO-2026-09-06.md`](assurance-engine-GO-2026-09-06.md), [`assurance-engine-auth-2026-09-01.md`](assurance-engine-auth-2026-09-01.md), [`assurance-engine-cloudrun-readiness-2026-09-06.md`](assurance-engine-cloudrun-readiness-2026-09-06.md), [`assurance-engine-owner-runbook-2026-09-07.md`](assurance-engine-owner-runbook-2026-09-07.md) |
| Eval harness design | [`inc8-eval-harness-spec.md`](inc8-eval-harness-spec.md) |
| Eval conventions, how to run, how to read a report | [`docs/qa/evals/README.md`](../qa/evals/README.md) |
| Expert-calibration log | [`docs/qa/evals/calibration.md`](../qa/evals/calibration.md) |
| Committed run records | `docs/qa/evals/runs/<ts>-<label>/` |
| Golden corpus (24 synthetic cases) | `spring-backend/src/test/resources/golden/` |
| Harness code | `spring-backend/src/test/java/com/afterduty/eval/` |
