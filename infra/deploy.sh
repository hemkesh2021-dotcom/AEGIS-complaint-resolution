#!/usr/bin/env bash
# Deploy both AEGIS v2 services to Cloud Run. Fill in PROJECT / REGION first.
# Prereqs: gcloud auth login; APIs enabled (run, artifactregistry, cloudbuild).
set -euo pipefail

PROJECT="${PROJECT:-your-gcp-project}"
REGION="${REGION:-asia-south1}"
REPO="${REPO:-aegis}"   # Artifact Registry repo name

gcloud config set project "$PROJECT"

# One-time: create the Artifact Registry repo (ignore error if it exists)
gcloud artifacts repositories create "$REPO" \
  --repository-format=docker --location="$REGION" \
  --description="AEGIS images" 2>/dev/null || true

IMG_BASE="${REGION}-docker.pkg.dev/${PROJECT}/${REPO}"

# --- Classifier (private) ---
gcloud builds submit ./classifier --tag "${IMG_BASE}/classifier:latest"
gcloud run deploy aegis-classifier \
  --image "${IMG_BASE}/classifier:latest" \
  --region "$REGION" --no-allow-unauthenticated \
  --memory 2Gi --cpu 2

CLASSIFIER_URL="$(gcloud run services describe aegis-classifier --region "$REGION" --format='value(status.url)')"

# --- API (public) ---
gcloud builds submit ./api --tag "${IMG_BASE}/api:latest"
gcloud run deploy aegis-api \
  --image "${IMG_BASE}/api:latest" \
  --region "$REGION" --allow-unauthenticated \
  --set-env-vars "CLASSIFIER_URL=${CLASSIFIER_URL}"

echo "Deployed. API: $(gcloud run services describe aegis-api --region "$REGION" --format='value(status.url)')"
