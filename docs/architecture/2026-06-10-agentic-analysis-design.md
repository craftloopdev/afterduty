# Agentic Condition Synthesis & Gap Analysis on Vertex AI Claude — Final Architecture

**Date:** 2026-06-10 · **Status:** Approved design (synthesized from a 3-architect / 2-judge panel)
**Method:** 25-agent review — 5 code readers mapped the legacy pipeline, 4 researchers established
ground-truth pricing/capabilities, 3 architects proposed competing designs (accuracy-first,
cost-first, ship-first), 2 adversarial judges verified every load-bearing claim against source
and audited all cost arithmetic. Full materials: [`proposals/`](proposals/),
[`research/`](research/), [`baseline/`](baseline/).

**Verdict (both judges concur):** the **accuracy-first pipeline**, delivered via the
**ship-first 8-increment migration**, with the **cost-first cache/batch discipline** as the
unit-economics layer.

**Honest cost target:** ~**$1–2 per initial analysis** (30 docs / ~300 pages),
~**$0.15–0.78 per incremental doc**, ~**$0.30 per chat session** — vs ~$15–25 per analysis on
today's path. ~10× cheaper *and* materially more accurate. At $11.99/mo, a heavy user costs
~$2–7/mo in model spend.

---

## Part 1 — The verified baseline (what runs today)

Every claim below was verified against source by at least two independent agents.

### What actually routes where (nothing runs on Vertex Claude today)

| Purpose | Model | Path |
|---|---|---|
| All 6 extraction purposes, synthesis identify/rate/verify, gap_whatif | `gemini-3.1-pro-preview` | Vertex realtime SSE, temp 0.2 |
| synthesis_duplicate_merger, gap_evidence, gap_validation | `claude-opus-4-7` | **Direct Anthropic Batches API** (api.anthropic.com, API key) — *not* Vertex |
| Ask AI chat | `claude-opus-4-7` | Direct Anthropic, synchronous, tool-use loop |

- `va-claim.vertex.claude-region` is **dead config** — no Claude-on-Vertex code path exists
  (`LlmProviderRouter.java`). The goal of "Claude through Vertex on GCP" is not yet implemented at all.
- `purposeDefaultModels` is never populated (`LlmProviderRouter.java:32,86-95`), so `CLAUDE_MODEL` /
  `GEMINI_MODEL` env vars **do not change routing** — defaults are hard-coded at `:91-92`.
- `LlmJobRequest.preferredModel/preferredProvider` overrides exist but have zero callers — a free
  escalation hook.

### The six defects that dominate cost and correctness

1. **Silent zero-conditions** — an unparseable/empty identify result marks synthesis COMPLETE with
   zero conditions (`SynthesisStateMachine.java:135-140`). A veteran with a 300-page file can be told
   "no conditions found" because of a JSON parse failure. *Worst veteran-harm bug in the system.*
2. **Perpetual re-run loop** — `AnalysisScheduler.java:192/207` re-triggers synthesis/gap when the
   *configured model string* differs from the stored one; combined with hard-coded router defaults,
   claims re-analyze forever.
3. **Full redo + compounding duplication on every new upload** — new doc resets
   `extractionState=NONE`; the state machine re-extracts **all** docs (`ExtractionStateMachine.java:120`
   `findByClaimId`, no pending filter), appends duplicate Atoms (no dedupe), re-parses **stale
   `ClaimPipelineJob` rows from prior runs** (`deleteByClaimIdAndStage` exists, zero call sites), and
   appends duplicate `IdentifiedCondition` rows (async path never deletes).
4. **O(conditions × atoms) token blowup** — `RatingAgent` resends the **entire atom corpus per
   condition**; 12 conditions ⇒ 12 full-corpus sends, uncached.
5. **Chat is unmetered Opus** — synchronous `claude-opus-4-7`, no prompt caching, writes **no
   `AiCallLog`**, unbounded 429 retry, `Thread.sleep(30s)` on the request thread, free-tier users not
   gated (`ChatAgent.java:52,172-174,402`).
6. **Cost ledger is fiction** — `AiCostService.java:28` prices Opus 4.7 at $15/$75 (real Vertex/API
   price: $5/$25; batch $2.50/$12.50), never applies the 50% batch discount, never populates cache
   columns; extraction jobs escape the $4/mo cap via `.userId(null)`. Reported Claude spend is
   ~3–6× reality, so cost-driven decisions (including the $4 cap itself) are mis-calibrated.

Also verified: `vasrd_codes.json` covers **37** diagnostic codes (Part 4 has **202 sections**);
`WhatIfScenarioGenerator.java:43` says "use 2024 VA rate tables" (stale); the gap stage runs ~3 calls
× 8 conditions on direct-API Opus — the single most expensive stage.

---

## Part 2 — Target architecture

### Principles (where the panel converged)

1. **Static DAG for analysis, agentic loop for chat only.** Anthropic's own guidance: multi-agent ≈
   15× token cost and suits independent-context problems; one veteran's case file is maximally
   interdependent. The pipeline is a deterministic map-reduce DAG. Exactly **two** dynamic surfaces:
   the chat agent (tool-use loop) and a bounded ≤2-turn self-repair inside synthesis verification.
   *(The "fully dynamic agents" instinct is answered with: dynamic where judgment varies case-by-case
   — escalation, repair, chat investigation — static everywhere repeatability and auditability win.)*
2. **Abstention is a first-class, presented state.** Every stage schema includes
   `not_found / insufficient_evidence / abstain` fields. An empty identify result becomes
   `abstain_low_evidence` → escalation, never silent COMPLETE. "We couldn't confirm X from your
   records" is shown to the veteran as honest output — for a benefits tool, a confident wrong answer
   is the unrecoverable failure.
3. **Every factual claim is grounded** — to a PDF page (Citations API) or a 38 CFR section (typed
   `vasrd_lookup`). Math is never done by an LLM (`VaMathService` + resurrected `VasrdDecisionEngine`).
4. **Cache-first, batch-default economics.** Stable-prefix prompts under `cache_control`; everything
   non-interactive rides the 50%-off batch lane; `cache_read_input_tokens > 0` asserted in CI and
   alerted in prod.
5. **Content-addressed artifacts, supersede-don't-append.** Incrementality is structural, not a
   timestamp heuristic.

### Pipeline & model routing

All Claude via **Vertex AI global endpoint** (same price as direct API; keeps billing/IAM in GCP;
`anthropic-java-vertex:2.18.0` is *already on the backend classpath, unused*). us-central1 hosts no
Claude — global endpoint avoids the +10% regional premium. Verified prices per MTok in/out
(batch = 50%, cache reads = 0.1×): Fable 5 $10/$50 · Opus 4.8 $5/$25 · Sonnet 4.6 $3/$15 ·
Haiku 4.5 $1/$5 · Gemini 3 Flash $0.50/$3 · Gemini 3.1 Flash-Lite $0.25/$1.50.

| Stage | Static/Dynamic | Model | Lane | Notes |
|---|---|---|---|---|
| 0. Doc classify + text-layer probe | static | Gemini 3.1 Flash-Lite | realtime | doc_type, OCR-needed flag |
| 1. Per-doc extraction (MAP) | static | Gemini 3 Flash; escalate per-doc → Haiku 4.5 → Sonnet on abstention/validator fail | batch | structured outputs; native-PDF text when text layer exists; ~258 tok/page |
| 2. Embed + pre-LLM dedupe | static | gemini-embedding-001 (768-dim MRL) | batch | pgvector cosine + exact-token dedupe **before** any merge call |
| 3. Case-model merge (REDUCE) | static | Sonnet 4.6 | batch | over compact per-doc extractions, never raw docs |
| 4. Condition identify | static | Sonnet 4.6 | batch | abstention field; empty ⇒ escalate to Opus, never COMPLETE |
| 5. Rating — mechanical DCs | static | **deterministic code** (VasrdDecisionEngine on full parsed Part 4) | — | e.g. tinnitus DC 6260 = 10% in code; removes cost *and* a hallucination surface |
| 6. Rating — judgment DCs | static | Sonnet 4.6 + `vasrd_lookup` tool | batch* | criteria text verbatim from eCFR-parsed Part 4 with as-of date |
| 7. Gap analysis | static | Sonnet 4.6, DBQ-derived checklists | batch* | "the rater's form asks for X; your file lacks X" |
| 8. **Adversarial verify** | ≤2-turn repair loop | **Opus 4.8** (harder tier + different family than generator) | **realtime** | sees atoms + citations; **binding veto** — can suppress any unsupported sentence before the veteran sees it |
| 9. Plain-language strategy | static | Sonnet 4.6 | batch | down-to-earth wording; no-legal-advice rubric enforced by judge |
| Chat (Ask AI) | **agentic** (single agent + tools) | Sonnet 4.6, escalate hard questions → Opus 4.8 (same cached prefix) | realtime, streaming | see Chat section |

\* Batch lanes that fan out per-condition assume prompt-cache reads work **within one Vertex batch
job** — flagged unverified by both judges. Until the spike (Increment 0) confirms it, price these
lanes at cache-write rates and/or run them realtime-cached; do **not** bank the 0.1× fan-out saving.

**Fable 5** ($10/$50) is *not* default anywhere; adopt only if golden-set evals show the 2× premium
buys measurable accuracy on identify/verify. Opus 4.8/Fable 5 **batch availability on Vertex is
unconfirmed** — keep Opus stages realtime until verified (snapshot confirms batch through Opus 4.7,
Sonnet 4.6, Haiku 4.5).

### Two-pass output (citations + structured data are incompatible)

The Citations API 400s when combined with structured outputs, so every user-facing stage is two-pass:
**Pass A** structured extraction (json_schema, enums, abstention fields) → **Pass B**
citation-grounded narrative pinned to exact PDF pages (server-validated; `cited_text` isn't billed).
Scanned no-text-layer docs are detected at upload (stage 0) and OCR'd (DocAI $1.50/1k pages) or
flagged — they cannot be cited otherwise. **An upload-corpus text-layer audit is an open
pre-implementation task.**

### Incremental updates (the artifact graph)

Artifact DAG: `DocFacts(doc) → CaseModel → Condition_i → {Rating_i, Gaps_i} → Strategy`, each node
keyed by `hash(inputs, prompt_version, schema_version, model_id)` and stored with its fingerprint.

New document upload:
1. **One** classify+extract call for the new doc (~$0.005–0.013) + embed (~$0.001). All other
   DocFacts artifacts remain valid by content-hash — *never recomputed* (kills
   `findByClaimId`-over-everything).
2. Deterministic pre-LLM dedupe folds new facts into CaseModel; emits a **dirty set** of affected
   condition IDs (typically 0–2 of 12).
3. Only dirty conditions re-run identify-delta/rate/gap/verify (~$0.14–0.46); clean conditions
   untouched. No new facts ⇒ pipeline stops after step 2 (~$0.01 total).
4. **Generation-pointer activation**: new run writes `run_id`, flips the claim's active-generation
   pointer atomically, marks old rows `superseded_by` (column already exists, unused). Old rows are
   kept → chat citations into prior analyses stay valid. Veteran never sees a half-updated analysis.
5. **Stable condition identity** across re-analysis via `(vasrd_code, body_system, theory)`
   fingerprint so chat references and user-set gap statuses survive re-runs. Known risk (judge-flagged):
   bilateral/dual-joint conditions can share a DC — fingerprint must include laterality/site.

Replaces: timestamp triggers, model-string re-run loop (kill `AnalysisScheduler:192/207` comparison),
append-only duplication, stale-job re-parsing (finally call `deleteByClaimIdAndStage`).

### Chat system (fast, grounded, cheap)

**Single agent** (never multi-agent) on Sonnet 4.6, streaming SSE through the Next.js BFF.

- **Grounding tools:** (1) `search_my_file` — hybrid retrieval over the veteran's own chunks:
  pgvector HNSW (768-dim) + tsvector full-text fused by RRF *in one SQL query* (claims language is
  exact-token-heavy: "DC 5260", "DBQ", dates); (2) `get_analysis` — direct reads of
  CaseModel/conditions/gaps artifacts; (3) `vasrd_lookup` / `va_kb_search` — VASRD + M21-1 +
  presumptives KB, every chunk carrying its eCFR as-of date. Answers cite CFR section / M21-1 / the
  veteran's own page — **citation-first UX is table stakes** (every credible competitor does it).
- **Cache-first prompting:** frozen system prompt + case digest (~8–25K tokens) under
  `cache_control` (1h TTL), question varies last → turn 1 writes, turns 2+ read at 0.1×
  (~$0.02–0.03/question, ~$0.17–0.30 per session). Audit for silent cache invalidators (timestamps,
  unsorted JSON). Escalate hard "what's missing" questions to Opus 4.8 on the *same* cached prefix.
- **pgvector on the existing Cloud SQL** — $0 marginal infra, instant freshness after upload,
  per-veteran isolation by `user_id`. Vertex AI Search rejected (min viable config ~$6,100/mo;
  storage metering unknowns); Vector Search rejected (~$68/mo minimum + ops for zero benefit at this
  scale).
- **Fixes inherited:** chat writes `AiCallLog` (it currently writes nothing), gated to paid tier,
  bounded retries, no `Thread.sleep` on request threads.

### VA knowledge bases (all free / public domain)

| Source | Use | Ingestion |
|---|---|---|
| **38 CFR Part 4** (202 sections, 1.06MB XML) | `vasrd_lookup` tool; deterministic DC→%→criteria records; rating-table math | eCFR versioner API, nightly `titles.json` diff → re-ingest changed sections; every chunk stamped "current as of" (the Feb 2026 §4.10 publish-then-rescind proves freshness = correctness) |
| **38 CFR Part 3** (3.307–3.320) | presumptives (PACT Act, Agent Orange, Gulf War) as hand-curated structured eligibility rules | eCFR + Federal Register API watch |
| **Public DBQs** (~70 PDFs) | gap-analysis evidence checklists per condition | parse questions/measurements per DBQ |
| **M21-1** | adjudication-procedure grounding for chat | KnowVA scrape per section; Changes-By-Date re-scrape |
| **BVA decisions** (1.85M+, CC0, monthly) | future "similar past decisions" feature (what V2V sells at $9.99/mo) — educational framing | sitemap-indexed .txt ingest |

### Accuracy & evaluation

- **Golden set before model flips:** 20–50 synthetic/redacted case files with expected
  extractions/conditions/gaps; CI regression on every prompt/schema/model change; judge from a
  different model family (Gemini judges Claude) with no-legal-advice and citation-accuracy as hard
  rubric lines. **Open dependency:** a VSO/domain expert to calibrate "gap-analysis completeness"
  and rating bands.
- **Binding verifier** (stage 8) with atoms+citations visibility and sentence-level suppression.
- **Escalation monitor:** per-doc escalation >40% erases the cheap-tier savings — alert at 25%.
- **Token re-baseline:** Opus 4.7+ tokenizer inflates counts up to ~35% — re-baseline all estimates
  against real `AiCallLog` data after Increment 2.

### Cost model (judge-audited arithmetic)

| Scenario | Cost | Basis |
|---|---|---|
| Initial analysis (30 docs/300pp, 12 conditions) | **~$1.96** conservative; ~$0.82–1.43 if batch+cache confirmed | extract $0.195 (Flash batch) + identify $0.15 + merge $0.06 + rate ~$0.39 + Opus verify $0.23 + gap ~$0.90 + embed $0.02 *(ship design, line-audited)* |
| + cache-write correction | +~$0.36 one-time | first 60K-token atom-block cache write at 1.25–2× (judge-found omission) |
| Incremental doc (typical) | **~$0.01–0.78** | $0.01 no-new-facts → $0.78 with identify + 2 dirty conditions |
| Chat session (8 questions) | **~$0.30** | 25K cached prefix: $0.16 write + ~$0.02/question reads |
| Heavy user month | **~$2–7** | vs $11.99 Pro price — sustainable headroom |

The cost-first design's $0.32 headline was judged "best-case-stacked marketing" — its own honest
figure is $0.82. **Use $2/initial for planning until the two cache spikes resolve.**

### Free / paid gating (unchanged policy, fixed enforcement)

Free: uploads + VSO sharing (+ doc storage, classification). Paid ($11.99/mo / $119.99/yr — Stripe
web, RevenueCat/Apple/Google Pay via Capacitor): extraction-analysis pipeline, synthesis, gaps,
strategy, chat. Fix: chat gated + metered (today it's free Opus for anyone); kill `.userId(null)`
cap escapes; raise the $4/mo cap to ~$8 *after* Increment 0 makes the ledger honest (the cap is
currently calibrated against 3–6×-overstated costs).

---

## Part 3 — Migration plan (8 reversible increments)

Each independently shippable, behind a flag/interface, with rollback. Order = ROI.

| # | Increment | What | Why first |
|---|---|---|---|
| 0 | **Cost truth + spikes** | Fix `AiCostService` prices (+50% batch discount, cache columns); route chat through `AiCallLog`; kill `.userId(null)`; **spike: cache-reads-within-Vertex-batch; spike: Opus 4.8/Fable 5 Vertex batch support; audit upload text-layer coverage** | Every later decision depends on honest numbers; zero new infra |
| 1 | **Stop the bleeding** | Kill model-string re-run loop; fix silent zero-conditions → `abstain` + escalate; add FAILED branch to `allSucceeded` gates (use the existing unused `allTerminal` helper); call `deleteByClaimIdAndStage` | Top correctness bugs; pure deletions/guards |
| 2 | **Claude-on-Vertex** | Wire `anthropic-java-vertex:2.18.0` (already on classpath) as a provider; populate `purposeDefaultModels`; synthesis/gap → Sonnet 4.6 batch; verify → Opus 4.8 realtime; chat → Sonnet 4.6 | The goal's core ask; ~3–5× cost cut from Opus 4.7-direct |
| 3 | **Artifact keying + delta extraction** | `(content_hash, prompt_version, schema_version, model_id)` keys on per-doc extractions; extraction fan-out filters to changed docs; pre-LLM dedupe | Kills full-redo; biggest incremental-cost lever |
| 4 | **Cache discipline** | Stable-prefix prompts; `cache_control` on atom-block/case digest; CI+prod assert `cache_read_input_tokens > 0`; fix `RatingAgent` full-corpus resend (cached block + per-condition tail) | Kills O(cond×atoms); the 0.1× lever |
| 5 | **Two-pass + abstention everywhere** | json_schema pass A + citations pass B per user-facing stage; abstention fields; golden set gates each stage flip | Accuracy spine; golden set built here |
| 6 | **Deterministic rating + full VASRD** | Parse full Part 4 (202 sections) via eCFR; resurrect `VasrdDecisionEngine` for mechanical DCs; `vasrd_lookup` tool for judgment DCs; nightly eCFR diff | Removes hallucination surface + cost |
| 7 | **Generation-pointer incrementality** | `run_id` + `superseded_by` activation; dirty-set condition re-runs; stable condition identity (with laterality) | Full delta path; chat-reference stability |
| 8 | **Chat v2 + KBs** | Hybrid pgvector+tsvector RRF retrieval; DBQ checklists in gap stage; M21-1/presumptives KB; citation-first chat UX; Opus escalation on cached prefix | The product's "fast good chat" promise |

Parallel track (frontend): the UI fixes from the companion review
([`../qa/ui-review/2026-06-10-user-story-map.md`](../qa/ui-review/2026-06-10-user-story-map.md)) —
particularly the broken Share-invite delivery, dead Add-Evidence CTAs, Ask AI nav entry, and
free-vs-Pro states — so pipeline improvements actually reach the veteran.

## Part 4 — Risks & open questions

1. **Cache reads within a Vertex batch job** — unverified; load-bearing for the cheap fan-out
   estimates. Resolve in Increment 0; until then plan at $2/initial.
2. **Opus 4.8 / Fable 5 on Vertex batch** — unconfirmed; keep Opus realtime.
3. **OCR/text-layer coverage of real uploads** — gates Citations on scanned records; audit needed.
4. **Golden-set calibration needs a VSO** — open product dependency for "complete gap analysis."
5. **Tokenizer drift** (+35% Opus 4.7+) — re-baseline from real `AiCallLog` post-Increment 2.
6. **Vertex constraints:** no Files API → docs inline base64 (30MB cap) or pre-extracted text;
   structured outputs ✅; web search tool ✅ ($10/1k searches); quota (QPM/TPM) increase before launch.
7. **Bilateral condition identity** — fingerprint must include laterality or merges corrupt history.
8. **62→84% hybrid-retrieval precision claim is one unverified third-party bench** — treat as
   directional only.
