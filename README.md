# AEGIS — Agentic Complaint Resolution

![CI](https://github.com/hemkesh2021-dotcom/AEGIS-complaint-resolution/actions/workflows/ci.yml/badge.svg)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)

A banking grievance system that takes a complaint from intake to a regulator-aware, human-approved response — and **verifies its own AI before anything reaches a customer**.

A **Spring Boot** (Java 21) orchestrator chains seven phases — ingest → classify → compliance → retrieve → draft → verify → audit — calling a **Python DistilBERT** classifier, **pgvector** RAG retrieval over regulation passages, and an external LLM (**NVIDIA NIM**) for drafting, with a deterministic template engine as automatic fallback. Customers get a live-updating portal; operators get an urgency-sorted review console; every case gets an append-only audit trail.

**Explore the system as an [interactive 3D architecture](frontend/architecture.html)** — served at `http://localhost:8088/architecture.html` once the stack is up. Every pulse in the animation is a real data path.

## Why it's different

- **Grounding gate.** Every AI draft is verified twice — at draft time and again at the moment of approval. Amounts, dates, and phone numbers must appear in the complaint or retrieved context; template debris (`[Bank Name]`, raw timestamps) is caught. A reply citing $150 against a $420 complaint is blocked with a 422; operator overrides are possible but audit-trailed. This caught a real hallucination during development — there's a regression test reproducing it.
- **PII never crosses the trust boundary.** Prompts to the external LLM are redacted (emails, phones, card/account numbers, SSNs) and the customer's name travels as a `{CUSTOMER_NAME}` token substituted back locally. Classification and embeddings run fully local. A daily job purges expired data (`AEGIS_RETENTION_DAYS`).
- **Unguessable tracking.** Customers track cases with a 128-bit `TRK-…` token; short CMP references are never accepted on public endpoints, so cases can't be enumerated. Both public endpoints are per-IP rate-limited.
- **Immutable communications.** Sent replies can't be edited — corrections go out as follow-up messages the customer sees as a thread, each one re-verified and audited.
- **Honestly evaluated.** Accuracy is measured on a *temporal holdout* refreshed from the live CFPB API — not a curated demo set (see [Evaluation](#evaluation)).
- **Explainable by default.** Deadlines, risk flags, escalation, and urgency are deterministic rules with recorded reasons — ML only where it earns its place, and low classifier confidence auto-escalates to a human.

## Layout

```
aegis-v2/
├── api/          Spring Boot orchestrator (Java 21) — pipeline, gate, audit
│   ├── src/main/java/com/aegis/api/{controller,dto,entity,repo,service}
│   ├── src/main/resources/{application.yml,regulations.json,templates.json,knowledge/kb.json}
│   └── src/test/java/…                  unit tests (grounding gate, compliance clocks, security filter…)
├── classifier/   Python FastAPI serving fine-tuned DistilBERT (+ heuristic fallback)
├── frontend/     portal.html (customer) · index.html (operator console) · architecture.html (3D)
├── eval/         behavioral suite + CFPB temporal-holdout harness
├── infra/        deploy.sh — Cloud Run deploy for both services
└── docker-compose.yml   local end-to-end (classifier + api + Postgres/pgvector + nginx)
```

## Quickstart

```bash
cp .env.example .env        # set AEGIS_API_KEY (and NIM_API_KEY for live LLM drafts)
docker compose up --build
```

| Surface | URL |
|---|---|
| Customer portal | http://localhost:8088/portal.html |
| Operator console | http://localhost:8088 (enter your `AEGIS_API_KEY` in the sidebar) |
| 3D architecture | http://localhost:8088/architecture.html |
| API health | http://localhost:8080/health |

Without a NIM key the pipeline still runs end to end — drafts come from the deterministic template engine (`"source": "Template engine"` instead of `"NVIDIA NIM (RAG)"`). On first startup the API ingests `knowledge/kb.json` into pgvector using local ONNX embeddings (no key needed).

**Try the full loop:** lodge a complaint on the portal (note the `TRK-…` tracking code) → open the case in the console → edit the reply to add an invented figure like `$999` → **Approve & send** gets blocked by the grounding gate with named issues → fix it, send, and watch the portal flip to *Responded* with an AI summary and the full reply.

## API

**Public** (rate-limited per IP):

- `POST /api/intake` — lodge a complaint → acknowledgement + `trackingToken`
- `GET /api/status/{trackingToken}` — status; once responded: reply + summary + follow-up thread

**Operator** (require `X-API-Key`):

- `POST /api/complaints` — run the full pipeline on a case
- `GET /api/complaints` · `GET /api/complaints/{id}` · `GET /api/escalations` — queue, case + audit trail + thread, escalations
- `POST /api/complaints/{id}/approve` — verify + send; `422` with issues if the grounding check fails (`force: true` to override, audited); `409` once sent
- `POST /api/complaints/{id}/follow-up` — append a verified correction/update to a sent case
- `POST /api/eval` — fast, side-effect-free scoring endpoint for the eval harness

## Evaluation

Two evaluations, honestly separated — one measures real accuracy, the other proves nothing broke. Both call `POST /api/eval` (no LLM, no DB writes; stack must be running).

**1 · CFPB temporal holdout — the numbers to quote.** A stratified sample of the *most recent* complaint narratives from the CFPB public API. Because they were published after the model was trained, they cannot overlap the training data:

```bash
python3 eval/fetch_cfpb_sample.py     # ~220 fresh cases, stratified over 11 categories
python3 eval/run_eval.py --cfpb       # accuracy + per-category P/R/F1 + confusion matrix
```

**Latest holdout run — 77.3% overall accuracy (170/220, all 11 categories)**, consistent with the ~75% held-out test split at training time. Highlights: Vehicle loan F1 **0.95**, Prepaid card **0.91**, Student loan **0.88**. The model's one real weakness is the semantically adjacent debt cluster: "Debt or credit management" acts as a magnet for misclassifications (precision 40%), pulling cases from Debt collection (6×) and Credit reporting (4×) — a category-boundary problem even humans argue about. Full per-category table and confusion matrix: [`eval/cfpb_report.md`](eval/cfpb_report.md). Regenerate any time — recency keeps it honest.

**2 · Behavioral regression suite** — 18 curated, deliberately clear-cut cases exercising classification, escalation, and retrieval end to end:

```bash
python3 eval/run_eval.py              # writes eval/report.md
```

| Metric | Result |
|---|---|
| Classification accuracy | 100% (18/18) |
| Escalation precision | 88% |
| Escalation recall | 100% |
| Escalation F1 | 0.93 |
| Retrieval hit-rate (top-4) | 94% (17/18) |

_These scores mean the pipeline behaves correctly on unambiguous inputs — a regression check, **not** a real-world accuracy claim. The single escalation "false positive" is a low-confidence case correctly routed to human review._ See `eval/README.md`.

## Tests

The deterministic core is unit-tested (`mvn test`; runs on every CI push):

- **DraftVerifier** — the grounding gate, including a regression test that reproduces a real caught incident (an LLM draft citing $150 against a $420 complaint, with an invented 1-800 number and a `[Bank Name]` placeholder).
- **PiiRedactor** — every identifier class is stripped; complaint substance and amounts survive.
- **ComplianceEngine** — exact business-day deadline math (weekend skipping) and all four risk-flag families.
- **EscalationDecider** — risk-flag and confidence-threshold behavior, including the 0.55 boundary.
- **ApiKeyFilter** — 401 without key, public paths open, per-IP rate limits on intake *and* status, fail-closed when unconfigured.

## Security

Defense in depth, documented in full in [SECURITY.md](SECURITY.md):

- **Auth:** operator endpoints + `/actuator/metrics` behind `X-API-Key` (constant-time compare); only intake, status, and health are public.
- **AI-output safety:** the grounding gate blocks invented figures, contacts, and template debris at draft *and* send time; overrides are audited.
- **Data protection:** PII redaction + name tokenization before any external LLM call; 128-bit tracking tokens; retention purge job.
- **Abuse resistance:** per-IP rate limits on intake (LLM-cost abuse) and status (token guessing); 8 KB input cap; suppressed stack traces; non-root containers.
- **Supply chain:** gitleaks secret-scanning in CI (full history); Dependabot across Maven, pip, Actions, and Dockerfiles.

**For production:** replace the shared key with OAuth2/OIDC + roles, terminate TLS behind a WAF, and move secrets to a secret manager.

## CI

`.github/workflows/ci.yml` on every push/PR to `main`: gitleaks secret scan → build + **unit-test** the Java API → check the Python classifier → build both container images. A Cloud Run deploy job is scaffolded (commented) for when GCP is connected.

## Deploy to Cloud Run

```bash
PROJECT=your-project REGION=asia-south1 ./infra/deploy.sh
```

Deploys the classifier (private) and the API (public, wired to the classifier URL). Move the NIM key + DB creds to Secret Manager first.

## Roadmap

Server-sent events for live portal updates (replacing polling) · OAuth2/OIDC with operator roles + maker-checker approval · distributed rate limiting · real email delivery (in + out) · operator-feedback learning loop (edits → retraining data) · hybrid retrieval (BM25 + reranker) with citation-pinned drafts · multilingual intake · case-intelligence dashboard · k6 load-test benchmarks.

## Contributing

Contributions are welcome — please read [CONTRIBUTING.md](CONTRIBUTING.md) and the [Code of Conduct](CODE_OF_CONDUCT.md). Report vulnerabilities privately via [SECURITY.md](SECURITY.md).

## License

Released under the [MIT License](LICENSE) © 2026 Hemkesh.

> **Disclaimer:** an educational / portfolio project — not legal or financial advice. The bundled regulation knowledge base is illustrative only. Do not process real customer PII without appropriate privacy and security controls.
