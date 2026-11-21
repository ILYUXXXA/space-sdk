#!/usr/bin/env python3
"""Reproducibly fine-tune a small Russian scam-risk classifier and export INT8 ONNX.

Only the Russian partition of the licensed synthetic corpus is used. Raw message
examples are never written to logs or metrics artifacts.
"""
from __future__ import annotations

import copy
import hashlib
import json
import os
import random
import re
import shutil
import sys
import time
from collections import Counter
from pathlib import Path

os.environ.setdefault("TOKENIZERS_PARALLELISM", "false")

import numpy as np
import onnxruntime as ort
import torch
from onnxruntime.quantization import QuantType, quantize_dynamic
from sklearn.feature_extraction.text import TfidfVectorizer
from sklearn.pipeline import FeatureUnion
from sklearn.linear_model import LogisticRegression
from sklearn.metrics import (accuracy_score, classification_report, confusion_matrix,
                             f1_score, precision_recall_fscore_support)
from sklearn.model_selection import GroupShuffleSplit
from torch.utils.data import DataLoader, TensorDataset
from transformers import AutoModel, AutoTokenizer


ROOT = Path(__file__).resolve().parent
RAW = ROOT / "data" / "raw"
BASE = ROOT / "base-model"
ARTIFACTS = ROOT / "artifacts"
ANDROID_ASSETS = ROOT.parent / "android" / "app" / "src" / "main" / "assets" / "model"
LABELS = ["safe", "needs_check", "suspicious", "dangerous"]
LABEL_TO_ID = {name: index for index, name in enumerate(LABELS)}
TACTICS = ["urgency", "impersonation", "phishing_link", "payment_request", "credential_theft",
           "verification_needed", "investment_or_prize", "remote_access", "secrecy", "job_scam",
           "marketplace_scam", "third_party", "too_good_to_be_true"]
SEED = 20261121
MAX_LENGTH = 128
MODEL_REVISION = "e8ed3b0c8bbf4fb6984c3de043bf7d2f4e5969ae"
DATASET_REVISION = "d4141645c5d9c439143604e5e64f7d3651e82942"


def set_seed(seed: int) -> None:
    random.seed(seed)
    np.random.seed(seed)
    torch.manual_seed(seed)
    if torch.cuda.is_available():
        torch.cuda.manual_seed_all(seed)


def extract_message(prompt: str) -> str:
    patterns = [
        r"Analyze this message for scam risk:\s*\n+(.+?)\n+\s*Return only the JSON object\.",
        r"### Input Message\s*\n+(.+?)\n+\s*Return(?: ONLY| only)? a valid JSON object",
        r"Input Message\s*\n+(.+?)\n+\s*Return",
    ]
    for pattern in patterns:
        match = re.search(pattern, prompt, flags=re.IGNORECASE | re.DOTALL)
        if match:
            text = match.group(1).strip().strip('"')
            if text:
                return text
    # Fail closed instead of training on a system prompt or instruction text.
    raise ValueError("Could not isolate the message field from a dataset row")


def map_tactics(annotation: dict) -> list[float]:
    tags = " ".join(str(x) for x in annotation.get("tactics", [])).lower()
    scam_type = str(annotation.get("scam_type", "")).lower()
    all_text = f"{tags} {scam_type}"
    found: set[str] = set()
    checks = {
        "urgency": ("urgency",),
        "impersonation": ("fake authority", "impersonation", "family_impersonation", "government_impersonation"),
        "phishing_link": ("suspicious link", "unknown_link", "phishing"),
        "payment_request": ("payment request", "payment pressure"),
        "credential_theft": ("credential request", "code theft", "wallet request", "credential_theft"),
        "verification_needed": ("verification needed",),
        "investment_or_prize": ("investment bait", "investment grooming", "crypto request", "prize_scam", "investment_scam"),
        "remote_access": ("remote access request", "tech_support"),
        "secrecy": ("secrecy",),
        "job_scam": ("fake job", "job_scam"),
        "marketplace_scam": ("marketplace", "marketplace_scam"),
        "third_party": ("third party", "new channel", "suspicious contact", "wrong number"),
        "too_good_to_be_true": ("too good to be true", "prize_scam", "investment_scam"),
    }
    for label, phrases in checks.items():
        if any(phrase in all_text for phrase in phrases):
            found.add(label)
    return [1.0 if label in found else 0.0 for label in TACTICS]


def read_train_rows() -> list[dict]:
    path = RAW / "adaption-scam-train.jsonl"
    if not path.is_file():
        raise FileNotFoundError(f"Missing licensed corpus: {path}")
    records: list[dict] = []
    for line_no, line in enumerate(path.read_text(encoding="utf-8").splitlines(), start=1):
        row = json.loads(line)
        if row.get("language") != "ru":
            continue
        annotation = json.loads(row["assistant"])
        risk = annotation.get("risk_level")
        if risk not in LABEL_TO_ID:
            continue
        message = extract_message(row.get("user", ""))
        scenario_id = re.sub(r"_ru$", "", str(row.get("id", f"row-{line_no}")), flags=re.I)
        records.append({"text": message[:1000], "label": LABEL_TO_ID[risk],
                        "tactics": map_tactics(annotation), "group": scenario_id})
    if len(records) < 400:
        raise ValueError(f"Unexpectedly few Russian training rows: {len(records)}")
    return records


def group_split(records: list[dict]) -> tuple[list[int], list[int], list[int]]:
    y = np.asarray([r["label"] for r in records])
    groups = np.asarray([r["group"] for r in records])
    first = GroupShuffleSplit(n_splits=1, test_size=0.16, random_state=SEED)
    train_val, test = next(first.split(np.zeros(len(y)), y, groups))
    second = GroupShuffleSplit(n_splits=1, test_size=0.18, random_state=SEED + 1)
    train_rel, val_rel = next(second.split(np.zeros(len(train_val)), y[train_val], groups[train_val]))
    train = train_val[train_rel]
    val = train_val[val_rel]
    for name, idx in (("train", train), ("validation", val), ("test", test)):
        missing = sorted(set(range(len(LABELS))) - set(y[idx].tolist()))
        if missing:
            raise ValueError(f"The grouped {name} split is missing classes {missing}; revise split seed/strategy")
    return train.tolist(), val.tolist(), test.tolist()


def encode(tokenizer, texts: list[str]) -> tuple[torch.Tensor, torch.Tensor, torch.Tensor]:
    batch = tokenizer(texts, truncation=True, padding="max_length", max_length=MAX_LENGTH,
                      return_tensors="pt")
    token_type_ids = batch.get("token_type_ids", torch.zeros_like(batch["input_ids"]))
    return batch["input_ids"], batch["attention_mask"], token_type_ids


def make_loader(tokenizer, records: list[dict], indices: list[int], batch_size: int, shuffle: bool):
    chosen = [records[i] for i in indices]
    input_ids, attention_mask, token_type_ids = encode(tokenizer, [r["text"] for r in chosen])
    labels = torch.tensor([r["label"] for r in chosen], dtype=torch.long)
    tactics = torch.tensor([r["tactics"] for r in chosen], dtype=torch.float32)
    return DataLoader(TensorDataset(input_ids, attention_mask, token_type_ids, labels, tactics),
                      batch_size=batch_size, shuffle=shuffle)


def predict_torch(model, loader, device) -> tuple[list[int], list[list[float]], list[list[float]]]:
    model.eval()
    predictions: list[int] = []
    probabilities: list[list[float]] = []
    tactic_probabilities: list[list[float]] = []
    with torch.inference_mode():
        for ids, mask, types, _, _ in loader:
            risk_logits, tactic_logits = model(input_ids=ids.to(device), attention_mask=mask.to(device),
                                               token_type_ids=types.to(device))
            probs = risk_logits.softmax(dim=-1).cpu().numpy()
            tactic_probs = tactic_logits.sigmoid().cpu().numpy()
            predictions.extend(probs.argmax(axis=1).tolist())
            probabilities.extend(probs.tolist())
            tactic_probabilities.extend(tactic_probs.tolist())
    return predictions, probabilities, tactic_probabilities


def classification_metrics(y_true: list[int], y_pred: list[int]) -> dict:
    report = classification_report(y_true, y_pred, labels=list(range(len(LABELS))),
                                   target_names=LABELS, output_dict=True, zero_division=0)
    return {
        "rows": len(y_true),
        "accuracy": accuracy_score(y_true, y_pred),
        "macro_f1": f1_score(y_true, y_pred, average="macro", zero_division=0),
        "weighted_f1": f1_score(y_true, y_pred, average="weighted", zero_division=0),
        "dangerous_precision_recall_f1": report["dangerous"],
        "per_class": {name: report[name] for name in LABELS},
        "confusion_matrix_rows_true_cols_pred": confusion_matrix(y_true, y_pred, labels=list(range(len(LABELS)))).tolist(),
    }


def tactic_metrics(y_true: list[list[float]], probabilities: list[list[float]], threshold: float = 0.55) -> dict:
    truth = np.asarray(y_true, dtype=np.int32)
    predicted = (np.asarray(probabilities) >= threshold).astype(np.int32)
    precision, recall, f1, support = precision_recall_fscore_support(
        truth, predicted, average=None, zero_division=0
    )
    return {
        "threshold": threshold,
        "micro_f1": f1_score(truth, predicted, average="micro", zero_division=0),
        "macro_f1": f1_score(truth, predicted, average="macro", zero_division=0),
        "per_tactic": {name: {"precision": float(precision[i]), "recall": float(recall[i]),
                               "f1": float(f1[i]), "support": int(support[i])}
                       for i, name in enumerate(TACTICS)},
    }


def tfidf_baseline(records: list[dict], train_indices: list[int], test_indices: list[int]) -> dict:
    word = TfidfVectorizer(ngram_range=(1, 2), min_df=1, max_features=50000, sublinear_tf=True)
    char = TfidfVectorizer(analyzer="char_wb", ngram_range=(3, 5), min_df=2,
                           max_features=60000, sublinear_tf=True)
    features = FeatureUnion([("word", word), ("char", char)])
    train_text = [records[i]["text"] for i in train_indices]
    test_text = [records[i]["text"] for i in test_indices]
    train_y = [records[i]["label"] for i in train_indices]
    test_y = [records[i]["label"] for i in test_indices]
    x_train = features.fit_transform(train_text)
    x_test = features.transform(test_text)
    model = LogisticRegression(max_iter=500, class_weight="balanced", random_state=SEED)
    model.fit(x_train, train_y)
    return classification_metrics(test_y, model.predict(x_test).tolist())


def load_external_eval(tokenizer) -> tuple[torch.Tensor, torch.Tensor, torch.Tensor, list[int], list[list[float]]]:
    path = RAW / "eval_ru_en.jsonl"
    if not path.is_file():
        return torch.empty(0), torch.empty(0), torch.empty(0), [], []
    rows = [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines()]
    rows = [row for row in rows if row.get("language") == "ru" and row.get("expected_risk_level") in LABEL_TO_ID]
    inputs = [str(row.get("input", ""))[:1000] for row in rows]
    ids, masks, types = encode(tokenizer, inputs)
    target_tactics = [map_tactics({"tactics": row.get("expected_tactics", []),
                                   "scam_type": row.get("expected_scam_type", "")}) for row in rows]
    return ids, masks, types, [LABEL_TO_ID[row["expected_risk_level"]] for row in rows], target_tactics


def eval_onnx(model_path: Path, tensors, labels: list[int], tactics_true: list[list[float]]) -> dict:
    if not labels:
        return {"rows": 0, "note": "No external eval set found"}
    options = ort.SessionOptions()
    options.intra_op_num_threads = min(4, os.cpu_count() or 1)
    session = ort.InferenceSession(str(model_path), sess_options=options, providers=["CPUExecutionProvider"])
    y_pred: list[int] = []
    tactic_probs: list[list[float]] = []
    for start in range(0, len(labels), 16):
        stop = min(start + 16, len(labels))
        feeds = {
            "input_ids": tensors[0][start:stop].numpy().astype(np.int64),
            "attention_mask": tensors[1][start:stop].numpy().astype(np.int64),
            "token_type_ids": tensors[2][start:stop].numpy().astype(np.int64),
        }
        risk_output, tactic_output = session.run(["risk_logits", "tactic_logits"], feeds)
        y_pred.extend(np.asarray(risk_output).argmax(axis=1).tolist())
        tactic_probs.extend((1.0 / (1.0 + np.exp(-np.asarray(tactic_output)))).tolist())
    result = classification_metrics(labels, y_pred)
    if tactics_true:
        result["tactic_metrics"] = tactic_metrics(tactics_true, tactic_probs)
    return result


class ContextClassifier(torch.nn.Module):
    def __init__(self):
        super().__init__()
        self.encoder = AutoModel.from_pretrained(
            str(BASE), add_pooling_layer=False, use_safetensors=True, local_files_only=True)
        hidden = int(self.encoder.config.hidden_size)
        self.dropout = torch.nn.Dropout(0.1)
        self.risk_head = torch.nn.Linear(hidden, len(LABELS))
        self.tactic_head = torch.nn.Linear(hidden, len(TACTICS))

    def forward(self, input_ids, attention_mask, token_type_ids):
        encoded = self.encoder(input_ids=input_ids, attention_mask=attention_mask,
                               token_type_ids=token_type_ids, return_dict=True)
        pooled = self.dropout(encoded.last_hidden_state[:, 0, :])
        return self.risk_head(pooled), self.tactic_head(pooled)


def main() -> int:
    started = time.time()
    set_seed(SEED)
    torch.set_num_threads(min(4, os.cpu_count() or 1))
    ARTIFACTS.mkdir(parents=True, exist_ok=True)
    records = read_train_rows()
    train_idx, val_idx, test_idx = group_split(records)
    y = [r["label"] for r in records]
    split_counts = {name: dict(Counter(LABELS[y[i]] for i in idx))
                    for name, idx in (("train", train_idx), ("validation", val_idx), ("test", test_idx))}
    print(f"Dataset rows: {len(records)} Russian synthetic messages; grouped splits: {split_counts}", flush=True)
    baseline = tfidf_baseline(records, train_idx, test_idx)
    print(json.dumps({"tfidf_baseline_test_macro_f1": baseline["macro_f1"],
                      "tfidf_baseline_dangerous_recall": baseline["dangerous_precision_recall_f1"]["recall"]}), flush=True)

    tokenizer = AutoTokenizer.from_pretrained(str(BASE), use_fast=True, local_files_only=True)
    model = ContextClassifier()
    device = torch.device("cuda" if torch.cuda.is_available() else "cpu")
    model.to(device)
    train_loader = make_loader(tokenizer, records, train_idx, batch_size=8, shuffle=True)
    val_loader = make_loader(tokenizer, records, val_idx, batch_size=16, shuffle=False)
    test_loader = make_loader(tokenizer, records, test_idx, batch_size=16, shuffle=False)
    counts = np.bincount(np.asarray(y)[train_idx], minlength=len(LABELS)).astype(np.float32)
    class_weights = torch.tensor(1.0 / np.sqrt(np.maximum(counts, 1)), dtype=torch.float32, device=device)
    class_weights = class_weights / class_weights.mean()
    tactic_counts = np.asarray([r["tactics"] for r in records], dtype=np.float32)[train_idx].sum(axis=0)
    tactic_pos_weight = np.sqrt((len(train_idx) - tactic_counts) / np.maximum(tactic_counts, 1)).clip(1.0, 6.0)
    tactic_pos_weight_tensor = torch.tensor(tactic_pos_weight, dtype=torch.float32, device=device)
    risk_criterion = torch.nn.CrossEntropyLoss(weight=class_weights)
    tactic_criterion = torch.nn.BCEWithLogitsLoss(pos_weight=tactic_pos_weight_tensor)
    optimizer = torch.optim.AdamW(model.parameters(), lr=2.0e-5, weight_decay=0.01)
    epochs, best_f1, best_state, best_epoch = 4, -1.0, None, 0
    history: list[dict] = []
    for epoch in range(1, epochs + 1):
        model.train()
        losses = []
        for ids, mask, types, labels, tactic_labels in train_loader:
            optimizer.zero_grad(set_to_none=True)
            risk_logits, tactic_logits = model(input_ids=ids.to(device), attention_mask=mask.to(device),
                                                token_type_ids=types.to(device))
            loss = risk_criterion(risk_logits, labels.to(device)) + 0.5 * tactic_criterion(tactic_logits, tactic_labels.to(device))
            loss.backward()
            torch.nn.utils.clip_grad_norm_(model.parameters(), 1.0)
            optimizer.step()
            losses.append(float(loss.detach().cpu()))
        val_pred, _, val_tactic_probs = predict_torch(model, val_loader, device)
        val_true = [y[i] for i in val_idx]
        val_f1 = f1_score(val_true, val_pred, average="macro", zero_division=0)
        val_tactic_true = [records[i]["tactics"] for i in val_idx]
        val_tactic_f1 = tactic_metrics(val_tactic_true, val_tactic_probs)["macro_f1"]
        selection_score = float(val_f1 + 0.15 * val_tactic_f1)
        entry = {"epoch": epoch, "train_loss": float(np.mean(losses)), "val_risk_macro_f1": float(val_f1),
                 "val_tactic_macro_f1": float(val_tactic_f1), "checkpoint_selection_score": selection_score}
        history.append(entry)
        print(json.dumps(entry), flush=True)
        if selection_score > best_f1:
            best_f1, best_epoch = selection_score, epoch
            best_state = {k: v.detach().cpu().clone() for k, v in model.state_dict().items()}
        elif epoch - best_epoch >= 2:
            break
    if best_state is None:
        raise RuntimeError("Training failed to produce a checkpoint")
    model.load_state_dict(best_state)
    model.to(device)
    test_pred, _, test_tactic_probs = predict_torch(model, test_loader, device)
    test_true = [y[i] for i in test_idx]
    test_tactics_true = [records[i]["tactics"] for i in test_idx]

    fp32_path = ARTIFACTS / "contextshield-risk-fp32.onnx"
    int8_path = ARTIFACTS / "contextshield-risk-int8.onnx"
    sample_ids = torch.ones((1, MAX_LENGTH), dtype=torch.long, device=device)
    sample_mask = torch.ones_like(sample_ids)
    sample_types = torch.zeros_like(sample_ids)

    class ExportWrapper(torch.nn.Module):
        def __init__(self, inner):
            super().__init__()
            self.inner = inner

        def forward(self, input_ids, attention_mask, token_type_ids):
            return self.inner(input_ids=input_ids, attention_mask=attention_mask,
                              token_type_ids=token_type_ids)

    model.eval()
    torch.onnx.export(
        ExportWrapper(model), (sample_ids, sample_mask, sample_types), str(fp32_path),
        input_names=["input_ids", "attention_mask", "token_type_ids"], output_names=["risk_logits", "tactic_logits"],
        dynamic_axes={"input_ids": {0: "batch", 1: "sequence"},
                      "attention_mask": {0: "batch", 1: "sequence"},
                      "token_type_ids": {0: "batch", 1: "sequence"},
                      "risk_logits": {0: "batch"}, "tactic_logits": {0: "batch"}},
        opset_version=17, do_constant_folding=True, dynamo=False)
    quantize_dynamic(str(fp32_path), str(int8_path), weight_type=QuantType.QInt8,
                     per_channel=False, reduce_range=False)
    model_size_bytes = int8_path.stat().st_size
    external_tensors = load_external_eval(tokenizer)
    external = eval_onnx(int8_path, external_tensors[:3], external_tensors[3], external_tensors[4])
    test_tensors = encode(tokenizer, [records[i]["text"] for i in test_idx])
    int8_test = eval_onnx(int8_path, test_tensors, test_true, test_tactics_true)

    # Ensure quantized output is numerically usable before copying into the APK.
    options = ort.SessionOptions()
    options.intra_op_num_threads = min(4, os.cpu_count() or 1)
    session = ort.InferenceSession(str(int8_path), sess_options=options, providers=["CPUExecutionProvider"])
    smoke_ids, smoke_mask, smoke_types, _, _ = next(iter(test_loader))
    smoke_logits, smoke_tactics = session.run(["risk_logits", "tactic_logits"], {
        "input_ids": smoke_ids[:2].numpy().astype(np.int64),
        "attention_mask": smoke_mask[:2].numpy().astype(np.int64),
        "token_type_ids": smoke_types[:2].numpy().astype(np.int64),
    })
    if (smoke_logits.shape != (min(2, len(test_idx)), len(LABELS)) or
            smoke_tactics.shape != (min(2, len(test_idx)), len(TACTICS)) or
            not np.isfinite(smoke_logits).all() or not np.isfinite(smoke_tactics).all()):
        raise RuntimeError(f"Invalid quantized ONNX outputs: {smoke_logits.shape}, {smoke_tactics.shape}")

    dataset_hash = hashlib.sha256((RAW / "adaption-scam-train.jsonl").read_bytes()).hexdigest()
    metrics = {
        "model_id": "cointegrated/rubert-tiny2",
        "model_revision": MODEL_REVISION,
        "model_license": "MIT",
        "task": "joint 4-class Russian risk classifier and 13-label tactic classifier",
        "labels": LABELS,
        "tactics": TACTICS,
        "training_data": "dokster/adaption-scam-dataset-ru-en-v1; Russian-only synthetic subset",
        "training_data_revision": DATASET_REVISION,
        "training_data_license": "Apache-2.0",
        "training_data_sha256": dataset_hash,
        "rows_total_ru": len(records),
        "split_by_scenario_id": split_counts,
        "seed": SEED,
        "max_length": MAX_LENGTH,
        "best_epoch": best_epoch,
        "training_history": history,
        "tfidf_baseline_test": baseline,
        "heldout_synthetic_test": {"risk": classification_metrics(test_true, test_pred),
                                   "tactics": tactic_metrics(test_tactics_true, test_tactic_probs)},
        "heldout_int8_test": int8_test,
        "external_synthetic_boundary_eval_ru": external,
        "onnx_int8_bytes": model_size_bytes,
        "latency_claim": "Not measured on the target Android device; run on-device benchmark before any production claim.",
        "limitations": [
            "Dataset is synthetic and not representative of real Russian-language traffic.",
            "Model probability is a triage signal, not proof of fraud or identity.",
            "High-risk payment gating must continue to require evidence from explicit local rules/context.",
            "Do not use for real payment blocking until independently evaluated on consented, legally sourced traffic.",
        ],
        "elapsed_seconds": round(time.time() - started, 2),
    }
    (ARTIFACTS / "metrics.json").write_text(json.dumps(metrics, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    (ARTIFACTS / "label_map.json").write_text(json.dumps({"labels": LABELS, "tactics": TACTICS, "max_length": MAX_LENGTH}, indent=2) + "\n", encoding="utf-8")
    ANDROID_ASSETS.mkdir(parents=True, exist_ok=True)
    shutil.copy2(int8_path, ANDROID_ASSETS / "contextshield-risk-int8.onnx")
    shutil.copy2(BASE / "vocab.txt", ANDROID_ASSETS / "vocab.txt")
    (ANDROID_ASSETS / "model_metadata.json").write_text(
        json.dumps({"version": "ru-risk-multitask-1", "labels": LABELS, "tactics": TACTICS, "max_length": MAX_LENGTH,
                    "model_id": "cointegrated/rubert-tiny2", "model_revision": MODEL_REVISION,
                    "dataset_id": "dokster/adaption-scam-dataset-ru-en-v1", "dataset_revision": DATASET_REVISION,
                    "model_sha256": hashlib.sha256(int8_path.read_bytes()).hexdigest()}, indent=2) + "\n",
        encoding="utf-8")
    print(json.dumps({"complete": True, "best_epoch": best_epoch, "onnx_int8_bytes": model_size_bytes,
                      "android_assets": str(ANDROID_ASSETS), "heldout_risk_macro_f1": metrics["heldout_int8_test"]["macro_f1"],
                      "external_risk_macro_f1": external.get("macro_f1"),
                      "external_tactic_macro_f1": external.get("tactic_metrics", {}).get("macro_f1")}, ensure_ascii=False), flush=True)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
