# Assurance Evidence Engine integration — app-side pickup

**Status: PICKED UP 2026-08-30, NOT STARTED.** This records the tasking and the verified ground
truth. No spec, no plan, no code yet — the six items below need a design pass with the owner before
anything is built.

**Decision (2026-08-30, the owner):** Craftloop's Assurance Evidence Engine becomes After Duty's
claim/gap-analysis engine, running as a **separate Cloud Run service inside After Duty's trust
boundary**. Relayed by the phronesis engine session.

## Why this file exists

The first handoff was routed to the wrong session. It reached the **afterduty.app marketing-site
session** (`~/Developer/VAClaimPath`), which is static-site scope only. That session did the right
thing: it accepted **item 6 alone** (site wording — and even that gated on the integration actually
shipping *and* the owner approving new copy), recorded the obligation in its own repo, returned four
facts about this app, and sent items 1–5 back. The engine repo logged the correction in
`d4f67f0` and parked the row "awaiting app-repo session."

This session (`~/Developer/va-claim-path-app`, PID 23105) is that session. Items 1–5 land here.

## The division

**This repo owns the app-side six:**

| # | Item | Gate |
|---|---|---|
| 1 | Decisions memo | — |
| 2 | Pack generator | `validate` / `lint` / `test-pack` |
| 3 | Java run-record serializer | `conform` |
| 4 | 25-case shadow-mode pilot vs current gap logic | — |
| 5 | Cloud Run deploy pinning an engine SHA | — |
| 6 | Site-wording change — **last, and owned by the site repo** | integration ships + the owner approves copy |

**The engine repo (`~/Developer/phronesis/assurance-engine`) owns:** the engine core; a thin HTTP
wrapper deliberately landing **outside `src/assurance`** so the accreditation story stays
one-accredited-core (Track A1, branch `trackA1-wrapper`, contract in its `docs/SERVICE.md` —
`POST /v1/assess`, `POST /v1/conform`, `GET /healthz`); and the E1 scope-key invalidation
enhancement, triggered only **if** our HLR modeling needs it.

**Contract seam:** `run-record/1` (`EVIDENCE-INTERFACE.md`) plus the pack toolkit.
**Consumption:** local-path, SHA-pinned builds. Cross-session messages both ways; engine session is
`phronesis-ab`.

## Ground truth — the site session's four facts, re-verified here

All four hold. Two need correction before they drive design.

**(a) Pack-generator input is the DB layer, not raw eCFR — CONFIRMED.**
`VasrdRecord` / `VasrdDataService` / `VasrdRecordRepository` exist, and `VasrdRecord.asOfDate` is a
real column (`as_of_date`, `nullable = false`, documented as "eCFR issue date of the ingest"). It is
a sound pack-versioning hook. Note the item-2 label "eCFR-driven pack generator" is therefore
**misleading** — eCFR is upstream of our ingest, and the generator should read the DB, whose
`as_of_date` already carries point-in-time provenance. Also relevant: `Chunk` carries its own
independent `as_of_date`, so "the as_of_date column" is ambiguous in a codebase with two.

**(b) Golden corpus size — RESOLVED, and nobody was stale.**
The "24 vs 25" disagreement is a tier confusion, not a stale fact. The committed classpath tier holds
**exactly 24** synthetic cases (`gc-001`…`gc-024`, per `spring-backend/src/test/resources/golden/manifest.json`).
`gc-100` — the real, expert-confirmed case — lives in the **private tier**, loaded only when
`GOLDEN_PRIVATE_ROOT` / `golden.private.root` is set (`GoldenCaseLoader.java`), because its content is
PHI and can never enter the repo. So: **24 in CI, 25 with the private tier configured.** Item 4's
"25-case pilot" therefore *requires the private root* — a pilot run in plain CI silently gets 24 and
omits the only real case, which is the one that matters most.

**(c) Estimator boundary is isolated — TRUE ONLY IN PART.**
`VaMathService` and `PyramidingRules` exist as named. But `VaMathService` has **four** call sites:
`ConditionController`, `RatingController`, `ClaudeSynthesisService`, `SynthesisStateMachine`. A
serializer seam placed "after synthesis" covers the latter two and **misses both controllers**, which
reach the estimator directly. Either the seam moves, or the controller paths need explicit handling —
this is a design question for item 3, not a detail.

**(d) `GCP_PROJECT=craftloop-va-claim` is the trust-boundary anchor — CONFIRMED.**
Consistent with everything in this repo; the engine service deploys into the same project.

## Message sent to the engine session — 2026-08-31

Claimed items 1–5 in the engine repo's coordination row per its parallel-session protocol
(`phronesis/assurance-engine` commit `53fbbf5` on `main`, session id `vaclaimpath-56d4c57b`). That row
had been parked "awaiting app-repo session" because the engine session believed no app session
existed. It relays the three fact corrections above and asks five clarifications.

**Since then:** the A1 wrapper **merged to engine `main`** (`e9c62af`, suite 744), so the HTTP contract
is live rather than pending — `GET /healthz`, `POST /v1/assess`, `POST /v1/conform`, documented in
`docs/SERVICE.md`. The `run-record/1` schema is at the engine repo **root**, `EVIDENCE-INTERFACE.md`
(not under `docs/`), required fields `schema_version, run_id, sut, scenario, conditions, determinism,
outcome_kind, harness`.

## ALL FIVE CLARIFICATIONS ANSWERED — 2026-08-31

Full reply: `docs/architecture/assurance-engine-answers-2026-08-31.md` (engine canonical copy
`docs/afterduty/ANSWERS-2026-08-31.md`, commit `2bb56cc`). Both fact corrections accepted. Every
artifact cited below was verified to exist before being recorded here.

**1. The `run-record/1` premise was WRONG — and it was our tasking that was wrong, not the answer.**
`run-record/1` is for **harness-emitted instrumented test runs**. Stage E-2's 45/45 never went
through it. Document-derived VA evidence is **scenario evidence**, not test runs.

> **Item 3 is re-scoped: the Java serializer emits engine SCENARIO JSON, not run-records.**
> Consumed by `POST /v1/assess`. The `conform` gate applies to future instrumented telemetry and is
> **not the v0 seam** — so item 3's stated gate was wrong too.

Verified scenario shape (`validation/bva/cases/gc-101-*.json`): `subject`, `config`, `evidence`,
`adjudications`, `ontology`, `gold`, `bva_meta`. Nothing resembling `sut`/`determinism`/`outcome_kind`.
The domain mapping to read first is **five public BVA scenarios** built from real published Board
decisions (`validation/bva/cases/gc-10{1..5}-*.json` — hypertension, denied + granted variants),
plus **12 synthetic** scenarios in `validation/goldset/cases/{dev,test}/` showing adjudication,
coverage and challenge blocks.

**2. Item 4 — app-side**, engine invoked by CLI at a pinned SHA over a local path; no service needed
for the pilot. `GOLDEN_PRIVATE_ROOT` is **required**, and the pilot must assert the private tier
loaded and **fail loudly at 24 cases**. Comparison of record is a **fresh engine run** (our generated
pack + our serializer output) vs current app gap logic, per case, committed as a report. Stage E's
45/45 is a **sanity baseline only** — divergence is a finding about the generated pack, never a
number to hard-code toward.

**3. Item 5 — our pipeline** builds the image from an engine git SHA (multi-stage, mirroring the
engine's committed `Dockerfile`), pushed to **Artifact Registry in `craftloop-va-claim`**. The engine
publishes no registry image pre-award. **Binding:** install the core profile via
`pip install --require-hashes -r requirements-core.lock`, never a bare `pip install`.
**Also binding on our generated packs** (service input limits): ≤256 KB pack text, nesting depth 64,
200k YAML events, and **no YAML anchors/aliases**.

**4. Item 2 — generation target is `ontologies/bva.rating.v1.yaml`** (public, VA-domain: hypertension
DC 7101, 38 CFR service-connection elements as sufficiency rules, adjudication authority, denial
modeling). `ontologies/atec.uas.v1.yaml` is a second structural example in the same grammar.
`assurance schema pack` prints the machine-readable schema; `docs/AUTHORING.md` is the authoring
guide; `validate` / `lint` / `test-pack` are our quality gates. The private goldencase pack is **not
needed** and stays path-referenced (PHI rule).

**5. Item 1 — the memo comes first** and is the instrument that proposes the remaining app-side
decisions to the owner: ledger persistence (ephemeral vs GCS/AlloyDB for user-facing audit trails), pack
regeneration cadence against `VasrdRecord.as_of_date`, pilot design, and — resolving our fact (c) —
**seam scoping**: the engine consumes **post-synthesis batch assessments**, and the two controller
paths reaching `VaMathService` directly are *live estimation UX, not argument-keeping*, so they need
**no engine call in v0**. That must be stated as an explicit scoping decision, not left implicit.

## Engine state (verified 2026-08-31)

`main` = `380f4a7`, suite **780**, and **in sync with `origin`** (`github.com/craftloopdev/assurance-engine`)
— the owner pushed, so CI can fetch by SHA; pin `380f4a7` or later — **superseded 2026-09-06: pin `1b94993` or later**, because `380f4a7`
predates the token gate (memo D6). Newly relevant: `assurance export
SCENARIO --format gsn [--svg PATH]` renders any assessment as a disposition-colored GSN argument
graph (our pilot report can carry a per-case picture); hash-pinned locks + CycloneDX SBOMs with a
`tools/make_sbom.py --check` drift gate; the A1 wrapper merged and hardened (bounded input, PHI-safe
logging, authless by design — Cloud Run terminates TLS and does IAM).

## Standing channel (no human relay)

- **App → engine:** commits to the engine board row (works today). `SendMessage` to `phronesis-ab`
  bounces in both directions (confirmed 2026-09-01); board commits and file drops are the only channels.
- **Engine → app:** files dropped into this `docs/architecture/`, because this session exposes no
  message endpoint so `SendMessage` to us bounces. If we ever register a reachable session name,
  announce it in the next board commit and messaging supersedes files.

Report engine-side defects to `phronesis-ab` or the board row — never work around them silently.

## Next step

Nothing is blocked. Item 1 (the decisions memo) is next and is the input to a brainstorm + spec with
the owner. Item 6 stays with the site repo, last.

## Update 2026-09-06/07 — D1–D6 approved; work begins

the owner approved D1–D6 as written on 2026-09-06 (in the engine session; delivered here as two
engine-authored file drops, `assurance-engine-GO-2026-09-06.md` and
`assurance-engine-cloudrun-readiness-2026-09-06.md`, both now committed — the engine repo holds no
canonical copy of either). One correction, to D6's pin: **`1b94993` or later**, because `380f4a7`
predates endpoint authentication. The memo's D6 now carries the full deploy contract (fail-closed
`ASSURANCE_SERVICE_TOKEN`, ordered deploy, both caller headers, the internal-ingress requirement and
what it costs on our side). Sequencing per the memo: brainstorm + spec with the owner → D2 serializer →
D3 pack (steps 1–2) → D5 pilot → D3 steps 3–4; D6 in parallel.

Engine `main` on 2026-09-07: `d70737c` (docs only), with `1b94993` the last code change. The engine
board records that it believes no live app session exists (its `SendMessage` attempts reach the
marketing session and bounce); this session is live and picks up by file drop, as before.
