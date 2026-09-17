# Owner runbook — one-time setup for the engine service (D6, v0)

**For:** Sean. **Takes:** about two minutes. **Run as:** an account holding Owner on
`craftloop-va-claim`, or at least Service Account Admin plus Secret Manager Admin. **Why it is you and
not a session:** the deploy identity every session uses
(`1048958573080-compute@developer.gserviceaccount.com`) was tested on 2026-09-07 with
`projects.testIamPermissions`. It can build, push, deploy, redeploy and bind invokers. It deliberately
cannot create service accounts, create secrets, add secret versions, grant access on a secret, or
create a registry repository. That is least privilege working, not a credential failure. Widening the
deploy identity would be the wrong fix, because that identity is also the runtime identity of
`va-claim-web-next` (see the findings at the end).

Nothing here touches DNS, mail, billing, or an existing service. Every step is additive and reversible.

## The five commands

```bash
P=craftloop-va-claim

# 1. A dedicated runtime identity for the engine. Never the API's SA, never the compute SA.
gcloud iam service-accounts create assurance-engine --project "$P" \
  --display-name "Assurance engine runtime (After Duty private instance)"

# 2. The shared token. Generated inline, never displayed, no trailing newline, stdin rather than a
#    file so nothing lands on disk. 48 random bytes -> 64 base64 characters, all printable ASCII
#    without spaces, which is what the engine's startup check requires (16+ such characters).
gcloud secrets create assurance-service-token --project "$P" --replication-policy automatic
openssl rand -base64 48 | tr -d '\n' | \
  gcloud secrets versions add assurance-service-token --project "$P" --data-file=-

# 3. Let the engine's identity read it. Cloud Run validates this at deploy time, so it must exist
#    before the first deploy or the deploy fails.
gcloud secrets add-iam-policy-binding assurance-service-token --project "$P" \
  --member "serviceAccount:assurance-engine@$P.iam.gserviceaccount.com" \
  --role roles/secretmanager.secretAccessor

# 4. Its own registry repository (optional but cleaner; without it the image goes into
#    cloud-run-source-deploy beside the app images).
gcloud artifacts repositories create assurance --project "$P" \
  --repository-format docker --location us-central1

# 5. The audit the engine's readiness note asks for: which principals can invoke Cloud Run services
#    through project-level roles, with no service-level binding at all.
gcloud projects get-iam-policy "$P" --flatten="bindings[].members" \
  --format="table(bindings.role,bindings.members)" | grep -E "roles/(owner|editor|run\.)"
```

The API's runtime identity (`firebase-adminsdk-fbsvc@craftloop-va-claim`) needs no grant on the
secret: it already holds project-level Secret Manager access (that is how `SENDGRID_API_KEY` is
mounted today), so the same secret can be mounted on the API side with a version pin.

## What the sessions do after this, without you

1. Build the engine image with Cloud Build from the engine checkout at the pinned SHA (`1b94993` or
   later), tagged with that SHA, pushed to Artifact Registry in this project. This is also the first
   time the engine's Dockerfile is ever built, so a build failure here is a finding, not a surprise.
2. Deploy `assurance-engine` with `--no-allow-unauthenticated --service-account assurance-engine@…
   --set-secrets ASSURANCE_SERVICE_TOKEN=assurance-service-token:1` (a version, never `latest`),
   ingress `all` for v0 per your 2026-09-07 decision, explicit request timeout and concurrency.
3. Grant `roles/run.invoker` on that one service to the API's runtime identity only.
4. Confirm `GET /healthz` reports `"auth": "token"`, then prove the caller with one authenticated
   request from the API's identity (identity token in `Authorization`, shared token in
   `X-Assurance-Service-Token`).
5. Redeploy `va-claim-api` with the engine URL and the same secret version mounted.
6. Internal ingress comes later as its own change with its own rollback, because it needs Direct
   VPC egress, Private Google Access and Cloud NAT on the API.

## Findings from the permission test

The same permission test surfaced least-privilege findings about existing identities and secret
handling. They are held out of this file on purpose: this repository is slated to go public, and an
unremediated privilege finding is a roadmap. They are recorded for you, owner-only, at
`~/.gcp/afterduty-iam-findings-2026-09-07.md` on the machine the test ran from. None blocks v0; one
should land before the engine leaves shadow mode.

## If you would rather delegate these five to a session

Grant a *new* deploy-only service account (not the compute SA) `roles/iam.serviceAccountCreator`,
`roles/secretmanager.admin` and `roles/artifactregistry.admin`, and drop its key at
`~/.gcp/afterduty-deployer.json`. Sessions would then use it with `--account`. This is more standing
privilege on disk than the five commands above, so the commands are the recommendation.
