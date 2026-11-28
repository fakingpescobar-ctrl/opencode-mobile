# Qwen3-TTS: гейт G6 — ширина стримингового чанка кодера 8→16 (CPH2747, 29.11.2026)

## Goal

Проверить, даёт ли увеличение максимальной ширины чанка `CodecDecode` с 8 до 16 кадров ≥10% улучшения RTF на стеке ACCEPT-ed G4 codec8 без изменения качества и без увеличения времени до первого PCM. Результат — формальный ACCEPT/DROP research-документом. APK, release CI и дефолтный Supertonic не меняются.

Макс дал согласие искать другие рычаги после того, как квантование кодера закрыто (G5a/G5b DROP). В отличие от квантования, этот гейт меняет не веса, а только размер батча в уже существующем статическом графе: сама арифметика та же, меняется число вызовов `ggml_backend_graph_compute` и число строк в `mul_mat`.

## Non-goals

- APK wiring, `release-apk.yml`, подпись, versionCode, теги `apk-*`, установка APK, смена дефолтного TTS.
- Повтор ISA/`GGML_CPU_ARM_ARCH` (G1d DROP), KleidiAI (G2 DROP), глобальных 8 потоков (G3 DROP), квант кодера (G5a/G5b DROP), Vulkan (много-переменный, отдельный план).
- Изменение модели,talker'а, числа потоков, расписания ранков, формата PCM и измерителя.
- `--max-batch` / конкурентные запросы: гейт про задержку одиночного запроса, не про throughput.

## Constraints

- Единственная переменная относительно G4 codec8 — `src/pipeline-codec.h:53`: `CODEC_STREAM_CLASSES` `4` → `5` (T=1<<cls ∈ {1,2,4,8,16}). Патч G4 codec8 (отдельный 8-поточный CPU-бэкенд кодера) сохраняется: stream16 собирается поверх codec8, не вместо него.
- Talker `qwen-talker-0.6b-base-Q4_K_M.gguf` sha `4b468ec7…`, codec `qwen-tokenizer-12hz-Q4_K_M.gguf` sha `cf3788b4…` — те же, что в G4/G5.
- Сборка research-only через расширение существующего `.github/workflows/build-qwentts-arm-codec8.yml` новым вариантом `stream16`; CMake-флаги, тулчейн Zig 0.13 musl aarch64, ggml `ef4555b`, qwentts `2edb35f` — байт-в-байт как в G4. `GGML_CPU_ARM_ARCH` не задаётся, `GGML_CPU_KLEIDIAI=OFF`.
- Проверка CMake-шага: `git diff --name-only` строго `{src/pipeline-codec.cpp, src/pipeline-codec.h}`; отсутствие маркера `G6 stream16` = fail. Любая другая правка = fail.
- Пороги наследуются от G4/G5a без изменений: ≥10% улучшения RTF med в `warm-short` (2), `warm-short-2` (10), `warm-medium`, `warm-long`; guard Talker/CodePredictor ≤10% регресса **на кадр**; cold-first PCM ≤796.4 мс; 28/28 HTTP 200, 0 crash/hang, s16le 24 kHz mono, peak >0.01.
- При дрейфе >10% между A1 и вариантом делать A2 и строить вердикт на обеих парах (урок G5a).
- Замер только device-side в фоне с done-сентинелом; `kill-server`/`start-server` запрещены; при обрыве кабеля — честно сказать Максу, не дёргать демон.
- Research-артефакты: `$DEVICE/files/qwen3-tts-arm-research/g6/`; APK и `MOBILE_AGENT/*` не править.

## Current behaviour

- `src/pipeline-tts.cpp:565`: `maxT = 1 << (CODEC_STREAM_CLASSES - 1)` = 8.
- `src/pipeline-tts.cpp:602-658` `tts_engine_codec_flush()`: collects pending rows, decodes greedy width `T` (8 → 4 → 2 → 1 depending on remainder), calls `pipeline_codec_decode_stream_batch`.
- `src/pipeline-tts.cpp:1375-1379`: `codec_target` начинается с 1 и удваивается до `maxT`; значит первый PCM приходит после кадра `T=1` и **не зависит** от увеличения maxT. Опасения по cold-first (было в оценке T=16 как «+0.64 с») снимаются структурой ранкона.
- `src/pipeline-codec.cpp:294,557`: проверка `sliding_window + T ≤ CODEC_STREAM_RING`; `sliding_window=72`, ring=128 → 72+16=88 ≤ 128, запас есть; T=32 тоже проходит (104), но в этом гейте не используется.
- `src/backend.h:30-33`: базовые 4 потока; G4 даёт кодеру отдельный бэкенд `backend_cpu_n_threads()*2` = 8; в логах `[Load] Codec backend: CPU (CPU threads: 8)`, Talker 4.
- G4: RTF med short 1.7680 / short-2 1.8367 / medium 1.9362 / long 2.1362; cold-first 437 мс; `CodecDecode` ≈58% времени; 28/28 HTTP 200.
- Runtime-ручки без пересборки исчерпаны: env потоков в `qwentts` нет (`backend.h:30-33`), OpenMP в ARM-бинаре отсутствует (`GOMP_*`/`libgomp` в `libggml-cpu.so` не найдены), Vulkan-бэкенда в бинаре нет (иначе `ggml_backend_init_best()` выбрал бы его), BLAS бесполезен при T≤8. `taskset` есть, но аффинность не снимает потолок и в G3 не спасла регресс AR-стадий.
- `CODEC_STREAM_CLASSES` — единственная оставшаяся одно-строчная правка, влияющая на доминирующую стадию и не меняющая веса.

## Proposed design

Один гейт: `stream16` против свежего `codec8` на тех же бинарях, моделях и измерителе.

Почему не альтернативы:

| Вариант | Почему не выбран |
|---|---|
| `CODEC_STREAM_CLASSES=6` (T=32) | Больше выигрыш на long, но вдвое больше буферы графа и задержка хвостовых чанков; оставляем как follow-up, если T=16 даст ≥10% и захотим продолжить. |
| TransConv F32→F16 (`causal-trans-conv.h:52`) | 5–8% на `CodecDecode` → ~3–5% итогового RTF, ниже порога; плюс риск битовой несовместимости. |
| Q8_0 + repack для DAC-свёрток | Потенциал 1.5–3×, но ассерт `mul_mat` на F16-im2col, правка `dst_type`, проверка качества — несколько переменных и высокий риск; отдельный гейт. |
| Повышение потоков Talker/Predictor | G3 показал структурный регресс +21…+112%; G4 уже нашёл оптимум 4/8. |
| `--max-batch 2..4` | Только throughput при конкурентных запросах; наш протокол последовательный. |

Ожидаемый механизм: при T=16 число `graph_compute` на long/medium уменьшается вдвое, строки GEMM заполняются лучше. Quality неизменна: те же веса, тот же операторный граф, только другая ширина батча.

## Interfaces

- Новый вариант `stream16` в dispatch-workflow `.github/workflows/build-qwentts-arm-codec8.yml` (`inputs.variant`: `baseline | codec8 | stream16`).
- Patch-шаг для `stream16`: сначала что же правка G4 codec8 в `src/pipeline-codec.cpp`, затем замена `static const int CODEC_STREAM_CLASSES = 4;` → `= 5;` в `src/pipeline-codec.h`; grep-маркер `G6 stream16`.
- env измерителя (без изменений): `QWEN_BIN`, `QWEN_CODEC_MODEL`, `QWEN_OUT`, `QWEN_VARIANT`, `QWEN_PORT`, `QWEN_LOADER`, `QWEN_NATIVE_LIB`. Новых CLI-флагов и API-контрактов нет.
- Порты: baseline `codec8` = 4191, вариант `stream16` = 4197 (4193/4195 заняты G5a/G5b).
- Device root: `/data/user/0/org.opencode.mobile.debug/files/qwen3-tts-arm-research/g6/` с `bin-base/`, `bin-stream16/`, `out/base/`, `out/stream16/`.
- Новый режим измерителя `screen`: 3×`warm-short` + 3×`warm-medium`; только для go/no-go, не для вердикта. `matrix` остаётся байт-в-байт как в G4 (28 запросов).
- Done-сентинел `out/<variant>/bg-<mode>.done`, exit 0, `DONE N` в логе.
- Локальные JSON: `C:\Users\BIGBRO\AppData\Local\Temp\opencode\g6-results\`.

## Steps

1. Записать план — verify: файл в git, `git diff --check` чистый.
2. Добавить вариант `stream16` в research-workflow; verify: YAML валиден, `git diff --check`, для `baseline`/`codec8` ветки не изменились.
3. Dispatch `stream16`, дождаться success, скачать артефакт; verify: manifest с sha, `qwen-tts` отличается от codec8 `595035cd…`, `git diff --name-only` в логе = два файла, ggml pin `ef4555b`. Инфраструктурный отказ гейтом не считается.
4. Подготовить устройство: `g6/bin-base` = копия g5b/bin (codec8, sha-пин), `g6/bin-stream16` = новые бинари, те же GGUF с sha-проверкой, адаптированные bench/run/bg/cleanup; verify: `sha256sum` на устройстве, legacy-каталоги g4/g5a/g5b не тронуты.
5. **Скрининг:** `codec8/screen` и `stream16/screen` (по 6 запросов); verify: 6/6 HTTP 200, `done=0`, backend 4/8, peak>0.01. Сравнить `CodecDecode` на кадр и RTF. **Точка решения:** если `stream16` не даёт ≥10% улучшения `CodecDecode` на кадр **хотя бы в одной** из категорий (short/medium) — полную матрицу не открывать, DROP по скринингу.
6. При положительном скрининге: `codec8` A1 (28), `stream16` (28), при дрейфе >10% — A2 `codec8`; все device-side под `nohup` + done-сентинел. Verify: `DONE 28`, 28/28 HTTP 200.
7. Расчёт медиан RTF, first PCM, stage ms/frame, guard, дрейфа, peak/rms; вердикт в `docs/research/QWEN3_TTS_G6_CODEC_STREAM_CHUNK_GATE.md`. Verify: `git diff --check`, коммит и пуш только research-документов и research-workflow.

## Risks

- **Малый эффект на коротких фразах:** ранкон 1→2→4→8 одинаков, T=16 включается только после 15 кадров; у short (~19 кадров) выигрыша может не быть. Это честно проверяется скринингом до полной матрицы; порог не подгоняется.
- **Регресс first PCM:** структура ранкона предсказывает неизменность, но это проверяется приоритетной метрикой cold-first ≤796.4 мс.
- **Память графа:** T=16 увеличивает intermediate-тензоры; на 8-ядерном устройстве с Big core это не должно быть проблемой, но при crash/hang гейт считается провалом.
- **Дрейф стенда:** G5a показал до +49% RTF за полчаса; A/B/A обязателен для полной матрицы.
- **ADB:** только точечные read-команды; фоновый `nohup` внутри device-side shell, не `adb shell … &`.

## Open questions

- Нет. Порог и граница зафиксированы до измерений; если скрининг отрицательный — DROP без полной матрицы, как в G5b.
