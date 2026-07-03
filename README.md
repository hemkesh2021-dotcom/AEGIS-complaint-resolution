# AEGIS v2 — Polyglot RAG Complaint Pipeline

![CI](https://github.com/hemkesh2021-dotcom/AEGIS-complaint-resolution/actions/workflows/ci.yml/badge.svg)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)

A production-shaped rebuild of AEGIS: a **Spring Boot** orchestrator (Java 21) that calls a **Python** DistilBERT classifier service, applies explainable compliance rules, drafts a reply, decides escalation, and audits every case. Deployable on GCP Cloud Run.

> This is the Weeks 1–3 foundation from `../AEGIS_v2_Roadmap.md`. RAG retrieval + LLM drafting (Weeks 4–5), the Postgres audit store (Week 3), and CI/CD (Week 7) are marked with `TODO`s and ready to slot in. See `../AEGIS_v2_Architecture.md` and `../AEGIS_v2_Prototype_Walkthrough.md` for the full design.

## Layout

```
aegis-v2/
├── api/          Spring Boot orchestrator (Java 21) — the brain
│   └── src/main/java/com/aegis/api/{controller,dto,service}
│   └── src/main/resources/{application.yml,regulations.json,templates.json}
├── classifier/   Python FastAPI service serving DistilBERT (+ heuristic fallback)
├── frontend/     Single-file HTML console (index.html)
├── infra/        deploy.sh — Cloud Run deploy for both services
└── docker-compose.yml   local end-to-end (classifier + api + Postgres/pgvector)
```

## What works today

- `POST /api/complaints` runs the full pipeline: classify → compliance (deadlines + risk) → draft (template) → tasks → escalation → audit (logged).
- The API works **even without the model service** — `ClassifierClient` falls back to a keyword heuristic, so you get a live endpoint on day one.
- The classifier service reuses your **v1 trained model** via the `../models` mount.

## Quickstart (local, one command)

```bash
cd aegis-v2
docker compose up --build
```

- API → http://localhost:8080  · health → http://localhost:8080/health
- Classifier → http://localhost:8000/health  (`model_loaded: true` if the model mounted)
- Frontend → open `frontend/index.html` in a browser (API base defaults to http://localhost:8080)

Test the API directly:

```bash
curl -s http://localhost:8080/api/complaints \
  -H 'Content-Type: application/json' \
  -d '{"text":"There are unauthorized fraud charges on my credit card and I will sue.",
       "customerName":"A. Rao","complaintId":"CMP-1"}' | jq
```

## Enable live LLM drafts (optional)

Retrieval + drafting work without any key (template fallback). To get real NIM-generated, RAG-grounded replies, copy `.env.example` to `.env` and add your (rotated) NIM key:

```bash
cp .env.example .env      # then edit .env and set NIM_API_KEY=nvapi-...
docker compose up -d --build api
```

When a key is present, drafts come back with `"source": "NVIDIA NIM (RAG)"`; otherwise `"Template engine"`. On startup the API ingests `resources/knowledge/kb.json` into pgvector once (local embeddings, no key needed).

## Run services individually

**API** (needs JDK 21 + Maven):
```bash
cd api && ./mvnw spring-boot:run      # or: mvn spring-boot:run
```
Leave `CLASSIFIER_URL` unset to use the heuristic fallback, or point it at the classifier.

**Classifier** (needs Python 3.11):
```bash
cd classifier
pip install -r requirements.txt
MODEL_DIR=../../models/complaint_classifier uvicorn main:app --port 8000
```

## API contract

`POST /api/complaints`
```json
{ "text": "...", "customerName": "A. Rao", "complaintId": "CMP-1", "receivedAt": "2026-06-29T10:14:00" }
```
Returns `{ complaintId, prediction, compliance, draft, tasks, escalation }`. Every call is persisted to Postgres.

Read endpoints (Week 3):
- `GET /api/complaints/{id}` — a stored case plus its ordered audit trail
- `GET /api/complaints` — the 50 most recent cases (audit view)
- `GET /api/escalations` — only the cases flagged for senior review

## Evaluation

Score the deterministic pipeline against a labeled set (no LLM, no DB writes — via `POST /api/eval`):

```bash
python3 eval/run_eval.py        # stack must be running; writes eval/report.md
```

| Metric | Result |
|---|---|
| Classification accuracy | 100% (18/18) |
| Escalation precision | 88% |
| Escalation recall | 100% |
| Escalation F1 | 0.93 |
| Retrieval hit-rate (top-4) | 94% (17/18) |

_The eval set is a small, deliberately clear-cut behavioral set — a regression/sanity check, not a real-world accuracy claim (the model scored ~75% on the full held-out CFPB test set). The single escalation false-positive is a low-confidence case that was correctly routed to human review._ See `eval/README.md`.

## Security

The API enforces a security gate (`ApiKeyFilter` + `CorsConfig`):

- **Public:** `POST /api/intake`, `GET /api/status/{id}`, `/health`, `/actuator/health/**`.
- **Protected** (require `X-API-Key`): all operator endpoints and `/actuator/metrics`.
- **Rate-limited:** intake capped per client IP (default 20/min) to curb spam and LLM-cost abuse.
- CORS restricted to the configured origin; complaint text size-capped (8 KB); error messages/stack traces suppressed; the API container runs as a non-root user; constant-time key comparison.

Set `AEGIS_API_KEY` and `AEGIS_ALLOWED_ORIGINS` in `.env`; the operator console prompts for the key in its sidebar. **For production**, replace the shared key with real user auth (OAuth2/JWT), terminate TLS, and move secrets (NIM key, DB creds) into a secret manager.

## CI

`.github/workflows/ci.yml` runs on every push/PR to `main`: builds + tests the Java API, installs + syntax-checks the Python classifier, and builds both container images. A Cloud Run deploy job is scaffolded (commented) for when GCP is connected.

## Deploy to Cloud Run

```bash
PROJECT=your-project REGION=asia-south1 ./infra/deploy.sh
```
Deploys the classifier (private) and the API (public, wired to the classifier URL). Move the NIM key + DB creds to Secret Manager before wiring the LLM (Week 7).

## Contributing

Contributions are welcome — please read [CONTRIBUTING.md](CONTRIBUTING.md) and the [Code of Conduct](CODE_OF_CONDUCT.md). Report vulnerabilities privately via [SECURITY.md](SECURITY.md).

## License

Released under the [MIT License](LICENSE) © 2026 Hemkesh.

> **Disclaimer:** an educational / portfolio project — not legal or financial advice. The bundled regulation knowledge base is illustrative only. Do not process real customer PII without appropriate privacy and security controls.

## Next (from the roadmap)

- **Week 3 (done):** `AuditService` persists a `CaseRecord` + `AuditEvent` trail to Postgres, exposed via the read endpoints above.
- **Weeks 4–5 (done):** Spring AI RAG — local ONNX embeddings + pgvector retrieval (`RagRetriever`, `IngestionService`) and NIM LLM drafting (`DraftService`), template as automatic fallback. Set `NIM_API_KEY` to enable live drafts.
- **Week 7:** GitHub Actions CI/CD, Secret Manager, structured logging, RAG eval.
