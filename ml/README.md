# Локальное обучение Android-модели ContextShield

## Что именно обучается

`cointegrated/rubert-tiny2` (MIT) дообучается как multitask encoder на четыре класса риска (`safe`, `needs_check`, `suspicious`, `dangerous`) и тринадцать тактик (`urgency`, `secrecy`, `authority_impersonation`, `family_impersonation`, `emergency_story`, `credential_or_otp_request`, `payment_request`, `new_payee`, `remote_access_install`, `link_or_qr`, `investment_or_prize`, `threat_or_fear`, `evasion`). Это компактный encoder-классификатор, а не генерирующая LLaMA/чат-LLM. На Android он запускается локально через ONNX Runtime Mobile; текст уведомления не покидает устройство при обычном анализе.

Используем только русскую часть `dokster/adaption-scam-dataset-ru-en-v1` (Apache-2.0), 1 124 синтетических сообщения; revision зафиксирован в `dataset_registry.yaml`. Отдельная synthetic RU boundary-подвыборка используется только для оценки. Скрипт отдельно считает word/char TF-IDF baseline и сравнивает его с encoder; результат не предполагается заранее. Telegram-derived FraudLens исключён из-за условий первоисточника. Обученный результат является MVP baseline, а не подтверждённым production детектором.

## Запуск на этой машине

В каталоге `outputs`:

```powershell
python -m venv ml/.venv
ml/.venv/Scripts/python.exe -m pip install -r ml/requirements.txt
ml/.venv/Scripts/python.exe ml/download_assets.py
ml/.venv/Scripts/python.exe ml/train.py
```

`download_assets.py` скачивает model/dataset с зафиксированных Hugging Face commit revision и сверяет SHA-256 для крупных файлов. Затем `train.py` проверяет одинаковые scenario ID группами, делает детерминированные train/validation/test split без пересечения шаблонов, считает TF-IDF контрольную точку, выбирает multitask checkpoint по validation macro-F1, экспортирует и динамически квантует ONNX, сравнивает риск и тактики на независимом synthetic boundary set и копирует только INT8 runtime-модель, vocab и проверочную metadata в `android/app/src/main/assets/model/`.

Скрипт никогда не пишет сырой текст в метрики/лог. Сырые публичные файлы находятся в `ml/data/raw/` и исключены из Git. Не загружать их в приложение или публичный репозиторий.

## Ограничения

- Synthetic test metrics показывают лишь корректность пайплайна и baseline на распределении датасета.
- Их нельзя выдавать за точность на реальной переписке или использовать как банковскую гарантию.
- Системные правила, граф эпизода и источник данных должны оставаться отдельными от model score.
- Смена никнейма не объединяет и не наследует доверие; при отсутствии официального opaque account ID identity status — `UNKNOWN`.
- Перед включением в реальный финансовый поток нужны законно собранные consented примеры, независимая разметка и проверка ложных тревог.
