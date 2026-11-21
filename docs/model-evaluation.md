# ContextShield Android model card and evaluation snapshot

**Generated:** 2026-11-21
**Model:** `cointegrated/rubert-tiny2`, revision `e8ed3b0c8bbf4fb6984c3de043bf7d2f4e5969ae`, MIT
**Runtime:** ONNX Runtime Android `1.32.0`, dynamic INT8 quantization
**ONNX asset:** `android/app/src/main/assets/model/contextshield-risk-int8.onnx` (~29.3 MB; SHA-256 `bbc117da6ec6c212b4e233842da3141da0a4d0b1c57cca02760d6f8ef8c6bcbe`)

## Training data and labels

The training source is revision `d4141645c5d9c439143604e5e64f7d3651e82942` of [`dokster/adaption-scam-dataset-ru-en-v1`](https://huggingface.co/datasets/dokster/adaption-scam-dataset-ru-en-v1), Apache-2.0. This is synthetic/template-generated data, not a collection of actual user messages. The pipeline uses only 1,124 Russian rows and divides them by scenario ID into train/validation/test sets. Raw data and the base checkpoint remain local and are excluded from Git; the deployable fine-tuned ONNX model is included.

The classifier has four message-level risk classes: `safe`, `needs_check`, `suspicious`, `dangerous`. A second head predicts 13 broadly grouped tactics drawn from dataset annotations: urgency, impersonation, phishing link, payment request, credential theft, verification needed, investment/prize, remote access, secrecy, job scam, marketplace scam, third party, and too-good-to-be-true. Explanations shown to the user still come from local evidence and policy, not generated prose.

## Observed synthetic metrics

| Evaluation set | Rows | Risk macro-F1 | Dangerous precision | Dangerous recall | Tactic macro-F1 |
|---|---:|---:|---:|---:|---:|
| Scenario-grouped held-out subset of the same synthetic corpus | 180 | 0.911 | 0.983 | 0.983 | 0.689 |
| Separate Russian synthetic boundary evaluation split | 316 | 0.862 | 0.970 | 0.828 | 0.460 |

These figures are reproducible pipeline evidence only. The boundary set is also synthetic and has the same broad authoring/data lineage; it is not an independent real-world benchmark. The recall change between the two sets is a concrete warning against interpreting one test score as expected field performance. Tactic metrics are especially limited by weak/noisy annotation mapping and should be read as experimental graph features.

## Decision controls and limitations

- The model is a local triage signal, not evidence that a person is a scammer.
- A model prediction by itself cannot pause the demo transfer. The local rule/context policy needs independent evidence.
- `safe` is not proof of safety; hidden/absent notification text means insufficient context.
- Display name/nickname does not identify a sender or inherit trust. Notification sender continuity remains `UNKNOWN` without an official stable platform ID.
- No latency or memory claim is made until measured on the target Xiaomi phone. The model file is ~29.3 MB; the final universal APK may be larger because it also packages ONNX Runtime native libraries.
- The demo button is not connected to a bank and does not transfer or block real money.
- Do not use this model for real transaction decisions until validated on consented, rights-cleared traffic and independently reviewed for false positives, false negatives, calibration, and Android device coverage.

To reproduce, follow [`ml/README.md`](../ml/README.md). The exact downloaded source revisions and SHA-256 values are recorded in `ml/dataset_registry.yaml` and `ml/artifacts/metrics.json` (the latter is local and git-ignored).
