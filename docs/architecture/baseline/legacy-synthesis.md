# Legacy Synthesis & Rating Pipeline — Engineering Map (Spring backend)

Scope: the "condition synthesis & rating" portion of the analysis pipeline as it exists TODAY on branch `feat/next-web-foundation`. All paths relative to `spring-backend/src/main/java/com/afterduty/` unless noted. This is the baseline document for redesigning synthesis as a dynamic Claude-agent workflow on Vertex AI.

**Headline facts**

- The ACTIVE pipeline is an async, DB-backed, hand-rolled state machine (`service/synthesis/SynthesisStateMachine.java`) driven by a 15-second polling scheduler (`service/AnalysisScheduler.java`). It is NOT the `ClaudeSynthesisService`.
- `ClaudeSynthesisService` is misnamed: it calls **Gemini 3.1 Pro Preview via the Vertex AI REST API** (`service/ClaudeSynthesisService.java:23,48,339-341`). It is the legacy synchronous path, now reachable only via `/api/intake/debug/synthesis` and `/api/intake/debug/gap-analysis` (`controller/IntakeController.java:540-585`) through `PipelineService.runSynthesisOnly/runGapAnalysisOnly` (`service/PipelineService.java:214-223`).
- Live models: `gemini-3.1-pro-preview` (4 of 5 synthesis LLM calls) and `claude-opus-4-7` via the Anthropic **Message Batches API direct** (the duplicate-merge call). Claude on Vertex is not used anywhere.
- A whole layer of deterministic rating machinery (`VasrdDecisionEngine`, `EvidencePreComputer`, `ConditionPromptTemplates`) is **dead code** — referenced only by `controller/StrategyDebugController.java:85-119`, never by the live state machine.

---

## 1. STAGES — ordered control flow

### 1.1 Trigger & outer loop

- `AnalysisScheduler.tick()` runs `@Scheduled(fixedDelay = va-claim.pipeline.poll-ms:15000, initialDelay 20000)` (`AnalysisScheduler.java:120-138`). It does `claimRepository.findAll()` **every tick over every claim in the DB** and calls `advanceClaim` per claim.
- Per claim gating (`advanceClaim`, `AnalysisScheduler.java:140-160`):
  1. `hasActiveSubscription(claim)` — paid gate; no subscription → nothing runs (`:164-169`, `User.hasActiveSubscription()` at `model/User.java:162`).
  2. Extraction state machine ticks if `extractionState != null && != COMPLETE`.
  3. Synthesis state machine ticks if `shouldRunSynthesis` (`:173-194`):
     - in-flight (`synthesisState != null`) → always tick;
     - else requires: not `synthesisInProgress`; ≥1 atom; **quiet period** — newest atom older than `va-claim.pipeline.quiet-seconds:30`; zero evidence rows in `pending/processing`;
     - then triggers when: never synthesized (`lastSynthesisAt == null`) OR `currentClaudeModel != lastSynthesisModel` OR newest atom is newer than `lastSynthesisAt`.
  4. Gap state machine ticks if `shouldRunGapAnalysis` (`:198-214`): synthesis finished at least once, not in progress, and (never ran / model changed / `lastSynthesisAt > lastGapAnalysisAt`).

### 1.2 Synthesis state machine (the system under redesign)

`SynthesisStateMachine` (`service/synthesis/SynthesisStateMachine.java`). State string-enum stored in `claims.synthesis_state` (`model/Claim.java:84`): `NONE → IDENTIFYING → MERGING → RATING → VERIFYING → COMPLETE` (`:35`). Exactly **one transition per scheduler tick** (`advance`, `:81-92`). Every stage is "submit async LLM job(s), record `ClaimPipelineJob` join rows, return; next tick checks `llmJobService.allSucceeded(...)`".

| Transition | Method | What happens | LLM purpose / fan-out |
|---|---|---|---|
| NONE→IDENTIFYING | `doIdentify` `:98-114` | Load ALL atoms for claim + service context + presumptive context (`EnhancedSynthesisOrchestrator.buildServiceContext/buildPresumptiveContext`, `EnhancedSynthesisOrchestrator.java:118-159`); submit 1 job; save `ClaimPipelineJob(stage="synthesis_identify")`; set `synthesisInProgress=true` | `synthesis_identify` ×1 (Gemini) |
| IDENTIFYING→MERGING | `doMergeIfReady` `:120-150` | Wait until all `synthesis_identify` jobs SUCCEEDED; parse JSON array of conditions (`ConditionIdentificationAgent.parseResponse`); **empty array → fast-path `markSynthesisComplete(claimId,"unknown")`** (`:135-140`); else submit merge job | `synthesis_duplicate_merger` ×1 (Claude batch) |
| MERGING→RATING | `doRateIfReady` `:156-197` | Re-fetch the identify result from `pjobs.get(0)` of stage rows; re-parse; parse merge decision and apply in Java (`DuplicateConditionMerger.parseResponse/applyMergeDecision`, `DuplicateConditionMerger.java:115-202` — index-based merge groups, confidence fusion `:205-213`, triad-evidence union `:216-236`, safety: unmentioned indices kept, parse failure returns originals); **persist `IdentifiedCondition` rows (no rating yet)**; fan out one rating job per condition with ALL atoms | `synthesis_rate` ×N (Gemini), `batchGroupKey = "synthesis_rate_"+claimId` |
| RATING→VERIFYING | `doVerifyIfReady` `:203-255` | Wait for all rate jobs; write `estimated_rating`, `rating_rationale`, `confidence` to each condition by `ClaimPipelineJob.conditionId`; build full condition summary; submit 1 verification job | `synthesis_verify` ×1 (Gemini) |
| VERIFYING→COMPLETE | `doCompleteIfReady` `:261-305` | Parse issues array; `EnhancedSynthesisOrchestrator.applyCorrections` (`EnhancedSynthesisOrchestrator.java:55-91`) flags `pyramiding` / `rating_mismatch` / `invalid_code`; **only `pyramid_flag` is persisted, and by positional list-index alignment** (`SynthesisStateMachine.java:287-295`); read the verify job's `modelName` and call `analysisScheduler.markSynthesisComplete(claimId, modelName)` which nulls `synthesisState`, sets `lastSynthesisAt/lastSynthesisModel` (`AnalysisScheduler.java:218-227`) | — |

After COMPLETE, gap analysis fires on the next tick (`lastSynthesisAt > lastGapAnalysisAt`). `GapStateMachine` (`service/gap/GapStateMachine.java:33,72-76`) is a sibling machine: `NONE → EVIDENCE_GAPS (N× gap_evidence, Claude batch) → VALIDATING (N× gap_validation, Claude batch) → WHATIF (N× gap_whatif, Gemini) → COMPLETE`, fanning out per condition — same submit/poll pattern.

### 1.3 Async job plumbing (sync vs async)

Nothing in the pipeline ever blocks on a model:

- `LlmJobService.submit()` persists a `QUEUED` `llm_jobs` row with the full serialized request payload and returns a UUID (`service/llm/LlmJobService.java:51-81`).
- `LlmJobSubmitter.tick()` every `va-claim.llm.submitter.poll-ms:3000`, up to `batch-size:50` rows via `SELECT ... FOR UPDATE SKIP LOCKED` (multi-instance safe), groups by `(provider, batchGroupKey)` so Anthropic gets one HTTP POST per group; QUEUED→SUBMITTED; **3 submit attempts then FAILED** (`service/llm/LlmJobSubmitter.java:40-92`).
- `LlmJobPoller.tick()` every `va-claim.llm.poller.poll-ms:15000`: polls each active providerJobId, fetches results when terminal, persists `responsePayload`, writes an `AiCallLog` cost row, and runs **orphan recovery** — any SUBMITTED row older than `va-claim.llm.orphan-deadline-min:30` is reset to QUEUED (`service/llm/LlmJobPoller.java:47-125`).
- State machines read results via `LlmJobService.getResult()` which throws `LlmJobFailedException` for FAILED jobs (`LlmJobService.java:95-103`) — but the machines gate on `allSucceeded()` first, which simply returns `false` for any non-SUCCEEDED job (`:112-115`).

**Failure handling gap:** there is no failure transition in either state machine. A single FAILED LlmJob (e.g. after the submitter's 3 attempts) makes `allSucceeded` false forever → the claim is **permanently wedged** in IDENTIFYING/MERGING/RATING/VERIFYING with `synthesisInProgress=true`, which also blocks any future synthesis trigger. The scheduler will tick the wedged stage every 15s indefinitely.

### 1.4 Legacy synchronous path (kept for reference)

`ClaudeSynthesisService.runSynthesis` (`:173-228`): one blocking Gemini call with a combined identify+rate prompt (the canonical-VASRD masterlist digest is included here — `:185,196` — unlike the async path), parse, save conditions. `runGapAnalysis` (`:233-302`): one blocking Gemini call, then `PyramidingRules.plan` + `VaMathService.calculateCombinedRating` (`:284-296`). 429 handling is `Thread.sleep(60_000)` + unbounded recursion (`:353-360`). Reached only from debug endpoints; `/debug/synthesis` is also the **only** place that deletes prior conditions (`IntakeController.java:552`).

`StrategyOrchestrator` is a deprecated stub that throws (`service/synthesis/StrategyOrchestrator.java:28-32`).

---

## 2. MODELS & PROMPTS

### 2.1 Providers and exact model ids

| Provider impl | API | Model (exact) | Where configured |
|---|---|---|---|
| `VertexGeminiAsyncProviderImpl` (`service/llm/VertexGeminiAsyncProviderImpl.java`) | **Vertex AI REST** `POST .../publishers/google/models/{model}:streamGenerateContent?alt=sse` (`:180-182`), ADC credentials, location `global` | `gemini-3.1-pro-preview` | `va-claim.gemini.model` ← `GEMINI_MODEL` (`resources/application.yml:51-54`); provider default `@Value` (`VertexGeminiAsyncProviderImpl.java:59`); router hardcoded fallback (`LlmProviderRouter.java:92`) |
| `AnthropicBatchProviderImpl` (`service/llm/AnthropicBatchProviderImpl.java`) | **Anthropic API direct** (NOT Vertex): `POST https://api.anthropic.com/v1/messages/batches`, `x-api-key: ${ANTHROPIC_API_KEY}`, `anthropic-version: 2023-06-01` (`:42-49,72-79`). Message Batches = 50% off list price, ≤24h SLA (`:25-26`) | `claude-opus-4-7` | **Hardcoded** in `LlmProviderRouter.defaultModelFor` (`LlmProviderRouter.java:91`). NB: `va-claim.claude.model` / `CLAUDE_MODEL` (`application.yml:55-57`) is NOT consulted by the router — `purposeDefaultModels` is never populated (`LlmProviderRouter.java:32,86`); the config value is only used by `AnalysisScheduler` for change detection (`AnalysisScheduler.java:81`) |
| Legacy sync `ClaudeSynthesisService` | Vertex AI REST `generateContent` (non-streaming) | `gemini-3.1-pro-preview`, `thinkingBudget 10240`, `temperature 0.2`, `maxOutputTokens 65536` (`ClaudeSynthesisService.java:42-56,327-332`) | `va-claim.gemini.*` |

### 2.2 Routing (purpose → provider), `LlmProviderRouter.java:40-52`

| Purpose | Provider | Effective model |
|---|---|---|
| `synthesis_identify` | vertex-gemini | gemini-3.1-pro-preview |
| `synthesis_duplicate_merger` | **anthropic-batch** | **claude-opus-4-7** |
| `synthesis_rate` | vertex-gemini | gemini-3.1-pro-preview |
| `synthesis_verify` | vertex-gemini | gemini-3.1-pro-preview |
| `gap_evidence`, `gap_validation` | anthropic-batch | claude-opus-4-7 |
| `gap_whatif` | vertex-gemini | gemini-3.1-pro-preview |
| `extraction_*` (6 purposes) | vertex-gemini | gemini-3.1-pro-preview |

Precedence: request `preferredProvider` → model-prefix (`claude-*`/`gemini-*`) → purpose table → vertex-gemini fallback (`:55-71`).

### 2.3 Batch vs realtime

- **Anthropic = true batch**: one `/v1/messages/batches` POST per `(provider, batchGroupKey)` group; poller checks `processing_status`, then downloads JSONL results keyed by `custom_id` = internal job UUID (`AnthropicBatchProviderImpl.java:55-199`).
- **Gemini = realtime streaming masquerading as async**: Vertex Batch Prediction was rejected (24h SLA + GCS I/O — comment at `VertexGeminiAsyncProviderImpl.java:24-28`). Instead, one **virtual thread per job** runs `streamGenerateContent` SSE; results buffer in an in-memory `ConcurrentHashMap` (`:51,82-94,134-251`). JVM restart loses in-flight results; orphan recovery requeues them after 30 min. **No batch pricing discount on the Gemini side.** Parameters: `temperature 0.2` hardcoded (`:169`), `thinkingConfig` only when `thinkingBudget > 0` (`:171-173`).
- Anthropic thinking mapping: `thinkingBudget > 0` → `thinking: {type:"adaptive"}` + `output_config.effort` low (<4k) / medium (<12k) / high (`AnthropicBatchProviderImpl.java:254-266`).

### 2.4 Prompts — where they live

All live prompts are Java text blocks compiled into agent classes:

- Identification: `ConditionIdentificationAgent.SYSTEM_PROMPT` (`ConditionIdentificationAgent.java:30-51`) — identify all conditions, triad assessment (diagnosis/in-service/nexus with STRONG/MODERATE/WEAK/MISSING), secondary/bilateral/TDIU hints, "DO NOT assign ratings", "Return ONLY the JSON array". User message = service context + presumptive context + **the full atom dump grouped by type** (`buildAtomSummary` `:107-123`). No canonical VASRD code list is provided (the legacy sync path injects a masterlist digest; the async path does not).
- Duplicate merge: `DuplicateConditionMerger.SYSTEM_PROMPT` (`DuplicateConditionMerger.java:30-68`) — definition of duplicate, "never merge bilateral / secondary-vs-primary", "WHEN IN DOUBT, DO NOT MERGE", index-based merge-decision JSON shape. `maxTokens 4096`, `thinkingBudget 6000` (`:24-25` → Claude adaptive thinking, effort=medium).
- Rating: `RatingAgent.SYSTEM_PROMPT` (`RatingAgent.java:35-63`) — exact rating set {0,10,...,100}, count-medication-courses instructions, hand-listed thresholds for asthma 6602 / PTSD 9411 / tinnitus 6260 / sleep apnea 6847 / GERD 7346, pro-veteran tie-break ("Do not under-rate"). User message = condition + VASRD criteria lookup (`lookupVasrdCriteria` `:132-151` from `VasrdDataService` — only ~35 codes in `resources/vasrd_codes.json`, else "use general VA rating principles") + a deterministic corticosteroid course pre-count (`buildMedicationSummary` `:153-185`) + **the full atom dump again** (`formatAtoms` `:187-193`).
- Verification: `SynthesisVerificationAgent.SYSTEM_PROMPT` (`SynthesisVerificationAgent.java:28-45`) — 5 checks (code validity, rating-severity match, triad sufficiency, missing secondaries, pyramiding), JSON issue array. Input is the conditions summary only — **the verifier never sees the atoms**, so "is there sufficient evidence" is judged from the conditions' own evidence lists.
- Dead richer prompts: `ConditionPromptTemplates` (`service/synthesis/ConditionPromptTemplates.java`) has per-VASRD-category rating and gap prompts (respiratory/mental-health/musculoskeletal/digestive/neurological) with `{inject actual criteria}` placeholders and next-threshold logic — referenced only by `StrategyDebugController`.

### 2.5 Structured output approach

Prompt-level only: "Return ONLY the JSON …", then `cleanJsonResponse` markdown-fence stripping + Jackson parse in every agent (e.g. `ConditionIdentificationAgent.java:125-131`). No JSON schema, no structured-output API, no function calling (the `tools` field exists on `LlmJobRequest` (`LlmJobRequest.java:22`) but no synthesis agent uses it). Parse-failure fallbacks are silent: identify → `List.of()` (`:101-104`, which then **fast-paths the claim to COMPLETE as if it had no conditions**), rate → `{estimated_rating:0, confidence:0.3}` (`RatingAgent.java:126-129`), verify → no issues (`SynthesisVerificationAgent.java:87-90`), merge → return originals (`DuplicateConditionMerger.java:198-201`).

### 2.6 Token/size limits

`LlmJobRequest` builder defaults `maxTokens 16_000`, `thinkingBudget 0` (`LlmJobRequest.java:83-84`). Merger overrides to 4096/6000. Gemini provider fallback `maxOutputTokens 65536` (unused — payload always carries the field). **No input-size management at all**: identify and every rate job embed the entire atom corpus; nothing truncates, chunks, retrieves, or caches.

---

## 3. DATA ARTIFACTS — what is persisted where

Everything is **Postgres** (Cloud SQL in prod, `application-cloud.yml`). GCS (`va-claim.gcs.bucket: vaclaim-documents`, `application.yml:49-50`) holds only the uploaded documents consumed by the extraction layer; synthesis never touches GCS.

| Artifact | Table / entity | Written by | Read by |
|---|---|---|---|
| Atoms (typed evidence facts: `type`, `value`, `source`, `timestamp`, `confidence`, `evidenceId`, `supersededBy`) | `atoms` / `model/Atom.java` | Extraction pipeline (`createdBy = "ai:gemini"`) | doIdentify, doRateIfReady (re-read fresh each stage), gap stages |
| LLM request/response payloads, status, tokens | `llm_jobs` / `model/LlmJob.java` (`requestPayload`/`responsePayload` JSON text, `status`, `providerJobId`, `batchGroupKey`, `attempts`, timestamps) | `LlmJobService.submit`, `LlmJobPoller` | State machines via `getResult`; identify result is re-parsed from this table in both MERGING and RATING stages |
| Stage↔job join | `claim_pipeline_jobs` / `model/ClaimPipelineJob.java` (`claimId`, `stage`, `llmJobId`, `conditionId`) | Each stage method | Each "IfReady" method; **never deleted** (delete methods exist on `ClaimPipelineJobRepository.java:29-34` but have zero callers) |
| Conditions | `identified_conditions` / `model/IdentifiedCondition.java` — name, vasrdCode, bodySystem, triad_* (JSON), isPresumptive/basis, estimatedRating, ratingRationale, confidence, gaps (JSON), whatIfScenarios (JSON), supersededBy, pyramidGroup/pyramidReason | Created in doRateIfReady (`SynthesisStateMachine.java:176-181`), ratings written in doVerifyIfReady, pyramid flags in doCompleteIfReady; gaps/what-ifs written by GapStateMachine | UI controllers, gap pipeline, chat |
| Pipeline state | `claims` columns: `extraction_state`, `synthesis_state`, `gap_state`, `synthesis_in_progress`, `last_synthesis_at/_model`, `last_gap_analysis_at/_model` (`model/Claim.java:57-88`) | State machines + `AnalysisScheduler.markSynthesisComplete/markGapAnalysisComplete` | `shouldRunSynthesis` / `shouldRunGapAnalysis` |
| Cost rows | `ai_call_logs` / `model/AiCallLog.java` (tokens incl. thinking + cache columns, per-component costs `precision 12, scale 8`, latency, `llmJobId`) | `LlmJobPoller.recordCost/recordError`, legacy `callGemini` | `AiCostService.checkCostAlerts`, `UsageService` |

**Reused vs recomputed:** atoms are reused (re-read) at every stage; the identify output is reused by re-parsing the stored job payload; conditions are persisted mid-pipeline and mutated in place by later stages. Nothing else is cached — no prompt caching, no embedding store, no intermediate evidence brief. Static reference data: `vasrd_codes.json` (~35 codes, `VasrdDataService.java:22-34`), hardcoded presumptive rules (`PresumptiveRulesService.java:14-88`), hardcoded 2024/2025 compensation tables (`VaMathService.java:15-51`).

---

## 4. INCREMENTAL BEHAVIOR — new document after a completed analysis

**Design intent: full re-run, no delta. Actual behavior: full re-run with three compounding defects.**

Flow when a user uploads a new document after COMPLETE:

1. Extraction produces new atoms → newest-atom timestamp moves.
2. After the 30s quiet period and extraction drain, `shouldRunSynthesis` fires via `latestAtom.isAfter(claim.getLastSynthesisAt())` (`AnalysisScheduler.java:193`) → `SynthesisStateMachine` starts again from NONE: **re-identifies over the entire atom corpus, re-merges, re-rates every condition, re-verifies**. There is no notion of "only the new evidence" anywhere.
3. Gap analysis then re-runs in full because `lastSynthesisAt > lastGapAnalysisAt` (`AnalysisScheduler.java:208`) — N more evidence-gap + N validation + N what-if jobs.

Defects in the re-run path (all verified by call-site search):

- **(a) Duplicate conditions accumulate.** Nothing in the async path deletes prior `identified_conditions`; the only `conditionRepository.deleteByClaimId` call in production code is in the debug endpoint (`IntakeController.java:552`). Run 2's doRateIfReady saves a fresh set of rows; doVerifyIfReady/doCompleteIfReady then operate on `findByClaimId` = old + new rows (`SynthesisStateMachine.java:230,275`). The dedup machinery that could mark `supersededBy` (`ConditionPostProcessService.deduplicateConditions`, `ConditionPostProcessService.java:44-90`) is itself only invoked from `/debug/post-process`.
- **(b) Stale stage rows poison re-runs.** `claim_pipeline_jobs` rows are never deleted, and the stage readers take `pjobs.get(0)` from an unordered `findByClaimIdAndStage` (`SynthesisStateMachine.java:129,167,172,270`). On run 2 the list contains run-1 rows first (PK order), so MERGING parses the **stale run-1 identify result**, and `allSucceeded` is evaluated over the union of both runs' job ids (a FAILED run-1 job blocks run 2 forever).
- **(c) Model-mismatch perpetual re-run loop.** `markSynthesisComplete` stores the verify job's model — `gemini-3.1-pro-preview` (`SynthesisStateMachine.java:297-303`), or `"unknown"` on the empty fast-path — while `shouldRunSynthesis` compares it against `va-claim.claude.model` = `claude-opus-4-7` (`AnalysisScheduler.java:81,192`). With default config the comparison is permanently unequal, so synthesis (and gap, via `:207`) re-triggers after every completion, each pass appending duplicates per (a) and burning spend. No test covers this (`src/test/java/com/afterduty/service/synthesis/SynthesisStateMachineTest.java` asserts state nulling only). Whether production exhibits the loop depends on the deployed `CLAUDE_MODEL`/`GEMINI_MODEL` env values; with the committed defaults it loops.

What invalidates what, in summary: new atom ⇒ entire synthesis invalid (full redo); synthesis completion ⇒ entire gap analysis invalid (full redo); configured-model change ⇒ both invalid. Nothing finer-grained exists; per-condition `estimated_rating`, triads, gaps and what-ifs are all recomputed wholesale, and user-visible condition IDs are not stable across runs (new rows each time).

---

## 5. COST — tracking, capping, per-run structure

### 5.1 Tracking

- Every completed/failed LlmJob → one `AiCallLog` row via `LlmJobPoller.recordCost/recordError` (`LlmJobPoller.java:127-165`); the legacy sync path logs directly in `callGemini` (`ClaudeSynthesisService.java:307-424`).
- `AiCostService.computeCosts` prices tokens from a **hardcoded table** "as of April 2026" (`AiCostService.java:22-32`): `gemini-3.1-pro-preview` $2/$12/$12 per MTok (in/out/thinking); `claude-opus-4-7` **$15/$75/$75**; unknown-model fallback $5/$15/$15. Two accuracy problems: current Anthropic list price for Opus 4.7 is $5/$25, and the Batches API the app actually uses is 50% of list — so logged Claude costs overstate real spend by roughly **6×**. Cache-token columns exist on `AiCallLog` (`:47-51`) but are never populated (no prompt caching in use).
- `UsageService.getCurrentUsage` sums `ai_call_logs.total_cost` per user per calendar month (`UsageService.java:29-66`).

### 5.2 Limiting

- **Per-user fair-use cap: $4.00/month** (`usage.limit-cents: 400`, enabled by default — `application.yml:23-25`, `config/UsageProperties.java:14-16`), applies to paid subscribers too (comment at `UsageService.java:37-41`). Enforced by `UsageGuard.assertCapacity` throwing `UsageLimitException` (`UsageGuard.java:18-24`).
- **Enforcement gap:** `UsageGuard` is wired only into `ChatService` and the legacy `PipelineService` (`ChatService.java:39`, `PipelineService.java:34`). The live async pipeline (`AnalysisScheduler` → state machines → `LlmJobService`) never checks the cap; its only gate is `hasActiveSubscription`. A claim with many conditions/documents — or the §4(c) re-run loop — can spend without per-user limit.
- **Global alerts are log-only:** WARN at >$5/day, ERROR at >$20/day total spend (`AiCostService.checkCostAlerts`, `:84-92`). Nothing throttles or stops.

### 5.3 Structural $-per-analysis

Per synthesis run: `1 identify (Gemini) + 1 merge (Claude Opus 4.7 batch) + N rate (Gemini) + 1 verify (Gemini)` = N+3 LLM calls. Per subsequent gap run: `N gap_evidence (Claude batch) + N gap_validation (Claude batch) + N gap_whatif (Gemini)` = 3N calls. Dominant cost drivers:

- Identify and **each of the N rating calls re-send the full atom corpus** → input tokens scale O((N+1) × atoms). A veteran with 8 conditions and a 30k-token atom dump pays ~270k Gemini input tokens for synthesis alone (~$0.54 input at $2/MTok) before outputs/thinking, then the gap fan-out on top.
- Gemini calls are realtime streaming — no batch discount; only the 1 merge + 2N gap Claude calls get batch pricing.
- No prompt caching anywhere despite the identical atom-dump prefix across the N rating calls (and across re-runs).
- The dead `VasrdDecisionEngine` was built to make deterministic ratings free (`tinnitus 6260, ED 7522, asthma 6602, sleep apnea 6847, knee 5260/5261, migraines 8100` — `VasrdDecisionEngine.java:46-66`), but the live path pays an LLM call for every condition including tinnitus.

---

## 6. STATIC vs DYNAMIC

**Static (fixed sequence):**
- The stage graph is a hardcoded enum walked one transition per 15s tick; stage count, order, and fan-out shape (1/1/N/1) never vary with the claim (`SynthesisStateMachine.java:81-92`).
- One fixed prompt per stage; no condition-category specialization in the live path (the specialized `ConditionPromptTemplates` are dead).
- Provider/model per purpose is a fixed table (`LlmProviderRouter.java:40-52`); per-request overrides exist (`preferredProvider/preferredModel`) but no synthesis caller uses them.
- VA math (combined rating, bilateral factor, VA rounding, compensation tables — `VaMathService.java:56-171`), presumptive eligibility (`PresumptiveRulesService`), and pyramiding caps (`PyramidingRules.java:61-123`) are deterministic Java.

**The only adaptive elements today:**
- Empty identify → fast-path COMPLETE (`SynthesisStateMachine.java:135-140`).
- The merge decision is LLM-made but applied/validated by Java with strong guardrails (index bounds, forgotten-index recovery, parse-failure = no-op — `DuplicateConditionMerger.java:121-202`).
- Verification issues feed a fixed 3-case `switch` of corrections (`EnhancedSynthesisOrchestrator.java:71-88`) — flags only; nothing re-rates or re-identifies. There is **no verify→fix loop**: one pass, then complete.

**Where a dynamic Claude-agent loop plausibly helps:**
1. **Identification with tools** — agent queries a full VASRD catalog / masterlist instead of free-recalling codes (today: no canonical list in the async prompt, 35-code lookup only at rating time).
2. **Evidence-scoped rating** — agent retrieves only the atoms relevant to a condition (tool over `atoms`) instead of the O(N×atoms) full-dump fan-out; could also call a deterministic-rating tool (resurrected `VasrdDecisionEngine`) first and skip the LLM where the schedule is mechanical.
3. **Closed-loop verification** — let the verifier re-open identification/rating for flagged conditions (today its `rating_mismatch`/`invalid_code` findings die as unpersisted map flags, §7.8).
4. **True incremental updates** — an agent diffing "new atoms since last run" against existing conditions could update/merge in place instead of the append-everything full redo of §4.
5. **Adaptive depth** — escalate thinking/effort or a second-opinion model only for low-confidence conditions, rather than uniform settings.

**Where dynamism would hurt:** the deterministic spine must stay code — combined-rating math, bilateral/pyramiding rules, presumptive matching, compensation tables (legal correctness, testability); cost/latency governance (the current system's fixed shape is at least predictable: N+3 calls); and auditability of what the veteran is shown (rating rationale tied to enumerated evidence).

---

## 7. WEAKNESSES (concrete, cited)

1. **Perpetual re-run loop from model bookkeeping mismatch.** `lastSynthesisModel` is recorded as the verify job's model (`gemini-3.1-pro-preview`, or `"unknown"` on fast-path) at `SynthesisStateMachine.java:297-303`, but compared against `va-claim.claude.model` (`claude-opus-4-7`) at `AnalysisScheduler.java:192` (gap: `:207`). With committed defaults the check is always true → synthesis+gap re-run after every completion, forever, multiplying spend and duplicates. Untested (`SynthesisStateMachineTest.java` covers state nulling only).
2. **Re-runs append duplicate conditions.** No `deleteByClaimId`/supersede in the async path (only `IntakeController.java:552` debug endpoint); doRateIfReady inserts a fresh condition set each run (`SynthesisStateMachine.java:176-181`) and later stages read old+new via `findByClaimId` (`:230,275`). Condition IDs are unstable across runs, breaking anything (scenarios, chat references) that holds an ID.
3. **Stale `claim_pipeline_jobs` rows poison re-runs.** Stage rows are never deleted (delete methods unused — `ClaimPipelineJobRepository.java:29-34`); readers take `pjobs.get(0)` of an unordered list (`SynthesisStateMachine.java:129,167,270`) so run 2 parses run 1's identify/merge/verify results, and `allSucceeded` spans both runs' jobs.
4. **No failure path in the state machines.** A FAILED job (3 submit attempts, `LlmJobSubmitter.java:80-88`, or batch entry error) leaves `allSucceeded=false` forever; claim wedges mid-pipeline with `synthesisInProgress=true`, blocking all future synthesis, with the scheduler uselessly ticking it every 15s.
5. **$4/month usage cap not enforced on the live pipeline.** `UsageGuard` only guards `ChatService`/legacy `PipelineService` (`ChatService.java:39`, `PipelineService.java:34`); `AnalysisScheduler`→`LlmJobService` path has no cap check — only log-only daily alerts (`AiCostService.java:84-92`).
6. **Cost accounting wrong for Claude.** Hardcoded `claude-opus-4-7` at $15/$75/MTok with no batch discount (`AiCostService.java:28`) vs actual $5/$25 list ÷2 for batches → ~6× overstatement, which also makes the (chat-side) $4 cap trip ~6× too early for Claude usage and corrupts any spend dashboards.
7. **Dead deterministic layer; every rating costs an LLM call.** `VasrdDecisionEngine` (free rule-based ratings, `VasrdDecisionEngine.java:46-66`), `EvidencePreComputer` (structured evidence briefs + criteria mapping, `EvidencePreComputer.java:52-108,533-556`), and `ConditionPromptTemplates` (per-category prompts) are referenced only by `StrategyDebugController.java:85-119` — even fixed-rating tinnitus goes to Gemini with the full atom dump.
8. **Verification corrections are mostly discarded and index-aligned.** `applyCorrections` writes `rating_review_needed`/`code_review_needed` onto transient maps that are never persisted; only `pyramid_flag` survives, and it's copied back via positional alignment of two separately-built lists (`SynthesisStateMachine.java:276-295`) — order skew silently mislabels conditions. The verifier also never sees atoms (`SynthesisVerificationAgent.buildRequest`), so its "insufficient evidence" check is circular.
9. **Fragile JSON contract with silent, destructive fallbacks.** Prompt-only JSON + fence-stripping in 4 agents; identify parse failure returns `[]` which **fast-paths the claim to COMPLETE as if the veteran had no conditions** (`ConditionIdentificationAgent.java:97-105` + `SynthesisStateMachine.java:135-140`); rate failure silently persists 0% (`RatingAgent.java:126-129`).
10. **Token waste by construction.** Full atom corpus duplicated in identify + each of N rate calls (O(N×atoms) inputs), no prompt caching (identical prefixes), no Gemini batch pricing, no input truncation/retrieval; plus `tick()` does `claimRepository.findAll()` over the whole tenant base every 15s (`AnalysisScheduler.java:120-129`).

Additional notes (beyond the top 10): rating-stage VASRD criteria coverage is ~35 codes (`vasrd_codes.json`) with a "use general principles" fallback (`RatingAgent.java:132-151`); the async identify prompt lacks the canonical-code masterlist digest the legacy path had (`ClaudeSynthesisService.java:185-201`); Gemini in-flight results are RAM-only → JVM restart costs a 30-min orphan delay per job (`VertexGeminiAsyncProviderImpl.java:30-35`, `LlmJobPoller.java:113-125`); `VaMathService` is injected into `SynthesisStateMachine` but unused (`SynthesisStateMachine.java:49,78`); pyramiding-aware combined rating (`PyramidingRules` + bilateral factor) only runs in the legacy sync gap path (`ClaudeSynthesisService.java:284-296`), not after async synthesis; legacy 429 handling is `Thread.sleep(60s)` + unbounded recursion on the request thread (`ClaudeSynthesisService.java:353-360`); `ClaudeSynthesisService`/`AnalysisScheduler.currentClaudeModel` naming actively misleads (both are Gemini-facing).
