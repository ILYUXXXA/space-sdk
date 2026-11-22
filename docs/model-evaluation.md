# ContextShield Android model card and evaluation snapshot

**Generated:** 2026-11-21
**Model:** `cointegrated/rubert-tiny2`, revision `e8ed3b0c8bbf4fb6984c3de043bf7d2f4e5969ae`, MIT
**Runtime:** ONNX Runtime Android `1.32.0`, dynamic INT8 quantization
**Model asset:** `android/app/src/main/assets/model/contextshield-risk-int8.onnx`, 29,268,978 bytes
**SHA-256:** `93defa709e362986c9b341505d07173317d19658c2a39d93ddf0a7ef25162f0e`

## What changed

The original model was trained on 1,124 synthetic Russian rows and was biased toward long, formal scam examples. The new training run adds 80 project-authored short conversational messages, balanced across `safe`, `needs_check`, `suspicious`, and `dangerous`. After grouped train/validation/test splitting, one deterministic surface variation is generated for each training row only. Variations cover informal wording, `ё/е`, punctuation/case/spacing, and sparse Latin look-alikes. No evaluation row is augmented or used for gradient updates.

This is a broader data-coverage change, not a claim that one phrase or one user message was “fixed.” The model remains a compact multi-task encoder, not a generative LLM. It predicts four risk classes and thirteen tactics. Notification text is analyzed locally on Android; the ordinary inference path makes no network call.

## Training data

- `dokster/adaption-scam-dataset-ru-en-v1`, pinned revision `d4141645c5d9c439143604e5e64f7d3651e82942`, Apache-2.0: 1,124 Russian synthetic/template-generated rows.
- `ml/data/curated_ru_train.jsonl`: 80 original project-authored synthetic short messages. This file contains no user messages.
- Total before training-only surface augmentation: 1,204 rows. Scenario groups are kept within one split.
- A separate 316-row Russian synthetic boundary set from the dataset source is evaluation-only; it is not used for gradient updates.
- `ml/data/challenge_suite_ru.jsonl`: 32 additional hand-authored short-message examples, 8 per class. It is also evaluation-only and is not part of training.

No Telegram-derived corpus is used. The available FraudLens card describes public Telegram-channel sourcing; we have not trained on it because the source terms need a separate rights review. A surfaced multilingual SpamShield dataset repository currently has no data files despite its dataset-card claims, so it is not a usable source. The license/quality/source review is recorded in `ml/dataset_registry.yaml`.

## Results

| Evaluation set | Rows | Accuracy | Risk macro-F1 | Dangerous precision | Dangerous recall | Tactic macro-F1 |
|---|---:|---:|---:|---:|---:|---:|
| Grouped held-out split of combined training sources | 193 | 0.927 | 0.881 | 0.966 | 0.966 | 0.796 |
| Separate Russian synthetic boundary set | 316 | 0.880 | 0.869 | 0.970 | 0.834 | 0.632 |
| Project-authored short challenge suite | 32 | 0.500 | 0.476 | — | — | — |

On the challenge suite, the prior bundled model scored 0.344 accuracy and 0.296 macro-F1; the candidate scores 0.500 and 0.476. At a diagnostic combined `suspicious + dangerous` score threshold of 0.70, challenge-set precision/recall changed from 0.778/0.875 to 0.833/0.938; one of eight safe examples is still over the threshold in both models. This is a small designed test, not an estimate of live false-alert rates.

The external boundary-set accuracy rose from 0.873 to 0.880 and macro-F1 from 0.862 to 0.869. Dangerous-class recall changed from 0.828 to 0.834. These are modest changes on synthetic data from the same broad authoring lineage. They do not establish real-world performance. Short-form challenge scores remain low, which is why the app must continue to treat output as triage evidence and why more independently labeled, rights-cleared Russian messages are needed.

## Product decision rules

- Model softmax confidence is not calibrated and is not a probability that a message is fraudulent or a person is a fraudster. The app therefore presents the selected text class and tactic signals without a numeric confidence percentage.
- The two model outputs are explained separately in the app: a four-class message head and a multi-label tactics head. Neither identifies the sender; the policy decision uses local rules and the recent episode score.
- A combined episode score of 30 or more recommends an independent check and leaves the demo transfer available. A score of 60 or more pauses only the demo transfer.
- A model class prediction alone never pauses the demo transfer. The local rule/context policy requires explicit signals for a pause.
- Strong financial pressure can trigger a non-blocking recommendation to verify the request independently.
- Explicit money demand plus an ultimatum and a physical-threat cue can pause the **demo** transfer. This is a safety rule for a direct threat, not a model claim.
- A money request tied to threats to disclose personal data gets a focused extortion warning. A single event does not establish the sender's identity or prove a crime.
- `safe` means no signal was recognized; it is not proof of safety. Hidden or absent notification text means insufficient evidence.
- Display names and nicknames do not identify a sender and never inherit trust. Continuity remains `UNKNOWN` without a stable official account identifier.
- The demo button is not connected to a bank and cannot move or block real funds.

## Limits and next data needed

Both the public training corpus and the added project examples are synthetic. The separate challenge suite is small and authored by the project; none of these metrics can be presented as accuracy on users' conversations. The data lacks coverage of realistic benign requests, current Russian-speaking messenger patterns, voice-to-text errors, code-switching, regional slang, and new scam scripts. The model still has noticeable short-message class errors.

The next defensible quality step is a consented, legally sourced, independently labeled Russian corpus, with hard negatives and sender/context metadata removed or pseudonymized. A bank or security reviewer should define the labels and costs of false alarms/misses. Keep a frozen test set from that corpus and publish class-wise precision/recall, calibration, and false alerts per thousand benign messages before considering any real transaction gating.

## Reproduction

Follow [`ml/README.md`](../ml/README.md). It includes the pinned source revisions and commands to regenerate the INT8 ONNX model and run both evaluation suites. The evaluation suite intentionally contains no screenshots or message copied from the user.
