# Qwen3-TTS / llama.cpp под ARMv9 телефона: исследование и план сборки

Дата: 28.11.2026. Целевое устройство: OnePlus CPH2747, SM8850 / Snapdragon 8 Elite Gen 5, 8 ядер Oryon, ARMv9, Adreno 840, Android 16, 16 KB page.

Этот документ сводит четыре независимых разбора (GitHub/Hugging Face, локальный CI-аудит, архитектурный разбор, проверка отчёта гейта) и фиксирует решение до начала нового цикла правок. Он **не** отменяет и не переписывает `docs/research/QWEN3_TTS_ISA_GATE.md`: тот документ остаётся формальным отчётом G1d.

## 1. Цель и границы

**Цель:** получить воспроизводимую research-сборку Qwen3-TTS, оптимизированную под наш CPU, и понять, где реально искать ускорение, чтобы приблизиться к RTF ≤ 1.0.

**Не-goals:**

- не менять `release-apk.yml`, APK wiring, подпись, versionCode и установку APK;
- не менять дефолтный TTS: Supertonic остаётся default;
- не повторять уже DROP-нутый ISA-флаг `dotprod/i8mm`;
- не публиковать локальный APK под тегом `apk-*`;
- не редактировать параллельно `README.md`, `MOBILE_AGENT/ЖУРНАЛ.md`, `MOBILE_AGENT/СОСТОЯНИЕ.md` без отдельного слова Макса.

## 2. Точка отсчёта

Формальная база — G1c из `docs/research/QWEN3_TTS_ISA_GATE.md:21-29`:

| Категория | RTF med эталона | Порог ACCEPT (−10%) |
|---|---:|---:|
| warm-short | 2.109423 | ≤ 1.898481 |
| warm-medium | 2.497221 | ≤ 2.247499 |
| warm-long | 2.651994 | ≤ 2.386794 |

Первый PCM med — **724 мс**, порог ACCEPT ≤ **796.4 мс**. Ша эталона: `qwen-tts 2b456a06…`, `libqwen.so e2d9dcba…`, `tts-server 39ba9fdd…`.

G1d (`docs/research/QWEN3_TTS_ISA_GATE.md:57-74`) официально DROP:

| Метрика | Эталон | ISA | Изменение |
|---|---:|---:|---:|
| RTF short | 2.109423 | 2.205480 | −4.55% (хуже) |
| RTF medium | 2.497221 | 2.365609 | +5.27% (ниже порога 10%) |
| RTF long | 2.651994 | 2.695078 | −1.62% (хуже) |
| Первый PCM | 724 мс | 679 мс | PASS |

Вывод G1d: гипотеза «базовый ARMv8-A душит dotprod/i8mm» опровергнута на этом стеке. Причины, почему флаг не сработал, подробнее в разделе 3.

### 2.1. Голосовой референс для клонирования

По решению Макса мобильный Qwen3-TTS клонирует голос из:

- путь: `C:\Users\BIGBRO\projects\giga-voice\callum_CURRENT_male.mp3`;
- размер: **59 811 байт**;
- SHA256: `8c94dc49a0e98820ad65ab66ef75e7f5b23010d6b573b5fbfa9e2ff4f6d2f85b`.

Бинарник в git не копируем: в research-замеры передаём только путь и sha. В будущем измерителе клонирования проверяем не только RTF, но и peak/RMS и прослушивание. `ffprobe` в текущем окружении недоступен, длительность/кодек не зафиксированы.

## 3. Что выяснено по llama.cpp / ggml

Все утверждения ниже — fact, если не помечено как гипотеза.

### 3.1. Готовые ARM-варианты уже есть в апстриме

- `ggml-org/llama.cpp`, PR `#14206`, коммит `3ba0d843c6` (16.06.2025), MIT. В `ggml/src/CMakeLists.txt:536-542` есть `android_armv8.6_1` (`DOTPROD + FP16 + MATMUL_INT8`), `android_armv9.0_1` (+SVE2), `android_armv9.2_1/2` (+SVE, SME).
- `ggml-org/llama.cpp`, PR `#25554`, коммит `cb26014d96` (07.08.2026), MIT. Runtime-детект фич через `getauxval(AT_HWCAP/AT_HWCAP2)` в `ggml/src/ggml-cpu/arch/arm/cpu-feats.cpp`; выбор — `ggml_backend_load_best()` в `ggml-backend-reg.cpp:480-545`.

**Следствие:** отдельный «наш» форк ради ARM-инструкций не нужен: апстрим уже делает то же самое. Гипотеза «SVE2/SME2 доступны Oryon» не подтверждена: HWCAP-логика выбирает `armv8.6_1`, а не `armv9.*`.

### 3.2. Почему G1d был нейтрален

- `GGML_BACKEND_PATH` загружается **после** `ggml_backend_load_best("cpu")` (`ggml-backend-reg.cpp:593-598`), поэтому авто-вариант регистрируется первым и выигрывает.
- Каждый ARM-вариант сам добавляет `-march` в `CMakeLists.txt:719`; глобальный `CFLAGS=-march=...` ничего не перебивает.
- В research-CI G1d Zig 0.13 отверг `-march=armv8.6-a+dotprod+i8mm`; понадобился `-mcpu=generic+features`.

Это объясняет, почему «просто добавить флаг» не дало прироста. Повторять тот же самый прогон **запрещено** правилом «один прогон = один вердикт».

### 3.3. Flash Attention

- CPU FA реален: в `ggml/src/ggml-cpu/ops.cpp` есть `ggml_compute_forward_flash_attn_ext_f16` (FA-регион начинается около строки 8780, функция — около 8970–9732 в апстриме); K/V идут через `vec_dot` type traits, то есть NEON/dotprod/i8mm. Операция `GGML_OP_FLASH_ATTN_EXT` разрешается в `ggml-cpu.c` (например, `2037/2438/2996` в апстриме), а не в `ggml-cpu.cpp:444` — там `GGML_OP_SET_ROWS`. GQA и KV-cache не блокируют. Номера строк в зафорченном ggml могут отличаться, поэтому workflow проверяет наличие функции по имени, а не по строке.
- Vulkan FA есть (`flash_attn.comp`, `flash_attn_cm1/cm2`, `FA_SCALAR`), Adreno детектится через `VK_KHR_cooperative_matrix` + `VK_QCOM_cooperative_matrix_conversion` (`ggml-vulkan.cpp:130-146`). Но на SM8850/Adreno 840 известны SIGSEGV: `#29952` (закрыт, `vkCreateComputePipelines` с coopmat fp16-accum после `spirv-opt --ssa-rewrite`) и `#30649` (открыт, полный оффлоад). Возможный обход — `GGML_VK_DISABLE_COOPMAT`, отдельный эксперимент.
- **Главное:** FA уже есть в нашей базе. Цепочка коммитов через GitHub API подтверждена: `76ec9de → 83acdda → 2edb35f` (то есть `76ec9de` — предок пина). Коммит `76ec9de` (16.11.2026) — «fuse QKV projections and keep an F16 cache for flash attention»; по словам автора: Talker `1.87 → 1.59 мс/кадр`, code predictor `3.05 → 2.45 мс`, кэш вдвое меньше. Это не наши замеры.
- FA ускоряет Talker. Он **не** убирает автогрессивные шаги Predictor и не ускоряет свёрточный CodecDecode.

**Решение:** отдельный гейт «включить FA» не открываем. Делаем только дешёвую проверку, что FA-коммит является предком пина и `GGML_OP_FLASH_ATTN_EXT` не отключён в runtime.

### 3.4. KleidiAI

- В пине `ServeurpersoCom/qwentts.cpp @ 2edb35f` сабмодуль ggml зафиксирован на `ef4555b7aede4467a5de803b103615e0a5f30dd8`.
- В этом ggml уже есть `src/ggml-cpu/kleidiai/` и опция `GGML_CPU_KLEIDIAI`; `CMakeLists.txt` тянет KleidiAI `v1.32.0` через `FetchContent` с MD5 `05f1953428ffe8e8c7f585dc6d508887`.
- Ядра KleidiAI — Apache-2.0, basic сборка поддерживает Linux/Android AArch64.
- Живой `baseline` (`workflow_dispatch`, run `46114617340`) собрался успешно. Первый `kleidiai` (run `46115587334`) упал не по существу варианта: KleidiAI v1.32.0 задаёт per-source `-march=armv8.2-a+fp16+...`, а Zig 0.13 знает ту же фичу как `fullfp16` (`error: unknown CPU feature: 'fp16'`). Обёртка тулчейна теперь переводит `-march` в `-mcpu=generic+features` и нормализует фичи по отдельным токенам. Это инфраструктурная правка, не технический DROP KleidiAI и не новый research-гейт.

**Решение:** KleidiAI — первый новый замеряемый вариант после baseline. Ожидаемый выигрыш на dotprod/i8mm — гипотеза, не факт. SME2/SVE2-части не берём: Oryon их через HWCAP не заявляет.

### 3.5. NPU и чужие движки

- `ggml-hexagon` — единственный реальный NPU-бэкенд для ggml-моделей (`ggml/src/ggml-hexagon/htp/`, HVX/HMX, `flash-attn-ops.c`). CI гоняет его на SM8850, но `scripts/snapdragon/run.py:517` делает `adb root`. На розничном CPH2747 без разблокировки неприменимо.
- QNN в `sherpa-onnx` / `executorch` — только для ONNX/PyTorch моделей, не для ggml-весов. Qwen3-TTS через QNN не подтверждён.
- `alibaba/MNN` — другой движок (Apache-2.0), не замена ggml; полезен только как эталон ядер.
- Мелкие репозитории `Dmage22/llama-snapdragon`, `xentron-bit/llama-android-prebuilt`, `FASTCHIP/lfm3-android` — переупаковка апстрима без независимых замеров. Не берём.
- Отдельных патчей под 16 KB page не найдено; это ни подтверждено, ни опровергнуто.

## 4. Что выяснено по Qwen3-TTS / Hugging Face

| Проект | URL / коммит | Лицензия | Вердикт |
|---|---|---|---|
| `HaujetZhao/Qwen3-TTS-GGUF` | `74feb58`, 19.09.2026 | MIT | Пайплайн экспорта/инференса, не C++ runtime. README прямо называет бутылочное горлышко Predictor: `12.5 кадров/с × 15 шагов = 187.5 шагов/с аудио`. Поддерживает 0.6B/1.7B Base/CustomVoice/VoiceDesign, но без Android-инструкций |
| `ServeurpersoCom/qwentts.cpp @ 2edb35f` | 17.11.2026, ggml `ef4555b` | MIT | Лучшая база: C++17/GGML, стриминг, `buildtermux.sh`, implicit GEMM для causal conv1d, FA уже внутри |
| `mschneider82/qwentts.cpp` | синхрон с upstream | MIT | Идентичный дубль, пользы нет |
| `zjh1934/tingben` | активен | MIT | Android-интеграция arm64-v8a, метрик RTF нет; reference, не движок |
| `ultrapg/voicemobile` | ранний | GPL-3.0 | Не брать из-за лицензии |
| `sosora7520/qwentts.cpp` | 404 | не подтверждена | Не использовать |
| `Solidaxel/Qwen3-TTS-GGUF` | Hugging Face | Apache-2.0 | Только веса, не runtime |

**Форматный раскол:** `qwentts.cpp` ждёт `qwen-talker-{size}-{mode}-{variant}.gguf` + `qwen-tokenizer-12hz-{variant}.gguf` (0.6B Base Q4_K_M есть, ~629 МБ). `llama.cpp b12816 llama-tts` / BricksDisplay используют backbone + codec. Без конвертации не взаимозаменяемо.

**Гипотеза (не подтверждена нашим профилем):** узкое место — Predictor, а не CodecDecode. В отчёте G1d есть только общая фраза «CodecDecode остаётся доминирующим (50–55% времени)» (`QWEN3_TTS_ISA_GATE.md:72`), без разбивки по слоям. `pwconv1/pwconv2` и `convnext-block.h:88-95` там нет. Патчить pwconv вслепую не будем.

## 5. Локальные точки входа

- `build-qwentts-musl.yml:6-7` — только `workflow_dispatch`; `:38-45` пин qwentts/ggml; `:47-66` Zig musl-сборка с `GGML_NATIVE=OFF`, `GGML_STATIC=ON`, `GGML_BACKEND_DL=OFF`; `:68-87` артефакт.
- `build-llama-musl.yml:7-13,42-46,48-63,65-88` — llama-side research-CI (пин `b12554`).
- Измерители `bench-resident.mjs`, `run-bun.sh`, `summary-resident.mjs` и JSON-вердикты в git **отсутствуют**; в репозитории живут только итоговые цифры в отчётах. Их восстановление — отдельный шаг.
- `release-apk.yml` защищён: `:12-20` триггеры (`workflow_dispatch` + `push tags: apk-*`), `:25-27` concurrency, `:104-111` versionCode, `:35-42,76-102,161-196` подпись/serial, `:212-248` release. Не трогаем.
- `GGML_CPU_ARM_ARCH` в текущем CI отсутствовал; в истории был (`dc5d3d8`, `8d237bc`) и в удалённом G1d-workflow. Повторно не включаем.

## 6. Проектируемый research-гейт G2

**Правила до патча:**

1. Пороги зафиксированы в этом документе до запуска и не меняются.
2. Изменитель RTF, эталонные бинари и sha остаются прежними.
3. Равный или худший результат → DROP и откат; «почти получилось» не оставляем.
4. Один прогон = один вердикт; перезапуск « ради удачи» запрещён.
5. Product-гейт RTF ≤ 1.0 не пересматривается ни одним diagnostics-ACCEPT.
6. APK/CI-release/установка не открываются.

| Метрика | ACCEPT | DROP |
|---|---|---|
| RTF med short / medium / long | ≥10% улучшения в каждой категории против G1c | <10% или хуже в любой категории |
| Первый PCM med | ≤ 796.4 мс | > 796.4 мс |
| Стабильность | 28/28 HTTP 200, 0 crash/hang, s16le 24 kHz mono | любой 4xx/5xx, обрыв, crash/hang |
| RU/WAV sanity | PCM ненулевой, peak > 0.01 | тишина/мусор/иной формат |

## 7. План реализации

### Шаг 0 — воспроизводимость (реализовано в первую очередь)

Новый `workflow_dispatch`-workflow `.github/workflows/build-qwentts-arm-research.yml`, скопированный с `build-qwentts-musl.yml`, с двумя вариантами: `baseline` и `kleidiai`. Никакого `GGML_CPU_ARM_ARCH`, никакого повтора G1d. Workflow проверяет, что FA-коммит `76ec9de` — предок пина, и пишет manifest с sha.

**Проверка:** workflow в `main`, `git diff --check`, валидный YAML; `baseline` — success (run `46114617340`), первый `kleidiai` — инфраструктурный failure (run `46115587334`, `fp16` → `fullfp16`), повтор после правки тулчейна — отдельный `workflow_dispatch`.

### Шаг 1 — stage-профиль

Восстановить `bench-resident.mjs` / `run-bun.sh` / `summary-resident.mjs` из diagnostics-артефактов мобильного агента или переписать минимальный измеритель с тем же протоколом 28 запросов. Снять разбивку Talker / Predictor / CodecDecode и распределение по 8 ядрам на **одном и том же** WAV. Отдельный кейс клонирования голоса использует референс из раздела 2.1; его sha сверяется до и после прогона.

**Проверка:** 28/28 HTTP 200, совпадают sha эталона, вывод с долями по стадиям.

### Шаг 2 — первый гейт по данным профиля

- Если доминирует **Predictor**: искать уменьшение шагов/RVQ depth или chunked streaming. Готового форка с этим не найдено, потребуется правка.
- Если доминирует **CodecDecode**: `n_threads=6` + аффинность на большие ядра Oryon; затем квантование декодера F16 → Q8_0/Q4_0; затем KleidiAI как отдельный вариант.

**Проверка:** пороги из раздела 6, один прогон на вариант.

### Шаг 3 — отдельные треки

Vulkan только с `GGML_VK_DISABLE_COOPMAT`, Hexagon/QNN — отдельно и с честным no-go на розничном устройстве без root. FA остаётся вне гейтов: только runtime-проверка.

## 8. Риски и откат

- KleidiAI тянет внешний архив с GitHub через `FetchContent`: риск инфраструктурного падения, а не технического DROP. Такой run не считается вердиктом.
- Результат шумовой полосы ±5%: порог 10% выбран осознанно, меньший порог ничего не различает.
- Форк `qwentts.cpp` MIT и ggml MIT, KleidiAI Apache-2.0 — лицензионно совместимо. GPL-3.0 и неопубликованные репозитории не трогаем.
- Откат любого research-изменения: удалить новый dispatch-workflow и artifact, оставить `release-apk.yml` и APK нетронутыми.

## 9. Источники

- `docs/research/QWEN3_TTS_ISA_GATE.md` — формальный G1c/G1d.
- GitHub API: `github.com/ServeurpersoCom/qwentts.cpp` коммиты `2edb35f`, `83acdda`, `76ec9de`; `github.com/ServeurpersoCom/ggml @ ef4555b`, `src/ggml-cpu/kleidiai/`, `src/ggml-cpu/CMakeLists.txt`.
- `github.com/HaujetZhao/Qwen3-TTS-GGUF @ 74feb58`, `readme.md`.
- `github.com/ggml-org/llama.cpp` PR `#14206` / `#25554`, issues `#29952` / `#30649`.
- `huggingface.co/Serveurperso/Qwen3-TTS-GGUF`, `huggingface.co/BricksDisplay/Qwen3-TTS-12Hz-0.6B-GGUF`.
- Локальные `.github/workflows/build-qwentts-musl.yml`, `build-llama-musl.yml`, `release-apk.yml`.
