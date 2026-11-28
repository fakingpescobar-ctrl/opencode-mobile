# Qwen3-TTS: исследовательский гейт CPU-потоков для CodecDecode

## Goal

Проверить, убирает ли доминирование CodecDecode (58–59% времени) простая правка числа потоков CPU-бэкенда ggml: использовать все доступные ядра CPH2747 вместо половины. Результат — один research-вердикт с теми же порогами, что и G2, без изменения APK.

## Non-goals

- не менять `release-apk.yml`, APK wiring, подпись, versionCode, теги `apk-*`;
- не менять дефолтный TTS (Supertonic);
- не трогать `README.md`, `MOBILE_AGENT/ЖУРНАЛ.md`, `MOBILE_AGENT/СОСТОЯНИЕ.md`;
- не повторять ISA-флаг G1d и не включать KleidiAI/SVE2/SME2;
- не квантовать модели и не патчить CodecDecode-граф в этом гейте;
- не пересматривать product-гейт `RTF ≤ 1.0`.

## Constraints

- research-only workflow, только `workflow_dispatch`, один прогон варианта = один вердикт;
- порог research: ≥10% улучшения RTF в **каждой** категории short/medium/long против свежего baseline; равный/худший результат → DROP и откат;
- стабильность: 28/28 HTTP 200, 0 crash/hang, s16le 24 kHz mono, peak > 0.01;
- первый PCM med (cold/short) ≤ 796.4 мс;
- только одна переменная за прогон; база `ServeurpersoCom/qwentts.cpp @ 2edb35f`, ggml `ef4555b7aede4467a5de803b103615e0a5f30dd8`, модель `0.6B Base Q4_K_M`;
- замеры — тот же 28-запросный измеритель, один резидентный `tts-server`, порты 4187/4188, sha-пины бинарников;
- ADB нестабилен под нагрузкой: прогон запускается device-side в фоне с done-сентинелом.

## Current behaviour

- `src/backend.h:25-29`: `backend_cpu_n_threads()` возвращает `std::thread::hardware_concurrency() / 2` с комментарием «one thread per physical core for HT/SMT». На телефоне CPH2747 (8 ядер Oryon, без SMT) это даёт 4 потока — вдвое меньше доступных.
- `src/backend.h:36-56`: `cpu_backend_new(int n_threads)` создаёт CPU-бэкенд и задаёт потоки через `ggml_backend_reg_get_proc_address(reg, "ggml_backend_set_n_threads")`; upstream-комментарий прямо отмечает, что `ggml_backend_cpu_device_init_backend` иначе использует значение по умолчанию `GGML_DEFAULT_N_THREADS (4)`.
- `src/backend.h:110-127`: `backend_init()` вызывается один раз и передаёт это же число во все Pipeline (Talker, Predictor, Codec); `src/qwen.cpp:464,471` использует один `BackendPair` на весь `qt_context`.
- `tools/tts-server.cpp:45-59`: CLI не имеет флага `--threads`, поэтому число потоков нельзя изменить без правки исходника.
- Замер G2 baseline: CodecDecode 2184 / 11022 / 35199 мс (short/medium/long), 57.6 / 59.0 / 58.4% от Total; RTF 2.5766 / 2.6699 / 3.0117; 28/28 HTTP 200.

## Proposed design

**Выбранный вариант `cpu8`:** в research-CI после клонирования пина `2edb35f` одной точечной заменой в `src/backend.h` убрать деление на 2:

```c
static int backend_cpu_n_threads(void) {
    int n = (int) std::thread::hardware_concurrency();
    return n > 0 ? n : 1;
}
```

Всё остальное (CMake-флаги, тулчейн с `CMAKE_ASM_COMPILER=/usr/bin/aarch64-linux-gnu-gcc`, модели, измеритель) остаётся байт-в-байт как в G2. `GGML_CPU_KLEIDIAI` остаётся `OFF`.

**От rejected:**
- `taskset`/аффинность — не снимает потолок в 4 потока, только меняет размещение уже существующих;
- `--codec-chunk-dur` — влияет на one-shot WAV-путь (`codec-chunked-decode.h`), а тест использует потоковый PCM (`pipeline_codec_decode_stream_batch`, `src/pipeline-codec.cpp:674`); плюс риск артефактов на границах чанков;
- квантование кодера — меняет веса, требует отдельного гейта и другая точка отсчёта.

## Interfaces

- Новый dispatch-workflow `.github/workflows/build-qwentts-arm-cpu8.yml` с `inputs.variant = baseline | cpu8`.
- Для `cpu8`: один `sed`-шаг с точной подстановкой строки `int n = (int) std::thread::hardware_concurrency() / 2;` → `int n = (int) std::thread::hardware_concurrency();`. Шаг обязан fail, если строка не найдена (иначе гейт измеряет не тот вариант).
- env измерителя: `QWEN_BIN`, `QWEN_OUT`, `QWEN_VARIANT`, `QWEN_PORT`, `QWEN_LOADER`, `QWEN_NATIVE_LIB`.
- Артефакты: `qwentts-arm-baseline`, `qwentts-arm-cpu8` с manifest sha.
- Новых CLI-флагов, env-переменных приложения и API-контрактов нет.

## Steps

1. Записать этот план — verify: файл в git, `git diff --check` чистый.
2. Создать research-workflow с двумя вариантами и sha-проверкой patch-шага — verify: YAML валиден, dispatch `baseline` даёт `qwen-tts 5a54104f…` (байт-в-байт G2).
3. Dispatch `cpu8` — verify: зелёный run, в логе видна замена, `qwen-tts` sha отличается от baseline, manifest сохранён.
4. Выложить бинари в приватную папку приложения, прогнать `smoke` для обоих вариантов — verify: HTTP 200, `DONE 1`, sha-пины проходят, в логе виден `(CPU threads: 8)` для `cpu8` и `(CPU threads: 4)` для baseline.
5. Замер: baseline matrix (A2) → cpu8 matrix → при подозрении на дрейф ещё baseline (A3) — verify: 28/28, 0 crash/hang, JSON сохранён.
6. Посчитать медианы RTF и first PCM, применить пороги раздела Constraints — verify: таблица дельт, однозначный ACCEPT/DROP.
7. Записать формальный вердикт `docs/research/QWEN3_TTS_G3_CPU_THREADS_GATE.md`; при DROP удалить workflow — verify: `git diff --check`, коммит и пуш, APK/CI-release не тронуты.

## Risks

- 8 потоков могут конкурировать с `opencode serve` и UI за память и полосу; возможен равный/худший результат → DROP по правилу 59/61.
- `hardware_concurrency()` может вернуть не 8: шаг 4 проверяет фактическое число в логе; если там не 8, гейт считается инфраструктурно несостоявшимся и перезапуск запрещён, а не «вердиктом».
- patch-шаг может не сработать из-за CRLF/форматирования → sha не изменится, гейт не открыт.
- ADB может снова уйти в offline; forefront-замер не используется, только device-side фон + sentinel.
- Откат: удалить workflow и документальные записи о варианте; оставить diagnostics-артефакты вне APK.

## Open questions

Нет: рычаг, пороги и границы зафиксированы; решение о следующем гейте после вердикта остаётся за Максом.
