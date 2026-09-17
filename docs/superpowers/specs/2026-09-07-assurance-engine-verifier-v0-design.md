# Assurance Engine as a second verifier — v0 design

**Status:** design APPROVED by the owner on 2026-09-07 (in chat, after the decisions memo D1–D6 was
approved on 2026-09-06). This file is the written spec for review before an implementation plan is
written. **Two calls made at approval:** (1) pack v1 carries four checks, not the two D3 named for
steps one and two; (2) item 2 in v0 means an *authored* pack with the engine's gates, and the
generator arrives with the per-code step.

**Decisions this spec implements:** `docs/architecture/assurance-engine-decisions-memo.md` (D1 verifier
seam, D2 scenario JSON, D3 check-scoped pack, D4 Postgres ledger with account-lifetime retention, D5
two-verifier shadow pilot, D6 deploy with the 2026-09-07 ingress decision). Engine pin: `1b94993` or
later. Engine contract: engine repo `docs/SERVICE.md`; pack grammar: `docs/AUTHORING.md`; format
reference: `validation/bva/cases/gc-10*.json`.

## 1. Goal

Run Craftloop's Assurance Evidence Engine as a second, rule-based verifier beside the existing LLM
verifier, on the same per-condition input, in shadow mode, persisting both verifiers' findings, and
score both against the golden corpus including the one real case.

## 2. Scope

**v0 delivers**

- Pack v1 `afterduty.verify` with four decidable checks per condition, hand-authored, gated by the
  engine's `validate`, `lint` and `test-pack`.
- A deterministic Java serializer from the app's active conditions to engine scenario JSON.
- Two engine clients behind one interface: CLI over a pinned checkout, and HTTP with both headers.
- A mapper from engine dispositions to the app's existing issue shape, with rule id and citation.
- A ledger table holding the scenario, the result, the engine's issues and the LLM verifier's issues.
- An after-commit, async, flag-gated hook that never affects the pipeline.
- A pilot harness and scoreboard over all 25 golden cases, with tiered reports.

**v0 does not deliver**

- Any UI change. Nothing the veteran sees changes.
- Any gating. Engine findings never block or alter a claim.
- Per-code rating criteria, ROM codes, or mental-health codes (D3 steps three and four).
- Page or quote locators (extraction follow-on).
- A pack generator (arrives with per-code rules, keyed on `VasrdRecord.asOfDate`).
- `missing_secondary` findings. Not decidable; the engine never emits them.
- CI wiring. No GitHub workflow runs the backend tests today; gates run locally through gradle.

## 3. What the code exploration established

These facts shaped the design and are recorded so they are not re-derived.

- The LLM verifier's output is almost entirely discarded today. `EnhancedSynthesisOrchestrator.applyCorrections` handles three of five issue types, and its output is dropped when the deterministic pyramiding pass is on (the default). `insufficient_evidence` and `missing_secondary` end in a debug log. The only durable trace is the raw model text in `llm_jobs.response_payload`. The ledger in this spec is the first place verifier findings persist.
- Triad legs on `IdentifiedCondition` are JSON maps with `status` (STRONG, MODERATE, WEAK), `confidence` and `evidence[]`. `EnhancedSynthesisOrchestrator.isSupportedLeg` counts STRONG and MODERATE as supported.
- `VasrdDataService.getByCode` returns `rating_levels` keyed by percent strings (or the literal `note`), plus `as_of_date` when known.
- Provenance available: evidence id, filename, file hash, atom id, atom free-text `source`. No page numbers anywhere.
- `PyramidingGroups.groupFor` maps codes 9201–9440 and 9520–9521 to one mental-health group; `assignPyramidingGroups` marks one primary and sets `pyramidReason` on the others; `PyramidingRules` treats a non-blank `pyramidReason` as the exclusion signal, caps 6260 at 10, and pairs exactly two conditions sharing a code.
- Golden expectations (`GoldenExpectation.PhaseExpectation`) have no slot for expected verifier issues.
- Flyway is not configured; `ddl-auto=update` creates tables from entities, and `db/migration/*.sql` files are the canonical record kept in lockstep.
- `@EnableAsync` is on; no `@TransactionalEventListener` exists yet.
- Engine CLI: `assurance run SCENARIO --export PATH` writes the raw export; `assurance export --format gsn --svg PATH SCENARIO` renders the argument. The `matches` operator is `re.fullmatch`, so containment patterns are written `(?is).*X.*`.
- Engine export shape: `claims[]` entries carry `id`, `type_ref`, `parameters` (every assessment parameter plus the bound ones), `parent_id`, `statement`; `dispositions[]` entries carry `claim_id`, `value`, `derivation`, `inputs_hash`, `ontology_hash`. The mapper joins on `claim_id`. A rule-decided derivation carries `rule_id`, `requirements[]` (each with `requirement_id`, `satisfied`, `shortfall`, `evidence_satisfying`), `unmet[]`, `refutation_checks[]`, `requirements_satisfied`, `requirements_total`, `rationale` and `versions`; a decomposed claim's derivation carries `aggregator`, `strategy` and `children`.
- Engine provenance contract (`src/assurance/evidence.py`): every record needs `source_uri`, `extraction_method`, `extraction_version`, `ingest_timestamp`, plus `locator` when the type requires it; a record marked `model_derived` additionally needs `model_id`, `model_version`, `prompt_hash`, `params_hash`, each non-empty. Only missing keys are rejected; extra keys pass through.

## 4. Architecture

```
SynthesisStateMachine.doCompleteIfReady         (existing: parses LLM issues, flips the generation)
    | publishes SynthesisVerifiedEvent           (inside the same transaction)
    v
AssuranceShadowListener                          (@TransactionalEventListener AFTER_COMMIT, @Async)
    v
AssuranceShadowService.assess(event)
    |-- load active conditions by id, VASRD rows by code, corrections matcher
    |-- EngineScenarioSerializer  -> scenario JSON + scenario hash
    |-- AssuranceEngineClient     -> EngineAssessment   (CliAssuranceEngineClient | HttpAssuranceEngineClient)
    |-- EngineIssueMapper         -> engine issues (same shape as LLM issues + rule_id, citation)
    '-- AssuranceAssessmentRepository.save(row)   (both issue lists, hashes, result, status)
```

Failure anywhere after the event fires produces a ledger row with a status and a PHI-safe error
summary. Nothing propagates back to the pipeline.

## 5. Pack v1 — `afterduty.verify`

**Location:** `spring-backend/src/main/resources/assurance/packs/afterduty.verify.v1.yaml`, with
`afterduty.verify.packtest.yaml` beside it. Read as a classpath resource, hashed, and sent as request
text (the service takes pack text in the body; the CLI reads it from a path).

**Identity:** `id: afterduty.verify`, `version: "1.0.0"`. Version bumps on any rule change.

**Assessment parameters** (scenario `config.parameters`): `conditions` (list of condition keys),
`schedule_as_of_date` (string), `claim_id` (string).

**Test activity:** `TA-AFTERDUTY-SYNTHESIS`, unit `condition`.

**Evidence types** (all produced by `TA-AFTERDUTY-SYNTHESIS`; `locator_required` stays true):

| Type | Fields | `model_derived` |
|---|---|---|
| `condition_finding` | `condition_key` str, `condition_name` str, `vasrd_code` str, `vasrd_code_known` bool, `body_system` str, `estimated_rating` int, `rating_in_schedule` bool, `schedule_levels_known` bool, `schedule_levels` list, `schedule_as_of_date` str, `is_presumptive` bool, `excluded_from_claim` bool, `supporting_atom_count` int | false |
| `triad_finding` | `condition_key` str, `element` str (`diagnosis`, `in_service`, `nexus`), `status` str, `supported` bool, `confidence` float, `evidence_count` int | **true** (`model_derived_allowed: true`); the status is the model's own reading and the argument says so |
| `presumptive_finding` | `condition_key` str, `is_presumptive` bool, `basis` str, `basis_disproven` bool, `disproven_by` str | false; emitted only for presumptive conditions |
| `pyramiding_finding` | `condition_key` str, `vasrd_code` str, `estimated_rating` int, `pyramid_group` str, `pyramid_primary` bool, `absorbed` bool, `rated_peers_in_group` int, `same_code_count` int | false |

Raw values sit beside every reduced boolean so a reader of the ledger sees "rating 30, levels
10/20/40/60, in_schedule false", never a bare "false".

**Claim types and argument shape**

```
analysis_verified            root; all_children_established; over: conditions, item_as: item
  condition_verified         bind condition_key: $item; all_children_established
    code_valid                        SR-CODE-VALID
    rating_in_schedule                SR-RATING-LEVEL
    service_connection_supported      any_child_established
      triad_elements_supported        SR-TRIAD
      presumptive_basis_valid         SR-PRESUMPTIVE
    not_pyramided                     SR-NOT-PYRAMIDED
```

Bound parameters flow from the fan-out to the leaf rules, exactly as the engine's AiTR pack binds
`condition` at one level and reads it with `param: condition` in `SR-PD-CELL`.

**Sufficiency rules** (every `requires[]` entry selects records with
`where: [{field: condition_key, op: eq, param: condition_key}]`):

| Rule | Requires | Refuted when | Citation |
|---|---|---|---|
| `SR-CODE-VALID` | 1 `condition_finding`, `vasrd_code_known eq true` | `vasrd_code_known eq false` | 38 CFR Part 4; §4.27 (diagnostic code numbers) |
| `SR-RATING-LEVEL` | 1 `condition_finding`, `rating_in_schedule eq true` | `rating_in_schedule eq false` | §4.7, §4.31, and the code's own criteria; DC 6260's single 10 falls out |
| `SR-TRIAD` | 3 `triad_finding` (`min_count: 3`), `supported eq true` | none | §3.303(a); Shedden v. Principi, 381 F.3d 1163 |
| `SR-PRESUMPTIVE` | 1 `presumptive_finding`, `is_presumptive eq true`, `basis_disproven eq false`, `basis matches <recognized-basis pattern>` | `basis_disproven eq true` | §3.307, §3.309, §3.317, §3.320 (PACT Act); the pattern is pack data, reviewed by the owner |
| `SR-NOT-PYRAMIDED` | 1 `pyramiding_finding`, `rated_peers_in_group eq 0`, `same_code_count lte 2` | `rated_peers_in_group gte 1`; `same_code_count gt 2` | §4.14; §4.130 single evaluation; §4.25/§4.26 for the bilateral pair |

Disposition semantics that the mapper relies on: a rule whose records are present but unsatisfied
is PARTIAL; no records at all is UNEVALUATED; a `refuted_when` match is REFUTED. A non-presumptive
condition has no `presumptive_finding`, so that leaf is UNEVALUATED and `any_child_established`
resolves on the triad leaf alone.

**Invalidation rule:** `on: ontology_change` invalidates `condition_finding` and
`pyramiding_finding` (schedule-derived findings), rationale: the schedule or the pack moved.

**Packtest:** leaf rules only (the engine's packtest exercises leaves). For each of the five rules:
ESTABLISHED, PARTIAL, UNEVALUATED (no records), and REFUTED where a `refuted_when` exists. About
eighteen tests.

**Gates:** gradle task `assurancePackGates` runs `assurance validate`, `assurance lint` (warnings
recorded in the task output; unknown keys fail) and `assurance test-pack` using
`$ASSURANCE_ENGINE_HOME/.venv/bin/assurance`; skipped with a warning when the variable is absent. A
Java `PackSafetyTest` mirrors the service's input guard with SnakeYAML events: under 256 KB, no
anchors or aliases, depth under 64, under 200,000 events.

## 6. Serializer

`com.afterduty.service.assurance.EngineScenarioSerializer`. A pure function over an input record;
no repositories, no clock reads. `AssuranceShadowService` assembles the input.

**Input** `ScenarioInput(Long claimId, String generation, String synthesisModel,
List<IdentifiedCondition> conditions, Map<String, VasrdCodeInfo> schedule,
CorrectionsMatcher corrections, Map<Long, AtomRef> atoms, Instant now)`.

- `VasrdCodeInfo(String code, boolean known, List<Integer> levels, String asOfDate)` built from `VasrdDataService.getByCode`.
- `CorrectionsMatcher` is a functional interface, `(conditionName, presumptiveBasis) -> Optional<String>`
  returning the matching correction id. `DomainCorrectionsService` gains a public, non-mutating
  `disprovenBy` implementing it and sharing the matcher `enforce` uses (a blank basis counts as a
  match; a name containing `undiagnosed` never matches).
- `AtomRef(Long atomId, Long evidenceId, String filename, String fileHash, String source)`.

**Output:** scenario JSON.

```json
{
  "subject":  {"claim_id": "1234", "generation": "gen-…", "synthesis_model": "…"},
  "config":   {"id": "afterduty-claim-1234-gen-…",
               "parameters": {"conditions": ["c-77", "c-78"], "schedule_as_of_date": "2026-08-01", "claim_id": "1234"},
               "challenge_enabled": false},
  "evidence": [{"type": "condition_finding", "payload": {…}, "provenance": {…}, "model_derived": false}, …],
  "adjudications": [],
  "ontology": "afterduty.verify.v1.yaml"
}
```

`ontology` is rewritten by each client: the CLI client to the absolute path of the pack it wrote
to a private temp directory; the HTTP service overwrites it with the request's pack text.

**Reduction rules** (each is a unit test):

| Field | Rule |
|---|---|
| `condition_key` | `"c-" + id`; conditions without an id are refused (the serializer only runs over persisted rows) |
| `vasrd_code_known` | `schedule.get(code).known` (the DB tier, with the curated JSON fallback counting as known) |
| `schedule_levels` | `rating_levels` keys parseable as integers, ascending |
| `schedule_levels_known` | levels non-empty |
| `rating_in_schedule` | rating null or 0, or levels unknown, or levels contain rating |
| `triad_finding.supported` | status is STRONG or MODERATE (mirrors `isSupportedLeg`); a null leg emits status `MISSING`, supported false, evidence_count 0 |
| `basis_disproven`, `disproven_by` | `corrections.disprovenBy(name, basis)` present |
| `absorbed` | `pyramidReason` non-blank (the `PyramidingRules` exclusion signal) |
| `rated_peers_in_group` | if this condition is rated, not absorbed and has a group: count of other conditions with the same group, rating above 0, not absorbed; otherwise 0 |
| `same_code_count` | conditions (including this one) sharing a non-blank `vasrd_code` |
| `supporting_atom_count` | size of `supportingAtomIds`, or 0 |

**Provenance per record:** `locator` `claim/<id>/condition/<cid>[/triad/<element>]`, `source_uri`
`afterduty://claim/<id>/condition/<cid>`, `extraction_method` `afterduty.serializer`,
`extraction_version` `"1"`, `ingest_timestamp` RFC 3339 from `now`. Evidence references are carried
as additional provenance keys, which the engine passes through: `evidence_refs` (a list of
`{evidence_id, filename, file_hash}` deduplicated over the condition's supporting atoms) and
`atom_ids`. `triad_finding` records are model-derived and therefore also carry `model_id` and
`model_version` (the synthesis model the pipeline recorded for this generation), `prompt_hash` (the
identify prompt's registered version id) and `params_hash` (see section 6a); the serializer refuses
to emit a model-derived record with any of the four blank, because the engine would reject the whole
scenario.

**Model-derived provenance (6a).** `triad_finding` records carry `model_id` and `model_version`
from the model name the pipeline recorded for this generation (`LlmJob.model_name`; Vertex model ids
carry their version), `prompt_hash` from `PromptVersionRegistry.IDENTIFY_PROMPT_VERSION` joined with
`RATING_PROMPT_VERSION`, and `params_hash` as the SHA-256 of the identify request's recorded
`purpose`, model name and `maxTokens`. `AssuranceShadowService` resolves these from the event; if any
is unavailable the serializer refuses the record and the row records `serializer_error`.

**Determinism:** conditions ordered by id; evidence ordered by condition key, type, element; JSON
object keys sorted. `scenarioSha256` is computed over the canonical JSON with every
`ingest_timestamp` replaced by a fixed sentinel, so identical inputs hash identically.

**PHI:** condition names, bases and statuses are health information. The scenario is stored in the
ledger table and sent only to the engine inside the project. It is never logged. Clients log
method, status, sizes and duration only.

## 7. Engine clients

```java
public interface AssuranceEngineClient {
    EngineAssessment assess(String packText, JsonNode scenario) throws EngineException;
}
public record EngineAssessment(String rootDisposition, List<Disposition> dispositions,
                               String ledgerHead, String engineVersion, JsonNode raw, Duration elapsed) {}
public record Disposition(String claimId, String typeRef, Map<String,Object> parameters,
                          String value, JsonNode derivation) {}
```

- `CliAssuranceEngineClient`: writes pack and scenario to a private temp directory, runs
  `$ASSURANCE_ENGINE_HOME/.venv/bin/assurance run <scenario> --export <path>`, parses the export
  (`claims[]` joined to `dispositions[]` by `claim_id`), deletes the directory in a `finally`. Used
  by tests and the pilot.
- `HttpAssuranceEngineClient`: `POST {url}/v1/assess` with `{"pack", "scenario", "include": ["export"]}`,
  headers `Authorization: Bearer <Google identity token for audience url>` (from the metadata server
  through the google-auth library already on the classpath via Firebase Admin) and
  `X-Assurance-Service-Token: <token>`. Timeout from config. Any non-200 raises `EngineException`
  with status and the engine's `error` string truncated to 200 characters (the engine never echoes
  input in errors). Used in production.
- Both adapters normalize to the same `EngineAssessment`. The HTTP `export` projection is
  `argument_view(export)`, which deep-copies the raw export and removes only the volatile fields
  (ledger head and evidence ingest timestamps); the `claims[]` and `dispositions[]` arrays are the
  same in both. A contract test tagged `eval-assurance` still runs one scenario through the CLI and
  through a locally started service and asserts the normalized dispositions are identical, so a
  future engine change to either projection is caught here rather than in production.

**Configuration** (`va-claim.assurance.*` in `application.yml`, env in parentheses):
`enabled` (`ASSURANCE_ENABLED`, default false), `mode` (`shadow`, the only value), `client`
(`http` or `cli`, default `http`), `url` (`ASSURANCE_SERVICE_URL`), `token`
(`ASSURANCE_SERVICE_TOKEN`, mounted from Secret Manager, never logged), `timeout-ms` (default
10000), `engine-home` (`ASSURANCE_ENGINE_HOME`, CLI only), `engine-sha` (the pinned SHA, recorded
in the ledger), `pack` (resource name, default `afterduty.verify.v1.yaml`).

## 8. Mapper

`EngineIssueMapper.map(EngineAssessment, scenario)` returns the app's issue shape,
`{condition_name, issue_type, description, suggested_fix}`, extended with `source: "engine"`,
`rule_id`, `citation`, `condition_key`, `engine_claim_id`. Only leaf dispositions map.

| Leaf `type_ref` | Value | `issue_type` |
|---|---|---|
| `code_valid` | REFUTED | `invalid_code` |
| `rating_in_schedule` | REFUTED | `rating_mismatch` |
| `service_connection_supported` | PARTIAL or UNEVALUATED | `insufficient_evidence` (description names the unsupported elements from the scenario's `triad_finding` records) |
| `presumptive_basis_valid` | REFUTED | `insufficient_evidence` (description names the correction id) |
| `not_pyramided` | REFUTED | `pyramiding` (one issue per member; each names its rated peer or the duplicate-code count) |

The mapper reads `derivation.rule_id` for `rule_id`, `derivation.unmet[].requirement_id` and
`derivation.refutation_checks[]` for the description's cause, and the scenario's own finding records
for the raw values. `description` is generated from those raw values. `suggested_fix` comes from one function,
`EngineIssueMapper.suggestedFix(issueType, finding)`, **reserved for the owner to write**: it is the
wording a veteran would eventually read, and it is VA judgment rather than engineering.

## 9. Ledger

Entity `AssuranceAssessment`, table `assurance_assessments`, migration
`V20260907_1__assurance_assessments.sql` kept in lockstep with the entity per the repo's convention.

| Column | Type | Notes |
|---|---|---|
| `id` | BIGSERIAL | |
| `claim_id`, `user_id` | BIGINT NOT NULL | indexes on `user_id` and on (`claim_id`, `created_at`) |
| `generation` | VARCHAR(64) | the generation marker the conditions belonged to |
| `mode` | VARCHAR(16) NOT NULL | `shadow` |
| `status` | VARCHAR(24) NOT NULL | `ok`, `engine_error`, `serializer_error`, `skipped` |
| `error_summary` | VARCHAR(500) | exception class plus the engine's error string; never input |
| `pack_id`, `pack_version`, `pack_sha256` | VARCHAR | identity of the pack text sent |
| `engine_sha`, `engine_version` | VARCHAR | configured pin; version reported by the engine |
| `scenario_sha256`, `scenario_json` | VARCHAR(64), TEXT | the exact request |
| `root_disposition` | VARCHAR(16) | |
| `result_json` | TEXT | normalized dispositions, ledger head, summary counts |
| `engine_issues_json`, `llm_issues_json` | TEXT | both verifiers, same shape |
| `llm_job_id` | UUID | the `synthesis_verify` job |
| `duration_ms` | INTEGER | |
| `created_at` | TIMESTAMPTZ NOT NULL | |

Retention is account lifetime (D4). `UserDeletionService.deleteAllUserData` deletes these rows by
`user_id` before claims, in the existing FK-safe order. Nothing reads the table in v0 except the
pilot and reporting.

## 10. Hook

- `SynthesisVerifiedEvent(Long claimId, Long userId, List<Long> conditionIds,
  List<Map<String,Object>> llmIssues, UUID llmJobId, String synthesisModel, String generation)`,
  published with `ApplicationEventPublisher` from `doCompleteIfReady` after the generation flip, in
  the same transaction, so it is delivered only if the flip commits.
- `AssuranceShadowListener`: `@TransactionalEventListener(phase = AFTER_COMMIT)` and
  `@Async("assuranceExecutor")`. Returns immediately when `enabled` is false. Otherwise calls
  `AssuranceShadowService.assess`, which catches everything and writes a row with a status.
- `assuranceExecutor`: a dedicated `ThreadPoolTaskExecutor`, two threads, bounded queue, so a slow
  engine can never starve the pipeline's executor. No retries in v0.

## 11. Pilot and scoreboard

- Gradle task `evalAssurance` running tests tagged `eval-assurance`; requires `-PassuranceEval`,
  `GOLDEN_PRIVATE_ROOT` and `ASSURANCE_ENGINE_HOME`.
- `AssuranceShadowPilotTest` preconditions: `GoldenCaseLoader.privateRoot()` is set and `gc-100`
  is among the loaded cases, otherwise fail with a message naming `GOLDEN_PRIVATE_ROOT`. This is
  the "fail loudly at 24" rule.
- Per case and phase: seed canned evidence, `PipelineDriver.runPhase`, take
  `PipelineEndState.activeConditions`; LLM issues by `SynthesisVerificationAgent.parseResponse`
  over the canned `synthesis_verify` text; engine issues by serializer, CLI client, mapper.
- **New optional expectation field:** `PhaseExpectation.expectedIssues` (`expected_issues`), a list of
  `ExpectedIssue(condition_pattern, issue_type, must_be_flagged, note)`. Existing cases are unchanged.
  gc-100's private `expected.json` receives its known issues (their number and types live only in
  the private tier); synthetic cases receive expected issues where the canned data warrants them,
  authored in the plan.
- **Scoreboard** per case and in total, for each verifier: `catches` (expected and flagged), `misses`
  (expected and not flagged), `unexpected` (flagged and not expected, listed for review), plus
  `agreement` (flagged by both). The harness cannot tell a false alarm from a new find; the owner
  classifies the unexpected list, and the gc-100 gate is zero unexplained unexpected findings plus
  the pyramiding and insufficient-evidence expectations caught. No percentage is a gate.
- **Reports:** `docs/qa/evals/runs/assurance-pilot-<runId>/report.json`, `report.md`, `scores.csv`
  (same conventions as `EvalReportWriter`), with synthetic cases in full. gc-100's detail is written
  only under `$GOLDEN_PRIVATE_ROOT/snapshots/assurance-pilot-<runId>/`; the committed report carries
  its counts only. A GSN SVG per case from `assurance export --format gsn --svg`, tiered the same way.
- The engine's own Stage E result on gc-100 is noted as a sanity baseline, never a target.

## 12. Testing strategy

- Unit: every reduction rule; serializer determinism (same input, same bytes after the timestamp
  sentinel); mapper table; `PackSafetyTest`; both client adapters against fixture exports.
- Engine gates: `validate`, `lint`, `test-pack` through `assurancePackGates`.
- Golden fixtures: for three committed synthetic cases, a committed expected-dispositions JSON
  produced by the CLI; a test asserts equality, guarding serializer and pack drift together (tagged
  `eval-assurance`).
- Contract: CLI and HTTP adapters agree on one scenario (tagged `eval-assurance`).
- Regression: the existing `check` suite is unchanged with the flag off; one test proves the
  listener is a no-op when disabled.
- Pilot: section 11.

## 13. Local setup

`ASSURANCE_ENGINE_HOME=~/Developer/phronesis/assurance-engine`, checked out at `1b94993` or later,
with `.venv` created by `pip install --require-hashes -r requirements-core.lock` plus `-e .` for the
CLI. `GOLDEN_PRIVATE_ROOT=~/Developer/phronesis/assurance-engine/private/goldencase/afterduty`.
Neither path is ever committed.

## 14. Risks and how each is closed

| Risk | Closure |
|---|---|
| HTTP `export` projection drifts from the CLI export in a future engine version | contract test in section 7; today they share the same arrays |
| A model-derived record with a blank provenance key rejects the whole scenario | serializer refuses to emit it and the ledger row records `serializer_error`; unit test per key |
| `matches` is a full match | patterns written `(?is).*X.*`; packtest covers a hit and a miss |
| Pyramiding reduction drifts from `PyramidingRules` edge cases | serializer tests mirror its null-id and same-code cases |
| First container build is unproven | D6, owner runbook, Cloud Build proves it |
| Engine slow or down in production | async, dedicated executor, timeout, status row, no retries |

## 15. Deferred

Per-code generator keyed on `VasrdRecord.asOfDate` through `KbRefreshJob.changedSections`; page
locators in extraction; CI wiring for gradle; the deploy-identity split from the owner runbook;
internal ingress; any UI; token rotation as a coordinated redeploy per the engine's `SERVICE.md`.
