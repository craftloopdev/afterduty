# Engine session → app session: D1 through D6 are signed off. Begin work.

**From:** the assurance-engine session, 2026-09-06.
**Authority:** the owner, this date, in the engine session. He read the six decisions and settled them.

## The decision

**D1 through D6 as written in your memo are approved. Start building.** No caveats on the
decisions themselves. The only correction is to D6's pin, below, and it is about which commit to
pin, not about the deployment decision.

That includes the reframe you made after the first draft: the engine goes in as a **second verifier
beside `SynthesisVerificationAgent`**, not as a rater, and the rating path is untouched. Your
sequencing holds: memo first, then the brainstorm and spec with the owner, then the pack generator, the
serializer, the pilot, and the deploy.

## The one correction, and it is load-bearing

D6 says pin `380f4a7` or later. **That commit predates endpoint authentication.** Pinning it exactly
gives you a service with no token gate, which is the opposite of what your 1 September REQUEST asked
for.

**Pin `1b94993` or later.** Engine `origin/main` is there now. That commit carries all three of:
- the shared-token gate with fail-closed startup (your REQUEST, option (a) plus (c)),
- the Dockerfile installing the core profile with `--require-hashes -r requirements-core.lock`,
- `service/ui/` excluded from the image.

Consequence you must design for: **the container refuses to start without
`ASSURANCE_SERVICE_TOKEN`.** Exit code 2, before the socket binds. That is deliberate. A deploy that
forgets the secret crash-loops loudly rather than serving open.

## What is proven, and what is not

Proven in a container-equivalent environment (the post-`.dockerignore` file set, a fresh virtualenv
with only the hash-installed dependency, engine package never installed): no secret exits 2; with
the secret, `/healthz` reports `"auth": "token"`, an unauthenticated `POST /v1/assess` is 401, an
authenticated one returns the full assessment, and `GET /` is 404 because the console is off.

**Not proven: no container has ever been built.** The engine machine has no container runtime. The
hardened image (non-root, read-only root filesystem) is a deferred engine task. Carry that as a risk
in your deploy plan and prove it in your own pipeline, which is where the build happens anyway.

## Everything else you need

`assurance-engine-cloudrun-readiness-2026-09-06.md`, beside this file: the ordered deploy commands
(secret access must be granted BEFORE the deploy, Cloud Run validates it at deploy time), the two
headers your caller sends, the internal-ingress requirement that actually bites
(`--vpc-egress all-traffic` plus Private Google Access), the post-deploy check, and the project-level
roles worth auditing because they carry `run.routes.invoke` with no service-level binding.

Contract details: engine `docs/SERVICE.md`. Worked non-PHI examples: `validation/bva/cases/gc-10*.json`.
Pack exemplar: `ontologies/bva.rating.v1.yaml`. Authoring guide: `docs/AUTHORING.md`.

New since your memo: `POST /v1/assess` takes an optional `include` array (`export`, `gsn`,
`worklist`, `ledger_export`, `annex`, `coverage_after`), each adding one response key, with `_after`
twins when the scenario declares changes. An absent `include` returns the previous body byte for
byte, so nothing you already consume changes.

Report engine-side defects on the engine board row or by file. Never work around them silently.
