# Judge report — Operator / economics lens

Verdict: **design-ship wins.** It is the only design whose initial-cost arithmetic reproduces
line-for-line against verified prices ($1.96, every line checks), its migration is 8 independently
shippable + reversible increments anchored to source facts I verified (anthropic-java-vertex:2.18.0
and google-cloud-storage:2.45.0 already on classpath; purposeDefaultModels empty; superseded_by column
present; deleteByClaimIdAndStage uncalled; in-memory Vertex result map), and it adds exactly ONE agent
surface + ONE bounded micro-loop — minimal new operational surface for a 1-person team. Graft the
accuracy design's verifier-sees-atoms + first-class abstention routing + golden-set-before-flip
sequencing, and the cost design's deterministic pre-LLM dedupe + cache-write honesty.

## Verified facts
- Prices (research-vertex 83-86, 210-211): Opus4.8 5/25 (batch 2.5/12.5, read .50); Sonnet4.6 3/15
  (batch 1.5/7.5, read .30); FlashLite .25/1.50 (batch .125/.75); Flash .50/3; embed .15/.12.
- Citations incompatible w/ structured outputs (400) → two-pass mandatory (agentic 35,110). All 3 honor.
- Vertex: NO Files API/URL sources — docs inline base64 (vertex 139); 30MB req cap (vertex 53). Batch
  confirmed Sonnet4.6/Haiku; Opus4.8/Fable5 UNCONFIRMED (vertex 120-122). Min cache prefix Sonnet 2048,
  Opus 4096 (vertex 102-103). us-central1 no Claude; global endpoint fine (vertex 154).
- pgvector $0 vs VectorSearch ~$68/mo vs VertexSearch $150-800 (gcp-rag 159). HNSW 2000-dim → 768 MRL.
  62→84% hybrid precision is ONE unverified 3rd-party bench (gcp-rag 108).
- Source bugs ALL verified: AnalysisScheduler 192/207 model-string compare → perpetual re-run;
  SynthesisStateMachine 135-139 empty-identify→COMPLETE-as-zero; allSucceeded gates w/ no FAILED branch
  (allTerminal helper exists, unused) → wedge; ExtractionStateMachine 120 findByClaimId no-filter,
  177/327 userId(null) cap escape; LlmProviderRouter 32 purposeDefaultModels empty → 91 opus-4-7 / 92
  gemini-pro defaults; ChatAgent 52/402 opus-4-7, 172-174 Thread.sleep(30s) on req thread, 0 AiCallLog,
  191 per-claim system prompt no cache; AiCostService 28 opus-4-7 = $15/$75; vasrd_codes.json = 37
  entries (Part4=202); WhatIfScenarioGenerator 43 "use 2024 VA rate tables".

## Cost arithmetic audit (my core mandate)
- SHIP $1.96: every line reproduces exactly. HONEST. Incremental $0.78 (or ~$0.30 if identify made
  delta-aware) also honest. Envelope ~$6.90/mo steady vs $11.99 — defensible.
- COST $0.32 headline STACKS 3 best-cases (batch + Gemini-free-native-text + cache-warm); its OWN honest
  batched figure is $0.82, realtime $1.43. Treat $0.82 as defensible, $0.32 as marketing.
- ACCURACY $0.85-1.10: honest, deliberately higher (Opus verify-sees-atoms realtime + Opus gap-validate).
- SHARED FLAW (ship + cost): cache-WRITE not costed in rate/gap fan-out. First call writes 60k atom block
  @ 1.25-2x ($3.75-6/MTok) ≈ one-time ~$0.36 (Sonnet) per claim, omitted. AND cache_control survival
  inside Vertex Batch is unconfirmed — if batch rows don't share cache, the "0.1x fan-out" win evaporates
  for the batched stages. Material to ship's rate($0.387)+gap($0.900) and fatal to cost's $0.32.

## Material flaws
1. Accuracy "Claude cost 6x overstated": reachable only as ledger-$15 vs batch-real-$2.50; realtime is 3x.
   Loose. Ship's "3x real Sonnet price" is a different (also-true) comparison. None state it cleanly.
2. Cache-write omission in rate/gap fan-out (ship 312/314, cost 6.1) + unconfirmed batch+cache interaction.
3. Cost $0.32 / incremental $0.012 are best-case-stacked; honest figures $0.82 / $0.14(new-condition).
4. Ship stage-1 "GCS enables native PDF + 30MB Vertex path" overclaims — Vertex still requires base64
   inline (vertex 139); GCS removes DB bloat + lets you stream-to-inline, doesn't let Vertex read GCS.
5. 62→84% hybrid precision quoted as fact (cost 231, accuracy 4.1) but gcp-rag 108 marks it UNVERIFIED.
6. Opus4.8/Fable5 Vertex Batch unconfirmed — all 3 flag it; ship+cost route batched stages to Sonnet (safe),
   accuracy keeps Opus on realtime verify (safe). Good.
7. Opus 4.7+ tokenizer +35% (vertex 88) inflates every Opus-stage estimate; all 3 flag, none re-baseline.
8. Stable condition identity by (vasrd_code, body_system, theory) mis-merges bilateral/dual-joint —
   accuracy flags it (risk 5); ship's condition_fingerprint has same exposure, doesn't call it out.

## Grafts into final architecture
- Accuracy: verifier MUST see atoms+citations w/ binding veto (kills legacy circular verify); first-class
  abstention routed to escalation NOT silent-empty; golden-set built BEFORE flipping any model floor;
  two-pass citations; nightly eCFR diff w/ as-of date stamped per chunk (freshness=correctness, §4.10).
- Cost: deterministic pre-LLM dedupe (pgvector cosine + exact-token) BEFORE the LLM merge — shrinks merge
  input cheaply; cache-write accounting honesty; assert cache_read_input_tokens>0 as CI+prod alert.
- Ship: keep the LlmJob/SKIP-LOCKED/AiCallLog substrate; Increment-0 cost-truth + Increment-1 kill-loop
  FIRST (highest ROI, no new infra); deterministic VasrdDecisionEngine + VaMath (never LLM for math);
  raise $4 cap to ~$8 once batch accounting fixed; single agent never multi (15x).
