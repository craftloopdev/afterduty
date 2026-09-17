# VA Claim Path — Accuracy-First Architecture

**Architect lens:** Accuracy-first (maximize correctness + trustworthiness of condition synthesis & gap analysis).
**Date:** 2026-06-10. **Author:** Accuracy-first panel architect.
**Charter:** Verification layers, adversarial checks, VASRD citation grounding, abstention when uncertain, eval harness with golden cases. Cost is secondary but every dollar saved helps another veteran — so I show where accuracy spend pays off and where it is waste.

All per-MTok prices are from the research drafts (verified 2026-06-10 against platform.claude.com / cloud.google.com). All code citations are `file:line` against `spring-backend/src/main/java/com/afterduty/` on branch `feat/next-web-foundation`.

---

## 0. Thesis (the one-paragraph version)

The legacy system is *prompt-engineered JSON over a full-corpus dump, with silent fallbacks that fail toward "this veteran has no conditions" and "0% rating."* That is the worst possible failure mode for an accuracy-critical benefits tool: it is confidently wrong and invisible. My design replaces it with a **static map-reduce DAG over per-document artifacts**, where every factual claim is **server-grounded to a page (Citations) or a 38 CFR section (typed VASRD lookup)**, every stage **abstains as a first-class schema field** instead of guessing, an **adversarial verifier with veto power** sits between synthesis and the veteran, and a **golden-case eval harness gates every prompt/model/schema change in CI**. The deterministic legal spine (combined-rating math, bilateral factor, pyramiding, presumptive matching) stays in code — never the LLM. Accuracy spend is concentrated where errors are unrecoverable (the synthesis→verify→present path and citation grounding); it is starved everywhere the answer is mechanical (Gemini Flash extraction, deterministic ratings, prompt-cached chat).

---

## 1. End-to-end pipeline (static vs dynamic-agentic, with WHY)

The core decomposition is **known and enumerable** (classify → extract → merge → identify → rate → verify → gaps → present), so per the Anthropic/Google consensus (research-agentic-patterns.md §1) it must be a **static DAG with programmatic checkpoints**, not an orchestrator-agent. Dynamic agency is reserved for the two genuinely open-ended surfaces: **Ask-AI chat** and **"what specifically is missing / how do I get it" deep-dives** — and even there, a *single* agent with tools, never multi-agent (the case file is one interdependent context — Anthropic's explicit multi-agent anti-pattern, ~15x token cost, research-agentic-patterns.md §1).

| # | Stage | Static / Dynamic | Model | Why this choice (accuracy lens) |
|---|---|---|---|---|
| 1 | **Ingest + text-layer probe** | Static | none (PDFBox) + Document AI OCR fallback | Deterministic. Probe each PDF for an extractable text layer at upload. Citations are **incompatible with scanned no-text-layer PDFs** (research-agentic-patterns.md §2) — so text-layer quality is a *gating dependency* I must detect at ingest, not discover at citation time. No-text-layer files are OCR'd (Document AI, deterministic, bounding boxes + confidence, never hallucinates — research-gcp-rag.md §1.6) so they become citable. |
| 2 | **Classify document** | Static (single typed call) | Gemini 3 Flash-Lite | Closed value space (DD-214, C&P/DBQ, decision letter, STR, lay statement, …) → enum-constrained structured output. Cheap, high-volume, mechanical. Replaces today's **filename-substring typing** (`GeminiExtractionService.java:123-140`) which is a silent correctness hole (any file with "letter" gets the nexus prompt). |
| 3 | **Per-document extraction → DocFacts artifact (MAP)** | Static, parallel, **Batch** | Gemini 3 Flash-Lite (escalate to Sonnet 4.6 on abstention) | This is the map step of map-reduce (research-agentic-patterns.md §3). One structured extraction per doc, keyed by `(content_hash, prompt_version, schema_version, model_id)`. Embarrassingly parallel → Vertex Batch (50% off). **Abstention fields are first-class** (`not_found`/`insufficient_evidence`/`illegible`) so a bad scan escalates instead of fabricating. This is the unit of incrementality (§3). |
| 4 | **Merge DocFacts → CaseModel (REDUCE)** | Static, single call over *compact* artifacts | Sonnet 4.6 | Reduce step. Consumes hundreds of tokens/doc of structured DocFacts, **never raw PDFs** — keeps re-synthesis cheap as the file grows. Deterministic dedupe of identical atoms happens in Java first; the LLM only resolves genuine semantic conflicts (e.g., two C&P exams disagreeing on severity). |
| 5 | **Identify candidate conditions** | Static, **tool-grounded** | Sonnet 4.6 + VASRD-catalog tool | Today the async identify prompt has **no canonical VASRD code list** (legacy-synthesis.md §2.4; the masterlist digest exists only on the dead sync path). I give the model a `vasrd_lookup` tool over the full 38 CFR Part 4 catalog (§5.3) so codes are *grounded*, not free-recalled. Output carries explicit theory-of-entitlement tags (direct / secondary §3.310 / presumptive §3.307–3.320). **Empty result is an explicit `abstain_low_evidence` flag**, never the current silent fast-path-to-COMPLETE-as-zero-conditions (`SynthesisStateMachine.java:135-140`, verified). |
| 6 | **Deterministic rating gate** | **Static / code, no LLM** | none | Resurrect the dead `VasrdDecisionEngine` (`legacy-synthesis.md` weakness 7). Conditions with mechanical schedules (tinnitus 6260 = 10%, ED 7522, single-DC presumptives) are rated **in code from the parsed Part 4 tables** — zero LLM cost, perfectly reproducible, auditable. Only conditions that need judgment fall through to stage 7. |
| 7 | **Rate (judgment cases) — evidence-scoped** | Static, parallel, **Batch** | Sonnet 4.6; escalate to Opus 4.8 on low confidence | One job per condition, but each receives **only the atoms relevant to that condition** (retrieval, §3) + the exact Part 4 criteria for its DC, **not** the O(N×atoms) full dump the legacy path re-sends every time (`RatingAgent.java:187-193`). Rating is a closed enum {0,10,…,100}. Confidence is a required field; low confidence routes to the escalation lane. |
| 8 | **Adversarial verify (RVSR persona) — WITH VETO** | Static, single call, **sees the atoms** | Opus 4.8 (different-family / harder model than generator) | The legacy verifier never sees the atoms (`SynthesisVerificationAgent`, legacy-synthesis.md §2.4) so "is there sufficient evidence" is circular, and its findings die as unpersisted map flags (weakness 8). My verifier reads the **atoms + ratings + the generator's citations**, and its verdicts are **binding**: `rating_mismatch`/`invalid_code`/`unsupported_claim` either auto-correct (deterministic cases) or **demote the condition to "needs review" and suppress the specific unsupported sentence** before the veteran sees it. This is the generator-coverage / verifier-precision adversarial split (research-agentic-patterns.md §2). |
| 9 | **Gap analysis (evidence gaps)** | Static, parallel, **Batch** | Sonnet 4.6 + DBQ-rubric tool | Per condition, grounded in the **public DBQ for that condition** as the evidence checklist ("the rater's form asks for X; your file lacks X" — research-va-knowledge.md §4) plus the verbatim Part 4 criterion. Generator is told to **over-report** candidate gaps with confidence+severity (coverage incentive). |
| 10 | **Gap validation (adversarial RVSR)** | Static, parallel, **Batch** | Opus 4.8 | Keep the existing genuinely-good generator/critic pair (legacy-gap-strategy.md notes this is "the one genuinely agentic touch"). Critic filters/ranks (precision incentive) and can only *shrink* the gap list. This is the verifier half of the coverage/precision split. |
| 11 | **What-if scenarios** | **Static / code for the math, LLM for the prose** | Sonnet 4.6 (prose only) | The rating-impact *number* comes from deterministic `VaMathService` (combined rating, bilateral factor, §4.25/4.26, **current** comp tables — not the hard-coded "2024 rate tables" string in `WhatIfScenarioGenerator.java:44`). The LLM only writes the plain-language "down to earth" explanation. Legal math is never the model's job. |
| 12 | **Present** | Static | none | Render CaseModel + citations + gaps. Every shown claim links to a page ("your 2019 C&P exam, p.4") or a CFR section ("38 CFR §4.130, current as of 2026-06-09"). "Needs review" conditions are visually distinct; abstentions surface as "we couldn't confirm X from your records." |
| **A** | **Ask-AI chat** | **Dynamic — single agent w/ tools** | Sonnet 4.6 (escalate Opus 4.8) | Open-ended → agentic. Tools: hybrid retrieval over the veteran's chunks, CaseModel reader, VASRD/M21-1 KB retrieval. Cache-first (§4). |
| **B** | **"What's missing" deep-dive** | **Dynamic — single agent w/ tools** | Opus 4.8 | Genuinely open-ended investigation; same tool surface, deeper thinking budget. |

**Why static dominates:** legal correctness, testability, predictable cost/latency, and a replayable per-stage audit trail (the legacy `LlmJob.request_payload/response_payload` audit is genuinely good — legacy-gap-strategy.md §6 — and I keep it). **Why dynamic at the edges:** chat and "what's missing" cannot be enumerated in advance.

---

## 2. Model routing table (model · realtime/batch · caching)

Run **Claude via Vertex AI, global endpoint** (no 10% premium, newest models, ADC auth — no API key to rotate for veterans' medical PII; research-vertex-claude.md §8, impl. #1). us-central1 does not serve Claude; global endpoint is fine from a us-central1 Cloud Run service. **Batch on Vertex = Vertex Batch Prediction (GCS JSONL), NOT the Anthropic Message Batches API** (which is unavailable on Vertex — the current `AnthropicBatchProviderImpl` hits `api.anthropic.com` directly and must be re-pointed; research-vertex-claude.md §6).

| Stage | Model (Vertex ID) | Mode | Caching / stable-prefix design |
|---|---|---|---|
| Classify (2) | `gemini-3-flash-lite` | Batch (initial) / realtime (incremental) | Cache the classification system prompt + enum schema (frozen). |
| Extract MAP (3) | `gemini-3-flash-lite` → escalate `claude-sonnet-4-6` | Batch (initial bulk) / realtime (single new doc) | Stable prefix = `[extraction system prompt + JSON schema]`; volatile = the one document. Per-doc, so caching helps only on escalation re-runs. |
| Merge (4) | `claude-sonnet-4-6` | Realtime | Compact input; caching marginal. |
| Identify (5) | `claude-sonnet-4-6` | Realtime | **Cache prefix = `[identify system prompt + VASRD catalog tool defs + service/presumptive context]`** (stable across the veteran's runs); volatile = compact DocFacts. 1h TTL during an active session. |
| Rate judgment (7) | `claude-sonnet-4-6` → escalate `claude-opus-4-8` | Batch | **Cache prefix = `[rating system prompt + Part 4 criteria block]`** shared across the N rating jobs of one claim (the N-job fan-out has an identical prefix — exactly the cache win the legacy path throws away). Fan-out concurrency gotcha: fire job 1, await first token, then the rest (research-agentic-patterns.md §5). |
| Verify (8) | `claude-opus-4-8` | Realtime | Harder/different-tier model than the Sonnet generator → mitigates self-preference bias (research-agentic-patterns.md §2). Sees atoms + citations. |
| Gap evidence (9) | `claude-sonnet-4-6` | Batch | Cache `[gap system prompt + DBQ rubric + Part 4 criterion]` per condition-type. |
| Gap validate (10) | `claude-opus-4-8` | Batch | Adversarial critic; binding shrink-only. |
| What-if prose (11) | `claude-sonnet-4-6` | Batch | Math is deterministic; LLM only narrates. |
| Chat (A) | `claude-sonnet-4-6` → Opus 4.8 | Realtime streaming | **Cache `[frozen chat system prompt + VASRD KB slice + veteran's DocFacts/CaseModel]`**; question appended last (§4). |
| Deep-dive (B) | `claude-opus-4-8` | Realtime | Same cache prefix as chat; higher thinking budget. |

**Citations** are enabled on the **present-narrative and chat passes** (available on Vertex — research-vertex-claude.md §7, research-agentic-patterns.md §2). Because **Citations is incompatible with structured outputs (400 error)**, every accuracy-critical stage is **two-pass**: pass A = structured extraction (json_schema + enums + abstention), pass B = cited narrative over the same cached document. This split is non-negotiable and is the single most important shape constraint in the design.

**Why not Opus/Fable everywhere:** the 5x Haiku→Opus spread means triaging the high-volume map stage to Gemini Flash captures most of the cost win with none of the router complexity (research-agentic-patterns.md §8). Fable 5 ($10/$50) is adopted *only* if the eval harness proves it beats Opus 4.8 on the golden set — accuracy spend must be earned, not assumed. (Also: Opus 4.8/Fable 5 Vertex Batch support is unconfirmed in the research snapshot — confirm before batching on them; Sonnet 4.6 + Haiku 4.5 batch is confirmed.)

---

## 3. Incremental-update design (artifact graph · invalidation · delta path · consistency)

The legacy behavior is a **full redo with compounding duplication** — new upload resets `extractionState=NONE`, re-extracts ALL docs (`ExtractionStateMachine.java:120`), appends duplicate atoms/conditions with no supersede, and a model-name-mismatch bug re-runs everything on every tick (legacy-synthesis.md §4, verified). I replace timestamp-as-trigger with a **content-addressed artifact graph + dirty propagation**.

### 3.1 Artifact graph (DAG of cached, keyed artifacts)

```
Document(content_hash)
   └─► DocFacts[doc]          key = hash(content_hash, doc_type, extract_prompt_ver, schema_ver, model_id)
          └─► CaseModel        key = hash(set of DocFacts keys, merge_prompt_ver, schema_ver, model_id)
                 ├─► Condition[c]        (identify+rate; key includes the atom-subset hash for c)
                 │      ├─► Rating[c]
                 │      ├─► Verify[c]
                 │      └─► Gaps[c] ─► WhatIf[c]
                 └─► Chunks[doc] (embeddings, for retrieval)   key = hash(content_hash, chunk_ver, embed_model)
```

Every node stores its **input fingerprint**. A node is *valid* iff its stored fingerprint equals the current hash of its inputs. This is standard build-system invalidation applied to LLM artifacts (research-agentic-patterns.md §3).

### 3.2 What a new document invalidates (the delta path)

Upload doc *D*:
1. Probe text layer → OCR if needed → `content_hash(D)`.
2. **1 classify call + 1 extract call** for *D* only → `DocFacts[D]`. (All other DocFacts unchanged — their fingerprints didn't move.)
3. `CaseModel` fingerprint changes (its DocFacts set grew) → **1 merge call** over the *compact* DocFacts set (cheap; reduce step).
4. Recompute **only the conditions whose relevant atom-subset hash changed.** *D* mentions PTSD and tinnitus → only those two conditions' identify/rate/verify/gap chains re-run; a knee condition *D* never touches keeps its valid artifacts and is **not re-paid**. This is the core win: **1 new doc on a 30-doc case ≈ 1 map + cheap merge + 1–3 affected condition chains**, not 100% re-analysis.
5. Deterministically rated conditions (stage 6) re-run for free in code.

### 3.3 Consistency guarantees

- **Supersede, never append.** New Condition/Rating rows set `supersededBy` on the old (the column exists — `IdentifiedCondition`); a single "active set" view is always coherent. Fixes the legacy append-duplicate bug (legacy-synthesis.md weakness 2). **Stable condition IDs** across runs (carry identity by `(vasrd_code, body_system, theory)`), so chat references and user-set gap statuses survive re-analysis — the legacy path destroys both (`GapStateMachine.java:143-146` erases chat-set statuses).
- **Atomic activation.** A re-analysis writes a new artifact generation, then flips an `active_generation` pointer in one transaction. The veteran never sees a half-updated case (no mixed old/new conditions).
- **Idempotent by content hash.** Re-uploading the same file is a no-op (fingerprint hit). Kills the duplicate-atom accumulation.
- **No model-name re-run loop.** Invalidation is by *input fingerprint*, not by comparing a config model string to a stored job model — the bug class at `AnalysisScheduler.java:192` simply cannot exist.
- **Stale pipeline rows can't poison re-runs** because readers key on the active generation, not `pjobs.get(0)` of an unordered union (legacy-synthesis.md weakness 3).

### 3.4 Keep / fix from the legacy substrate

Keep the DB-queue (`SELECT … FOR UPDATE SKIP LOCKED` submitter, orphan recovery, `LlmJob` audit trail) — it is solid and multi-instance-safe. **Fix the missing failure transition** (one FAILED job wedges the claim forever — legacy weakness 4): every stage gets `allTerminal` handling → a FAILED job marks the condition "needs review" and the rest of the claim proceeds. **Fix the double-submit** (`LlmJobService.submit` pre-registers via `provider.submit()` then the submitter submits again — legacy-llm-cost-chat.md weakness 1: likely 2x pay on every job).

---

## 4. Chat system (retrieval/grounding · KB ingestion · latency · streaming · cost)

### 4.1 Retrieval / grounding

Three grounding sources, all retrieved, then composed into a cached prefix:
1. **Veteran's own docs** — **hybrid retrieval** (pgvector HNSW + `tsvector` full-text + Reciprocal Rank Fusion in one SQL query — research-gcp-rag.md §1.8). Claims chat is full of exact tokens (DC 5260, VA Form 21-4138, dates) that pure vector retrieval misses — hybrid is mandatory, not optional. Embeddings: `gemini-embedding-001` MRL-truncated to 768 dims (fits pgvector's 2,000-dim HNSW limit), on the **existing Cloud SQL instance** (~$0 marginal infra, instant freshness after upload, per-veteran isolation via `WHERE user_id` + Postgres RLS — research-gcp-rag.md impl. #1).
2. **Analysis artifacts** — CaseModel + conditions + gaps read directly (structured, no retrieval needed).
3. **VA knowledge** — VASRD/M21-1/presumptives KB (§4.3), retrieved per query, **every chunk stamped with its eCFR `current as of` date** so chat can cite "38 CFR §4.130 (current as of 2026-06-09)."

The chat agent (single agent, tool-use loop) gets tools: `search_my_documents` (hybrid), `read_case_model`, `search_va_regulations`. Optional Vertex Ranking API reranker ($1/1k) if eval shows retrieval precision needs a lift.

### 4.2 Grounding / accuracy in chat

- **Citations on** for answers that quote the veteran's docs → server-validated page pointers, 0 source hallucination (research-agentic-patterns.md §2).
- **Abstention as a tool outcome:** if retrieval returns nothing relevant, the agent is instructed (and rubric-graded) to say "I don't see that in your records" rather than confabulate.
- **No-legal-advice boundary** is a system-prompt invariant *and* a judge rubric criterion on every sampled chat turn (§5.4).

### 4.3 KB ingestion (VASRD / M21-1 / presumptives / DBQ)

Per research-va-knowledge.md — the whole KB is **$0 and public-domain/CC0**:
- **38 CFR Part 4 & Part 3** via the free eCFR versioner API. Nightly job: `titles.json` → if title 38 `up_to_date_as_of` advanced, `/versions?part=3,4` → re-ingest only changed sections → re-embed only those chunks. Every chunk carries its point-in-time date. The Feb-2026 publish-then-rescind of §4.10 proves a stale snapshot becomes *wrong*, not merely old — freshness is a correctness requirement.
- **Part 4 rating tables parsed into structured records** (DC → rating% → criteria text) — this is the `vasrd_lookup` tool backing (§5.3) and the deterministic-rating engine's data. Today only **37 codes** exist in `vasrd_codes.json` (verified); the full Part 4 is 202 sections — parse all of them.
- **DBQs** as the gap-analysis rubric (≈70 public PDFs, 19 categories). **M21-1** scraped from KnowVA (HTML only, watch Changes-By-Date). **Presumptives** hand-curated from §§3.307–3.320 with FR-API change-watch.
- Chunking: **section-aware / hierarchical** (decision-letter headings, DBQ questions, STR encounters) — chunking failure is the dominant RAG failure mode for clinical records (research-gcp-rag.md §1.7); reuse the existing Gemini extraction pass to emit chunks, avoiding $10/1k Layout Parser.

### 4.4 Latency budget & streaming

Target first-token < 2s, full answer < 8s:
- Query embedding (Vertex): ~50–150ms. Hybrid retrieval (pgvector, in-instance): single-digit–low-tens ms (research-gcp-rag.md §1.9). **Cache read of the case-file prefix: ~0** (it's a prefix hit). LLM generation dominates → **stream tokens** to the UI (Vertex supports streaming for Claude).
- The legacy `ChatAgent` blocks synchronously with unbounded 429 recursion + 30s sleeps on the request thread (`ChatAgent.java:172-176`) — replace with bounded retry + streaming.

### 4.5 Cache-first design (the unit-economics lever)

`[frozen system prompt + VASRD KB slice + veteran's DocFacts/CaseModel]` under `cache_control` (1h TTL for session continuity); the question is appended last. Every follow-up question pays **0.1x** on the whole cached prefix (research-agentic-patterns.md §5, research-vertex-claude.md §5). The legacy `ChatAgent.buildSystemPrompt(claimId)` builds a fresh per-claim system prompt with **no cache_control** (verified `ChatAgent.java:191`) and writes **no AiCallLog** (spend invisible — legacy-llm-cost-chat.md weakness 3). Both fixed. Audit `cache_read_input_tokens > 0` in monitoring; zero means a silent invalidator slipped into the prefix.

---

## 5. Accuracy mechanisms (the heart of this lens)

### 5.1 Structured output + first-class abstention

Every extraction/identify/rate stage uses Vertex `output_config.format` json_schema with `additionalProperties:false` + **enums on closed value spaces** (doc_type, body_system, rating ∈ {0,10,…,100}, theory_of_entitlement). Schema compliance ≠ semantic correctness (research-agentic-patterns.md §4), so I stack: (1) constrained decoding for syntax; (2) enums; (3) **nullable/abstention fields** (`not_found`, `insufficient_evidence`, `illegible`, `abstain_low_evidence`) rather than forcing a value; (4) **deterministic validators** (dates parse; page refs ≤ doc length; DC exists in the parsed Part 4 catalog; rating ∈ enum); (5) semantic spot-check via citations/judge on high-stakes fields. This directly kills the legacy silent-fallback failures I verified: empty-identify → COMPLETE-as-zero-conditions (`SynthesisStateMachine.java:135`), and rate-parse-fail → silent 0%/0.3-confidence (`RatingAgent.java:122-130`). Under my design those become explicit abstentions that route to escalation or "needs review," never a confident wrong answer shown to a veteran.

### 5.2 Citation grounding (two-pass)

Pass A structured extraction (no citations), pass B cited narrative (citations on, no structured output) over the same cached document. Server-validated page pointers mean **no fabricated page numbers** (Anthropic reports 10%→0% source hallucination for one customer — research-agentic-patterns.md §2). Scanned no-text-layer docs are OCR'd at ingest (§1 stage 1) so they remain citable. VASRD claims are grounded a second way: the typed `vasrd_lookup` returns the verbatim §-text + as-of date, so a cited criterion is the *actual* regulation, not the model's memory of it.

### 5.3 VASRD grounding tool (vs free recall)

A `vasrd_lookup(diagnostic_code | condition_name)` tool over the **full parsed Part 4 catalog** (research-va-knowledge.md impl. #3) returns: canonical DC, rating thresholds, verbatim criteria text, eCFR as-of date. Used by identify (ground the code), rate (exact criteria), and chat. Replaces today's no-catalog identify prompt + 37-code lookup (verified). The deterministic `VasrdDecisionEngine` rates mechanical codes straight from this data — free and reproducible.

### 5.4 Adversarial verification + LLM-as-judge

- **Inline adversarial verify (stage 8, runtime, blocking):** generator (Sonnet) over-reports with confidence/severity; verifier (Opus, harder tier) has **veto power** — suppresses unsupported sentences, demotes low-evidence conditions to "needs review." Generator-coverage / verifier-precision split per research-agentic-patterns.md §2 (self-filtering at generation depresses recall on recent Claude models).
- **Offline LLM-as-judge (eval/monitoring, not runtime):** single rubric call, 0.0–1.0, **end-state evaluation** (grade the final artifact, not the trajectory). Debiased per research-agentic-patterns.md §2: judge from a **different model family** than the generator where feasible, explicit "**do not prefer longer answers**" rubric line, position-bias control, **calibrated against a small human-labeled set** (a VSO advisor is the ideal labeler — flagged as an open product dependency). Rubric criteria: factual accuracy, **citation accuracy**, rating-criteria correctness, completeness of gaps, and the **no-legal-advice boundary** as a hard criterion on every output.

### 5.5 Eval harness with golden cases (CI gate)

Per the 2025 consensus (research-agentic-patterns.md §6): **Traces → manual error analysis of 50–100 real traces → golden dataset → human-calibrated judge → CI.** Build **20–50 synthetic/redacted case files** with expected extractions, expected conditions+ratings, and known gaps. Run as unit-test-style assertions (exact extractions, DC validity, rating ∈ expected band) **plus** the judge rubric, in CI **on every prompt/model/schema/KB-version bump**. Even ~20 cases catch most regressions. Production failures triage into new golden cases (the regression loop). This is the mechanism that lets a 1-person team change a prompt or bump Sonnet→Opus without silently regressing a veteran's rating.

### 5.6 Where accuracy spend pays off vs waste

- **Pays off:** the synthesis→verify→present path (errors here are shown to a veteran and unrecoverable), citation grounding (turns "trust me" into "see p.4"), the eval gate (prevents silent regressions), the harder verifier model. These get Opus 4.8 and real thinking budget.
- **Waste:** running Opus on tinnitus (deterministic — code it), re-sending the full atom corpus per condition (scoped retrieval instead), uniform max thinking on mechanical extractions, Fable 5 anywhere the golden set doesn't justify 2x cost, and the legacy 6x-overstated Claude cost accounting (`AiCostService.java:28`, no batch discount) that trips caps early.

---

## 6. Cost model (arithmetic from real per-MTok prices)

Assumptions: ~30 docs / ~300 pages initial; ~600 text tokens/page → ~180k tokens raw corpus; DocFacts compress ~10x → ~18k tokens compact CaseModel input; ~8 conditions; chat 200-page-equivalent (~120k-token) cached case file. Prices (research drafts, Vertex global, batch=50% off): Gemini 3 Flash-Lite $0.25/$1.50 (batch $0.125/$0.75); Sonnet 4.6 $3/$15 (batch $1.50/$7.50); Opus 4.8 $5/$25 (batch $2.50/$12.50); cache read 0.1x.

**Initial analysis (~30 docs / 300 pages):**
- Classify 30 docs (Flash-Lite batch): 30 × ~1k in / ~0.1k out ≈ $0.004.
- Extract MAP 30 docs (Flash-Lite batch): 30 × ~6k in / ~1.5k out ≈ 180k in + 45k out → $0.0225 + $0.034 ≈ **$0.06**. (Escalations to Sonnet on a few abstaining scans add ~$0.05.)
- Merge (Sonnet realtime): ~18k in / ~3k out ≈ $0.054 + $0.045 ≈ **$0.10**.
- Identify (Sonnet, cached prefix): ~18k in / ~3k out ≈ **$0.10**.
- Rate: ~half the 8 conditions deterministic (free); 4 judgment cases (Sonnet batch, scoped ~5k in / ~1k out each, shared cached criteria): ~$0.04.
- Verify (Opus realtime, sees atoms): ~20k in / ~3k out ≈ $0.10 + $0.075 ≈ **$0.18**.
- Gaps + validate + what-if (Sonnet+Opus batch, ~8 conditions, scoped): ≈ **$0.25**.
- Embeddings (gemini-embedding-001, ~180k tokens): ≈ **$0.03**.
- **Initial total ≈ $0.85–$1.10 per veteran.** (Conservative band; dominated by Opus verify + gap stages — the accuracy spend, deliberately.)

**Incremental (1 new doc on a 30-doc case):**
- Classify + extract that 1 doc (Flash-Lite realtime): ~7k in / ~1.5k out ≈ **$0.004**.
- Merge over compact DocFacts (Sonnet): ≈ **$0.05**.
- Re-run only ~1–3 affected condition chains (identify-delta + rate + verify + gaps, scoped, cached): ≈ **$0.06–$0.12**.
- Embed 1 doc: ≈ **$0.001**.
- **Incremental total ≈ $0.12–$0.18** — roughly **1/6th of initial**, versus the legacy ~100% (or unbounded, given the re-run loop). This is the headline incrementality win.

**Chat session (~10 turns over a cached 120k-token case file):**
- Turn 1: cache write ~120k × $3.75/MTok (Sonnet 5m write) ≈ $0.45 once; output ~800 tok ≈ $0.012.
- Turns 2–10: cache **read** 120k × $0.30/MTok ≈ **$0.036/turn** + ~12k volatile in + ~800 out ≈ $0.05/turn → 9 × ~$0.05 ≈ $0.45.
- Query embeddings + retrieval: negligible (~$0.0001 total).
- **Session ≈ $0.90** (~$0.09/turn amortized). Without caching the same session is ~$3.50+ (cache read at 0.1x is the lever — research-agentic-patterns.md §5). On 1h-TTL with a chatty user it's cheaper still.

**Margin sanity at $11.99/mo:** initial ~$1, then a few incremental docs (~$0.15 each) + a couple of chat sessions (~$0.90 each) → a typical active month ≈ **$3–$5 of AI cost**, comfortably under the $4 fair-use cap's intent once the cap is fixed to count *all* spend (today extraction is submitted with `userId(null)` so it escapes the cap entirely — legacy weakness; my design caps the whole pipeline).

---

## 7. Migration path + risks

### 7.1 Migration (incremental, behind flags — never a big-bang on a live benefits tool)

1. **Stop the bleeding first (week 1, no architecture change).** Fix the perpetual re-run loop (invalidate by fingerprint, not model-string compare — `AnalysisScheduler.java:192`), add the missing failure transition (`allTerminal`), fix the double-submit (`LlmJobService.submit`), apply the Claude batch discount in `AiCostService`, enforce the usage cap on the scheduler path with non-null `userId`. These are bug fixes with immediate cost impact and don't depend on the new design.
2. **Re-point Claude to Vertex** (AnthropicVertex SDK, global endpoint) replacing direct `api.anthropic.com`; replace the Anthropic Message Batches calls with Vertex Batch Prediction (GCS JSONL).
3. **Build the artifact graph + content-addressed keys** alongside the existing tables (DocFacts/CaseModel as new generations; keep writing legacy rows in parallel for one release to compare).
4. **Parse full 38 CFR Part 4** into structured records; stand up the `vasrd_lookup` tool + nightly eCFR refresh; resurrect `VasrdDecisionEngine` on the parsed data.
5. **Introduce structured output + abstention + two-pass citations** stage by stage, each gated by the golden-set eval (build the golden set *before* this step — it is the safety net).
6. **Add pgvector hybrid retrieval + KB ingestion**; rebuild chat cache-first with streaming.
7. **Flip `active_generation`** to the new pipeline per-cohort once eval parity + improvement is demonstrated; retire the legacy state machines.

### 7.2 Risks

1. **OCR/text-layer coverage unknown.** If a large fraction of veteran scans lack a text layer, Citations breaks on them and OCR cost/latency rises. Mitigation: text-layer probe + Document AI fallback at ingest; **needs a sample audit of real uploads** (open question in research).
2. **Judge/golden calibration needs a domain expert.** "Completeness of gap analysis" and rating-band correctness can't be self-validated. Mitigation: recruit a VSO advisor to label the calibration set; until then, treat judge scores as directional and lean on deterministic validators + citations. **Open product dependency.**
3. **Vertex Batch support for Opus 4.8/Fable 5 unconfirmed** in the research snapshot. Mitigation: use Sonnet 4.6 (confirmed batch) for batched stages; keep Opus on realtime verify; confirm before batching on Opus 4.8.
4. **Opus 4.7+ tokenizer inflates tokens ~35%** vs older models — my cost estimates use list prices but real token counts may run higher on Opus stages. Mitigation: re-baseline against actual `AiCallLog` after migration; the eval harness tracks cost-per-case as a first-class metric.
5. **Stable condition identity across re-analysis is subtle.** Carrying identity by `(vasrd_code, body_system, theory)` can mis-merge when a veteran legitimately has two distinct conditions under one DC (bilateral, separate joints). Mitigation: the deterministic bilateral/pyramiding rules already disambiguate; golden cases must include multi-condition-per-DC scenarios.

---

## Appendix: top legacy defects this design eliminates (verified)

| Legacy defect (cited) | This design |
|---|---|
| Empty identify → silent COMPLETE-as-zero-conditions (`SynthesisStateMachine.java:135-140`, verified) | First-class `abstain_low_evidence`; never auto-completes as zero. |
| Rate parse-fail → silent 0%/0.3 confidence (`RatingAgent.java:122-130`, verified) | Structured output + abstention → "needs review," not a wrong 0%. |
| No canonical VASRD list in identify; 37-code lookup (verified) | `vasrd_lookup` tool over full parsed Part 4 + as-of dates. |
| Verifier never sees atoms; findings discarded (legacy-synthesis.md §2.4, w8) | Verifier sees atoms+citations; verdicts are binding (veto + suppress). |
| Full-redo + append duplicates + re-run loop (legacy §4, verified) | Content-addressed delta path; supersede; atomic generation flip. |
| Full atom dump per condition, no caching (legacy w10) | Scoped retrieval + shared cached criteria prefix. |
| One FAILED job wedges the claim forever (legacy w4) | `allTerminal` failure transition → "needs review," claim proceeds. |
| Claude cost 6x overstated, cap unenforced on pipeline (legacy w5,w6) | Vertex batch pricing applied; cap enforced across all stages w/ real userId. |
| No citations, no structured output (verified in providers) | Two-pass: json_schema extraction + server-validated citations. |
| No eval harness | Golden-set CI gate on every prompt/model/schema/KB change. |
