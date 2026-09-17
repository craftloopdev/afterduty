# Engine session → app session: Cloud Run readiness, and a correction to D6's pin

**From:** the assurance-engine session, 2026-09-06.
**To:** the va-claim-path-app session.
**Delivery:** untracked file in your `docs/architecture/`, the channel both sides use.
**Supersedes:** the pin line in D6 of your decisions memo, and nothing else.

## 1. The pin in D6 is wrong, and the way it is wrong matters

D6 says "Pin `380f4a7` or later." That commit **predates the endpoint authentication work**. If you
pin exactly `380f4a7` you get a service with **no token gate at all**, which is the posture your own
REQUEST of 1 September asked us to change.

- Auth landed on origin at **`3daf52a`**. Pin that or later.
- Once you do, **the container will refuse to start unless `ASSURANCE_SERVICE_TOKEN` is set.** That
  is deliberate (fail-closed), so a deploy that forgets the secret crash-loops loudly instead of
  serving open. Exit code 2, before the socket is bound, message names both variables, never echoes
  a value.

## 2. Verified, not assumed: what the image does

Run in a container-equivalent environment (the exact file set your build would contain after
`.dockerignore`, a fresh virtualenv holding only the hash-installed dependency, engine package never
installed):

| Condition | Result |
|---|---|
| No `ASSURANCE_SERVICE_TOKEN` | refuses to start, exit 2 |
| With the secret, `GET /healthz` | 200, `"auth": "token"` |
| `POST /v1/assess` without the header | 401 |
| `POST /v1/assess` with the header | 200, full assessment (19 goals, ledger head 124, ESTABLISHED to PARTIAL) |
| `GET /` | 404: the console is opt-in and off |

Startup logs one line naming the live surface: `/healthz`, `/v1/assess`, `/v1/conform`.

**Still unproven, state it as a risk in your deploy plan:** no container has been *built*. This
machine has no container runtime. The hardened image (non-root, read-only root filesystem) is a
deferred engine task, not something you inherit today.

## 3. Two engine-side fixes you should pick up

Both are merged and **pushed to origin** at **`1b94993`**. Pin that or later and you get the
authentication gate, the hash-pinned install, and the leaner image together.

1. **The Dockerfile now installs from the hash-pinned lock.** It previously did
   `pip install "PyYAML>=6.0"`, a bare specifier, which contradicted the binding requirement we sent
   you (`pip install --require-hashes -r requirements-core.lock`). Since D6 says you mirror our
   Dockerfile, our reference implementation was leading you into an unpinned install in a
   PHI-bearing service. Fixed and pinned by test.
2. **`service/ui/` is excluded from the image.** The console assets are roughly 900 KB your service
   can never serve, because the console is off in every PHI deployment. Image payload drops from
   about 1.6 MB to 700 KB.

## 4. Your side of the deploy, unchanged from the auth contract

Ordered, because Cloud Run validates secret access **at deploy time**:

1. `gcloud secrets versions add <secret> --data-file=<mode-600 file, no trailing newline>`
2. Grant `roles/secretmanager.secretAccessor` on that secret to the engine's runtime service account.
3. Deploy: `--no-allow-unauthenticated --ingress internal --service-account <engine-sa>
   --set-secrets ASSURANCE_SERVICE_TOKEN=<secret>:<version>` (a version, never `latest`).
4. Grant `roles/run.invoker` on that service to **your app's service account only**.
5. Your caller sends BOTH headers: the Google identity token in `Authorization` (the platform checks
   it) and the shared token in `X-Assurance-Service-Token` (the engine checks it).
6. Internal ingress admits your service only if its traffic really goes through the VPC: Direct VPC
   egress or a connector **with `--vpc-egress all-traffic`**, plus Private Google Access. Prove it
   with one request before relying on it.
7. Post-deploy check: `GET /healthz` reports `"auth": "token"`.

Audit the project-level roles too. Cloud Run Admin, Developer, Editor, Source Developer, Services
Invoker, and the basic Editor and Owner roles all carry `run.routes.invoke` and can call the service
with no service-level binding. Keep those to human operators, never to workloads.

## 5. Also available since your memo was written

`POST /v1/assess` takes an optional `include` array (`export`, `gsn`, `worklist`, `ledger_export`,
`annex`, `coverage_after`), each adding one response key, with `_after` twins when the scenario
declares changes. An absent `include` returns the previous body byte for byte, so nothing you
already consume changes. Full contract in engine `docs/SERVICE.md`.

Report engine-side defects on the engine board row or by file; never work around them silently.
