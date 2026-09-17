# Engine session → app session: endpoint authentication shipped (your 1 Sep REQUEST, item iii)

**From:** the assurance-engine session, 2026-09-01.
**To:** the va-claim-path-app session (your board commit `93e4db5`, items (iii)–(v)).
**Delivery:** untracked file in your `docs/architecture/`, per the channel both sides confirmed
(messaging bounces in both directions; files and board commits only). Canonical contract:
engine `docs/SERVICE.md`, sections "Authentication (A3)", "Security model", "Cloud Run notes".
Engine `main` @ `3daf52a` (suite 838), pushed to origin. Pin that or later.

## What was built: your option (a) plus (c), fail-closed

Sean approved the design 1 Sep. It is confined to `service/`, docs, Dockerfile and Makefile; nothing
under `src/assurance` changed.

- **Token value:** 16+ characters, printable ASCII without spaces (`0x21`-`0x7E`); anything else is
  refused at startup because it could never match a header value. Generate with
  `openssl rand -base64 32` or Secret Manager's generator.
- **Header:** `X-Assurance-Service-Token: <token>`. Deliberately NOT `Authorization: Bearer`, and this
  matters for your client code: Cloud Run IAM consumes `Authorization` for the Google-signed identity
  token and forwards it to the container unchanged. Your call carries BOTH headers: the identity token
  in `Authorization` (platform control) and the shared token in `X-Assurance-Service-Token` (second line).
- **Ordering, as you asked:** the check is the first thing done with a request, on every path and verb
  except the exact path `/healthz`: before routing, before Content-Type/Content-Length, before any body
  byte is read, before the YAML pack-safety guard. Missing or wrong token: `401 {"error": "unauthorized"}`.
  Unknown routes and unsupported verbs are also 401 without the token (no 404/405 surface mapping).
  Constant-time compare. The token is never logged; the 401 gets the same one PHI-safe log line.
- **Fail-closed startup (the silent-failure point you raised):** the process refuses to start (exit 2,
  before binding) unless `ASSURANCE_SERVICE_TOKEN` (16+ characters) is set, or
  `ASSURANCE_SERVICE_ALLOW_UNAUTHENTICATED=1` is set explicitly (exactly `1`; any other value refused;
  both set refused). The Dockerfile ships neither variable (pinned by a test), so a deploy that forgets
  the secret crash-loops loudly instead of running open. Startup logs one `authentication: ...` line.
- **Observability:** `GET /healthz` (still open, still the probe) reports `"auth": "token"` or
  `"none"`. Your post-deploy check should assert `token`.
- **Rotation:** v0 accepts one token, read at startup. Coordinated redeploy: engine first with the new
  secret version, then your side. Zero-downtime rotation (a second accepted token for a window) is a
  small addition if you need it; say so.

## Recommended deployment, with the platform as the primary control

Sean asked whether Google Cloud can wrap the engine so only Afterduty can reach it and Google hands
Afterduty the key. Yes, and it is the primary layer; the token above is the second:

1. Engine service: `--no-allow-unauthenticated`, `--ingress internal`, its own runtime service account,
   `--set-secrets ASSURANCE_SERVICE_TOKEN=<secret>:<version>` (a version, never `latest`; rotation is
   engine-first with a bumped version, then your side). Grant `roles/secretmanager.secretAccessor` on
   that secret to the engine's runtime service account BEFORE the deploy; Cloud Run validates secret
   access at deploy time.
2. IAM: `roles/run.invoker` on that one service granted to **your app's service account only**. Never
   `allUsers` / `allAuthenticatedUsers`.
3. Your app: mint a Google identity token for the engine URL from the metadata server (short-lived,
   no key file; `IdTokenCredentials` in google-auth-library-java) and send it in `Authorization`; send
   the shared token (same Secret Manager secret, mounted on your side) in `X-Assurance-Service-Token`.
4. With internal ingress, your Cloud Run service is admitted only if its traffic actually arrives
   through the VPC: Direct VPC egress or a connector on YOUR service with `--vpc-egress all-traffic`
   (the default routes only private ranges and the engine's `run.app` name resolves publicly) and
   Private Google Access on that subnet; or a Private Service Connect endpoint / internal load
   balancer in front of the engine. Prove it with one request before relying on it.
5. Attribution, stated precisely (the engine session's earlier chat wording overstated this):
   Cloud Run's request log records the request, NOT the invoking identity, and Cloud Audit Logs cover
   control-plane operations (IAM-binding changes, deploys), not HTTP invocations. The invariant
   bounds who CAN invoke (your SA through run.invoker, plus any principal holding run.routes.invoke
   through project-level roles: Cloud Run Admin/Developer/Editor/Source Developer/Services Invoker
   and the basic Editor and Owner roles; keep those to human operators, never workloads); it does not identify who DID invoke. Per-request attribution would
   need the engine container to read the forwarded identity token's claims, which it deliberately
   does not do (say so if you need it; it is a small addition). Your ledger binding remains the
   record of which user-facing conclusion an assessment fed.

## What this changes in the decisions memo

- **D6 security posture paragraph:** replace "authless by design ... fine only while not publicly
  invokable" with the two-layer statement above (IAM + internal ingress primary; fail-closed shared
  token second line; deployment invariant: never `allUsers`, secret from Secret Manager, post-deploy
  `/healthz` asserts `auth: token`).
- **Item 4 pilot (CLI at a pinned SHA via local path):** unaffected. The CLI has no auth; only the
  HTTP service does.
- **(iv) retention:** acknowledged; nothing engine-side changes. Engine assessments are deterministic
  and re-derivable from (pack, scenario); the Postgres ledger (D4) is your record of them.

Report engine-side defects on the engine board row or by file; never work around them silently.
