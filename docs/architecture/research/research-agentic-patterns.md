# Research: Agentic Document-Analysis Patterns — Accuracy + Cost + Incrementality

**Date:** 2026-06-10
**Scope:** Current (2025–2026) best practice for high-accuracy, cost-effective agentic document-analysis pipelines, researched for the VA Claim Path product review. All pricing/limit numbers below were verified against official Anthropic documentation on 2026-06-10; third-party claims are flagged as such.

---

## Findings

### 1. Orchestrator-worker vs static DAGs: when dynamic routing beats fixed pipelines

**The consensus position (Anthropic + Google Cloud): start with a fixed workflow; reach for dynamic agents only when subtasks are unpredictable.**

- Anthropic's "Building Effective Agents" defines five workflow patterns — prompt chaining, routing, parallelization, orchestrator-workers, evaluator-optimizer — and is explicit that "optimizing single LLM calls with retrieval and in-context examples is usually enough" for many applications. Workflows give "predictability and consistency for well-defined tasks"; agents are for tasks whose steps can't be hardcoded. Core principle: *add complexity only when simpler approaches demonstrably fail.*
  Source: https://www.anthropic.com/engineering/building-effective-agents (also at /research/building-effective-agents)
- **Orchestrator-workers** is the right pattern specifically when *you cannot predict the subtasks in advance* (e.g., multi-file code changes, open-ended research). Its defining feature vs parallelization is that subtasks are determined by the orchestrator per-input, not pre-defined. If you *can* enumerate the steps (extract per doc → merge → synthesize), a static DAG with programmatic checkpoints is cheaper, more debuggable, and more predictable.
- Google Cloud's Architecture Center guidance matches: "agents are effective for applications that solve open-ended problems"; for tasks with predefined steps (summarization, classification, extraction), non-agentic pipelines are "more efficient and cost-effective." It also warns about "tool bloat" (keep tools <5 parameters, use granular toolsets, progressive disclosure).
  Source: https://docs.cloud.google.com/architecture/choose-agentic-ai-architecture-components
- **Multi-agent economics (Anthropic engineering, multi-agent research system):** a lead-Opus + Sonnet-subagents system beat single-agent Opus by **90.2%** on their internal research eval — but research agents use ~**4x** the tokens of chat and multi-agent systems ~**15x**. Token usage alone explained ~80% of performance variance. Multi-agent wins **only when the task decomposes into independent parallel threads**; it performs *poorly* when subtasks share context or have many interdependencies (their example: coding). Orchestrator prompts need effort-scaling rules ("simple fact-finding = 1 agent, 3–10 tool calls") and explicit subagent task descriptions (objective, output format, tool guidance, boundaries) or subagents duplicate work.
  Source: https://www.anthropic.com/engineering/multi-agent-research-system

**Practical rule for document pipelines:** the document-analysis core (per-doc extraction → merge → report) is a *known* decomposition → static DAG. Dynamic agent routing pays off only at the edges: open-ended Q&A/chat over the case file, "find what's missing" investigations, and deciding *which* follow-up lookups to make.

### 2. Verification patterns: LLM-as-judge, adversarial checks, citation grounding, abstention

**LLM-as-judge works in production but must be debiased and calibrated against humans.**
- Anthropic's research system used a *single LLM call with a single rubric prompt* outputting 0.0–1.0 scores across factual accuracy, citation accuracy, completeness, source quality, tool efficiency — plus **end-state evaluation** (judge the final artifact, not intermediate steps) and human spot-checks for edge cases. (Source: multi-agent post above.)
- Known judge biases and mitigations (2025–2026 literature): **position bias** (mitigate by evaluating both orderings and averaging — prompt instructions like "consider both equally" have ~zero effect), **verbosity bias** (measured 15–30 points of inflated preference for longer outputs; an explicit "do not prefer longer answers" rubric line roughly halves it; length-controlled metrics help), **self-preference bias** (use a judge from a different model family than the generator, or an ensemble). Continuous calibration against a small human-labeled set is considered mandatory before trusting judge scores.
  Sources: https://mbrenndoerfer.com/writing/position-bias-in-llm-judges ; https://futureagi.com/blog/evaluating-llm-judge-bias-mitigation-2026/ ; https://deepchecks.com/llm-judge-calibration-automated-issues/ ; https://www.emergentmind.com/topics/llm-as-a-judge-evaluations
- **Adversarial verification** in practice = a second pass with an opposite incentive: generator instructed for *coverage* ("report every candidate finding with confidence + severity, do not self-filter"), verifier instructed for *precision* (filter/dedup/rank downstream). Anthropic's own Opus 4.7/4.8 migration guidance codifies this for review harnesses: models follow "only report high-severity" instructions literally, so self-filtering at generation time depresses recall — move filtering to a separate stage. (Source: Anthropic model migration guidance, platform.claude.com docs.)

**Citation grounding (verified against official docs):**
- Anthropic's **Citations** feature: enable `citations: {enabled: true}` on document blocks; responses interleave text blocks with citations pointing at exact source locations — **page-number ranges for PDFs (1-indexed), char ranges for plain text, block indices for custom content**. Citations are *parsed and validated server-side*, so pointers are guaranteed to reference the provided documents (no fabricated page numbers). `cited_text` does **not** count toward output tokens (and not toward input tokens when passed back). Anthropic reports the feature is "significantly more likely to cite the most relevant quotes" vs prompt-based citing; one customer (Endex, per Anthropic's announcement) went from 10% source hallucinations to 0%.
  Sources: https://platform.claude.com/docs/en/build-with-claude/citations.md ; https://www.anthropic.com/news/introducing-citations-api ; https://techcrunch.com/2025/01/23/anthropics-new-citations-feature-aims-to-reduce-ai-errors/
- **Critical constraint:** Citations are **incompatible with structured outputs** — combining `citations.enabled` with `output_config.format` returns a 400. A pipeline that wants both must split into (a) a structured-extraction pass and (b) a cited narrative/verification pass. Also: **scanned PDFs without extractable text are not citable** (image citations unsupported) — OCR or text-layer quality is a gating dependency. Citations work *with* prompt caching and the Batch API.
- All active Claude models support citations; the feature is also available on Vertex AI (per Anthropic's announcement).

**Abstention / confidence:**
- 2025 research distinguishes behavioral refusal from *epistemic abstention* and converges on multi-layer designs: schema-level "not_found"/"insufficient_evidence" output options, self-consistency / token-entropy uncertainty estimates, and conformal-prediction-based abstention with finite-sample correctness guarantees. Key empirical finding: models default to answering rather than admitting uncertainty; prompting helps but doesn't eliminate miscalibration — so pipelines should make abstention a *first-class schema field* and route abstentions to escalation (bigger model or human) rather than hoping the model says "I don't know."
  Sources: https://arxiv.org/pdf/2509.01455 (unified confidence-calibration + risk-controlled refusal framework) ; https://arxiv.org/html/2604.27914 (conformal abstention) ; https://arxiv.org/pdf/2510.24020 (fine-grained semantic-confidence abstention)

### 3. Incremental / delta processing when new documents arrive

- **Map-reduce over per-document extractions is the standard shape.** DocETL (UC Berkeley, VLDB 2025) formalizes LLM document pipelines as Map (per-doc transformation adding schema keys) / Reduce (aggregate docs sharing keys) / equijoin operators, with **incremental pipeline execution** as a built-in feature — only re-run operators whose inputs changed. LLM×MapReduce (2024–2025) adds a "collapse" stage between map and reduce to compress mapped outputs before merging.
  Sources: https://www.vldb.org/pvldb/vol18/p3035-shankar.pdf ; https://arxiv.org/pdf/2410.12189 ; https://arxiv.org/pdf/2410.09342 ; https://thedataexchange.media/docetl/
- **Artifact caching + dependency invalidation** (engineering practice; assembled from the above + standard build-system design — no single canonical source): persist each per-document extraction as an artifact keyed by `(content_hash, prompt_version, schema_version, model_id)`. A new document triggers exactly one map call plus a re-run of the (cheap) merge/synthesize stages; a prompt or model change invalidates only artifacts keyed to the old version. The merge stage should consume *compact structured extractions* (hundreds of tokens/doc), not raw documents, so re-synthesis over N docs stays cheap even as N grows.
- **The asymmetry to engineer for:** map calls are expensive and embarrassingly parallel (→ Batch API, 50% off, fine for "your analysis is being updated" UX); reduce/synthesize is one interactive-speed call over small inputs (→ run on demand). This is exactly the artifact split that makes "user uploads 1 new document to a 40-doc case" cost ~1/40th of initial analysis instead of 100%.

### 4. Structured-output reliability

- **Constrained decoding is now the default mechanism** (grammar-compiled logit masking; XGrammar is the default backend in vLLM/SGLang/TensorRT-LLM with ~<40µs/token overhead). On hosted APIs: Anthropic `output_config: {format: {type: "json_schema", ...}}` (or SDK `messages.parse()` with Pydantic/Zod) and `strict: true` tool schemas; OpenAI Structured Outputs are equivalent. Schemas must set `additionalProperties: false`; recursive schemas and numeric/string constraints (min/max, minLength) are unsupported on Anthropic — SDKs strip them and validate client-side. First use of a schema incurs a one-time compilation cost (then 24h schema cache).
  Sources: https://platform.claude.com/docs/en/build-with-claude/structured-outputs.md ; https://www.aidancooper.co.uk/constrained-decoding/ ; https://arxiv.org/html/2501.10868v1 ; https://openai.com/index/introducing-structured-outputs-in-the-api/
- **The 2025 hard-won lesson: schema compliance ≠ semantic correctness.** A perfectly valid JSON object can still contain wrong values. Reliability stack: (1) constrained decoding for syntax; (2) enums/`const` wherever the value space is closed (e.g., VA form types, body systems); (3) nullable/abstention fields rather than forcing a value; (4) post-hoc deterministic validators (dates parse, page refs ≤ doc length, cross-field consistency); (5) semantic spot-check via judge or citation-grounding on high-stakes fields.
  Sources: https://rotascale.com/blog/structured-output-isnt-reliable-output/ ; https://collinwilkins.com/articles/structured-output
- On Anthropic specifically: structured outputs are incompatible with citations (400) and with prefilling; `stop_reason: "max_tokens"` can truncate valid JSON — check it; `stop_reason: "refusal"` output may not match schema.

### 5. Prompt-caching-aware architecture (stable prefix design)

Verified against https://platform.claude.com/docs/en/build-with-claude/prompt-caching.md and the official pricing page:

- **Caching is a strict prefix match** over rendered bytes in order `tools → system → messages`. Any byte change invalidates everything after it. Design rules: frozen system prompt (no timestamps/UUIDs/user IDs interpolated), deterministic tool serialization (sorted), volatile per-request content *after* the last `cache_control` breakpoint. Max 4 breakpoints/request; minimum cacheable prefix is model-dependent (e.g., 4096 tokens on Opus 4.8/Haiku 4.5-class models, 1024–2048 on Sonnet-class — shorter prefixes silently don't cache).
- **Economics:** cache read = **0.1x** base input price; write = **1.25x** (5-min TTL) or **2x** (1-hour TTL). 5-min caching breaks even after a single read. Multipliers stack with the Batch discount.
- **Pipeline implication:** for repeated Q&A/chat over the same case file, structure requests as `[stable system prompt + document blocks (cache_control on last doc)] + [varying question]`. The official PDF docs explicitly recommend `cache_control` on the document block for repeated analysis. Verify with `usage.cache_read_input_tokens` — zero across repeated requests means a silent invalidator (timestamp in system prompt, unsorted JSON, varying tool set).
- **Concurrency gotcha:** a cache entry is readable only after the first response begins streaming — for fan-out (N parallel questions over one doc set), send 1 request, await first token, then fire the rest, or all N pay full write price.
- Agent-specific corollaries: don't swap tools or models mid-session (full invalidation); use subagents on a cheaper model rather than switching the main loop's model; mid-conversation operator instructions go in `messages` (system-role message, beta) rather than editing the top-level system prompt.

### 6. Eval harnesses for domain pipelines (golden sets, regression evals)

- The 2025 consensus stack (Hamel Husain / Shreya Shankar, "AI Evals for Engineers & PMs"; Pragmatic Engineer): **Traces → Error analysis → Golden dataset → LLM-judge validated against humans → CI integration.** The most-skipped, most-important step is **error analysis**: manually read 50–100 real traces and open-code the failure modes — *that* is where the judge rubric comes from, not from generic metric libraries.
  Sources: https://hamel.dev/blog/posts/evals-faq/ ; https://newsletter.pragmaticengineer.com/p/evals ; https://www.news.aakashg.com/p/hamel-shreya-podcast-2
- **Golden set** = versioned input fixtures (here: synthetic/redacted case files) + expected structured outputs + rubric criteria, run like unit tests on every prompt/model/pipeline change; production traces that fail get triaged into new golden cases (the regression loop). Judges themselves need a small human-labeled calibration set, and judge prompts are versioned artifacts like any other prompt.
  Source: https://www.getmaxim.ai/articles/building-a-golden-dataset-for-ai-evaluation-a-step-by-step-guide/
- Anthropic's multi-agent post adds two domain-pipeline-relevant tactics: **end-state evaluation** (grade the final report, not the trajectory) and starting small — even ~20 hand-picked test cases catch most regressions early.

### 7. Long-context vs RAG for 50–500-page case files

- **Capacity is no longer the constraint on Claude:** PDF support allows up to **600 pages per request** (100 pages for 200K-context models) and **32 MB max request size**; 1M-token context on Opus 4.6+/Sonnet 4.6 is billed at **standard pricing with no long-context premium** (official pricing page). Each PDF page costs roughly **1,500–3,000 text tokens + image tokens** (pages are also rendered as images for visual understanding). A 500-page file ≈ 0.75M–1.5M tokens — at or beyond a single request even on 1M-context models; dense files can exhaust context before the page limit.
  Source: https://platform.claude.com/docs/en/build-with-claude/pdf-support.md
- **Accuracy degrades with stuffing:** 2025 evaluations consistently find multi-hop/aggregation accuracy dropping between ~32K and 128K tokens depending on model, and 10–20+ point drops when relevant info sits mid-context ("lost in the middle"). RAG won on cross-document synthesis queries; long-context won on simple single-passage queries. Third-party cost studies report order-of-magnitude cost/latency advantages for retrieval at scale (treat exact figures — "94% cheaper," "1,250x" — as single-study claims, not verified constants).
  Sources: https://arxiv.org/pdf/2501.01880 (Long Context vs RAG: An Evaluation and Revisits) ; https://tianpan.co/blog/2026-04-09-long-context-vs-rag-production-decision-framework ; https://dev.to/gabrielanhaia/long-context-models-killed-rag-except-for-the-6-cases-where-they-made-it-worse-1ico
- **2025–2026 winner is the hybrid:** retrieve/condense first, then reason long-context over the *relevant* subset ("RAG-augmented long context"). For a bounded case file, the cleanest hybrid is not vector RAG at all but the **map-reduce variant**: per-document structured extractions (computed once, cached) become the "retrieved" context for synthesis and chat; full raw documents are pulled into context only when a specific question requires page-level re-reading of one or two documents (then cited via the Citations feature). Vector RAG becomes necessary only for corpus-wide semantic search across *many users' files* or a VA-regulations knowledge base — not for a single user's 50–500-page file set.

### 8. Cost routing: cheap-model triage → escalate hard cases

- **Two production patterns:** *routing* (predict difficulty upfront, send to the right model) and *cascading* (always try cheap first; escalate on low confidence/quality). FrugalGPT (router + threshold quality scorer + stop judge) showed up to ~98% cost reduction on benchmarks; RouteLLM showed preference-trained routers generalize across model families. A 2026 survey (arXiv 2603.04445) consolidates the field.
  Sources: https://arxiv.org/html/2603.04445v2 ; https://tianpan.co/blog/2025-10-19-llm-routing-production ; https://genta.dev/resources/llm-routing-guide
- **Production heuristics:** cascades win when escalation rate stays low — if **>~40% of requests escalate**, the double-inference overhead erases savings; effective custom routers have been trained on **<1,500 labeled examples**, so production logs + judge labels are usually enough data. (Heuristics from the TianPan production writeup; directional, not lab-verified constants.)
- **Simplest robust design for a document pipeline** (and the one Anthropic's own docs gesture at — "Haiku for simple tasks, Sonnet for production workloads, Opus for complex reasoning"): static *stage-based* routing rather than learned per-query routing. Cheap model (Haiku 4.5, $1/$5 per MTok) for per-document classification + field extraction; mid/top model (Sonnet 4.6 $3/$15 or Opus 4.8 $5/$25) for cross-document synthesis, gap analysis, and chat; escalation triggered by explicit signals (abstention fields, validator failures, judge score below threshold) rather than a learned router. 5x price spread between Haiku and Opus means triaging the high-volume map stage down captures most of the win with none of the router complexity.
- Anthropic's caching/subagent guidance reinforces this: run cheap subagents for scoped subtasks instead of switching the main loop's model (which would invalidate the prompt cache).

---

## Pricing / Limits Table

All verified 2026-06-10 against https://platform.claude.com/docs/en/about-claude/pricing.md, pdf-support.md, citations.md, prompt-caching.md, and batch docs unless noted.

| Item | Value |
|---|---|
| Claude Opus 4.8 | $5 / MTok input, $25 / MTok output (1M context) |
| Claude Sonnet 4.6 | $3 / MTok input, $15 / MTok output (1M context) |
| Claude Haiku 4.5 | $1 / MTok input, $5 / MTok output (200K context) |
| Claude Fable 5 | $10 / MTok input, $50 / MTok output (1M context) |
| Long context | Full 1M window at **standard pricing** — no long-context premium (Opus 4.6+, Sonnet 4.6, Fable 5) |
| Prompt cache write | 1.25x base input (5-min TTL) / 2x (1-hour TTL) |
| Prompt cache read | 0.1x base input; breaks even after 1 read (5-min TTL) |
| Cache breakpoints | Max 4 per request; min cacheable prefix 1024–4096 tokens (model-dependent) |
| Batch API | 50% off input and output (e.g., Opus 4.8 $2.50/$12.50; Haiku 4.5 $0.50/$2.50); most batches <1h, max 24h, up to 100K requests / 256 MB; stacks with caching |
| PDF limits | 32 MB max request; **600 pages max/request** (100 pages on 200K-context models); standard PDFs only (no encryption) |
| PDF token cost | ~1,500–3,000 text tokens/page **plus** per-page image tokens (each page also rendered as an image) |
| Citations | All active models (except Haiku 3); PDF citations = 1-indexed page ranges; `cited_text` free of output/input token charges; **incompatible with structured outputs (400)**; scanned/no-text-layer PDFs not citable |
| Structured outputs | `output_config.format` json_schema; `additionalProperties: false` required; no recursive schemas / numeric constraints; one-time schema compile, 24h schema cache |
| Web search tool | $10 per 1,000 searches (if used for VA-regulation lookups) |
| Files API | 500 MB max file, 100 GB/org storage; upload once, reference by `file_id` (beta) |
| Tokenizer note | Opus 4.7+ uses a new tokenizer — up to **35% more tokens** for the same text vs older models (re-baseline cost estimates) |
| Multi-agent overhead | ~4x tokens (single agent w/ tools) to ~15x (multi-agent) vs plain chat — Anthropic engineering blog |
| Cascade heuristics | Escalation >~40% erases cascade savings; routers trainable on <1,500 labels (TianPan/RouteLLM — directional) |
| Provider surface note | Anthropic server-side tools & Managed Agents are first-party API only — **not** on Vertex AI/Bedrock; Citations *is* available on Vertex AI |

---

## Implications for VA Claim Path

VA Claim Path's paid tier is AI analysis over a veteran's uploaded claim documents (DD-214s, medical records, decision letters, C-file excerpts — i.e., 10s to 100s of PDF pages per user), with an Ask-AI chat and planned gap-analysis/scenario features. The research maps onto it like this:

1. **The analysis pipeline should be a static DAG, not an agent.** The decomposition is known: classify doc → extract per-doc structured facts (conditions, dates, ratings, in-service events, nexus statements) → merge into a case model → synthesize gap analysis / report. Anthropic's and Google's guidance both say agentic routing here adds cost and variance for nothing. Reserve agentic (orchestrator/tool-use) behavior for the two genuinely open-ended surfaces: Ask-AI chat and "what's missing for this claim" investigations — and even there, a single agent with tools, not multi-agent (the task is context-heavy and interdependent — exactly Anthropic's anti-pattern for multi-agent, and ~15x token cost).

2. **Build incrementality into the artifact model now.** Key each per-document extraction by `(doc content hash, prompt_version, schema_version, model_id)` and store it (DB/Firestore/GCS). A newly uploaded document = 1 map call + re-run of the cheap merge/synthesize stage — not a full re-analysis. This is the single biggest cost lever for the "veteran adds documents over months" usage pattern, and it also gives instant re-analysis UX. The merge stage should read compact extractions, never raw PDFs.

3. **Stage-based cost routing + Batch:** Haiku 4.5 ($1/$5) for per-doc classification/extraction (high volume, simple per-call task), Sonnet 4.6/Opus for synthesis, gap analysis, and chat. Escalate individual documents to the bigger model on explicit signals: abstention fields populated, validator failures (bad dates, page refs out of range), or low judge score. Run non-interactive work (initial multi-doc ingest, bulk re-analysis after a prompt upgrade) through the **Batch API for 50% off** — it supports PDFs, caching, and citations.

4. **Citations are close to mandatory for this domain — design the two-pass split.** An educational tool telling veterans what their records show must point at pages ("your 2019 C&P exam, p. 4"); Citations gives server-validated page-range pointers and turned 10% source hallucination into 0% for one customer. Because Citations is **incompatible with structured outputs**, the pipeline needs: pass A = structured extraction (json_schema, enums for condition/form types, explicit `not_found`/`insufficient_evidence` abstention fields); pass B = cited narrative/verification over the same cached document. Caveat: scanned records without a text layer aren't citable — detect text-layer quality at upload and OCR (or flag) those files.

5. **Prompt-caching-aware chat design.** For Ask-AI over a case file: frozen system prompt + document blocks with `cache_control` on the last doc; questions appended after. Every follow-up question then pays 0.1x on the whole case file. Audit for silent invalidators (no timestamps/user names in the system prompt, sorted tool JSON) and assert `cache_read_input_tokens > 0` in monitoring. For a 200-page file (~500K tokens incl. image tokens) this is the difference between ~$2.50 and ~$0.25 of input per question on Opus — it decides paid-tier unit economics.

6. **Long context vs RAG:** a single veteran's file fits the "condensed-context hybrid": extractions are the working context; pull a specific full document into context only when a chat question needs page-level re-reading (≤600 pages/request, but watch the 32 MB and dense-page caveats; split long files at ingestion into per-document or per-section blocks). Vector RAG is only warranted for a shared VA-regulations/38 CFR knowledge base — not for per-user files. Mind lost-in-the-middle: don't stuff all raw docs into one 800K-token prompt and hope; accuracy measurably degrades.

7. **Eval harness before shipping analysis changes.** Build a golden set of 20–50 synthetic (or consented/redacted) case files with expected extractions and known gaps; run extraction-accuracy assertions + an LLM-judge rubric (factual accuracy, citation accuracy, completeness, no-legal-advice tone) in CI on every prompt/model/schema bump. Derive the rubric from error analysis of real traces (read 50–100), not from a generic metric library. Judge debiasing: different model family than the generator where feasible, "don't reward verbosity" rubric line, small human-labeled calibration set. The no-legal-advice boundary is itself a rubric criterion worth judging on every output.

8. **Provider caveat:** if analysis runs via Vertex AI Claude (per the GCP deployment), Citations works there, but Anthropic server-side tools (web search, code execution) and Managed Agents do not — those require the first-party API. On Bedrock's Converse API, visual PDF understanding requires citations enabled. Worth deciding the provider split before designing the chat agent's tool surface.

---

## Open Questions

1. **Typical case-file size distribution for VA Claim Path users** — median pages/docs per user determines whether per-question full-file caching (implication 5) is affordable at the $11.99/mo price point, or whether chat must run over extractions only. Needs production data.
2. **OCR strategy for scanned records.** What fraction of uploaded VA medical records lack a text layer? (Citations won't work on them.) Options: Google Document AI OCR at ingest vs Claude vision-only extraction without citations vs rejecting/flagging. Unresolved; needs a sample audit of real uploads.
3. **Where exactly to draw the escalation threshold** (Haiku → Sonnet/Opus). The <40%-escalation heuristic is directional; the real threshold needs the golden set + cost telemetry to tune. Unknown until evals exist.
4. **Vertex AI vs first-party Anthropic API split.** Pricing parity is close but Vertex adds regional-endpoint premiums (10% regional vs global) and lacks server-side tools/Managed Agents; first-party adds a vendor relationship outside GCP billing. Not resolvable from public docs alone — depends on compliance posture (BAA/data residency needs for veterans' medical data).
5. **Judge calibration data.** Who provides the human gold labels for the rubric — a VSO advisor? Without domain-expert calibration, judge scores for "completeness of gap analysis" are unvalidated. Open product/ops question.
6. **Third-party cost-comparison figures** (RAG "94% cheaper," "1,250x at scale," European bank study) come from single blog-cited studies and could not be verified against primary sources — treat as directional only.
7. **DocETL-style incremental execution** is documented as a framework feature, but the exact invalidation semantics (what happens on schema evolution mid-corpus) aren't specified in public docs — the artifact-keying scheme in implication 2 is standard build-system practice applied to LLM pipelines, not a cited standard.

---

## Source list (primary)

- https://www.anthropic.com/engineering/building-effective-agents
- https://www.anthropic.com/engineering/multi-agent-research-system
- https://docs.cloud.google.com/architecture/choose-agentic-ai-architecture-components
- https://platform.claude.com/docs/en/about-claude/pricing.md (pricing verified)
- https://platform.claude.com/docs/en/build-with-claude/pdf-support.md (PDF limits verified)
- https://platform.claude.com/docs/en/build-with-claude/citations.md (citations behavior verified)
- https://platform.claude.com/docs/en/build-with-claude/prompt-caching.md
- https://platform.claude.com/docs/en/build-with-claude/structured-outputs.md
- https://www.anthropic.com/news/introducing-citations-api
- https://www.vldb.org/pvldb/vol18/p3035-shankar.pdf (DocETL, VLDB 2025)
- https://arxiv.org/pdf/2410.09342 (LLM×MapReduce)
- https://arxiv.org/pdf/2501.01880 (Long Context vs RAG evaluation)
- https://arxiv.org/html/2603.04445v2 (routing/cascading survey)
- https://hamel.dev/blog/posts/evals-faq/ ; https://newsletter.pragmaticengineer.com/p/evals
- https://mbrenndoerfer.com/writing/position-bias-in-llm-judges ; https://futureagi.com/blog/evaluating-llm-judge-bias-mitigation-2026/
- https://arxiv.org/pdf/2509.01455 (calibration + risk-controlled refusal)
- https://tianpan.co/blog/2025-10-19-llm-routing-production ; https://tianpan.co/blog/2026-04-09-long-context-vs-rag-production-decision-framework (directional production heuristics)
