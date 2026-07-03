#!/usr/bin/env python3
"""AEGIS evaluation harness.

Scores the deterministic pipeline (classification, escalation, retrieval) against
a labeled set by calling POST /api/eval — which runs classify → compliance →
escalation → urgency → retrieval WITHOUT the LLM draft or DB writes, so it's fast
and side-effect free.

Usage:
    python eval/run_eval.py [API_BASE]      # default http://localhost:8080
"""
import json
import os
import sys
import urllib.request

API = (sys.argv[1] if len(sys.argv) > 1 else "http://localhost:8080").rstrip("/")
HERE = os.path.dirname(os.path.abspath(__file__))
CASES = json.load(open(os.path.join(HERE, "eval_set.json")))


def call(text):
    data = json.dumps({"text": text}).encode()
    req = urllib.request.Request(
        API + "/api/eval", data=data, headers={"Content-Type": "application/json"}
    )
    with urllib.request.urlopen(req, timeout=30) as r:
        return json.loads(r.read())


correct = tp = fp = fn = tn = retr_hits = 0
tiers = {}
rows = []

for c in CASES:
    r = call(c["text"])
    label_ok = r["label"] == c["expected_category"]
    correct += label_ok
    exp, got = bool(c["expect_escalate"]), bool(r["escalate"])
    tp += exp and got
    fp += (not exp) and got
    fn += exp and (not got)
    tn += (not exp) and (not got)
    hit = c["expected_category"] in (r.get("retrievedCategories") or [])
    retr_hits += hit
    tiers[r["urgency"]] = tiers.get(r["urgency"], 0) + 1
    rows.append((c["expected_category"], r["label"], label_ok, exp, got, hit))

n = len(CASES)
acc = correct / n
prec = tp / (tp + fp) if (tp + fp) else 0.0
rec = tp / (tp + fn) if (tp + fn) else 0.0
f1 = 2 * prec * rec / (prec + rec) if (prec + rec) else 0.0
retr = retr_hits / n

out = []
out.append("# AEGIS Evaluation Report\n")
out.append(f"Cases: **{n}** · API: `{API}`\n")
out.append("| Metric | Value |")
out.append("|---|---|")
out.append(f"| Classification accuracy | {acc:.0%} ({correct}/{n}) |")
out.append(f"| Escalation precision | {prec:.0%} |")
out.append(f"| Escalation recall | {rec:.0%} |")
out.append(f"| Escalation F1 | {f1:.2f} |")
out.append(f"| Retrieval hit-rate (top-4) | {retr:.0%} ({retr_hits}/{n}) |")
out.append(f"| Urgency distribution | {tiers} |")
out.append("")
out.append("| Expected | Predicted | ✓ | exp.esc | got.esc | retr✓ |")
out.append("|---|---|:-:|:-:|:-:|:-:|")
for exp_cat, pred, ok, ee, ge, ht in rows:
    out.append(
        f"| {exp_cat} | {pred} | {'✓' if ok else '✗'} | {ee} | {ge} | {'✓' if ht else '✗'} |"
    )

text = "\n".join(out)
print(text)
with open(os.path.join(HERE, "report.md"), "w") as f:
    f.write(text + "\n")
print(f"\nWrote {os.path.join(HERE, 'report.md')}")
