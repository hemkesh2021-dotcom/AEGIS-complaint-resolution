#!/usr/bin/env python3
"""AEGIS evaluation harness.

Two modes, honestly separated:

  DEFAULT  — behavioral regression suite: 18 curated, deliberately clear-cut
             cases. A sanity/regression check on the whole deterministic
             pipeline (classification, escalation, retrieval). High scores
             here mean "nothing broke", NOT "the model is this accurate".

  --cfpb   — stratified sample of *recent* CFPB narratives (fetched with
             fetch_cfpb_sample.py, published after the model was trained, so
             a true temporal holdout). Reports accuracy, per-category
             precision/recall/F1 and a confusion matrix. These are the
             numbers to quote.

Both call POST /api/eval — classify → compliance → escalation → urgency →
retrieval, with no LLM call and no DB writes.

Usage:
    python3 eval/run_eval.py [--cfpb] [API_BASE]     # default http://localhost:8080
"""
import json
import os
import sys
import urllib.request

args = [a for a in sys.argv[1:]]
CFPB = "--cfpb" in args
args = [a for a in args if a != "--cfpb"]
API = (args[0] if args else "http://localhost:8080").rstrip("/")
HERE = os.path.dirname(os.path.abspath(__file__))


def load_api_key():
    """POST /api/eval sits behind the operator gate — send X-API-Key.
    Sources, in order: $AEGIS_API_KEY, then the repo-root .env file."""
    key = os.environ.get("AEGIS_API_KEY", "").strip()
    if key:
        return key
    env_path = os.path.join(HERE, "..", ".env")
    if os.path.exists(env_path):
        for line in open(env_path):
            line = line.strip()
            if line.startswith("AEGIS_API_KEY=") and not line.startswith("#"):
                return line.split("=", 1)[1].strip()
    return ""


API_KEY = load_api_key()
if not API_KEY:
    sys.exit("No API key found — set AEGIS_API_KEY in the environment or in .env "
             "(the eval endpoint is operator-gated).")


def call(text):
    data = json.dumps({"text": text}).encode()
    req = urllib.request.Request(
        API + "/api/eval", data=data,
        headers={"Content-Type": "application/json", "X-API-Key": API_KEY},
    )
    with urllib.request.urlopen(req, timeout=60) as r:
        return json.loads(r.read())


# ── short aliases for readable confusion matrices ────────────────────────────
ALIAS = {
    "Checking or savings account": "Checking",
    "Credit card": "CreditCard",
    "Credit reporting or other personal consumer reports": "CredRep",
    "Debt collection": "DebtColl",
    "Debt or credit management": "DebtMgmt",
    "Money transfer, virtual currency, or money service": "MoneyTx",
    "Mortgage": "Mortgage",
    "Payday loan, title loan, personal loan, or advance loan": "PaydayLoan",
    "Prepaid card": "Prepaid",
    "Student loan": "StudentLn",
    "Vehicle loan or lease": "VehicleLn",
}
def alias(c):
    return ALIAS.get(c, (c or "?")[:10])


def eval_cfpb():
    path = os.path.join(HERE, "cfpb_sample.json")
    if not os.path.exists(path):
        sys.exit("No cfpb_sample.json — run:  python3 eval/fetch_cfpb_sample.py")
    cases = json.load(open(path))
    cats = sorted({c["expected_category"] for c in cases})
    conf = {e: {p: 0 for p in cats + ["OTHER"]} for e in cats}
    correct = 0

    print(f"Scoring {len(cases)} CFPB holdout cases against {API} …")
    for i, c in enumerate(cases):
        r = call(c["text"])
        exp, pred = c["expected_category"], r["label"]
        conf[exp][pred if pred in cats else "OTHER"] += 1
        correct += (pred == exp)
        if (i + 1) % 25 == 0:
            print(f"  {i+1}/{len(cases)}  running accuracy {correct/(i+1):.0%}")

    n = len(cases)
    out = []
    out.append("# AEGIS — CFPB Holdout Evaluation\n")
    out.append(f"Cases: **{n}** (stratified, most-recent narratives from the CFPB public API "
               f"— published after model training; a temporal holdout) · API: `{API}`\n")
    out.append(f"## Overall accuracy: **{correct/n:.1%}** ({correct}/{n})\n")

    out.append("## Per-category precision / recall / F1\n")
    out.append("| Category | n | Precision | Recall | F1 |")
    out.append("|---|--:|--:|--:|--:|")
    for cat in cats:
        tp = conf[cat][cat]
        fn = sum(conf[cat].values()) - tp
        fp = sum(conf[e][cat] for e in cats if e != cat)
        prec = tp / (tp + fp) if (tp + fp) else 0.0
        rec = tp / (tp + fn) if (tp + fn) else 0.0
        f1 = 2 * prec * rec / (prec + rec) if (prec + rec) else 0.0
        out.append(f"| {cat} | {tp+fn} | {prec:.0%} | {rec:.0%} | {f1:.2f} |")

    out.append("\n## Confusion matrix (rows = expected, cols = predicted)\n")
    out.append("| | " + " | ".join(alias(c) for c in cats) + " | OTHER |")
    out.append("|---|" + "--:|" * (len(cats) + 1))
    for e in cats:
        row = [str(conf[e][p]) if conf[e][p] else "·" for p in cats + ["OTHER"]]
        out.append(f"| **{alias(e)}** | " + " | ".join(row) + " |")

    pairs = sorted(((conf[e][p], e, p) for e in cats for p in cats if e != p and conf[e][p]),
                   reverse=True)[:5]
    if pairs:
        out.append("\n## Top confusions\n")
        for n_, e, p in pairs:
            out.append(f"- {e} → predicted {p} ({n_}×)")

    out.append("\n*Generated by `eval/run_eval.py --cfpb`. Refresh the sample any time with "
               "`eval/fetch_cfpb_sample.py` — recency keeps it a true holdout.*")
    text = "\n".join(out)
    print("\n" + text)
    with open(os.path.join(HERE, "cfpb_report.md"), "w") as f:
        f.write(text + "\n")
    print(f"\nWrote {os.path.join(HERE, 'cfpb_report.md')}")


def eval_behavioral():
    cases = json.load(open(os.path.join(HERE, "eval_set.json")))
    correct = tp = fp = fn = tn = retr_hits = 0
    tiers = {}
    rows = []

    for c in cases:
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

    n = len(cases)
    acc = correct / n
    prec = tp / (tp + fp) if (tp + fp) else 0.0
    rec = tp / (tp + fn) if (tp + fn) else 0.0
    f1 = 2 * prec * rec / (prec + rec) if (prec + rec) else 0.0
    retr = retr_hits / n

    out = []
    out.append("# AEGIS — Behavioral Regression Suite\n")
    out.append(f"Cases: **{n}** curated, deliberately clear-cut · API: `{API}`\n")
    out.append("> A sanity/regression check on the whole pipeline — high scores mean "
               "*nothing broke*, not that the model is this accurate on real data. "
               "For honest model metrics see `cfpb_report.md` (run with `--cfpb`).\n")
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


if __name__ == "__main__":
    eval_cfpb() if CFPB else eval_behavioral()
