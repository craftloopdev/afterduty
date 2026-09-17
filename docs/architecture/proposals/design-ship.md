# VA Claim Path — Architecture under the "Ship-first (pragmatic evolution)" lens

**Architect charter:** biggest accuracy + cost wins with the LEAST disruption to the existing Spring
pipeline (state machines, `LlmProviderRouter`, batch providers, Postgres artifacts). Evolve stages
in place. Add a dynamic orchestrator *only* where the static DAG demonstrably fails. Sequence as
shippable increments.

**Author:** panel architect (ship-first). **Date:** 2026-06-10.
**Verified against source** on branch `feat/next-web-foundation` (paths relative to
`spring-backend/src/main/java/com/afterduty/` unless noted) and against the four research drafts in
`docs/_review_drafts/research-*.md`.

---

## 0. Thesis (one paragraph)

The existing pipeline is **architecturally correct for this problem** and should be *kept*: a static
DAG (extract → synthesize → gaps → strategy) is exactly what Anthropic's and Google's own guidance
prescribe for an enumerable decomposition (research-agentic §1). What's wrong is almost entirely
**fixable in place**: it routes to the wrong models, pays full price for batch, re-runs everything on
every upload, accretes duplicate rows, has no failure path, sends PDFs as base64 text, and bolts chat
on as an uncached direct-Anthropic Opus loop. The single highest-leverage discovery from reading the
code: **`build.gradle.kts` already ships `com.anthropic:anthropic-java-vertex:2.18.0` and
`google-cloud-storage:2.45.0`** (build.gradle.kts:31-36) — both unused. So "move Claude to Vertex"
and "store docs in GCS" are *provider/wiring swaps behind interfaces that already exist*
(`LlmAsyncProvider`, `DocumentStorageService`), not rewrites. We keep the `LlmJob` substrate, the
three state machines, the `ClaimPipelineJob` join, the `AiCallLog` ledger, and the `$4` usage cap —
and we evolve each in 8 shippable increments. We add exactly **one** dynamic-agentic surface (Ask-AI
chat) and exactly **one** narrowly-scoped agent loop inside synthesis (self-repair on parse/verify
failure), because those are the only two places the static DAG demonstrably fails.

---

## 1. End-to-end pipeline (static vs dynamic-agentic, with WHY)

Stage IDs map to existing state-machine states so the diff is legible.

| # | Stage | Today | Proposed | Static / Dynamic | Why |
|---|-------|-------|----------|------------------|-----|
| 1 | **Upload + persist** (`IntakeController.upload`) | base64 envelope into `EvidenceItem.raw_content` (IntakeController.java:123-134) | write bytes to **GCS** via the already-wired `DocumentStorageService.uploadToGcs`; store `gcs_path` only; compute `content_hash` (already computed at :116) | **Static** | Deterministic I/O. Removes ~67 MB DB rows; enables Claude/Gemini native PDF + the 30 MB Vertex request cap path. |
| 2 | **Text-layer probe + classify** (NEW, folds into extraction entry) | filename-substring typing (GeminiExtractionService.java:124-139) | one cheap Gemini call per *new* doc: detect text layer (citable vs scanned) + classify doc_type (enum) + page count | **Static** | Closed task; classification feeds prompt selection and the citations OCR gate (research-agentic §4). |
| 3 | **Per-doc extraction → atoms/events** (`ExtractionStateMachine`) | 5 sequential Gemini passes over **all** docs every run, append-only (ExtractionStateMachine.java:120) | **map over the DELTA only** (new/changed docs); one structured-output Gemini call per doc producing typed atoms + events in a single schema; artifact keyed by `(content_hash, prompt_version, schema_version, model_id)` | **Static** | Enumerable per-doc transform = textbook Map stage (research-agentic §3). The collapse from 5 passes → 1 schema'd call is the biggest extraction-cost cut. |
| 4 | **Synthesis: identify → merge → rate → verify** (`SynthesisStateMachine`) | full re-run over all atoms; appends conditions; verify output mostly discarded | **incremental reduce** over compact atoms; supersede prior conditions; verify stays but gains a **bounded self-repair loop** | **Mostly static, ONE dynamic edge** | Identify/merge/rate are predictable → static. Verify→re-rate is the one place the DAG fails today (flags written then thrown away, SynthesisStateMachine.java:275-295): a ≤2-turn agent loop that re-rates only flagged conditions. |
| 5 | **Gap analysis: evidence-gaps → validate → what-if** (`GapStateMachine`) | full re-run per condition incl. stale rows; Opus batch | per-condition reduce, scoped to **conditions touched by the delta**; adversarial validate kept (it's good); what-if kept | **Static** | Per-condition fan-out is predictable. Adversarial generate→validate split already matches best practice (research-agentic §2). |
| 6 | **Strategy / scenarios** (`VaMathService`, ConditionController) | deterministic combined-rating calculator, no LLM | **unchanged** — keep deterministic | **Static (deterministic)** | 38 CFR 4.25/4.26 math must be exact; LLMs must not do arithmetic here (research-va §2). Already correct. |
| 7 | **Presentation / progress** | async path never writes `analysisStage/ProgressPct` (Claim.java) | state machines write progress on each transition | **Static** | Pure plumbing; fixes the "UI shows no progress" gap (orchestration W10). |
| 8 | **Ask-AI chat** (`ChatAgent`) | direct Anthropic Opus 4.7, no cache, no retrieval, mutates state (ChatAgent.java:36-59) | **single agent w/ tools on Vertex Claude Sonnet 4.6**, cache-first, grounded by hybrid pgvector retrieval over the veteran's corpus + analysis artifacts + VASRD KB | **Dynamic-agentic (single agent)** | Open-ended Q&A is the one genuinely unpredictable surface → agent. **Never multi-agent**: context-heavy + interdependent = Anthropic's documented anti-pattern, ~15x tokens (research-agentic §1). |

**Static/dynamic summary:** 7 of 8 stages stay static (the analysis core). Dynamic behavior is
confined to (a) Ask-AI chat as a single tool-using agent, and (b) a ≤2-turn self-repair micro-loop
inside synthesis-verify. Everything else keeps the predictable, batch-friendly, cost-deterministic
fan-out the current design already has — which is the property the charter says to preserve
(orchestration §6 "Where dynamism would hurt").

---

## 2. Model routing table (model · realtime/batch · caching)

All Claude via **Vertex AI global endpoint** (no 10% premium, newest models first, ADC auth — no
Anthropic key; research-vertex §2,§8). All Gemini already on Vertex. Prices are per-MTok from
research-vertex/agentic tables; **batch = 50% off**, **cache read = 0.1x input**.

| Stage / purpose | Model | Vertex ID | Realtime vs Batch | Caching strategy (stable prefix) |
|---|---|---|---|---|
| Doc classify + text-layer probe (stage 2) | **Gemini 3.1 Flash-Lite** | `gemini-3.1-flash-lite` | Realtime (fast, cheap) | none (tiny prompt) |
| Per-doc extraction (stage 3) | **Gemini 3 Flash Preview** | `gemini-3-flash-preview` | **Batch** for initial bulk ingest (50% off); **realtime** for single incremental upload (latency) | cache the *system+schema* prefix; doc bytes vary per call |
| Hard-doc extraction escalation | **Claude Haiku 4.5** | `claude-haiku-4-5@20251001` | Realtime | cache system+schema prefix |
| Synthesis identify / merge | **Claude Sonnet 4.6** | `claude-sonnet-4-6` | **Batch** (initial) / realtime (incremental) | cache `[system prompt + VASRD KB slice + presumptive context]`; volatile = atom digest |
| Synthesis rate (per condition) | **Claude Sonnet 4.6** | `claude-sonnet-4-6` | Batch (initial) / realtime (incremental) | **cache the atom-corpus block ONCE** with `cache_control`, fan out N rate calls reading it at 0.1x |
| Synthesis verify + self-repair re-rate | **Claude Opus 4.8** | `claude-opus-4-8` | Realtime (≤2 turns) | reuse cached atom block |
| Gap evidence / validation (per condition) | **Claude Sonnet 4.6** | `claude-sonnet-4-6` | **Batch** (50% off — non-interactive) | cache `[system + VASRD/DBQ rubric]`; volatile = condition+atoms |
| Gap what-if | **Claude Sonnet 4.6** | `claude-sonnet-4-6` | Batch | cache rubric prefix |
| Ask-AI chat | **Claude Sonnet 4.6** | `claude-sonnet-4-6` | **Realtime streaming** | cache `[system + analysis-artifact snapshot + retrieved chunks]`, 1h TTL; question last |
| Chat "deep dive / what's missing" escalation | **Claude Opus 4.8** | `claude-opus-4-8` | Realtime streaming | same cached prefix (escalation keeps prefix → cache survives) |
| Embeddings (KB + chunks) | **gemini-embedding-001 @ 768d** | — | Batch (backfill) / online (incremental) | n/a |

**Why this routing (ship-first justification):**

- **It's a map-population change, not a rewrite.** Today `LlmProviderRouter.purposeDefaults` is
  populated (provider routing works) but **`purposeDefaultModels` is empty** (LlmProviderRouter.java:32,
  86), so everything falls to hardcoded `claude-opus-4-7` / `gemini-3.1-pro-preview` defaults
  (:89-95). Populating `purposeDefaultModels` from config wires the entire table above. The
  `preferredModel`/`preferredProvider` fields on `LlmJobRequest` already exist and are unused by every
  caller — that's the per-call escalation override hook, free.
- **Gemini stays the extraction workhorse** (Flash tier $0.25–0.50/MTok in) because it's already the
  incumbent and 2–4x cheaper than Haiku for the high-volume map stage (research-agentic §8). Claude
  Haiku is the *escalation* target on abstention/validator failure, not the default.
- **Sonnet 4.6 (1M ctx, no long-context premium) replaces Opus 4.7 as the synthesis/gap default.**
  Opus 4.7 at $15/$75 in the current code (AiCostService.java:28) is **3x the real Sonnet price** and
  ~5x what we need for these tasks. Opus 4.8 is reserved for verify/self-repair and chat deep-dives.
- **Batch the non-interactive lifting at 50% off.** Initial 30-doc analysis and post-prompt-upgrade
  re-analysis are not latency-sensitive ("your analysis is updating") → Vertex Batch Prediction (GCS
  JSONL, ≤24h; research-vertex §6). The current `AnthropicBatchProviderImpl` already speaks "batch";
  we re-point it at Vertex batch. Single incremental uploads go realtime for UX.

**Caching as the core cost lever** (research-agentic §5, research-vertex §5): every per-condition
fan-out (rate, gap, validation) re-sends the full atom corpus today (O(conditions × atoms) tokens —
synthesis weaknesses W10). We instead place **one `cache_control` breakpoint on the atom-corpus
block** and fan out per-condition calls that read it at 0.1x. Stable-prefix discipline: system prompt
frozen (no timestamps/UUIDs), VASRD KB slice sorted deterministically, atom digest sorted by
`(doc_id, atom_id)`, volatile per-condition content last. Assert `cache_read_input_tokens > 0` in
`AiCallLog` monitoring — zero means a silent invalidator.

---

## 3. Incremental-update design (the headline feature)

This is the product's stated #1 requirement and the current system's worst failure (full redo +
duplicate accumulation across all five draft reports). The fix is the **DocETL map-reduce + artifact
keying** pattern (research-agentic §3) grafted onto the existing artifact tables.

### 3.1 Artifact graph (what depends on what)

```
EvidenceItem(gcs_path, content_hash)
        │  doc-level map (extraction)
        ▼
DocExtract  artifact, KEY = (content_hash, prompt_version, schema_version, model_id)
        │  ── compact: typed atoms + events for ONE doc, hundreds of tokens
        ▼
Atom / MedicalEvent rows  (tagged with source evidence_id + extract_key)
        │  reduce (synthesis identify/merge)
        ▼
IdentifiedCondition rows  (run_id, superseded_by)   ◄── condition_fingerprint
        │  per-condition reduce (rate, gap, validate, what-if)
        ▼
ratings + gaps(jsonb) + whatIfScenarios(jsonb)
        │  index for chat
        ▼
chunk rows (pgvector + tsvector)   ◄── per-doc, regenerated only for changed docs
```

### 3.2 What a new doc invalidates (precisely)

Replace today's timestamp-only triggering (orchestration §4) with **explicit dirty tracking** on
content hashes:

1. **New/changed doc D** → invalidates *only* `DocExtract(D)`. All other docs' extracts are reused
   (this is the cost win: 1 map call, not N). Implemented by filtering the extraction fan-out to
   `evidence WHERE extract_key IS NULL OR content_hash != extract_key.hash` instead of
   `findByClaimId` (ExtractionStateMachine.java:120).
2. New atoms from D → mark the claim `synthesis_dirty=true` with a **scope set** = condition
   fingerprints whose atoms changed (new condition candidates + conditions referencing D).
3. Synthesis **identify** runs over the *full* compact atom set (cheap — it's a digest, not raw
   docs), but **rate/gap/validate/what-if fan out only over conditions in the dirty scope** (delta),
   reusing prior per-condition artifacts for untouched conditions.
4. Conditions no longer in the identify output are marked `superseded_by` (not deleted) so chat
   history and citations stay valid.
5. Chat index: re-chunk + re-embed **only doc D**; insert rows; old chunks for D deleted by
   `evidence_id`. Instant freshness (pgvector row insert is queryable immediately; research-gcp §1.9).

### 3.3 The delta path (sequence)

```
upload(D) → GCS + content_hash
  → classify(D)                       [1 Gemini Flash-Lite call]
  → extract(D) → DocExtract(D)        [1 Gemini Flash call]   ← the ONLY per-doc cost
  → atoms/events(D) persisted, dedup by (evidence_id, atom natural key)
  → synthesis.identify (full digest)  [1 Sonnet call, cached KB prefix]
  → diff conditions → dirty scope S
  → for c in S: rate/gap/validate/whatif   [|S| fan-outs, cached atom block @0.1x]
  → re-embed(D) only
```

A new doc on a 30-doc claim costs **~1 extraction call + 1 identify + |S| per-condition calls**
(typically |S| = 1–3), versus today's **30 doc re-extractions + full synthesis + full gap re-run**.

### 3.4 Consistency guarantees (keep the crash-safe substrate)

- **Idempotency / dedup**: atoms get a natural key `(evidence_id, type, normalized_value, span)`;
  `saveAtoms` becomes upsert-on-key (fixes append-only duplication, extraction W3). Re-running a doc's
  extract is a no-op if `content_hash` unchanged.
- **Versioned supersede, never delete**: `IdentifiedCondition.run_id` + `superseded_by` (the column
  already exists, set today only on the legacy path — orchestration data-artifacts table). The async
  gap machine starts honoring `WHERE superseded_by IS NULL` (fixes synthesis W2/W4, gap W3).
- **Stale-job hygiene**: `deleteByClaimIdAndStage` exists but is never called (gap W2) — call it at the
  start of each stage run so readers stop parsing prior-run `ClaimPipelineJob` rows (fixes the
  `pjobs.get(0)`-of-unordered-list bug across synthesis/gap).
- **Crash safety preserved**: the DB-persisted `LlmJob` queue + state columns already survive restarts
  (the only hole is the Vertex in-memory result map, fixed in §7 increment 1). Delta scope lives in a
  `claim_dirty_scope` table so a mid-delta crash resumes deterministically.
- **Single-flight**: add a `SELECT … FOR UPDATE SKIP LOCKED` claim-level advisory lock in
  `advanceClaim` so two Cloud Run instances can't double-fan-out (fixes orchestration W6). The
  `LlmJobSubmitter` already uses SKIP LOCKED — we mirror the pattern.

---

## 4. Chat system (retrieval, KB ingestion, latency, streaming, cost)

### 4.1 Grounding design — hybrid pgvector, NOT a managed search product

Per research-gcp §1.3/§1.8 and §3.1: **pgvector on the existing Cloud SQL instance** ($0 marginal
infra, instant freshness, per-veteran isolation via `WHERE user_id` hardenable with RLS). Skip Vertex
AI Search (~$150–800/mo + storage) and Vector Search (~$68/mo always-on) at this scale.

Three retrieval sources, fused per turn:

1. **Veteran's own corpus** — chunk rows produced *by the existing extraction pass* (reuse
   `GeminiExtractionService`/`EventSegmentationAgent`; research-gcp §3.3), section-aware (decision-
   letter headings, DBQ questions, STR encounters), `gemini-embedding-001 @ 768d` (fits pgvector HNSW
   2,000-dim limit), metadata `(doc_type, doc_date, condition, page_span)`.
2. **Analysis artifacts** — conditions, ratings, gaps, what-ifs as structured context blocks (cached).
3. **VA KB** — 38 CFR Part 4, presumptives, DBQ rubrics (§4.2).

**Retrieval query** = single SQL: pgvector HNSW ANN + `tsvector websearch_to_tsquery` full-text,
merged with **Reciprocal Rank Fusion** (k≈60). Hybrid is mandatory here because claims chat is dense
with exact tokens — diagnostic codes ("DC 5260"), form numbers ("VA Form 21-4138"), dates
(research-gcp §1.8). Optional later: Vertex Ranking API reranker at $1/1k (works with any retriever).

### 4.2 KB ingestion (VASRD / M21-1 / presumptives)

Per research-va: build on the **free eCFR versioner API** (38 CFR Part 4 = 1.06 MB XML, 202 sections,
no key). Pipeline (one-time + nightly cron):

- Nightly `titles.json` check → `/versions` diff for parts 3+4 → re-ingest **only changed sections**
  (the Feb-2026 publish-then-rescind of §4.10 proves stale snapshots become *wrong*, research-va).
- Parse rating tables into **structured records** (diagnostic_code → rating% → criteria) for
  deterministic display + the 4.25/4.26 math — retrieval alone can't do this reliably.
- Public DBQs become the **gap-analysis rubric** (claimed condition → DBQ → required measurements).
- Every chunk carries its citation + "current as of" date; chat surfaces it. Citation-first UX is
  table stakes (competitors V2V/VeteranAI already advertise it, research-va).

### 4.3 Latency budget (target < 2.5 s to first token)

| Step | Budget |
|---|---|
| Embed query (Vertex) | 50–150 ms |
| Hybrid pgvector + RRF (in-instance) | 15–30 ms (research-gcp §1.9) |
| Build prompt, cache-prefix hit | ~0 (read-only) |
| Sonnet 4.6 first token (streaming, cached prefix) | ~500–900 ms |
| **Total to first token** | **~1.5 s** |

Stream tokens to the Next.js BFF over SSE (the web foundation already proxies SSE). Cache prefix at 1h
TTL so follow-ups in a session read the case file at 0.1x.

### 4.3a Chat billing fix (correctness, not just cost)

Today `ChatAgent` writes **no `AiCallLog`** (spend invisible to cap + dashboards) and free owners can
chat (CHAT scope bypasses subscription; llm-cost-chat W3). Fix: route chat through the `LlmJob`/
`AiCallLog` ledger like every other call, and gate chat on the paid tier. This makes the $4 cap
actually mean something.

### 4.4 Cost per chat session (arithmetic)

Assume a session = 8 questions over a 30-doc / 300-page case file. Working context = **compact
artifacts + top-12 retrieved chunks ≈ 25K input tokens** (NOT the raw 300 pages — that's the
condensed-context hybrid, research-agentic §7). Output ≈ 600 tokens/answer.

- **Turn 1** (cache write, 1h TTL, Sonnet 4.6 $3/$15, write 2x): 25K × $3 × 2 / 1e6 = **$0.150** in +
  0.6K × $15 / 1e6 = $0.009 out → **$0.159**.
- **Turns 2–8** (cache read 0.1x on the 25K prefix; ~1K volatile question tokens at full): each ≈
  (25K × $3 × 0.1 + 1K × $3 + 0.6K × $15) / 1e6 = $0.0075 + $0.003 + $0.009 = **$0.0195**.
- **Session total** = $0.159 + 7 × $0.0195 = **≈ $0.30 per 8-question session.**
- Query embeddings: 8 × ~$0.000008 = negligible. pgvector retrieval: $0.

At $11.99/mo, a heavy user doing ~40 sessions/month ≈ **$12 of chat** — so chat *is* the unit-economics
risk and caching is what keeps it viable (without the cache, turns 2–8 cost $0.084 each → $0.74/
session → ~$30/mo; caching is a **~2.5x** session saving). Escalating a hard question to Opus 4.8
keeps the *same* cached prefix, so only output and the volatile question cost more.

---

## 5. Accuracy mechanisms

Layered, matched to the ship-first posture (add the cheap high-leverage ones first):

1. **Structured outputs everywhere extraction/synthesis emits JSON** (`output_config.format`
   json_schema, `additionalProperties:false`, enums for doc_type/body_system/diagnostic_code). Today
   every agent parses prompt-engineered JSON and **returns empty on failure** — an unparseable identify
   response fast-paths synthesis to COMPLETE as if the claim has zero conditions (synthesis W9,
   SynthesisStateMachine.java:135-140). Schema enforcement + checking `stop_reason != max_tokens`
   eliminates the silent-empty failure class. (research-agentic §4.)
2. **First-class abstention fields** (`not_found` / `insufficient_evidence`) in every extraction
   schema, routed to escalation (Gemini Flash → Haiku → Sonnet) rather than silently dropping data.
3. **Self-repair micro-loop** in synthesis-verify (the one dynamic edge): verify already runs but its
   flags are discarded (synthesis W8). Wire flags → re-prompt the ≤2 flagged conditions with the
   correction note → re-rate. Bounded turns = bounded cost.
4. **Adversarial gap validation** — already present and good (GapValidationAgent's RVSR-rater persona);
   keep it. Matches the generate-for-coverage / verify-for-precision pattern (research-agentic §2).
5. **Citations** on the *narrative* surfaces (chat answers, the "why this gap" explanation). Two-pass
   split is mandatory because **citations are incompatible with structured outputs (400 error)**
   (research-agentic §4): pass A = structured extraction; pass B = cited narrative over the same cached
   doc. Server-validated page pointers → ~0% source hallucination (research-agentic §2). Gate: scanned/
   no-text-layer PDFs aren't citable → the stage-2 text-layer probe flags them for OCR-or-disclaimer.
6. **Eval harness before any prompt/model change ships**: golden set of 20–50 synthetic/redacted case
   files with expected extractions + known gaps; LLM-judge (different model family — Gemini judging
   Claude output, or vice versa) with debiasing rubric lines; run in CI on every prompt/model/schema
   bump (research-agentic §6). The no-legal-advice tone boundary is itself a judge criterion. Start at
   ~20 cases — catches most regressions.
7. **Cost accounting fix** so the cap is honest: apply the **50% batch discount** in `AiCostService`
   (currently overstates Claude ~2x, all drafts), populate cache-token columns, attribute extraction
   spend to the real `userId` (today `userId(null)`, extraction W6), and enforce the cap on the
   scheduler path (today only at intake/chat — orchestration W2).

---

## 6. Cost model (real per-MTok prices, full arithmetic)

Prices (research-vertex / research-agentic, per MTok): Gemini 3 Flash $0.50/$3 (batch $0.25/$1.50);
Gemini 3.1 Flash-Lite $0.25/$1.50; **Sonnet 4.6 $3/$15** (batch $1.50/$7.50, cache read $0.30);
**Opus 4.8 $5/$25**; gemini-embedding-001 $0.12/MTok batch. Assume **30 docs / 300 pages**, ~600 text
tokens/page ⇒ **~180K corpus tokens**; ~12 conditions; ~8 with gaps.

### 6.1 Initial analysis (one-time, batched at 50% off)

| Component | Calc | Cost |
|---|---|---|
| Classify 30 docs (Flash-Lite, realtime) | 30 × ~1.5K in × $0.25/1e6 | $0.011 |
| Extract 30 docs (Gemini 3 Flash, **batch**) — in = corpus 180K + 30×2K sys, out ≈ 30×3K atoms | (240K×$0.25 + 90K×$1.50)/1e6 | $0.195 |
| Synthesis identify (Sonnet **batch**, full atom digest ≈ 60K in, 8K out) | (60K×$1.50 + 8K×$7.50)/1e6 | $0.150 |
| Synthesis merge (Sonnet batch, 20K in, 4K out) | (20K×$1.50 + 4K×$7.50)/1e6 | $0.060 |
| Rate 12 conds (Sonnet batch, atom block **cached once** then 0.1x; per call ≈ 60K cached-read + 2K vol in, 1.5K out) | 12 × (60K×$0.30 + 2K×$1.50 + 1.5K×$7.50)/1e6 ≈ 12×$0.032 | $0.387 |
| Verify + self-repair (Opus 4.8 realtime, 60K cached-read + 8K out) | (60K×$0.50 + 8K×$25)/1e6 | $0.230 |
| Gap evidence+validate+whatif: 8 conds × 3 calls (Sonnet batch, cached atom + rubric, ~3K vol in, 2K out each) | 24 × (60K×$0.30 + 3K×$1.50 + 2K×$7.50)/1e6 ≈ 24×$0.0375 | $0.900 |
| Embed corpus for chat (180K tokens, batch) | 180K×$0.12/1e6 | $0.022 |
| **Total initial analysis** | | **≈ $1.96** |

Without the model swap + batch + caching, the same workload on the current Opus-4.7-priced,
full-resend, no-batch path is **~$15–25** per analysis (and re-paid on every upload). **~10x cut.**

### 6.2 Incremental doc (one new doc on the 30-doc claim)

| Component | Calc | Cost |
|---|---|---|
| Classify + extract 1 doc (Flash, realtime) | (~8K in + 3K out)×Flash | $0.013 |
| Synthesis identify over full digest (Sonnet realtime, 62K in, 8K out) | (62K×$3 + 8K×$15)/1e6 | $0.306 |
| Re-rate/gap for dirty scope |S|≈2 conds × ~4 calls (Sonnet, cached atom @0.30, ~3K vol, 2K out) | 8 × (60K×$0.30 + 3K×$3 + 2K×$15)/1e6 ≈ 8×$0.057 | $0.456 |
| Re-embed 1 doc | ~6K×$0.15/1e6 | $0.001 |
| **Total incremental** | | **≈ $0.78** |

(Realtime, not batch, for UX. Identify dominates because it reads the whole digest; if we make identify
itself delta-aware — only re-identify when a *new candidate condition* appears — incremental drops to
~$0.30.) Versus today's **full re-run ≈ another $15–25**. **~20–30x cut.**

### 6.3 Chat session

From §4.4: **≈ $0.30 per 8-question session** (cache-first Sonnet). Opus deep-dive turn ≈ +$0.18 each.

### 6.4 Per-veteran envelope (sanity vs $11.99/mo)

Initial $1.96 + say 5 incremental docs/mo × $0.78 = $3.90 + 10 chat sessions × $0.30 = $3.00 ⇒
**~$8.86/veteran in month 1**, **~$6.90/mo steady-state**. Within the $11.99 price and the $4 *fair-use
cap* (which caps the abusive tail, not the median). The cap should be raised to ~$8 with batch
accounting fixed, or the median user never approaches it.

---

## 7. Migration path (8 shippable increments) + risks

Ordered by leverage-per-disruption. Each is independently shippable and reversible. No state-machine
rewrite; every change is behind an existing interface or a config flag.

**Increment 0 — Cost-accounting truth (½ day).** Apply 50% batch discount in `AiCostService`; attribute
extraction `AiCallLog` to the real `userId`; enforce the cap on the scheduler path. *Why first:* makes
every later cost claim measurable and stops the runaway-spend loop's blast radius.

**Increment 1 — Kill the perpetual re-run loop + wedge bug (½ day).** Fix the model-name mismatch
(stamp `lastSynthesisModel` from config, not the Gemini verify job; AnalysisScheduler.java:192 vs
SynthesisStateMachine.java:298-303) and add a FAILED→error transition so one bad job doesn't brick a
claim forever (W5 across all drafts). *Why:* these two bugs alone are likely the dominant live cost and
reliability problem. Also make the Vertex provider persist results (not in-memory ConcurrentHashMap,
W7).

**Increment 2 — Move Claude to Vertex (1–2 days).** Add a `VertexAnthropicProviderImpl` using the
**already-present** `anthropic-java-vertex:2.18.0` (build.gradle.kts:32), global endpoint, ADC auth;
register it in `LlmProviderRouter`. Keep `AnthropicBatchProviderImpl` behind a flag for rollback. Drop
`ANTHROPIC_API_KEY` once verified. *Risk:* per-model batch support — Vertex batch confirmed through
Opus 4.7 + Sonnet 4.6 + Haiku 4.5; **Sonnet 4.6 (our default) is confirmed** (research-vertex Open Q1).

**Increment 3 — Model routing table (½ day).** Populate `purposeDefaultModels` from config so the §2
table is live (Sonnet 4.6 for synthesis/gap, Opus 4.8 for verify/chat-escalation). Pure config; instant
~3x cost cut on synthesis/gap by leaving Opus-4.7 pricing.

**Increment 4 — GCS + structured outputs + native PDF (2–3 days).** Wire `uploadToGcs` at intake
(client already on classpath), store `gcs_path`, stop base64-in-Postgres; switch extraction to one
schema'd structured-output call per doc with abstention fields. Collapses 5 passes → 1 and fixes the
silent-empty failure class.

**Increment 5 — Incremental delta (3–4 days, the headline).** Add `extract_key` content-hash artifact;
filter extraction fan-out to changed docs; atom upsert-on-key; condition supersede + dirty-scope
table; gap machine honors `superseded_by`; call `deleteByClaimIdAndStage`. This is the largest change
but stays *inside* the existing state machines — it changes *which rows* they fan out over, not their
shape.

**Increment 6 — Prompt caching (1–2 days).** Add `cache_control` breakpoints (atom-corpus block for
the rate/gap fan-out; KB/rubric prefixes). Assert `cache_read_input_tokens > 0`. The per-condition
fan-out cost cut is large here.

**Increment 7 — Chat rebuild (4–5 days).** Single-agent Sonnet 4.6 on Vertex, cache-first, grounded by
pgvector hybrid retrieval (`CREATE EXTENSION vector`; embeddings via Vertex), VASRD KB ingest cron,
citations on narrative, route through `AiCallLog`, gate on paid tier, SSE streaming to the BFF.

**Increment 8 — Eval harness (2–3 days, gate further changes).** 20–50 golden case files, cross-family
LLM judge, CI on prompt/model/schema bumps.

### Risks (max 5)

1. **Vertex batch model coverage** — Opus 4.8 / Fable 5 batch support unconfirmed in the research
   snapshot (Sonnet 4.6 confirmed). Mitigation: default batch to Sonnet 4.6; run Opus realtime only.
2. **Incremental correctness** — supersede/dirty-scope logic is the subtlest change; a bug could hide
   or duplicate conditions. Mitigation: ship behind a flag, validate against the eval golden set
   (increment 8) before default-on; keep the full-rerun path as fallback.
3. **Quota / 429s on Vertex global endpoint** at launch traffic (research-vertex §9). Mitigation:
   file QPM/TPM increase pre-launch; client-side retry with backoff (replacing today's unbounded 429
   recursion, llm-cost-chat W9); batch absorbs bursts.
4. **Citations gating on scanned PDFs** — no-text-layer records can't be cited (research-agentic §4).
   Mitigation: stage-2 probe flags them; OCR via Gemini or surface a "couldn't cite source" disclaimer.
5. **Tokenizer inflation on 4.7+/4.8** (~35% more tokens for same text, research-vertex §4) skews the
   §6 estimates upward for Opus stages. Mitigation: estimates already keep Opus to verify + chat
   deep-dives; re-baseline from real `AiCallLog` token counts after increment 0.

---

## Appendix — verified source anchors (load-bearing)

- `build.gradle.kts:31-36` — **`anthropic-java-vertex:2.18.0` + `google-cloud-storage:2.45.0` already
  on classpath, unused** (de-risks Vertex Claude + GCS migration to wiring, not new deps).
- `LlmProviderRouter.java:32,86-95` — `purposeDefaults` populated (provider routing works);
  `purposeDefaultModels` empty → falls to hardcoded `claude-opus-4-7`/`gemini-3.1-pro-preview`.
- `LlmProviderRouter.java` `resolveProvider`/`resolveModel` — `preferredProvider`/`preferredModel`
  override hooks exist on `LlmJobRequest`, no caller sets them (clean per-call escalation lever).
- `AnthropicBatchProviderImpl.java:45,57,73-74` — direct `api.anthropic.com` + `x-api-key` (the thing
  to replace with Vertex).
- `ChatAgent.java:36-59` — direct Anthropic `/v1/messages`, `claude-opus-4-7`, no cache, no retrieval,
  mutates state via tools, no `AiCallLog`.
- `IntakeController.java:116,123-134` — `content_hash` already computed; bytes base64'd into
  `raw_content`; `DocumentStorageService` injected but `uploadToGcs` not called at upload.
- `application.yml:24,48,50,54,57,64` — `limit-cents:400`, `vertex.claude-region:global`,
  `bucket:vaclaim-documents`, Gemini/Claude model env keys, orphan-deadline.
- `ExtractionStateMachine.java:120` — `findByClaimId` (no delta filter) = the re-extract-everything
  root cause.
- Synthesis/Gap `superseded_by` + `deleteByClaimIdAndStage` exist, async path ignores them.
