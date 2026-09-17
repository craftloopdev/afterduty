# Assurance Engine integration — decisions memo (item 1)

**Status: APPROVED 2026-09-06.** the owner read D1–D6 in the engine session and approved all six as
written, with one correction — the engine SHA to pin in D6. Delivered here as two engine-authored
file drops, `assurance-engine-GO-2026-09-06.md` and `assurance-engine-cloudrun-readiness-2026-09-06.md`
(this directory). Nothing below is built yet. This memo was the instrument the engine session asked
for: the app-side decisions stated explicitly so they could be accepted, amended or rejected *before*
a spec is written.

**Context:** `docs/architecture/assurance-engine-integration.md` (tasking + all five clarifications
answered). Engine pinned at **`1b94993`** or later, `github.com/craftloopdev/assurance-engine` — corrected
2026-09-06 from `380f4a7`, which predates endpoint authentication (see D6).

**Reading order for a reviewer:** D1 and D2 are the load-bearing ones. D3–D6 follow from them.

---

## What this integration actually changes

**The engine is a VERIFIER, not a rater.** *(Reframed 2026-09-05 at the owner's direction — an earlier
draft posed this as "which conditions does the engine rate," which created a false choice between
verifiability and decidability and left no clear path to ship. That framing is abandoned; the
reasoning is kept here so it is not re-adopted.)*

The app already runs a verification stage: `SynthesisVerificationAgent` is an LLM checking the LLM's
own output, with a settled contract — take proposed conditions, return issues. Its system prompt
checks five things:

| # | Check | Rule-decidable? |
|---|---|---|
| 1 | Is the VASRD code valid and correct? | Partly — validity yes, correctness needs reading |
| 2 | Does the rating match documented severity? | **Yes** where criteria are numeric |
| 3 | Sufficient evidence for each triad element? | **Yes** — this is exactly what pack sufficiency rules model |
| 4 | Missing secondary conditions? | No — requires reading the records |
| 5 | Pyramiding? | **Yes** — a cross-condition rule |

The engine drops into that slot as a **second, deterministic verifier**. It answers 2, 3 and 5 with a
derivation and a 38 CFR citation, and stays silent on 1 and 4.

**Why this satisfies all three requirements at once.**

*The product bar is never at risk.* The LLM still identifies conditions and proposes ratings, so the
product never drops below what ships today — which already beats a veteran with little experience of
VA rules. The engine is **strictly additive**: it can only add a caught error. There is no state in
which enabling it makes a veteran worse off.

*Decidability stops being a selection problem.* The engine speaks where rules decide and is silent
elsewhere, and silence costs nothing because the LLM verifier still covers the judgment calls.
Abstention is not a hole in the product — it is the engine declining to duplicate work the LLM does
better.

*Verifiability is already in hand.* gc-100's seven known issues — **2 rating_mismatch, 3 pyramiding,
2 insufficient_evidence** — are real adjudicated ground truth, and every one of them falls inside
checks 2, 3 and 5. The decidable subset is precisely where the real errors were.

**What this does to coverage.** Service connection is the **same triad for every condition** (current
diagnosis, in-service event, nexus), so sufficiency rules are cross-cutting, not per-diagnostic-code.
Pyramiding is inherently cross-condition. Those two checks reach **all sixteen** of gc-100's
conditions on day one. Only rating-percentage criteria are per-DC, and those are added incrementally.
The earlier draft's "narrow pack covers 2 of 16 conditions" problem was an artifact of the rater
framing and does not exist here.

The deeper point stands and is worth keeping: today's gap and verification stages are **LLM calls**
(`EvidenceGapAnalyzer`, `SynthesisVerificationAgent`, `GapValidationAgent` — all
`LlmJobRequest`-driven). Replacing *judgment* with *derivation* is the value, because a derivation can
be shown to a veteran and cited. The risk is unchanged: a wrong rule is wrong identically every time.
Running as a verifier bounds that risk — a wrong rule produces a false alarm on a screen the LLM has
already filled in, not a wrong rating in place of a right one.


## D1. The seam is the verification stage. The rating path is untouched.

**Decision.** The engine is wired in as a **second verifier alongside `SynthesisVerificationAgent`**,
consuming the same input that stage already takes (proposed conditions + ratings + evidence) and
returning the same shape (a list of issues). Nothing on the rating path changes:
`ClaudeSynthesisService` still proposes ratings, and `ConditionController` / `RatingController` still
call `VaMathService` directly for live estimation.

**Why.** Verification is a rule-checking job; identification and rating from messy records is a
reading job. Putting the engine where the app already checks its own work means we add determinism
exactly where determinism is the right tool, and we change nothing where judgment is.

It also means the integration is reversible and low-blast-radius: the engine's output is one more
issue list, not a replacement for a number a veteran is looking at.

**What it commits us to.** Two verifiers run over the same input and **can disagree**. That is the
pilot's entire signal (D5), not a defect. The live estimator keeps its own arithmetic —
`VaMathService` already implements §4.25 combined ratings, §4.26 bilateral factor and rounding — so
the engine is not needed to make the headline number correct.

**Rejected:** replacing `ClaudeSynthesisService`'s rating proposals with engine ratings in v0 (would
gate shipping on per-DC pack coverage, and could regress conditions the pack does not cover);
routing the two controller paths through the engine (network hop inside an interactive path, no
user-visible gain).


## D2. `run-record/1` is not our contract. We emit engine scenario JSON.

**Decision.** The Java serializer emits **engine scenario JSON** consumed by `POST /v1/assess`:
`subject`, `config`, `evidence`, `adjudications`, `ontology`. The `conform` gate named in the
original tasking is for instrumented-harness telemetry and is **not our v0 gate**.

**Why.** The tasking said "contract seam: run-record/1." That was wrong, and it is worth recording
*why* so it doesn't get re-adopted: `run-record/1` describes harness-emitted **test runs** — `sut`,
`determinism`, `outcome_kind`, `harness`. A veteran's claim is not a test run. Stage E-2 produced its
45/45 dispositions through scenarios, never run-records. Verified against
`validation/bva/cases/gc-101-*.json`, whose top-level keys are `subject`/`config`/`evidence`/
`adjudications`/`ontology`/`gold`/`bva_meta`.

**What it commits us to.** Our mapping is: conditions → claims, evidence atoms → typed evidence
records (payload + provenance, per the pack's declared `evidence_types`), rating decisions →
adjudications. Five **public** BVA scenarios built from real published Board decisions are the
worked reference (`validation/bva/cases/`), plus 12 synthetic ones in `validation/goldset/cases/`.

## D3. Packs regenerate on eCFR *change*, not on a schedule.

**Decision.** Pack regeneration hooks the existing KB-refresh **diff**, not a timer, and packs are
versioned by `VasrdRecord.as_of_date`.

**Why.** The machinery already exists and is the right shape. `KbRefreshJob` runs nightly
(`0 30 9 * * *` UTC) but does **not** re-ingest blindly — `changedSections(part, watermark)` returns
only sections whose eCFR content moved, the watermark advances per ingested section, and a failed
section retries the next night without aborting the run. Regenerating a pack nightly when 38 CFR
Part 4 changes a few times a year would churn pack identity — and therefore assessment provenance —
for nothing.

Note the naming trap recorded earlier: **two** entities carry `as_of_date`. `VasrdRecord.asOfDate`
(the eCFR issue date of the ingest, `NOT NULL`) is the versioning key. `Chunk.asOfDate` is a
different thing. The generator reads the **DB**, not eCFR — item 2's "eCFR-driven" label is
misleading and should be retired.

**What it commits us to.** A pack is a pure function of (VASRD content at an `as_of_date`, generator
version). Assessments record which pack version produced them, so a rating can be explained against
the regulation text as it stood that day. Generation is gated by `assurance validate` / `lint` /
`test-pack` against `ontologies/bva.rating.v1.yaml` as the exemplar. Engine-imposed limits are
binding: **≤256 KB pack text, nesting depth ≤64, ≤200k YAML events, no YAML anchors or aliases.**

**First pack scope: by CHECK, not by condition.**

Because the engine verifies rather than rates, scope is chosen by *which check* it can answer, and
the two highest-value checks are **cross-cutting** — they apply to every condition at once:

| Order | What to encode | Reach | Why first |
|---|---|---|---|
| 1 | **Triad sufficiency** — current diagnosis, in-service event, nexus | **All conditions** | The triad is identical for every DC. This is what `ontologies/bva.rating.v1.yaml` already models as service-connection sufficiency rules. Covers 2 of gc-100's 7 known issues (`insufficient_evidence`). |
| 2 | **Pyramiding** (§4.14) | **All conditions** | Inherently cross-condition. Covers 3 of gc-100's 7 known issues — the largest single bucket. Existing `PyramidingRules` / `PyramidingGroups` give us a trusted baseline to diff against. |
| 3 | Per-DC rating criteria, decidable ones first — 6260 (flat 10% cap), 6100 (Table VI/VII lookup) | Per condition | Covers the remaining `rating_mismatch` issues. Both codes appear in gc-100, so both get real-ground-truth coverage. |
| 4 | Musculoskeletal ROM thresholds (5237/5257/5260/5201) | Per condition | Numeric thresholds, but "painful motion" is judgment — expect partial answers and abstention. |
| — | **Deferred:** mental-health codes (9411/9434/9400) | — | 38 CFR 4.130's General Rating Formula rates by descriptive impairment levels. Not encodable, and the LLM handles it well. The engine should stay silent here indefinitely, not "eventually". |

Steps 1 and 2 reach **all sixteen** of gc-100's conditions and address **5 of its 7 known issues**
before any per-DC work happens at all. That is the answer to "where do we start" — not a narrow
slice of conditions, but the rules that apply to all of them.


## D4. The ledger persists in Postgres, alongside the existing audit log.

**Decision.** Engine assessment records persist as a **Postgres table in the existing Cloud SQL
instance**, following the `AuthAuditLog` pattern. Not ephemeral, not GCS, not AlloyDB.

**Why.** The audit trail is *user-facing* — the point is that a veteran, or their VSO, can see why
the engine concluded what it concluded, months later, against the regulation as it stood then.
Ephemeral fails that outright. GCS is wrong for something queried per-claim per-condition. AlloyDB is
a migration this decision does not justify on its own. `AuthAuditLog` already establishes the pattern
and the retention/deletion story that the account-deletion path (`DELETE /api/auth/account`) must
extend to cover.

**What it commits us to.** Assessment records are **PHI-adjacent** and inherit every existing
obligation: encryption in transit, deletion on account deletion, no export to any surface outside the
trust boundary, and inclusion in the Data Safety declarations already filed on both stores.

**Retention: account lifetime.** *(Corrected 2026-09-01 — an earlier draft of this memo recommended
"deleted with the claim." That was wrong on two counts, and the correction is recorded rather than
quietly swapped.)*

First, **there is no per-claim delete.** The only deletion endpoints are `DELETE /api/auth/account`
and `DELETE /evidence/{evidenceId}`; `ClaimController` has no `@DeleteMapping`. "Deleted with the
claim" describes a lifecycle event this app does not have.

Second, the **published privacy policy already sets the standard**: *"We keep your evidence and
derived analyses as long as your account exists."* An engine assessment is a derived analysis. Tying
it to a shorter lifetime would have contradicted a promise already shipped in `privacy.html` and
mirrored in both stores' data-safety declarations.

So: assessments live for the life of the account, and `UserDeletionService.deleteAllUserData` must be
extended to cover the new table in FK-safe order — the same treatment evidence rows already get.
This also happens to be the right product answer: a VA claim with an HLR runs for years, and the
audit trail's whole value is that it is still there when the veteran needs to explain a rating from
eighteen months ago.

## D5. The pilot compares two verifiers on the same input, and is allowed to fail.

**Decision.** Shadow-mode, **app-side**, engine invoked by CLI at a pinned SHA over a local path (no
service needed). For each golden case, feed **the same input** to both `SynthesisVerificationAgent`
and the engine, diff the two issue lists, and commit a report. **Ships nothing to users.**

**Why this comparison and not another.** "Engine rating vs LLM rating" is not a well-formed
comparison — the engine abstains by design, so the two are often answering different questions.
"Engine issues vs LLM issues, same input" is well-formed: both verifiers have the identical job, the
identical input, and gc-100 has a **known correct answer** — 7 issues, being 2 `rating_mismatch`,
3 `pyramiding`, 2 `insufficient_evidence`.

So the pilot has a real scoreboard, on real adjudicated data:

- **Catches** — issues the engine finds that are genuinely present. Against gc-100 these are countable
  against the known 7.
- **Misses** — known issues the engine fails to raise. Expected early, where the pack is thin.
- **False alarms** — issues the engine raises that are not real. **The metric that matters most**, and
  the one that decides whether this can ever face a veteran. A verifier that cries wolf is worse than
  no verifier.
- **New finds** — issues the engine raises that the LLM missed and that turn out to be real. The
  strongest possible result, and the entire argument for the integration.

**Binding, from our own earlier finding:** the pilot **must assert the private tier loaded and fail
loudly at 24 cases.** The committed classpath tier holds exactly 24 synthetic cases
(`gc-001`…`gc-024`); `gc-100` — the only case with real adjudicated ground truth — loads solely under
`GOLDEN_PRIVATE_ROOT`. A silent 24-case run drops the one case the scoreboard depends on.


### Where it runs, and why the UI does not change

**Zero UI impact in v0, by construction.** The pilot never touches live user data and ships nothing
to production. It belongs beside the **eval harness that already exists** —
`spring-backend/src/test/java/com/afterduty/eval/` (`EvalRunLoop`, `DeterministicScorer`,
`EvalReportWriter`, `EndStateDigest`, `EvalSpendGuard`), every piece already carrying
`@Tag("regression")` so it stays out of the normal test run. The pilot is a sibling: load the corpus
through `GoldenCaseLoader` (private tier asserted), serialize each case to scenario JSON, call the
engine CLI at the pinned SHA, and write the comparison through `EvalReportWriter`. Nothing in
`web/` changes; no endpoint is added; no user sees anything.

That is the honest answer to "how does it affect the UI": **it does not, and it must not.** A shadow
pilot that leaked into the UI would stop being a shadow pilot.

### The success criterion

**Proposed, needs the owner.** Two gates, both judgment calls, deliberately not percentages:

1. **Zero unexplained false alarms on gc-100.** Every issue the engine raises that is not in the known
   7 must have a written explanation, and none of those explanations may be "the pack is wrong in a
   way we can't fix."
2. **The engine catches the pyramiding and insufficient-evidence issues** — the 5 of 7 that steps 1
   and 2 of the pack scope are built to reach. Missing a `rating_mismatch` is acceptable in v0 if the
   relevant DC is not yet encoded; missing a pyramiding issue is not, because that rule is supposed to
   be complete.

It is deliberately not a percentage. We author the pack, so any threshold is one we can hit by editing
the pack until it agrees — fitting to the answer key. With n=1 on real ground truth, a percentage is
decoration, but "did it catch the 5 it was built to catch, without inventing others" is a real
question with a real answer.


### The UI question, and why the verifier framing shrinks it

Under the rater framing this was a hard problem: a rule engine abstains, today's UI always shows a
number, and there was no screen state for "cannot be determined." **The verifier framing removes
that**, because the LLM still produces every number. The engine never leaves a blank.

What remains is a much easier question, and it is upside rather than risk: **how do we surface a
caught issue with its citation?** The app already renders verification issues from
`SynthesisVerificationAgent`, so there is an existing surface. The engine's issues arrive in the same
shape with something extra — a derivation and a 38 CFR cite — so the eventual UI work is
*enriching an existing element*, not inventing a new state.

Still no v0 UI change: shadow mode shows nothing. But the eventual change is additive, which is why
the false-alarm metric in D5 is the one that gates ever showing it. An issue shown to a veteran with
a regulation citation carries authority, and that authority is exactly why a wrong one would be
worse than saying nothing.


## D6. Deployment: our pipeline, our registry, pinned SHA.

**Decision.** We build the engine image from a pinned engine git SHA using the engine's committed
`Dockerfile` at that SHA, push to **Artifact Registry in `craftloop-va-claim`**, and deploy as a
separate Cloud Run service in that project. **Pin `1b94993` or later.**

**Pin correction (2026-09-06, from the engine session; load-bearing).** The first draft said
`380f4a7` or later. That commit **predates endpoint authentication** — pinning it exactly gives a
service with no token gate, the opposite of what our 1 September request asked for. Authentication
landed on the engine's origin at `3daf52a`; `1b94993` adds the two container fixes below. Verified
here on 2026-09-07: the ancestry is `380f4a7` → `3daf52a` → `1b94993`, and `1b94993` is on
`origin/main` of `github.com/craftloopdev/assurance-engine`.

**What `1b94993` carries, all three binding on us:**
- the shared-token gate, **fail-closed at startup** — the container exits 2 before binding a socket
  unless `ASSURANCE_SERVICE_TOKEN` is set (16+ printable-ASCII characters, no spaces). A deploy that
  forgets the secret crash-loops loudly instead of serving open. That is the design, not a bug to
  work around;
- the Dockerfile installs the core profile with `pip install --require-hashes -r requirements-core.lock`
  (it previously did a bare `pip install "PyYAML>=6.0"`, which would have led our mirrored build
  into an unpinned install in a PHI-bearing service);
- `service/ui/` excluded from the image (the console is opt-in and always off for PHI deployments).

**Why.** `craftloop-va-claim` is the trust boundary — the same project already holds the API, the web
BFF, Cloud SQL and the documents bucket, and it is the project covered by the GCP BAA that made the
PHI routing lawful. An engine processing evidence has to sit inside it. The engine repo publishes no
registry image pre-award, so the build is ours either way.

**Two access-control layers, the platform's primary.** Cloud Run IAM (`--no-allow-unauthenticated`,
`roles/run.invoker` on this one service granted to the API's runtime service account only) plus the
in-process shared token, so that one misapplied deploy flag is a loud failure rather than a silent
total one. Our caller sends **both** headers: the Google identity token in `Authorization` (the
platform checks it) and the shared token in `X-Assurance-Service-Token` (the engine checks it).
*(The first draft's "authless by design" paragraph is superseded — that was the pre-`3daf52a`
posture.)*

**Deploy order, binding.** Cloud Run validates secret access at deploy time, so the order is fixed:

1. add the token as a Secret Manager version (mode-600 file, no trailing newline);
2. grant `roles/secretmanager.secretAccessor` on that secret to the engine's runtime service
   account — a new, dedicated SA, not the API's and not the default compute SA;
3. deploy with `--no-allow-unauthenticated --service-account <engine-sa>
   --set-secrets ASSURANCE_SERVICE_TOKEN=<secret>:<version>` — a **version, never `latest`**, or a
   scale-out instance could start on a token the caller is not yet presenting;
4. grant `roles/run.invoker` on the service to the API's runtime SA only;
5. post-deploy check: `GET /healthz` reports `"auth": "token"`;
6. set Cloud Run's per-request timeout and per-instance concurrency deliberately — the wrapper has
   no internal wall-clock budget for CPU-bound work already in flight.

**Ingress — the one part of the readiness note that is real work on our side.** The engine's
deployment invariant asks for `--ingress internal`, and internal ingress only admits a caller whose
traffic actually arrives through the VPC. As of 2026-09-07 `va-claim-api` has **no VPC egress at
all** (no connector, no Direct VPC egress; the Serverless VPC Access API is not enabled in the
project). Meeting the invariant means Direct VPC egress on the API with `--vpc-egress all-traffic`,
Private Google Access on the subnet, and Cloud NAT for the API's non-Google egress (Stripe,
SendGrid, RevenueCat, eCFR) — a production networking change with its own blast radius, and one that
needs owner-level IAM (the credentials available to this session cannot list networks or subnets).
Recommendation: **spec it as two steps** — v0 deploys with IAM + shared token and the caller proven
with one request; internal ingress follows as its own change with its own rollback, not folded into
the first engine deploy. The engine's `SERVICE.md` calls internal ingress binding for PHI-bearing
use, so whether v0 may run without it is the owner's call, stated here so it is not decided by default.

**Decided 2026-09-07 (the owner):** v0 deploys with IAM plus the shared token; the caller is proven with
one request; internal ingress lands afterwards as its own change with its own rollback. The engine
service is After Duty's private instance — its only invoker is the API's runtime service account,
and nothing else inside or outside the project is granted `run.invoker` on it.

**Risk carried, stated plainly.** No container has ever been built from this Dockerfile — the engine
machine has no container runtime, and neither does this one (`docker`, `podman` and `nerdctl` are
all absent). The image is proven only in a container-equivalent environment. Our pipeline is
therefore **Cloud Build** (`gcloud builds submit` from the engine checkout at the pinned SHA; the
project has built this way before), and the first build is where the Dockerfile gets proven. The
hardened image (non-root, read-only root filesystem) is a deferred engine task we do not inherit.

**Audit item.** Project-level roles that carry `run.routes.invoke` (Cloud Run Admin, Developer,
Source Developer, Services Invoker, and basic Editor/Owner) can call the service with no
service-level binding. Keep those to human operators, never to workloads. No credential available
to this session can read the project IAM policy, so this audit is the owner's.

**Also noted, out of scope here:** the permission test surfaced least-privilege findings about
existing identities and secret handling. They are recorded owner-side, outside this repository, and
none blocks v0.

---

## What this memo does not decide

- **Whether engine-found issues are ever shown to veterans.** v0 is shadow-mode only. That decision
  needs the pilot's false-alarm rate, and it is the owner's. Note this is a *much smaller* decision than
  the one the earlier draft posed: the engine adds issues to an existing surface rather than
  replacing the rating a veteran sees, so it can be enabled per-check rather than all at once.
- **Whether the engine ever replaces `SynthesisVerificationAgent`.** Not in scope. Running both is
  the safer end state — an LLM verifier catches what rules cannot express, and vice versa.
- **HLR modeling.** Deferred; it is also the trigger for the engine's E1 scope-key work, so adopting
  it is a cross-repo decision, not ours alone.
- **Item 6 (site wording).** The marketing-site session owns it, gated on this shipping *and* the owner
  approving copy. `/process` currently says After Duty "doesn't run on that engine" — true today,
  false the day this ships, and it must not be changed before both gates are met.

## Sequencing, once signed off

1. **D2 serializer** — build against the five public BVA scenarios, which are the worked reference.
2. **D3 pack, steps 1-2 only** — triad sufficiency and pyramiding. Cross-cutting, so this alone
   reaches every condition and 5 of gc-100's 7 known issues.
3. **D5 pilot** — needs both of the above. Run it before writing any per-DC rating criteria: the
   false-alarm rate on cross-cutting rules is the go/no-go for the whole approach, and it is cheaper
   to learn it now.
4. **D3 steps 3-4** — per-DC criteria, decidable codes first, only if step 3 came back clean.

D6 is independent and can run in parallel from the start. D4 lands with whatever first writes an
assessment. Item 6 (site wording) stays with the site repo, last.


## Sign-off

| # | Decision | Status |
|---|---|---|
| D1 | Engine is a **verifier** beside `SynthesisVerificationAgent`; rating path untouched | ☑ approved 2026-09-06 |
| D2 | Scenario JSON, not `run-record/1` | ☑ approved 2026-09-06 |
| D3 | Packs regenerate on eCFR change; scope by **check**, cross-cutting rules first | ☑ approved 2026-09-06 |
| D4 | Ledger in Postgres beside `AuthAuditLog`, account-lifetime retention | ☑ approved 2026-09-06 |
| D5 | Two-verifier shadow pilot; scoreboard = catches / misses / **false alarms** / new finds | ☑ approved 2026-09-06 |
| D6 | Our pipeline → Artifact Registry in `craftloop-va-claim`, pinned SHA, never `allUsers` | ☑ approved 2026-09-06 — pin corrected to `1b94993` |

**D1 and D3 changed materially on 2026-09-05** — the engine moved from rater to verifier, and pack
scope from "which conditions" to "which checks." That change is what makes verifiability,
decidability and the product bar compatible instead of competing, so it is worth reading D1 and the
framing section before the rest.

**Approved as written on 2026-09-06** (the owner, in the engine session; relayed by file drop). D5's two
gates stand as the success criterion; D3 scope and D4 retention stand as recommended. D6's ingress
sequencing was decided 2026-09-07: v0 first with IAM plus the shared token, internal ingress as a
follow-on change with its own rollback.
