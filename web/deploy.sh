#!/usr/bin/env bash
# Deploy the Next.js web BFF to its OWN Cloud Run service, separate from the
# Flutter 'va-claim-web' service and the backend api service. Runs in parallel
# with Flutter — no public-domain cutover.
set -euo pipefail
cd "$(dirname "$0")"

PROJECT="${PROJECT:-craftloop-va-claim}"
REGION="${REGION:-us-central1}"
SERVICE="${SERVICE:-va-claim-web-next}"

# Load public config (Firebase web config + backend URL) from .env.local.
if [ -f .env.local ]; then set -a; . ./.env.local; set +a; fi

: "${SS_API_BASE_URL:?set SS_API_BASE_URL (e.g. https://api.afterduty.app/api)}"
: "${NEXT_PUBLIC_FIREBASE_API_KEY:?set NEXT_PUBLIC_FIREBASE_API_KEY}"

# SA-key auth (never `gcloud auth login`): always deploy as the va-claim deploy SA
# (the active gcloud account may belong to another project).
if [ -f "$HOME/.gcp/default-compute-sa.json" ]; then
  echo "Activating va-claim deploy service account…"
  gcloud auth activate-service-account --key-file="$HOME/.gcp/default-compute-sa.json" --quiet
fi

# The web BFF holds NO secrets (Firebase web config is public; SS_API_BASE_URL is
# a public URL) — unlike the backend, so setting env vars on deploy is safe.
ENV_VARS="SS_API_BASE_URL=${SS_API_BASE_URL}"
ENV_VARS="${ENV_VARS},NEXT_PUBLIC_FIREBASE_API_KEY=${NEXT_PUBLIC_FIREBASE_API_KEY}"
ENV_VARS="${ENV_VARS},NEXT_PUBLIC_FIREBASE_AUTH_DOMAIN=${NEXT_PUBLIC_FIREBASE_AUTH_DOMAIN}"
ENV_VARS="${ENV_VARS},NEXT_PUBLIC_FIREBASE_PROJECT_ID=${NEXT_PUBLIC_FIREBASE_PROJECT_ID}"
ENV_VARS="${ENV_VARS},NEXT_PUBLIC_FIREBASE_STORAGE_BUCKET=${NEXT_PUBLIC_FIREBASE_STORAGE_BUCKET}"
ENV_VARS="${ENV_VARS},NEXT_PUBLIC_FIREBASE_MESSAGING_SENDER_ID=${NEXT_PUBLIC_FIREBASE_MESSAGING_SENDER_ID}"
ENV_VARS="${ENV_VARS},NEXT_PUBLIC_FIREBASE_APP_ID=${NEXT_PUBLIC_FIREBASE_APP_ID}"

echo "Deploying ${SERVICE} to ${PROJECT}/${REGION} (separate from flutter va-claim-web)…"
gcloud run deploy "${SERVICE}" \
  --source . \
  --region "${REGION}" \
  --project "${PROJECT}" \
  --allow-unauthenticated \
  --set-env-vars "${ENV_VARS}" \
  --quiet
