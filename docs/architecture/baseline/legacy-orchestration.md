# Legacy Spring Analysis Pipeline — Orchestration & Scheduling Map

Baseline engineering map of the analysis pipeline as it exists TODAY on `feat/next-web-foundation`
(spring-backend). All paths are relative to `spring-backend/src/main/java/com/afterduty/` unless
noted. This is the reference document for the Vertex-AI Claude-agent redesign.

---

## 1. STAGES — ordered control flow

### 1.1 Entry points

| Trigger | Code | Behavior |
|---|---|---|
| Upload (multipart) | `controller/IntakeController.java:154` | saves `EvidenceItem` (base64 in `raw_content`), sets claim `EXTRACTING`, calls `pipelineService.processEvidence()` async |
| Upload (JSON legacy) | `IntakeController.java:199` | same |
| Quick-add text | `IntakeController.java:240` | same (`source_type=quick_add`), requires active subscription unless VSO view-as |
| Reprocess one doc | `IntakeController.java:476-507` | deletes that doc's atoms (`:486`), resets status, re-enqueues |
| `/analyze` (admin-only force run) | `IntakeController.java:421-472` | raw `new Thread()` → legacy synchronous `PipelineService.runFullPipeline` (`:459`) |
| Monthly usage reset | `job/UsageResetJob.java:40` | re-enqueues evidence deferred by the usage cap via `processEvidenceForReset` |
| Debug endpoints | `IntakeController.java:511-584`, `controller/EventPipelineController.java` (segment/extract/aggregate per claim), `controller/StrategyDebugController.java` | synchronous one-off runs |

`PipelineService.processEvidence` (`service/PipelineService.java:60-123`) does **no AI work itself**
anymore: it checks the usage cap (`:72`), marks evidence `processed/"Queued for async extraction"`
(`:97-101`), sets `claim.synthesisNeeded=true`, `lastEvidenceAt=now`, and seeds
`claim.extractionState="NONE"` if null (`:106-108`). Everything downstream is driven by polling.

### 1.2 The scheduler loop

`service/AnalysisScheduler.java` — `@Scheduled` every **15 s** (`va-claim.pipeline.poll-ms`,
default 15000, initial delay 20 s; `:120-121`). Each tick:

1. `claimRepository.findAll()` (`:125`) — iterates **every claim in the database**.
2. Per claim (`advanceClaim`, `:140-160`), gated by `hasActiveSubscription` (`:164-169`):
   - if `extractionState != null && != "COMPLETE"` → `extractionStateMachine.advance(claim)` (does not block synthesis/gap in the same tick),
   - if `shouldRunSynthesis` (`:173-194`) → `synthesisStateMachine.advance(claim)`,
   - if `shouldRunGapAnalysis` (`:198-214`) → `gapStateMachine.advance(claim)`.

**Synthesis eligibility** (`:173-194`): in-flight state always ticks; otherwise needs ≥1 atom,
**quiet period** of 30 s since the latest atom (`va-claim.pipeline.quiet-seconds`, `:84-85`), no
evidence in `pending/processing`, then triggers on: never synthesized, **configured Claude model
changed** (`:192`, see weakness W1), or atom newer than `lastSynthesisAt`.

**Gap eligibility** (`:198-214`): in-flight ticks; otherwise requires synthesis done and triggers
on: never ran, model changed (`:207`), or synthesis newer than last gap run. (Condition hand-edits
cannot retrigger — `IdentifiedCondition` has no `updatedAt`, `:211-213`.)

Completion callbacks `markSynthesisComplete`/`markGapAnalysisComplete` (`:218-238`) null the state
column and stamp `lastSynthesisAt/Model`, `lastGapAnalysisAt/Model`.

### 1.3 The three hand-rolled state machines

State lives in three string columns on `Claim` (`model/Claim.java:80-88`:
`extractionState`, `synthesisState`, `gapState`). Each `advance()` is `@Transactional` and moves
**at most one transition per tick**. Per-stage LLM jobs are tracked by join rows
`model/ClaimPipelineJob.java` (claim_id, stage, llm_job_id, condition_id, evidence_id); a stage
advances only when **every** job in it is SUCCEEDED (`llmJobService.allSucceeded`).

**Extraction** (`service/extraction/ExtractionStateMachine.java:41-50`):

```
NONE → EXTRACTING_DIAGNOSIS → EXTRACTING_MEDICATIONS → EXTRACTING_SERVICE_RECORDS
     → EXTRACTING_ATOMS → EXTRACTING_SEGMENTS → EXTRACTING_EVENTS → null (=COMPLETE)
```

- Each stage fans out **one job per EvidenceItem of the whole claim** (`startStage`, `:120` —
  `findByClaimId`, *not* only unprocessed docs), except EXTRACTING_EVENTS which fans out one job
  per **unextracted `MedicalEvent`** (`:153-190`).
- Stage results are parsed on the transition tick (`parseStageResults`, `:233-291`): diagnosis /
  medication / service-record / atom stages save `Atom` rows; SEGMENTS saves `MedicalEvent` rows
  (`EventSegmentationAgent.parseResponse`, `extraction/EventSegmentationAgent.java:108-118`);
  EVENTS saves atoms per event and flips `event.extracted=true`
  (`extraction/EventExtractionAgent.java:125-136`).
- `parseState(null)` disambiguates "never ran" vs "completed" by counting prior extraction
  pipeline jobs (`:367-395`).
- Event-stage job↔event mapping uses `evidenceId` as a proxy because `ClaimPipelineJob` has no
  eventId field (`:182-183`, `:274-287`) — which result lands on which event is order-dependent
  when one document yields multiple events.

**Synthesis** (`service/synthesis/SynthesisStateMachine.java:35`):

```
NONE → IDENTIFYING → MERGING → RATING → VERIFYING → COMPLETE
```

- IDENTIFYING: 1 job over **all atoms** + service & presumptive context
  (`doIdentify`, `:98-114`; context built by `EnhancedSynthesisOrchestrator`).
- MERGING: 1 job — LLM dedupes the identified-condition list (`DuplicateConditionMerger`); empty
  identify result fast-paths to COMPLETE with model `"unknown"` (`:135-140`).
- RATING: persists merged conditions as **new** `IdentifiedCondition` rows (`:177-181`, no
  deletion/supersede of prior rows), then fans out **1 rating job per condition**, each fed all
  atoms (`:184-192`).
- VERIFYING: writes ratings back (`:212-227`), then 1 verification job over all conditions
  (`:247-250`).
- COMPLETE: applies verification corrections + pyramid flags (`:275-295`), stamps
  `lastSynthesisModel` from the **verify job's** model name (`:298-303`).

**Gap analysis** (`service/gap/GapStateMachine.java:33`):

```
NONE → EVIDENCE_GAPS → VALIDATING → WHATIF → COMPLETE
```

- EVIDENCE_GAPS: 1 job per condition (all atoms in context), `doFanoutEvidence` (`:84-120`);
  no conditions → fast-path COMPLETE.
- VALIDATING: persists gaps onto each condition (`cond.setGaps`, `:140-147`), then 1 validation
  job per condition-with-gaps (`:150-169`); none → fast-path COMPLETE.
- WHATIF: persists validated gaps (`:203-214`), 1 what-if job per condition-with-gaps
  (`:216-237`).
- COMPLETE: persists `whatIfScenarios` per condition (`:263-271`), stamps `lastGapAnalysisModel`
  from a what-if job (`:273-278`).

### 1.4 The async LLM job substrate

- `service/llm/LlmJobService.java` — `submit()` persists a QUEUED `LlmJob` row (full request
  JSON in `request_payload`) and returns a UUID (`:52-81`); `getResult()` returns empty while
  in-flight, throws `LlmJobFailedException` on FAILED (`:95-103`).
- `service/llm/LlmJobSubmitter.java` — `@Scheduled` every **3 s** (`:40-41`), takes up to **50**
  QUEUED jobs (`batch-size`, `:32-33`) with `SELECT … FOR UPDATE SKIP LOCKED` (multi-instance
  safe, `:21-23`), groups by `(provider, batchGroupKey)` and submits one provider call per group.
  Submit-failure retry: attempts incremented; **FAILED after 3 attempts** (`:79-88`).
- `service/llm/LlmJobPoller.java` — `@Scheduled` every **15 s** (`:47-48`): polls each active
  providerJobId, fetches results when terminal, persists `response_payload`, writes `AiCallLog`
  cost rows (`recordCost`, `:127-147`). **Orphan recovery**: SUBMITTED rows older than **30 min**
  (`va-claim.llm.orphan-deadline-min`, `:38-39`) are reset to QUEUED (`:113-125`).
- Retry semantics: only *submission* and *orphaned* jobs retry. A job the provider reports as
  FAILED is terminal — and a stage with one FAILED job **never** satisfies `allSucceeded`, so the
  claim wedges in that stage forever (see W5).

### 1.5 Legacy synchronous path (still live, admin-only)

`PipelineService.runFullPipeline` (`PipelineService.java:159-208`): single `@Transactional` that
calls `ClaudeSynthesisService.runSynthesis` + `ConditionPostProcessService.postProcess` +
`runGapAnalysis` synchronously, with progress % writes (`updateClaimProgress`). Despite its name,
`ClaudeSynthesisService` calls **Gemini via Vertex REST** (`ClaudeSynthesisService.java:42-56`,
`callGemini`, `:307`). The deliberate single entry-time usage check is documented at
`PipelineService.java:180-186` (re-checking mid-transaction would roll back the AiCallLog while
the spend already happened). This path is the only one that runs `ConditionPostProcessService`
(dedupe via `supersededBy` + pyramiding; `ConditionPostProcessService.java:31-90`) and the only
one that collects `PipelineMetrics` (`PipelineService.java:197-205`).

---

## 2. MODELS & PROMPTS

### 2.1 Providers

Two providers behind `service/llm/LlmProviderRouter.java`:

- **`vertex-gemini`** (`VertexGeminiAsyncProviderImpl.java`) — *not* Vertex Batch ("24h SLA …
  too slow", `:24-30`). Spawns a **virtual thread per job** calling
  `…aiplatform.googleapis.com/v1/...:streamGenerateContent?alt=sse` (`:180-182`) with ADC
  credentials. Results buffered **in-memory** (`:51`, `ConcurrentHashMap`); JVM restart loses
  them → orphan recovery re-pays. `temperature` hard-coded **0.2** (`:169`); `thinkingConfig`
  budget from request (default 10240, `:62-63`).
- **`anthropic-batch`** (`AnthropicBatchProviderImpl.java`) — **direct Anthropic Message Batches
  API** (`https://api.anthropic.com/v1/messages/batches`, `:45, :73`), `x-api-key` auth from
  `ANTHROPIC_API_KEY` (`application.yml:56`), anthropic-version `2023-06-01` (`:48-49`). Comment
  notes 50% batch pricing (`:26`). `thinkingBudget>0` maps to `thinking: adaptive` +
  `output_config.effort` low/<4k, medium/<12k, high (`:254-266`). **Not on Vertex.**

### 2.2 Routing table (purpose → provider), `LlmProviderRouter.java:40-52`

| Purpose | Provider | Effective model |
|---|---|---|
| extraction_diagnosis / medication / service_record / atom / event_segment / event | vertex-gemini | `gemini-3.1-pro-preview` |
| synthesis_identify, synthesis_rate, synthesis_verify | vertex-gemini | `gemini-3.1-pro-preview` |
| synthesis_duplicate_merger | anthropic-batch | `claude-opus-4-7` |
| gap_evidence, gap_validation | anthropic-batch | `claude-opus-4-7` |
| gap_whatif | vertex-gemini | `gemini-3.1-pro-preview` |

Model resolution: `purposeDefaultModels` is **never populated** (`:32`), so every job falls to
`defaultModelFor(provider)` — hard-coded `"claude-opus-4-7"` / `"gemini-3.1-pro-preview"`
(`:89-95`). The `CLAUDE_MODEL` / `GEMINI_MODEL` env keys (`application.yml:54,57`) do **not**
change what the router submits; they only feed `AnalysisScheduler.currentClaudeModel` and
`ClaudeSynthesisService`/provider fallbacks.

### 2.3 Prompts, structured output, limits

- All prompts are **Java string constants** inside each agent: e.g.
  `synthesis/ConditionIdentificationAgent.java:30`, `synthesis/RatingAgent.java:35`,
  `synthesis/DuplicateConditionMerger.java:30`, `synthesis/SynthesisVerificationAgent.java:28`,
  `gap/EvidenceGapAnalyzer.java:37`, `gap/GapValidationAgent.java:33`,
  `gap/WhatIfScenarioGenerator.java:34`, `extraction/GeminiExtractionService.java:33`,
  `extraction/EventSegmentationAgent.java:33`, plus per-document-type prompt selection by
  **filename keyword** in `extraction/DocumentTypePrompts.java` (fallback at
  `GeminiExtractionService.java:124-139`). Legacy prompts: `ClaudeSynthesisService.java:58,106`.
- Structured output is **prompt-engineered JSON** ("Return ONLY the JSON array",
  `GeminiExtractionService.java` prompt) parsed with `cleanJsonResponse` + Jackson. **No tool
  use / JSON-schema enforcement** anywhere in the pipeline (the `tools` field on
  `LlmJobRequest.java:22` is unused by pipeline agents). Parse failures return `List.of()`
  silently (e.g. `SynthesisVerificationAgent.java:83-90`).
- Token budgets (maxTokens / thinkingBudget): request default **16 000 / 0**
  (`LlmJobRequest.java:83-84`). Per agent: diagnosis/medication/service-record 16 384 / 0;
  atom 65 536 / 8 192 (`GeminiExtractionService.java:27,105`); segmentation 32 768 / 2 048
  (`EventSegmentationAgent.java:31,96`); event extraction 32 768 / 8 192; identify & rate &
  verify default 16 000 / 0; merger 4 096 / 6 000 (`DuplicateConditionMerger.java:24-25`);
  gap_evidence & gap_validation 8 192 / 8 000; gap_whatif 65 536 / 10 240
  (`WhatIfScenarioGenerator.java:29-32`). Upload size cap 50 MB (`IntakeController.java:102`).

---

## 3. DATA ARTIFACTS (all Postgres; GCS effectively unused)

| Artifact | Written by | Notes |
|---|---|---|
| `EvidenceItem.raw_content` | upload controllers | **base64-wrapped file bytes stored in Postgres** (`IntakeController.java:124-127`); `gcs_path` exists on the model but `DocumentStorageService.uploadToGcs` (`:44`) has **no callers** — bucket `vaclaim-documents` (`application.yml:50`) is configured but writes are never wired (also `EventPipelineController.java:88-92` "GCS download (not yet wired)") |
| `Atom` | extraction stage parses (`ExtractionStateMachine.saveAtoms`, `:349-365`) | type/value/source/confidence/timestamp, `created_by` = `ai:extraction-{diagnosis,medication,service-record,atom,event}`; append-only, no dedupe |
| `MedicalEvent` | SEGMENTS stage (`EventSegmentationAgent.java:108-118`) | per-document clinical events; `extracted` flag drives EVENTS stage |
| `IdentifiedCondition` | synthesis RATING stage (`SynthesisStateMachine.java:177-181`) + rating/verify updates; gaps & what-ifs written by gap machine (jsonb `gaps`, `what_if_scenarios`) | `superseded_by` set **only** by `ConditionPostProcessService` (legacy path) and read by `PipelineVerifierService` filters (`:92, :154`) — the async gap machine ignores it |
| `LlmJob` | `LlmJobService.submit` | **full request payload and full response payload persisted as JSON text** — this is the de-facto prompt/result archive |
| `ClaimPipelineJob` | each state machine | stage↔job join; never garbage-collected |
| `AiCallLog` | `LlmJobPoller.recordCost/recordError`, legacy `callGemini` | tokens, per-call costs, latency, purpose, llm_job_id |
| `PipelineMetrics` | `PipelineVerifierService.collectMetrics` (`:204-274`) | quality counters + token/cost rollup; **only written on the legacy admin path** (`PipelineService.java:199`) |
| `Claim` columns | scheduler + machines | `extraction/synthesis/gapState`, `synthesisNeeded`, `lastEvidenceAt`, `lastSynthesisAt/Model`, `lastGapAnalysisAt/Model`, `synthesisInProgress`, `gapAnalysisInProgress`, `analysisStage/Message/ProgressPct` (`model/Claim.java:38-88`) |

Reuse vs recompute: atoms are reused by synthesis and gap (never re-read from documents);
conditions are reused by gap; `MedicalEvent.extracted` makes the EVENTS stage incremental. But
the document→atom stages and the entire synthesis/gap chains recompute from scratch on each run.

---

## 4. INCREMENTAL BEHAVIOR — new upload AFTER a completed analysis

Today the answer is: **full re-run of everything, with duplicate-data accumulation.**

1. Upload → `processEvidence` sets `synthesisNeeded=true`, and because `extractionState` is null
   after completion, sets it to `"NONE"` (`PipelineService.java:106-108`).
2. Next scheduler tick restarts the extraction machine from NONE. `startStage` fans out jobs for
   **every EvidenceItem on the claim** (`ExtractionStateMachine.java:120` `findByClaimId` — the
   comment says "pending" but there is no filter), so **all previously processed documents are
   re-extracted through all 5 document stages**. `saveAtoms` appends — prior atoms are not
   deleted (only `/reprocess` deletes per-doc atoms, `IntakeController.java:486`), so **atoms
   duplicate** for old documents on every incremental run. SEGMENTS likewise re-segments old
   docs and `saveAll`s new `MedicalEvent` rows (duplicate events); only EXTRACTING_EVENTS is
   incremental (`findByClaimIdAndExtracted(false)`, `:154`).
3. New atoms reset the 30 s quiet window; once quiet, `shouldRunSynthesis` fires
   (`latestAtom.isAfter(lastSynthesisAt)`, `AnalysisScheduler.java:193`) → **full synthesis
   re-run over the full (now duplicated) atom set**. New conditions are saved as new rows;
   nothing deletes or supersedes the previous run's conditions in the async path
   (`SynthesisStateMachine.java:177-181`; `ConditionPostProcessService` is only invoked from the
   legacy `runFullPipeline`, `PipelineService.java:176`, and the debug endpoint deletes-then-runs,
   `IntakeController.java:552`). **Condition rows therefore grow run over run.**
4. `lastSynthesisAt > lastGapAnalysisAt` → **full gap analysis re-run** across *all* conditions
   including stale/duplicate ones (`GapStateMachine.java:85` has no `supersededBy` filter):
   per-condition gap, validation, what-if jobs all re-bought.
5. Edge case: if a new doc arrives while extraction is mid-flight, `extractionState` is unchanged
   (non-null), so the new doc only joins stages that have not yet started — earlier stages are
   silently skipped for it until the next full restart.

Invalidates what: nothing is explicitly invalidated; recomputation is triggered purely by
timestamps (`lastEvidenceAt`/atom `createdAt` vs `lastSynthesisAt` vs `lastGapAnalysisAt`) and
the model-name comparison. There is no document-level or condition-level dirty tracking.

---

## 5. COST — tracking and caps

- **Per-call ledger**: `AiCallLog` (`model/AiCallLog.java`) — provider, model, purpose
  (`callType`), input/output/thinking tokens, computed `input/output/thinking/total_cost`,
  latency, llm_job_id. Written by `LlmJobPoller.recordCost` (async path) and
  `ClaudeSynthesisService.callGemini:387` (legacy path).
- **Pricing table** hard-coded in `service/AiCostService.java:23-32` (per 1M tokens):
  gemini-3.1-pro-preview $2/$12/$12; gemini-2.5-pro $1.25/$10; gemini-2.5-flash $0.30/$2.50;
  claude-opus-4-7 and -4-6 $15/$75/$75; claude-sonnet-4-6 $3/$15; claude-haiku-4-5 $0.80/$4;
  unknown-model fallback $5/$15 (`:64-65`). **The 50% Anthropic batch discount is NOT applied** —
  batch jobs are booked at full price, so Claude spend is overcounted ~2x against the cap.
- **Alerts**: log-only daily thresholds $5 warn / $20 error (`AiCostService.java:84-92`).
- **Monthly per-user cap**: `usage.limit-cents: 400` → **$4/user/month** fair-use ceiling
  (`application.yml:23-29`), computed by `UsageService.getCurrentUsage` summing
  `AiCallLog.totalCost` by `userId` over the calendar month (`UsageService.java:29-66`); applies
  to paid users too (comment `:37-41`); operator emails exempt.
- **Enforcement points**: only `PipelineService.processEvidence:72` (defers evidence with status
  `deferred_usage_limit`), `runFullPipeline:164`, and `ChatService:71`. **The
  AnalysisScheduler→state-machine path never checks the cap** — synthesis and gap jobs are
  submitted regardless of spend. Worse, all extraction jobs are submitted with
  `userId(null)` (`ExtractionStateMachine.java:327` and `:177`), so extraction spend has no
  userId on its AiCallLog rows and **is invisible to the per-user cap sum**.
- **Per-run figure**: `PipelineMetrics.totalCostUsd` (`PipelineVerifierService.java:233-243`)
  sums *all* AiCallLogs for the claim — cumulative across runs, not per-run; logged at
  `PipelineService.java:200-202`. No constant in code states a $-per-analysis target.
- **Structural $-per-analysis** (D docs, E events, C conditions, G≤C with gaps):
  Gemini-3.1-pro: `5×D` document-stage calls + `E` event calls + identify + `C` rating + verify +
  `G` what-if; Claude-Opus-4.7 batch: 1 merge + `C` gap-evidence + `G` validation. The dominant
  costs are the per-condition Opus batch calls (each carrying the full atom set as context) and
  re-runs caused by the incremental behavior above.

---

## 6. STATIC vs DYNAMIC

**Static (fixed sequence):**
- Stage order is compile-time: 6 extraction stages × every document, then a fixed 4-step
  synthesis chain, then a fixed 3-step gap chain (`ExtractionStateMachine.java:97-106`,
  `SynthesisStateMachine.java:82-91`, `GapStateMachine.java:69-77`).
- Every document gets all five extraction passes regardless of content; every condition gets a
  rating job, a gap job, and (if gaps) validation + what-if jobs.
- Provider/model per purpose is a hard-coded table; prompt selection is filename-keyword only.

**Existing adaptivity (small):**
- Fan-out cardinality (per-evidence / per-condition / per-event).
- Empty-result fast-paths to COMPLETE (`SynthesisStateMachine.java:135-140`,
  `GapStateMachine.java:87-90, :171-176, :239-243`).
- Document-type prompt switch (`DocumentTypePrompts.java`).
- Verification stage exists but its output only annotates pyramid flags — no loop back to
  re-rate or re-extract; low-confidence atoms are merely counted in metrics notes
  (`PipelineVerifierService.java:250-252`).

**Where a dynamic Claude-agent loop would plausibly help:**
- *Extractor selection & merging*: one agent pass per document deciding which extractions are
  relevant (vs 5 unconditional passes) and emitting typed atoms in one structured call.
- *Incremental planning*: an agent can scope work to "what changed" (new doc → extract that doc,
  diff conditions, re-rate only affected conditions) — none of which the timestamp-based
  scheduler can express.
- *Self-repair*: re-prompt on JSON parse failure, re-rate when verification flags issues,
  re-extract low-confidence atoms — currently all dead ends.
- *Condition lifecycle*: dedupe/supersede across runs (the current async path simply accretes
  rows).

**Where dynamism would hurt (preserve these properties):**
- Cost determinism: today the call count is exactly predictable per claim shape; an agent loop
  needs hard budget/turn caps wired to `AiCallLog` to keep the $4 cap meaningful.
- The Anthropic *batch* discount and the fan-out grouping (`batchGroupKey`) depend on
  homogeneous, pre-plannable calls — an interactive agent loop forfeits batch pricing.
- Crash-safety: the DB-persisted job queue + state columns survive restarts (except the Vertex
  in-memory caveat); a long-running agent conversation must checkpoint equivalently.
- Accounting: `purpose` tags drive cost attribution; free-form agent tool calls need an
  equivalent taxonomy.

---

## 7. WEAKNESSES (concrete, cited)

1. **Likely infinite re-run loop via model-name mismatch.** `shouldRunSynthesis` re-triggers when
   `currentClaudeModel` (config, default `claude-opus-4-7`, `AnalysisScheduler.java:81,192`)
   differs from `lastSynthesisModel` — but `markSynthesisComplete` stores the **verify job's**
   model, and `synthesis_verify` routes to Vertex Gemini (`LlmProviderRouter.java:43`), so the
   stored value is `gemini-3.1-pro-preview` (`SynthesisStateMachine.java:298-303`). The strings
   can never match → synthesis (and gap, via `AnalysisScheduler.java:207` +
   `GapStateMachine.java:273-278`) re-runs after every completion, indefinitely, on every claim
   with an active subscription. Fast-path completions store `"unknown"` (`:138, :89`) — same effect.
2. **Usage cap not enforced on the scheduler path, and extraction spend invisible to it.** Only
   `processEvidence`/`runFullPipeline`/chat call `UsageGuard.assertCapacity`
   (`PipelineService.java:72,164`, `ChatService.java:71`); the synthesis/gap state machines never
   do. Extraction jobs are submitted with `userId(null)`
   (`ExtractionStateMachine.java:327`), so their AiCallLog rows don't count toward
   `totalCostByUserIdInPeriod` (`UsageService.java:49`).
3. **New upload re-extracts every document and duplicates data.** `startStage` fans out over
   `findByClaimId` (`ExtractionStateMachine.java:120`) with append-only `saveAtoms` (`:349-365`)
   and re-segmentation (`EventSegmentationAgent.java:114`) — atoms and MedicalEvents duplicate on
   each incremental run; cost scales with total corpus, not the delta.
4. **Conditions accrete across runs in the async path.** Re-synthesis saves a fresh condition set
   without deleting/superseding the old (`SynthesisStateMachine.java:177-181`);
   `ConditionPostProcessService` runs only on the legacy admin path (`PipelineService.java:176`);
   `GapStateMachine` then buys gap/validation/what-if calls for stale rows too
   (`GapStateMachine.java:85`, no `supersededBy` filter).
5. **One FAILED job wedges the claim forever.** Stages advance only on `allSucceeded`
   (`ExtractionStateMachine.java:207`, `SynthesisStateMachine.java:126`,
   `GapStateMachine.java:132,194,261`); provider-FAILED jobs are terminal
   (`LlmJobSubmitter.java:81-85`) and nothing requeues them or transitions the stage to an error
   state — `synthesisInProgress` stays true and the scheduler ticks the dead stage forever.
6. **Scheduler scales O(all claims) and is multi-instance unsafe.** `tick()` does
   `claimRepository.findAll()` every 15 s and advances claims serially
   (`AnalysisScheduler.java:122-138`); state-machine `advance()` has no claim-level lock, so two
   Cloud Run instances can both observe `NONE` and double-fan-out (double spend). Only the
   submitter uses SKIP LOCKED (`LlmJobSubmitter.java:21-23`).
7. **Vertex "async" provider is in-memory.** Results live in a `ConcurrentHashMap`
   (`VertexGeminiAsyncProviderImpl.java:51`); any restart/deploy mid-call loses them, and
   recovery waits the full 30-min orphan deadline (`LlmJobPoller.java:38-39,113-125`) before
   re-paying for the call.
8. **No schema-enforced structured output; silent empty-parse failures.** All agents parse
   prompt-engineered JSON and return `List.of()`/`Map.of()` on failure (e.g.
   `SynthesisVerificationAgent.java:83-90`, `ConditionIdentificationAgent.java` parse) — an
   unparseable identify response fast-paths the whole synthesis to COMPLETE as if the claim had
   no conditions (`SynthesisStateMachine.java:135-140`).
9. **Documents live as base64 text in Postgres and are sent to the model as base64.** Upload
   wraps bytes in a base64 envelope into `raw_content` (`IntakeController.java:124-127`);
   `buildRequestForStage` passes `ev.getRawContent()` verbatim as the user message
   (`ExtractionStateMachine.java:298`), so binary PDFs/images reach Gemini as base64 text;
   `uploadToGcs` (`DocumentStorageService.java:44`) is never called despite the configured
   bucket (`application.yml:50`) — 50 MB uploads become ~67 MB DB rows.
10. **Config/observability drift.** Router model defaults are hard-coded and ignore
    `CLAUDE_MODEL`/`GEMINI_MODEL` (`LlmProviderRouter.java:89-95`); Anthropic batch discount not
    reflected in `AiCostService` (overcounts vs the $4 cap); `PipelineMetrics` only collected on
    the admin path; `claim.analysisStage/ProgressPct` only updated by the legacy path, so the
    async pipeline gives the UI no progress %; event-stage results map to events via the
    evidenceId proxy (`ExtractionStateMachine.java:182-183, 274-287`), order-dependent for
    multi-event documents; `EventPipelineController` debug endpoints lack admin gating (auth-only,
    `EventPipelineController.java:63-195`).
