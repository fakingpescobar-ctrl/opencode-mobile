# Qwen3-TTS: G5 — техническая проверка идеи Vulkan/Adreno для CodecDecode

## Goal

Понять, можно ли вынести CodecDecode на GPU/Adreno телефона CPH2747 как следующий research-гейт после ACCEPT G4, не открывая APK wiring и не меняя дефолтный TTS. Результат этого документа — go/no-go и честная фиксация количества переменных, а не обещание ускорения.

## Non-goals

- не менять `release-apk.yml`, APK wiring, подпись, versionCode, теги `apk-*`, установку APK;
- не менять дефолтный TTS (Supertonic);
- не трогать `README.md`, `MOBILE_AGENT/ЖУРНАЛ.md`, `MOBILE_AGENT/СОСТОЯНИЕ.md`;
- не повторять G1d (`GGML_CPU_ARM_ARCH`), G2 (KleidiAI), G3 (глобальные 8 потоков), G4 (codec8 уже ACCEPT);
- не включать SVE2/SME2;
- не менять модель и не пересматривать product-гейт `RTF ≤ 1.0`.

## Constraints

- база: `ServeurpersoCom/qwentts.cpp @ 2edb35f`, ggml `ef4555b7aede4467a5de803b103615e0a5f30dd8`;
- research-only `workflow_dispatch`, один прогон = один вердикт, preference — одна переменная за гейт;
- сборка musl aarch64 через Zig wrapper, как в G4: `GGML_STATIC=ON`, `GGML_BACKEND_DL=OFF`, `GGML_CPU_KLEIDIAI=OFF`, `GGML_CPU_ARM_ARCH` не задаётся;
- замеры только device-side в фоне, с `done`-сентинелом: ADB нестабилен под sustained load;
- если гейт требует больше одной переменной, это должно быть явно зафиксировано до запуска CI, а не выяснено после прогона.

## Current behaviour

Доказательства собраны по исходникам на pinned commit и по уже работающему G4-workflow.

- `src/backend.h:21-25`: `BackendPair` содержит `backend`, `cpu_backend`, `has_gpu`.
- `src/backend.h:134-166`: `backend_init(label, device)` выбирает `ggml_backend_init_by_name(device, nullptr)`, иначе `ggml_backend_init_best()`. CPU-fallback создаётся отдельно (`151/154`), `has_gpu = !best_is_cpu` (`163`). ENV-переключателей бэкенда нет.
- `src/backend.h:183-201`: `backend_sched_new()` получает 1 или 2 бэкенда и host buffer type GPU для pinned memory; scheduler умеет маршрутизировать неподдерживаемые GPU-ops на CPU.
- `src/pipeline-codec.cpp:23-25`: `pipeline_codec_load(PipelineCodec*, ..., BackendPair bp)` принимает пару **по значению**, сохраняет её в `pc->bp` и использует для весов/графов.
- `src/pipeline-codec.cpp:106`, `:181`, `:899`: one-shot decode/encode идут через `backend_sched_new` / `sched_graph_compute`.
- `src/pipeline-codec.cpp:657`: streaming-путь `codec_stream_run` вызывает `ggml_backend_graph_compute(pc->backend, gf)` **напрямую**, без scheduler. Здесь CPU-fallback недоступен.
- `src/qwen.cpp:464`: `backend_init("Talker", params->device)` создаёт **одну** пару на Talker, CodePredictor и Codec; `src/pipeline-tts.cpp:208` передаёт её в `pipeline_codec_load`, `:231` — в `backend_sched_new` Talker/Predictor; `:392` только обнуляет `pt->bp`.
- `src/qwen.cpp:514`: освобождается только `q->bp`; отдельная codec-пара пока нигде не создаётся и не освобождается.
- `CMakeLists.txt:113-122` qwentts: `link_ggml_backends` статически линкует каждый существующий `ggml-<backend>`, включая `vulkan`. При `GGML_VULKAN=ON` бэкенд попадёт во **все** бинари, а не только в кодер.
- `ggml src/ggml-vulkan/CMakeLists.txt:9-14`: обязательны `find_package(Vulkan COMPONENTS glslc REQUIRED)` и `SPIRV-Headers CONFIG REQUIRED`; `:40` glslc используется для проверки расширений шейдеров.
- `ggml src/ggml-vulkan/ggml-vulkan.cpp:9625-9629`: `GGML_OP_CONV_TRANSPOSE_1D` поддерживается только при `src0 F32` и `dst F32`; иначе `nullptr` (нет pipeline). Аналогично `GGML_OP_CONV_2D_DW` требует поддерживаемых шейдеров/типов.
- Модель кодера в гейтах — `qwen-tokenizer-12hz-Q4_K_M.gguf`; то есть значимая часть ops работает с квантованными весами, а не с F32.

## Proposed design

### Вариант A — «просто включить Vulkan и отдать кодер GPU» (рекомендуется закрыть как не-одну-переменную)

Минимальный технический путь, который реально существует:

1. В research-CI добавить `-DGGML_VULKAN=ON` и обеспечить aarch64-musl Vulkan headers/libs + glslc/SPIRV-Headers.
2. В `pipeline_tts_load()` вместо `bp` в `pipeline_codec_load()` передать отдельную пару `backend_init("Codec", "Vulkan0")`.
3. Обработать `backend == NULL` (fallback на CPU-пару), освободить новую пару в ветках ошибки `pipeline-tts.cpp:209-213/232-236` и в `pipeline_tts_free()` / `qt_free()` (`src/qwen.cpp:501-514`).

Почему это **не** гейт одной переменной: меняются CMake-флаги, код выбора бэкенда и код освобождения; плюс `GGML_VULKAN=ON` меняет все бинари через `link_ggml_backends`. Отдельный вопрос — типы: `CONV_TRANSPOSE_1D` в Vulkan только F32, а кодер Q4_K_M; one-shot ещё может маршрутизировать неподдерживаемое на CPU через scheduler, но streaming (`pipeline-codec.cpp:657`) идёт в Vulkan напрямую и может упасть.

### Вариант B — зафиксировать блок и уйти в дешёвый одно-переменный гейт (рекомендуется)

Сначала записать, что Vulkan-идея на текущем пине требует как минимум трёх правок и проверки совместимости типов, то есть не подходит под принятый дисциплинарный формат «одна переменная за гейт». Затем открыть следующий одно-переменный research-гейт, который бьёт по тому же доминирующему CodecDecode: **G5a — смена квантования codec GGUF** (`Q4_K_M` → `Q8_0` или `Q4_0`) при байт-идентичных бинарях и той же 28-запросной матрице. Это меняет только модель кодера, не код и не APK.

## Interfaces

- Текущие интерфейсы, которые пришлось бы затронуть в варианте A: `backend_init(const char * label, const char * device)`, `BackendPair`, `pipeline_codec_load(..., BackendPair bp)`, `pipeline_tts_load(...)`, `pipeline_tts_free()`, `qt_free()`.
- Новый CLI/ENV не требуется: `--device` уже есть в `tools/qwen-tts.cpp` и `tools/tts-server.cpp`, но он задаёт один бэкенд для всех стадий и для G5 непригоден.
- Для варианта B: новый research-артефакт codec GGUF и env измерителя `QWEN_CODEC_MODEL` (если понадобится); бинари остаются теми же, что в G4 codec8/baseline.

## Steps

1. Статический go/no-go по типам и сборке — **выполнено**: `GGML_STATIC=ON` + `GGML_BACKEND_DL=OFF` (G4 workflow), Vulkan CMake требует glslc/SPIRV-Headers, `CONV_TRANSPOSE_1D` только F32, codec — Q4_K_M, streaming идёт без scheduler. Verify: цитаты в разделе Current behaviour.
2. Записать формальный вывод: вариант A — multi-variable и высокорисковый, не открывать как обычный research-гейт без явного решения Макса. Verify: этот документ в git.
3a. Если Макс выбирает вариант A: создать research-workflow с явным перечнем ≥3 изменений, отдельной проверкой `Vulkan0` на устройстве и готовым fallback на CPU; пороги и guard — как в G4. Verify: workflow success, smoke показывает `Codec backend: Vulkan (Vulkan0)` и 28/28 HTTP 200; любой fails → DROP.
3b. Если Макс выбирает вариант B: написать план G5a и открыть гейт смены квантования codec GGUF, бинари — байт-в-байт из G4. Verify: план в git, затем обычный цикл CI → device matrix → verdict.

## Risks

- Вариант A: неподдерживаемые квантованные ops в Vulkan → hard fail в streaming-пути; статическая линковка Vulkan в musl aarch64 может не собраться на ubuntu-latest; `backend_init("Codec","Vulkan0")` может дать `NULL` на устройстве → нужен fallback; утечка отдельной BackendPair при забытом release.
- Вариант B: смена квантования может ухудшить качество аудио; нужно слушать/sanity-check помимо peak > 0.01; выигрыш по RTF не гарантирован.
- Общий риск: дрейф стенда между прогонами (в G4 A1→A2 был до +19% RTF) → обязателен повтор baseline после варианта.
- Откат: research-workflow и diagnostics удаляются/остаются вне APK; product-гейт не пересматривается.

## Open questions

1. Какой маршрут выбирает Макс: A (сознательно много-переменный Vulkan-гейт) или B (одно-переменный гейт квантования кодера)?
2. Если B: какое квантование пробовать первым — `Q8_0` (качество, меньше риска) или `Q4_0` (больше скорость)?
3. Если A: допустимо ли ради Vulkan нарушить правило «одна переменная за гейт» и добавить в research-CI Vulkan SDK / glslc / SPIRV-Headers?
