# Legacy Spring Analysis Pipeline — LLM Providers, Cost Controls & Chat

Engineering map of the system as it exists TODAY (branch `feat/next-web-foundation`, 2026-06-10).
All paths relative to `spring-backend/src/main/java/com/afterduty/` unless noted.
Purpose: baseline for redesign as a dynamic Claude-agent workflow on Vertex AI.

---

## 1. STAGES — ordered control flow

### 1.1 Entry: evidence upload (user-triggered, async hand-off)

- `controller/IntakeController.java:95-157` (multipart), `:161-202` (JSON), `:212-243` (quick-add) →
  `service/PipelineService.processEvidence()` (`@Async`, `PipelineService.java:60-123`).
- `processEvidence` does NO extraction itself. It:
  - checks the monthly usage cap (`UsageGuard.assertCapacity`, `PipelineService.java:72`); on cap hit sets
    `processingStatus="deferred_usage_limit"` and returns (`:74-80`);
  - marks evidence `processed`/"Queued for async extraction" (`:97-101`);
  - sets `claim.lastEvidenceAt`, `claim.synthesisNeeded=true`, `claim.extractionState="NONE"` *only if currently null* (`:103-110`), status `EXTRACTING`.
- Binary uploads are stored as a **base64 envelope inside `EvidenceItem.rawContent` (Postgres text column)**:
  `"CONTENT_ENCODING: base64\nFILE_TYPE: ...\nFILENAME: ...\n\n<base64>"` (`IntakeController.java:124-127`). 50 MB size cap (`:102`).
  GCS (`va-claim.gcs.bucket: vaclaim-documents`, `application.yml:50`) is used for download/delete endpoints only
  (`IntakeController.java:276,335`); **extraction never reads from GCS**.

### 1.2 Scheduler: `AnalysisScheduler` (polling driver, replaces "Analyze" button)

- `@Scheduled` every 15 s (`va-claim.pipeline.poll-ms:15000`, initial 20 s) — `AnalysisScheduler.java:120-138`.
- Each tick: `claimRepository.findAll()` (full table scan, `:125`) → per claim `advanceClaim` (`:140-160`):
  1. **Subscription gate**: claim owner must have `hasActiveSubscription()` (`:164-169`; `User.java`: `subscriptionExpiresAt > now`).
  2. **Extraction SM** ticks if `extractionState != null && != "COMPLETE"` (`:144-149`).
  3. **Synthesis SM** ticks if `shouldRunSynthesis` (`:173-194`): in-flight state, OR (≥1 atom AND 30 s quiet period
     `va-claim.pipeline.quiet-seconds:30` AND no evidence pending/processing AND [never synthesized | configured
     `va-claim.claude.model` differs from `lastSynthesisModel` | latest atom newer than `lastSynthesisAt`]).
     NB: the model-change trigger compares the **Claude** model id (`:81,192`) even though synthesis stages mostly run on **Gemini**.
  4. **Gap SM** ticks if `shouldRunGapAnalysis` (`:198-214`): in-flight, OR synthesis done AND [never ran | Claude model changed | `lastSynthesisAt > lastGapAnalysisAt`].
- Completion callbacks `markSynthesisComplete`/`markGapAnalysisComplete` (`:218-238`) null the SM state and stamp `last*At`/`last*Model`.
- `claim.synthesisNeeded` is set by uploads/chat but **never read by the scheduler** — triggers are purely timestamp-based. (`PipelineService.checkAndRunSynthesis`, `PipelineService.java:229-243`, is private and has no callers — dead code.)

### 1.3 Extraction state machine (`extraction/ExtractionStateMachine.java`)

State stored as a string in `Claim.extractionState`; one transition max per tick (`:94-107`):

```
NONE → EXTRACTING_DIAGNOSIS → EXTRACTING_MEDICATIONS → EXTRACTING_SERVICE_RECORDS
     → EXTRACTING_ATOMS → EXTRACTING_SEGMENTS → EXTRACTING_EVENTS → null (COMPLETE)
```

- `startStage` fans out **one LLM job per `EvidenceItem` of the whole claim** (`:120,133-138`) — no "already extracted" filter.
  EXTRACTING_EVENTS instead fans out per unextracted `MedicalEvent` (`:153-190`).
- Each job gets `batchGroupKey = "<purpose>_<claimId>"` so the submitter can batch them (`:130,301`).
- `advanceIfReady` blocks until `llmJobService.allSucceeded(stageJobIds)` (`:196-217`); then `parseStageResults` persists
  Atoms/MedicalEvents (`:233-291`) and transitions.
- `parseState(null)` disambiguates "never started" vs "completed" by counting historical `ClaimPipelineJob` rows (`:367-395`).
- Prompt input is `ev.getRawContent()` **verbatim** (`:298`) — i.e., the base64 envelope for binary uploads.

### 1.4 LLM job infrastructure (the only async LLM API)

- `llm/LlmJobService.submit()` (`LlmJobService.java:51-81`): persists a `QUEUED` `LlmJob` row (provider+model resolved by
  router, full prompt serialized into `request_payload`), then **"pre-registers" by calling `provider.submit(List.of(saved))`**
  (`:73-78`) while leaving the row QUEUED — see Weakness W1.
- `llm/LlmJobSubmitter.tick()` every 3 s (`poll-ms:3000`, batch-size 50; `LlmJobSubmitter.java:40-92`): pulls QUEUED rows with
  `PESSIMISTIC_WRITE` (skip-locked), groups by `(provider, batchGroupKey)` (null key ⇒ singleton group), calls `provider.submit(jobs)`,
  sets `SUBMITTED` + `providerJobId` + `attempts++`. Submit failure: retry next tick; **FAILED after 3 attempts** (`:79-89`).
- `llm/LlmJobPoller.tick()` every 15 s (`LlmJobPoller.java:47-52`):
  - **Orphan recovery**: any `SUBMITTED` row older than `va-claim.llm.orphan-deadline-min:30` is reset to QUEUED with null
    `providerJobId` (`:113-125`) — no attempts ceiling on this loop.
  - **Poll**: per active `providerJobId`, `provider.poll()`; IN_PROGRESS rows marked; on terminal, `provider.fetchResults()`
    → `response_payload` persisted, status SUCCEEDED/FAILED, and an `AiCallLog` row written (`:86-104,127-165`).
- Job status machine: `QUEUED → SUBMITTED → IN_PROGRESS → SUCCEEDED | FAILED` (`model/LlmJob.java:26-32`). The full prompt
  and raw response live in Postgres (`request_payload`/`response_payload`, `:64-74`).
- Orchestrators consume via `getResult()` (empty until SUCCEEDED, throws `LlmJobFailedException` on FAILED — `LlmJobService.java:95-103`)
  and the `allSucceeded`/`allTerminal` gates (`:106-115`).

### 1.5 Synthesis state machine (`synthesis/SynthesisStateMachine.java`)

```
NONE → IDENTIFYING → MERGING → RATING → VERIFYING → COMPLETE
```
- `doIdentify` (`:98-114`): ALL claim atoms + service/presumptive context → 1 `synthesis_identify` job (Gemini).
- `doMergeIfReady` (`:120-150`): parses identify JSON; empty ⇒ fast-path complete; else 1 `synthesis_duplicate_merger` job (**Anthropic Opus batch**).
- `doRateIfReady` (`:156-197`): persists merged conditions as new `IdentifiedCondition` rows (`:177-181`, **no deletion of prior
  conditions**), fans out 1 `synthesis_rate` job per condition (Gemini) with all atoms in the prompt.
- `doVerifyIfReady` (`:203-255`): writes ratings onto conditions, 1 `synthesis_verify` job (Gemini) over all conditions.
- `doCompleteIfReady` (`:261-305`): parses issues, applies pyramiding/ratings corrections via `EnhancedSynthesisOrchestrator.applyCorrections`,
  then `markSynthesisComplete`.
- Stale-row hazard: each step reads `findByClaimIdAndStage(...)` and takes `pjobs.get(0)` with **no ordering and no cleanup of
  previous-run rows** (`:121-131,165-174,262-272`) — see W4.

### 1.6 Gap-analysis state machine (`gap/GapStateMachine.java`)

```
NONE → EVIDENCE_GAPS → VALIDATING → WHATIF → COMPLETE
```
- `doFanoutEvidence` (`:84-120`): 1 `gap_evidence` job per condition (**Anthropic Opus batch**), all atoms in each prompt.
- `doFanoutValidatingIfReady` (`:126-182`): writes `cond.gaps` **wholesale** from the model output (`:143-146` — clobbers any
  chat-set resolved/dismissed status), then 1 `gap_validation` job per condition with gaps (**Anthropic Opus batch**).
- `doFanoutWhatIfIfReady` (`:188-249`): updates gaps, 1 `gap_whatif` job per condition (Gemini).
- `doCompleteIfReady` (`:255-280`): stores `whatIfScenarios` per condition, `markGapAnalysisComplete`.
- Every advance gate is `allSucceeded` — a single FAILED job permanently blocks the stage (see W5).

### 1.7 Chat (synchronous, separate from the job infrastructure)

- `POST /api/intake/chat` (`IntakeController.java:636-651`) → `ChatService.sendMessage` (`ChatService.java:59-117`):
  thread resolve/create (UNIQUE (viewer,claim), race-safe), `usageGuard.assertCapacity(viewer)` **before** the AI call (`:71`),
  persist veteran message, snapshot atom count, call `ChatAgent.handle`, persist assistant reply (graceful fallback text on any
  exception, `:92-97`), set `synthesisNeeded=true` if atoms grew (`:108-114`).
- `ChatAgent.handle` (`ChatAgent.java:79-138`): a real tool-use agent loop — system prompt embeds the FULL claim state
  (all atoms + conditions + gaps rendered as text, `:191-272`), last 16 thread messages (`:39,276-290`), 7 tools
  (`add_atom`, `update_atom`, `delete_atom`, `update_condition`, `delete_condition`, `mark_gap_resolved`, `mark_gap_dismissed`,
  `:294-390`), loops ≤ 8 iterations executing tools against repositories (`:393-495`), returns final text.
- HTTP: direct Anthropic `/v1/messages` (sync), 300 s timeout, **unbounded recursive retry with `Thread.sleep(30s)` on 429** (`:170-176`).
- Access (`ClaimAccessService.assertScope` CHAT case, `ClaimAccessService.java:139-158`): **claim owner always allowed —
  bypasses subscription entirely**; non-owner viewer needs `canViewAnalysis` + viewer Pro.
- **No `AiCallLog` is written for chat calls** (only `LlmJobPoller` and `ClaudeSynthesisService` call `AiCostService`).

### 1.8 Legacy synchronous path (still wired)

- `POST /api/intake/analyze` (admin-only, `IntakeController.java:430,457-464`) and `/debug/*` endpoints call
  `PipelineService.runFullPipeline` (`PipelineService.java:159-208`): synchronous `ClaudeSynthesisService.runSynthesis`
  (one-shot Gemini over all atoms) → `ConditionPostProcessService.postProcess` (dedup/pyramiding; only on this path) →
  `runGapAnalysis` (one-shot Gemini) → `PipelineVerifierService.collectMetrics`.
- `ClaudeSynthesisService` is **misnamed — it calls Vertex Gemini** (`ClaudeSynthesisService.java:23,339-341`), sync
  `generateContent`, unbounded 60 s-sleep retry on 429 (`:353-359`), records `AiCallLog` itself (`:356,366,387,422`).
- Deliberate design note at `PipelineService.java:181-186`: cap is checked once at entry, not mid-pipeline, because a guard
  throw inside `@Transactional` would roll back the just-written `AiCallLog` rows while the provider money is already spent.

### 1.9 Retry / failure summary

| Layer | Retry | Failure handling |
|---|---|---|
| Submit to provider | next 3 s tick, max 3 attempts (`LlmJobSubmitter.java:79-89`) | LlmJob FAILED + errorMessage |
| Poll/fetch error | leave SUBMITTED, retry next tick (`LlmJobPoller.java:105-109`) | orphan recovery after 30 min requeues (unbounded) |
| Vertex JVM restart | in-memory result lost → poll FAILED or orphan requeue (`VertexGeminiAsyncProviderImpl.java:30-35,100-106`) | re-pays the call |
| Per-job model failure | none | state machines stall forever on `allSucceeded` (W5) |
| Chat / legacy 429 | infinite recursion + sleep (`ChatAgent.java:172-176`; `ClaudeSynthesisService.java:353-359`) | — |
| Chat other errors | none | fallback assistant message (`ChatService.java:92-97`) |

---

## 2. MODELS & PROMPTS

### 2.1 Routing (`llm/LlmProviderRouter.java`)

Precedence: explicit `preferredProvider` → model-prefix (`claude-*`→Anthropic, `gemini-*`→Vertex) → purpose table → Vertex default
(`:17-23,55-71`). **No caller in the codebase ever sets `preferredProvider`/`preferredModel`** (grep), and `purposeDefaultModels`
is declared but never populated (`:32`), so the effective routing is the purpose table + per-provider default model:

| purpose | provider | model | maxTokens | thinkingBudget |
|---|---|---|---|---|
| extraction_diagnosis | vertex-gemini | gemini-3.1-pro-preview | 16,384 | 0 (`DiagnosisExtractorService.java:58-70`) |
| extraction_medication | vertex-gemini | " | 16,384 | 0 (`MedicationExtractorService.java:62-65`) |
| extraction_service_record | vertex-gemini | " | 16,384 | 0 (`ServiceRecordExtractorService.java:65-68`) |
| extraction_atom | vertex-gemini | " | 65,536 | 8,192 (`GeminiExtractionService.java:27,105-106`) |
| extraction_event_segment | vertex-gemini | " | 32,768 | 2,048 (`EventSegmentationAgent.java:31,96-97`) |
| extraction_event | vertex-gemini | " | 32,768 | 8,192 (`EventExtractionAgent.java:37,113-114`) |
| synthesis_identify | vertex-gemini | " | 16,000 (builder default) | 0 (`ConditionIdentificationAgent.java:85-91`; `LlmJobRequest.java:83-84`) |
| synthesis_duplicate_merger | **anthropic-batch** | **claude-opus-4-7** | 4,096 | 6,000 (`DuplicateConditionMerger.java:24-25`) |
| synthesis_rate | vertex-gemini | gemini-3.1-pro-preview | 16,000 | 0 (`RatingAgent.java:108-116`) |
| synthesis_verify | vertex-gemini | " | 16,000 | 0 (`SynthesisVerificationAgent.java:71-78`) |
| gap_evidence | **anthropic-batch** | **claude-opus-4-7** | 8,192 | 8,000 (`EvidenceGapAnalyzer.java:29-30`) |
| gap_validation | **anthropic-batch** | **claude-opus-4-7** | 8,192 | 8,000 (`GapValidationAgent.java:27-28`) |
| gap_whatif | vertex-gemini | gemini-3.1-pro-preview | 65,536 | 10,240 (`WhatIfScenarioGenerator.java:29-32`) |
| chat (bypasses router) | Anthropic sync `/v1/messages` | claude-opus-4-7 | 8,192 | 6,000 → adaptive/medium (`ChatAgent.java:36-38,154-157`) |
| legacy synthesis/gap (bypasses router) | Vertex sync `generateContent` | gemini-3.1-pro-preview | 65,536 | 10,240 (`ClaudeSynthesisService.java:48-56`) |

Purpose table: `LlmProviderRouter.java:40-52`. Default models: `:89-95`. Config: `application.yml:51-57`
(`GEMINI_MODEL:gemini-3.1-pro-preview`, `CLAUDE_MODEL:claude-opus-4-7`, `ANTHROPIC_API_KEY`, `GCP_PROJECT`, location `global`).
`va-claim.vertex.claude-region` (`application.yml:47-48`) has **no Java reader — dead config** (no Claude-on-Vertex path exists today).

### 2.2 Providers

- **`AnthropicBatchProviderImpl`** (`llm/AnthropicBatchProviderImpl.java`): Anthropic **Message Batches API** direct
  (`api.anthropic.com/v1/messages/batches`, version header `2023-06-01`). One POST per (provider, batchGroupKey) group; all jobs in
  the group share one `batch_id` as `providerJobId` (`:55-103`). Poll maps `processing_status` (`:105-142`; non-200/exception ⇒
  treated as IN_PROGRESS). Results fetched as JSONL keyed by `custom_id`=job UUID (`:144-199`). Thinking: `thinkingBudget>0` ⇒
  `thinking:{type:adaptive}` + `output_config.effort` low/<4k, medium/<12k, high (`:254-266`). Doc comment claims 50% batch pricing,
  ≤24 h SLA (`:23-24`).
- **`VertexGeminiAsyncProviderImpl`** (`llm/VertexGeminiAsyncProviderImpl.java`): NOT Vertex batch prediction (rejected for
  24 h SLA + GCS I/O, `:24-27`). Instead **one virtual thread per job** calling `streamGenerateContent?alt=sse` on
  `aiplatform.googleapis.com` (`:82-94,180-182`), ADC credentials. `temperature` hardcoded **0.2** (`:169`), `thinkingConfig.thinkingBudget`
  passthrough. Results buffered **in-memory** (`inflight` map, `:51`); JVM restart loses them (orphan recovery compensates by re-running).
  Token usage from `usageMetadata` incl. `thoughtsTokenCount` (`:227-235`).
- **Chat + legacy sync**: direct HTTP in `ChatAgent`/`ClaudeSynthesisService` as above — these two bypass `LlmJobService` entirely.

### 2.3 Prompts & structured output

- All prompts are **Java string constants compiled into the services** — e.g. `GeminiExtractionService.java:33-82` (exhaustive
  atom-extraction rules + confidence rubric + self-check), document-type prompt selection by filename keyword matching
  (`:123-140`, `DocumentTypePrompts` for health_summary/imo_letter/dd214/dbq/personal_statement), `DiagnosisExtractorService.java:28-42`,
  `ClaudeSynthesisService.java:58-140` (synthesis + gap one-shot prompts), `ChatAgent.java:195-228` (chat system prompt with
  rendered claim state). No prompt files, no versioning, no prompt registry.
- Structured output approach everywhere: "Return ONLY the JSON array/object, no other text" + manual ``` fence stripping +
  Jackson parse; **parse failure returns an empty list and the pipeline continues silently** (`GeminiExtractionService.java:142-187`,
  `ClaudeSynthesisService.java:521-541`). No JSON mode (`responseMimeType` deliberately avoided with thinking,
  `ClaudeSynthesisService.java:330`), no tool-forced schemas outside chat.
- `LlmJobRequest` (`llm/LlmJobRequest.java`) is the provider-agnostic shape: purpose, systemPrompt, userMessage | messages[],
  tools[], maxTokens (default 16,000), thinkingBudget (default 0), batchGroupKey, claim/user/condition/evidence ids.

---

## 3. DATA ARTIFACTS

All persistence is **Postgres** (Cloud SQL; `application-cloud.yml`). GCS holds original uploaded files only (download/delete paths).

| Artifact | Written by | Notes |
|---|---|---|
| `EvidenceItem` | upload endpoints | `rawContent` = base64 envelope (binary) or plain text (JSON/quick-add); `processingStatus` pending/processing/processed/error/deferred_usage_limit; `fileHash` dedup per claim (`IntakeController.java:115-118`) |
| `Atom` | ExtractionStateMachine.saveAtoms (`:349-365`), ChatAgent `add_atom` (`ChatAgent.java:396-409`) | typed facts, confidence, source, `createdBy` provenance (`ai:extraction-*`, `ai:claude-opus-4-7`); **append-only** in the pipeline; deleted only by reprocess endpoint (`atomRepository.deleteByEvidenceId`, `IntakeController.java:486`) or chat tool |
| `MedicalEvent` | EventSegmentationAgent.parseResponse (`:114`) | per-document clinical episodes; `extracted` flag flipped by EventExtractionAgent (`:128-134`); drives EXTRACTING_EVENTS fan-out |
| `IdentifiedCondition` | SynthesisStateMachine (MERGING→RATING, `:177-181`), updated at RATING/VERIFYING; gaps + whatIfScenarios (JSON columns) written by GapStateMachine; chat tools mutate | **never deleted by the async pipeline** — only `/debug/synthesis` clears (`IntakeController.java:552`) and `ConditionPostProcessService` (legacy path only) supersedes duplicates |
| `LlmJob` | LlmJobService/Submitter/Poller | durable per-call record: full `request_payload` + `response_payload`, status, attempts, timestamps — effectively a complete prompt/response archive |
| `ClaimPipelineJob` | state machines | (claimId, stage, llmJobId, conditionId, evidenceId) linking rows; **never deleted** except whole-user deletion; `deleteByClaimIdAndStage` exists but has no caller |
| `AiCallLog` | LlmJobPoller (async path), ClaudeSynthesisService (legacy) | tokens + computed $ per call; `llm_job_id` groups rows per logical job |
| `Claim` columns | everywhere | `extractionState`/`synthesisState`/`gapState` (SM cursors), `synthesisNeeded`, `synthesisInProgress`, `gapAnalysisInProgress`, `lastSynthesisAt/Model`, `lastGapAnalysisAt/Model`, `lastEvidenceAt`, `lastAnalyzedAt`, `analysisStage/Message/ProgressPct` |
| `ChatThread` / `IntakeMessage` | ChatService | one thread per (viewer, claim) (UNIQUE), messages with role veteran/assistant; atoms link to messageId for chat provenance |

**Reused vs recomputed:** atoms, conditions, gaps, and LLM responses are all persisted, but the pipeline never *reuses* prior
LLM work on re-runs — every re-trigger recomputes everything from the raw inputs (see §4). The only true reuse is
`MedicalEvent.extracted` (events stage skips already-extracted events) and the file-hash duplicate-upload rejection.

---

## 4. INCREMENTAL BEHAVIOR — new document after a completed analysis

What actually happens, step by step:

1. **Upload** → `processEvidence`: `extractionState` is null (completed), so it is set to the literal `"NONE"`
   (`PipelineService.java:106-108`), `synthesisNeeded=true`, `lastEvidenceAt=now`.
2. **Extraction restarts from scratch for the ENTIRE claim.** `startStage` fans out per-stage jobs for
   `evidenceItemRepository.findByClaimId(claim)` — **all documents, old and new** (`ExtractionStateMachine.java:120,133-138`).
   Every previously processed document is re-sent to Gemini for all 5 text stages. There is no per-document "already extracted" marker.
3. **Atoms duplicate.** `parseStageResults` re-parses every `ClaimPipelineJob` for the stage — and because rows from previous
   runs are never deleted, it iterates *previous runs' SUCCEEDED jobs too* (`:198,233-291`), re-`saveAtoms`-ing their outputs.
   Combined with the fresh re-extraction this is multiplicative: run N re-persists atoms from runs 1..N. No atom dedup exists
   in the async path.
4. **Synthesis re-runs in full.** After the 30 s quiet period, `shouldRunSynthesis` fires because
   `latestAtom > lastSynthesisAt` (`AnalysisScheduler.java:193`). The 4-stage chain runs over ALL atoms.
   New `IdentifiedCondition` rows are **appended without deleting the previous run's conditions**
   (`SynthesisStateMachine.java:177-181`); the dedup/supersede pass (`ConditionPostProcessService`) only runs on the legacy
   `/analyze` path (`PipelineService.java:176`), not the production async path. Stage readers also use `pjobs.get(0)` over a
   list polluted with previous-run rows and no ORDER BY (`:129,167`), so a re-run can parse a **stale** identify/merge result.
5. **Gap analysis re-runs in full** (`lastSynthesisAt > lastGapAnalysisAt`, `AnalysisScheduler.java:208`), per condition —
   including the duplicated ones — and `cond.setGaps(gaps)` **overwrites the gaps array wholesale**
   (`GapStateMachine.java:143-146`), destroying any `status=resolved/dismissed` the veteran set via chat tools
   (`ChatAgent.setGapStatus`, `ChatAgent.java:471-495`).
6. **Chat-added atoms** trigger the same full re-synthesis path (atom timestamp), not a delta.

**Summary: there is no incremental processing anywhere.** A new document costs ~(5 × total_docs + events) extraction calls
+ full synthesis (3 + N_conditions calls) + full gap analysis (up to 3 × N_conditions calls), and it corrupts state
(duplicate atoms/conditions, lost gap statuses) on top of the cost. The only "delta" mechanisms are timestamps
(`lastSynthesisAt` vs latest atom) used as *triggers*, never as *scopes*.

---

## 5. COST — tracking, capping, per-run structure

### 5.1 Recording

- `AiCostService.recordCall/recordError` (`AiCostService.java:40-61`) computes $ from a **hard-coded per-1M-token table**
  (`:23-32`, "as of April 2026"): gemini-3.1-pro-preview 2.00/12.00/12.00, gemini-2.5-pro 1.25/10/10, gemini-2.5-flash
  0.30/2.50/2.50, claude-opus-4-7 and 4-6 15/75/75, claude-sonnet-4-6 3/15/15, claude-haiku-4-5 0.80/4/4; unknown model
  fallback 5/15/15 (`:64-65`). Thinking tokens billed at the output rate.
- **The 50% Anthropic batch discount is NOT modeled** — batch jobs are booked at sync prices, so Anthropic spend is
  overstated ~2× in the books (vs `AnthropicBatchProviderImpl.java:23` claiming 50% off).
- `cache_read_tokens`/`cache_write_tokens` columns exist (`AiCallLog.java:47-51`) but are never populated; **no prompt caching
  is used anywhere** despite the chat/synthesis prompts repeating the full claim state every call.
- Async-path rows are written by `LlmJobPoller.recordCost/recordError` (`LlmJobPoller.java:127-165`) with purpose, provider,
  model, tokens, latency, `llm_job_id`. Legacy sync path self-records (`ClaudeSynthesisService.java:356,366,387,422`).
- **Chat records nothing** — `ChatAgent` never touches `AiCostService` (grep over `recordCall` confirms only Poller + legacy service).

### 5.2 Capping & alerting

- Per-user monthly cap: `usage.limit-cents: 400` = **$4.00/user/month**, UTC calendar month
  (`application.yml:23-29`, `UsageProperties.java:14`, `UsageService.java:29-66`). Applies to paying subscribers too
  (fair-use ceiling on the $11.99/mo plan — comment `UsageService.java:36-41`); only the `USAGE_UNLIMITED_EMAILS` operator
  allowlist is exempt.
- Enforcement points (`UsageGuard.assertCapacity`) — **only two**: `ChatService.sendMessage` (`ChatService.java:71`) and
  `PipelineService.processEvidence`/`runFullPipeline` (`PipelineService.java:72,164`). The production async pipeline
  (`AnalysisScheduler` → state machines → `LlmJobService`) **never checks the cap**: once evidence is queued, all extraction
  fan-out, synthesis, and gap stages run regardless of accrued spend. The cap effectively gates only "can you queue another doc"
  and "can you send a chat message".
- Daily alerts are **log-only**: WARN > $5/day, ERROR > $20/day across all users (`AiCostService.java:84-92`). No circuit
  breaker, no notification, no per-claim ceiling.
- Free tier: uploads are free (`IntakeController.java:166-167`); the AI pipeline is gated by the owner-subscription check in
  `AnalysisScheduler.advanceClaim` (`:141,164-169`) and `/quick-add`'s `requireActiveSubscription` (`:219-221`). **Exception:
  chat — the claim owner bypasses the subscription check entirely** (`ClaimAccessService.java:139-143`), so a free user gets
  Claude Opus chat bounded only by a $4 cap that chat spend never increments.

### 5.3 Per-analysis cost shape (structural)

For a claim with D documents, E medical events, C identified conditions:

- Extraction: `5D + E` Gemini 3.1 Pro calls (each carrying the full document text — or its base64 — in the prompt).
- Synthesis: 1 identify (Gemini, all atoms) + 1 merge (Opus batch) + `C` rate (Gemini, **all atoms per condition**) + 1 verify (Gemini).
- Gap: `C` gap_evidence (Opus batch, **all atoms per condition**) + ≤`C` gap_validation (Opus batch) + ≤`C` whatif (Gemini).
- Chat: per message, 1+ Opus sync calls (≤8 in the tool loop), each re-sending the full rendered claim state + 16-turn history.
- Re-run amplification: each new document re-pays nearly all of the above (§4), and the per-condition stages multiply against
  duplicate conditions accumulated by prior re-runs.
- Observability: per-claim totals via `AiCallLogRepository.totalCostByClaimId` and `PipelineVerifierService.collectMetrics`
  (`PipelineVerifierService.java:232-243`, logged at `PipelineService.java:198-205`); admin dashboards keyed off the
  aggregate queries in `AiCallLogRepository` (by model, by user/callType — `:32-51`). No per-run dollar figures appear in
  comments beyond the $5/$20 alert thresholds and the $4 cap.

---

## 6. STATIC vs DYNAMIC

**Static (everything except chat):**
- Three hand-rolled, linear state machines with fixed stage orders compiled into `switch` statements
  (`ExtractionStateMachine.java:97-107`, `SynthesisStateMachine.java:82-92`, `GapStateMachine.java:69-78`).
- Fixed fan-out shapes: per-evidence × 5 stages; per-condition × 3 gap stages. Every document gets all five extractors
  regardless of content; the only content-adaptivity is filename-keyword prompt selection (`GeminiExtractionService.java:123-140`).
- Purpose→provider routing is a static table (`LlmProviderRouter.java:40-52`); the `preferredProvider`/`preferredModel`
  escape hatches exist but are unused; `purposeDefaultModels` is empty.
- Prompts are compiled constants; temperature is hardcoded 0.2 for all Gemini calls (`VertexGeminiAsyncProviderImpl.java:169`).
- Triggers are mechanical timestamp/quiet-period rules in `AnalysisScheduler` — no judgment about *what* changed.

**Dynamic (today):**
- `ChatAgent` is the one genuine agent loop: model-driven tool selection, ≤8 iterations, persisted side effects
  (`ChatAgent.java:89-137`). It is also the one path with no cost tracking and no async/batch infrastructure.
- Adaptive-effort mapping for Anthropic thinking budgets (`AnthropicBatchProviderImpl.java:254-266`).

**Where a dynamic Claude-agent workflow would plausibly help:**
- Document triage: one cheap classification call deciding *which* extractors/modalities a document needs (vs blanket 5 calls),
  and native multimodal ingestion instead of the base64-text hack (W6).
- Incremental reasoning: an agent that reads existing atoms/conditions and processes only the delta of a new document,
  merging against DB state instead of the current full-redo + append-duplicates behavior (§4).
- Condition-merge/verify: dedup against *persisted* conditions (the current merger only sees the fresh identify output).
- Failure recovery: agent can retry/repair a single failed sub-task instead of the all-or-nothing `allSucceeded` stall (W5).
- Gap analysis grounded in tools (VASRD lookup, VA math) rather than asking the model to emit ratings tables by memory.

**Where it would hurt / what the static design currently buys:**
- The Anthropic batch API's 50% pricing requires up-front static fan-out — a reactive agent loop forfeits that discount
  (relevant if batch jobs move to realtime Claude on Vertex).
- Deterministic cost ceilings: today the call count per run is exactly predictable (5D+E+3+C+3C); an open agent loop needs
  explicit budget enforcement that the current cap infrastructure (only enforced at queue time, §5.2) cannot provide.
- Crash-safety semantics are simple because every step is a durable `LlmJob` row + idempotent-ish poll; an agent transcript
  would need equivalent durable checkpointing on Cloud Run (restarts already lose Vertex in-flight streams today).

---

## 7. WEAKNESSES (concrete, cited)

W1. **Likely double execution / double pay of every async LLM job.** `LlmJobService.submit` "pre-registers" by calling
`provider.submit(List.of(saved))` while leaving the row QUEUED (`LlmJobService.java:70-78`); the comment claims this is an
HTTP no-op for real providers, but `AnthropicBatchProviderImpl.submit` unconditionally POSTs a real batch
(`AnthropicBatchProviderImpl.java:70-91`) and `VertexGeminiAsyncProviderImpl.submit` unconditionally starts a real streaming
call on a virtual thread (`VertexGeminiAsyncProviderImpl.java:82-94`). `LlmJobSubmitter` then submits the same QUEUED row
again (`LlmJobSubmitter.java:44-73`). Net: two provider executions per job; only the second's result/cost is recorded.

W2. **The monthly cap is not enforced where the money is spent.** `UsageGuard.assertCapacity` is called only at evidence-queue
time and chat time (`PipelineService.java:72,164`; `ChatService.java:71`). The scheduler-driven extraction/synthesis/gap fan-out —
the dominant spend — submits jobs with no cap check, and re-triggers itself indefinitely (§4), so one already-queued claim can
blow far past $4 with no brake. Daily $5/$20 alerts are log-only (`AiCostService.java:84-92`).

W3. **Chat is unmetered Opus, and free owners can use it.** `ChatAgent` writes no `AiCallLog` (no `AiCostService` reference in
the class), so chat spend neither counts against the $4 cap it nominally checks nor appears in dashboards; and
`ClaimAccessService` CHAT scope lets the claim **owner bypass the subscription check entirely**
(`ClaimAccessService.java:139-143`), i.e. free-tier users get claude-opus-4-7 tool-loop chat (≤8 calls/message, full claim
state re-sent each call) at zero recorded cost. (`IntakeController.java:153`: "Phase D will revisit chat-side billing.")

W4. **Re-runs corrupt state: duplicate atoms, duplicate conditions, stale results, lost user edits.**
(a) New upload restarts extraction over all docs and `parseStageResults` re-parses prior runs' never-deleted
`ClaimPipelineJob` rows, re-appending their atoms (`ExtractionStateMachine.java:120,198,233-291,349-365`).
(b) Re-synthesis appends new `IdentifiedCondition` rows without removing the previous set
(`SynthesisStateMachine.java:177-181`); dedup (`ConditionPostProcessService`) only runs on the legacy path
(`PipelineService.java:176`). (c) Stage readers take `pjobs.get(0)` from an unordered, multi-run list
(`SynthesisStateMachine.java:129,167`). (d) Gap re-runs overwrite `cond.gaps` wholesale, erasing chat-set
resolved/dismissed statuses (`GapStateMachine.java:143-146` vs `ChatAgent.java:471-495`).

W5. **No failure path in any state machine.** Every transition gates on `llmJobService.allSucceeded(...)`
(`ExtractionStateMachine.java:207`, `SynthesisStateMachine.java:126,162,209,267`, `GapStateMachine.java:132,194,261`).
A single FAILED `LlmJob` (e.g. submit failed 3×) leaves the claim's SM state non-null forever: the scheduler re-ticks it every
15 s as a no-op, the user sees a permanently in-progress analysis, and nothing retries, compensates, or surfaces the error.

W6. **Binary documents are fed to the LLM as base64 text.** Uploads are stored as a base64 envelope in `rawContent`
(`IntakeController.java:124-127`) and extraction passes `ev.getRawContent()` verbatim into the user message
(`ExtractionStateMachine.java:298`; `GeminiExtractionService.java:100`). The decode helper exists but is only used by the
download endpoint (`DocumentStorageService.java:151-159`). PDFs/scans are therefore never properly read (and a 50 MB file
would be a ~67 MB prompt); no multimodal/Files-API path exists despite GCS storage being available.

W7. **Cost books are inaccurate by design.** Hardcoded pricing table with a generous unknown-model fallback
(`AiCostService.java:23-32,64-65`) will silently drift; the Anthropic 50% batch discount is not applied (overstating Opus
spend ~2×, which also distorts the $4 cap); thinking tokens are priced at output rates for all models; cache token columns are
dead (`AiCallLog.java:47-51`); chat spend is absent entirely (W3).

W8. **Scheduler scales O(all claims) and is multi-instance unsafe.** `AnalysisScheduler.tick` does
`claimRepository.findAll()` every 15 s (`AnalysisScheduler.java:125`) and `advanceClaim` has no cross-instance locking —
two Cloud Run instances can both pass `shouldRunSynthesis` and double-submit a whole synthesis chain (the LlmJob layer is
skip-locked, `LlmJobSubmitter.java:21-23`, but SM advancement is not).

W9. **Unbounded retries.** ChatAgent recurses forever on 429 with a 30 s sleep on the request thread
(`ChatAgent.java:172-176`); `ClaudeSynthesisService` does the same with 60 s inside a `@Transactional` request
(`:353-359`); orphan recovery requeues SUBMITTED jobs every 30 min with no attempt ceiling (`LlmJobPoller.java:113-125`),
so a permanently-failing provider call is re-paid indefinitely (Vertex re-runs are real money each cycle).

W10. **Fragile structured output + misc hygiene.** All pipeline outputs rely on "Return ONLY JSON" + fence-stripping;
parse failures return empty lists and the pipeline proceeds as if the stage legitimately found nothing
(`GeminiExtractionService.java:182-186`, `ClaudeSynthesisService.java:521-530`) — e.g. an unparseable identify response
fast-paths synthesis to COMPLETE with zero conditions (`SynthesisStateMachine.java:135-139`). Also: `ClaudeSynthesisService`
is misnamed (it calls Gemini, `:23`); `va-claim.vertex.claude-region` is dead config (`application.yml:47-48`);
`purposeDefaultModels` never populated (`LlmProviderRouter.java:32`); the "model changed → re-run" trigger compares the
Claude model id for Gemini-executed stages (`AnalysisScheduler.java:81,192,207`), so flipping `CLAUDE_MODEL` re-runs every
claim's synthesis (cost spike) while flipping `GEMINI_MODEL` re-runs nothing.
