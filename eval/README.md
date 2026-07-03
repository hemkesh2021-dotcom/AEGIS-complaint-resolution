# AEGIS evaluation harness

Scores the deterministic pipeline against a small labeled set — no LLM, no DB writes.

**Metrics**
- **Classification accuracy** — predicted CFPB category vs. expected.
- **Escalation precision / recall / F1** — did it flag the genuinely risky complaints (fraud, legal, harassment, vulnerable customer)?
- **Retrieval hit-rate** — did the top-4 pgvector passages include one from the expected category?
- **Urgency distribution** — Critical / Prioritized / Normal counts.

**Run it** (stack must be up):

```bash
docker compose up -d --build api      # picks up the /api/eval endpoint
python3 eval/run_eval.py              # or: python3 eval/run_eval.py http://localhost:8080
```

Results print to the console and are written to `eval/report.md` — paste those numbers into your project README.

**How it works:** `run_eval.py` posts each case to `POST /api/eval`, which runs classify → compliance → escalation → urgency → retrieval and returns the analysis, skipping the LLM draft and persistence so the whole set scores in seconds. Ground-truth escalation labels are based on the presence of high-severity risk signals in each complaint.
