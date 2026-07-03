# AEGIS evaluation harness

Two evaluations with two different jobs — never confuse them:

| | Behavioral suite (default) | CFPB temporal holdout (`--cfpb`) |
|---|---|---|
| **Data** | 18 curated, deliberately clear-cut cases (`eval_set.json`) | ~220 recent narratives from the CFPB public API, stratified over 11 categories (`cfpb_sample.json`) |
| **Measures** | classification + escalation + retrieval — did anything break? | real classification accuracy: per-category P/R/F1 + confusion matrix |
| **Honest reading** | regression / sanity check | the numbers to quote |
| **Output** | `report.md` | `cfpb_report.md` |

The holdout is *temporal*: `fetch_cfpb_sample.py` pulls the **most recent** narratives, which were published after the model was trained — so they cannot overlap the training data. Re-fetch any time; recency keeps it honest.

**Run it** (stack must be up):

```bash
docker compose up -d --build api          # exposes POST /api/eval

# behavioral regression suite
python3 eval/run_eval.py

# honest holdout: fetch fresh data, then score
python3 eval/fetch_cfpb_sample.py         # ~220 cases (arg = per-category count)
python3 eval/run_eval.py --cfpb
```

**How it works:** each case is posted to `POST /api/eval`, which runs classify → compliance → escalation → urgency → retrieval and returns the analysis — no LLM call, no DB writes, so a full set scores in seconds. Behavioral ground-truth escalation labels are based on high-severity risk signals present in each complaint.
