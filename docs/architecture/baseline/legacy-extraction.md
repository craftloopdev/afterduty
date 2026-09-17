# Legacy Document-Extraction Pipeline — Engineering Map (as of 2026-06-10)

Scope: the "Layer 1" document → atoms extraction part of the Spring analysis pipeline
(`spring-backend/src/main/java/com/afterduty/service/extraction/` + the async LLM job
infrastructure in `service/llm/`). Baseline for redesign as a dynamic Claude-agent workflow on
Vertex AI. All paths relative to `spring-backend/src/main/java/com/afterduty/` unless noted.

---

## 1. STAGES — ordered control flow

### 1.1 Trigger: upload

- `controller/IntakeController.java:90-157` — `POST /api/intake/evidence` (multipart).
  Validates ≤50 MB (:102), SHA-256 dedup per claim (:115-118 via
  `service/DocumentStorageService.java:86-102`), then stores the file **as a base64 envelope
  inside Postgres** `evidence_items.raw_content`:
  `"CONTENT_ENCODING: base64\nFILE_TYPE: …\nFILENAME: …\n\n<base64>"` (:123-127, :134).
  GCS is *not* written on this path — `DocumentStorageService.uploadToGcs` has **zero callers**
  (only `downloadFromGcs` at `IntakeController.java:276` for legacy rows with a `gcsPath`).
- JSON variant `:161-202` stores `req.getContent()` verbatim; quick-add `:212-243` stores plain
  text (`source_type="quick_add"`).
- Claim is flipped to `EXTRACTING` synchronously for banner UX (:143-146), then
  `pipelineService.processEvidence(evidenceId, claimOwnerId)` runs `@Async`.

### 1.2 Enqueue: `service/PipelineService.java:61-123`

- Usage-cap check `usageGuard.assertCapacity(userId)` (:72); on `UsageLimitException` the
  evidence is parked as `processing_status="deferred_usage_limit"` (:74-79).
- Otherwise the evidence is **immediately marked `processed` / "Queued for async extraction"**
  (:97-101) — before any extraction has happened — and the claim gets
  `synthesisNeeded=true`, `extractionState="NONE"` if currently null (:104-108).
- No LLM work happens here. The literal string `"NONE"` is the restart signal.

### 1.3 Driver: `service/AnalysisScheduler.java`

- `@Scheduled` `tick()` every `va-claim.pipeline.poll-ms` (default **15 s**, initial 20 s)
  (:120-138) iterates **all claims** (`claimRepository.findAll()`, :125).
- Per claim (:140-160): subscription gate first (:141, :164-169 — **free users' queued docs are
  never extracted**); then `extractionStateMachine.advance(claim)` whenever
  `extractionState != null && != "COMPLETE"` (:144-149); synthesis and gap state machines tick
  in the same pass (extraction does not block them structurally — synthesis is held off instead
  by the atom quiet-period, :180-193).

### 1.4 State machine: `service/extraction/ExtractionStateMachine.java`

Hand-rolled, state persisted as a string in `claims.extraction_state`; **at most one transition
per tick** (:26-35). Order (:41-50, switch :94-107):

```
NONE → EXTRACTING_DIAGNOSIS → EXTRACTING_MEDICATIONS → EXTRACTING_SERVICE_RECORDS
     → EXTRACTING_ATOMS → EXTRACTING_SEGMENTS → EXTRACTING_EVENTS → COMPLETE (stored as null)
```

- **startStage** (:113-151): for each of the first five stages, fans out **one `LlmJobRequest`
  per `EvidenceItem` of the claim** — `evidenceItemRepository.findByClaimId(claim.getId())`
  (:120) i.e. *all* evidence, not just new — and records a `ClaimPipelineJob` join row per job
  (:133-138, model `model/ClaimPipelineJob.java`).
- **EXTRACTING_EVENTS** (:153-190) instead fans out one job per `MedicalEvent` with
  `extracted=false` (:154). Because `ClaimPipelineJob` has no eventId column, **`evidenceId` is
  stored as a proxy for the event** (:182-183 comment).
- **advanceIfReady** (:196-217): stage advances only when `llmJobService.allSucceeded(jobIds)`
  over **all `ClaimPipelineJob` rows ever written for (claim, stage)** (:198, :206-210). Then
  `parseStageResults` (:233-291) parses every job's stored result and persists atoms/events,
  and `transitionTo(next)` starts the next stage.
- **parseState** (:367-395): `null` means COMPLETE if any extraction pipeline-job rows exist for
  the claim, else NONE — prevents spontaneous restarts after completion.
- `saveAtoms` (:349-365) appends `Atom` rows with `createdBy="ai:extraction-…"`, default
  confidence 0.8, **no dedup of any kind**.

### 1.5 Async LLM job layer (`service/llm/`)

- `LlmJobService.submit` (:52-81) persists a `QUEUED` `LlmJob` row with the **full request
  payload JSON** (system prompt + user message + maxTokens + thinkingBudget) in Postgres and
  returns a UUID; never blocks on the model.
- `LlmJobSubmitter.tick()` every 3 s (`va-claim.llm.submitter.poll-ms`, batch-size 50,
  `application.yml:58-61`), multi-instance-safe via `SELECT … FOR UPDATE SKIP LOCKED`
  (`LlmJobSubmitter.java:20-23`). Groups by `(provider, batchGroupKey)` (:48-53) — group keys
  are `extraction_<stage>_<claimId>`. Submit failure → retry next tick; **after 3 attempts the
  job is permanently FAILED** (:79-89).
- `LlmJobPoller.tick()` every 15 s (`va-claim.llm.poller.poll-ms`): polls provider, persists
  `responsePayload` on success, writes an `AiCallLog` cost row per terminal job (:86-104,
  :127-147). **Orphan recovery**: any job stuck `SUBMITTED` > `va-claim.llm.orphan-deadline-min`
  (default 30 min) is requeued to QUEUED (:113-125) — this is what survives JVM restarts since
  the Vertex provider holds results in memory.

### 1.6 Failure semantics (today)

- A job that ends `FAILED` (3 failed submits, Gemini "no text content", batch entry error) is
  terminal. `advanceIfReady` checks `allSucceeded` — not `allTerminal` — so **one FAILED job
  freezes the claim in that stage forever**; there is no failure transition, stage timeout, or
  per-job retry-after-failure (`ExtractionStateMachine.java:207`; `allTerminal` exists unused at
  `LlmJobService.java:106-109`).
- JSON parse failures in any extractor return `List.of()` silently while the job stays
  SUCCEEDED — the pipeline advances with the atoms lost (e.g. `GeminiExtractionService.java:182-186`,
  `EventSegmentationAgent.java:186-190`).

### 1.7 Orphaned/dead pieces

- `ExtractionOrchestrator.java` — merge/dedup helpers are `private` and have **no callers**;
  the class is dead code (:25-50). Cross-extractor dedup therefore never runs.
- `CrossEventAggregator.java` (medication courses, PHQ-9/PCL-5 score trends, dose-change
  detection, :45-73) is **not part of the automated pipeline** — only reachable via debug
  endpoint `POST /api/claim/debug/aggregate-events` (`controller/EventPipelineController.java:172-179`).
- Debug endpoints `/debug/segment-evidence`, `/debug/extract-events` still call the deprecated
  synchronous methods which now `throw UnsupportedOperationException`
  (`EventPipelineController.java:79-85` → `EventSegmentationAgent.java:64-78`,
  `EventExtractionAgent.java:90-94`) — broken paths.

---

## 2. MODELS & PROMPTS

### 2.1 Routing & models

- `service/llm/LlmProviderRouter.java:47-52`: all six extraction purposes
  (`extraction_diagnosis`, `extraction_medication`, `extraction_service_record`,
  `extraction_atom`, `extraction_event_segment`, `extraction_event`) default to
  **`vertex-gemini`**.
- Default model: **`gemini-3.1-pro-preview`** — `${GEMINI_MODEL:gemini-3.1-pro-preview}`
  (`application.yml:51-54`), mirrored in `LlmProviderRouter.java:92` and
  `VertexGeminiAsyncProviderImpl.java:59`.
- Anthropic side: **`claude-opus-4-7`** via the **Anthropic Message Batches API direct**
  (`${ANTHROPIC_API_KEY}`, `AnthropicBatchProviderImpl.java:42-49, 72-79`;
  `LlmProviderRouter.java:91`) — used only by synthesis/gap purposes
  (`synthesis_duplicate_merger`, `gap_evidence`, `gap_validation`, router :41-45).
  **Extraction never touches Claude today.**
- Vertex execution model (`VertexGeminiAsyncProviderImpl.java:21-39`): explicitly *not* the
  Vertex Batch Prediction API ("24h SLA … too slow"). Instead a **virtual thread per job**
  calls `…aiplatform.googleapis.com/v1/projects/{p}/locations/{l}/publishers/google/models/{m}:streamGenerateContent?alt=sse`
  (:180-183) with ADC credentials (:67-76). So it's realtime streaming dressed as an async job.
  Results buffered in an in-memory `ConcurrentHashMap` (:51) → lost on restart (recovered via
  orphan requeue). **Temperature hardcoded 0.2** (:169); `thinkingConfig.thinkingBudget`
  forwarded (:171-173).

### 2.2 Prompts (all Java string constants — no template files, no versioning)

| Stage | Prompt location | maxTokens | thinkingBudget |
|---|---|---|---|
| extraction_diagnosis | `DiagnosisExtractorService.java:28-42` | 16 384 (:64) | 0 |
| extraction_medication | `MedicationExtractorService.java:28-43` | 16 384 (:68) | 0 |
| extraction_service_record | `ServiceRecordExtractorService.java:27-46` | 16 384 (:68) | 0 |
| extraction_atom (generic) | `GeminiExtractionService.java:33-82` + doc-type prompts | 65 536 (:106) | 8 192 (`${va-claim.gemini.thinking-budget:8192}`, :27-28) |
| extraction_event_segment | `EventSegmentationAgent.java:33-53` | 32 768 (:96) | 2 048 (:31) |
| extraction_event | `EventExtractionAgent.java:145-180` + per-event-type table :44-79 | 32 768 (:113) | 8 192 (:37) |

- Doc-type specialization is **filename-substring matching** (`GeminiExtractionService.java:123-140`):
  "health summary"/"blue button" → health_summary, "imo"/"nexus"/**"letter"** → imo_letter,
  "dd214" → dd214, "dbq"/"questionnaire" → dbq, "personal"/"statement"/"buddy" →
  personal_statement; prompts in `DocumentTypePrompts.java:21-118` with a shared SELF_CHECK
  suffix (:11-19). Content is never classified; `evidence_items.ai_classification` is never
  populated.
- **Structured output approach: prompt-only.** Every prompt ends "Return ONLY the JSON array,
  no other text"; parsers strip ``` fences and Jackson-parse into `List<Map<String,Object>>`
  (e.g. `GeminiExtractionService.java:142-177`). No Gemini `responseSchema`/JSON mode, no tool
  use, no retry-on-malformed.
- **No input-size management**: the whole `rawContent` is inlined into a single user message
  for each of the 5 per-evidence stages (`ExtractionStateMachine.java:298-313`); no chunking,
  truncation, or token counting anywhere.

---

## 3. DATA ARTIFACTS (what is persisted where)

All in **Postgres** (Cloud SQL; `application-cloud.yml:1-13`). GCS
(`${GCS_BUCKET:vaclaim-documents}`, `application.yml:49-50`) is plumbed in
`DocumentStorageService` but unused by the live upload path.

| Artifact | Table / model | Written by | Notes |
|---|---|---|---|
| EvidenceItem | `evidence_items` (`model/EvidenceItem.java`) | `IntakeController` | `raw_content` TEXT holds the doc (base64 envelope for binary uploads); `file_hash` SHA-256 dedup; `gcs_path`, `anthropic_file_id`, `ai_classification`, `ai_extracted_data` columns effectively vestigial; `processing_status` lifecycle: pending → processing → processed / error / deferred_usage_limit |
| LlmJob | `llm_jobs` (`model/LlmJob.java`) | `LlmJobService` / poller | Full request payload **and** full response payload JSON persisted per call; status QUEUED/SUBMITTED/IN_PROGRESS/SUCCEEDED/FAILED; never garbage-collected |
| ClaimPipelineJob | `claim_pipeline_jobs` (`model/ClaimPipelineJob.java`) | state machine | (claim, stage) → llmJobId; `deleteByClaimIdAndStage` exists (`repository/ClaimPipelineJobRepository.java:29`) but **no production caller** — rows accumulate forever |
| Atom | `atoms` (`model/Atom.java`) | `saveAtoms` (`ExtractionStateMachine.java:349-365`) | type/value/source/confidence/string timestamp; `createdBy` distinguishes `ai:extraction-diagnosis|medication|service-record|atom|event` and `ai:aggregator`; `superseded_by` column exists, never set by extraction; append-only |
| MedicalEvent | `medical_events` (`model/MedicalEvent.java`) | `EventSegmentationAgent.parseResponse` (:108-119) | `raw_text` is only a marker stub `"[start: …] ... [end: …]"` (:173-175) — actual span matching "deferred"; `page_range` never populated; `extracted`/`atom_count` set by `EventExtractionAgent.parseResponse` (:125-137) |
| AiCallLog | `ai_call_logs` (`model/AiCallLog.java`) | `LlmJobPoller.recordCost/recordError` (:127-165) | tokens + computed $ per call, keyed to llm_job_id, claim_id, evidence_id, user_id |
| IdentifiedCondition / gaps / scenarios | downstream | synthesis & gap state machines | consume atoms; out of extraction scope |

**Reused vs recomputed:** the only "already done" marker extraction respects is
`MedicalEvent.extracted=true`. Atoms, segmentation results, and per-evidence stage outputs are
never reused — see §4.

---

## 4. INCREMENTAL BEHAVIOR — new doc after a completed analysis

Today it is a **full re-run with compounding duplication**, not a delta:

1. After completion, `extractionState` is null. New upload →
   `PipelineService.processEvidence` sets it back to `"NONE"` (`PipelineService.java:106-108`).
2. Next tick, `startStage(EXTRACTING_DIAGNOSIS)` fans out jobs for **every `EvidenceItem` of the
   claim** (`ExtractionStateMachine.java:120` — `findByClaimId`, no "already extracted" filter).
   All previously processed documents go through all four per-evidence extractors *and*
   segmentation again. Their atoms are appended **again** (no dedup) → duplicate atoms per
   re-run.
3. Compounding bug: `advanceIfReady` reads **all** `ClaimPipelineJob` rows for (claim, stage)
   including rows from previous runs (:198), and `parseStageResults` re-fetches those old
   SUCCEEDED results and re-saves their atoms too (:233-291). So run N re-ingests results of
   runs 1…N-1 *in addition to* re-extracting everything. Nothing ever deletes old pipeline rows.
4. Segmentation re-creates `MedicalEvent` rows for every evidence → duplicate events; only the
   new (duplicate) `extracted=false` ones get event-level extraction (:154).
5. Downstream invalidation: new atoms make `latestAtom > lastSynthesisAt`, so after a 30 s quiet
   period (`va-claim.pipeline.quiet-seconds`) **synthesis fully re-runs**, then gap analysis
   re-runs because synthesis is newer (`AnalysisScheduler.java:173-214`). Model-change is the
   other invalidator (`lastSynthesisModel`/`lastGapAnalysisModel` vs configured Claude model).
6. Cost shape: every additional upload costs ≈ (5 × all-docs) + per-event Gemini calls, plus a
   full Claude synthesis+gap pass — extraction cost grows superlinearly with library size.

---

## 5. COST tracking & caps

- **Per-call accounting**: `LlmJobPoller.recordCost` → `AiCostService.recordCall`
  (`service/AiCostService.java:40-54`) computes input/output/thinking cost from a **hardcoded
  price table per 1M tokens** (:23-32): `gemini-3.1-pro-preview` $2/$12/$12,
  `gemini-2.5-pro` $1.25/$10/$10, `gemini-2.5-flash` $0.30/$2.50/$2.50,
  `claude-opus-4-7` and `-4-6` $15/$75/$75, `claude-sonnet-4-6` $3/$15/$15,
  `claude-haiku-4-5` $0.80/$4/$4; unknown model fallback $5/$15/$15 (:64-65).
- **Global alerts (log-only)**: daily spend > $5 warn, > $20 error (:84-92).
- **Per-user monthly cap**: `usage.limit-cents: 400` = **$4/user/month**, enabled
  (`application.yml:23-29`). `UsageService.getCurrentUsage` sums
  `totalCostByUserIdInPeriod` (`UsageService.java:49-65`;
  `repository/AiCallLogRepository.java:25-27` — `WHERE a.userId = :userId`). Enforced by
  `UsageGuard.assertCapacity` at evidence-enqueue time (`PipelineService.java:72-80`, defers the
  doc) and at `runFullPipeline` entry (:164); deliberately **not** re-checked mid-pipeline to
  avoid rolling back already-paid AiCallLog rows (:181-186 comment).
- **Cap leak (structural)**: every extraction `LlmJobRequest` is built with `.userId(null)`
  (`ExtractionStateMachine.java:177, 327`), so extraction `AiCallLog` rows have `user_id NULL`
  and are **invisible to the $4 cap query**. The heaviest spender (full documents × 5 stages ×
  re-runs) is uncapped per-user; only `totalCostByClaimId` and the global daily alert see it.
- **Per-run figures**: no dollar constants in code; `PipelineVerifierService.collectMetrics`
  sums AiCallLog per claim and logs `cost=$…` after each full pipeline
  (`PipelineService.java:198-205`, `PipelineVerifierService.java:232-271`). Structurally, one
  document ≈ 5 Gemini-3.1-Pro calls over the full text (+1 per segmented event), at realtime
  streaming prices — the Anthropic 50%-off batch discount (`AnthropicBatchProviderImpl.java:26`)
  never applies to extraction, and Vertex Batch was explicitly rejected for latency
  (`VertexGeminiAsyncProviderImpl.java:24-29`).

---

## 6. STATIC vs DYNAMIC

**Static (everything, essentially):**
- Fixed 6-stage sequence hardcoded in a switch (`ExtractionStateMachine.java:97-106`); same
  pipeline for a 300-page Blue Button export, a DD-214, and a one-line quick-add. The four
  per-evidence stages have **no data dependency on each other** (only SEGMENTS→EVENTS does) yet
  run sequentially, adding ≥4 scheduler round-trips of latency for nothing.
- Every doc gets every extractor — a DD-214 still pays a medication-extraction call; a
  quick-add sentence pays all five.
- Stage prompts, token budgets, and model choice are compile-time constants.

**The only adaptivity present:**
1. Filename-substring prompt selection (`GeminiExtractionService.java:123-140`) — brittle,
   content-blind ("letter" → IMO prompt).
2. Per-event-type focus instructions (`EventExtractionAgent.java:44-79`).
3. Purpose→provider/model routing table (`LlmProviderRouter.java:39-53`) — one-line vendor swap.

**Where a dynamic Claude-agent loop would plausibly help:**
- Content-based document classification + *choosing which extractors are relevant* (kills the
  5×-calls-per-doc fixed cost).
- Chunking / long-doc planning and real event-span resolution (replacing the marker-stub
  `rawText` hack).
- Self-repair on malformed JSON (today: silent atom loss) and selective re-extraction of only
  the new document (today: full re-run).
- Dedup/merge of new atoms against the existing atom set (today: dead code).

**Where it would hurt / must be preserved:**
- The Postgres-backed job table + poll-based resumability is the system's real strength
  (multi-instance safe, restart-tolerant via orphan recovery). An in-memory agent loop would
  reintroduce the exact fragility the Vertex provider already exhibits
  (`VertexGeminiAsyncProviderImpl.java:30-35`).
- Cost governance: an open-ended agent loop needs *better* per-call attribution than today
  (the userId=null leak), plus hard per-run budgets — the current cap machinery is checked only
  at entry points.
- Auditability of atoms (educational tool, VSO-facing): deterministic stage provenance
  (`createdBy`, `source`) is valuable; an agent design needs equivalent structured tracing.

---

## 7. WEAKNESSES (concrete, cited)

1. **Binary uploads are fed to Gemini as base64 text.** Multipart uploads store a
   `CONTENT_ENCODING: base64` envelope in `raw_content` (`IntakeController.java:123-127,134`);
   `ExtractionStateMachine.buildRequestForStage` inlines `ev.getRawContent()` verbatim as the
   "document text" (:298-300). `DocumentStorageService.decodeUploadContent` (:150-164) has **no
   callers**, and there is no PDF text-extraction dependency in `build.gradle.kts` (no
   pdfbox/tika/Document AI). PDF/image uploads are effectively garbage-in for all five stages.
2. **New upload triggers a full re-run that also replays prior results.** All evidence is
   re-extracted (`ExtractionStateMachine.java:120`) *and* stale `ClaimPipelineJob` rows from
   earlier runs are re-parsed and their atoms re-saved (:198, :233-291;
   `deleteByClaimIdAndStage` never called) → atom duplication compounds with every upload, then
   feeds duplicate-inflated synthesis.
3. **One FAILED LLM job wedges the claim permanently** — `advanceIfReady` requires
   `allSucceeded` (:207) with no failure transition, stage timeout, or re-dispatch of failed
   jobs (unused `allTerminal` at `LlmJobService.java:106-109`). The scheduler then ticks the
   stuck claim every 15 s forever.
4. **EXTRACTING_EVENTS never sees real event text.** Segmentation stores only marker stubs
   `"[start: …] ... [end: …]"` as `rawText` ("text matching deferred",
   `EventSegmentationAgent.java:161-175`), so `EventExtractionAgent.buildUserContent`
   (:182-199) extracts atoms from a 1-2 sentence summary + name lists — a near-duplicate of
   EXTRACTING_ATOMS at extra cost and lower fidelity.
5. **Job↔event association is arbitrary.** `evidenceId` is used as an event proxy because
   `ClaimPipelineJob` lacks an event field (`ExtractionStateMachine.java:182-183`); result
   parsing matches "first still-unextracted event with that evidenceId" (:274-287), so with
   multiple events per document, atoms can be attributed to the wrong event.
6. **Extraction spend bypasses the $4/user/month cap**: requests are built with
   `.userId(null)` (`ExtractionStateMachine.java:177,327`) while the cap query filters
   `WHERE a.userId = :userId` (`AiCallLogRepository.java:25-27`) — the costliest pipeline
   phase is uncapped per user.
7. **Silent atom loss on malformed JSON.** All parsers catch exceptions and return an empty
   list while the job remains SUCCEEDED (e.g. `GeminiExtractionService.java:182-186`,
   `DiagnosisExtractorService.java:154-158`); no JSON schema/structured-output mode, no repair
   retry — extraction quality failures are invisible.
8. **Filename-substring document typing** (`GeminiExtractionService.java:123-140`): any file
   containing "letter" gets the IMO prompt; content classification never happens and
   `ai_classification` stays null.
9. **No token/size management**: full document inlined into 5 separate prompts; 50 MB upload
   limit with base64 inflating ~33%; context overflow surfaces only as a generic provider error
   feeding weakness #3. Sequential stage ordering also wastes wall-clock (≥1 scheduler tick per
   stage, 15 s each, even for a one-line quick-add).
10. **Orphaned machinery & misleading status**: dedup helpers dead
    (`ExtractionOrchestrator.java:25-50` — never invoked), `CrossEventAggregator` reachable only
    from a debug endpoint (`EventPipelineController.java:172-179`), debug segment/extract
    endpoints call deprecated methods that throw (:79-85); evidence is marked `processed`
    *before* extraction runs (`PipelineService.java:97-101`), making the scheduler's in-flight
    check (`AnalysisScheduler.java:187-188`) and the user-facing status both unreliable.

### Secondary observations
- Vertex provider holds results in memory only; restart = 30 min orphan-deadline stall before
  re-pay/re-run (`VertexGeminiAsyncProviderImpl.java:30-35`, `LlmJobPoller.java:38-39`).
- `AnalysisScheduler.tick()` loads `claimRepository.findAll()` every 15 s — O(all claims) per
  tick (:124-131).
- Free-tier users: upload path sets claim status `EXTRACTING` at 5% progress
  (`IntakeController.java:143-146`) but the scheduler's subscription gate means extraction
  never runs — the claim can present as perpetually "extracting".
- Temperature is a hardcoded 0.2 for all Gemini calls (`VertexGeminiAsyncProviderImpl.java:169`).
- `spring.main.allow-circular-references: true` is required to boot the scheduler/state-machine
  graph (`application.yml:4-9`) — a wiring smell worth removing in the redesign.
