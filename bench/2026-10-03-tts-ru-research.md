# 2026-10-03 — Русский TTS для Android on-device: что реально есть

Проверял для задачи «озвучка ответов с выключателем, русский обязателен».
Источники: k2-fsa/sherpa-onnx docs + release assets (`releases/tag/tts-models`), k2-fsa/ZipVoice README.

## 1. Kokoro — русского НЕТ

- `kokoro-multi-lang-v1_0` — 53 спикера, только en + zh. В доках прямая цитата:
  > "It is a multi-lingual model, but we only add English and Chinese support for it."
- `kokoro-multi-lang-v1_1` — 103 спикера, "Chinese + English".
- `kokoro-en-v0_19` — 11 спикеров, en.
- Префиксы голосов: `af/am/bf/bm/ef/em/ff/hf/hm/if/im/jf/jm/pf/pm` (en) + `zf*/zm*` (zh). Ни одного ru.
- Модель 310M (v1_0) / 330M (en v0_19), 24 kHz, есть int8-вариант v1_1.
- Вывод: если русский обязателен — Kokoro отпадает.

## 2. ZipVoice — тоже zh+en, и «123 мс» не подтверждается

- README: "Multi-lingual: support Chinese and English." Русского нет.
- «123M parameters» — это 123 **миллиона параметров**, не 123 мс. Замера «123 мс до первого аудио на Snapdragon 8 Gen 3» в README/доках нет — источник цифры не найден.
- В sherpa-onnx есть официальные пакеты: `sherpa-onnx-zipvoice-zh-en-emilia`, `sherpa-onnx-zipvoice-distill-int8-zh-en-emilia` + vocoder `vocos_24khz.onnx`. Kotlin-пример есть (`kotlin-api-examples/test_zipvoice_tts.kt`).
- Только **offline** (нужен `--reference-audio` + `--reference-text`, при несовпадении качество падает).
- Память: README §3.3 — текст режется на чанки по пунктуации и батчится, «almost constant memory usage», регулируется `--max-duration`. Утверждение «состояние O(T) на предложение» первоисточником не подтверждается.
- Русского нет → как движок озвучки не подходит.

## 3. Что реально есть с русским в sherpa-onnx

| Модель | Языки | Формат | Размер | Streaming |
|---|---|---|---|---|
| **SupertonicTTS 3** (`sherpa-onnx-supertonic-3-tts-int8-2026-05-11`) | **31, включая ru** (`--lang=ru`), мульти-спикер (`--sid`) | 4× int8 ONNX + `tts.json` + `unicode_indexer.bin` + `voice.bin` | ~инт8 пакет, уточнить | offline |
| `vits-piper-ru_RU-ruslan-medium` (+ `-int8`, `-fp16`) | ru | VITS ONNX | medium, есть int8 | offline |
| `vits-piper-ru_RU-irina-medium` (+ int8/fp16) | ru | VITS ONNX |同上 | offline |
| `vits-piper-ru_RU-dmitri-medium` (+ int8/fp16) | ru | VITS ONNX |同上 | offline |
| `vits-piper-ru_RU-denis-medium` (+ int8/fp16) | ru | VITS ONNX |同上 | offline |
| `vits-mms-rus` | ru | VITS (Meta MMS) | — | offline |

SupertonicTTS 3 — лучший кандидат: русский есть штатно, int8 (важно для телефона), Kotlin API
(`kotlin-api-examples/test_supertonic_tts.kt`), PR k2-fsa/sherpa-onnx#3605.

## 4. Чего нет

- **Streaming TTS** ни у одного из перечисленных ru-движков — только offline (сгенерировал всё предложение/абзац, потом отдай).
- Значит «озвучка с выключателем» = псевдостриминг: резать текст на предложения и синтезировать по одному. Отмена = сброс очереди несинтезированных кусков (плюс fade-out уже играющего, если он уже отдан в AudioTrack).

## 5. Замеры

Не сделаны. Стенда `bench/` в проекте нет, железа для TTS-прогона не подключено. Первый замер, который имеет смысл: SupertonicTTS 3 int8 `--lang=ru` на SM8850 — RTF + время до первого аудио по предложению.

Источники:
- https://k2-fsa.github.io/sherpa/onnx/tts/pretrained_models/kokoro.html
- https://k2-fsa.github.io/sherpa/onnx/tts/zipvoice.html
- https://k2-fsa.github.io/sherpa/onnx/tts/supertonic.html
- https://github.com/k2-fsa/ZipVoice
- https://github.com/k2-fsa/sherpa-onnx/releases/tag/tts-models
