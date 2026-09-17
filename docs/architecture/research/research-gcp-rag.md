# Research: RAG / Grounding Options on GCP for Veteran Documents in GCS

Date: 2026-06-10. Researcher: product-review subagent (web research task).
Scope: grounding/retrieval options for VA Claim Path — veteran documents (PDFs, images, decision letters, service records) in GCS; metadata + extracted artifacts in Cloud SQL Postgres; Spring backend on Cloud Run; 1-person team. Evaluation axes: cost at small scale (thousands of users × tens of docs), chat latency, operational simplicity.

All load-bearing prices below were verified against official Google pricing pages on 2026-06-10 (fetched directly; cloud.google.com pricing pages were curled and parsed because they exceed normal fetch limits). Third-party numbers are labeled as such.

---

## 1. Findings

### 1.1 Vertex AI Search (now branded "Agent Search", formerly Enterprise Search / Gen App Builder)

Source: https://cloud.google.com/generative-ai-app-builder/pricing (official, fetched 2026-06-10)

- Fully managed search over data stores; ingests unstructured PDFs directly from GCS buckets (codelab: https://codelabs.developers.google.com/codelabs/how-to-query-vertex-ai-search-cloud-run-service). Handles parsing, chunking, embedding, hybrid (semantic + keyword) retrieval, and optional answer generation in one product.
- **General (pay-as-you-go) pricing model:**
  - Search Standard Edition: **$1.50 / 1,000 queries** ("semantic retrieval and KPI optimization").
  - Search Enterprise Edition: **$4.00 / 1,000 queries** (includes "Core Generative Answers (AI Mode)").
  - Advanced Generative Answers (AI Mode): **+$4.00 / 1,000 user input queries** (add-on to either edition).
  - Index storage: **$0.006849315 / GiB-hour ≈ $5.00 / GiB-month**, with a **free quota of 10 GiB per month** (shared across Agent Search).
  - Free trial: **10,000 queries per month** at no cost (excludes Advanced Generative Answers).
- **Configurable (subscription) pricing model:** minimum commitment **1,000 queries-per-minute + 50 GB storage**; Query Unit $0.008219178/hour (≈ $6/QPM-month → ≈ $6,000/month minimum). Add-ons: Semantic $0.75/1k queries + $1.50/GB-month embeddings storage; Core Generative Answers $2.00/1k; Advanced $4.00/1k. **Irrelevant at VA Claim Path's scale — the minimum commitment alone is ~$6,100/month.**
- Document processing at ingestion is billed via Document AI SKUs through Agent Search: OCR **first 1,000 pages/month free, then $1.50 / 1,000 pages**; **Layout Parser (includes initial chunking) $10.00 / 1,000 pages**.
- Related APIs on the same pricing page:
  - **Ranking API (semantic reranker): $1.00 / 1,000 queries**, a query = up to 100 documents to rerank. Usable standalone with *any* retriever, including pgvector.
  - **Grounded Generation on your own retrieved data: $2.50 / 1,000 requests** (also confirmed on the Vertex gen-AI pricing page as "Grounding with your data $2.50 per 1,000 prompts").
  - Check Grounding API: listed as "$0.00075 / 1,000 count" (as printed on the page; see Open Questions — this figure looks like it may be per-1k-characters or a page typo, it is ~1000x cheaper than everything else).
  - Agent Search for Healthcare: $20.00 / 1,000 queries (medically tuned; US-only data stores) — likely overkill/expensive here.
- Naming churn is real: the page now says "Agent Search"; docs sites mix "Vertex AI Search", "AI Applications", and "Agent Search". Pricing model was visibly restructured (new "AI Mode" SKUs). For a 1-person team this product-surface churn is an operational consideration in itself.
- Multi-tenancy: per-veteran isolation must be done with metadata filters on a shared data store (or per-user data stores, which hit data-store count limits). The filter is enforced by *your* query code — a missed filter is a cross-veteran PII leak. No row-level-security equivalent.
- Latency: no official latency SLO is published for search queries; community reports put simple queries in the hundreds of ms (unverified). Generative answer calls add LLM latency on top.

### 1.2 Vertex AI Vector Search (formerly Matching Engine) + Vector Search 2.0

Source: https://cloud.google.com/vertex-ai/pricing (official, fetched/parsed 2026-06-10)

- Classic (performance-optimized) tier:
  - Index building/updating: **$3.00 per GiB processed** (all regions). Data size = #vectors × dims × 4 bytes.
  - Streaming update inserts: **$0.45 / GiB ingested**; compaction rebuilds are billed again at batch rate ($3/GiB) — triggered at the latest when oldest uncompacted data is 5 days old.
  - Serving: per node-hour, always-on. us-central1: **e2-standard-2 $0.0938084/hr**, e2-standard-16 $0.7504672/hr, e2-highmem-16 $1.012368/hr.
  - Google's own example table: **2M vectors × 128 dims @ 100 QPS = 1× e2-standard-2 = ~$68/month**. That is the practical floor for an always-on index.
- Storage-optimized tier ("Vector Search 2.0", collections/data objects, auto-scaling): **Capacity Unit $2.30/CU-hour per replica** (bundled compute + up to 1 TiB SSD) ≈ **$1,679/month minimum**, plus Write Units $0.45/GiB. Built for billion-scale, not for us. (Overview: https://docs.cloud.google.com/vertex-ai/docs/vector-search/storage-optimized-vector-search, https://medium.com/google-cloud/introducing-vertex-ai-vector-search-2-0-from-zero-to-billion-scale-90ed666dac43)
- Supports native **hybrid search**: dense + sparse embeddings in one index, merged with Reciprocal Rank Fusion; sparse vectors via TF-IDF/BM25/SPLADE that you compute yourself (https://docs.cloud.google.com/vertex-ai/docs/vector-search/about-hybrid-search).
- It is a vector index only: you still store chunk text/metadata elsewhere (i.e., in Cloud SQL anyway), and you manage index lifecycle, endpoints, deployments. Most moving parts of all options.

### 1.3 pgvector on Cloud SQL for PostgreSQL

Sources: https://docs.cloud.google.com/sql/docs/postgres/extensions , https://docs.cloud.google.com/sql/docs/postgres/generate-manage-vector-embeddings , https://github.com/pgvector/pgvector (all fetched 2026-06-10)

- Cloud SQL for PostgreSQL supports **pgvector 0.8.0 on PostgreSQL 13+** (0.7.4 on PG12, 0.5.1 on PG11). Enable with `CREATE EXTENSION vector;`. Upstream latest is 0.8.2; 0.8.0 added **iterative index scans** (keeps scanning until enough results pass your `WHERE` filters — important when filtering by `user_id`).
- Index types: **HNSW** and IVFFlat. Dimension limits (pgvector docs): `vector` type up to 16,000 dims, but **HNSW/IVFFlat indexes support max 2,000 dims** for `vector`; `halfvec` (fp16) indexable up to 4,000 dims. **Consequence: gemini-embedding-001's default 3,072 dims cannot be HNSW-indexed as `vector` — use MRL truncation to 1,536 or 768 dims (officially supported, minimal quality loss) or `halfvec`.**
- Cloud SQL also ships `google_ml_integration` (v1.2+) with an in-database `embedding()` function calling Vertex AI embedding models — handy, though calling Vertex from the Spring app is equally fine and easier to version/control.
- Cloud SQL "vector assist" (PG12+) helps with index parameter tuning: https://docs.cloud.google.com/sql/docs/postgres/vector-assist-overview
- Marginal cost: **effectively $0** beyond embedding API calls and a few GB of Cloud SQL storage — it runs inside the instance you already pay for. No new service, IAM surface, or deployment. Per-veteran isolation is a `WHERE user_id = ?` clause living next to your existing authz, and can be hardened with Postgres RLS.
- ScaNN index is **AlloyDB-only**, not Cloud SQL (https://cloud.google.com/blog/products/databases/how-scann-for-alloydb-vector-search-compares-to-pgvector-hnsw). At this corpus size HNSW is more than sufficient; AlloyDB migration would only matter at much larger scale.

### 1.4 Vertex AI RAG Engine (managed middle option)

Sources: https://docs.cloud.google.com/gemini-enterprise-agent-platform/build/rag-engine/rag-engine-billing , https://docs.cloud.google.com/vertex-ai/generative-ai/docs/rag-engine/understanding-ragmanageddb (fetched 2026-06-10)

- Managed RAG pipeline (corpora, ingestion from GCS/Drive, chunking, embedding, retrieval, optional LLM parsing/reranking) exposed via `retrieveContexts` and as a Gemini grounding tool. Can also be backed by your own Vector Search or pgvector instance instead of RagManagedDb.
- Billing = pass-through of components: embedding model costs, optional LLM-parser model costs, reranking (LLM or the $1/1k Ranking API), plus **RagManagedDb = a Google-managed Spanner instance billed to your project at standard Spanner SKUs**. Tiers: Basic = fixed **100 processing units (0.1 node) with backup**; Scaled = 1+ node autoscaling to 10; Unprovisioned = deletes the DB and stops billing. Default parser and fixed-size chunking are free.
- Spanner pricing is edition/region-based, billed per node-hour pro-rated by processing units (https://cloud.google.com/spanner/pricing). Order of magnitude for the Basic tier (0.1 node, regional): roughly **$40–100/month** + storage/backup — exact figure depends on Spanner edition and region (not pinned down in this research; see Open Questions).
- Strategically it sits between "DIY pgvector" and "full Vertex AI Search": less code than pgvector, but a new product (GA mid-2025-era), Spanner billing indirection, and notably it *can use pgvector as its vector DB* — which signals pgvector is a first-class pattern at this scale even within Google's stack.

### 1.5 Embedding models + pricing (current as of 2026-06)

Source: https://cloud.google.com/vertex-ai/generative-ai/pricing (official, parsed 2026-06-10); model details: https://developers.googleblog.com/gemini-embedding-available-gemini-api/

- **gemini-embedding-001** (current flagship, GA): Vertex price **$0.00015 / 1,000 input tokens = $0.15 / 1M tokens online; $0.12 / 1M batch**; output free. On the Gemini API (non-Vertex) batch is $0.075/1M. 3,072-dim default with **Matryoshka (MRL) truncation to 1,536 / 768**; input limit 2,048 tokens per text (per model docs/third-party model cards — verify before relying on it for long chunks).
- **Legacy text models (text-embedding-005, text-multilingual-embedding-002)**: priced per character — **$0.000025 / 1k characters online ($0.00002 batch)** ≈ $0.025/1M chars (~$0.10/1M tokens at 4 chars/token), 768 dims. Google's blog says legacy embedding models "will be deprecated in the coming months" and recommends migrating to gemini-embedding-001; **no confirmed shutdown date for text-embedding-005 was found** (text-embedding-004 on the Gemini API was discontinued ~Nov 2025 per third-party trackers). New work should start on gemini-embedding-001.
- **Gemini Embedding 2 (unified multimodal, Preview)** appeared on the official pricing page: text $0.20/1M tokens, image $0.00012/image — a future option for embedding scanned pages directly; Preview, so not for production yet.
- Cost intuition: embeddings are a rounding error at this scale (see §2 worked example: ~$135 one-time for the whole corpus).

### 1.6 Document AI OCR vs Gemini multimodal for scanned docs

Sources: https://cloud.google.com/document-ai/pricing (official) ; https://ai.google.dev/gemini-api/docs/document-processing (official) ; Gemini prices from https://cloud.google.com/vertex-ai/generative-ai/pricing (official)

- **Document AI**: Enterprise Document OCR **$1.50 / 1,000 pages** (≤5M pages/mo; $0.60/1k above; add-ons $6/1k). **Layout Parser $10 / 1,000 pages** (includes initial RAG chunking; re-chunking $0.02/1k). Form Parser / Custom Extractor **$30 / 1,000 pages** (≤1M/mo). No charge for failed requests.
- **Gemini native PDF/vision**: each PDF page = **258 tokens** (image modality); pages scaled between 768×768 and 3072×3072; limits **50 MB / 1,000 pages per file**. **Gemini 3 models extract natively-embedded PDF text and do not charge tokens for it**; `media_resolution` (low/medium/high) tunes vision token cost per part.
- Per-page transcription cost math (258 input tokens + ~600 output tokens of transcribed text):
  - Gemini 2.5 Flash-Lite ($0.10/1M in, $0.40/1M out): ≈ **$0.00027/page** — ~5–6× cheaper than Document AI OCR.
  - Gemini 2.5 Flash ($0.30/1M in, $2.50/1M out): ≈ $0.0016/page — about parity with Document AI OCR.
  - Document AI OCR: $0.0015/page; Layout Parser: $0.010/page; Form Parser: $0.030/page.
- Quality trade-off: Document AI OCR is deterministic, returns geometry (bounding boxes), confidence scores, and never hallucinates; Gemini reads degraded scans, handwriting, stamps, and mixed layouts more "intelligently" but can hallucinate text and gives no character-level confidence. For *evidence documents in a veterans-benefits context*, silent hallucination is a real product risk; a common pattern is Gemini-first with spot OCR verification, or OCR-first with Gemini cleanup. Note VA Claim Path **already runs Gemini-based extraction** (`spring-backend/.../service/extraction/GeminiExtractionService.java`, `EventSegmentationAgent`, `EventExtractionAgent`) — so the Gemini path is the incumbent.
- Current Gemini model lineup on Vertex (for the generation side of RAG, official pricing page): 2.5 Flash $0.30/$2.50 per 1M in/out; 2.5 Flash-Lite $0.10/$0.40; 2.5 Pro $1.25/$10 (≤200k ctx); Gemini 3 Flash Preview $0.50/$3.00; 3.1 Flash-Lite $0.25/$1.50; 3.5 Flash $1.50/$9.00; 3.1 Pro Preview $2.00/$12.00. Context caching = 10% of input price on 2.5/3.x.

### 1.7 Chunking strategies for medical/military records

Sources: CLI-RAG (clinical hierarchical chunking) https://arxiv.org/pdf/2507.06715 ; https://community.databricks.com/t5/technical-blog/the-ultimate-guide-to-chunking-strategies-for-rag-applications/ba-p/113089 ; https://www.firecrawl.dev/blog/best-chunking-strategies-rag ; https://www.datacamp.com/blog/chunking-strategies ; Layout Parser docs (chunking included) https://cloud.google.com/document-ai/pricing

- Consensus: **chunking failure, not embedding quality, is the dominant RAG failure mode for clinical records.** Naive fixed-size splitting separates medication lists from their warnings, severs diagnosis from rationale, and mixes unrelated encounters.
- Clinical literature (CLI-RAG) recommends **hierarchical, section-aware chunking**: split first on clinical section headers (History of Present Illness, Assessment, Impression…), then recursively within sections; default fallback recursive splitter ~1,000 chars / 200 overlap.
- Applied to VA document types (all strongly structured):
  - **VA decision letters / rating decisions**: split on canonical headings — "DECISION", "EVIDENCE", "REASONS FOR DECISION" (per condition), effective dates. One chunk per condition-decision keeps a rating and its rationale together.
  - **DBQs / C&P exam reports**: form/question structure → key-value extraction (the app's existing Gemini "atom" extraction already does this); narrative sections → section chunks. Keep tables intact.
  - **STRs (service treatment records)**: noisy scans, encounter-oriented → page/encounter-level chunks with dates.
  - **DD-214 and personnel records**: single-page key-value — extract fields, don't chunk.
  - **Buddy/lay statements**: freeform → recursive ~400–800-token chunks with overlap.
- Always attach metadata to each chunk (veteran_id, document_id, doc_type, doc_date, condition tags, page span) and prepend a contextual header (doc title + section path) to the chunk text before embedding — the "contextual retrieval" pattern materially improves retrieval and lets the chat cite "your March 2019 C&P exam, p. 3".

### 1.8 Hybrid retrieval

Sources: https://jkatz05.com/post/postgres/hybrid-search-postgres-pgvector/ ; https://dev.to/lpossamai/building-hybrid-search-for-rag-combining-pgvector-and-full-text-search-with-reciprocal-rank-fusion-6nk ; https://rivestack.io/blog/hybrid-search-pgvector-postgres (third-party) ; https://docs.cloud.google.com/vertex-ai/docs/vector-search/about-hybrid-search ; https://docs.cloud.google.com/alloydb/docs/ai/run-hybrid-vector-similarity-search (official)

- Veteran-claim queries are full of exact tokens embeddings handle poorly: condition names, diagnostic codes (e.g., "DC 5260"), form numbers ("VA Form 21-4138"), dates, unit names. **Hybrid (vector + keyword) is strongly indicated.**
- In Postgres this is one SQL query: pgvector ANN subquery + `tsvector`/`websearch_to_tsquery` full-text subquery, merged with **Reciprocal Rank Fusion** (score = Σ 1/(k+rank), k≈60). No extra service. One third-party benchmark (rivestack, unverified) reports precision ~62% (vector-only) → ~84% (hybrid+RRF) with 15–30 ms total query latency on indexed tables up to a few million rows.
- Vertex AI Search does hybrid internally (that's part of what $1.50–4.00/1k buys). Vertex AI Vector Search supports dense+sparse hybrid with RRF but you must generate sparse vectors yourself.
- Optional quality step usable with any retriever: **Vertex Ranking API reranker at $1.00/1k requests** (rerank top ~50 hybrid hits before sending top ~10 to the LLM).

### 1.9 Latency for chat (summary judgment)

- **pgvector**: retrieval runs inside the Cloud SQL instance Cloud Run already connects to — single-digit-to-low-tens of ms for HNSW + RRF at this corpus size (per third-party benchmarks above; at ~10⁵–10⁶ chunks this is comfortably in-budget). Embedding the user query via Vertex adds one ~50–150 ms API call. Negligible vs LLM generation time.
- **Vertex AI Search**: an extra HTTPS hop; no official latency SLO published; anecdotally hundreds of ms (unverified). Fine for chat, but slower and less controllable than in-database retrieval.
- **Vector Search**: ms-level ANN per Google, but adds a network hop *plus* a second lookup to fetch chunk text from Postgres anyway.
- Freshness: a veteran uploads a doc and immediately asks about it. pgvector: row insert = instantly queryable. Vertex AI Search: ingestion/indexing delay is not officially bounded (typically minutes; unverified). Vector Search streaming: near-real-time at $0.45/GiB + compaction rebuild billing.

---

## 2. Pricing / Limits table

Worked-example assumptions: 5,000 users × 30 docs × 10 pages = **1.5M pages ≈ 150k docs**; ~600 text tokens/page; 1 chunk/page ≈ 1.5M chunks @ 768 dims; chat volume 100k retrieval queries/month.

| Item | Price (official) | At example scale |
|---|---|---|
| **Vertex AI Search** Standard queries | $1.50 / 1k queries | $150/mo @ 100k q |
| Vertex AI Search Enterprise queries | $4.00 / 1k queries | $400/mo @ 100k q |
| Advanced Generative Answers add-on | +$4.00 / 1k user queries | +$400/mo @ 100k q |
| Vertex AI Search index storage | ~$5.00 / GiB-mo (10 GiB free) | raw-PDF ingest @ ~1.5MB/doc ≈ 225 GB → **~$1,075/mo**; text-only ingest ≈ few GB → ~$0 |
| Vertex AI Search free tier | 10k queries/mo | covers early beta |
| Configurable pricing minimum | 1,000 QPM + 50 GB commit | ≈ $6,100/mo — not viable |
| Grounded Generation (own data) | $2.50 / 1k requests | $250/mo @ 100k |
| Ranking API (reranker) | $1.00 / 1k requests (≤100 docs/req) | $100/mo @ 100k |
| Healthcare Search | $20.00 / 1k queries | n/a (too costly) |
| **Vector Search** index build | $3.00 / GiB processed | 1.5M×768×4B ≈ 4.6 GiB → ~$14/build |
| Vector Search streaming inserts | $0.45 / GiB | pennies |
| Vector Search serving (us-central1) | e2-standard-2 $0.0938/node-hr | **~$68/mo minimum, always-on** |
| Vector Search 2.0 storage-optimized | $2.30 / CU-hr / replica | ≈ $1,679/mo min — not viable |
| **pgvector on Cloud SQL** | $0 marginal (existing instance); pgvector 0.8.0 on PG13+ | ~10 GB extra storage ≈ $1.70/mo (SSD $0.17/GB-mo) |
| pgvector index limits | HNSW/IVFFlat ≤ 2,000 dims (`vector`); `halfvec` ≤ 4,000 | gemini-embedding-001 must be truncated to 1,536/768 (MRL) |
| **RAG Engine** RagManagedDb Basic | Spanner 100 PU + backup (standard Spanner SKUs) | ~$40–100/mo order of magnitude (edition/region-dependent; not pinned) |
| **gemini-embedding-001** | $0.15 / 1M tokens online; $0.12 batch (Vertex); output free; 3,072d (MRL→1,536/768); ~2,048-token input | 900M tokens corpus → **$135 one-time** ($108 batch); query embedding ~$0.000008/turn |
| Legacy text-embedding-005 etc. | $0.025 / 1M chars online | deprecation announced, no confirmed date — don't build new on it |
| **Document AI** Enterprise OCR | $1.50 / 1k pages (≤5M/mo); $0.60/1k above | $2,250 one-time for 1.5M pages |
| Document AI Layout Parser (incl. chunking) | $10.00 / 1k pages | $15,000 one-time — avoid |
| Document AI Form Parser / Custom Extractor | $30 / 1k pages (≤1M/mo) | n/a |
| **Gemini page transcription** | 258 tokens/page input; 50 MB / 1,000 pages per file; Gemini 3: native PDF text free | 2.5 Flash-Lite ≈ $0.00027/page → **~$400 one-time**; 2.5 Flash ≈ $0.0016/page ≈ $2,400 |
| Gemini 2.5 Flash / Flash-Lite (generation) | $0.30/$2.50 and $0.10/$0.40 per 1M in/out | chat-turn LLM cost dominates retrieval cost in every architecture |
| Context caching (Gemini 2.5/3.x) | 10% of input price | useful for long per-veteran context |

Sources for every row: cloud.google.com/generative-ai-app-builder/pricing, cloud.google.com/vertex-ai/pricing, cloud.google.com/vertex-ai/generative-ai/pricing, cloud.google.com/document-ai/pricing, ai.google.dev/gemini-api/docs/document-processing, docs.cloud.google.com/sql/docs/postgres/extensions, github.com/pgvector/pgvector, cloud.google.com/spanner/pricing — all fetched 2026-06-10.

---

## 3. Implications for VA Claim Path

1. **pgvector on the existing Cloud SQL instance is the clear first choice.** At ~1.5M chunks worst-case (and realistically far fewer in year one), HNSW on Cloud SQL costs ~nothing, adds zero new infrastructure for a 1-person team, gives instant freshness after upload, and makes per-veteran isolation a `WHERE user_id = ?` (hardenable with RLS) instead of a metadata-filter discipline in an external SaaS index. Google's own RAG Engine even offers pgvector as a supported backend — evidence this is a sanctioned pattern at this scale.
2. **The retrieval-infra cost differences are noise; the real money is in parsing and generation.** Retrieval: pgvector ~$0 vs Vector Search ~$68/mo vs Vertex AI Search ~$150–800/mo + storage. Parsing 1.5M pages: Layout Parser $15k vs DocAI OCR $2.3k vs Gemini Flash-Lite ~$400. Generation: 100k chat turns at ~10k input tokens each ≈ $300/mo on 2.5 Flash. Optimize the parse path and the prompt size, not the vector store.
3. **Reuse the existing Gemini extraction pipeline as the chunker.** The backend already segments and extracts docs with Gemini (`GeminiExtractionService`, `EventSegmentationAgent`, `EventExtractionAgent`, `Atom` model) and stores artifacts in Postgres. Emitting section-aware chunks (doc_type-specific: decision-letter headings, DBQ questions, STR encounters) + metadata + a contextual header during that same pass costs almost nothing incremental and avoids paying Document AI Layout Parser $10/1k pages for worse, generic chunking. Gemini 3 models' free native-PDF-text extraction further cuts cost for born-digital PDFs.
4. **Go hybrid from day one.** Claims chat is dense with exact tokens (diagnostic codes, form numbers, dates, unit names). pgvector + `tsvector` + RRF is one SQL query; third-party benchmarks suggest a large precision jump over vector-only. If quality needs a boost later, bolt on the Vertex Ranking API at $1/1k requests — it works with any retriever and costs $0.001/turn.
5. **Embedding model: gemini-embedding-001 at 768 dims (MRL-truncated), online for incremental uploads, batch for backfills.** 768 dims fits pgvector HNSW limits (2,000 max), quarters storage vs 3,072, and the whole corpus costs ~$135 to embed. Do not build new on text-embedding-005 (deprecation announced). Mind the ~2,048-token input limit when sizing chunks (400–800 tokens is the sweet spot anyway).
6. **Vertex AI Search is the fallback, not the default.** It would buy managed parsing/chunking/hybrid/answers in one box, and the 10k free queries/month would cover a beta. But: raw-PDF ingestion makes index storage the dominant cost (~$5/GiB-mo on hundreds of GB of scans), multi-tenant isolation rests on never forgetting a filter, ingestion freshness is unbounded, pricing/branding churn ("Agent Search", AI-Mode SKUs) is ongoing, and the free tier hides a $4–8/1k marginal query cost once paid features (Enterprise + generative answers ≈ $0.008–0.012/turn) kick in. Revisit only if owning chunking/retrieval quality becomes a burden.
7. **Per-chat-turn economics (paid tier sanity check):** pgvector hybrid retrieval ≈ $0.00001 (query embedding) + LLM tokens; Vertex AI Search Enterprise + grounded generation ≈ $0.0065 + LLM tokens. At $11.99/month a heavy user doing 500 turns/month costs ~$0.005 retrieval (pgvector) vs ~$3.25 (VAIS full stack) before LLM costs — pgvector keeps gross margin insensitive to chat volume.
8. **Document AI keeps one niche**: deterministic OCR with bounding boxes + confidence for an "exact transcription view" or audit trail on legibility-critical evidence ($1.50/1k pages, first 1k/month free via Agent Search ingestion). Worth considering as a verification layer, not the primary parser.

## 4. Open Questions

1. **Check Grounding API price** is printed as "$0.00075 / 1,000 count" on the official page — implausibly cheap relative to everything else (likely per-1k-characters or a typo). Confirm in the console/SKU browser before relying on it for hallucination-checking economics.
2. **gemini-embedding-001 input limit (2,048 tokens)** was confirmed only via model cards/third-party docs in this pass; re-verify on the official model reference before finalizing chunk-size limits.
3. **text-embedding-005 shutdown date**: deprecation direction is official (Google blog) but no concrete discontinuation date found. Irrelevant if new work starts on gemini-embedding-001.
4. **Vertex AI Search index-storage metering for unstructured GCS docs**: whether billed GiB ≈ raw PDF bytes or extracted-text size materially changes the VAIS cost picture (≈$1,075/mo vs ≈$0). The pricing page implies raw-data-based metering ("raw data stored for indexing" in the configurable model) but the general-model metering basis for PDFs should be confirmed empirically with a test data store.
5. **Vertex AI Search ingestion/indexing latency** (upload → queryable) is not documented; if VAIS is ever piloted, measure it — chat-about-what-I-just-uploaded is a core UX path.
6. **RAG Engine Basic-tier real monthly cost** (Spanner 100 PU + backup + storage at current edition pricing in the app's region) — estimated $40–100/mo here; pin down via the Spanner pricing table or a billing experiment if RAG Engine becomes a candidate.
7. **Embedding/HNSW behavior on OCR noise**: degraded STR scans produce noisy text; evaluate whether retrieval quality needs an OCR-cleanup pass (Gemini rewrite) before embedding.
8. **Gemini Embedding 2 (multimodal, Preview)** could eventually embed scanned pages directly (skip transcription for retrieval); track its GA status and retrieval quality.
