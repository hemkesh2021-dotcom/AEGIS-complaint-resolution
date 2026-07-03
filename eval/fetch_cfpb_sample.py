#!/usr/bin/env python3
"""Fetch a stratified, *temporally held-out* eval sample from the CFPB public API.

Pulls the most recent consumer-complaint narratives per product category.
Because these were published AFTER the classifier was trained, they cannot
overlap the training data — an honest holdout, refreshed on demand.

Usage:
    python3 eval/fetch_cfpb_sample.py [PER_CATEGORY]   # default 20

Writes eval/cfpb_sample.json: [{"expected_category": ..., "text": ...}, ...]
No dependencies beyond the standard library.
"""
import json
import os
import sys
import time
import urllib.parse
import urllib.request

PER_CAT = int(sys.argv[1]) if len(sys.argv) > 1 else 20
HERE = os.path.dirname(os.path.abspath(__file__))
API = "https://www.consumerfinance.gov/data-research/consumer-complaints/search/api/v1/"

CATEGORIES = [
    "Checking or savings account",
    "Credit card",
    "Credit reporting or other personal consumer reports",
    "Debt collection",
    "Debt or credit management",
    "Money transfer, virtual currency, or money service",
    "Mortgage",
    "Payday loan, title loan, personal loan, or advance loan",
    "Prepaid card",
    "Student loan",
    "Vehicle loan or lease",
]

MIN_CHARS = 200      # skip stub narratives
MAX_CHARS = 7000     # stay under the API's 8000-char input limit


def fetch(category, want):
    def attempt(size):
        params = urllib.parse.urlencode({
            "product": category,
            "has_narrative": "true",
            "sort": "created_date_desc",
            "size": size,
            "no_aggs": "true",     # skip aggregation buckets — much faster
            "format": "json",
        })
        req = urllib.request.Request(API + "?" + params, headers={
            "User-Agent": "aegis-eval/1.0",
            "Accept": "application/json",
        })
        with urllib.request.urlopen(req, timeout=90) as r:
            return json.loads(r.read())

    # Huge categories (e.g. credit reporting) can return truncated multi-GB
    # bodies at larger sizes — degrade the page size until a response parses.
    data, last_err = None, None
    for size in (want * 4, want * 2, want):
        for _ in (1, 2):
            try:
                data = attempt(size)
                break
            except Exception as e:
                last_err = e
                time.sleep(3)
        if data is not None:
            break
        print(f"  .. {category}: retrying with size={max(want, size // 2)}")
    if data is None:
        print(f"  !! {category}: {type(last_err).__name__}: {last_err} — skipping")
        return []

    # The API answers in two shapes: a bare list of hits, or the
    # Elasticsearch-style {"hits": {"hits": [...]}} envelope. Accept both.
    if isinstance(data, list):
        hits = data
    elif isinstance(data, dict):
        h = data.get("hits")
        hits = (h.get("hits") if isinstance(h, dict) else h) or []
    else:
        hits = []

    seen, out = set(), []
    for h in hits:
        src = h.get("_source") if isinstance(h.get("_source"), dict) else h
        text = (src.get("complaint_what_happened") or "").strip()
        if len(text) < MIN_CHARS:
            continue
        key = text[:160]
        if key in seen:
            continue
        seen.add(key)
        out.append({"expected_category": category, "text": text[:MAX_CHARS]})
        if len(out) >= want:
            break
    return out


def main():
    sample = []
    for cat in CATEGORIES:
        rows = fetch(cat, PER_CAT)
        print(f"  {cat}: {len(rows)}")
        sample.extend(rows)
        time.sleep(1)  # be polite to the public API

    path = os.path.join(HERE, "cfpb_sample.json")
    with open(path, "w") as f:
        json.dump(sample, f, indent=1)
    print(f"\nWrote {len(sample)} cases → {path}")
    print("Now score it:  python3 eval/run_eval.py --cfpb")


if __name__ == "__main__":
    main()
