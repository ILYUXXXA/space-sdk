#!/usr/bin/env python3
"""Evaluate conservative review thresholds on the separate Russian holdout set.

This does not fit thresholds or write model/data artifacts. It reports two
explicit targets: (1) suspicious-or-dangerous and (2) any non-safe label.
The evaluation corpus is synthetic and must not be presented as field accuracy.
"""
from __future__ import annotations

import json
import os
from pathlib import Path

import numpy as np
import onnxruntime as ort
from sklearn.metrics import accuracy_score, precision_score, recall_score, f1_score
from transformers import AutoTokenizer

from train import BASE, LABELS, MAX_LENGTH, RAW, TACTICS, encode


ROOT = Path(__file__).resolve().parent
MODEL = ROOT.parent / "android" / "app" / "src" / "main" / "assets" / "model" / "contextshield-risk-int8.onnx"


def main() -> int:
    path = RAW / "eval_ru_en.jsonl"
    if not path.is_file() or not MODEL.is_file():
        raise FileNotFoundError("Download the pinned model/data assets before evaluation")
    rows = [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines()]
    rows = [r for r in rows if r.get("language") == "ru" and r.get("expected_risk_level") in LABELS]
    tokenizer = AutoTokenizer.from_pretrained(str(BASE), use_fast=True, local_files_only=True)
    tensors = encode(tokenizer, [str(r.get("input", ""))[:1000] for r in rows])
    options = ort.SessionOptions()
    options.intra_op_num_threads = min(4, os.cpu_count() or 1)
    session = ort.InferenceSession(str(MODEL), sess_options=options, providers=["CPUExecutionProvider"])
    logits: list[np.ndarray] = []
    tactic_logits: list[np.ndarray] = []
    for start in range(0, len(rows), 16):
        stop = min(start + 16, len(rows))
        feeds = {
            "input_ids": tensors[0][start:stop].numpy().astype(np.int64),
            "attention_mask": tensors[1][start:stop].numpy().astype(np.int64),
            "token_type_ids": tensors[2][start:stop].numpy().astype(np.int64),
        }
        batch, batch_tactics = session.run(["risk_logits", "tactic_logits"], feeds)
        logits.append(np.asarray(batch, dtype=np.float64))
        tactic_logits.append(np.asarray(batch_tactics, dtype=np.float64))
    values = np.concatenate(logits, axis=0)
    values -= values.max(axis=1, keepdims=True)
    probabilities = np.exp(values)
    probabilities /= probabilities.sum(axis=1, keepdims=True)
    tactic_values = np.concatenate(tactic_logits, axis=0)
    tactic_probabilities = 1 / (1 + np.exp(-tactic_values))
    labels = np.asarray([LABELS.index(r["expected_risk_level"]) for r in rows])
    prediction = probabilities.argmax(axis=1)
    risky_truth = np.isin(labels, [LABELS.index("suspicious"), LABELS.index("dangerous")]).astype(int)
    review_truth = (labels != LABELS.index("safe")).astype(int)
    risky_score = probabilities[:, LABELS.index("suspicious")] + probabilities[:, LABELS.index("dangerous")]
    review_score = 1 - probabilities[:, LABELS.index("safe")]
    risky_tactics = [TACTICS.index(name) for name in (
        "urgency", "impersonation", "phishing_link", "payment_request", "credential_theft",
        "investment_or_prize", "remote_access", "secrecy", "job_scam", "marketplace_scam",
        "third_party", "too_good_to_be_true")]

    print(json.dumps({
        "set": "separate Russian synthetic boundary evaluation",
        "rows": len(rows),
        "four_class_accuracy": float(accuracy_score(labels, prediction)),
        "four_class_macro_f1": float(f1_score(labels, prediction, average="macro", zero_division=0)),
        "dangerous_precision": float(precision_score(labels == LABELS.index("dangerous"), prediction == LABELS.index("dangerous"), zero_division=0)),
        "dangerous_recall": float(recall_score(labels == LABELS.index("dangerous"), prediction == LABELS.index("dangerous"), zero_division=0)),
        "review_thresholds": {
            str(threshold): {
                "precision": float(precision_score(review_truth, review_score >= threshold, zero_division=0)),
                "recall": float(recall_score(review_truth, review_score >= threshold, zero_division=0)),
                "f1": float(f1_score(review_truth, review_score >= threshold, zero_division=0)),
            } for threshold in (0.50, 0.60, 0.70, 0.80, 0.90)
        },
        "high_risk_thresholds": {
            str(threshold): {
                "precision": float(precision_score(risky_truth, risky_score >= threshold, zero_division=0)),
                "recall": float(recall_score(risky_truth, risky_score >= threshold, zero_division=0)),
                "f1": float(f1_score(risky_truth, risky_score >= threshold, zero_division=0)),
            } for threshold in (0.50, 0.60, 0.70, 0.80, 0.90)
        },
        "high_risk_plus_tactic_agreement": {
            f"risk_{risk_threshold}_tactic_{tactic_threshold}": {
                "precision": float(precision_score(
                    risky_truth,
                    (risky_score >= risk_threshold) & (tactic_probabilities[:, risky_tactics].max(axis=1) >= tactic_threshold),
                    zero_division=0)),
                "recall": float(recall_score(
                    risky_truth,
                    (risky_score >= risk_threshold) & (tactic_probabilities[:, risky_tactics].max(axis=1) >= tactic_threshold),
                    zero_division=0)),
                "f1": float(f1_score(
                    risky_truth,
                    (risky_score >= risk_threshold) & (tactic_probabilities[:, risky_tactics].max(axis=1) >= tactic_threshold),
                    zero_division=0)),
            } for risk_threshold, tactic_threshold in ((0.70, 0.50), (0.70, 0.60), (0.65, 0.70), (0.70, 0.70))
        },
        "note": "Synthetic holdout only; thresholds are diagnostic, not field-performance estimates.",
    }, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
