# Исследование STT-движков для opencode-mobile

Дата: 2026-11-25  
Автор: mobile-agent (сессия после apk-98)  
Статус: **research + план**, внедрение не начато.

## 0. Контекст и проблема

- Приложение: `opencode-mobile` (Android 16, arm64-v8a, OPPO CPH2747, мощный телефон).
- Текущий локальный STT: `whisper.cpp ggml-small` (`465 МБ`), движок в UI обозначен как `Локальные модели (whisper.cpp / sherpa-onnx)`.
- Жалобы Макса: Whisper **медленный** и **плохо понимает русскую речь**; на живых прогонах были галлюцинации/белиберда (`Новизпер СПП`, `весь прсп`).
- При этом apk-98 закрыл именно UI-залипание Whisper: `whisperRecorder` в `ChatOverlay.kt` переведён в `remember { mutableStateOf(...) }`. Живой прогон после фикса нормальный: `engine=whisper`, `model=small`, `86400` сэмплов (~5.4 с), RMS живой, `загрузка 0 мс, проход 2078 мс, звук 4560 мс, rtf 0,46`, результат: `Проверка связи 1-2-3. Это Виспер СПП. Движок.`.
- Отдельно проверен system Android Google STT: apk-97 чинил `ERROR_CLIENT (5)` (MIC conflict `MicLevelMeter` vs `SpeechRecognizer`); apk-98 системный путь не менял. По словам Макса после apk-98 залипания нет ни в Whisper, ни в Android Google, но качество Whisper не устраивает.

## 1. Факты, проверенные live в этой сессии

| Факт | Доказательство | Статус |
|---|---|---|
| apk-98 установлен | `mobile_app_install_status` job `0f685569-8dd3-4405-b53a-bbb719c0bcd1` → `installed` | verified-live |
| apk-98 совпадает с артефактом | `verify-install.sh` → `match=yes`, sha256 `53729223e1d5cda458c74141517acb04122d3c556028064f06333dd2fca67d79`, 263280513 б | verified-live |
| Whisper после apk-98 не залипает | `logcat -d --pid=$(pidof org.opencode.mobile.debug) \| grep VOICE` показал полный цикл start→stop→RMS→transcribe→результат | verified-live |
| Модель small на месте | `/data/data/org.opencode.mobile.debug/files/models/ggml-small.bin` = 465M, `.sha256` совпадает с фактическим sha256 | verified-live |
| Запись PCM пишется | `/data/data/org.opencode.mobile.debug/cache/rec.pcm` обновлялся (128K/130560B ранее, 172800B в успешном прогоне) | verified-live |
| Качество Whisper проблемное | Пользовательские примеры: `Голосового распознавания. Новизпер СПП`, `Проверка связи раз, два, три. Это голосовое распознавание на весь прсп.` | user-reported |

## 2. Текущий STT-стек (по коду GitHub/main, снимкам и кэшу)

### UI / настройки
- `app/src/main/java/org/opencode/mobile/ui/chat/VoiceSettings.kt`
  - `stt_engine`: `system | whisper`; миграция `ncnn -> whisper`.
  - `stt_model_whisper`: читается из prefs, дефолт `SttModels.DEFAULT_ID`.
  - Ограничение: UI сейчас модельный выбор фактически привязан к whisper-модели (`whisperModelId`), а не к общему каталогу движков/моделей.
- `app/src/main/java/org/opencode/mobile/ui/chat/VoicePanels.kt`
  - Панель STT: 2 движка — `Системный Android (Google)` и `Локальные модели (whisper.cpp / sherpa-onnx)`.
  - `SttModelPicker` показывается только при `WHISPER`.
- `app/src/main/java/org/opencode/mobile/ui/SttModelPicker.kt`
  - Нельзя выбрать модель, которой нет на диске. Показывает `скачана/не скачана/не установлена`, отправляет в Диагностику.

### Каталог и загрузка моделей
- `app/src/main/java/org/opencode/mobile/stt/SttModels.kt`
  - Каталог сейчас: `ggml-small.bin` (whisper.cpp) + `parakeet-tdt-0.6b-v3` как `manual=true`, `flatFileName=null`, `fetch` no-op, файлы ожидаются в `external/parakeet`.
  - `parakeet` уже упомянут как `sherpa-onnx nemo_transducer` в note, но нормальной кнопки скачивания нет.
- `app/src/main/java/org/opencode/mobile/stt/ModelDownloader.kt`
  - Скачивает только whisper small (`ggml-small.bin`) из HuggingFace `ggerganov/whisper.cpp`.
  - Есть SHA-256 manifest/sidecar `.sha256`, `.size`, `.part`, mutex, delete/cleanup.
  - Нет универсального загрузчика произвольных STT-моделей/движков.

### Распознавание
- `app/src/main/java/org/opencode/mobile/ui/chat/ChatOverlay.kt`
  - System STT: `SpeechRecognizer` + `onRmsChanged`; apk-97 убрал MIC conflict.
  - Whisper: запись PCM через `AudioRecorder`, стоп → сегментация/распознавание через `ChunkedTranscriber`/`WhisperTranscribeService` (по снимкам/логу).
- `app/src/main/java/org/opencode/mobile/stt/WhisperTranscribeService.kt`
  - Foreground service, очереди задач, whisper.cpp через JNI (`WhisperContext`), есть ветка Parakeet/sherpa-onnx (`OfflineRecognizer`, `OfflineTransducerModelConfig`) — задел есть, но продуктовый UX/скачивание не доведены.
- `app/src/main/java/org/opencode/mobile/stt/ChunkedTranscriber.kt` + `SpeechSegmenter.kt`
  - VAD-сегментация, склейка, антигаллюцинационная подготовка.

### Диагностика
- `app/src/main/java/org/opencode/mobile/ui/DiagnosticsScreen.kt`
  - Большой экран со снапшотом storage/engine/model/TTS/GGUF/core/runtime.
  - Есть паттерны загрузок: `EngineDownloadState`, `GgmlDownloadState`, `GgufDownloadState`, `TtsDownloadState`.
  - Для STT сейчас фактически заточено под whisper ggml small/turbo-историю в UI (нужно проверить актуальный main на предмет точных секций скачивания).

## 3. Кандидаты (сводка ресёрча)

> Важно: цифры WER/RTF из сабагентов и веб-сводок — **ориентиры**, пока не подтверждены по актуальным карточкам модели/рантайма и не замерены на нашем устройстве. Перед внедрением нужен факт-чек: точный repo, лицензия, формат, размер, язык, Android arm64 runtime, пример интеграции.

| Кандидат | Что это | Русский | On-device Android arm64 | Streaming | Статус для нас |
|---|---|---|---|---|---|
| **GigaAM v3 E2E RNNT** (Сбер) | Современный Conformer/RNNT, семейство GigaAM v3 | Главный плюс — сильный русский | Вероятный путь: ONNX + sherpa-onnx/ONNX Runtime; GGUF-варианты упоминались, но надо верифицировать | Да (RNNT) | **Приоритет №1** по запросу Макса и по качеству RU |
| **GigaAM v3 CTC** | CTC вариант | Обычно чуть хуже RNNT, но проще | ONNX/sherpa-onnx проще, чем RNNT | Ограниченно/зависит от реализации | Запасной, если RNNT тяжёл/сложен |
| **NVIDIA Parakeet (TDT 0.6B v3 / др.)** | Мультиязычный Conformer | Хороший, но обычно не лучше GigaAM именно на чистом RU | sherpa-onnx уже упоминался в коде/сервисе; GGUF есть в экосистеме | Зависит от варианта | **Приоритет №2 / быстрый контроль** |
| **SenseVoice Small** | FunASR/SenseVoice, мультиязычный | Нормальный, но RU не главный фокус | sherpa-onnx/ONNX реально | Частично | Контрольный кандидат |
| **Whisper large-v3-turbo / medium / distil** | Whisper семейство | Лучше small, но RU часто уступает специализированным RU моделям | whisper.cpp/sherpa-onnx | frame/chunk, не истинный RNNT | Бенчмарк, не основной фаворит |
| **Vosk / Silero / Moonshine** | Лёгкие/старые/EN-фокус | Обычно компромисс по качеству RU | Проще, но не цель | по-разному | Не приоритет |

## 4. Что нужно подтвердить перед реализацией (fact-check)

1. **GigaAM v3 E2E RNNT**: точное имя HuggingFace repo/model card, лицензия, файлы весов, формат (ONNX/safetensors/GGUF), размер, поддержка русского, пример запуска на Android/arm64.
2. **sherpa-onnx совместимость**: есть ли готовые sherpa-onnx артефакты/конфиг для выбранного GigaAM варианта; если нет — какой минимальный путь (ONNX Runtime Mobile, MNN, TFLite, execuTorch).
3. **Parakeet**: какой именно чекпойнт уже ближе всего к нашему `WhisperTranscribeService` (nemo_transducer ONNX int8?) и откуда его легально/стабильно скачивать.
4. **Лицензии**: MIT/Apache/CC-BY/other — можно ли паковать/скачивать в debug/release.
5. **Размер/хранилище**: модель, доп. .so, кэш, место на `/data` и external files.

## 5. План реализации

### Фаза A — Инвентаризация и дизайн (сначала)
- [ ] Снять актуальные исходники с GitHub/main по STT/UI: `ChatOverlay`, `VoiceSettings`, `VoicePanels`, `SttModelPicker`, `SttModels`, `ModelDownloader`, `WhisperTranscribeService`, `ChunkedTranscriber`, `DiagnosticsScreen`, `EngineStore/EngineCatalog` при наличии.
- [ ] Зафиксировать текущий контракт prefs: `stt_engine`, `stt_model_whisper`, ключи диагностики.
- [ ] Спроектировать общий каталог STT:
  - `engine`: `system | whispercpp | sherpa_onnx` (или совместимый маппинг со старым `whisper`).
  - `modelId`: модель внутри движка.
  - `source`: builtin/downloaded/manual.
  - `status`: not_installed/downloading/ready/corrupt/unsupported.
  - `size`, `sha256/manifest`, `languages`, `notes`, `recommendedForRu`.

### Фаза B — UX: кнопки скачивания и выбор движка
- [ ] **Меню голосового распознавания** (`VoicePanels`/picker): показывать движки и модели, которые можно реально протестировать; для неустановленных — понятный статус и переход в диагностику/кнопка скачивания.
- [ ] **Диагностика** (`DiagnosticsScreen`): секция STT-движков/моделей с кнопками Скачать/Удалить/Проверить, прогрессом, ошибкой, размером, свободным местом.
- [ ] Не ломать старые prefs: `whisper` должен открывать старую модель small без миграционного шока.
- [ ] System STT оставить как fallback.

### Фаза C — Загрузчик и хранилище
- [ ] Обобщить загрузку: не только `ggml-small.bin`, но и наборы файлов (Parakeet/SenseVoice/GigaAM ONNX).
- [ ] Для multi-file моделей: manifest.json со списком файлов, sha256, размером, версией.
- [ ] Атомарная установка: `.part`/tmp dir → verify → rename/swap.
- [ ] Очистка битых/старых артефактов; учёт места до скачивания.

### Фаза D — Runtime интеграция (по очереди)
- [ ] **D1. GigaAM v3 E2E RNNT** — первый POC, если подтвердится on-device формат. Цель: русский dictation, приемлемый RTF, нет галлюцинаций на коротких фразах.
- [ ] **D2. Parakeet** — довести существующий sherpa-onnx задел до продуктового состояния (скачивание, выбор, диагностика).
- [ ] **D3. SenseVoice Small** — контрольный мультиязычный.
- [ ] **D4. Whisper turbo/medium** — только как опциональный бенчмарк, если не раздувает APK/UX.

### Фаза E — Бенч на OPPO CPH2747
- [ ] Фиксированный набор фраз: короткие команды, длинная русская фраза, имена/термины, шумный фон, быстрая речь.
- [ ] Метрики: итоговый текст, WER/CER ручной, `rtf`, время загрузки модели, время первого результата, peak RSS/память (по возможности), галлюцинации на тишине, пустые ответы.
- [ ] Логировать в `VOICE`/diag с engine+model+версией.
- [ ] Сравнивать с текущим `ggml-small`.

### Фаза F — Релизный ритуал
- [ ] Правки в коде → проверка локальными тестами, где есть.
- [ ] Память: ЖУРНАЛ/СОСТОЯНИЕ/README при необходимости.
- [ ] APK только через GitHub: push → Release APK workflow → watch → download → `mobile_app_install` → тап Макса → `verify-install.sh`.
- [ ] Не ставить до зелёного CI. Не использовать adb install как основной путь.

## 6. Риски

- **Неподтверждённый формат GigaAM для mobile**: в паблике легко спутать PyTorch/ONNX/GGUF/v3/v2/e2e-rnnt/ctc. Нельзя кодить под слух.
- **Лицензия/распространение**: скачивание в рантайме обычно безопаснее, чем bundling, но лицензию всё равно проверить.
- **Размер**: крупные модели могут не влезть в `/data`; нужен выбор хранилища и честный UI свободного места.
- **RAM/thermal**: на мощном телефоне может работать, но первый прогон/долгая запись — отдельный риск.
- **Регрессия текущего STT**: system и whisper small должны остаться рабочими.
- **Сложность multi-file sherpa-onnx моделей**: нужен надёжный manifest и проверка целостности всех файлов.

## 7. Ближайший практический шаг

1. Подтвердить реальные артефакты **GigaAM v3 E2E RNNT** и **Parakeet** для Android arm64/sherpa-onnx.
2. Осмотреть актуальный main на GitHub: где именно в `DiagnosticsScreen` и `VoicePanels` лучше всего добавить новые движки/кнопки скачивания.
3. Сделать первый продуктовый срез: каталог движков + кнопки скачивания/статус в Диагностике + выбор в меню голосового распознавания + реальная установка хотя бы одного нового движка (GigaAM, если подтвердится; иначе Parakeet как первый рабочий sherpa-onnx кандидат).
4. Собрать APK, установить по ритуалу, дать Максу живой A/B тест.

## 8. Подтверждённые артефакты (фактчек 2026-11-25)

### GigaAM v3 E2E RNNT (приоритет №1)
- HF: `Alexxerm/gigaam-v3-e2e-rnnt-sherpa-onnx` (MIT, ru, sherpa-onnx). Источник модели: `ai-sage/GigaAM-v3` (MIT, ru+en).
- Файлы: `encoder.int8.onnx`, `decoder.onnx`, `joiner.onnx`, `tokens.txt` (+config.yaml). Общий размер ~226 MB.
- URL: `https://huggingface.co/Alexxerm/gigaam-v3-e2e-rnnt-sherpa-onnx/resolve/main/<file>`
- sherpa-onnx: `model_type=nemo_transducer`, `feature_dim=64`, `sample_rate=16000`, `decoding_method=greedy_search`, `provider=cpu`, `num_threads=2-4`.
- Ограничения: offline RNNT, нужен VAD/сегментация для длинных записей (наш ChunkedTranscriber уже есть), RAM ~230MB+runtime.
- Не подтверждено: точные per-file sha256 на HF, качество на нашем OPPO/нашем sherpa-onnx jni, поведение на длинных RU диктовках.

### Parakeet TDT 0.6B v3 int8 (приоритет №2, контроль/быстрый sherpa-onnx путь)
- HF: `csukuangfj/sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8` (CC-BY-4.0, 25 языков incl. ru).
- Файлы: `encoder.int8.onnx`, `decoder.int8.onnx`, `joiner.int8.onnx`, `tokens.txt`. ~1.34 GB.
- URL: `https://huggingface.co/csukuangfj/sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8/resolve/main/<file>`
- sherpa-onnx: `nemo_transducer`, `feature_dim=80`, `sample_rate=16000`, greedy, cpu. Длинные аудио резать по тишине.
- В коде уже есть Parakeet manual-задел, но UI/скачивание не доведены.

### SenseVoice small int8 (приоритет №3, мультиязычный контроль)
- HF канон: `csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17`; зеркало: `newbeeforever/sensevoice-small-sherpa-onnx`.
- Файлы: `model.int8.onnx`, `tokens.txt`. ~239 MB.
- URL: `https://huggingface.co/csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17/resolve/main/<file>`
- sherpa-onnx: `model_type=sense_voice` (в Kotlin API обычно `OfflineSenseVoiceModelConfig`), `feature_dim=80`, 16kHz. Не RNNT.
- Риск: другой API-конфиг в нашей версии sherpa-onnx jni; надо проверить наличие `OfflineSenseVoiceModelConfig` перед включением.

## 9. Контракт реализации (для делегирования)

### Цели первого среза
1. Каталог STT-моделей становится единым источником правды: whisper ggml + sherpa-onnx multi-file модели.
2. В Диагностике появляются кнопки Скачать/Повторить/Удалить и прогресс для GigaAM RNNT, Parakeet, SenseVoice.
3. В меню голосового распознавания можно выбрать движок/модель, включая новые, с понятным статусом "не скачана".
4. Runtime: GigaAM RNNT и Parakeet работают через sherpa-onnx OfflineRecognizer; SenseVoice - только если Kotlin API доступен, иначе помечается как "ожидает поддержку рантайма", но UI/скачивание уже есть.
5. Не ломать `system` и `whisper small`.

### Пакеты
- **P1 Каталог+downloader+prefs**: `SttModels.kt`, `ModelDownloader.kt`, `VoiceSettings.kt`, `SttModelPicker.kt` (только контракт данных/статусов). 
- **P2 Runtime**: `WhisperTranscribeService.kt`, `ChunkedTranscriber.kt` (если нужно), `ChatOverlay.kt` dispatch.
- **P3 UI диагностики/меню**: `DiagnosticsScreen.kt`, `VoicePanels.kt`, `SttModelPicker.kt` визуал, строки.
- **P4 Audit+сборка**: `audit`, локальные проверки где есть, GitHub Release APK, install/verify, живой A/B.

### Миграция prefs
- `stt_engine`: оставить `system|whisper`; `whisper` трактовать как локальный STT движок-кошелёк. Внутри локальных моделей различать runtime по модели каталога (whisper.cpp ggml vs sherpa-onnx transducer/sense-voice).
- Новое поле опционально: `stt_model_local`/`stt_local_model` или переиспользовать `stt_model_whisper` с миграцией имени. Нельзя терять уже выбранный `small`.
- `VoiceEngine.knownModel()` должен принимать все id из нового каталога.

### Multi-file download контракт
- Модель описывается манифестом: `id`, `dirName`, `files:[{name,url,size,sha256?}]`, `engineKind`.
- Скачивание в `filesDir/stt/<id>/` атомарно: `<file>.part` -> verify size/hash/exists -> rename. После всех файлов писать `.ready`.
- `isReady()` = все файлы на месте и размер совпал; sha256 если есть. Без полного хеша на UI-потоке.
- Удаление удаляет каталог модели целиком.

### Runtime контракт
- Whisper ggml: как сейчас через `WhisperContext`.
- Sherpa transducer (GigaAM RNNT, Parakeet): `OfflineRecognizerConfig(featConfig(featureDim=64/80, sampleRate=16000), modelConfig(transducer=..., tokens=..., modelType="nemo_transducer", provider="cpu", numThreads=2, debug=BuildConfig.DEBUG), decodingMethod="greedy_search")`.
- GigaAM dir/files: `encoder.int8.onnx`, `decoder.onnx`, `joiner.onnx`, `tokens.txt`, featureDim 64.
- Parakeet dir/files: `encoder.int8.onnx`, `decoder.int8.onnx`, `joiner.int8.onnx`, `tokens.txt`, featureDim 80.
- SenseVoice: только если API есть; иначе runtime вернёт понятную ошибку "SenseVoice пока не поддержан этим APK" и модель не будет отмечена как runnable.

## 10. Локальный smoke-bench 26.11.2026 (apk-109, записи Макса)

**Контекст:** Макс записал 9 эталонных RU-фраз (`my_voice_1..9.wav`, 16 kHz 16-bit stereo PCM) по скрипту, покрывающему даты/время, шипящие, Ъ/Ь, имена, ООО/аббревиатуры/латиницу, числительные, пунктуацию, ё/омонимию и длинную диктовку. Файлы прогнаны на установленном apk-109 debug (`org.opencode.mobile.debug`, commit `5b3eda4474c2499aa03635f77425ab1186b59a12`) через debug broadcast `org.opencode.mobile.STT_BENCH`.

**Метод:** один и тот же WAV на каждый движок; stereo -> mono для `WhisperTranscribeService`; WER/CER после нормализации (lower, `ё->е`, без пунктуации/кавычек). Сырые гипотезы в `MOBILE_AGENT/tmp/stt-bench/results.json`.

| Модель | Готово | Avg WER | Avg CER | Avg RTF | Avg pass ms | Примечание |
|---|---:|---:|---:|---:|---:|---|
| GigaAM v3 E2E RNNT | 9/9 | 15.8% | 11.4% | 0.052 | 725 | Быстрый RU; цифры вместо словесных дат/времени, потери в #5/#6/#8 |
| Parakeet TDT 0.6B v3 int8 | 9/9 | 13.0% | 6.7% | 0.050 | 685 | Лучший avg WER/CER на smoke, но #5 латиница/бренды провал (WER 64.7%) |
| Whisper small | 9/9 | 20.0% | 12.8% | 0.193 | 2544 | Медленнее ~3.7x; латиницу/бренд в #5 держит лучше Parakeet, но WER выше |
| SenseVoice small int8 | 0/9 | — | — | — | — | Runtime не подключён: `нужен sherpa-onnx sense_voice API` |

**Вывод:** на этом малом наборе Parakeet лидирует по среднему WER/CER и скорости, но опасен на латинице/аббревиатурах/брендах. GigaAM быстрый и приемлемый для обычного RU, но нормализует числа/даты. Whisper small — медленный базовый контроль. SenseVoice нельзя считать проверенным, пока runtime не реализован.

**Артефакты:** `docs/benchmarks/STT_БЕНЧМАРК_2026-11-26.md`, `MOBILE_AGENT/tmp/stt-bench/REPORT.md`, `MOBILE_AGENT/tmp/stt-bench/results.json`, `MOBILE_AGENT/tmp/stt-bench/summary.json`, `MOBILE_AGENT/tmp/stt-bench/reference.json`.
