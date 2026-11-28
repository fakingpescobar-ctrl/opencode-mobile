# Qwen3-TTS: исследовательский гейт G4 — потоки 8 только для CodecDecode

## Goal

Проверить, можно ли сохранить выигрыш CodecDecode от 8 потоков (G3: −31…−42% на medium/long) и при этом убрать регрессию Talker/CodePredictor, не трогая глобальное число потоков. Это следующий дешёвый одно-переменный research-гейт после DROP G3.

## Non-goals

- не менять `release-apk.yml`, APK wiring, подпись, versionCode, теги `apk-*`;
- не менять дефолтный TTS (Supertonic);
- не трогать `README.md`, `MOBILE_AGENT/ЖУРНАЛ.md`, `MOBILE_AGENT/СОСТОЯНИЕ.md`;
- не повторять G1d (`GGML_CPU_ARM_ARCH`), G2 (`GGML_CPU_KLEIDIAI`), G3 (глобальные 8 потоков);
- не включать SVE2/SME2, не квантовать кодер, не менять модель;
- **не смешивать с идеей GPU/Vulkan**: это отдельный будущий гейт, здесь только CPU-потоки;
- не пересматривать product-гейт `RTF ≤ 1.0`.

## Constraints

- research-only workflow, только `workflow_dispatch`, один прогон варианта = один вердикт;
- порог research: ≥10% улучшения RTF в **каждой** категории short/medium/long против свежего baseline; равный/худший результат → DROP и откат;
- дополнительный заранее зафиксированный guard: медианы TalkerDecode и CodePredictor не должны ухудшаться более чем на 10% против того же baseline; нарушение = DROP даже при формальном проходе RTF;
- стабильность: 28/28 HTTP 200, 0 crash/hang, s16le 24 kHz mono, peak > 0.01;
- первый PCM med (cold/short) ≤ 796.4 мс;
- 10-повторный `warm-short-2` как в G3: улучшение RTF также должно быть ≥10%, иначе выигрыш на 2 повторах не считается воспроизводимым;
- база `ServeurpersoCom/qwentts.cpp @ 2edb35f`, ggml `ef4555b7aede4467a5de803b103615e0a5f30dd8`, модель `0.6B Base Q4_K_M`;
- замеры — тот же 28-запросный измеритель, один резидентный `tts-server`, порты 4187/4189, sha-пины бинарников;
- ADB нестабилен под нагрузкой: прогон запускается device-side в фоне с done-сентинелом.

## Current behaviour

- `src/backend.h:30-33`: `backend_cpu_n_threads()` = `hardware_concurrency()/2` → 4 на CPH2747 (8 ядер Oryon, без SMT).
- `src/qwen.cpp:464,471`: один `BackendPair` на Talker, Predictor и Codec.
- `src/pipeline-tts.cpp:208`: тот же `bp` передаётся в `pipeline_codec_load`.
- `src/pipeline-codec.cpp:23-25`: `pipeline_codec_load(PipelineCodec * pc, ..., BackendPair bp)` принимает `bp` **по значению** и сохраняет в `pc->bp`; `pc->backend` и `pc->sched` (`:106`) используют именно её.
- `PipelineCodec::bp` уже есть в структуре и фактически не используется вне загрузки — отдельный бэкенд можно передать без изменения ABI.
- G3 показал: при 8 потоках CodecDecode стал быстрее, но Talker/CodePredictor замедлились на 21–112%, long RTF вырос на 2.8%, cold-first PCM вырос до 3828 мс.

## Proposed design

**Выбранный вариант `codec8`:** в research-CI после клонирования пина `2edb35f` патчим только `src/pipeline-codec.cpp`:

1. В `pipeline_codec_load()` вместо прямого `pc->bp = bp;` создаём отдельный CPU-only `BackendPair` с `backend_cpu_n_threads() * 2` потоками и подменяем локальную копию `bp`:

```cpp
// G4 codec8: отдельный CPU-бэкенд кодера, 8 потоков; Talker/Predictor остаются на 4.
BackendPair codec_bp = {};
codec_bp.backend = cpu_backend_new(backend_cpu_n_threads() * 2);
codec_bp.cpu_backend = codec_bp.backend;
bp = codec_bp;
pc->bp = bp;
qt_log(QT_LOG_INFO, "[Load] Codec backend: %s (CPU threads: %d)",
       ggml_backend_name(bp.backend), backend_cpu_n_threads() * 2);
```

2. В `pipeline_codec_free()` добавляем `backend_release(pc->bp.backend, pc->bp.cpu_backend); pc->bp = {};` — новый бэкенд должен освобождаться. В baseline этого блока нет, поэтому двойного free нет.

Остальное байт-в-байт как в G3/G2: CMake-флаги, musl-тулчейн, `GGML_CPU_KLEIDIAI=OFF`, `GGML_CPU_ARM_ARCH` не задаётся. Никаких новых CLI-флагов.

### Почему не альтернативы

- `ggml_backend_set_n_threads()` вокруг вызовов кодера: технически работает (ggml перепланирует compute на каждом вызове, scheduler не кэширует число потоков), но бэкенд общий с Talker/Predictor; нужны RAII-обёртки минимум в 4 местах и легко протечь 8 потоками в autoregressive-стадии. Это не одна переменная.
- Global `cpu8` — уже DROP в G3.
- Vulkan/GPU split — интересная идея, но требует отдельного гейта: Vulkan-бэкенд и kbuild, поддержка нужных op/ subgroup-функций на Adreno, другое число переменных. Не открываем здесь.

## Interfaces

- Новый dispatch-workflow `.github/workflows/build-qwentts-arm-codec8.yml` с `inputs.variant = baseline | codec8`.
- Для `codec8`: patch-шаг с точными якорями `    pc->bp              = bp;` и хвостом `pipeline_codec_free()`; шаг обязан fail, если якорь не найден или изменён больше одного файла.
- Для `baseline`: проверка отсутствия маркера `G4 codec8`; `qwen-tts` обязан дать sha `5a54104f…` байт-в-байт.
- env измерителя: `QWEN_BIN`, `QWEN_OUT`, `QWEN_VARIANT`, `QWEN_PORT`, `QWEN_LOADER`, `QWEN_NATIVE_LIB`.
- Артефакты: `qwentts-arm-baseline`, `qwentts-arm-codec8` с manifest sha.

## Steps

1. Записать этот план — verify: файл в git, `git diff --check` чистый.
2. Создать research-workflow, запустить dispatch `baseline` — verify: success, `qwen-tts 5a54104f…`.
3. Dispatch `codec8` — verify: success, в логе виден diff только `src/pipeline-codec.cpp`, `qwen-tts` sha отличается от baseline, `libggml*.so` побайтово совпадают.
4. Выложить бинари в `/data/user/0/org.opencode.mobile.debug/files/qwen3-tts-arm-research/g4/`, smoke обоих вариантов — verify: HTTP 200, в логе `[Load] Talker backend: CPU (CPU threads: 4)` и `[Load] Codec backend: CPU (CPU threads: 8)` только у codec8.
5. Device-side фон: baseline matrix (A1) → codec8 matrix → при подозрении на дрейф повтор baseline (A2) — verify: 28/28, `DONE 28`, JSON сохранён.
6. Посчитать медианы RTF, first PCM, stage-профиль, 10-повторный short; применить пороги — verify: таблица дельт, однозначный ACCEPT/DROP.
7. Записать формальный вердикт `docs/research/QWEN3_TTS_G4_CODEC_ONLY_THREADS_GATE.md`; при DROP удалить workflow — verify: `git diff --check`, коммит и пуш только research-документации.

## Risks

- Раздельные CPU-бэкенды могут дать дополнительный memory pool и больший pressure на кэш: проверяем smoke и 28-запросной матрицей.
- `hardware_concurrency()` может вернуть не 8: лог покажет фактическое число; если не 8, гейт инфраструктурно несостоялся, а не вердикт.
- Освобождение бэкенда в `pipeline_codec_free()` может конфликтовать с использованием `pc->bp` внутри графов: проверяем отсутствие crash/hang на 28 запросах.
- PUT-патч может не сработать из-за форматирования: sha-пины и `git diff` ловят это до сборки.
- ADB может уйти в offline: только device-side `nohup` + done-сентинел.
- Откат: удалить workflow и записать DROP; diagnostics-артефакты вне APK остаются.

## Open questions

Нет. Пороги и границы зафиксированы; выбор следующего гейта (GPU/Vulkan или что-то ещё) остаётся за Максом после вердикта G4.
