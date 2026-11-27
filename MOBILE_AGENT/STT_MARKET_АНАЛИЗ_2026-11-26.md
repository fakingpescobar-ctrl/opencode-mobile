# Анализ рынка on-device STT для opencode-mobile

Дата: 2026-11-26 (обновлено после повторной попытки websearch + ввод Макса)  
Статус: исследовательский отчёт (без нового APK/CI)  
Устройство-цель: OPPO CPH2747, Android 16, arm64-v8a, Snapdragon-class 8-ядерный SoC, ~15 GB RAM, текущий APK с sherpa-onnx/whisper.cpp.  
Критерий Макса: **обязательная поддержка русского языка**. Латентность распознавания **>3-5 с неприемлема**, **<=3 с приемлема** для короткой/средней голосовой диктовки.

## 1. Источники и границы доказательности

### Проверено локально / на устройстве
- `MOBILE_AGENT/STT_БЕНЧМАРК_2026-11-26.md` — smoke-bench 26.11.2026, apk-109, 9 RU WAV Макса, один и тот же файл на движок.
- `MOBILE_AGENT/tmp/stt-bench/summary.json`, `results.json` — сырые метрики.
- Живой runtime apk-111 (`c166cbf`, run `45763643850`, sha256 `9b7090cb...`, `verify-install.sh match=yes`): SenseVoice исполнился через `STT_BENCH`, но модель `sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17` дала RU-мусор (`'As the  Z.'`, RTF 0.02) — ограничение модели, не APK.
- Каталог APK: `SttModels.kt` содержит Whisper small, GigaAM v3 E2E RNNT, Parakeet TDT 0.6B v3 int8, SenseVoice small int8.

### Проверено официальными/первичными источниками (webfetch/curl/raw, 26.11.2026)
- GigaAM GitHub README (`salute-developers/GigaAM`): v3 включает `v3_ctc/rnnt/e2e_ctc/e2e_rnnt`, 240M, 700k h pretrain / 4k h ASR, MIT; заявлено ~30% WER reduction на новых доменах и 70:30 win vs Whisper-large-v3 в side-by-side e2e; поддержка ONNX export, word timestamps, longform через VAD.
- GigaAM-v3 HF: https://huggingface.co/ai-sage/GigaAM-v3 — MIT, ru+en, `ssl/ctc/rnnt/e2e_ctc/e2e_rnnt`.
- GigaAM v3 e2e RNNT sherpa-onnx pkg: https://huggingface.co/Alexxerm/gigaam-v3-e2e-rnnt-sherpa-onnx — MIT, encoder.int8/decoder/joiner/tokens, `nemo_transducer`, сегментация 25-30s; авторский замер Galaxy S25 Ultra CPU 2 threads RTF 0.029.
- sherpa-onnx T-one page: https://k2-fsa.github.io/sherpa/onnx/pretrained_models/online-ctc/t-one-ctc-models.html — `sherpa-onnx-streaming-t-one-russian-2025-09-08`, Russian, 8kHz input, streaming, model.onnx ~138MB, пример RTF=0.32 на 6.4s.
- sherpa-onnx pretrained index: подтверждает NeMo transducer/CTC GigaAM RU, Parakeet TDT v3, Whisper, Zipformer RU, T-one RU CTC, SenseVoice.
- SenseVoice sherpa-onnx: https://k2-fsa.github.io/sherpa/onnx/sense-voice/index.html — языки zh, yue, en, ja, ko; RU не заявлен.
- Whisper model card: https://github.com/openai/whisper/blob/main/model-card.md — multilingual, но RU/скорость зависят от размера и рантайма.
- Qwen3-ASR-0.6B HF README: 30 языков + 22 китайских диалекта, Apache-2.0; точный список языков в сессии не подтверждён до RU-позиции (требует сверки карточки/конфига).

### Не подтверждено / ограничение среды
- **`websearch` в сессии отменялся**; живой обзор Open ASR Leaderboard, Reddit, русских форумов, свежих GitHub forks/issues не выполнен полноценно.
- Макс прислал внешний аналитический текст (ниже учитываю как **непроверенный внешний ввод**, не как факт устройства). Ценные гипотезы из него помечены `требует фактчека на CPH2747`.
- Open ASR Leaderboard даёт EN/long-form/multilingual данные, но **не даёт честного RU-on-device mobile CPU рейтинга** "из коробки"; русские результаты надо собирать отдельно.

## 2. Базовая линия на нашем устройстве (факт)

Smoke-набор: 9 коротких RU фраз, 1 диктор, тихая обстановка. Нормализация WER/CER скрывает пунктуацию/регистр/ё.

| Модель | APK/runtime | RU | Avg pass ms | Avg RTF | Avg WER | Avg CER | Статус <=3s |
|---|---|---:|---:|---:|---:|---:|---|
| GigaAM v3 E2E RNNT | sherpa-onnx transducer | да | 725 | 0.052 | 15.8% | 11.4% | **проходит** |
| Parakeet TDT 0.6B v3 int8 | sherpa-onnx transducer | заявлен | 685 | 0.050 | 13.0% | 6.7% | **скорость ок**, риск RU/латиница |
| Whisper small ggml | whisper.cpp | да | 2544 | 0.193 | 20.0% | 12.8% | **погранично** |
| SenseVoice small int8 | sherpa-onnx sense_voice | **нет** | 285 (16.1s аудио) | 0.02 | N/A | N/A | **быстро, но не RU** |

Вывод: текущий лидер по критерию "быстро + RU" — **GigaAM v3 E2E RNNT**.

## 3. Матрица кандидатов (RU обязателен, приоритет <=3s)

Оценка латентности: OPPO CPH2747, CPU, int8/fp16 где применимо, фраза 5-15s. Без нашего замера — `оценка`.

| Кандидат | Runtime / совместимость | RU | Размер | Ожид. латентность | Плюсы | Риски | Вердикт |
|---|---|---|---:|---|---|---|---|
| **GigaAM v3 E2E RNNT** | Уже в APK, sherpa-onnx NeMo transducer | **Да, RU-first** | ~226 MB | **0.5-1.5s** (факт 725ms avg) | Быстрый, пунктуация/нормализация, MIT, уже интегрирован | Сегментация 25-30s; числа нормализуются; имена/термины | **Основной сейчас** |
| **GigaAM v3 RNNT/CTC (plain)** | sherpa-onnx NeMo, возможен | **Да** | ~220-240 MB | **0.5-1.5s** оценка | Потенциально лучше дословность, без навязанной нормализации | Нужен корректный ONNX пакет/постпроцессинг/пунктуация | **A/B после e2e** |
| **T-one (voicekit-team)** | sherpa-onnx streaming CTC есть (`sherpa-onnx-streaming-t-one-russian-2025-09-08`) | **Да, RU** | ~138 MB model.onnx | **<1s** оценка, streaming 300ms chunks; пример RTF 0.32 | Очень лёгкий, нативный streaming, хорош для телефонии/команд | 8kHz ориентир; качество на чистой широкой речи может уступать GigaAM; пунктуация/латиница ограничены; нужна проверка лицензии/интеграции в APK | **Высокий приоритет фактчека** |
| **Vosk small RU / Zipformer RU (sherpa-onnx)** | sherpa-onnx zipformer/transducer, не интегрирован | **Да** | ~45-70 MB | **<1s** оценка | Очень малый, быстрый, оффлайн | Качество ниже современных Conformer/E2E; интеграция нового пути | **Low-end fallback кандидат** |
| **Parakeet TDT 0.6B v3 int8** | Уже в APK | Заявлен (вкл. ru) | ~670 MB | **0.5-1.2s** факт | Очень быстрый, хорошая средняя метрика | Не RU-first: ломает латиницу/аббревиатуры/бренды | **Контрольный, не RU-first** |
| **Whisper small ggml** | Уже в APK | Да | 465 MB | **2.0-3.5s** факт | Понятный fallback, multilingual | Медленный, хуже WER, галлюцинации | **Fallback** |
| **Whisper tiny/base ggml** | whisper.cpp уже есть | Да | 75/142 MB | **0.4-1.5s** оценка | Быстрее small, легко попробовать | Качество RU вероятно заметно хуже | **Только после smoke** |
| **Whisper medium/large-v3/turbo** | whisper.cpp тяжело | Да | 0.8-1.5+ GB | **>3-10s** вероятно | Качество выше на сложных случаях | Не укладывается в <=3s на mobile CPU | **Не приоритет** |
| **Qwen3-ASR 0.6B** | Не в APK; тяжёлый Python/vLLM/ONNX путь неготов | Заявлен multilingual, RU требует проверки | ~1.2 GB fp16 (оценка) | **0.2-1s на GPU/MLX**, на mobile CPU рискованно | Современный мультиязычный, устойчивость к шуму/музыке | Непонятная on-device Android CPU интеграция; размер/рантайм; RU в списке языков подтвердить | **Интересно, но высокий риск интеграции** |
| **SenseVoice small int8 (текущий)** | Уже в APK | **Нет** | 239 MB | **~0.3s** факт | Быстрый, события/эмоции в других сценариях | Не RU; текущая маркировка вводит в заблуждение | **Исключить из RU** |
| **Moonshine / Distil-Whisper** | Не в APK | Обычно EN-first / RU не доказан | малый-средний | возможно <3s | Скорость на edge | RU поддержка/качество сомнительны | **Не брать без RU доказательства** |

## 4. Что важно из внешнего анализа Макса (непроверено, но полезно как гипотезы)

Макс прислал сводку с акцентом на GigaAM v3, T-one, Vosk/Zipformer, Parakeet TDT v3, Qwen3-ASR, SenseVoice/OmniSenseVoice, оптимизированные Whisper. Статус:

| Гипотеза из ввода Макса | Статус | Комментарий |
|---|---|---|
| GigaAM v3 — лучший RU по скорость/качество | **частично подтверждено** | Локально на 9 WAV и по официальным материалам GigaAM подтверждается как сильный RU-first кандидат. Цифры WER из внешнего текста не переносим на наш девайс без воспроизведения. |
| T-one — лучший нативный streaming RU для слабого CPU | **требует фактчека** | В sherpa-onnx есть готовая streaming CTC модель; это реально перспективно. Нужен замер на CPH2747, WER на наших WAV, проверка 8kHz/16kHz пайплайна и лицензии. |
| Vosk 0.52/0.54 Zipformer очень малый и быстрый | **требует фактчека** | Направление правдоподобно, sherpa-onnx имеет zipformer RU/Vosk lineage. Нужен реальный APK/рантайм тест. |
| Parakeet TDT v3 хорош для RU+EN | **частично подтверждено** | Скорость подтверждена локально. Но на RU smoke был провал по латинице/аббревиатурам/брендам — для tech-диктовки осторожно. |
| Qwen3-ASR 0.6B быстрый и качественный | **требует фактчека** | Карточка HF обещает multilingual; on-device Android CPU <=3s и простая интеграция в наш sherpa/whisper.cpp стек не доказаны. |
| SenseVoice/OmniSenseVoice как RU вариант | **опровергнуто для текущего пакета** | Текущий установленный SenseVoice пакет — zh/en/ja/ko/yue, RU не поддержан. Другие форки — только после отдельного фактчека. |
| Moonshine нет нативного RU STT | **требует фактчека** | Внешнее утверждение; перед выводом сверить актуальную карточку/репозиторий. |

## 5. Практический вывод для продукта

1. **По умолчанию для RU оставить GigaAM v3 E2E RNNT.** Он уже в APK, укладывается в <=3s, даёт лучший практический баланс.
2. **SenseVoice small int8 убрать из RU-рекомендаций.** Runtime работает, но модель не RU. В `SttModels.kt`/UI нужно убрать/поменять формулировку "вкл. ru".
3. **Следующий реальный кандидат на интеграцию/замер — T-one streaming CTC.** Причина: есть готовый sherpa-onnx streaming пакет, малый размер, потенциально <1s и нативный streaming. Риск: домен/качество/8kHz/пунктуация.
4. **Parakeet оставить контрольным multilingual движком.** Не рекомендовать как RU-first; добавить постобработку латиницы/брендов перед любым продвижением.
5. **Whisper small оставить fallback.** Tiny/base можно быстро smoke-протестировать, но ожидать деградацию качества.
6. **Qwen3-ASR/Vosk/Silero/Distil/Moonshine** — только после подтверждения: RU в модели, on-device Android arm64 CPU runtime, лицензия, размер, RTF/WER на CPH2747.

## 6. План проверки (следующий цикл)

1. Расширить эталон до 30-50 RU WAV: 3+ диктора, шум, быстрый темп, имена, адреса, tech-термины, длинные 30-90s.
2. Прогнать одинаковые файлы: GigaAM v3 e2e, GigaAM plain (если пакет готов), Parakeet, Whisper small/tiny/base, T-one, Zipformer RU/Vosk.
3. Метрики: WER/CER raw+norm, пунктуация, числа/даты, латиница/бренды, first-token latency, final latency, p95 latency, RTF, RAM/thermal.
4. Для каждого кандидата фиксировать: repo/tag/commit, sha256, лицензия, runtime config, версия sherpa-onnx/whisper.cpp.
5. Любое внешнее claim (Reddit/арены/форки/блоги) принимать только после воспроизведения на CPH2747.

## 7. Итог

На 26.11.2026, по подтверждённым данным устройства и официальным источникам, **лучший быстрый on-device STT с обязательным RU для текущего APK — GigaAM v3 E2E RNNT**. **T-one** — самый перспективный следующий кандидат на фактчек (streaming, малый, sherpa-onnx готов). **Parakeet** быстрый, но не RU-first. **Whisper small** пограничный. **SenseVoice small int8** быстрый, но **не поддерживает русский** и должен быть убран из RU-выбора/маркировки.

## 9. Апдейт 26.11: уточнение устройства (ввод Макса, не замерено нами)

Макс уточнил целевое устройство: **OnePlus 15 / CPH2747**, Qualcomm **Snapdragon 8 Elite Gen 5 (SM8850-AC, 3nm, Oryon до 4.6 GHz, Hexagon NPU, 12/16 GB LPDDR5X, UFS 4.1, Android 16/OxygenOS 16)**.

**Статус:** это меняет ожидания по запасу производительности в лучшую сторону, но **цифры ниже — оценка/гипотеза, не замер на CPH2747**. На нашем текущем smoke (9 WAV, apk-109) GigaAM уже даёт 725 ms avg pass / RTF 0.052, так что <=3s для коротких фраз реалистично и с запасом.

| Кандидат | Оценка на SD8 Elite Gen 5 (CPU/int8, 5-10s фраза) | Комментарий | Статус |
|---|---:|---|---|
| GigaAM v3 E2E RNNT | ~0.25-0.7s | Уже лучший RU-first в нашем APK; на Oryon должен быть не хуже текущего замера, вероятно лучше при правильных threads | **приоритет №1 для RU** |
| Parakeet TDT 0.6B v3 int8 | ~0.3-0.8s | Быстрый multilingual; сохраняется риск RU+латиница/аббревиатуры/бренды | контрольный multilingual |
| T-one streaming CTC | ~0.03-0.15s на 300ms чанк / <1s итог | Нативный streaming через sherpa-onnx; надо проверить качество на широкой речи, 8kHz путь, пунктуацию | **приоритет фактчека для live-вывода** |
| Whisper small ggml | ~0.8-2.0s | На топ-чипе может укладываться комфортнее, но качество RU ниже GigaAM | fallback |
| Whisper large-v3-turbo/distilled (ggml/q5/q8 + QNN/OpenCL) | ~0.8-2.5s оценка | Возможно на этом SoC, но размер/энергия/интеграция сложнее; не в текущем стеке | **только отдельный R&D** |

### Практические заметки для CPH2747 (проверить перед внедрением)

- **Runtime:** sherpa-onnx AAR/JNI (у нас уже есть) — основной путь. Для Whisper — whisper.cpp ggml; QNN/NNAPI/OpenCL только после измерения реального выигрыша.
- **Threads:** на Oryon начать с `numThreads=2`, затем сравнить 1/2/4; 8 потоков на коротких фразах может ухудшить latency из-за синхронизации.
- **Audio input:** `AudioRecord` с `VOICE_RECOGNITION`, 16kHz, 16-bit PCM mono; не включать AGC/компрессию, если модель чувствительна к артефактам.
- **Фон/энергия:** OxygenOS/ColorOS 16 может троттлить фоновые сервисы; для always-listen/VAD — исключение из оптимизации батареи + закрепление сервиса.
- **Evidence gap:** websearch в среде недоступен; публичные RTF/latency числа для SD8 Elite Gen 5 не подтверждены нами. Любая цифра выше — только ориентир для плана замеров.

## 10. Апдейт 26.11 (apk-119, CPH2747, тот же 9-WAV smoke)

После установки apk-119 (`cc961af4...`, verify-install match=yes) прогнаны дополнительные GGML-модели на тех же 9 RU WAV Макса (mono 16k, debug `STT_BENCH`). Raw: `MOBILE_AGENT/tmp/stt-bench/results-apk119-extra-9wav.json`, summary: `MOBILE_AGENT/tmp/stt-bench/summary-apk119-extra-9wav.json`.

| Модель | WER | CER | RTF | avg pass ms | Вердикт по <=3s |
|---|---:|---:|---:|---:|---|
| Whisper tiny | 37.66% | 17.21% | 0.043 | 609 | быстро, качество неприемлемо низкое для RU |
| Whisper base | 22.39% | 13.48% | 0.052 | 671 | быстро, но хуже small/GigaAM, цифры/латиница/нормализация ломаются |
| Whisper small (apk-109 baseline) | 20.00% | 12.80% | 0.193 | 2544 | погранично |
| Podlodka turbo ggml q5_0 | 18.31% | 12.49% | 1.744 | 22464 | **не проходит**: 22.5s avg, RTF 1.74 |
| Podlodka turbo ggml q8_0 | 19.24% | 14.24% | 1.642 | 21258 | **не проходит**: 21.3s avg, RTF 1.64 |

**Вывод:** Podlodka GGML q5/q8 в текущем whisper.cpp-пути на CPH2747 не являются быстрыми on-device кандидатами под критерий <=3s. Качество на smoke лучше tiny/base, но латентность на порядок хуже допустимой. Не рекомендовать как RU-fast; возможен отдельный R&D по другому runtime/параметрам, но только с реальным замером.

## Замер Zipformer RU int8 2025-04-20 на apk-124 (CPH2747, 26.11)

- APK: apk-124, commit `381dd07f8bca`, Release run `45868126502`, sha256 `f4e46149af1cb58617987761bbda6f41ef8f0b541173b82e1c51cddfea8fb2f6`, verify-install match=yes.
- Runtime: sherpa-onnx `OfflineRecognizer` transducer, `modelType=""` (не nemo), featureDim 80, threads 2, greedy_search.
- Модель: `csukuangfj/sherpa-onnx-zipformer-ru-int8-2025-04-20` (alphacep/vosk-model-ru origin), файлы encoder.int8.onnx/decoder.onnx/joiner.int8.onnx/tokens.txt скачаны и проверены по sha256.
- Метод: те же 9 WAV Макса, 16kHz mono pcm16, debug `STT_BENCH`.

| id | WER | CER | RTF | pass ms | Комментарий |
|---|---:|---:|---:|---:|---|
| my_voice_1 | 0.000 | 0.000 | 0.03 | 440 | дата/время ок |
| my_voice_2 | 0.063 | 0.013 | 0.03 | 349 | речка→реки |
| my_voice_3 | 0.000 | 0.000 | 0.03 | 276 | ок |
| my_voice_4 | 0.143 | 0.013 | 0.03 | 330 | вьюжной→южной |
| my_voice_5 | 0.647 | 0.566 | 0.03 | 491 | API/MCP/Wi-Fi/OPPO CPH2747 сильно искажены |
| my_voice_6 | 0.071 | 0.013 | 0.03 | 313 | трёмстам→трёхстам |
| my_voice_7 | 0.000 | 0.000 | 0.03 | 289 | ок |
| my_voice_8 | 0.056 | 0.016 | 0.03 | 301 | ёж→ёжи/орфография |
| my_voice_9 | 0.000 | 0.000 | 0.03 | 469 | длинная речь ок |

**Итог:** avg WER 10.9%, avg CER 6.9%, avg RTF 0.029, avg pass 362ms, 9/9 done. Очень быстрый. Подходит как RU-fast кандидат, но **не безопаснее GigaAM** на аббревиатурах/латинице/именах устройств; default RU-first пока не менять.

## 11. Апдейт 27.11 (apk-131, CPH2747, T-one 9-WAV STT_BENCH)

- APK: apk-131, commit `290c4362e34f`, Release run `45939787641` (#127), sha256 `fc20b5c0bc2f4c75870b2105900bd814a421d7d01a4cc97cb76fd81a4c638e39`, verify-install match=yes.
- Модель: `t-one-ctc-ru-streaming` (sherpa-onnx OnlineRecognizer, Tone CTC RU), файлы на устройстве в `/files/stt/t-one-ctc-ru-streaming/model.int8.onnx` 144193702 B sha256 `5ded080e2a6c86ecc11bcb0902d77524eb3e8b0844cb0c0754347f5aafb4dabc`.
- Метод: 9 эталонных RU WAV Макса, 16kHz mono pcm16, debug `STT_BENCH`, engine `sherpa_online_ctc`.
- Raw: `MOBILE_AGENT/tmp/t-one-bench-log.json`.

| id | load ms | pass ms | audio ms | RTF | WER | CER | Комментарий |
|---|---:|---:|---:|---:|---:|---:|---|
| my_voice_1 | 1159 | 1417 | 16100 | 0.09 | 0.000 | 0.000 | дата/время ок |
| my_voice_2 | 0 | 660 | 12480 | 0.05 | 0.063 | 0.013 | речки→реки |
| my_voice_3 | 0 | 558 | 10320 | 0.05 | 0.000 | 0.000 | ок |
| my_voice_4 | 0 | 652 | 12500 | 0.05 | 0.143 | 0.013 | вьюжной→в южной |
| my_voice_5 | 0 | 1045 | 17640 | 0.06 | 0.706 | 0.513 | API/MCP/Wi-Fi/OPPO CPH2747 сильно искажены |
| my_voice_6 | 0 | 630 | 11580 | 0.05 | 0.071 | 0.013 | трёмстам→трестам |
| my_voice_7 | 0 | 593 | 10600 | 0.06 | 0.000 | 0.000 | ок |
| my_voice_8 | 0 | 613 | 11140 | 0.06 | 0.167 | 0.016 | у ели/ёж/ещё слиты/упрощены |
| my_voice_9 | 0 | 1064 | 17500 | 0.06 | 0.000 | 0.000 | длинная речь ок |

**Итог:** avg WER 12.77%, avg CER 6.31%, avg pass 803.6ms, RTF 0.05-0.09, 9/9 done. Cold load 1159ms только на первом файле, далее warm cache. 

**Вывод:** T-one подтверждён как очень быстрый RU streaming-кандидат (RTF ~0.06, комфортно <=3s). Обычная RU речь/длинные фразы хороши, но латиница/аббревиатуры/бренды/коды устройств — критическая слабость (voice_5). Не поднимаем выше GigaAM как RU-first default; подходит как экспериментальный fast mode, если продукт явно предупреждает про tech/latin токены.

## 12. Апдейт 27.11: chat STT picker (apk-131)

- Причина: на скриншоте Макса в chat STT picker были только tiny/base/small/podlodka q5, GigaAM пропал; стиль старый ANSI.
- Фикс: `SttModelPicker.kt` переделан под стиль Диагностики, показывает `SttModels.all`, статусы готовности/runtime, RU-бейдж, выбор только `ready && runnable`, скролл, анимации. `VoicePanels.kt` убрана лишняя внешняя коробка.
- Коммиты `c7cc3e4174c1`, `290c4362e34f`; apk-131 установлен и verify ok.
- Живой UI-smoke picker’а после тапа ещё не снимался uiautomator (панель перекрывает чат); нужна визуальная проверка Макса.

## 13. Апдейт 27.11: Silero VAD v6 live smoke FAIL на apk-131, фикс в apk-132

- **Smoke:** apk-131, CPH2747, короткий hold-to-speak Макса. Silero VAD v6 не активировался, сработал RMS fallback.
- **Logcat:** `W/SileroVAD: Silero VAD failed, fallback to RMS`, `java.lang.NoSuchMethodError: no non-static method "Lcom/k2fsa/sherpa/onnx/SpeechSegment;<init>(I[F)V"`. Стек: `Vad.front()` native -> `SileroVadSegmenter.drain/splitWithVad` -> `ChunkedTranscriber.transcribe`.
- **Причина:** bundled `libsherpa-onnx-jni.so` ожидал `SpeechSegment(int,float[])`, Java binding в APK — `SpeechSegment(long,float[])` (`app/src/main/java/org/opencode/mobile/tts/Vad.kt`). Модель `silero_vad.onnx` валидна (643854 B, sha256 `9e2449e1087496d8d4caba907f23e0bd3f78d91fa552479bb9c23ac09cbb1fd6`).
- **Фикс:** добавлен compat-конструктор `SpeechSegment(start:Int, samples:FloatArray): this(start.toLong(), samples)`. Commit `f18a85ee6dd147b54f8e6e79f1f8b8dc225ef024`, Release CI `45942888984` (#128) success, apk-132 `dist-opencode-mobile-132-f18a85e.apk` 263470377 B sha256 `8258a636a8336627491df3f1395d7aaf02f47234ba4b2a406799063d59d104ef`, install job `c3841935-6534-47fb-955b-b29fbf83f491` installed, `verify-install.sh match=yes`.
- **Smoke apk-132 (27.11, PASS):** `I/SileroVAD loaded .../silero_vad.onnx`, `segments=4 from 9s audio`, `D/CHUNKED VAD=silero-v6`; NoSuchMethodError/RMS fallback отсутствуют. GigaAM распознал 4 сегмента, RTF 0.03-0.05. Silero VAD v6 подтверждён в live chunked-пути.

## 14. Апдейт 27.11: Qwen3-ASR 0.6B int8 9-WAV на apk-132

- **Сборка:** apk-132, CPH2747, 16k mono, debug `STT_BENCH`, model `qwen3-asr-0.6b`, engine `sherpa_qwen3_asr`.
- **Cold/load:** first pass 2634ms load + 2786ms pass (модель на диске); последующие load 0ms (cached, force-stop недоступен).
- **Raw:** `MOBILE_AGENT/tmp/qwen3-06b-bench-apk132.json`, summary `MOBILE_AGENT/tmp/qwen3-06b-bench-apk132.md`.

| id | pass ms | RTF | WER | CER | Комментарий |
|---|---:|---:|---:|---:|---|
| my_voice_1 | 2527 | 0.16 | 62.5% | 61.5% | даты/числа форматированы иначе |
| my_voice_2 | 1832 | 0.15 | 18.8% | 3.7% | мелкие замены |
| my_voice_3 | 1774 | 0.17 | 6.3% | 4.3% | почти ок |
| my_voice_4 | 1817 | 0.15 | 28.6% | 7.8% | вьюжной/льняному |
| my_voice_5 | 3274 | 0.19 | 58.8% | 52.6% | API/Wi-Fi/OPPO транслитерация |
| my_voice_6 | 1829 | 0.16 | 7.1% | 2.6% | трёмстам |
| my_voice_7 | 2201 | 0.21 | 0.0% | 0.0% | ок |
| my_voice_8 | 2729 | 0.24 | 22.2% | 7.8% | ёж/ели/ещё/всё |
| my_voice_9 | 6962 | 0.40 | 16.1% | 5.1% | длинная речь, >3s, хвост деградировал |

**Итог:** avg pass 2567ms, RTF 0.20, avg WER 24.5%, avg CER 16.2%. <=3s: 6/9, >3s: 3/9 (max 6962ms).
**Вердикт:** **hide_experiment**. Хуже GigaAM (15.8% WER на тех же 9 WAV), латентность/качество нестабильны; оставлять только как опциональный эксперимент.

## 15. Апдейт 27.11: Vosk feasibility + backlog factcheck

- **Vosk 0.52/0.54:** реализуемо при условии добавления `libvosk.so` JNI arm64. Официальные RU модели: `vosk-model-ru-0.10/0.22/0.42` + `vosk-model-small-ru-0.22` (45 MB, Android/iOS/RPi). В коде упомянуты 0.54/0.52 — сверить перед wiring. Рекомендация: small-ru-0.22, ~3 инж. дня, Apache 2.0. Отчёт: `VOSK_FEASIBILITY_REPORT.md`.
- **Smart Turn v3.2:** блокер/отдельный трек. EOU/turn-taking, не STT; готового Android arm64 JNI/ONNX/AAR не подтверждено.
- **Qwen3-ForcedAligner:** не STT, блокер/отдельный трек. Нужен aligner pipeline.
- **onnx-asr:** потенциально реализуемо как отдельный ONNX backend/порт, не прямой APK dep.
- **Qwen3-ASR 1.7B:** отдельный трек/высокий риск; mobile-ready int8 ONNX/sherpa артефакт не подтверждён, RAM/latency/OOM риск.

*Caveat: Vosk/backlog factcheck сделаны субагентами; websearch был недоступен, ключевые официальные источники/версии перепроверить перед реализацией.*


## 16. Апдейт 27.11: apk-134 GigaAM 9-WAV регрессия после ChunkedTranscriber fix

- **Сборка:** apk-134 (`898fd621`, CI #130 success, sha256 `c397cd82...`, verify-install match=yes).
- **Фикс:** `ChunkedTranscriber.prepareAudio(samples, model)` добавляет 3s padding только для `WHISPER_GGML`/unknown; sherpa движки (GigaAM/Parakeet/T-one/Qwen3/SenseVoice) транскрибируются без лишней тишины.
- **Smoke:** GigaAM `STT_BENCH`, `sherpa_transducer`, те же 9 RU WAV. 9/9 завершены, ошибок/крашей нет.

| id | pass ms | RTF | WER |
|---|---:|---:|---:|
| my_voice_1 | 1019 | 0.06 | 54.2% |
| my_voice_2 | 750 | 0.06 | 6.3% |
| my_voice_3 | 435 | 0.04 | 0.0% |
| my_voice_4 | 795 | 0.06 | 14.3% |
| my_voice_5 | 1143 | 0.06 | 29.4% |
| my_voice_6 | 718 | 0.06 | 21.4% |
| my_voice_7 | 660 | 0.06 | 0.0% |
| my_voice_8 | 720 | 0.06 | 16.7% |
| my_voice_9 | 1163 | 0.07 | 0.0% |

**Итог:** avg WER 15.8%, avg pass 823ms, avg RTF 0.059. Качество совпало с базой (15.8% WER); слабые места прежние (#1 даты/числа, #5 аббр/латиница). STT_BENCH подтверждает отсутствие регрессии в sherpa decode-пути; **live hold-to-speak smoke (mic → VAD → ChunkedTranscriber → GigaAM)** ещё нужно подтвердить на устройстве.

## 17. Апдейт 27.11: apk-140/141 — chat picker показывает только ready-модели

- **apk-140** (`9188970f1f3d`, CI `45981587711` #136, success): `SttModels.kt` пометил `VOSK_RU_054`, `VOSK_RU_052`, `QWEN3_ASR_17B` как `hiddenFromPicker=true` (runtime недоступен/не подключён автоматически в APK).
- **apk-141** (`d00747dc0fd5`, CI `45982164592` #137, success, install `6a5377f7-74f7-484b-b236-b3d4157b0f9a`, `verify-install match=yes`, sha256 `c4df378edee1cd12f5af6a2c40776a6de39004d6c1f16edb842d6679455c95a3`): `SttModelPicker.kt` показывает только `catalogModels.filter { it.id in readyIds && it.runnable }`. Пустое состояние: «Скачай модель в Диагностике — после скачивания она появится здесь». Скачивание/установка остаётся в Диагностике.
- **Правило UX (Макс, 27.11):** пока модель не скачана в Диагностике, её карточка не должна появляться в chat picker.
