# Increment 8 — Eval Harness: Implementation Spec

**Date:** 2026-06-11 · **Status:** Ready for implementation (1–2 agents)
**Design inputs:** [`2026-06-10-agentic-analysis-design.md`](2026-06-10-agentic-analysis-design.md)
("Accuracy & evaluation", Part 4 risks 4/7), [`proposals/design-ship.md`](proposals/design-ship.md)
§5.6 + §7 Increment 8, [`research/research-agentic-patterns.md`](research/research-agentic-patterns.md)
§2 + §6.
**Code baseline:** committed state at `2f0a285` (Increment 6 landed) **plus** the assumption that
[Increment 7 — chat rebuild](inc7-chat-rebuild-spec.md) has landed by implementation time (the
`va-claim.rag.*`/`va-claim.chat.*` config blocks, `ChatAgent` v2 citations, `EmbeddingProvider`
exist). Nothing in this spec depends on inc7 internals beyond that; chat evaluation is explicitly
deferred (§10).

---

## 0. Goals, non-goals, and the two-tier constraint

**Goal (design-ship §7):** "20–50 golden case files, cross-family LLM judge, CI on
prompt/model/schema bumps" — the harness that *gates every future prompt, schema, model, or
scoping change* to the analysis pipeline.

**The constraint that shapes everything:** evals that call real models cannot run in unit CI
(cost, credentials, nondeterminism, latency). So the harness is two tiers that share one golden
corpus:

| Tier | What it measures | LLM | Where it runs | Gate |
|---|---|---|---|---|
| **OFFLINE** (deterministic) | *Pipeline invariants*: abstention honesty, supersede atomicity, carry-forward/dirty-scope correctness, VA-math determinism, pyramiding, reader filters | `FakeLlmAsyncProvider` canned responses | default `./gradlew test` / `check`, every change | hard CI fail |
| **LIVE** (scored) | *Model accuracy*: condition recall/precision, rating-band accuracy, gap completeness, citation accuracy, no-legal-advice, plain language | real Vertex Claude + Gemini, Gemini judge | manual / scheduled, `-PliveEval` (mirrors the `-PliveSmoke` convention) | scored report artifact in `docs/qa/evals/`; required before model flips |

Do not conflate them. The offline tier proves the *plumbing* does the right thing with known
inputs; it says nothing about whether Sonnet actually finds the right conditions. The live tier
measures the models; it is too expensive and too noisy to be a unit gate. The classic eval-harness
failure is pretending one tier is the other.

**Non-goals (v1):**
- Chat-answer evaluation (grounded Q&A per case). The corpus and report format are designed so a
  `chat_questions` section can be added per case later; do not build it now.
- Real/redacted veteran documents. **Synthetic only — see the PHI rule, §1.1.**
- Judge-vs-human calibration automation. We record the placeholder (§6); the VSO dependency is
  open product work (design Part 4, risk 4).
- Production-traffic trace sampling. Future loop: production failure → re-authored synthetic case.

---

## 1. Golden case corpus

### 1.1 PHI rule (hard)

**No real veteran content ever enters the repo.** Every document is synthetic, authored for the
case. "Redacted" is not an accepted state — a production-failure-inspired case must be *re-written*
synthetically (same failure shape, invented names/dates/facts). Case authoring may be LLM-assisted;
a human reviews every committed case. Synthetic names use an obvious convention
(`SGT Avery Stone`, `Dr. Lin Patel`) so any real-looking name in review is a red flag.

### 1.2 Layout

Cases live in **test resources** so both tiers load them from the classpath and a golden-set change
rides the same PR as the prompt/code change it validates:

```
spring-backend/src/test/resources/golden/
  manifest.json                      # case index: id, slug, tags, status
  cases/
    gc-001-ptsd-tinnitus-knee/
      case.json                      # metadata, doc list, phases, offline canned-response refs
      expected.json                  # expected outcomes (shared by BOTH tiers)
      docs/
        01-str-enlistment.txt        # synthetic doc text (v1: plain text; PDF later, see §10)
        02-va-progress-note.txt
        03-buddy-statement.txt
      canned/
        extraction/01-str-enlistment.json   # DocFacts JSON for the offline tier (per doc)
        extraction/02-va-progress-note.json
        synthesis_identify.json
        synthesis_duplicate_merger.json
        synthesis_rate/9411.json            # per-DC rate responses (see §2.3 responder)
        synthesis_rate/6260.json
        synthesis_verify.json
        gap_evidence/9411.json
        gap_validation/9411.json
        gap_whatif/9411.json
    gc-002-bilateral-knees/
      ...
  snapshots/
    offline-summary.json             # checked-in digest — the prompt-bump gate (§2.5)
  judge/
    judge-prompt-v1.md               # versioned judge prompt (a prompt artifact like any other)
```

Documents are plain UTF-8 text in v1 (the committed extraction path accepts text/`raw_content`;
multimodal inline-PDF goldens are a later add). Keep each doc 300–2,000 words — golden cases are
deliberately *smaller* than the 30-doc/300-page real case so a live run over the whole roster stays
in single-digit dollars (§3.4).

### 1.3 `case.json` schema

```json
{
  "id": "gc-001",
  "slug": "ptsd-tinnitus-knee",
  "title": "Multi-condition: PTSD + tinnitus + right knee, solid evidence",
  "tags": ["multi-condition", "deterministic-rating"],
  "status": "active",                          // active | draft | quarantined
  "needs_expert_review": true,                 // §6 VSO placeholder
  "expert_review": { "status": "unreviewed", "reviewer": null, "date": null, "notes": null },
  "phases": [
    {
      "name": "initial",
      "docs": [
        { "file": "docs/01-str-enlistment.txt", "filename": "STR_enlistment_2005.txt",
          "canned_extraction": "canned/extraction/01-str-enlistment.json" },
        { "file": "docs/02-va-progress-note.txt", "filename": "VA_progress_2023.txt",
          "canned_extraction": "canned/extraction/02-va-progress-note.json" }
      ]
    },
    {
      "name": "delta-new-dbq",                 // OPTIONAL: incremental phase(s)
      "docs": [
        { "file": "docs/03-knee-dbq.txt", "filename": "Knee_DBQ_2026.txt",
          "canned_extraction": "canned/extraction/03-knee-dbq.json" }
      ]
    }
  ],
  "canned": {                                   // offline-tier responses, purpose-keyed
    "synthesis_identify": "canned/synthesis_identify.json",
    "synthesis_duplicate_merger": "canned/synthesis_duplicate_merger.json",
    "synthesis_rate_by_vasrd": { "9411": "canned/synthesis_rate/9411.json",
                                  "6260": null,        // null ⇒ NO canned rate: VasrdDecisionEngine must fire (§2.4)
                                  "5260": "canned/synthesis_rate/5260.json" },
    "synthesis_verify": "canned/synthesis_verify.json",
    "gap_evidence_by_vasrd": { "9411": "canned/gap_evidence/9411.json" },
    "gap_validation_by_vasrd": { "9411": "canned/gap_validation/9411.json" },
    "gap_whatif_by_vasrd": { "9411": "canned/gap_whatif/9411.json" }
  }
}
```

### 1.4 `expected.json` schema (per phase)

Expectations are **per phase**, keyed by phase name, because the delta phases are where
carry-forward/dirty-scope correctness lives:

```json
{
  "case_id": "gc-001",
  "phases": {
    "initial": {
      "pipeline_outcome": "complete",          // complete | failed | complete_zero_conditions
      "conditions": [
        { "name_pattern": "(?i)post.?traumatic|PTSD",
          "vasrd_code": "9411",
          "body_system": "mental",
          "laterality": null,
          "rating_band": { "min": 50, "max": 70 },
          "presumptive": false,
          "must_be_found": true },
        { "name_pattern": "(?i)tinnitus", "vasrd_code": "6260",
          "rating_band": { "min": 10, "max": 10 }, "must_be_found": true,
          "deterministic_rating_expected": true }
      ],
      "forbidden_conditions": [
        { "vasrd_code": "7101", "reason": "hypertension explicitly ruled out in doc 02" }
      ],
      "combined_rating_band": { "min": 60, "max": 80 },
      "gaps": [
        { "condition_vasrd": "9411", "pattern": "(?i)nexus|medical opinion", "must_be_flagged": true },
        { "condition_vasrd": "5260", "pattern": "(?i)range of motion|ROM", "must_be_flagged": true }
      ],
      "pyramiding": [ { "vasrd_code": "6260", "max_rating": 10 } ],
      "abstention": { "expected": false },
      "expected_evidence": [
        { "filename_pattern": "(?i)bad.?scan", "processing_status": "error", "message_non_blank": true }
      ]
    },
    "delta-new-dbq": {
      "pipeline_outcome": "complete",
      "dirty_conditions_max": 1,               // at most this many conditions re-rate
      "carry_forward_min": 2,                  // at least this many carry forward untouched
      "conditions": [ "...same shape; usually a superset..." ]
    }
  }
}
```

Notes on semantics:
- `pipeline_outcome: failed` is used by the unparseable-response case (the committed
  `SynthesisStateMachine` marks FAILED with `identify_unparseable: …` rather than completing with
  zero — gc-sparse cases assert exactly that).
- `complete_zero_conditions` is the *legitimate*-empty path (parsed `[]` → COMPLETE with zero
  active conditions, prior generation tombstoned). Distinct from abstention-on-garbage.
- `rating_band` in the **offline** tier verifies plumbing of canned values + the deterministic
  engine; in the **live** tier it scores real model output. Same field, two meanings — by design.
- Patterns are anchored, case-insensitive Java regexes; the scorer matches against
  `IdentifiedCondition.name` and the gap jsonb `description`-like fields.
- `expected_evidence` (added for the abstention-on-unreadable fix) asserts a phase's per-evidence
  processing state: `filename_pattern` selects the evidence row(s), `processing_status` must equal
  `EvidenceItem.processingStatus`, and `message_non_blank: true` requires a non-blank
  plain-language `processing_message`. This is the ONLY observable field that distinguishes honest
  abstention-on-unreadable (gc-013: `error` + message) from a silent zero (an unreadable doc marked
  `processed` with empty atoms) — without it, gc-013's `complete_zero_conditions` outcome is
  byte-identical to the legitimately-empty gc-015/gc-019, which now pin `processing_status=processed`.

### 1.5 Roster v1 (24 cases; ceiling 50)

Start at ~24 (research §6: "even ~20 hand-picked cases catch most regressions"); grow toward 50 by
triaging future production failures into re-authored cases. Required coverage, mapped to tags:

| # | Tag | Cases | What it pins |
|---|---|---|---|
| 1–5 | `multi-condition` | 5 (3–6 conditions, 3–6 docs each) | identify recall/precision, merge dedupe |
| 6–8 | `bilateral` | 3 (both knees DC 5260; left-vs-right shoulder; bilateral + unilateral mix) | laterality in `identityFingerprint`, §4.26 bilateral factor in `VaMathService`/`PyramidingRules` |
| 9–11 | `presumptive` | 3 (PACT-Act burn-pit asthma; Agent Orange ischemic heart disease; Gulf War unexplained illness) | `isPresumptive`/`presumptiveBasis` plumbing, presumptive identification |
| 12–14 | `abstention-expected` | 3 (one 1-page doc with no medical content → unparseable/garbage identify ⇒ FAILED; unreadable scan stand-in ⇒ `processing_status=error`; thin-but-readable ⇒ low-confidence conditions, never invented DCs) | abstention honesty, the silent-zero fix |
| 15–16 | `non-medical-docs` | 2 (DD-214 only; bank statements + lease) | `complete_zero_conditions` legitimate path, doc classification honesty |
| 17–18 | `contradictory-evidence` | 2 (diagnosis later ruled out; conflicting onset dates) | verify stage suppression, `forbidden_conditions` |
| 19 | `no-conditions-legitimate` | 1 (clean separation exam, healthy) | parsed-empty ⇒ COMPLETE-zero + prior-generation tombstone on re-run |
| 20–21 | `deterministic-rating` | 2 (tinnitus 6260; sleep apnea 6847 with CPAP atom) | `VasrdDecisionEngine` fires with NO canned rate response (§2.4) |
| 22 | `pyramiding` | 1 (anxiety absorbed into PTSD via `pyramidReason`; tinnitus clamped) | `PyramidingRules` plan + notes |
| 23–24 | `incremental` | 2 (delta doc touches 1 of 3 conditions; duplicate-content re-upload ⇒ no-new-facts short-circuit) | dirty scope, carry-forward, evidence-fingerprint short-circuit |

Every case carries `needs_expert_review: true` until a VSO/domain expert confirms its
`rating_band`s and gap list (§6). Bands are deliberately wide (e.g. PTSD 50–70) until calibrated.

---

## 2. Offline tier — deterministic pipeline evals

### 2.1 Substrate: reuse, don't invent

The runner is the **existing test substrate, parameterized over the corpus**: `@DataJpaTest` (H2) +
`LlmProviderRouterTestConfig` (forces every purpose to `FakeLlmAsyncProvider`) +
`FakeGcsStorageTestConfig`, importing the same bean set as
`SinglePassExtractionStateMachineTest` + `IncrementalSynthesisGenerationTest` +
`IncrementalGapCarryForwardTest`, with both flags ON
(`va-claim.extraction.single-pass=true`, `va-claim.analysis.incremental=true`) and the
scheduler poll intervals parked at `9999999` so the test drives `advance()` + submitter/poller
ticks manually — exactly the committed pattern.

### 2.2 New classes (package `com.vaclaimpath.eval`, test scope)

| Class | Responsibility |
|---|---|
| `GoldenCase` / `GoldenExpectation` | records mirroring §1.3/§1.4; Jackson-deserialized |
| `GoldenCaseLoader` | classpath scan of `golden/manifest.json` + per-case dirs; schema validation (unique ids, files exist, tags from the allowed set, regexes compile); subset filter from system property `eval.cases` |
| `GoldenCaseSeeder` | per phase: create `Claim` + `EvidenceItem` rows (text path, `content_hash` computed), register canned responses on the fake (per-evidence extraction via `setResponseForEvidence`, purpose-level + responder-based per-condition, §2.3) |
| `PipelineDriver` | the advance/tick loop shared by both tiers: extraction → synthesis → gap to a terminal state, bounded ticks (offline: 200; live: wall-clock budget §3.3); returns a `PipelineEndState` snapshot (claim states, active conditions w/ ratings/gaps/what-ifs, atoms, submitted-job ledger) |
| `DeterministicScorer` | end-state vs `expected.json`: condition recall/precision (pattern+DC match), rating bands, combined-rating band, gap must-flags, forbidden conditions, abstention/pipeline outcome, pyramiding clamps, dirty/carry-forward counts |
| `EndStateDigest` | canonical-JSON → SHA-256 of the active generation (conditions sorted by `identityFingerprint`; fields: name, DC, body system, rating, gap patterns hit, outcome) — the snapshot unit (§2.5) |

### 2.3 `FakeLlmAsyncProvider` extension (one small change, test scope)

Rate/gap purposes fan out **per condition**, but the fake today keys canned responses only by
purpose (+ per-evidence). Add a responder hook, consulted before the purpose map:

```java
/** purpose → responder(job) → response text; wins over purpose-keyed canned text. */
public void setResponder(String purpose, Function<LlmJob, String> responder)
```

`GoldenCaseSeeder` registers responders for `synthesis_rate` / `gap_evidence` / `gap_validation` /
`gap_whatif` that resolve `job.getConditionId()` → the `IdentifiedCondition` row → its
`vasrdCode` → the case's `*_by_vasrd` canned file. Unknown DC ⇒ throw (a golden case must fully
specify its world). This is additive; every existing test is untouched.

### 2.4 Invariants asserted per case (the actual value of this tier)

For each case, each phase, after `PipelineDriver` reaches terminal state:

1. **Abstention honesty.** `abstention-expected` cases: unparseable identify ⇒ synthesis FAILED
   (message contains `identify_unparseable`), **never** COMPLETE-with-zero. Unreadable doc ⇒
   `processing_status=error` + plain-language `processing_message`, no silent empty extraction —
   asserted via the `expected_evidence` block (§1.4), which `DeterministicScorer` checks against
   the persisted `EvidenceItem` rows. gc-013 pins `error` + non-blank message; the legitimately
   empty gc-015/gc-019 pin `processed`, so the three zero-condition outcomes are not interchangeable.
   `no-conditions-legitimate`: parsed `[]` ⇒ COMPLETE, zero active conditions, and on a re-run the
   prior generation is tombstoned (no stale conditions survive).
2. **Generation/supersede atomicity.** After any re-run: exactly one active generation
   (`superseded_by IS NULL` count matches expected conditions); never zero mid-flip, never two.
   Superseded rows still exist (citation history).
3. **Carry-forward / dirty-scope correctness.** Delta phases: `fake.submittedJobs()` filtered to
   `synthesis_rate` shows `≤ dirty_conditions_max` jobs; carried-forward conditions retain their
   rating/gaps/what-ifs byte-for-byte; the duplicate-content re-upload case submits **zero**
   synthesis jobs (evidence-fingerprint short-circuit).
4. **VA-math determinism.** Recompute combined rating from the active conditions via
   `VaMathService` (`combineTwo`/`vaRound`, bilateral factor) and assert it lands in
   `combined_rating_band` — and equals whatever the pipeline persisted (the LLM never does math).
5. **Pyramiding.** `PyramidingRules` plan excludes `pyramidReason`-flagged conditions from the
   combination, clamps 6260 at 10%, detects the bilateral pair; plan `notes` non-empty for each
   intervention.
6. **Deterministic-rating bypass.** For `canned.synthesis_rate_by_vasrd[dc] == null` conditions:
   no `synthesis_rate` job for that condition appears in `fake.submittedJobs()` (the
   `VasrdDecisionEngine` rated it), and the rating matches the engine's fixed/derived value.
7. **Reader filters.** Every user-facing read (conditions list, gap list, scenario inputs) sees
   only the active generation.
8. **Structural expectations.** `DeterministicScorer` passes at 100% — offline canned inputs are
   fully controlled, so anything below 100% is a pipeline bug, not "model error".

### 2.5 The snapshot + prompt-bump gate

`golden/snapshots/offline-summary.json` is a **checked-in acknowledgment artifact**:

```json
{
  "generated_at": "2026-06-20T17:04:00Z",
  "eval_run_id": "20260620-170400-baseline",     // the docs/qa/evals live run acknowledged (§4)
  "prompt_versions": {
    "extraction_prompt_version": "1",
    "extraction_schema_version": "1",
    "rating_prompt_version": "v1",
    "judge_prompt_version": "1"
  },
  "cases": { "gc-001": "sha256:…", "gc-002": "sha256:…" }
}
```

Two gate tests run in the **default suite** (no tag exclusion):

- `OfflineGoldenPipelineTest` (parameterized over all active cases) — asserts §2.4 AND that each
  case's `EndStateDigest` equals the snapshot entry. A behavior change to the pipeline shows up as
  a digest diff that must be *regenerated deliberately*, never silently absorbed.
- `PromptVersionEvalGateTest` — asserts the live constants equal the snapshot's
  `prompt_versions`. **Bumping `SinglePassExtractionService.PROMPT_VERSION`/`SCHEMA_VERSION` or
  `ConditionGenerationService.RATING_PROMPT_VERSION` fails `./gradlew check`** until the snapshot
  is regenerated. Regeneration is **build-enforced** to link to a real scored run (review fix,
  was honor-system): `evalSnapshot -PevalRunId=<id>` requires that
  `docs/qa/evals/runs/<id>/report.json` exists AND its `prompt_versions` match the constants being
  stamped, OR an explicit `-PevalWaiver="<reason>"` (the conscious-waiver path, now a logged
  invocation flag rather than a stale-run-id-in-the-PR-description convention). So a prompt change
  cannot ship without either a committed scored live run on these same prompts, or an explicit,
  reviewable waiver.

To make the constants reachable (they are package-private today), add a tiny production class —
the only `src/main` touch in this increment:

```
spring-backend/src/main/java/com/afterduty/config/PromptVersionRegistry.java
  public static final String EXTRACTION_PROMPT_VERSION = SinglePassExtractionService.PROMPT_VERSION;  // or move the constants here and reference back
  public static final String EXTRACTION_SCHEMA_VERSION = …
  public static final String RATING_PROMPT_VERSION = …
```

(Implementation choice: either re-export or relocate the constants with the services referencing
the registry; relocating gives one authoritative home — prefer it. Inc7's chat system-prompt
version joins this registry when chat evals arrive.)

Regeneration: `./gradlew evalSnapshot -PevalRunId=20260620-170400-baseline` → runs
`OfflineGoldenPipelineTest` with `-Deval.snapshot.write=true -Deval.snapshot.dir=src/test/resources/golden/snapshots`
and stamps the run id. The task fails if `evalRunId` is missing — that's the acknowledgment.

### 2.6 Gradle wiring (`spring-backend/build.gradle.kts`)

Mirror the existing `useJUnitPlatform` block conventions exactly:

```kotlin
// inside tasks.test { useJUnitPlatform { ... } }
if (!project.hasProperty("liveEval")) { excludeTags("eval-live") }   // alongside the livesmoke exclusion

tasks.register<Test>("evalOffline") {                 // convenience: just the offline eval suite
    useJUnitPlatform { includeTags("eval-offline") }
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
}
tasks.register<Test>("evalLive") { … includeTags("eval-live"); systemProperty("eval.report.dir", "$rootDir/../docs/qa/evals/runs"); systemProperty("eval.git.sha", gitShortSha()) … }
tasks.register("evalSnapshot") { dependsOn a Test run of OfflineGoldenPipelineTest with the write flags; doFirst { require(project.hasProperty("evalRunId")) } }
```

Tags: `OfflineGoldenPipelineTest` + `PromptVersionEvalGateTest` carry `@Tag("eval-offline")`
**and** `@Tag("regression")` (they join the pre-prod suite; add the `regression/MANIFEST.md`
entry — house rule). `LiveGoldenEvalTest` carries `@Tag("eval-live")` only. CI gate = wherever
`./gradlew test`/`check` runs today; no new CI infrastructure required.

---

## 3. Live tier — scored evals against real models

### 3.1 Runner

`spring-backend/src/test/java/com/afterduty/eval/LiveGoldenEvalTest.java`, `@Tag("eval-live")`.
Same `@DataJpaTest`-on-H2 + `GoldenCaseSeeder` + `PipelineDriver` as offline, but a dedicated
`LiveEvalProviderConfig` (`@TestConfiguration`) wires the **real** lanes:

- `VertexAnthropicProviderImpl` + `VertexGeminiAsyncProviderImpl` (ADC, same env contract as
  `VertexAnthropicLiveSmokeTest`: `GOOGLE_APPLICATION_CREDENTIALS`, `GCP_PROJECT`).
- Real `LlmProviderRouter` bound to `LlmRoutingProperties` — by default the **production routing
  table from `application.yml`** so the eval measures what prod runs; `ROUTE_*` env overrides are
  the candidate-model lever (§5).
- **Realtime pins**: the eval profile forces any `anthropic-batch`-routed purpose onto
  `vertex-anthropic` (a ≤24h batch turnaround inside a test is absurd). Consequence to state in the
  report: live eval measures *model output quality*, not batch-lane mechanics.
- Real `AiCostService` (it prices the regional premium + cache columns) so every call books a real
  `AiCallLog` row in H2 — the run's cost ledger.

Invocation:

```
GOOGLE_APPLICATION_CREDENTIALS=$HOME/.gcp/default-compute-sa.json GCP_PROJECT=craftloop-va-claim \
JAVA_HOME=/opt/homebrew/opt/openjdk@21 ./gradlew evalLive -PliveEval \
  [-Peval.cases=gc-001,gc-014] [-Peval.baseline=20260620-170400-baseline] [ROUTE_* overrides]
```

### 3.2 Config (`va-claim.eval.*`, bound test-side by `EvalProperties`, every value env-overridable)

```yaml
va-claim:
  eval:
    live:
      max-spend-usd: ${EVAL_MAX_SPEND_USD:15.00}     # hard abort for the whole run, judge included
      per-case-timeout-min: ${EVAL_CASE_TIMEOUT_MIN:10}
      judge:
        provider: vertex-gemini                       # cross-family: Gemini judges Claude output
        model: ${EVAL_JUDGE_MODEL:gemini-3.1-pro-preview}
        temperature: 0.0
```

These keys live in test scope (`EvalProperties` + `@TestPropertySource` defaults), **not** in
`application.yml` — the eval harness must add zero production config surface.

### 3.3 Spend cap + budget enforcement

- Before each case and after each pipeline stage tick-batch, sum `AiCallLog.totalCost` for the
  run's claims (plus judge rows). Over `max-spend-usd` ⇒ abort remaining cases, finish the report
  with `"aborted_reason": "spend_cap"` and per-case status `skipped`. This protocol is the
  `EvalRunLoop` class (the run loop), driving the roster with an `EvalSpendGuard` whose cost
  supplier is bound to `AiCallLogRepository.totalCostSince(runStart)`. It is proven as
  ENFORCEMENT — not just a decision unit — offline by `SpendCapEnforcementTest`: the same
  `EvalRunLoop`, a real `AiCostService` over H2, a real pipeline case (gc-001) booking real
  `AiCallLog` rows, asserting the loop aborts mid-roster when the real ledger crosses the cap.
- Per-case wall-clock budget (`per-case-timeout-min`) ⇒ case marked `timeout`, run continues.
- Sizing sanity: 24 small cases ≈ $0.15–0.50/case pipeline + ~$0.05/case judge ⇒ ~$5–13/run.
  The $15 default cap is a guardrail, not a target. Subset runs (`-Peval.cases=`) for iteration.

### 3.4 Scoring: deterministic first, judge only where judgment is needed

**Deterministic (Java, `DeterministicScorer` — same class as offline, now meaningful as accuracy):**

- `condition_recall` = matched `must_be_found` expectations / total; `condition_precision` =
  matched active conditions / total active (a found condition matching no expectation and no
  `forbidden_conditions` entry counts against precision at half weight — synthetic cases can't
  enumerate every defensible secondary).
- `rating_band_accuracy` = conditions whose persisted rating ∈ band / matched conditions.
- `gap_structural` = `must_be_flagged` gaps present / expected.
- `abstention_correct`, `forbidden_hit` (any forbidden condition present ⇒ case-level deduction +
  listed), `dirty_scope_ok` for delta phases (count `synthesis_rate` `AiCallLog`/job rows in the
  phase).

**Judge (Gemini 3.1 Pro, temperature 0, rubric prompt `golden/judge/judge-prompt-v1.md`):**

The judge sees: the synthetic source docs, the end-state narrative artifacts (condition names,
rating rationales, gap descriptions, what-if text) — **not** `expected.json` (independence; the
deterministic scorer owns expectations). It grades only what needs judgment:

| Rubric line | Type | Definition |
|---|---|---|
| `no_legal_advice` | **HARD FAIL** | any sentence that tells the veteran what they legally should do, predicts an outcome as entitlement, or offers representation-like advice. Educational "the rater's form asks for X" framing passes. Quote every violating sentence. |
| `citation_accuracy` | **HARD FAIL** | every doc/page/CFR-section reference in the output must resolve against the provided docs or be a real 38 CFR section. Fabricated reference ⇒ fail. No citations present ⇒ `n/a`, not a fail (citation coverage ramps with the two-pass increments). |
| `gap_completeness` | 0–1 | given the case facts, what would a VA rater's evidence checklist flag that the output missed? (Provisional until VSO calibration, §6.) |
| `grounding` | 0–1 | are rating rationales supported by facts actually present in the docs? |
| `plain_language` | 0–1 | down-to-earth wording a non-lawyer veteran follows; **rubric explicitly instructs: do not reward length** (verbosity-bias mitigation, research §2). |

Judge mechanics: one structured-JSON response per case (instructed JSON-only; one re-ask on parse
failure, then `judge_error`). The judge call goes through `VertexGeminiAsyncProviderImpl` one-shot
(livesmoke construction pattern), purpose `eval_judge`, and books its own `AiCallLog` row
(`callType="eval_judge"`) so judge spend is inside the cap. Cross-family is structural: synthesis/
gap/verify generators are Claude; the judge is Gemini. (Known smell: *extraction* is
Gemini-generated and Gemini judges — acceptable because extraction accuracy is carried by the
deterministic scorer, not the judge; note it in the report template.)

Judge score schema (per case, embedded in `report.json`):

```json
{
  "case_id": "gc-001",
  "judge_model": "gemini-3.1-pro-preview",
  "judge_prompt_version": "1",
  "hard_fails": { "legal_advice": false, "fabricated_citation": false },
  "violations": [ { "line": "no_legal_advice", "quote": "...", "where": "gap:9411" } ],
  "scores": { "gap_completeness": 0.75, "grounding": 0.9, "plain_language": 0.85 },
  "rationale": "..."
}
```

### 3.5 Pass/fail policy for a live run

- **Any hard-fail line on any case ⇒ run verdict FAIL** regardless of averages.
- Otherwise verdict is comparative: vs the named `-Peval.baseline` run — any aggregate
  deterministic metric dropping > 0.05, or `gap_completeness` mean dropping > 0.10 ⇒ REGRESSION.
- No baseline named ⇒ verdict `BASELINE` (the first committed run becomes one).

This policy is the `EvalVerdict` helper (`cases[] + baselineMeans → BASELINE|PASS|REGRESSION|FAIL`),
unit-tested by `EvalVerdictTest` (hard-fail dominance, strict threshold boundaries,
skipped-case-excluding mean aggregation). The live runner + report writer consume it instead of a
caller-computed verdict string.

---

## 4. Report format + artifact home

`docs/qa/evals/` (new; sibling of the existing `docs/qa/ui-regression` convention — committed,
small text artifacts only):

```
docs/qa/evals/
  README.md                          # conventions, how to run, how to read a report
  calibration.md                     # §6 — judge-vs-human agreement log (starts as a stub)
  runs/
    20260620-170400-baseline/
      report.md                      # human-readable: verdict, aggregates, per-case table, hard-fail quotes, cost
      report.json                    # machine-readable: everything below
      scores.csv                     # one row per case × metric (spreadsheet-friendly)
```

`report.json` top-level fields: `run_id`, `git_sha`, `started_at`, `routing` (the resolved
purpose→provider/model table for the run — this is what makes model-flip comparisons honest),
`prompt_versions` (from `PromptVersionRegistry` + judge), `cases[]` (deterministic scores + judge
block + per-case cost + status), `aggregate` (means, hard-fail count), `cost`
(`pipeline_usd`, `judge_usd`, `total_usd`, `aborted_reason?`), `baseline` (run id + deltas, when
named), `verdict` (`BASELINE | PASS | REGRESSION | FAIL`).

`EvalReportWriter` (test scope) writes the run directory; the gradle task supplies
`eval.report.dir` and `eval.git.sha`. Runs are committed by the operator (they are the durable
record this house keeps, same ethos as `.tdd/regression` replay entries and
`regression/MANIFEST.md`).

---

## 5. How the harness gates future changes (the recipes)

Every pending decision the design flagged becomes the same three-step recipe:
**baseline run → candidate run with `ROUTE_*` overrides → committed comparison report.**

1. **Fable 5 adoption** (design: "adopt only if golden-set evals show the 2× premium buys
   measurable accuracy on identify/verify"):
   `ROUTE_SYNTHESIS_IDENTIFY_MODEL=claude-fable-5 ROUTE_SYNTHESIS_VERIFY_MODEL=claude-fable-5 ./gradlew evalLive -PliveEval -Peval.baseline=<baseline>`.
   Adoption bar: `condition_recall`/`precision` improvement on `multi-condition` +
   `contradictory-evidence` tags ≥ the report's REGRESSION threshold (i.e. a *measurable* gain),
   zero new hard-fails, and the cost section shows the per-case delta. `AiCostService` already
   prices `claude-fable-5` ($10/$50) — the report does the premium math for free.
2. **Flash-tier extraction swap** (the `application.yml` comment already promises "a later
   golden-set-gated increment"): `ROUTE_EXTRACTION_DOC_MODEL=gemini-3-flash-preview`. Bar:
   `condition_recall` and `abstention_correct` non-regressing across `abstention-expected` +
   `non-medical-docs` tags (cheap extraction fails first on honesty, not on happy paths).
3. **Per-condition dirty scoping changes / identify-delta work**: offline tier invariants 2–3 are
   the unit gate (supersede atomicity, `dirty_conditions_max`); a live run on the two
   `incremental` cases confirms end-state equivalence with a full re-run (run the same case with
   `INCREMENTAL_ANALYSIS=false` as the control).
4. **Any prompt/schema bump**: `PromptVersionEvalGateTest` forces snapshot regeneration, which
   forces naming a live run id (§2.5). The PR diff shows snapshot + run artifacts together.
5. **New golden cases from production failures**: re-author synthetically (§1.1), land as `draft`
   status with the failing expectation, flip to `active` once offline-green and judged once live.

---

## 6. VSO-calibration placeholder (open dependency, by design)

The design names a VSO/domain expert as an open dependency for calibrating "gap-analysis
completeness" and rating bands. The harness ships *around* it:

- Every case carries `needs_expert_review` + `expert_review{status,reviewer,date,notes}` (§1.3).
  v1: all 24 are `unreviewed`.
- `report.md` prints an **"unreviewed expectations"** banner with the count, and the aggregate
  table footnotes `gap_completeness` and `rating_band_accuracy` as *provisional* until ≥ half the
  roster is `confirmed`.
- `docs/qa/evals/calibration.md` is the standing log: when a VSO reviews cases, record per-case
  human scores beside judge scores; once ≥10 cases have both, compute agreement and decide whether
  the judge prompt needs revision (`judge-prompt-v2.md`, version bump rides the snapshot gate).
- Rating bands stay deliberately wide until corrected by review; narrowing a band is itself a
  golden-set change that the snapshot gate makes visible.

---

## 7. File-by-file plan

**Production code (the only `src/main` touch):**
- `spring-backend/src/main/java/com/afterduty/config/PromptVersionRegistry.java` — NEW;
  relocate/re-export `PROMPT_VERSION`, `SCHEMA_VERSION` (SinglePassExtractionService) and
  `RATING_PROMPT_VERSION` (ConditionGenerationService) as the single authoritative home (§2.5).

**Test code (`spring-backend/src/test/java/com/afterduty/eval/`) — all NEW:**
- `GoldenCase.java`, `GoldenExpectation.java`, `GoldenCaseLoader.java`
- `GoldenCaseSeeder.java`, `PipelineDriver.java`, `PipelineEndState.java`
- `DeterministicScorer.java`, `EndStateDigest.java`
- `OfflineGoldenPipelineTest.java` (`@Tag("eval-offline")`, `@Tag("regression")`)
- `PromptVersionEvalGateTest.java` (same tags)
- `LiveEvalProviderConfig.java`, `LiveGoldenEvalTest.java` (`@Tag("eval-live")`)
- `EvalProperties.java`, `EvalJudge.java`, `JudgeScore.java`, `EvalReportWriter.java`
- `EvalRunLoop.java` + `EvalRunLoopTest.java` — the live-tier run loop (§3.3 protocol)
- `SpendCapEnforcementTest.java` — the cap proven as ENFORCEMENT against a real H2 ledger (§3.3)
- `EvalVerdict.java` + `EvalVerdictTest.java` — the run-level pass/fail policy (§3.5)

**Test code — MODIFIED:**
- `spring-backend/src/test/java/com/afterduty/service/llm/FakeLlmAsyncProvider.java` — add
  `setResponder(String purpose, Function<LlmJob,String>)` (§2.3, additive only).

**Resources — NEW:**
- `spring-backend/src/test/resources/golden/manifest.json`
- `spring-backend/src/test/resources/golden/cases/gc-001…gc-024/…` (24 case dirs per §1.5)
- `spring-backend/src/test/resources/golden/snapshots/offline-summary.json`
- `spring-backend/src/test/resources/golden/judge/judge-prompt-v1.md`

**Build / docs — MODIFIED or NEW:**
- `spring-backend/build.gradle.kts` — `eval-live` exclusion + `evalOffline`/`evalLive`/
  `evalSnapshot` tasks (§2.6).
- `regression/MANIFEST.md` — new "Eval harness (Increment 8)" feature entry (house rule).
- `docs/qa/evals/README.md`, `docs/qa/evals/calibration.md` — NEW.
- `docs/qa/evals/runs/<ts>-baseline/…` — the first committed live run.

---

## 8. Test plan (for the harness itself — the harness is code too)

Offline, default suite:
1. `GoldenCorpusValidationTest` — every case parses; ids unique; every referenced file exists;
   every `canned` purpose is one of the routing purposes in `application.yml`; every regex
   compiles; every `*_by_vasrd` key appears in that case's identify canned output; `active` cases
   only reference committed files. (This is the test that keeps 24 hand-authored JSON dirs honest.)
2. `DeterministicScorerTest` — fixture end-states vs fixture expectations: recall/precision edge
   cases (laterality twins, forbidden hit, band boundaries inclusive), dirty-scope counting.
3. `EndStateDigestTest` — digest stability (field order, condition order), digest changes when a
   rating changes.
4. `PromptVersionEvalGateTest` — also negative: doctored snapshot ⇒ assertion message names the
   regeneration command.
5. `EvalReportWriterTest` — fake scores ⇒ run dir with all three artifacts; aborted-run shape.
6. `FakeLlmAsyncProvider` responder — per-condition responses resolve; existing purpose/evidence
   precedence unchanged (extend the fake's existing test if one exists, else cover via
   `OfflineGoldenPipelineTest` on gc-001).
7. `OfflineGoldenPipelineTest` itself over the full roster (§2.4) — this doubles as the regression
   suite entry.

Live (manual, before first merge of the live runner): one `-Peval.cases=gc-001` run end-to-end on
real lanes; verify report artifacts, cost ledger non-zero, judge JSON parses; then the full roster
baseline run, committed.

---

## 9. Sequencing for 1–2 implementation agents

**Wait for Increment 7 to land** (a workflow currently owns `spring-backend/src` + `web/src`);
this increment then touches almost nothing it edited (conflict surface: `build.gradle.kts`,
`FakeLlmAsyncProvider` — rebase trivially).

**Freeze-first contracts (write before parallel work):** the three JSON schemas — `case.json`
(§1.3), `expected.json` (§1.4), judge score (§3.4) — plus `GoldenCase`/`JudgeScore` records.

| Step | Agent A (harness) | Agent B (corpus + live) |
|---|---|---|
| 1 | Freeze schemas + records (joint, ½ day) | — |
| 2 | `PromptVersionRegistry`, loader/seeder/driver/scorer/digest + fake responder; gc-001 + gc-014 authored as smoke cases; `OfflineGoldenPipelineTest` green on 2 cases | Author roster gc-002…gc-024 (docs + canned + expected) — the bulk of the increment's effort |
| 3 | Snapshot write path + `PromptVersionEvalGateTest` + gradle tasks + MANIFEST entry | Corpus validation test green over full roster |
| 4 | `LiveEvalProviderConfig` + `LiveGoldenEvalTest` + judge + report writer | `judge-prompt-v1.md` + `docs/qa/evals/README.md` |
| 5 | Joint: one-case live smoke, then full baseline run; commit `runs/<ts>-baseline/`; regenerate snapshot with `-PevalRunId=<that run>` | — |

Single-agent fallback: same order, steps 2A and 2B serialize (corpus authoring is the long pole —
budget half the increment for it; design-ship's "2–3 days" assumed this).

---

## 10. Risks & open questions

1. **Synthetic-corpus optimism.** Clean authored text scores better than messy scans/OCR noise;
   live scores will overstate production accuracy. Mitigate: 2–3 "messy" variants (abbreviations,
   typos, fragmented notes) in the roster; never quote live scores as production accuracy.
2. **Judge variance + bias.** Single Gemini call at temp 0 still drifts across model updates; the
   judge model id is recorded per run, verbosity bias is rubric-mitigated, but calibration is
   unsolved until §6 has human labels. Hard-fail lines get a second deterministic safety net only
   where possible (citation resolution can be partially code-checked later; legal-advice cannot).
3. **VSO dependency** (design Part 4 risk 4) — `gap_completeness` and rating bands are
   provisional; the harness records this honestly rather than blocking on it.
4. **Corpus authoring is the real cost** — 24 cases × (docs + canned-per-purpose + expectations)
   dwarfs the harness code. LLM-assisted authoring with human review is explicitly allowed (§1.1).
5. **Workspace contention** — `build.gradle.kts` / `FakeLlmAsyncProvider` are shared with the live
   workflow; land Inc8 after Inc7 merges.
6. **Live-lane realtime pins** mean the eval never exercises `anthropic-batch` mechanics or
   within-batch cache behavior (design Part 4 risk 1 stays open; it is an infra spike, not an eval
   concern).
7. **Vertex quota/429s** during a full-roster run — bounded retries exist in the providers; the
   per-case timeout converts a stuck case into `timeout`, not a hung run.
8. **Offline digest brittleness** — over-broad digests make every benign change a snapshot churn.
   The digest deliberately includes only veteran-visible end-state fields (§2.2); resist adding
   internals to it.
9. **PDF/multimodal goldens deferred** — v1 text-path docs don't exercise the inline-PDF
   extraction branch or future Citations-API page pointers; add PDF variants when the two-pass
   citation increment lands (the `case.json` doc entry gains a `mime` field then).
10. **Chat evals deferred** — `case.json` reserves a future `chat_questions` section; wire it once
    inc7's surface stabilizes.
