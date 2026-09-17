# Triage: near-empty single-pass extraction (extraction_doc) on gemini-3.1-pro-preview

**Date:** 2026-06-12 · **Status:** root cause confirmed by live probes · **Fix:** not yet applied (diagnosis-only pass; backend tree frozen for a pending deploy commit)

This was Increment 4's flagged unverified risk: whether the hand-built `responseSchema`
in `SinglePassExtractionService.docFactsSchema()` would be honored by the live Vertex
endpoint. It is honored — *too* literally. See "Root cause".

## Symptom

The first live eval (`docs/qa/evals/runs/2026-06-12T0622-baseline/`) saw gc-001 extract
**nothing** (condition_recall 0.0, ~28 visible output tokens, 0 atoms) while gc-017's
run found conditions fine. Single-pass extraction is DEFAULT-ON in production
(`va-claim.extraction.single-pass`), so this gates real veteran uploads. The Increment-4
silent-empty guard correctly converts the empty result into an honest per-doc error
("We couldn't read anything usable…"), but extraction still fails.

## Root cause (evidence-backed)

**The DocFacts `responseSchema` declares every property optional — there is no
`required` array anywhere in `docFactsSchema()`. Under Vertex structured-output
constrained decoding, a JSON object that stops after any subset of properties is
schema-valid, and gemini-3.1-pro-preview non-deterministically takes that degenerate
exit: it emits a minimal valid object and stops with `finishReason=STOP`.**

Live probes (2026-06-12, project `craftloop-va-claim`, location `global`,
`gemini-3.1-pro-preview`, request rebuilt byte-equivalent to
`VertexGeminiAsyncProviderImpl`: same system prompt, user envelope, temperature 0.2,
`maxOutputTokens=65536`, `thinkingBudget=8192`, `responseMimeType=application/json`
+ schema):

| Probe | Config | finishReason | candidates | thoughts | Result |
|---|---|---|---|---|---|
| gc-001 doc1 | **prod config (schema, no `required`)** | STOP | **27** | 1391 | `{"doc_type":"clinical_note","unreadable_or_unsupported":false}` — 0 atoms |
| gc-017 doc1 | prod config | STOP | **27** | 737 | same minimal object — 0 atoms |
| gc-017 doc2 | prod config | STOP | 350 | 1401 | **full extraction** (1 dx, 1 event, 5 atoms) |
| gc-001 doc1 | schema + `thinkingBudget=0` | STOP | 28 | 3892 | still minimal object |
| gc-001 doc1 | **no schema** (prompt only) | STOP | 1961 | 918 | full extraction in \`\`\`json fences |
| gc-001 doc1 | schema-in-prompt + JSON mime only | STOP | 1705 | 941 | full extraction (2 dx, 1 event, 11 atoms) |
| gc-001 doc1 | **schema + top-level `required`** | STOP | 1964 | 942 | **full extraction (2 dx, 1 event, 15 atoms)** |
| gc-001 doc2 | schema + `required` | STOP | 1654 | 936 | full (2 dx, 1 event, 13 atoms) |
| gc-017 doc1 | schema + `required` | STOP | 809 | 386 | full (2 dx, 1 event, 4 atoms) |

Hypotheses eliminated by the probes:

- **(a) Thinking ate the output budget** — no. `finishReason=STOP` (never MAX_TOKENS);
  thoughts ≤ ~3.9k vs `maxOutputTokens=65536`; disabling thinking still produced the
  minimal object. (Side observation: the model **ignored `thinkingBudget:0`** and
  thought 3892 tokens anyway — gemini-3.1-pro-preview appears to treat 0 as dynamic.)
- **(b) Schema rejection / over-strictness** — half right: the endpoint *accepts* the
  schema (no 400, no error), but its permissiveness (all-optional) is the bug.
- **(c) Safety filters** — no `safetyRatings`, no `promptFeedback` blocks on any probe.
- **(d) CONTENT_ENCODING base64 envelope** — not in play for the eval: the golden
  seeder stores docs as plain-text `rawContent` (`sourceType="text"`, no GCS path), so
  the model received plain text. (See "Adjacent prod risk" below — the envelope IS a
  real concern for prod `.txt`/`.docx` uploads, just not this bug.)
- **(e) responseMimeType + thinking interaction** — no: JSON mime without
  `responseSchema` (schema-in-prompt probe) works with thinking on.

## Why some cases pass

Pure chance. The degenerate exit is **non-deterministic** (temperature 0.2): in the
probes the *same* prod config failed gc-017 doc1 (27 tokens) but fully extracted
gc-017 doc2 (350 tokens); in the eval run the coin landed the other way (gc-017
passed, gc-001 failed twice). Document size/content is not the discriminator —
gc-017's 161-byte doc failed while its 209-byte sibling passed.

## Prod impact

**No real veteran upload has been affected yet.** Cloud Logging for `va-claim-api`
since the Inc-4 deploy shows zero extraction activity — no
`ExtractionStateMachine`/`single-pass`/`DocFacts`/silent-empty-guard lines, no
extraction_doc submissions; only `ChunkBackfillJob` heartbeats and startup routing
lines. The exposure is forward-looking: the first real upload would face a per-doc
coin flip, surfaced (thanks to the guard) as "We couldn't read anything usable from
this document" errors — honest, but a broken product experience.

## Recommended fix (minimal, sized for a follow-up agent)

1. **In `docFactsSchema()` add a top-level `required` array naming all 14 top-level
   keys** (`doc_type, doc_date, unreadable_or_unsupported, reason,
   diagnoses_not_found, diagnoses, medications_not_found, medications,
   service_record_not_found, service_records, events_not_found, events,
   atoms_not_found, atoms`). Probes show top-level `required` alone is sufficient —
   item objects then populate fully (with explicit nulls) without item-level
   `required`. `nullable:true` + `required` is the correct Vertex idiom: key must be
   present, value may be null — and the parsers already tolerate nulls.
   - Note: with `required` set, Vertex constrained decoding emits object keys in
     alphabetical order. Jackson parsing is order-insensitive; no parser change needed.
2. **Bump `SCHEMA_VERSION` to "2"** — that is the designed global re-extract lever
   (shifts every `extract_key`). It also trips `PromptVersionEvalGateTest`, which is
   the intended workflow: re-run the live eval (`./gradlew evalLive -PliveEval`) and
   commit the run as acknowledgment. Since prod has had zero extractions, the bump
   costs nothing operationally.
3. Keep the silent-empty guard exactly as is — it just proved its worth; with the fix
   it becomes the backstop for residual non-determinism rather than the common path.
4. *(Optional hardening, same review)*: belt-and-braces `propertyOrdering` is NOT
   needed; do not add per-item `required` unless a later eval shows item-level field
   dropping.

## Adjacent prod risk noted during triage (separate ticket)

For GCS-backed uploads whose MIME is **not** inlineable (`.txt`, `.docx`, anything
outside pdf/png/jpeg/webp/heic in `inlineMimeType()`), the text path feeds the model
`DocumentStorageService.loadExtractionText()` output — which for GCS rows is the
reconstructed **`CONTENT_ENCODING: base64` envelope**, i.e. the model is asked to
read base64. The eval never exercises this (plain `rawContent`), so it is unverified
against the live model. Worth a follow-up probe/fix (decode text-like MIME types
before prompting) — but it is *not* the cause of the gc-001 failure.

## Repro

Probe script (request-faithful rebuild of the provider call):
`/tmp/vcp-triage/probe.py` — ephemeral; rebuild from
`SinglePassExtractionService.SYSTEM_PROMPT`/`docFactsSchema()` +
`VertexGeminiAsyncProviderImpl.runOne()` if needed. Total probe spend: ~9 calls,
< $0.15.
