#!/usr/bin/env python3
"""Run the tracked, hand-authored Russian challenge suite against local ONNX models."""
from __future__ import annotations

import json
import os
from pathlib import Path

import numpy as np
import onnxruntime as ort
from sklearn.metrics import accuracy_score, f1_score, precision_score, recall_score
from transformers import AutoTokenizer

from train import BASE, LABELS, MAX_LENGTH, encode


ROOT = Path(__file__).resolve().parent
ASSET = ROOT.parent / "android" / "app" / "src" / "main" / "assets" / "model" / "contextshield-risk-int8.onnx"
BASELINE = ROOT / "artifacts" / "baseline-before-augmentation.onnx"
SUITE = ROOT / "data" / "challenge_suite_ru.jsonl"


def infer(path: Path, tensors) -> np.ndarray:
    options = ort.SessionOptions()
    options.intra_op_num_threads = min(4, os.cpu_count() or 1)
    session = ort.InferenceSession(str(path), sess_options=options, providers=["CPUExecutionProvider"])
    batches = []
    for start in range(0, len(tensors[0]), 16):
        stop = min(start + 16, len(tensors[0]))
        output, _ = session.run(["risk_logits", "tactic_logits"], {
            "input_ids": tensors[0][start:stop].numpy().astype(np.int64),
            "attention_mask": tensors[1][start:stop].numpy().astype(np.int64),
            "token_type_ids": tensors[2][start:stop].numpy().astype(np.int64),
        })
        batches.append(np.asarray(output, dtype=np.float64))
    logits = np.concatenate(batches, axis=0)
    logits -= logits.max(axis=1, keepdims=True)
    probabilities = np.exp(logits)
    return probabilities / probabilities.sum(axis=1, keepdims=True)


def summarize(path: Path, tensors, labels: np.ndarray) -> dict:
    probabilities = infer(path, tensors)
    predicted = probabilities.argmax(axis=1)
    actionable = np.isin(labels, [LABELS.index("suspicious"), LABELS.index("dangerous")]).astype(int)
    high_risk_score = probabilities[:, LABELS.index("suspicious")] + probabilities[:, LABELS.index("dangerous")]
    high_risk_prediction = high_risk_score >= 0.70
    safe = labels == LABELS.index("safe")
    return {
        "model_file": path.name,
        "four_class_accuracy": float(accuracy_score(labels, predicted)),
        "four_class_macro_f1": float(f1_score(labels, predicted, average="macro", zero_division=0)),
        "actionable_risk_precision_at_0_70": float(precision_score(actionable, high_risk_prediction, zero_division=0)),
        "actionable_risk_recall_at_0_70": float(recall_score(actionable, high_risk_prediction, zero_division=0)),
        "false_alerts_on_safe": int(np.logical_and(safe, high_risk_prediction).sum()),
        "safe_rows": int(safe.sum()),
        "predicted_labels": {label: int((predicted == i).sum()) for i, label in enumerate(LABELS)},
    }


def main() -> int:
    rows = [json.loads(line) for line in SUITE.read_text(encoding="utf-8").splitlines()]
    tokenizer = AutoTokenizer.from_pretrained(str(BASE), use_fast=True, local_files_only=True)
    tensors = encode(tokenizer, [str(row["input"])[:1000] for row in rows])
    labels = np.asarray([LABELS.index(row["expected_risk_level"]) for row in rows])
    models = [path for path in (BASELINE, ASSET) if path.is_file()]
    result = {
        "suite": "hand-authored synthetic Russian challenge suite; not used for training",
        "rows": len(rows),
        "expected_classes": {label: int((labels == i).sum()) for i, label in enumerate(LABELS)},
        "models": [summarize(path, tensors, labels) for path in models],
        "note": "Small diagnostic suite only. Scores do not estimate field performance.",
    }
    print(json.dumps(result, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
