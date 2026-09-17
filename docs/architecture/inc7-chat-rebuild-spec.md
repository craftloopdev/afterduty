# Increment 7 — Chat Rebuild: Implementation Spec

Status: SPEC (no code in this change). Date: 2026-06-11.
Sources: `docs/architecture/proposals/design-ship.md` §4 + §7 (Increment 7),
`docs/architecture/research/research-gcp-rag.md`, `docs/architecture/research/research-va-knowledge.md`,
`docs/qa/ui-review/areas/ui-chat.md`, and the live code as of branch `feat/next-web-foundation`
(Increments 0–6 landed or landing; see §9 dependencies).

Executable by three agents in parallel:
- **backend-infra** — sections A, B, C, D (schema, embeddings, KB ingest, retrieval)
- **backend-agent** — sections E, F-server (ChatAgent v2, gating, SSE endpoint)
- **web** — sections F-client, G (SSE consumption, chat UX)

Section H is the test plan per piece; section I is sequencing and file-conflict boundaries.

---

## 0. Goals and non-goals

Goals (from design-ship §4 + §7):
1. Ground chat in three fused sources: the veteran's own document chunks, the structured
   analysis artifacts, and a VA knowledge base (38 CFR Parts 3+4) — via **hybrid pgvector +
   tsvector retrieval fused with RRF** on the existing Cloud SQL instance ($0 marginal infra).
2. **Citation-first answers**: every factual claim cites either a CFR section with its
   point-in-time "current as of" date, or the veteran's own document by filename/date.
3. **< 2.5 s to first token**: SSE streaming Spring → Next BFF → browser, on top of the
   Increment-6 cached system prefix (turn 2+ reads the case file at 0.1×).
4. **Billing/gating correctness** (design §4.3a): chat spend already lands in `AiCallLog`
   (done in a prior mission); this increment closes the remaining hole — **owners must have an
   active subscription to chat** (today `AccessScope.CHAT` lets free owners through).
5. Fix the ship-blocking web findings from `ui-chat.md`: pre-send Pro gating, streaming render,
   retry without retyping, markdown, aria-live transcript, citation chips.

Non-goals (explicitly deferred):
- **M21-1 ingestion** — scrape-only source (KnowVA, no API/bulk). Deferred to a later increment;
  the `chunks.kb_source` enum and the ingest job structure leave room for it (see §C.4).
- BVA/CAVC similar-case search (research-va §6) — separate feature.
- Vertex Ranking API reranker — optional later bolt-on ($1/1k), interface leaves a seam.
- Postgres RLS hardening of per-claim isolation — `claim_id` WHERE-clause now, RLS later.
- Multi-thread chat / clear-history UI (review "fast follows" not in the G list).

---

## A. Schema — `chunks` + structured VASRD records (backend-infra)

### A.1 The `chunks` table

One table for both corpora, discriminated by `scope`. Evidence chunks are per-claim; KB chunks
are global.

**New entity** `spring-backend/src/main/java/com/afterduty/model/Chunk.java`
(table `chunks`), JPA-managed columns only — **the `embedding` and `tsv` columns are NOT mapped
in the entity** (see A.3 for why):

| Column | Type (JPA / SQL) | Notes |
|---|---|---|
| `id` | `Long`, IDENTITY | |
| `scope` | `String`, not null | `evidence` \| `kb` |
| `claim_id` | `Long`, nullable | required when scope=evidence; **null for kb** |
| `evidence_id` | `Long`, nullable | FK → `evidence_items(id)` `ON DELETE CASCADE` (mirror the `Atom.evidence` `@ForeignKey(foreignKeyDefinition=...)` pattern so doc deletion reaps chunks) |
| `kb_source` | `String`, nullable | `vasrd` \| `presumptives` (later: `m21-1`, `dbq`) |
| `cfr_section` | `String`, nullable | e.g. `4.71a`, `3.309` — KB chunks only |
| `doc_type` | `String`, nullable | evidence: `aiClassification` echo; kb: `regulation` |
| `doc_date` | `String`, nullable | evidence: best-effort from `aiExtractedData` (`document_date` key) else null — same loose-string convention as `Atom.timestamp` |
| `source` | `String`, not null | evidence: filename; kb: `ecfr` |
| `as_of_date` | `LocalDate`, nullable | KB chunks: the eCFR point-in-time date. THE citation-freshness field (research-va Implication 2) |
| `section_path` | `String`, nullable | contextual header (doc title / CFR section heading) — also prepended to `content` before embedding (research-gcp §1.7 "contextual retrieval") |
| `content` | `String`, `columnDefinition="text"`, not null | chunk text incl. contextual header |
| `token_count` | `Integer` | approx (chars/4), for budget accounting |
| `embedding_status` | `String`, not null, default `pending` | `pending` \| `embedded` \| `skipped` (skipped = pgvector unavailable or rag disabled) — drives the backfill job |
| `created_at` | `Instant`, not null | |

Btree indexes via `@Table(indexes=...)`: `(scope, claim_id)`, `(scope, kb_source)`,
`(evidence_id)`, `(scope, cfr_section)`.

**New repository** `repository/ChunkRepository.java` — derived deletes/counts only
(`deleteByEvidenceId`, `deleteByKbSourceAndCfrSection`, `countByEvidenceId`,
`findTopNByScopeAndEmbeddingStatus...` for backfill). All retrieval SQL lives in
`HybridRetrievalService` (§D), not here.

### A.2 Structured VASRD records + KB ingest watermarks

**New entity** `model/VasrdRecord.java` (table `vasrd_records`) — the deterministic side of
Part 4 (research-va Implication 3: rating tables must be structured records, not just chunks):

| Column | Type | Notes |
|---|---|---|
| `id` | Long IDENTITY | |
| `dc_code` | String, not null, indexed | 4-digit diagnostic code, e.g. `5260` |
| `title` | String | condition name from the schedule |
| `body_system` | String | from the subpart/section (e.g. `musculoskeletal`) |
| `cfr_section` | String, not null | e.g. `4.71a` |
| `rating_pct` | Integer, nullable | one row per (dc_code, rating tier); null for note-only rows |
| `criteria_text` | text | the criteria for that tier |
| `as_of_date` | LocalDate, not null | eCFR issue date of the ingest |
| `display_order` | Integer | preserves schedule order within a DC |

**New entity** `model/KbSection.java` (table `kb_sections`) — nightly-diff watermark per
eCFR section:

| Column | Type | Notes |
|---|---|---|
| `id` | Long | |
| `part` | Integer | 3 or 4 |
| `section_identifier` | String, unique with part | e.g. `4.71a` |
| `last_issue_date` | LocalDate | latest eCFR `/versions` issue_date ingested |
| `content_hash` | String | SHA-256 of the section XML — belt-and-braces vs. the versions feed |
| `last_ingested_at` | Instant | |

Repositories: `VasrdRecordRepository` (`findByDcCodeOrderByDisplayOrder`,
`deleteByCfrSection`), `KbSectionRepository` (`findByPartAndSectionIdentifier`).

### A.3 pgvector bootstrap — guarded, degrades gracefully

**The problem**: `ddl-auto: update` creates tables from JPA entities, but Hibernate has no
`vector(768)` type, and a `columnDefinition="vector(768)"` would make **boot fail** on any
Postgres without the extension (and on any non-PG test datasource). Requirement: no hard
dependency, no boot failure — attempt the extension, fall back to tsvector-only.

**New component** `spring-backend/src/main/java/com/afterduty/config/PgVectorBootstrap.java`:

- `@Component`, runs on `@EventListener(ApplicationReadyEvent.class)` (strictly after ddl-auto
  has created `chunks`). Uses `JdbcTemplate`. Idempotent — every statement is
  `IF NOT EXISTS`-guarded.
- Step 1 — extension: `CREATE EXTENSION IF NOT EXISTS vector` in its own try/catch.
  - Success → `vectorAvailable = true`.
  - Failure (extension not installed / insufficient privilege / non-PG database) →
    `vectorAvailable = false`, **exactly one** `log.warn("pgvector unavailable — hybrid
    retrieval degrades to full-text only: {}", e.getMessage())`. No rethrow. No retry loop.
- Step 2 — vector column + HNSW (only when `vectorAvailable`):
  ```sql
  ALTER TABLE chunks ADD COLUMN IF NOT EXISTS embedding vector(768);
  CREATE INDEX IF NOT EXISTS idx_chunks_embedding_hnsw
      ON chunks USING hnsw (embedding vector_cosine_ops);
  ```
  (768 dims is well inside the 2,000-dim HNSW ceiling — research-gcp §1.3. Default HNSW
  m/ef_construction; pgvector 0.8.0 iterative index scans handle the `claim_id` filter,
  research-gcp §1.3.)
- Step 3 — tsvector column + GIN (**always**, vector or not):
  ```sql
  ALTER TABLE chunks ADD COLUMN IF NOT EXISTS tsv tsvector
      GENERATED ALWAYS AS (to_tsvector('english', coalesce(content,''))) STORED;
  CREATE INDEX IF NOT EXISTS idx_chunks_tsv ON chunks USING gin (tsv);
  ```
  Wrapped in its own try/catch with one WARN (so an H2/local datasource without tsvector also
  degrades instead of failing — retrieval then falls back to naive `ILIKE`, §D.3).
- Exposes `public boolean isVectorAvailable()` and `public boolean isFullTextAvailable()` —
  consumed by `VertexEmbeddingProviderImpl` (skip embedding when no vector column),
  `HybridRetrievalService` (query branch), and the backfill job.

Since the entity never maps `embedding`/`tsv`, Hibernate's `ddl-auto: update` neither creates
nor fights these columns; all reads/writes of them go through native SQL in §B/§D.

### A.4 Doc-only migration SQL

Convention confirmed in-repo (e.g. `V20260611__evidence_extract_key.sql`): **Flyway is not
configured**; `ddl-auto=update` is the actual schema mechanism and the SQL files document the
change for a future migration-tool adoption. Add:

- `spring-backend/src/main/resources/db/migration/V20260612__chunks_vasrd_kb.sql` — full
  `CREATE TABLE chunks / vasrd_records / kb_sections`, the `CREATE EXTENSION`, vector/tsv
  columns, HNSW + GIN + btree indexes, with the standard `-- NOTE: Flyway is not currently
  configured…` header and a paragraph stating that the vector/tsv columns are owned by
  `PgVectorBootstrap`, not JPA.

### A.5 New config block (application.yml, every value env-overridable)

```yaml
va-claim:
  rag:
    # Master switch for evidence/KB chunking + embedding + retrieval grounding.
    # OFF → no chunks written, chat tools answer from state/VASRD records only.
    enabled: ${RAG_ENABLED:true}
    embedding:
      model: ${RAG_EMBEDDING_MODEL:gemini-embedding-001}
      dimensions: ${RAG_EMBEDDING_DIM:768}          # MRL truncation; must match vector(768)
      location: ${RAG_EMBEDDING_LOCATION:us-central1}
    chunking:
      target-tokens: ${RAG_CHUNK_TOKENS:600}        # 400–800 sweet spot, ≤2,048 model limit
      overlap-tokens: ${RAG_CHUNK_OVERLAP:120}
    retrieval:
      top-k: ${RAG_TOP_K:12}
      candidate-k: ${RAG_CANDIDATE_K:40}            # per-arm LIMIT before RRF
      rrf-k: ${RAG_RRF_K:60}
    backfill:
      enabled: ${RAG_BACKFILL_ENABLED:true}
      batch-size: ${RAG_BACKFILL_BATCH:10}
      poll-ms: ${RAG_BACKFILL_POLL_MS:60000}
  kb:
    enabled: ${KB_ENABLED:true}
    refresh-cron: ${KB_REFRESH_CRON:0 30 9 * * *}   # daily 09:30 UTC, after eCFR's daily refresh
    ecfr-base-url: ${KB_ECFR_BASE_URL:https://www.ecfr.gov}
  chat:
    streaming: ${CHAT_STREAMING:true}               # rollback to non-streaming POST only
    require-pro: ${CHAT_REQUIRE_PRO:true}           # rollback lever for the owner-gating change
```

---

## B. Embedding pipeline (backend-infra)

### B.1 Provider — Gemini lane, ADC, REST

**New package** `com.vaclaimpath.service.rag`.

`service/rag/EmbeddingProvider.java` (interface — the test seam):
```java
public interface EmbeddingProvider {
    enum TaskType { RETRIEVAL_DOCUMENT, RETRIEVAL_QUERY }
    /** 768-dim, L2-normalized. Throws EmbeddingUnavailableException when the lane is down. */
    float[] embed(String text, TaskType task);
    List<float[]> embedBatch(List<String> texts, TaskType task);
    boolean isAvailable();   // false when rag disabled or pgvector unavailable
}
```

`service/rag/VertexEmbeddingProviderImpl.java` — follow the exact transport style of
`VertexGeminiAsyncProviderImpl` (plain `java.net.http.HttpClient` + `GoogleCredentials
.getApplicationDefault().createScoped("https://www.googleapis.com/auth/cloud-platform")`),
**no new SDK dependency, no Claude-quota dependency**:

- Endpoint: `POST https://{location}-aiplatform.googleapis.com/v1/projects/{project}/locations/{location}/publishers/google/models/{model}:predict`
  with `{"instances":[{"task_type":"RETRIEVAL_DOCUMENT","content":"..."}],
  "parameters":{"outputDimensionality":768}}`. Project id from existing
  `va-claim.vertex.project-id`; location/model/dim from `va-claim.rag.embedding.*`.
- **Re-normalize** the truncated vectors (MRL truncation to 768 leaves them non-unit-norm;
  cosine via `<=>` is robust but normalized vectors keep ANN behavior consistent — do it).
- Batch: up to 25 instances per request (predict API limit headroom), chunk the list.
- Bounded retry on 429/503: reuse the `MAX_429_ATTEMPTS=3` / backoff pattern from `ChatAgent`
  — never unbounded.
- Cost booking: write one `AiCallLog` row per request, `callType="embedding"`,
  `provider="vertex-gemini"`, `modelName=gemini-embedding-001`, `inputTokens≈chars/4`,
  `outputTokens=0`, via the existing `AiCostService.recordCall`. Add the price row to
  `AiCostService` PRICING: **$0.15/MTok input, $0 output** (research-gcp §1.5).
  Embedding spend is a rounding error but the ledger stays honest (design Increment 0 ethos).

### B.2 Chunker — deterministic, no LLM

`service/rag/ChunkingService.java` — pure, stateless:

- Input: an `EvidenceItem`. Text source: `rawContent` (decoded text path) — when absent/blank
  (pure-multimodal docs), fall back to `aiSummary`; when both absent, produce zero chunks
  (logged at DEBUG).
- Split: paragraph/heading-aware recursive splitter to `target-tokens` (≈600 tokens ≈ 2,400
  chars) with `overlap-tokens` overlap. Token estimate = chars/4. Hard cap per chunk: 1,800
  tokens (inside gemini-embedding-001's ~2,048-token input limit, research-gcp Open Q2).
- Contextual header prepended to every chunk's `content` (research-gcp §1.7):
  `"[{filename} — {aiClassification or 'document'}{, doc_date if known}] "` —
  what lets answers cite "your March 2019 C&P exam".
- Also used by KB ingest for narrative CFR sections (header = `"[38 CFR § {section} — {heading}] "`).

Doc-type-specific section splitting (decision-letter headings, DBQ questions, STR encounters —
research-gcp §1.7) is a **quality follow-up, not Inc-7 scope**: the splitter takes a
`List<String> preferredBreakPatterns` so the upgrade is additive.

### B.3 Evidence embedding on extraction completion

`service/rag/EvidenceEmbeddingService.java`:

- `public void embedEvidence(Long evidenceId)`:
  1. Load `EvidenceItem`; bail unless `processingStatus == "processed"`.
  2. `chunkRepository.deleteByEvidenceId(id)` (chunks are derived data; re-extraction =
     re-chunk; the supersede machinery is NOT used here).
  3. `ChunkingService` → insert `Chunk` rows (`scope=evidence`, `claim_id`, `evidence_id`,
     `source=filename`, `doc_type=aiClassification`, `embedding_status=pending`).
  4. If `embeddingProvider.isAvailable()`: `embedBatch(...)` then write vectors via one
     `JdbcTemplate.batchUpdate("UPDATE chunks SET embedding = CAST(? AS vector),
     embedding_status='embedded' WHERE id = ?")`; on provider failure leave rows `pending`
     (the backfill job retries). If unavailable: mark `skipped` (tsvector-only retrieval
     still works over `content`).
- **Hook point** (the single-pass parse): `extraction/ExtractionStateMachine.java`, in the
  single-pass success branch — immediately after `evidence.setProcessingStatus("processed")`
  / `extract_key` write / `evidenceItemRepository.save(evidence)` (currently ~lines 477–491).
  Register a `TransactionSynchronization.afterCommit` callback that calls
  `evidenceEmbeddingService.embedEvidence(id)` on a virtual thread — the Vertex HTTP call must
  NOT run inside the `@Transactional` parse method. Guarded by `va-claim.rag.enabled`.
  This is the **only** extraction-file touch in the whole increment (one ~6-line block) —
  see §I conflict boundaries.

### B.4 Backfill job for existing claims

`spring-backend/src/main/java/com/afterduty/job/ChunkBackfillJob.java` (package follows
`UsageResetJob`):

- `@Scheduled(fixedDelayString = "${va-claim.rag.backfill.poll-ms:60000}")`, guarded by
  `va-claim.rag.backfill.enabled` + `va-claim.rag.enabled`.
- Pass 1 (chunking): pick up to `batch-size` `EvidenceItem`s with
  `processingStatus='processed'` and zero chunks (`NOT EXISTS` native count query on
  `ChunkRepository`) → `embedEvidence(...)` each. This both backfills old claims and
  self-heals any missed afterCommit hook.
- Pass 2 (embedding retry): pick up to `batch-size` chunks with
  `embedding_status='pending'` older than 5 minutes → re-embed.
- Quiet when there is nothing to do (DEBUG, not INFO). Steady-state cost: one cheap COUNT
  query per minute.

### B.5 Cost notes (for the spec record)

- One heavy claim (30 docs / ~180K corpus tokens): ~$0.027 online ($0.15/MTok). Whole
  existing user base is a one-time backfill measured in cents.
- Query embedding: ~50–150 ms, ~$0.000008/turn (research-gcp §1.9/§2) — inside the §4.3
  latency budget.
- KB: Part 4 = 1.06 MB XML ≈ ~270K text tokens → ~$0.04 one-time; nightly diffs re-embed only
  changed sections (usually zero).

---

## C. KB ingest — VASRD + presumptives via eCFR (backend-infra)

**New package** `com.vaclaimpath.service.kb`.

### C.1 eCFR client

`service/kb/EcfrClient.java` — `java.net.http.HttpClient`, no auth, base URL from
`va-claim.kb.ecfr-base-url` (overridable so tests point at a fixture server):

- `TitleFreshness fetchTitleFreshness()` → `GET /api/versioner/v1/titles.json`, returns title
  38's `up_to_date_as_of` + `latest_amended_on`.
- `List<SectionVersion> fetchVersions(int part, LocalDate since)` →
  `GET /api/versioner/v1/versions/title-38.json?part={p}&issue_date[gte]={since}`.
- `String fetchPartXml(int part, LocalDate date)` →
  `GET /api/versioner/v1/full/{date}/title-38.xml?part={p}` (1.06 MB for Part 4 — stream to
  string, 30 s timeout).
- Polite: single-threaded use from the nightly job only; no parallel hammering (research-va
  Open Q2 — rate limits unpublished).

### C.2 VASRD ingest (38 CFR Part 4 → 202 sections)

`service/kb/VasrdIngestService.java`:

- `ingestSection(String sectionIdentifier, Document partXml, LocalDate asOfDate)` —
  idempotent per section, in one transaction:
  1. Locate the `DIV8` node for the section (Java DOM over the part XML).
  2. **Structured records**: walk the section's rating tables (`GPOTABLE` rows): emit
     `VasrdRecord{dc_code, title, body_system, cfr_section, rating_pct, criteria_text,
     as_of_date, display_order}` rows. Delete-then-insert per `cfr_section`. Table shapes in
     Part 4 are heterogeneous — **parse defensively**: a section whose table fails to parse
     logs one WARN with the section id and still gets RAG chunks (criteria remain reachable
     via retrieval; the deterministic lookup just lacks that DC until the parser learns the
     shape). Track the parse success count in the job summary log.
  3. **RAG chunks**: section text → `ChunkingService` → `Chunk` rows (`scope=kb`,
     `kb_source=vasrd`, `cfr_section`, `source=ecfr`, `as_of_date`, `section_path` = section
     heading) — delete-by-`(kb_source, cfr_section)` then insert; embed like §B.3.
  4. Upsert `KbSection{part=4, section_identifier, last_issue_date=asOfDate, content_hash}`.
- `VasrdDataService` (existing, classpath `vasrd_codes.json`, ~35 codes) becomes **DB-first**:
  `getByCode`/`search` consult `VasrdRecordRepository` first and fall back to the JSON list
  when the table is empty (cold boot, KB disabled, tests). Signature-compatible — its existing
  callers don't change.

### C.3 Presumptives (38 CFR §§ 3.307–3.320)

- The **hand-structured rules stay where they are**: `PresumptiveRulesService` (in-code PACT
  Act / Agent Orange / Gulf War rules with VASRD codes) remains the deterministic eligibility
  engine — Inc 7 does not rewrite it.
- New in Inc 7: ingest the **regulatory text** of the ~12 backbone sections (3.303, 3.304,
  3.306, 3.307, 3.309, 3.310, 3.311, 3.316, 3.317, 3.318, 3.320, plus 3.156/3.159) as KB
  chunks (`kb_source=presumptives`, `cfr_section`, `as_of_date`) via the same
  `ingestSection` path with `part=3` and a hardcoded allowlist
  (`PresumptiveIngest.SECTIONS`) — so chat can quote and cite the actual rule text with a
  freshness date.

### C.4 M21-1 — DEFERRED (decision)

M21-1 is HTML-scrape-only (KnowVA, no API/bulk — research-va §5). It is the highest-leverage
"how raters decide" source but the ingestion is a scraper project with its own
change-feed (Changes-By-Date) and robots/session unknowns (research-va Open Q4). **Not in
Increment 7.** The schema is ready for it (`kb_source='m21-1'`, `cfr_section` generalizes to a
section-id string); the nightly job structure (§C.5) takes a second source without redesign.
Chat must therefore **not claim M21-1 grounding** in its system prompt.

### C.5 Nightly refresh job

`spring-backend/src/main/java/com/afterduty/job/KbRefreshJob.java`:

- `@Scheduled(cron = "${va-claim.kb.refresh-cron:0 30 9 * * *}", zone = "UTC")` (convention:
  `UsageResetJob`), guarded by `va-claim.kb.enabled`.
- Algorithm:
  1. **Cold start**: if `kb_sections` is empty → full bootstrap ingest (Part 4: all 202 DIV8
     sections; Part 3: the §C.3 allowlist) at the title's `up_to_date_as_of` date. Also runs
     on first deploy via an `ApplicationReadyEvent` listener kicking the same method on a
     background virtual thread (so a fresh environment doesn't wait a day) — skipped when
     non-empty.
  2. **Diff**: `fetchTitleFreshness()`; if title 38 `up_to_date_as_of` ≤ stored watermark
     (max `kb_sections.last_issue_date`), log DEBUG and exit. Otherwise
     `fetchVersions(part, watermark)` for parts 3+4 → re-ingest **only the changed sections**
     (the Feb-2026 §4.10 publish-then-rescind is the proof case — research-va §1).
  3. One INFO summary: sections checked / re-ingested / table-parse failures.
- Failure isolation: per-section try/catch; one bad section never aborts the run; failed
  sections retry next night (watermark only advances per successfully ingested section).

---

## D. Hybrid retrieval — one SQL, RRF-fused (backend-infra)

`service/rag/HybridRetrievalService.java` + `service/rag/RetrievedChunk.java` (record:
`id, scope, claimId, evidenceId, kbSource, cfrSection, source, docType, docDate, asOfDate,
sectionPath, content, score`).

Public API (the contract backend-agent codes against — freeze this first, §I):

```java
List<RetrievedChunk> searchEvidence(Long claimId, String query, int topK);
List<RetrievedChunk> searchKb(String query, String kbSourceOrNull, int topK);
```

### D.1 The hybrid query (vector available)

One `JdbcTemplate` statement per call — pgvector cosine ANN + `websearch_to_tsquery`
full-text, fused with RRF (k from `va-claim.rag.retrieval.rrf-k`, default 60; design §4.1,
research-gcp §1.8):

```sql
WITH vec AS (
    SELECT id, ROW_NUMBER() OVER (ORDER BY embedding <=> CAST(? AS vector)) AS r
    FROM chunks
    WHERE scope = ? AND (claim_id = ? OR ? IS NULL)        -- see D.2 isolation
      AND embedding IS NOT NULL
    ORDER BY embedding <=> CAST(? AS vector)
    LIMIT ?                                                 -- candidate-k (40)
),
txt AS (
    SELECT id, ROW_NUMBER() OVER (ORDER BY ts_rank_cd(tsv, q) DESC) AS r
    FROM chunks, websearch_to_tsquery('english', ?) q
    WHERE scope = ? AND (claim_id = ? OR ? IS NULL)
      AND tsv @@ q
    LIMIT ?
)
SELECT c.*, (COALESCE(1.0/(? + vec.r), 0) + COALESCE(1.0/(? + txt.r), 0)) AS rrf_score
FROM chunks c
JOIN (SELECT COALESCE(vec.id, txt.id) AS id, vec.r AS vr, txt.r AS tr
      FROM vec FULL OUTER JOIN txt ON vec.id = txt.id) f ON f.id = c.id
ORDER BY rrf_score DESC
LIMIT ?                                                     -- top-k (12)
```

The query embedding (`TaskType.RETRIEVAL_QUERY`) is computed once per call. Vector literal
passed as the pgvector text format `"[0.1,0.2,...]"` string + `CAST(? AS vector)`.

Hybrid is mandatory, not optional: claims chat is dense with exact tokens — "DC 5260",
"VA Form 21-4138", dates — that embeddings rank poorly (design §4.1).

### D.2 Per-claim isolation (hard requirement)

- `searchEvidence`: `scope='evidence' AND claim_id = :claimId` in **both** arms. `claimId` is
  never nullable on this path — assert non-null at method entry.
- `searchKb`: `scope='kb' AND claim_id IS NULL` (+ optional `kb_source = ?`). KB rows never
  carry a claim_id, so a coding error cannot leak evidence into KB results.
- Evidence and KB are **never fused in one SQL call** — the agent's tools query them
  separately (§E), which keeps the isolation predicate trivially auditable. (RLS hardening
  stays on the security backlog.)

### D.3 Degraded modes

- `!pgVectorBootstrap.isVectorAvailable()` **or** the query-embedding call throws →
  tsvector-only: run the `txt` arm alone ranked by `ts_rank_cd`. The branch decision logs at
  DEBUG (the single WARN already happened at bootstrap; per-query embedding failures WARN
  once per `va-claim.llm.cache-tripwire.window-ms`-style rate limit — reuse a simple
  `AtomicLong lastWarn` guard, not the tripwire class).
- `!isFullTextAvailable()` (non-PG datasource, i.e. tests/dev-H2) → naive
  `content ILIKE %term%` scan, capped — exists only so local boots don't NPE; never the
  production path.

---

## E. ChatAgent v2 — grounded tools, citations, paid gating (backend-agent)

All changes in `service/ChatAgent.java` + `service/ChatService.java` +
`service/ClaimAccessService.java`. **Preserved invariants** (do not regress):
Vertex transport + `va-claim.llm.chat-transport` rollback flag; model from
`va-claim.llm.purposes.chat.model` (Sonnet 4.6); `AiCallLog` booking via `recordChatUsage`
(configured-model-name convention); bounded `MAX_429_ATTEMPTS=3` retry; the
**Increment-6 (Mission 6b) two-block system shape** — frozen-instructions block + claim-state
block under one `cache_control{type:ephemeral}` breakpoint, flag
`va-claim.llm.prompt-caching`, flag-off = flat string. Build v2 on whatever exact shape 6b
landed (it is merging in a parallel workflow — rebase on it, §I).

### E.1 Tools — three NEW grounding tools, existing mutation tools kept

**Decision**: the seven shipped mutation tools (`add_atom`, `update_atom`, `delete_atom`,
`update_condition`, `delete_condition`, `mark_gap_resolved`, `mark_gap_dismissed`) stay —
chat-driven state editing is live behavior `ChatService` depends on (atoms-delta →
`synthesisNeeded`). Inc 7 **adds** three read/grounding tools to `buildToolSchema()` +
`executeTool(...)`:

1. **`search_my_file`** — `{query: string, k?: integer}` →
   `hybridRetrievalService.searchEvidence(claimId, query, k≤topK)`. Tool result: numbered
   blocks, each `"[doc:{evidenceId}] {source}{ ({docDate})} — {sectionPath}\n{content}"`.
   Description: "Search the veteran's own uploaded documents (service records, exams,
   decision letters) for passages relevant to a question. Use before answering anything about
   what their file shows."
2. **`get_analysis`** — `{}` → fresh render of live conditions + gaps
   (`conditionRepository.findByClaimIdAndSupersededByIsNull`), reusing the existing
   `renderConditions(...)`. Rationale: the claim state in the **cached** system block is a
   snapshot from turn start — after mutation tools fire (or a concurrent pipeline run), this
   tool is the fresh read. Description says exactly that.
3. **`vasrd_lookup`** — `{dc_code?: string, query?: string}`:
   - `dc_code` → `VasrdRecordRepository.findByDcCodeOrderByDisplayOrder`: structured tiers
     rendered as `"DC {code} — {title} (38 CFR § {section}, current as of {as_of_date}):\n
     {pct}% — {criteria}…"`. Falls back to `VasrdDataService.getByCode` when the table has no
     rows (KB cold/disabled).
   - `query` (no dc_code) → `hybridRetrievalService.searchKb(query, null, k)` over **both**
     `vasrd` and `presumptives` chunks, each block stamped
     `"[38 CFR § {cfr_section}, as of {as_of_date}] {content}"`.
   - Description: "Exact VA rating-schedule lookup by diagnostic code, or search the
     regulations (rating criteria, presumptive-condition rules)."

Constructor gains `HybridRetrievalService` + `VasrdRecordRepository` (+ keep
`VasrdDataService` reachable). When `va-claim.rag.enabled=false`, `search_my_file` and the
query mode of `vasrd_lookup` return "Document search is not available right now." (tool stays
registered — schema stability keeps the cached prefix stable across the flag).

### E.2 Citation-first system prompt

Extend the frozen-instructions block (NOT the cached claim-state block — instructions are
stable, so this is a one-time cache invalidation at deploy):

- "GROUNDING & CITATIONS — Every factual statement about regulations must cite its section
  and freshness, formatted as a markdown link with the `cite:` scheme:
  `[38 CFR § 4.71a (as of 2026-06-09)](cite:cfr/4.71a)`. Every statement about the veteran's
  own records must cite the document:
  `[your {filename}{, {doc_date}}](cite:doc/{evidenceId})` using the `[doc:N]` ids returned
  by search_my_file. If retrieval returns nothing relevant, say you couldn't find it in their
  file — never invent a citation. The regulations data is an unofficial eCFR compilation —
  when asked about exact current law, recommend verifying with a VSO."
- "Use search_my_file before answering questions about what the veteran's records show; use
  vasrd_lookup before quoting rating percentages or criteria."

The `cite:` link convention is the **wire format for citation chips** (§G.5): it is plain
text, survives the non-streaming fallback and thread persistence unchanged, renders as a
normal markdown link in any other client, and the web link-renderer intercepts the scheme.
Model non-compliance degrades to plain text — no parser to break.

### E.3 Paid-tier gating, server-side (design §4.3a)

`service/ClaimAccessService.java`, `assertScope` `case CHAT`:

- **Owner path changes**: owner chat now requires `currentUser.hasActiveSubscription()`
  (same `User.hasActiveSubscription()` used everywhere). Deny →
  `ResponseStatusException(HttpStatus.PAYMENT_REQUIRED, "subscription_required")` — **402,
  not 403**: the BFF `serverFetch` maps 402 → `SubscriptionRequiredError` → paywall, and
  IntakeController already uses exactly this status+reason for the analyze gate
  (IntakeController.java:884-888 convention).
- Rollback lever: inject `@Value("${va-claim.chat.require-pro:true}") boolean chatRequiresPro`
  — `false` restores today's owner bypass with no deploy.
- **VSO viewer path unchanged** (existing share-scope semantics): non-owner still requires
  `canViewAnalysis` (403 `chat_requires_view_analysis`) + the **viewer's own** Pro
  (403 `chat_requires_viewer_pro`). Viewers spend their own `UsageGuard` quota (ChatService
  bills `viewer.getId()` — already correct).
- `GET /api/claim/messages` stays `VIEW_DOCS` — a lapsed subscriber can still read history;
  only composing is gated. The 8 existing one-line `IntakeController` call sites don't change.
- Note: usage-cap exemption for subscribers is already handled inside `UsageService`
  ("active Stripe subscribers are bypassed automatically" — application.yml) — no UsageGuard
  change.

### E.4 ChatService split for streaming

`service/ChatService.java` — refactor `sendMessage` into composable steps (public behavior of
`sendMessage` itself unchanged, it remains the non-streaming path):

```java
public TurnContext prepareTurn(User viewer, Long claimId, String content)   // @Transactional
    // resolveOrCreateThread + usageGuard.assertCapacity + persist veteran msg
    // + atomsBefore snapshot; returns {thread, userMsg, atomsBefore}
public IntakeMessage completeTurn(User viewer, Long claimId, TurnContext ctx, String replyText) // @Transactional
    // persist assistant msg + atomsAfter/synthesisNeeded check
```

`sendMessage` = `prepareTurn` → `chatAgent.handle(...)` (with the existing
fallback-reply-on-RuntimeException catch) → `completeTurn`. The streaming controller (§F)
calls the same three steps around `handleStreaming`. Failure semantics match today: the user
message persists even when the agent fails (already true — the catch saves a fallback reply).

---

## F. Streaming — SSE end to end

### F.1 Wire contract (freeze before parallel work)

`POST /api/claim/chat/stream`, body `{"message": "..."}`, honors `X-View-As`. Pre-stream
failures return plain JSON statuses (401/402/403/429-as-UsageLimit) exactly like `/claim/chat`
— gating errors must stay machine-mappable by the BFF. Success → `200`,
`Content-Type: text/event-stream`, events:

| event | data (JSON) | meaning |
|---|---|---|
| `ack` | `{"user_message_id": 123}` | user turn persisted; agent started |
| `status` | `{"phase":"thinking"}` \| `{"phase":"tool","tool":"search_my_file"}` | agent phase change — drives the UI status line |
| `delta` | `{"text":"…"}` | assistant text delta, append-in-order |
| `message` | full `MessageResponse` of the persisted assistant message | terminal-success; client replaces accumulated deltas with canonical content |
| `error` | `{"code":"rate_limited"\|"agent_error"}` | terminal-failure (after `ack`); client offers retry |

Heartbeat: SSE comment line (`: ping`) every 15 s while the agent works (defeats idle proxy
timeouts on Cloud Run/Next hops).

### F.2 Spring server (backend-agent)

**New file** `controller/ChatStreamController.java` — deliberately NOT added to the 976-line
`IntakeController` (active in the parallel Increment-6 workflow; §I conflict boundaries):

- `@PostMapping(value = "/api/claim/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)`
  returning `SseEmitter` (Spring MVC; `spring.threads.virtual.enabled=true` makes the blocking
  worker cheap — no WebFlux).
- Duplicates the two small private helpers from IntakeController (`getUser` via
  `SecurityConfig.USER_ATTRIBUTE`; the resolve-claim-and-access dance via
  `claimAccessService.resolveIfPresent`/`assertScope(CHAT)` + own-claim fallback) — ~25 lines;
  extracting a shared helper class would touch IntakeController, which is off-limits this
  increment.
- Flow: resolve access (throws before the emitter exists → normal JSON error path) →
  `chatService.prepareTurn` → create `SseEmitter(timeout = 180_000)` → submit worker to a
  virtual-thread executor → return emitter. Worker: send `ack`; call
  `chatAgent.handleStreaming(content, claimId, viewerId, userMsgId, listener)` where the
  listener forwards `delta`/`status` events; then `chatService.completeTurn(...)` and send
  `message`; on RuntimeException → `completeTurn` with the existing fallback text, send
  `error`, complete. **Client disconnect does not abort the turn**: emitter sends throw
  `IllegalStateException` after disconnect — swallow per-send and keep running so the reply
  still persists (the thread refetch on next load shows it; same guarantee as today's model).
- Flag `va-claim.chat.streaming=false` → endpoint returns `409 {"error":"streaming_disabled"}`
  and the web client falls back to the non-streaming POST (§F.4).

`service/ChatAgent.java` — `handleStreaming`:

```java
public String handleStreaming(String msg, Long claimId, Long userId, Long userMessageId,
                              ChatStreamListener listener)
public interface ChatStreamListener {           // new tiny file service/ChatStreamListener.java
    void onDelta(String text);
    void onStatus(String phase, String toolName);   // toolName nullable
    ChatStreamListener NOOP = …;
}
```

- `handle(...)` becomes `handleStreaming(..., ChatStreamListener.NOOP)` — one code path.
- Inside the existing tool-use loop, replace `client.messages().create(params)` with
  `client.messages().createStreaming(params)`; consume the SDK's raw stream events,
  feeding `content_block_delta` text deltas to `listener.onDelta`, `content_block_start`
  of a `tool_use` block to `listener.onStatus("tool", name)`, and accumulating the full
  `Message` with the SDK's `MessageAccumulator`. At stream end, `toRawJson(accumulated)` →
  the surrounding loop, `recordChatUsage`, and tool execution are **unchanged**. Emit
  `onStatus("thinking", null)` at each round-trip start.
- Retry cap nuance: the 3-attempt 429 retry applies **only when zero deltas have been emitted
  for the current round-trip** (a retried half-streamed answer would duplicate text). A
  mid-stream failure after first delta → propagate as agent error (client gets `error` +
  retry affordance; the canonical thread stays consistent because nothing was persisted).
- Caching note: turn 2+ of a session re-sends the identical instructions + claim-state prefix
  → cache read at 0.1×. Keep the existing tripwire-style assertion: log WARN when
  `promptCachingEnabled` and turn>1 books `cache_read_input_tokens == 0`.

### F.3 Next.js BFF passthrough (web)

**New file** `web/src/app/api/chat/stream/route.ts`:

- `POST` handler. Reads `cp_session` cookie exactly like `serverFetch` (but does NOT use
  `serverFetch` — it buffers; this route hand-rolls the fetch to keep the body streaming):
  build headers `{Authorization: Bearer …, Accept: "text/event-stream",
  Content-Type: "application/json"}` (allowlist outbound, never forward inbound headers —
  client.ts security convention), `fetch(`${API_BASE}/claim/chat/stream`, {method:"POST",
  body, cache:"no-store"})`.
- Upstream non-200 → map to the same JSON statuses as `web/src/app/api/chat/route.ts`'s
  `mapError` (401/402/403/409/502) so the pre-send gate errors stay uniform.
- Upstream 200 → `return new Response(upstream.body, {status: 200, headers:
  {"Content-Type": "text/event-stream", "Cache-Control": "no-cache, no-transform",
  Connection: "keep-alive", "X-Accel-Buffering": "no"}})` — raw `ReadableStream`
  passthrough; cookie→Bearer happens here, SSE bytes are untouched.
- **Next.js 16 caveat** (web/AGENTS.md): verify the route-handler streaming conventions
  against `node_modules/next/dist/docs/` before writing it (runtime/dynamic flags differ
  from training data).
- Cleanup per review Orphan #1: delete the dead `GET` from `app/api/chat/route.ts`; keep its
  `POST` as the **non-streaming fallback** (unchanged contract: send → full-thread refetch).

### F.4 Client transport (web)

**New hook** `web/src/components/chat/useChatStream.ts` + **pure parser**
`web/src/lib/sse.ts`:

- `lib/sse.ts`: `parseSseStream(reader: ReadableStreamDefaultReader, onEvent)` — incremental
  line-buffer parser for `event:`/`data:`/comment lines (multi-`data:` join, CRLF tolerant).
  Pure, vitest-covered.
- `useChatStream` state machine: `idle → sending → streaming → done|failed`. `send(text)`:
  1. POST `/api/chat/stream` with `fetch`; if `res.ok && content-type startsWith
     text/event-stream` → consume via `parseSseStream`: `ack` (mark delivered), `status`
     (set phase label), `delta` (append to the in-flight assistant message), `message`
     (replace in-flight content with canonical, state=done), `error` (state=failed).
  2. **Fallback**: 409 (`streaming_disabled`), 404 (old backend), network/parse failure
     **before any delta** → re-send through the legacy POST `/api/chat` full-thread-refetch
     path. Failure after deltas → failed state with retry (no silent re-send).
  3. 402 → surface `subscription_required` to the gate UI (§G.1); 401/403/etc → failed.
- Persistence model is unchanged either way: the server persists the full reply at the end;
  on remount the SSR `loadMessages()` shows the canonical thread.

---

## G. Web UX (web agent) — `ChatView` rebuild

Files: `web/src/components/chat/ChatView.tsx` + `ChatView.module.css` (rework),
new `web/src/components/chat/Markdown.tsx`, `web/src/components/chat/CitationChip.tsx`,
`useChatStream.ts` (§F.4), `web/src/app/(app)/ask/page.tsx`,
`web/src/components/conditions/ConditionDetail.tsx` (one-line nav fix),
`web/src/lib/models/vm.ts` (+`createdAt?`/`pending?`/`failed?` on `MessageVM`),
`web/src/lib/adapters/message.ts`, fixtures + `web/src/app/dev/ask/page.tsx` variants.

### G.1 Pre-send Pro gate (review Missing-Affordance #2/#3, Orphan #4)

- `(app)/ask/page.tsx` (server component) already loads history; additionally pass
  `pro: boolean` (the `(app)/layout.tsx` already computes `isPro` — reuse that data source,
  do not add a new fetch) and `prefill?: string` from `searchParams.topic`.
- `ChatView({initial, pro, prefill})`: when `!pro` → composer replaced by a locked panel:
  lock icon, "Ask AI is a Pro feature.", short value line, `Button` → `/upgrade`. History
  (if any, e.g. lapsed sub) still renders read-only. Suggestion chips render with a small
  "Pro" badge and clicking routes to `/upgrade` — no request fired, no 402 trap.
- Keep the reactive 402 handler as defense-in-depth (server is the source of truth): 402
  mid-session (sub expired) swaps the composer into the same locked panel — with an
  `/upgrade` link, not the dead-end text banner.

### G.2 Streaming render + status

- In-flight assistant bubble appends `delta` text live; the "Thinking…" indicator is replaced
  by a phase line driven by `status`: `thinking` → "Thinking…", `tool:search_my_file` →
  "Searching your documents…", `tool:vasrd_lookup` → "Checking the rating schedule…",
  `tool:get_analysis` → "Reviewing your analysis…", mutation tools → "Updating your claim…".
- Auto-scroll respects `prefers-reduced-motion` (review a11y): `behavior:
  matchMedia("(prefers-reduced-motion: reduce)").matches ? "auto" : "smooth"`; only
  auto-follow when the user is already near the bottom.

### G.3 Retry without retyping (review Missing-Affordance #4, Orphan #3)

- Do NOT clear the input optimistically. Clear it on `ack` (server accepted). On failure
  before `ack`: input still holds the text; the optimistic user bubble gets
  `failed: true` styling + a "Retry" button (re-`send`, replacing the failed bubble) and a
  "Dismiss" that removes it. Stable optimistic keys: `tmp-${crypto.randomUUID()}` (fixes the
  collision nit).
- On failure after `ack` (user msg persisted, reply failed): keep the user bubble (it IS
  persisted), show the error banner with a Retry that asks "Try again" by re-sending a
  regenerate? **No** — keep scope: banner says the assistant reply failed; "Try again"
  re-sends the same text as a new turn (matches the server's persisted-fallback-reply model).

### G.4 Markdown rendering

- Add `react-markdown` (+ `remark-gfm`) to `web/package.json` — battle-tested, renders no raw
  HTML by default (XSS-safe for model output). `Markdown.tsx` wraps it with a minimal
  component map onto existing token-styled elements (p, ul/ol, strong, em, code, h3+ demoted
  to styled text so model headings can't break the page outline) and the custom link renderer
  (§G.5). Assistant bubbles render `<Markdown>{content}</Markdown>`; user bubbles stay
  plain-text pre-wrap.
- Streaming + markdown: re-render the accumulated string per delta batch (throttle renders
  with `requestAnimationFrame` batching in the hook). Unterminated markdown mid-stream renders
  literally — acceptable; the terminal `message` event re-renders canonical text.

### G.5 Citation chips

- `Markdown.tsx` link renderer intercepts `href`s with the `cite:` scheme (§E.2 contract):
  - `cite:cfr/{section}` → `CitationChip` (book icon, label = link text) →
    external `https://www.ecfr.gov/current/title-38/chapter-I/part-{3|4}/section-{section}`
    (new tab, `rel="noopener"`).
  - `cite:doc/{evidenceId}` → `CitationChip` (file icon) → Next `Link` to
    `/documents?focus={evidenceId}` (documents page already lists evidence; `focus` is a
    scroll/highlight param — if unsupported there, plain `/documents` is the v1 target).
  - Unknown `cite:` form → render the link text as plain text (never a dead `<a>`).
- Chip styling: token-only (existing `--ai-bg`/`--ai-fg` family), ≥24px inline tap target,
  `aria-label="Source: {text}"`.

### G.6 A11y + composer (review A11y block)

- Thread container: `role="log"` `aria-live="polite"` `aria-label="Conversation"`. To avoid
  per-token SR spam, the live region announces the in-flight message only once on completion:
  deltas render inside `aria-hidden` until the terminal event swaps in the canonical bubble
  (simple + correct; per-sentence announcement is a later refinement).
- Visually-hidden sender prefixes in every bubble: `<span className="visually-hidden">
  {role === "user" ? "You:" : "AI:"}</span>` (add the utility class to `base.css` if absent).
- Composer: `<textarea rows={1}>` with auto-grow (max ~6 rows), `aria-label="Ask a question
  about your claim"`, Enter=send / Shift+Enter=newline, `font-size: 1rem` (kills iOS
  zoom-on-focus), **not disabled while busy** (send button disables instead — keyboard focus
  is never dropped; review item), focus returns to it after each turn.
- Suggestion chips: min-height 44px (Button convention), focus-visible ring already global.
- Error banner keeps `role="alert"`; add dismiss.
- `ConditionDetail.tsx`: replace `window.location.assign("/ask")` with Next `Link` to
  `/ask?topic=${encodeURIComponent(condition.name)}`; `ChatView` prefills the composer with
  `prefill` (review Orphan #2 — the button finally carries its promised context).

---

## H. Test plan

### H.1 backend-infra (unit/state, all offline)

New test seams (follow `FakeGcsStorage` / `FakeLlmAsyncProvider` conventions):
- `src/test/java/com/afterduty/service/rag/FakeEmbeddingProvider.java` — deterministic
  vectors (seeded hash of the text → 768 floats, normalized), records calls, can be toggled
  unavailable/throwing.
- `src/test/java/com/afterduty/service/kb/FakeEcfrClient.java` — serves fixture XML/JSON
  from `src/test/resources/kb/` (check in: a 3-section Part-4 XML sample incl. one GPOTABLE
  rating table + one table-less section + one malformed table; a `titles.json`; a `versions`
  diff JSON).

Tests:
- `ChunkingServiceTest` — sizes/overlap/contextual header/2,048-token cap/empty-input.
- `VertexEmbeddingProviderImplTest` — request JSON shape (`outputDimensionality:768`),
  normalization, batch split, bounded 429 retry, AiCallLog row shape (mock HttpClient — same
  style as `VertexAnthropicProviderImplTest`).
- `PgVectorBootstrapTest` — mock JdbcTemplate: extension-fails ⇒ `vectorAvailable=false` +
  exactly one WARN + steps 2 skipped + step 3 still attempted; success ⇒ all DDL issued;
  idempotency (statements are IF-NOT-EXISTS literals — assert SQL strings).
- `EvidenceEmbeddingServiceTest` — delete-then-insert per evidence; `pending`→`embedded`
  transitions; provider-throw leaves `pending`; provider-unavailable marks `skipped`.
- `ChunkBackfillJobTest` — picks unchunked processed evidence; retries stale `pending`; quiet
  when done.
- `VasrdIngestServiceTest` — fixture XML ⇒ expected `VasrdRecord` rows (dc/pct/criteria/
  as_of_date), KB chunks with cfr_section+as_of_date, malformed-table section ⇒ WARN +
  chunks-only (no records, no throw).
- `KbRefreshJobTest` — cold-start full ingest; watermark short-circuit; changed-sections-only
  re-ingest (FakeEcfrClient scripted diff); per-section failure isolation.
- `HybridRetrievalServiceTest` — mock JdbcTemplate: asserts the claim_id filter is bound in
  BOTH arms for `searchEvidence`, `claim_id IS NULL` for `searchKb`, RRF/limit parameters,
  and branch selection (vector-available vs tsvector-only vs ILIKE).
- `VasrdDataServiceTest` (extend) — DB-first, JSON fallback when table empty.

### H.2 backend-agent (unit/state, all offline)

- `ClaimAccessServiceTest` (extend) — CHAT: owner without active sub ⇒ 402
  `subscription_required`; owner with sub ⇒ pass; `require-pro=false` ⇒ legacy bypass; viewer
  matrix unchanged (canViewAnalysis / viewer-pro 403s).
- `ChatAgentToolsTest` — fake `HybridRetrievalService` + in-memory repos: each new tool
  dispatches with the right scope/claimId and formats results (incl. `as_of_date` stamps);
  `search_my_file` never receives another claim's id; rag-disabled message; mutation tools
  regression-covered (existing behavior).
- `ChatAgentStreamingTest` — fake/stubbed streaming client (scripted SDK events):
  deltas forwarded in order; tool-start emits status; accumulated message feeds the loop
  identically to `handle`; usage booked once per round-trip; 429-after-first-delta does NOT
  retry, 429-before-delta does (≤3).
- `ChatServiceTest` (extend) — prepareTurn/completeTurn equivalence with sendMessage
  (user-msg persists on agent failure; synthesisNeeded on atom delta; thread race intact).
- `ChatStreamControllerTest` — MockMvc: pre-stream 402/403 are JSON; happy path emits
  ack→delta→message (mock agent driving the listener); disconnect mid-stream still calls
  completeTurn; flag-off ⇒ 409.

### H.3 web (vitest + playwright, offline)

- vitest (pure units, matching the existing `format.test.ts` style): `lib/sse.ts` parser
  (split-chunk events, multi-data, comments/heartbeats, CRLF); citation `cite:` href
  classification logic (extract to a pure helper `parseCiteHref`); message adapter
  (role mapping, createdAt); `useChatStream` reducer transitions if extracted as a pure
  reducer (recommended).
- playwright `web/tests/e2e/chat.spec.ts` (extend, mock the BFF routes): streaming happy path
  (mock SSE response body), fallback-to-POST on 409, pre-send Pro gate for free persona
  (no network call fired), retry-preserves-input, citation chip renders from `cite:` markdown,
  aria-live + label assertions.
- dev fixtures: add `messagesFixture` variants — markdown+citations message, failed-send
  state, free-tier locked state — wired into `app/dev/ask/page.tsx` (dev routes 404 in prod
  already).

### H.4 Cannot be tested offline → deployed smoke checklist

1. **pgvector on Cloud SQL**: `CREATE EXTENSION` succeeds with the app's DB role (Cloud SQL
   supports pgvector 0.8.0 on PG13+; verify instance PG version + flag). Boot log shows no
   WARN; `\d chunks` shows embedding/tsv + HNSW/GIN.
2. **Real hybrid SQL semantics**: the RRF query is Postgres-only (FULL OUTER JOIN over CTEs,
   `<=>`, `websearch_to_tsquery`) — unit tests only assert bindings. Smoke: upload a doc,
   ask "what does my file say about my knee", verify retrieval hits + a `DC 5260`-style
   exact-token query ranks via the text arm. (Optional local alternative: dockerized
   `pgvector/pgvector:pg16` — do not wire into CI this increment.)
3. **Vertex embeddings live**: add `VertexEmbeddingLiveSmokeTest` gated by an env var,
   mirroring `VertexAnthropicLiveSmokeTest` (dims=768, latency budget eyeball 50–150 ms).
4. **eCFR live ingest**: trigger `KbRefreshJob` once (temporary admin/debug hook or manual
   invocation); verify 202 sections, vasrd_records count, table-parse failure rate (expect
   some — record the number), spot-check `vasrd_lookup` for DC 5260 / DC 9411.
5. **SSE through the real stack**: Cloud Run (Spring) → Cloud Run (Next BFF) → browser —
   verify first token < 2.5 s on turn 1, deltas arrive incrementally (no buffering — watch
   the BFF response headers / `no-transform`), heartbeats keep a 60 s tool-heavy turn alive,
   `cache_read_input_tokens > 0` booked on turn 2 (AiCallLog).
6. **Gating end-to-end**: free owner ⇒ locked composer; direct `curl` of `/api/claim/chat`
   and `/chat/stream` as free owner ⇒ 402 JSON (server-side truth, not just UI).

---

## I. Sequencing, ownership, conflict boundaries

### I.1 Freeze-first contracts (write these interfaces before parallel work)

1. `HybridRetrievalService` public API + `RetrievedChunk` (§D) — backend-agent codes E
   against it with the fake.
2. SSE wire contract (§F.1) — web codes F-client/G against a mocked BFF route.
3. The `cite:` link scheme (§E.2/§G.5).

### I.2 Agent assignments and file ownership

**backend-infra** (A+B+C+D) owns — all new files unless noted:
- `model/Chunk.java`, `model/VasrdRecord.java`, `model/KbSection.java`;
  `repository/ChunkRepository.java`, `VasrdRecordRepository.java`, `KbSectionRepository.java`
- `config/PgVectorBootstrap.java`
- `service/rag/*` (EmbeddingProvider, VertexEmbeddingProviderImpl, ChunkingService,
  EvidenceEmbeddingService, HybridRetrievalService, RetrievedChunk)
- `service/kb/*` (EcfrClient, VasrdIngestService, PresumptiveIngest)
- `job/ChunkBackfillJob.java`, `job/KbRefreshJob.java`
- `db/migration/V20260612__chunks_vasrd_kb.sql`
- EDITS: `extraction/ExtractionStateMachine.java` (ONLY the §B.3 afterCommit hook block),
  `service/VasrdDataService.java` (DB-first), `AiCostService` (one pricing row),
  `application.yml` (the §A.5 block).

**backend-agent** (E + F-server) owns:
- EDITS: `service/ChatAgent.java` (tools, citations prompt, handleStreaming),
  `service/ChatService.java` (prepareTurn/completeTurn split),
  `service/ClaimAccessService.java` (CHAT owner gating + flag).
- NEW: `service/ChatStreamListener.java`, `controller/ChatStreamController.java`.
- MUST NOT touch: `IntakeController.java` (Increment-6 workflow active there; the existing
  `/claim/chat` + `/claim/messages` endpoints are correct as-is), anything under
  `extraction/`, `synthesis/`, `llm/` (except reading).

**web** (F-client + G) owns:
- NEW: `app/api/chat/stream/route.ts`, `components/chat/useChatStream.ts`,
  `components/chat/Markdown.tsx`, `components/chat/CitationChip.tsx`, `lib/sse.ts`.
- EDITS: `components/chat/ChatView.tsx` + `.module.css`, `app/api/chat/route.ts` (delete dead
  GET), `app/(app)/ask/page.tsx`, `components/conditions/ConditionDetail.tsx` (nav fix),
  `lib/models/vm.ts`, `lib/adapters/message.ts`, fixtures, `app/dev/ask/page.tsx`,
  `tests/e2e/chat.spec.ts`, `package.json` (react-markdown).
- Read `node_modules/next/dist/docs/` for Next 16 route-handler streaming before F-client.

No file is owned by two agents. The only shared-file risk is `application.yml`
(backend-infra adds §A.5; backend-agent adds nothing — `va-claim.chat.*` keys are included
in backend-infra's block) — single writer.

### I.3 Order of operations

1. backend-infra: A (schema+bootstrap) → B → D → C. D is the unblock for backend-agent's E
   integration tests against the real bean; C can land last (chat works with empty KB —
   `vasrd_lookup` falls back to the JSON list).
2. backend-agent: E.3 gating (independent, can land first — it is the billing fix) →
   E.1/E.2 tools against the frozen D interface → E.4 + F.2 streaming.
3. web: G.1 gate + G.3 retry + G.6 a11y (independent of streaming, immediate review-fix
   value) → F-client + G.2 against the mocked SSE route → G.4/G.5 markdown+citations.
4. Deployed smoke (§H.4) before flipping any default: `va-claim.chat.require-pro` ships
   `true` (it IS the fix), `rag/kb/streaming` ship `true` with their individual env rollbacks.

### I.4 Dependencies on Increment 6's landed shape

- **Chat cached system block (Mission 6b)**: ChatAgent v2 must keep the landed two-block
  system-array form (`[instructions][claim-state + cache_control]`, flag
  `va-claim.llm.prompt-caching`, flag-off = flat string). The E.2 citation rules go in the
  instructions block; the claim-state block's render is untouched. **Rebase E on the 6b
  commit before starting** — do not edit ChatAgent concurrently with the Increment-6
  workflow (coordinate: 6b merges first).
- Tool-schema additions sit in the request prefix → first deploy invalidates existing chat
  caches once (expected, harmless).
- Increment 5's `superseded_by IS NULL` reader discipline carries into `get_analysis` and the
  system block (already done) — new tools must use the same `...AndSupersededByIsNull` repo
  methods.
- Increment 4/5's single-pass + `extract_key` flow defines the §B.3 hook point; flag-off
  (`extraction.single-pass=false`) legacy path gets NO embedding hook this increment —
  backfill Pass 1 (§B.4) covers docs processed through it.

### I.5 Risks (carried into implementation)

1. **Part 4 table-parse coverage** — heterogeneous GPOTABLE shapes; mitigation: defensive
   parser + chunks-always + tracked failure count; lookup falls back to retrieval.
2. **SSE buffering anywhere in the chain** (Next BFF, Cloud Run) silently degrades streaming
   to one big flush; mitigation: smoke test §H.4-5, `no-transform` headers, heartbeats; the
   non-streaming fallback path stays shipped.
3. **Model citation compliance** — `cite:` links are prompt-enforced, not schema-enforced;
   degrade is plain text (acceptable); eval-harness criterion in Increment 8.
4. **Owner gating is a behavior change for existing free users** — currently they can chat.
   Product sign-off implied by design §4.3a; `require-pro` flag is the rollback.
5. **pgvector privilege on Cloud SQL** — `CREATE EXTENSION` may need `cloudsqlsuperuser`;
   if the app role can't, run it once manually (documented in the migration SQL header);
   bootstrap degrades to tsvector-only until then — by design.
