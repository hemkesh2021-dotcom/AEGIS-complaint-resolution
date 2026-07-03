"""AEGIS v2 — classifier microservice.

Serves the fine-tuned DistilBERT complaint classifier over HTTP. If the model
cannot be loaded (e.g. not mounted yet), it falls back to a keyword heuristic so
the service — and the whole pipeline — still responds.

Run locally:
    MODEL_DIR=../../models/complaint_classifier uvicorn main:app --port 8000
"""
from __future__ import annotations

import os
from typing import Dict, List

from fastapi import FastAPI
from pydantic import BaseModel

MODEL_DIR = os.environ.get("MODEL_DIR", "/models/complaint_classifier")

app = FastAPI(title="AEGIS Classifier", version="0.1.0")

# ── Model loading (best-effort) ───────────────────────────────────────────────
_tokenizer = None
_model = None
_torch = None
_loaded = False

try:  # heavy imports guarded so the heuristic path works without them
    import numpy as np  # noqa: F401
    import torch as _torch  # type: ignore
    from transformers import AutoModelForSequenceClassification, AutoTokenizer

    if os.path.isdir(MODEL_DIR):
        _tokenizer = AutoTokenizer.from_pretrained(MODEL_DIR)
        _model = AutoModelForSequenceClassification.from_pretrained(MODEL_DIR)
        _model.eval()
        _loaded = True
except Exception as exc:  # noqa: BLE001
    print(f"[classifier] model not loaded ({exc}); using heuristic fallback")


# ── Keyword fallback (CFPB categories) ────────────────────────────────────────
KEYWORDS: Dict[str, List[str]] = {
    "Checking or savings account": ["checking account", "savings account", "overdraft", "debit card", "account fee", "deposit", "withdrawal"],
    "Credit card": ["credit card", "annual fee", "apr", "interest rate", "late fee", "statement", "billing", "rewards"],
    "Credit reporting or other personal consumer reports": ["credit report", "credit score", "equifax", "experian", "transunion", "inaccurate", "reporting"],
    "Debt collection": ["debt collector", "collection agency", "collector", "i owe", "garnish", "repossess", "validation"],
    "Debt or credit management": ["debt management", "debt settlement", "credit repair", "credit counseling", "consolidation"],
    "Money transfer, virtual currency, or money service": ["money transfer", "wire transfer", "remittance", "western union", "paypal", "venmo", "crypto", "bitcoin"],
    "Mortgage": ["mortgage", "escrow", "foreclosure", "loan modification", "refinance", "servicer", "home loan"],
    "Payday loan, title loan, personal loan, or advance loan": ["payday loan", "title loan", "personal loan", "installment loan", "advance loan", "cash advance"],
    "Prepaid card": ["prepaid card", "reloadable card", "prepaid account", "card balance", "gift card"],
    "Student loan": ["student loan", "tuition loan", "sallie mae", "navient", "forbearance", "deferment"],
    "Vehicle loan or lease": ["auto loan", "car loan", "vehicle loan", "auto lease", "repossession", "auto finance"],
}


def _heuristic(text: str) -> Dict[str, object]:
    t = (text or "").lower()
    best_label = "Credit reporting or other personal consumer reports"
    best_score = 0
    for label, kws in KEYWORDS.items():
        score = sum(1 for kw in kws if kw in t)
        if score > best_score:
            best_score = score
            best_label = label
    confidence = min(0.55 + 0.1 * best_score, 0.95) if best_score > 0 else 0.35
    return {"label": best_label, "confidence": float(confidence)}


def _predict(text: str) -> Dict[str, object]:
    if not _loaded:
        return _heuristic(text)
    inputs = _tokenizer(text, truncation=True, padding=True, max_length=256, return_tensors="pt")
    with _torch.no_grad():
        logits = _model(**inputs).logits
        probs = _torch.softmax(logits, dim=-1).cpu().numpy()[0]
    idx = int(probs.argmax())
    label = _model.config.id2label.get(idx, str(idx))
    return {"label": label, "confidence": float(probs[idx])}


# ── API ───────────────────────────────────────────────────────────────────────
class ClassifyRequest(BaseModel):
    text: str


class ClassifyResponse(BaseModel):
    label: str
    confidence: float


@app.get("/health")
def health() -> Dict[str, object]:
    return {"status": "UP", "model_loaded": _loaded, "model_dir": MODEL_DIR}


@app.post("/classify", response_model=ClassifyResponse)
def classify(req: ClassifyRequest) -> Dict[str, object]:
    return _predict(req.text)
