#!/usr/bin/env python3
"""Download pinned model/data inputs and verify SHA-256 before training."""
from __future__ import annotations

import hashlib
from pathlib import Path
from urllib.request import urlopen


ROOT = Path(__file__).resolve().parent
MODEL_REVISION = "e8ed3b0c8bbf4fb6984c3de043bf7d2f4e5969ae"
DATASET_REVISION = "d4141645c5d9c439143604e5e64f7d3651e82942"

FILES = [
    (f"https://huggingface.co/cointegrated/rubert-tiny2/resolve/{MODEL_REVISION}/model.safetensors",
     ROOT / "base-model" / "model.safetensors", "26ebb6db2a68593c54c74902d7a74f332da66297693f965cc9f1b0af4abf3894"),
    (f"https://huggingface.co/cointegrated/rubert-tiny2/resolve/{MODEL_REVISION}/config.json",
     ROOT / "base-model" / "config.json", "adee8b3e344bcb8379f44d0b3577c267d52881341e05d973c43e49974778dfff"),
    (f"https://huggingface.co/cointegrated/rubert-tiny2/resolve/{MODEL_REVISION}/vocab.txt",
     ROOT / "base-model" / "vocab.txt", "f056a69b097422652053bf87565c35543e5d81540ca4b7dddd28de4157a969e0"),
    (f"https://huggingface.co/cointegrated/rubert-tiny2/resolve/{MODEL_REVISION}/tokenizer_config.json",
     ROOT / "base-model" / "tokenizer_config.json", None),
    (f"https://huggingface.co/cointegrated/rubert-tiny2/resolve/{MODEL_REVISION}/special_tokens_map.json",
     ROOT / "base-model" / "special_tokens_map.json", None),
    (f"https://huggingface.co/datasets/dokster/adaption-scam-dataset-ru-en-v1/resolve/{DATASET_REVISION}/data/train_0.jsonl",
     ROOT / "data" / "raw" / "adaption-scam-train.jsonl", "e51ce12962b5bd41f48e05a37346e63d032e7e19522d4e721a5f36c0f6c56050"),
    (f"https://huggingface.co/datasets/dokster/adaption-scam-dataset-ru-en-v1/resolve/{DATASET_REVISION}/data/eval_ru_en.jsonl",
     ROOT / "data" / "raw" / "eval_ru_en.jsonl", "d6bcc57f14c83d628f12ba64f68f809cfca68d7fc2db726d1006832be45766c2"),
]


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def download(url: str, target: Path, expected_sha256: str | None) -> None:
    target.parent.mkdir(parents=True, exist_ok=True)
    if target.is_file():
        existing_sha = sha256(target)
        if expected_sha256 is None or existing_sha == expected_sha256:
            print(f"verified existing: {target.relative_to(ROOT)}")
            return
        target.unlink()
    partial = target.with_suffix(target.suffix + ".part")
    print(f"downloading: {target.relative_to(ROOT)}")
    try:
        with urlopen(url, timeout=60) as response, partial.open("wb") as output:
            while True:
                chunk = response.read(1024 * 1024)
                if not chunk:
                    break
                output.write(chunk)
        actual = sha256(partial)
        if expected_sha256 and actual != expected_sha256:
            raise ValueError(f"SHA-256 mismatch for {target.name}: {actual}")
        partial.replace(target)
    finally:
        if partial.exists():
            partial.unlink()


def main() -> None:
    for url, target, digest in FILES:
        download(url, target, digest)
    print("All pinned inputs downloaded and checksummed. Raw corpus and base checkpoint stay local.")


if __name__ == "__main__":
    main()
