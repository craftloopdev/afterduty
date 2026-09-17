# Panel Judge — Accuracy / Veteran-Outcome Skeptic

Date: 2026-06-10. Judge lens: which design most reliably tells a veteran the TRUTH about
their conditions and gaps, refuses to fabricate medical/legal claims, communicates plainly,
and keeps that property as the case file grows. Cost arithmetic sanity-checked against the
research drafts and verified against source.

## Verification of load-bearing legacy claims (all confirmed in source)
- Silent zero-conditions: `service/synthesis/SynthesisStateMachine.java:135-140` — `if
  (conditions.isEmpty()) { ...markSynthesisComplete... }`. REAL. This is the single worst
  veteran-harm failure mode (an unparseable identify → "you have no conditions"). Every
  design cites it correctly; the accuracy design makes killing it the spine.
- Perpetual re-run loop: `AnalysisScheduler` `!currentClaudeModel.equals(claim.getLastSynthesisModel())`
  and same for gap. REAL.
- Per-condition full-atom resend: `RatingAgent.buildRequest(condition, List<Atom> atoms,...)`
  formats ALL atoms ("Evidence atoms (%d total)"). REAL — drives O(cond×atoms) cost.
- Chat = uncached Opus, no AiCallLog: `ChatAgent` default `claude-opus-4-7` (:52, :402),
  no AiCallLog write found. REAL.
- `purposeDefaultModels` empty → falls to `defaultModelFor` → `claude-opus-4-7` /
  `gemini-3.1-pro-preview` (`LlmProviderRouter.java:32, 84-95`). REAL.
- Cap escape via `.userId(null)` in extraction (multiple sites); `limit-cents: 400`,
  `claude-region: global` already default (application.yml). REAL.

## Material FLAW found in pricing arithmetic / claims
1. **Legacy-cost-overstatement claim diverges across designs and the accuracy design is the
   loosest.** Verified `AiCostService` PRICING has `claude-opus-4-7 = $15/$75`; real Vertex
   price is $5/$25 → **3x** overstated on price (ship's "3x" is correct). Accuracy says **6x**
   — only true for a *batched* Opus stage (3x price × 2x missing-batch-discount); stated
   flatly as "6x-overstated Claude cost accounting" it is wrong without that caveat. Cost says
   "~2x" — an UNDERSTATEMENT. Minor, but it is the accuracy lens citing a number it should be
   the most careful about.
2. **Cache-in-batch is unverified yet load-bearing in all three.** research-agentic §5: a cache
   entry is readable only after the first response streams; for fan-out "send 1, await first
   token, then the rest, or all N pay full write." A Vertex Batch job submits all rows at once —
   there is no await-first-token ordering within a batch. Whether per-row prompt-cache READS
   work across a single batch job is NOT established in the research. **Ship is most exposed**:
   §6.1 prices the 12 rate calls ($0.387) and 24 gap calls ($0.90) as *both* "Sonnet batch" *and*
   cached-read 0.30 — if batch rows can't read each other's cache they pay write, and ship's
   $1.96 initial understates by roughly +$0.5–1.2. Accuracy is least exposed (only ~4 judgment
   rate calls batched; criteria prefix, not full atom corpus). Cost hedges (rate/gap "realtime /
   batch (fleet)"; cache math on realtime path).
3. **Cost design's headline "$0.32 initial" is asserted, not summed.** Its own line items sum to
   $0.82 (batched) / $1.43 (realtime) — both reproduce correctly. The $0.32 "target" depends on
   "Gemini-3 free native-PDF text on the typical born-digital share" + warm cache, neither of
   which is line-itemized. The honest figure for this design is ~$0.82; $0.32 is a hopeful floor
   and the headline oversells.
4. **Cost design's chat "$0.013/question cache-warm" is ~2x optimistic.** Its own marginal calc
   (8K prefix) is $0.0249/question incl. output; 25K is $0.030. $0.013 is neither figure. The
   session totals ($0.17/$0.32) reproduce fine; only the per-question headline is off.
5. **Accuracy design's chat session (~$0.90) uses a 120K full-case-file prefix** vs cost/ship's
   8–25K extraction+retrieval prefix → ~3x costlier. Defensible for the lens (it buys
   citations over full docs) but it IS the most expensive chat of the three and should be
   acknowledged as a deliberate accuracy-for-cost trade, which it is.
6. **Ship appendix is malformed** (stray `</content></invoke>` at EOF) — cosmetic, content intact.

## Accuracy / grounding stress test (the heart of my lens)
- **Abstention as first-class schema field**: all three adopt it. ACCURACY makes it structural
  (every stage abstains; empty-identify becomes `abstain_low_evidence`, never auto-complete).
  Ship/cost adopt the field but ship still describes the fast-path mechanically; accuracy is the
  only one that treats "we couldn't confirm X" as a first-class *presented* state to the veteran.
- **Verifier with veto + sees the atoms**: ACCURACY's stage 8 (Opus reads atoms+ratings+citations,
  binding suppress/demote) is materially stronger than ship's ≤2-turn self-repair (which re-rates
  flagged conditions but does not suppress unsupported *sentences* before the veteran sees them)
  and cost's single Sonnet adversarial gap pass. For a tool that must not show a veteran a
  fabricated claim, a binding verifier that can suppress a specific unsupported sentence is the
  right primitive. This is ACCURACY's decisive advantage on the judging lens.
- **Deterministic rating gate (resurrect VasrdDecisionEngine)**: ONLY accuracy does per-DC
  deterministic rating (tinnitus 6260=10% in code, never the LLM). This both removes cost AND
  removes a hallucination surface. Ship/cost leave all rating to Sonnet. Strong graft for both.
- **Different-family / harder-tier judge & verifier**: accuracy and ship both specify cross-family
  judge (debias self-preference); accuracy additionally uses a *harder* model for the runtime
  verifier than the generator. Best practice (research-agentic §2). Cost specifies debiasing but
  keeps verifier at Sonnet (same family/tier as generator) — weakest on self-preference bias.
- **Citations two-pass / no-legal-advice as a hard judge criterion**: all three. Accuracy and ship
  both make no-legal-advice a per-output rubric line; accuracy makes it a hard criterion. Good.
- **VASRD grounding tool vs free recall**: accuracy's `vasrd_lookup` over full parsed Part 4 (202
  sections, not the legacy 37) returning verbatim §-text + as-of date is the strongest anti-
  hallucination grounding for rating criteria. Ship/cost parse Part 4 into structured records but
  describe it more as display/deterministic-math backing than as a model-facing grounding tool.
- **eCFR freshness = correctness**: all three cite the §4.10 publish-then-rescind and nightly diff.
  Equivalent. Good across the board.

## Feasibility for a 1-person team (skeptical read)
- SHIP is the only one organized as independently shippable, reversible increments (0→8) each
  behind an existing interface/flag, explicitly leveraging the *already-on-classpath*
  `anthropic-java-vertex:2.18.0` + `google-cloud-storage:2.45.0` (verified plausible — these are
  exactly the swaps that de-risk "move to Vertex" to wiring). Highest feasibility by a wide margin.
- ACCURACY is the most ambitious (two-pass everything, golden set as a hard gate before stages
  ship, deterministic engine resurrection, harder-tier verifier). Correct, but heavier for one
  person; its own migration §7 is reasonable but step 5 ("introduce structured+abstention+two-pass
  per stage, each gated by golden set") is a lot of serial work.
- COST is feasible but its biggest wins (the $0.32 headline) lean on the least-certain mechanisms
  (free native-PDF text share, cache-in-batch) and its accuracy floor is the thinnest of the three
  (same-tier verifier, no deterministic rating gate).

## Incremental-update design (delta) — all three converge, differences at the margin
- All adopt content-addressed artifact graph, supersede-not-append, dirty-scope re-run, atomic/
  generation-pointer activation, idempotent-by-hash. This is the strongest area of agreement and
  all correctly trace it to the verified legacy defects (findByClaimId, append, re-run loop).
- ACCURACY adds the subtlest-but-most-correct detail: **stable condition identity across re-analysis**
  by `(vasrd_code, body_system, theory)` so chat references and user-set gap statuses survive — and
  honestly flags the multi-condition-per-DC mis-merge risk (bilateral). Ship also preserves
  superseded conditions for chat/citation validity. Cost preserves gap statuses by merging forward.
  Accuracy's treatment is the most rigorous; ship's is the most concretely wired to existing columns
  (`run_id` + `superseded_by`, `deleteByClaimIdAndStage`).

## WINNER: design-accuracy — on my lens — but it MUST absorb ship's migration sequencing.
For a benefits tool where a confidently-wrong answer is the unrecoverable failure, the design that
makes abstention structural, gives the verifier binding veto + atom visibility, grounds every DC in
a typed Part 4 lookup, rates mechanical codes deterministically in code, and gates every change on a
golden set is the one that most reliably tells a veteran the truth. That is design-accuracy. Its
cost is the highest of the three (~$0.85–1.10 initial, ~$0.90 chat) but it is the *honestly* costed
one (no $0.32 hopeful floor) and still lands well under the $11.99 price. Its single real weakness is
feasibility/sequencing for one person — which is exactly ship's strength. The final architecture
should be design-accuracy's pipeline delivered via ship's 8-increment, behind-a-flag, reuse-the-
classpath sequencing, with cost's batch/cache discipline as the unit-economics layer.

## GRAFTS the final architecture must keep
- From SHIP: the 8-increment "stop the bleeding first" migration order; explicit reuse of the
  already-present `anthropic-java-vertex:2.18.0` + `google-cloud-storage:2.45.0`; honoring existing
  `superseded_by` / `run_id` columns and finally calling `deleteByClaimIdAndStage`; the
  `preferredModel`/`preferredProvider` per-call escalation hook that already exists unused; chat
  billing fix (route chat through AiCallLog + gate on paid tier) stated as a correctness fix.
- From COST: stable-prefix cache discipline with the `cache_read_input_tokens > 0` CI+prod assertion
  as a regression tripwire; per-doc (not per-corpus) escalation with the <40%-escalation monitor;
  the explicit, measured quality FLOOR per stage as the thing the golden set enforces; pre-LLM
  embedding+exact-token dedupe before the merge call.
- From ACCURACY (the winner, kept): first-class abstention as a *presented* state; binding verifier
  that sees atoms+citations and can suppress a specific unsupported sentence; deterministic
  VasrdDecisionEngine rating gate for mechanical DCs; `vasrd_lookup` model-facing tool over full
  parsed Part 4 with as-of dates; harder-tier/different-family verifier+judge; stable condition
  identity across re-analysis with the multi-condition-per-DC risk called out.

## Shared FLAWS the final architecture must resolve before trusting the numbers
- Confirm prompt-cache READ semantics *within a single Vertex Batch job* before pricing any
  batched per-condition fan-out at 0.1x (re-baseline ship's $1.96 if it doesn't hold).
- Confirm Opus 4.8 (and Fable 5) Vertex Batch support before batching on them (all three flag it;
  keep Opus on realtime verify until confirmed).
- Re-baseline all token counts against real AiCallLog after migration (Opus 4.7+/4.8 tokenizer ~35%
  inflation; all three flag it but all use list-price token counts).
- OCR/text-layer coverage of real uploads is unknown and gates Citations on scanned records — needs
  a sample audit (all three flag; none can resolve it from the drafts).
- Judge/golden calibration needs a domain expert (VSO) to label "completeness of gap analysis" and
  rating-band correctness — open product dependency (accuracy and ship both name it; it is real).
