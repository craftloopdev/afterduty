# Research: Claude on Vertex AI Model Garden (models, pricing, caching, batch)

Research date: 2026-06-10. All load-bearing numbers verified against official sources
(platform.claude.com / docs.cloud.google.com / cloud.google.com). Where a fact could not be
confirmed from an official page, it is flagged in Open Questions rather than guessed.

---

## Findings

### 1. Which Claude models are on Vertex AI right now (with exact Vertex model IDs)

Source: https://platform.claude.com/docs/en/build-with-claude/claude-on-vertex-ai (Anthropic's
official Vertex page, "API model IDs" table) and
https://docs.cloud.google.com/vertex-ai/generative-ai/docs/partner-models/claude

| Model | Vertex AI model ID | Status |
|---|---|---|
| Claude Fable 5 | `claude-fable-5` | GA on Vertex since **June 9, 2026** |
| Claude Opus 4.8 | `claude-opus-4-8` | Active |
| Claude Opus 4.7 | `claude-opus-4-7` | Active |
| Claude Opus 4.6 | `claude-opus-4-6` | Active |
| Claude Sonnet 4.6 | `claude-sonnet-4-6` | Active |
| Claude Sonnet 4.5 | `claude-sonnet-4-5@20250929` | Active |
| Claude Opus 4.5 | `claude-opus-4-5@20251101` | Active |
| Claude Haiku 4.5 | `claude-haiku-4-5@20251001` | Active |
| Claude Opus 4.1 | `claude-opus-4-1@20250805` | Deprecated |
| Claude Opus 4 / Sonnet 4 | `claude-opus-4@20250514` / `claude-sonnet-4@20250514` | Deprecated |
| Claude Haiku 3.5 | `claude-3-5-haiku@20241022` | Deprecated |
| Claude Sonnet 3.7 | `claude-3-7-sonnet@20250219` | Retired |

Notes:
- Newest models (Fable 5, Opus 4.8/4.7/4.6, Sonnet 4.6) use **bare IDs with no `@date` suffix**;
  older models keep date-suffixed IDs. Claude Mythos Preview exists as an invite-only research
  preview on Vertex (Project Glasswing) — not generally available.
- The Google Cloud model docs list pages for: Fable 5, Opus 4.8, Opus 4.7, Sonnet 4.6, Opus 4.6,
  Opus 4.5, Sonnet 4.5, Opus 4.1, Haiku 4.5, Opus 4, Sonnet 4 — matching the table above.
- Lifecycle dates on Vertex are set by Google and can differ from the first-party schedule
  (e.g., Haiku 3.5 is retired first-party but still served on Bedrock/Vertex).

### 2. API mechanics on Vertex

Source: https://platform.claude.com/docs/en/build-with-claude/claude-on-vertex-ai

- Endpoint shape: `https://{LOCATION}-aiplatform.googleapis.com/v1/projects/{PROJECT}/locations/{LOCATION}/publishers/anthropic/models/{MODEL_ID}:streamRawPredict` (or `:rawPredict`).
- `model` goes in the **URL**, not the body; body carries `"anthropic_version": "vertex-2023-10-16"`.
- Official Anthropic SDKs support Vertex: Python `AnthropicVertex` (`pip install "anthropic[vertex]"`),
  TypeScript `@anthropic-ai/vertex-sdk`, Java `anthropic-java-vertex` (`VertexBackend`), Go
  `vertex.WithGoogleAuth(...)`, C# `Anthropic.Vertex`, PHP `Anthropic\Vertex`, Ruby
  `Anthropic::VertexClient`. Auth is standard GCP ADC (`gcloud auth application-default login`,
  service accounts, WIF) — no Anthropic API key.
- The `ant` CLI does **not** support Vertex AI.
- Request payload limit: **30 MB** on Vertex.

### 3. Context windows / output limits

Sources: https://platform.claude.com/docs/en/build-with-claude/claude-on-vertex-ai,
https://platform.claude.com/docs/en/about-claude/pricing.md

- **1M-token context window on Vertex** for: Claude Fable 5, Opus 4.8, Opus 4.7, Opus 4.6, and
  Sonnet 4.6 — **at standard pricing, no long-context premium** (a 900K-token request bills at the
  same per-token rate as a 9K one). Other models (Sonnet 4.5, Haiku 4.5, Sonnet 4, etc.) are 200K.
- Max output (per Anthropic models docs): Opus-tier and Fable 5 = 128K; Sonnet 4.6 and
  Haiku 4.5 = 64K. Claude Code enables 1M context by appending `[1m]` to the model ID on Vertex.

### 4. Pricing — Vertex vs Anthropic direct API

Sources: https://platform.claude.com/docs/en/about-claude/pricing.md (full table, verified);
Anthropic's pricing page points to https://cloud.google.com/vertex-ai/generative-ai/pricing#claude-models
as the official Vertex price list and states the partner pricing rules below.

- **Global endpoints on Vertex use standard (Anthropic-list) per-MTok pricing.**
- **Regional and multi-region endpoints carry a 10% premium** over global. Applies to Claude
  Sonnet 4.5 / Haiku 4.5 / Opus 4.5 **and all newer models** (so Fable 5, Opus 4.8, Sonnet 4.6 are
  all in scope). Older models (Sonnet 4, Opus 4 and earlier) keep their legacy pricing structure.
- Anthropic first-party's `inference_geo: "us"` 1.1x multiplier does NOT apply on Vertex —
  Vertex has its own (equivalent) regional premium mechanism.

Per-MTok base prices (identical first-party and Vertex-global):

| Model | Input | Output | 5m cache write | 1h cache write | Cache read | Batch in | Batch out |
|---|---|---|---|---|---|---|---|
| Claude Fable 5 | $10 | $50 | $12.50 | $20 | $1.00 | $5 | $25 |
| Claude Opus 4.8 / 4.7 / 4.6 / 4.5 | $5 | $25 | $6.25 | $10 | $0.50 | $2.50 | $12.50 |
| Claude Sonnet 4.6 / 4.5 | $3 | $15 | $3.75 | $6 | $0.30 | $1.50 | $7.50 |
| Claude Haiku 4.5 | $1 | $5 | $1.25 | $2 | $0.10 | $0.50 | $2.50 |

- Caveat: Opus 4.7+ (incl. 4.8, Fable 5) use a new tokenizer that can consume **up to ~35% more
  tokens for the same text** — budget accordingly when comparing against Sonnet 4.6/Haiku 4.5.
- Fast mode (Opus 4.8 at $10/$50) is first-party only, not on Vertex.

### 5. Prompt caching on Vertex

Sources: https://docs.cloud.google.com/vertex-ai/generative-ai/docs/partner-models/claude/prompt-caching,
https://platform.claude.com/docs/en/about-claude/pricing.md,
https://code.claude.com/docs/en/google-vertex-ai

- **Supported on Vertex** with the same `cache_control: {type: "ephemeral"}` API. Default TTL
  **5 minutes**, extendable to **1 hour** via `"ttl": "1h"` (Google docs confirm both TTLs).
- Pricing multipliers (Anthropic-published, mirrored in the per-model table above): 5-min write =
  1.25x base input; 1-hour write = 2x; cache read = 0.1x base input.
- Minimum cacheable prefix (first-party numbers): 4096 tokens on Opus 4.8/4.7/4.6/4.5 and
  Haiku 4.5; 2048 on Fable 5 and Sonnet 4.6. Shorter prefixes silently don't cache.
- Claude Code on Vertex enables caching automatically (`DISABLE_PROMPT_CACHING=1` to disable,
  `ENABLE_PROMPT_CACHING_1H=1` for 1-hour TTL).

### 6. Batch prediction on Vertex

Sources: https://docs.cloud.google.com/vertex-ai/generative-ai/docs/partner-models/claude/batch,
https://platform.claude.com/docs/en/build-with-claude/claude-on-vertex-ai (feature lists)

- The Anthropic **Message Batches API endpoint is NOT available on Vertex** — instead Vertex
  offers its own **Batch Prediction** for Claude.
- Input: **BigQuery table or Cloud Storage JSONL**, rows in Anthropic Claude API schema. Output:
  BigQuery table or GCS JSONL (independently chosen).
- **50% discount on both input and output tokens** vs online/interactive pricing (matches the
  Anthropic batch table above).
- Completion: results available after all rows complete **or after 24 hours, whichever comes
  first**.
- Supported models per Google's docs (snapshot): Opus 4.7, Opus 4.6, Sonnet 4.6, Opus 4.5,
  Opus 4.1, Opus 4, Sonnet 4.5, Sonnet 4, Haiku 4.5, Haiku 3.5. **Fable 5 and Opus 4.8 were not
  yet in the list snapshot** — unconfirmed (see Open Questions).

### 7. Structured output, tool use, and feature gaps on Vertex

Source: https://platform.claude.com/docs/en/build-with-claude/claude-on-vertex-ai (Feature support
section); Google docs nav confirms dedicated pages for "Structured outputs", "Count tokens",
"Web search" under partner-models/claude.

Supported on Vertex:
- Messages API (full shape), **streaming**, **extended/adaptive thinking**, **token counting**
- **Tool use / function calling** — client-side tools including bash, text editor, computer use,
  memory tool; `tool_choice`; strict tool use
- **Structured outputs** (`output_config.format` JSON schema)
- **Web search tool** (server-executed search is the one server tool available)
- Citations, prompt caching (above)

NOT supported on Vertex (use first-party API if needed):
- Files API and URL sources for images/documents (must inline base64)
- Server-side tools: **code execution, web fetch, advisor**
- Agent infrastructure: Agent Skills API, MCP connector, programmatic tool calling
- API endpoints: **Message Batches** (Vertex batch replaces it), Models, Admin, Compliance,
  Usage & Cost
- **Claude Managed Agents**
- Server-side fallback (`fallbacks` parameter) — use client-side fallback

### 8. Regional availability (us-central1 / us-east5 / global)

Sources: https://platform.claude.com/docs/en/build-with-claude/claude-on-vertex-ai,
https://code.claude.com/docs/en/google-vertex-ai,
https://cloud.google.com/blog/products/ai-machine-learning/global-endpoint-for-claude-models-generally-available-on-vertex-ai,
https://cloud.google.com/blog/products/ai-machine-learning/multi-region-endpoints-for-claude-available-on-vertex-ai

- **us-central1 does NOT serve Claude models.** The primary US regional endpoint for Claude is
  **us-east5** (plus europe-west1/europe-west4 for some models).
- Three endpoint types:
  - **Global** (`region="global"`): recommended; dynamic routing, best availability, **no
    premium**; pay-as-you-go only. Newest models land here first.
  - **Multi-region** (`region="us"` or `"eu"`; hosts `aiplatform.us.rep.googleapis.com` /
    `aiplatform.eu.rep.googleapis.com`): data residency within a geography; +10%; PAYG only.
  - **Regional** (e.g. `us-east5`): single-region residency; +10%; **required for provisioned
    throughput**.
- Per-model regional availability varies and must be checked in Model Garden / the locations page
  (https://cloud.google.com/vertex-ai/generative-ai/docs/learn/locations#genai-partner-models).
  Claude Code's docs use `VERTEX_REGION_CLAUDE_HAIKU_4_5=us-east5` as the canonical example —
  Haiku 4.5 may not be on the global endpoint and typically pins to us-east5.

### 9. Rate limits / quotas / provisioned throughput

Sources: https://docs.cloud.google.com/gemini-enterprise-agent-platform/models/partner-models/claude/quotas,
https://cloud.google.com/vertex-ai/generative-ai/docs/quotas, plus the Anthropic Vertex page

- Claude quotas on Vertex are expressed as **QPM (queries/min) and TPM (tokens/min, input+output
  combined)**, set **per model, per region/endpoint-type, per project**. Maximums vary by account;
  view/raise in Cloud Console → Quotas & System Limits. (Exact default numbers per model were not
  retrievable from the truncated docs page — see Open Questions.)
- **Provisioned Throughput is available for Claude but only on regional endpoints** — not global,
  not multi-region. For "heightened rate limits," Google support is the path (per Claude Code docs).
- 429s on regional endpoints are common at default quota; Google's own guidance is to prefer
  `global` for availability.

### 10. Claude Agent SDK / agentic loops against Vertex

Sources: https://code.claude.com/docs/en/google-vertex-ai,
https://platform.claude.com/docs/en/agent-sdk/overview

- **Yes — agentic loops run against Vertex.** Two official paths:
  1. **Anthropic SDK + tool use** (`AnthropicVertex` client): full manual agentic loop or the beta
     tool runner; this is Anthropic's recommended pattern on Vertex since Managed Agents and most
     server-side tools are unavailable there.
  2. **Claude Agent SDK / Claude Code**: first-class Vertex support via
     `CLAUDE_CODE_USE_VERTEX=1`, `CLOUD_ML_REGION` (global | us | eu | us-east5 | ...),
     `ANTHROPIC_VERTEX_PROJECT_ID`; model pinning via `ANTHROPIC_MODEL` /
     `ANTHROPIC_DEFAULT_OPUS_MODEL` / `ANTHROPIC_DEFAULT_SONNET_MODEL` /
     `ANTHROPIC_DEFAULT_HAIKU_MODEL`. Prompt caching automatic. The `opus` alias resolves to
     Opus 4.6 unless pinned to `claude-opus-4-8`. MCP tool search disabled by default on Vertex
     (enable with `ENABLE_TOOL_SEARCH=true`; only Sonnet 4.5+/Opus 4.5+ accept the header).
- IAM: `roles/aiplatform.user` (key permission `aiplatform.endpoints.predict`) is sufficient for
  invocation + token counting.
- Data governance: handled by Vertex AI (request-response logging optional; zero-data-retention
  posture documented at https://cloud.google.com/vertex-ai/generative-ai/docs/data-governance).

### 11. Vertex Gemini current models + pricing (cheap-extraction tier comparison)

Source: https://cloud.google.com/gemini-enterprise-agent-platform/generative-ai/pricing (official
Vertex/Agent Platform pricing page, fetched 2026-06-10)

| Model | Input /MTok | Output /MTok | Cached input | Batch in/out |
|---|---|---|---|---|
| Gemini 3.1 Flash-Lite | $0.25 | $1.50 | $0.025 | $0.125 / $0.75 |
| Gemini 3 Flash Preview | $0.50 | $3.00 | $0.05 | $0.25 / $1.50 |
| Gemini 3.5 Flash | $1.50 (global) / $1.65 (non-global, eff. Jul 1 2026) | $9.00 / $9.90 | $0.15 / $0.165 | $0.75 / $4.50 |
| Gemini 3.1 Pro Preview | $2 (≤200K) / $4 (>200K) | $12 (≤200K) / $18 (>200K) | $0.20 / $0.40 | $1 / $6 |

- **Batch = 50% off** standard rates across Gemini models (unavailable for priority-tier, image
  models, Deep Research agent).
- **Implicit/context caching: cached input = 10% of base input** (e.g., $0.05 on Gemini 3 Flash).
  The page did not list the explicit context-cache *storage* fee (per token-hour) — open question.
- Gemini 3.1 Pro has a **>200K long-context surcharge** (2x input, 1.5x output); Claude's 1M
  models notably do NOT have one.
- Audio input is priced higher on Flash models ($1.00/MTok on 3 Flash).

---

## Pricing/Limits table (consolidated, per MTok unless noted)

| Item | Value | Source |
|---|---|---|
| Claude Fable 5 (`claude-fable-5`) | $10 in / $50 out; cache read $1; batch $5/$25; 1M ctx | platform.claude.com pricing |
| Claude Opus 4.8 (`claude-opus-4-8`) | $5 / $25; cache read $0.50; batch $2.50/$12.50; 1M ctx, 128K out | platform.claude.com pricing |
| Claude Sonnet 4.6 (`claude-sonnet-4-6`) | $3 / $15; cache read $0.30; batch $1.50/$7.50; 1M ctx, 64K out | platform.claude.com pricing |
| Claude Haiku 4.5 (`claude-haiku-4-5@20251001`) | $1 / $5; cache read $0.10; batch $0.50/$2.50; 200K ctx, 64K out | platform.claude.com pricing |
| Vertex regional/multi-region premium | +10% over global (Sonnet 4.5/Haiku 4.5/Opus 4.5 and newer) | claude-on-vertex-ai docs |
| Cache write multipliers | 1.25x (5-min TTL) / 2x (1-hour TTL); read 0.1x | platform.claude.com pricing |
| Vertex Claude batch discount | 50% on input+output; GCS JSONL or BigQuery; ≤24h completion | docs.cloud.google.com claude/batch |
| Vertex request payload cap | 30 MB | claude-on-vertex-ai docs |
| Provisioned throughput | Regional endpoints only (not global/multi-region) | claude-on-vertex-ai docs |
| Quota model | QPM + TPM (in+out) per model/region/project; console-adjustable | docs.cloud.google.com claude/quotas |
| Gemini 3 Flash Preview | $0.50 / $3.00; cached $0.05; batch $0.25/$1.50 | cloud.google.com pricing |
| Gemini 3.1 Flash-Lite | $0.25 / $1.50; cached $0.025; batch $0.125/$0.75 | cloud.google.com pricing |
| Gemini 3.1 Pro Preview | $2/$12 (≤200K), $4/$18 (>200K); cached $0.20; batch $1/$6 | cloud.google.com pricing |
| Web search tool (Claude) | $10 per 1,000 searches + token costs | platform.claude.com pricing |

---

## Implications for VA Claim Path

Context: GCP-native stack (Cloud Run Spring backend + Next.js web), paid tier = AI analysis
(document analysis, gap analysis, scenarios, Ask AI chat); free tier = uploads + VSO sharing.

1. **Vertex keeps the AI stack inside the existing GCP boundary.** Service-account/ADC auth (no
   Anthropic API key to provision/rotate), one GCP bill, Vertex data-governance/zero-retention
   posture, and request-response logging — all material for veterans' medical/PII documents and
   future compliance posture.
2. **Region plan:** use the **global endpoint** by default (no 10% premium, best availability,
   newest models first). us-central1 is not an option for Claude; if single-region US residency is
   ever required, that means **us-east5 at +10%**. Cross-region latency from a us-central1 Cloud
   Run service to the global endpoint is a non-issue for analysis workloads.
3. **Model tiering that matches the pipeline:**
   - Cheap extraction/classification of uploaded evidence: **Gemini 3 Flash Preview ($0.50/$3)**
     or **3.1 Flash-Lite ($0.25/$1.50)** — 2-4x cheaper than Claude Haiku 4.5, with $0.025-$0.05
     cached input. Haiku 4.5 ($1/$5) is the Claude-native alternative (likely pinned to us-east5).
   - Gap analysis / synthesis / Ask AI chat: **Claude Sonnet 4.6 ($3/$15, 1M ctx)**.
   - Hardest multi-document synthesis or agentic deep-dives: **Opus 4.8 ($5/$25)**; Fable 5
     ($10/$50) only if eval shows it pays for itself. Mind the ~35% tokenizer inflation on 4.7+.
4. **Batch the heavy lifting at 50% off.** Vertex Batch Prediction (GCS JSONL or BigQuery in/out,
   ≤24h) fits a Spring-driven nightly/queued re-analysis of a veteran's evidence locker —
   per-document analysis doesn't need to be synchronous. Confirm Opus 4.8/Fable 5 batch support
   first (open question); Sonnet 4.6 + Haiku 4.5 batch is confirmed.
5. **Prompt caching is the main cost lever for Ask AI chat.** Cache the system prompt + the
   user's evidence corpus prefix; reads at 0.1x mean a multi-turn chat over the same records costs
   ~10% of input on subsequent turns. Use 1h TTL for session continuity (2x write, pays off after
   2+ reads). Keep the prefix stable (no timestamps/UUIDs ahead of the cached blocks) and note
   minimum cacheable prefixes (2048 tokens Sonnet 4.6, 4096 Opus/Haiku).
6. **Agentic architecture constraint (feeds task #25):** on Vertex you get the Messages API +
   client-side tool use + structured outputs + web search tool — but **no Managed Agents, no
   code execution, no Files API, no MCP connector**. So the synthesis/gap-analysis agent should be
   a self-hosted loop (Anthropic SDK tool runner or manual loop in the Spring backend, or the
   Claude Agent SDK with `CLAUDE_CODE_USE_VERTEX=1` for heavier agents), with documents inlined
   (30 MB request cap) or pre-extracted to text via the cheap Gemini tier.
7. **Quotas are the scaling risk, not pricing.** Default QPM/TPM per model/region vary by account;
   plan a quota-increase request before launch traffic, prefer global endpoint to dodge regional
   429s, and only consider provisioned throughput (regional-only) if sustained throughput SLAs are
   ever needed.

---

## Open Questions

1. **Do Fable 5 and Opus 4.8 support Vertex Batch Prediction?** Google's batch page snapshot
   lists up through Opus 4.7 + Haiku 4.5; the page may have been updated since. Verify at
   https://docs.cloud.google.com/vertex-ai/generative-ai/docs/partner-models/claude/batch before
   designing a batch pipeline around Opus 4.8.
2. **Exact default QPM/TPM quotas per Claude model in us-east5/global** — the quotas page
   truncated on fetch; check the project's Quotas & System Limits console page (definitive,
   account-specific anyway).
3. **Gemini context-cache storage fee** (per token per hour) — cached-input token price is
   published (10% of input), but the storage SKU wasn't on the fetched pricing page.
4. **Vertex Claude SKU-level cache billing** — Anthropic's multipliers (1.25x/2x/0.1x) are
   authoritative on the Anthropic side and Google's docs confirm the mechanics + both TTLs, but
   the literal Vertex SKU table at cloud.google.com/vertex-ai/generative-ai/pricing#claude-models
   could not be fetched (page redirects/truncates). Verify in the GCP console pricing/SKU browser.
5. **Provisioned throughput pricing/GSU sizing for Claude** — confirmed regional-only, but rates
   were not retrieved.
6. **Per-model global-endpoint coverage** (esp. Haiku 4.5) — check "Supported features" per model
   in Model Garden; Claude Code docs imply Haiku 4.5 may need a regional pin (us-east5).

## Source list

- https://platform.claude.com/docs/en/build-with-claude/claude-on-vertex-ai — model IDs, features, endpoints, premiums
- https://platform.claude.com/docs/en/about-claude/pricing.md — full per-model price/cache/batch tables
- https://docs.cloud.google.com/vertex-ai/generative-ai/docs/partner-models/claude — Google Claude model index
- https://docs.cloud.google.com/vertex-ai/generative-ai/docs/partner-models/claude/batch — Vertex batch prediction for Claude
- https://docs.cloud.google.com/vertex-ai/generative-ai/docs/partner-models/claude/prompt-caching — caching TTLs on Vertex
- https://docs.cloud.google.com/gemini-enterprise-agent-platform/models/partner-models/claude/quotas — quota model
- https://code.claude.com/docs/en/google-vertex-ai — Claude Code/Agent SDK on Vertex, env vars, regions, 1M context
- https://platform.claude.com/docs/en/agent-sdk/overview — Agent SDK overview
- https://cloud.google.com/gemini-enterprise-agent-platform/generative-ai/pricing — official Gemini Vertex pricing
- https://cloud.google.com/blog/products/ai-machine-learning/global-endpoint-for-claude-models-generally-available-on-vertex-ai — global endpoint GA
- https://cloud.google.com/blog/products/ai-machine-learning/multi-region-endpoints-for-claude-available-on-vertex-ai — multi-region endpoints
- https://cloud.google.com/vertex-ai/generative-ai/docs/learn/locations#genai-partner-models — per-region availability reference
