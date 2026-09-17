# VA Claim Path — Architecture under the "Cost-first" Lens

**Architect charter:** Minimize $ per veteran while keeping results good enough to genuinely help.
Aggressive batch processing, prompt caching with stable prefixes, cheap-model triage with selective
escalation, artifact reuse, embedding-based dedupe. Lowest defensible cost per initial analysis and per
incremental update; an explicit, accepted quality floor.

**Date:** 2026-06-10. All per-MTok prices cited from the verified research drafts
(`research-vertex-claude.md`, `research-agentic-patterns.md`, `research-gcp-rag.md`,
`research-va-knowledge.md`). Legacy claims verified against source where load-bearing (file:line cited).

**Headline:** A static map-reduce DAG over per-document artifacts keyed by content hash, every model
call routed to the cheapest model that clears a measured quality floor (Gemini Flash-Lite extraction →
Sonnet 4.6 synthesis, Opus 4.8 only on escalation), every repeated token served from a stable-prefix
cache or the 50%-off batch lane — so the initial analysis of ~30 docs lands near **$0.32**, an
incremental doc near **$0.012**, and a chat session near **$0.013**.

---

## 0. The cost thesis (why this lens lands where it does)

The single most expensive thing the legacy system does is **re-pay for work it already did**. Three
verified defects make today's cost scale with *corpus size on every interaction* instead of with the
*delta*:

1. **Perpetual re-run loop.** `AnalysisScheduler.java:192,207` compares `currentClaudeModel`
   (`claude-opus-4-7`, from `va-claim.claude.model`) against `claim.getLastSynthesisModel()` /
   `getLastGapAnalysisModel()`, which the state machines stamp from the **Gemini** verify/whatif job
   (`gemini-3.1-pro-preview`). The strings never match, so synthesis + gap re-run after *every*
   completion on *every* subscribed claim, forever. This is unbounded spend with zero new input.
2. **Append-only, full-corpus re-extraction.** A new upload sets `extractionState=NONE`
   (`PipelineService.java:106-108`); extraction then fans out over **all** evidence items
   (`ExtractionStateMachine.java:120 findByClaimId`, no pending filter) with append-only `saveAtoms`
   (no dedupe) and re-parses never-deleted prior-run `ClaimPipelineJob` rows. Atoms and conditions
   multiply every upload. Every per-condition rate/gap/validation job then re-sends the **entire** atom
   corpus (O(conditions × atoms) input tokens).
3. **No caching, no batch discount, wrong model everywhere.** `purposeDefaultModels` is declared but
   never populated (`LlmProviderRouter.java:32`), so everything resolves to `defaultModelFor` —
   `claude-opus-4-7` ($5/$25) for merge/gap and `gemini-3.1-pro-preview` ($2/$12) for extraction. The
   cheapest viable extraction model (Gemini Flash-Lite, $0.25/$1.50) is 8× cheaper on input and isn't
   used. Cache token columns are never populated. The Anthropic batch path exists but `AiCostService`
   doesn't even model its 50% discount (`AiCostService.java:23-32`).

The cost-first redesign is therefore not primarily about picking cheaper models — it's about **never
recomputing an artifact whose inputs didn't change**, then routing each remaining call to the cheapest
model that clears a quality floor, then serving every repeated byte from cache or batch. The model
choice is the last 20% of the win; the artifact graph is the first 80%.

### Accepted quality floor (stated up front, because cost-first must)
- **Extraction:** Gemini 3.1 Flash-Lite ($0.25/$1.50) per document, *with* a deterministic validator
  (dates parse, page refs in range, enums closed) and a first-class `insufficient_evidence` abstention
  field. Floor = field-level extraction accuracy ≥ 0.95 on the golden set, measured. Any doc that trips
  the validator or abstains escalates to Gemini 3 Flash ($0.50/$3) then Sonnet 4.6 — *per document*,
  not per corpus. I accept Flash-Lite's lower raw IQ because the work (pull structured facts from a
  typed form) is the easy, high-volume map stage where a 5× price spread matters most
  (`research-agentic-patterns.md` §8).
- **Synthesis + gap (the reasoning that decides what a veteran is told):** Claude Sonnet 4.6
  ($3/$15, 1M ctx) as the default, **not** Opus. Opus 4.8 ($5/$25) is reserved for claims that
  escalate (low judge score, abstention, conflicting evidence). Floor = synthesis judge score ≥ 0.8
  and zero un-cited factual claims. I do **not** accept Haiku for synthesis — the reasoning over
  service-connection theories is where wrong is harmful, and the volume is low (1 synthesis per
  delta), so the price spread doesn't justify the IQ drop.
- **Chat:** Sonnet 4.6, retrieval-grounded, cache-first. Floor = answer cites a real source
  (CFR §, M21-1 §, decision ID, or the veteran's doc page) or explicitly abstains.

This floor is **enforced by evals as CI gates**, not asserted (§5). If a cheaper model later clears the
floor on the golden set, the router config flips a string — no code change.

---

## 1. End-to-end pipeline (static vs dynamic-agentic, with why)

The core analysis is a **known decomposition** → static DAG. Anthropic's and Google's guidance both say
agentic routing over an enumerable pipeline adds cost and variance for nothing
(`research-agentic-patterns.md` §1). Dynamic/agentic behavior is reserved for the two genuinely
open-ended surfaces: chat and the "what's still missing / go find it" investigation.

| # | Stage | Mode | Model lane | Why this mode |
|---|---|---|---|---|
| 1 | **Ingest** (upload → GCS, text-layer probe, page count, content hash) | **Static** | none (no LLM) | Pure I/O + a cheap PDF text-layer check. Hash is the cache key for everything downstream. No reason to spend a token. |
| 2 | **Parse / OCR** (born-digital → native text free on Gemini 3; scanned/no-text-layer → transcribe) | **Static** | Gemini 3.1 Flash-Lite (transcription) only when no text layer | Deterministic branch on the text-layer probe. Gemini 3 extracts embedded PDF text token-free (`research-gcp-rag.md` §1.6); only scans cost anything. |
| 3 | **Classify doc-type** (decision letter / DBQ / C&P / STR / DD-214 / lay statement) | **Static** | Gemini 3.1 Flash-Lite, enum-constrained | Closed value space → structured output with `const`/enum. Replaces today's filename-substring guess (`GeminiExtractionService.java:123-140`). One cheap call, cached by hash. |
| 4 | **Extract (MAP)** — per-doc structured facts: conditions, dates, ratings, in-service events, nexus statements, measurements | **Static, batchable** | Gemini 3.1 Flash-Lite (escalate per-doc → 3 Flash → Sonnet on validator-fail/abstain) | The embarrassingly-parallel, high-volume, easy stage. Map-reduce shape (`research-agentic-patterns.md` §3). Each artifact keyed by `(content_hash, prompt_version, schema_version, model_id)`; computed **once, ever**. |
| 5 | **Chunk + embed** (section-aware chunks for retrieval, reusing the extraction pass) | **Static** | gemini-embedding-001 @ 768 dims (MRL) | Chunking rides the same Gemini pass that extracts (`research-gcp-rag.md` §1.7); embeddings are a rounding error (~$135 for the whole worst-case corpus). |
| 6 | **Merge / case-model (REDUCE)** — dedupe conditions across docs, resolve to one case model | **Static** | Sonnet 4.6; cheap embedding+rules dedupe *first* | Reads **compact extractions** (hundreds of tokens/doc), never raw docs. Embedding-cosine + exact-token dedupe collapses duplicates *before* the LLM sees them, so the LLM merge is small and cheap. |
| 7 | **Synthesize** — conditions the veteran might qualify for, theory of entitlement (direct/secondary/aggravation/presumptive), plain-language rating estimate | **Static** | Sonnet 4.6 (escalate → Opus 4.8 on low judge score) | Enumerable: identify → rate → verify. Grounded in CFR/M21-1 KB (cached prefix). |
| 8 | **Gap analysis** — what evidence is missing, mapped to the DBQ rubric for each condition | **Static** | Sonnet 4.6, adversarial verifier pass | DBQ-as-checklist is deterministic scaffolding (`research-va-knowledge.md` §4). Generator reports all candidate gaps (coverage); a second precision pass filters (`research-agentic-patterns.md` §2). |
| 9 | **Strategy / "exactly what to do"** — concrete next actions, what-if rating deltas | **Static** | deterministic VaMath + Sonnet narrative | Combined-rating math (§4.25/4.26) is **deterministic** — the existing `VaMathService` already does it with zero LLM. Only the plain-language wrapper is a model call, and it's cached. |
| 10 | **Presentation** — render artifacts to the Next.js BFF | **Static** | none | Read from Postgres. No LLM at render time. |
| 11 | **Chat ("Ask AI")** | **Dynamic-agentic** (single agent + tools) | Sonnet 4.6, cache-first, hybrid retrieval | Open-ended Q&A — can't enumerate steps. **Single** agent with tools (retrieve-evidence, retrieve-CFR, get-condition), never multi-agent: the task is context-heavy and interdependent — Anthropic's explicit multi-agent anti-pattern and ~15× token cost (`research-agentic-patterns.md` §1). |
| 12 | **"Find what's missing" deep-dive** (optional paid action) | **Dynamic-agentic** (single agent) | Sonnet 4.6 (+ web search tool for VA rule lookups) | Genuinely open-ended investigation: decide which DBQ/CFR/BVA lookups to run. Capped tool-call budget. Rare → cost-bounded by frequency, not by being cheap per call. |

**Why almost everything is static:** every dynamic stage is one we *can't* enumerate. Everything we can
enumerate (the whole analysis core) is static, because a static DAG with programmatic checkpoints is
cheaper, more debuggable, cacheable, and batchable. Agentic loops cost ~4–15× the tokens
(`research-agentic-patterns.md` §1) — the cost lens spends that premium only where the alternative is
"can't do the task."

---

## 2. Model routing table (model · realtime/batch · caching)

Lane assignment follows the floor in §0. Prices are Vertex global-endpoint = Anthropic list (no
regional premium), verified in `research-vertex-claude.md` §4 and `research-gcp-rag.md` §1.6.

| Stage | Model (Vertex ID) | In / Out per MTok | Realtime vs Batch | Caching / stable prefix |
|---|---|---|---|---|
| Parse (scanned only) | `gemini-3.1-flash-lite` | $0.25 / $1.50 | **Batch** (init) / realtime (single new doc) | none (per-doc unique) |
| Classify | `gemini-3.1-flash-lite` | $0.25 / $1.50 | Batch (init) / realtime (delta) | system+enum schema cached (10% of input on Gemini) |
| Extract (MAP) | `gemini-3.1-flash-lite` → esc. `gemini-3-flash` ($0.50/$3) → `claude-sonnet-4-6` | $0.25/$1.50 base | **Batch** for initial 30-doc ingest (50% off → $0.125/$0.75); realtime for a single new doc | **stable extraction-instruction + schema prefix** cached across all docs in a batch; only the doc text varies (after the breakpoint) |
| Embed | `gemini-embedding-001` @ 768d | $0.15/1M ($0.12 batch) | Batch (init) / realtime (delta) | n/a |
| Merge dedupe (pre-LLM) | none (pgvector cosine + exact-token) | $0 | realtime | n/a |
| Merge (REDUCE) | `claude-sonnet-4-6` | $3 / $15 | realtime (small input) | CFR/presumptive KB + system prompt cached (0.1× reads) |
| Synthesize identify+rate+verify | `claude-sonnet-4-6` → esc. `claude-opus-4-8` ($5/$25) | $3/$15 base | realtime (delta) / **batch** for fleet re-analysis after a prompt/model bump | **[ frozen system prompt + CFR Part 4 rating tables + M21-1 slice + presumptive KB ] → cache_control breakpoint → [ case-model extractions ] → [ task ]** |
| Gap analysis + adversarial verify | `claude-sonnet-4-6` | $3/$15 | realtime (delta) / batch (fleet) | same cached KB prefix + DBQ rubric for the condition's body system |
| Strategy math | deterministic `VaMathService` | $0 | realtime | n/a |
| Strategy narrative | `claude-sonnet-4-6` | $3/$15 | realtime | cached system prompt |
| Chat | `claude-sonnet-4-6` | $3/$15 | realtime, **streaming** | **[ frozen chat system prompt + retrieved CFR/M21-1 chunks + retrieved case extractions ] cache_control → [ conversation turns ]**; 1h TTL within a session |
| Chat KB grounding lookups | Vertex `web search` tool (only if KB miss) | $10/1k searches | realtime | rare; only on KB miss |

**Caching design (the second-biggest lever after the artifact graph):**

- Caching is a **strict byte-prefix match** over `tools → system → messages`
  (`research-agentic-patterns.md` §5). The redesign freezes the prefix:
  - **No** timestamps, user IDs, UUIDs, or unsorted JSON before the breakpoint. The legacy chat
    interpolates full claim state into the system prompt — a silent invalidator. The new design puts
    *only* the stable system prompt + the (slowly-changing) CFR/M21-1 KB slice + the case extractions
    before `cache_control`, and the question/turn after it.
  - **Minimum cacheable prefix:** 2048 tokens on Sonnet 4.6 (`research-vertex-claude.md` §5). The cached
    KB+case prefix is always ≥ that, so it actually caches (legacy never met this because it never set
    `cache_control` at all).
  - **TTL:** 1h (`"ttl":"1h"`, 2× write) for chat sessions and for a batch of synthesis/gap calls that
    share the same case + KB prefix; 5-min (1.25× write) for one-off realtime calls. 5-min breaks even
    after **one** read; 1h after **two** (`research-agentic-patterns.md` §5).
  - **Verification:** assert `cache_read_input_tokens > 0` in monitoring. Zero across repeated requests
    = a silent invalidator regression → CI/alert. (Legacy has no such assertion; cache columns are dead.)
- **Batch (the 50%-off lever):** initial 30-doc ingest is non-interactive ("your analysis is being
  prepared") → Vertex Batch Prediction, GCS JSONL in/out, ≤24h, **50% off input+output**
  (`research-vertex-claude.md` §6). Same for any fleet-wide re-analysis after a prompt/schema bump.
  Single-new-doc updates run realtime (interactive UX) but are tiny. Confirm Opus 4.8 batch support
  before routing escalations through batch (open question in research §6); Sonnet 4.6 + Gemini batch
  are confirmed.

---

## 3. Incremental-update design (the heart of the cost lens)

### 3.1 Artifact graph

Persist every computed artifact in Postgres, each keyed so it is **recomputed only when its inputs
change**. Keying scheme (`research-agentic-patterns.md` §3, applied to this stack):

```
Document(content_hash)                         ← immutable; hash = SHA-256 of bytes
  └─ DocExtraction(content_hash, prompt_ver, schema_ver, model_id)   ← MAP artifact, computed once ever
       └─ Chunk[](content_hash, …) + Embedding[]                      ← retrieval artifacts
CaseModel(claim_id, set_of_doc_extraction_ids, merge_prompt_ver)     ← REDUCE artifact
  └─ Synthesis(case_model_id, synth_prompt_ver, model_id)
       └─ Condition[] → Gap[] (gap_prompt_ver) → WhatIf (deterministic)
```

Every artifact stores the **hash of its inputs** (the set of upstream artifact IDs/versions). An
artifact is valid iff `stored_input_hash == recomputed_input_hash`. This is build-system invalidation,
not timestamp comparison.

### 3.2 What a new doc invalidates (the delta path)

Veteran uploads document D_new to a claim that already has 30 docs:

1. **Ingest + parse + classify + extract D_new only.** 1 map call (Flash-Lite, realtime). The other 30
   `DocExtraction` artifacts are valid (their `content_hash` is unchanged) → **not recomputed**. This is
   the entire difference from the legacy `findByClaimId`-over-everything behavior
   (`ExtractionStateMachine.java:120`).
2. **Embed + chunk D_new only.** Insert rows into pgvector. Instantly queryable (no external index lag).
3. **Re-merge (REDUCE).** Cheap pre-LLM dedupe (pgvector cosine + exact-token match) runs over the new
   extraction vs the existing case model. If D_new introduces no new condition and no contradicting
   fact → **skip the LLM merge entirely** (the new extraction folds in deterministically). If it does →
   one **small** Sonnet merge call over compact extractions (not raw docs).
4. **Re-synthesize only affected conditions.** The merge step emits a *dirty set* of condition IDs (new
   or changed). Synthesis re-runs **only** for dirty conditions, with the cached KB+case prefix. Clean
   conditions keep their prior `Synthesis` artifact. (Legacy re-synthesizes all conditions and *appends*
   duplicate rows — `SynthesisStateMachine.java:177-181`.)
5. **Re-gap only dirty conditions.** Same dirty-set scoping. Gap artifacts for untouched conditions are
   reused. WhatIf math is deterministic and re-runs free.
6. **Supersede, never append.** New `Synthesis`/`Condition`/`Gap` rows carry a `supersedes` pointer and
   the old rows are marked superseded in the same transaction. This fixes the append-only accretion
   *and* preserves chat-set gap statuses by merging user state forward (legacy wipes them —
   `GapStateMachine.java:143-146`).

**Net:** one new doc on a 30-doc claim = **1 map call + (usually) 0–1 small merge call + re-synth of the
1–2 dirty conditions**, versus the legacy "full re-extraction of 31 docs + full synthesis + full gap +
perpetual loop." Cost drops from ~corpus-scale to ~delta-scale — roughly **1/30th** (§6).

### 3.3 Consistency guarantees

- **Idempotency / no double-spend.** Each artifact computation is keyed; a second trigger with the same
  input hash finds the artifact present and short-circuits. This also fixes the legacy double-submit
  defect where `LlmJobService.submit` pre-registers via `provider.submit()` *and* `LlmJobSubmitter`
  submits again (the structured summary flags this as ~2× spend on every async job).
- **Single-writer per claim.** Replace the multi-instance-unsafe `claimRepository.findAll()` poll
  (`AnalysisScheduler.java`) with a per-claim advisory lock (Postgres `pg_advisory_xact_lock(claim_id)`)
  or a Cloud Tasks queue keyed by claim_id, so two Cloud Run instances can't double-fan-out a stage.
- **Failure transition.** Every stage gets an explicit FAILED→retry(≤N)→DLQ path. Legacy gates on
  `allSucceeded` with no failure branch, so one FAILED job wedges the claim forever
  (`SynthesisStateMachine.java:126`, `GapStateMachine.java:193-198`) — and a wedged claim that keeps
  getting ticked is also a cost leak.
- **Trigger by input hash, not timestamp.** Kills the perpetual re-run loop at the root: re-analysis
  fires iff an upstream artifact's input hash changed, never because a model-name string mismatched
  (`AnalysisScheduler.java:192,207`).

---

## 4. Chat system (retrieval, KB, latency, cost)

### 4.1 Grounding sources (three, all required by the founder)
1. **Veteran's own docs/inputs** — the `Chunk`+`Embedding` artifacts from §3 (pgvector, per-user
   isolated by `WHERE user_id = ?`, hardenable with Postgres RLS).
2. **Analysis artifacts** — the `CaseModel`/`Synthesis`/`Condition`/`Gap` rows, injected as compact
   structured context (not re-derived).
3. **VA ratings KB** — 38 CFR Part 4/3 + M21-1 + presumptives + DBQ rubrics, ingested once (§4.3),
   stored as chunks in the same pgvector store with point-in-time `as_of` dates.

### 4.2 Retrieval design (hybrid, in-database, ~$0)
- **pgvector on the existing Cloud SQL instance** — $0 marginal infra, instant freshness after upload,
  per-user isolation as a WHERE clause (`research-gcp-rag.md` §1.3, §3.1). No new service for a
  1-person team; Google's own RAG Engine even supports pgvector as a backend, validating the pattern.
- **Hybrid retrieval from day one:** pgvector HNSW (768-dim, MRL-truncated gemini-embedding-001) +
  `tsvector` full-text, merged with **Reciprocal Rank Fusion** in one SQL query
  (`research-gcp-rag.md` §1.8). Claims chat is full of exact tokens — diagnostic codes ("DC 5260"),
  form numbers, dates — that pure vector search handles poorly. Third-party benchmark: ~62% → ~84%
  precision over vector-only at 15–30 ms.
- **Optional reranker** (Vertex Ranking API, $1/1k requests, ≤100 docs) only if eval shows retrieval
  quality needs a lift. Costs $0.001/turn — bolt on later, not day one.

### 4.3 KB ingestion (VASRD / M21-1 / presumptives) — $0 data, freshness-correct
- **38 CFR Part 3 & 4** via the free eCFR versioner API (`research-va-knowledge.md` §1). Nightly job:
  `titles.json` → if title 38 `up_to_date_as_of` advanced → `/versions?part=3,4` → re-ingest **only
  changed sections** → stamp each chunk with its eCFR `as_of` date. The Feb-2026 publish-then-rescind of
  §4.10 proves stale snapshots become *wrong*, so freshness is a correctness lever, and the diff means
  we re-embed only changed sections (~$0).
- **Part 4 rating tables** also parsed into **structured records** (diagnostic code → % → criteria) for
  deterministic display + the VaMath features — retrieval alone can't do exact criteria reliably.
- **M21-1** scraped per section from KnowVA, re-scraped via Changes-By-Date (email feed as tripwire).
- **DBQs** as the gap rubric (condition → DBQ → its required findings).
- **Presumptives** (§§3.307–3.320) as hand-curated structured eligibility data, watched via the Federal
  Register API.
- Whole KB is public domain / CC0 — **$0 data cost**; the moat is ingestion + citation UX.

### 4.4 Latency budget (target: first token < 1.2s)
| Step | Budget |
|---|---|
| Query embed (gemini-embedding-001, online) | 50–150 ms |
| Hybrid pgvector + tsvector + RRF (in-DB) | 15–30 ms |
| Cache read on the case+KB prefix (0.1× input) | folded into model TTFT |
| Sonnet 4.6 streaming first token | ~400–900 ms |
| **Total to first streamed token** | **< ~1.2 s** |
Streaming is non-negotiable for chat UX; Vertex supports it for Claude (`research-vertex-claude.md` §7).

### 4.5 Cost per chat session (cache-first is the whole game)
Assume a session = 6 questions over a case file whose **compact extractions + retrieved KB chunks** form
a ~25K-token stable prefix (not the raw 300 pages — we ground on extractions + top-k retrieval, not the
full corpus; `research-agentic-patterns.md` §7 hybrid). Sonnet 4.6 = $3/$15 per MTok.

- Turn 1 (cache **write**, 1h TTL = 2× input): 25K × $3 × 2 ÷ 1e6 = **$0.150** input write
  + ~1.5K output × $15 ÷ 1e6 = $0.0225 → **$0.173**.
- Turns 2–6 (cache **read** = 0.1× input): each = 25K × $3 × 0.1 ÷ 1e6 = $0.0075 input
  + ~1.5K out × $15 ÷ 1e6 = $0.0225 → **$0.030** × 5 = **$0.150**.
- New question text per turn (~200 tok) is negligible.
- **Session total ≈ $0.32** for a heavy 6-turn session.

If we size the grounded prefix smaller (top-k retrieval only, ~8K tokens — the realistic median since we
retrieve, not stuff), the same 6-turn session is: write 8K×$3×2/1e6 = $0.048 + 5×(8K×$3×0.1/1e6 +
$0.0225) = $0.048 + 5×$0.0249 = **$0.172**. **Per-question marginal cost ≈ $0.013** once the prefix is
cached. Without caching, every question would re-pay full input (~$0.075 on 25K, ~$0.024 on 8K) — caching
is a **~10× reduction** on the dominant input term, exactly the paid-tier unit-economics lever
(`research-agentic-patterns.md` §5; `research-vertex-claude.md` impl. #5).

---

## 5. Accuracy mechanisms (the floor the cost lens commits to)

Cost-first does **not** mean accuracy-blind — it means *buy accuracy with the cheapest mechanism that
works*. Cheapest-first ordering:

1. **Structured outputs + enums + abstention (≈ free).** Every extraction/synthesis call uses
   `output_config.format` json_schema with `additionalProperties:false`, enums for closed value spaces
   (doc types, body systems, theory-of-entitlement), and first-class `not_found` /
   `insufficient_evidence` fields (`research-agentic-patterns.md` §4). Schema compliance ≠ correctness,
   so abstention routes to escalation, not to a silent empty result. This *replaces* the legacy
   "return [] on parse failure → fast-path to COMPLETE with zero conditions"
   (`SynthesisStateMachine.java:135-140`), which is both wrong and a quality hole.
2. **Deterministic validators (free).** Dates parse, page refs ≤ doc length, diagnostic codes exist in
   the parsed Part 4 table, combined-rating math via `VaMathService`. Validator failure → escalate that
   *one* artifact, don't re-run the corpus.
3. **Cheap-model triage → selective escalation.** Flash-Lite extraction, escalate per-doc on
   validator-fail/abstain. Sonnet synthesis, escalate per-condition to Opus 4.8 on low judge score.
   Escalation is per-artifact, so the escalation premium is paid only where needed — and the
   <~40%-escalation heuristic (`research-agentic-patterns.md` §8) is monitored; if extraction escalates
   too often, the floor model moves up (a config change).
4. **Citations on the narrative pass (near-free, server-validated).** Pass A = structured extraction
   (json_schema). Pass B = cited narrative ("your 2019 C&P exam, p.4"), since Citations is **incompatible
   with structured outputs** (400) and available on Vertex (`research-agentic-patterns.md` §2,
   `research-vertex-claude.md` §7). `cited_text` is free of token charges. Scanned no-text-layer PDFs
   aren't citable → flagged at ingest (the text-layer probe in stage 1).
5. **Adversarial gap verification.** Generator reports all candidate gaps (coverage); a precision pass
   filters — self-filtering at generation depresses recall on recent Claude models
   (`research-agentic-patterns.md` §2). Two cheap Sonnet calls beat one Opus call that misses gaps.
6. **LLM-as-judge in CI, not in the hot path.** A single rubric call (0.0–1.0) on the **final artifact**
   (end-state eval), debiased (different model family where feasible, "don't reward verbosity" line),
   calibrated on ~20–50 human-labeled cases (`research-agentic-patterns.md` §6). It runs in CI on every
   prompt/model/schema bump and as a sampled production monitor — *not* on every user request, so it
   adds ~$0 to per-veteran cost while gating the quality floor.
7. **Golden set as the floor enforcer.** 20–50 synthetic/redacted case files with expected extractions +
   known gaps, run like unit tests. This is what lets the router safely use the cheapest model: the
   floor is *measured*, so "use Flash-Lite for extraction" is a defended decision, not a hope.

---

## 6. Cost model (arithmetic from verified per-MTok prices)

Assumptions: ~30 docs / ~300 pages initial. ~600 text tokens/page → ~180K tokens of document text total
(born-digital; Gemini 3 native PDF text is token-free, so for digital PDFs even the input is partly
free — I cost the conservative "all transcribed" case). Prices: Gemini 3.1 Flash-Lite $0.25/$1.50
(batch $0.125/$0.75); Sonnet 4.6 $3/$15 (cache read 0.1×, batch $1.50/$7.50); Opus 4.8 $5/$25;
gemini-embedding-001 $0.12/1M batch. All from `research-vertex-claude.md` / `research-gcp-rag.md`.

### 6.1 Initial analysis (~30 docs / ~300 pages)

**Extraction (MAP), batched, Flash-Lite ($0.125/$0.75 batch):**
- Input: 180K doc tokens + 30 × ~1.5K instruction/schema (stable prefix, but count it) ≈ 225K tokens
  → 225K × $0.125 ÷ 1e6 = **$0.028**.
- Output: 30 docs × ~2K structured tokens = 60K → 60K × $0.75 ÷ 1e6 = **$0.045**.
- Extraction subtotal ≈ **$0.073**. (Escalate ~10% of docs to Sonnet: 3 docs × ~6K in + 2K out at
  $1.50/$7.50 batch ≈ 3×(6K×1.5+2K×7.5)/1e6 = 3×$0.024 = **$0.072** added in the worst realistic case →
  call extraction **$0.07–$0.15**.)

**Embedding:** ~225K tokens × $0.12 ÷ 1e6 = **$0.027** (one-time; batch).

**Merge (REDUCE), Sonnet realtime:** input = 30 × ~2K compact extractions + ~10K cached KB prefix
(0.1× read after first write). First call writes ~10K KB prefix (2× = $0.06) once; merge input
~60K extractions × $3 ÷ 1e6 = $0.18 + output ~5K × $15 ÷ 1e6 = $0.075 → **≈ $0.32**. (Pre-LLM dedupe
shrinks the 60K; conservatively keep it.) *Lever:* run merge in batch too if init is async → halve to
**~$0.16**.

**Synthesis (identify+rate+verify), Sonnet, KB prefix cached:** say ~8 conditions. Identify: ~60K
extractions input + 5K out. Rate (one pass over conditions, cached KB read): ~60K read-priced via cache
(0.1×) + 8 conditions × ~1K out. Verify: ~10K + 3K out. Rough: input mostly cache-read after the first
write. Estimate input ≈ 60K full + 70K cache-read; output ≈ 16K.
- Input: 60K×$3/1e6 + 70K×$0.30/1e6 (0.1×) = $0.18 + $0.021 = $0.201
- Output: 16K × $15 ÷ 1e6 = $0.24
- Synthesis ≈ **$0.44**. *Lever:* batch the initial synthesis (50% off) → **~$0.22**.

**Gap analysis (generator + adversarial verifier), Sonnet, KB+DBQ prefix cached:** 8 conditions, two
passes, mostly cache-read input. Input ≈ 8×(~8K cache-read) ×2 passes = 128K × $0.30/1e6 = $0.038 +
~20K full = $0.06; output ≈ 8×~1.5K×2 = 24K × $15/1e6 = $0.36. Gap ≈ **$0.46**; batched → **~$0.23**.

**Strategy:** VaMath = $0; narrative ~5K out × $15/1e6 + cached input = **~$0.08**.

**Initial analysis total (realtime):** 0.10 + 0.027 + 0.32 + 0.44 + 0.46 + 0.08 ≈ **$1.43**.
**Initial analysis total (batched where non-interactive — the cost-first default):**
0.10 + 0.027 + 0.16 + 0.22 + 0.23 + 0.08 ≈ **$0.82**.
With aggressive cache reuse across synthesis/gap (shared case+KB prefix at 1h TTL) and Gemini-3 free
native-PDF-text on the (typical) born-digital share, the realistic landed cost is **≈ $0.30–$0.50 per
initial analysis**. I quote **~$0.32** as the target for the common case (mostly digital PDFs, batched,
cache-warm), **~$0.82** as the conservative all-transcribed batched figure, **~$1.43** as the realtime
worst case.

> Compare to legacy: every per-condition rate/gap/validation job re-sends the **full atom corpus** on
> Opus ($5/$25) with **no caching, no batch discount**, plus the perpetual re-run loop. A single legacy
> initial run is already multiples of this; the loop makes legacy steady-state cost *unbounded*.

### 6.2 Incremental doc (1 new doc on a 30-doc claim)

- Extract D_new: ~6K in + 2K out, Flash-Lite realtime: 6K×$0.25/1e6 + 2K×$1.50/1e6 = $0.0015 + $0.003 =
  **$0.0045**.
- Embed D_new: ~6K × $0.15/1e6 = **$0.0009**.
- Re-merge: usually deterministic ($0); if LLM needed, small Sonnet call over compact extractions ~10K
  cache-read in + 2K out ≈ 10K×$0.30/1e6 + 2K×$15/1e6 = $0.003 + $0.030 = **$0.033** (only when D_new
  changes the case model).
- Re-synth + re-gap **only the 1–2 dirty conditions**, KB+case prefix cache-read: ~2×(8K cache-read +
  ~1.5K out) for synth + ~2×(8K cache-read + ~1.5K out) for gap = input 32K×$0.30/1e6 = $0.010, output
  6K×$15/1e6 = $0.090 → **$0.10** (only when conditions are dirty).
- **Incremental total:**
  - *No new condition* (common — a supporting record): **~$0.006** (extract + embed; merge & synth skip).
  - *New/changed condition:* ~$0.0045 + $0.0009 + $0.033 + $0.10 ≈ **$0.14**.
  - Blended realistic average ≈ **$0.012–$0.02**.
- **vs legacy:** legacy re-extracts all 31 docs + full synthesis + full gap on every upload, i.e.
  ~initial-analysis cost *per doc* — the redesign is **~1/30th to 1/100th** of that.

### 6.3 Chat session
From §4.5: **~$0.013 per question** cache-warm; a typical 6-turn session **~$0.17–$0.32**. Retrieval is
~$0.00001/turn (pgvector + query embed) — negligible (`research-gcp-rag.md` §3.7).

### 6.4 Headline per-veteran economics
At $11.99/mo paid tier: initial analysis ~$0.32 + (say 10 incremental docs/mo × $0.015 = $0.15) +
(say 50 chat questions/mo × $0.013 = $0.65) ≈ **~$1.12 of model cost against $11.99 revenue** →
gross margin holds even for an active user. The legacy design, by contrast, can spend that *per re-run*
and re-runs perpetually.

---

## 7. Migration path + risks

### 7.1 Migration (incremental, no big-bang; each step is independently shippable)
1. **Stop the bleeding (week 1, pure config/guard).** (a) Fix the re-run trigger: compare against the
   actual stamped model or, better, switch the trigger to input-hash (kills the perpetual loop at
   `AnalysisScheduler.java:192,207`). (b) Enforce the $4 usage cap on the scheduler path and stop
   submitting extraction jobs with `userId(null)` (`ExtractionStateMachine.java:177,327`) so spend is
   visible/capped. (c) Add the FAILED→retry→DLQ transition so wedged claims stop being ticked. These are
   the highest-ROI cost fixes and need no new infra.
2. **Populate the router (week 1).** Fill `purposeDefaultModels` (`LlmProviderRouter.java:32`) so
   extraction routes to `gemini-3.1-flash-lite` and synthesis/gap to `claude-sonnet-4-6`. Model is now a
   config string. Fix `AiCostService` to model the batch discount and cache tokens so the cost ledger is
   accurate (it currently overstates Claude ~2×).
3. **Move Claude onto Vertex global endpoint** via `AnthropicVertex` SDK (ADC auth, no API key, same
   price, inside the GCP boundary; `research-vertex-claude.md` impl. #1). Replace the in-memory Gemini
   result map (lost on restart → 30-min orphan re-pay) with durable result storage.
4. **Introduce the artifact graph + content-hash keying (weeks 2–3).** Add `content_hash` to documents,
   version columns to extraction/synthesis/gap, input-hash validity checks. This is the change that
   turns full-redo into delta-path. Backfill hashes for existing docs.
5. **Add caching + batch lanes (weeks 3–4).** Stable-prefix system prompts + `cache_control` on the
   KB+case prefix; route initial ingest + fleet re-analysis through Vertex Batch Prediction. Assert
   `cache_read_input_tokens > 0` in monitoring.
6. **pgvector retrieval + KB ingestion (weeks 4–6).** Enable pgvector, embed chunks at 768d, build the
   eCFR/M21-1/DBQ/presumptive ingestion jobs with point-in-time stamps; rebuild chat on hybrid
   retrieval + cached prefix + Citations.
7. **Eval harness + golden set (parallel, gating).** Stand up the golden set and judge before flipping
   the extraction floor to Flash-Lite, so the quality floor is measured, not assumed.

### 7.2 Risks (max 5)
1. **Flash-Lite extraction misses below the floor.** Mitigation: the golden set gates it; escalation
   path catches per-doc failures; floor model is a config flip. Risk is bounded because escalation is
   per-document.
2. **Batch latency (≤24h) hurts the "analyze what I just uploaded" UX.** Mitigation: initial full
   analysis is async ("preparing…") on batch; single incremental docs run **realtime** (tiny, cheap).
   Only fleet re-analysis is batch-only.
3. **Cache silently invalidates** (a timestamp/UUID creeps into the prefix → every request re-pays full
   input, ~10× cost regression). Mitigation: the `cache_read_input_tokens > 0` assertion is a CI + prod
   alert; stable-prefix discipline is a reviewable invariant.
4. **Opus 4.8 / Fable 5 batch support unconfirmed** (`research-vertex-claude.md` §6 open question). If
   escalations can't batch, they run realtime at full price — bounded because escalation is rare by
   design (<~40% target, monitored).
5. **KB staleness becomes wrong-ness** (the §4.10 publish-then-rescind case). Mitigation: nightly eCFR
   diff with `as_of` stamps on every chunk and surfaced in citations; Federal Register watch for
   presumptives. This is a correctness risk the cost lens must not trade away.

---

## Appendix — verified legacy facts grounding this design
- Perpetual re-run loop: `AnalysisScheduler.java:192,207` (`currentClaudeModel.equals(lastSynthesisModel/lastGapAnalysisModel)`) vs Gemini-stamped models — **verified in source**.
- Extraction escapes the $4 cap: `ExtractionStateMachine.java:177,327` `.userId(null)` — **verified**.
- Usage cap `limit-cents: 400` ($4/mo): `application.yml:24` — **verified**.
- Router defaults hard-coded, `purposeDefaultModels` never populated: `LlmProviderRouter.java:32` (declared), `:86-95` (`defaultModelFor` → `claude-opus-4-7` / `gemini-3.1-pro-preview`) — **verified**.
- Append-only synthesis (no supersede in async path): `SynthesisStateMachine.java:177-181`; gap wipes user statuses: `GapStateMachine.java:143-146` (per code-reader summaries).
- No caching / no batch discount in cost ledger: `AiCostService.java:23-32` (per code-reader summaries).
