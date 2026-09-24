# Эксперименты по ускорению STT (Track 3)

`opencode-mobile` — локальное распознавание Whisper Large-v3-Turbo на Android (OPPO, 8 ядер ARM CPU + Adreno GPU).
Цель — выйти с текущего `encoder ≈ 8.8 c` (CPU, int8) на «полное распознавание 2–3 c» без потери качества (WER).

Дата: 02.09.2026 (обновлено 24.09.2026). Статус: **ЭКСП-1 выполнен — Vulkan-encoder int8 ~27% быстрее CPU (6.45 vs 8.82 s), но НЕ ×3; Vulkan в проде выключен** (см. §2 «Что в проде»). База: **encoder int8-CPU 8.82 s**.

---

## 1. Текущее состояние (базовая линия)

| Метрика | Значение | Режим |
|---|---|---|
| fbank | ~750–840 ms | CPU fp32 |
| **encoder** | **8.82 s** | CPU int8 (block-quant), 8 потоков |
| decoder | ~0.5–0.6 s | CPU fp16/32, KV-cache, 7 шагов · ~63–81 ms/шаг |
| **полный ncnn** | ~10.1 s | 48k сэмплов, lang=ru |
| целя | **2–3 s** | — |

Промежуточные замеры базы:
- encoder int8 ≈ 9.03 s (4 потока) → 8.82 s (8 потоков) — потоки дали лишь ~210 ms ⇒ упёрлись в память/потолок CPU, не в потоки.
- fp32 encoder ≈ 17.9 s (CPU). int8 дал ~2×.

**Вывод базы:** чисто CPU int8 для LargeV3-Turbo encoder почти исчерпано. Скачок к 2–3 c требует гетерогенного исполнения.

---

## 2. Статус ЭКСП-1: Vulkan-encoder + CPU-decoder (главный)

### Что сделано (коммит `bb2937c`)
- `CMakeLists.txt`: **`NCNN_VULKAN=ON`** + `NCNN_RUNTIME_CPU=ON`.
- **glslang submodule** подтянут: `git submodule update --init glslang` в `third_party/ncnn` (без этого `vulkan-shaders-gen` FAILED).
- `ncnn_jni.cpp`: `encoder.opt.use_vulkan_compute = true` + `use_subgroup_ops = false` (паттерн PhotonCamera; subgroup-ops — причина прошлого краша net.cpp:1361). decoder остаётся **CPU** (`use_vulkan_compute=false`) — точность, не зацикливается.
- Guard `ncnn::get_gpu_count()>0`; int8-encoder приоритетен даже на Vulkan.

### Реальные замеры (OPPO, AUTOSTT на test.f32, 2 прогона int8-Vulkan)
| Режим encoder | decoder | итого | Комментарий |
|---|---|---|---|
| **CPU int8, 8 thr** | CPU 0.44 s | ~10.1 s | база |
| **Vulkan int8** (subgroup off) | CPU 0.47 s | **~8.3 s** | encoder 6.45 / 6.95 s | 
| Vulkan fp32 | CPU 0.58 s | 12.6 s | encoder 11.24 s — **ХУЖЕ** int8 |

### Выводы по ЭКСП-1 (важно — корректировка плана)
- ✅ **Vulkan-encoder НЕ крашится** с `subgroup_ops=false` (решение старого краша net.cpp:1361).
- ✅ **decoder-CPU + encoder-GPU состояния корректны**: decoder не зацикливается, работает end-to-end.
- ‼️ **int8-Vulkan даёт лишь ~30%** (8.82→6.5–6.9 s), а **FP32-Vulkan медленнее int8** (11.2 s). Adreno int8-gemm на Vulkan слабый; раннее «FP32-Vulkan ×6 (3.09 s)» не воспроизвелось — вероятно был другой тест/версия.
- ⚠️ **Цель 2–3 s одним полным прогоном encoder НЕ достижима** на этом железе через один Vulkan-encoder. int8-Vulkan оптимум ≈ 6.5–7 s encoder.

**Вывод:** Vulkan-encoder полезен (стабилен, ~27% быстрее), но не является магией ×3. Для «мгновенности» нужны следующие уровни (4-bit/KV-quant) или потоковый пайплайн.

### Что в проде (24.09.2026)
- ncnn собран с `NCNN_VULKAN=ON` (резерв), но `use_vulkan_compute=true` **в ncnn_jni.cpp не активируется ни для одной подсети** (fbank/embed/decoder/proj_out — явно `false`; encoder — дефолт ncnn `false`). Vulkan-путь остаётся экспериментальным: включить = переставить `encoder.opt.use_vulkan_compute = true` в `load()` (см. паттерн §9) и пересобрать.
- ggml/whisper.cpp (`jni.c`): `ctx_params.use_gpu = false` навсегда — ggml-vulkan на Adreno выдаёт мусор и медленнее CPU.

---

## 3. Уточнённые следующие шаги (приоритет после ЭКСП-1)

1. **ЭКСП-3: 4-bit (INT4/FP4) + KV-cache-квантизация** — грузит память в ~2× меньше, на GPU обычно даёт ещё 1.5–2×. Ожидаемо encoder int4-Vulkan → ~4–5 s при лучшей точности памяти. Реалистичный промежуточный финиш.
2. **ЭКСП-5: потоковый пайплайн / чанкинг** — первый результат через 1–2 s, пока юзер говорит. Не снижает raw latency (останется ~7 s для полного файла), но **стремительно улучшает перцептивную латентность** — это путь к «ощущению 2–3 s».
3. (Отложено) **ЭКСП-6: NPU/QNN** — отдельная ветка, конвертация ncnn→QNN.

---

## 4. Прочие эксперименты (не основной шанс)

- **ЭКСП-2** (fp16/bf16 на Vulkan-encoder вместо fp32): на int8 уже fp16-хранение частично; fp32 нет. Может дать малый буст на GPU, но риск точности. Низкий приоритет.
- **ЭКСП-4** (set_cpu_powersave/привязка big.LITTLE): память-лимит — дёшево попробовать, ожидаемо мало.

---

## 5. Методология замеров (не менялась)

Диагностика: **nohup logcat → `/sdcard/stt.log`** + grep `NcnnWhisper` (`fbank=/encoder=/decoder=` в ncnn_jni.cpp). Буфер logcat на OPPO заливается AONLog/radio — стрим в файл обязателен.

Шаги:
1. `adb install -r app-debug.apk`
2. `adb shell am force-stop org.opencode.mobile.debug`
3. AUTOSTT (test.f32) ИЛИ ручной голосовой триггер.
4. снять `fbank=/encoder=/decoder=` из `/sdcard/stt.log`.
5. 3 прогона → минимум/медиану.

Качество: фикс-набор фраз (рус/англ, шум). Критерий — не потерять смысл (не только word-match).

---

## 6. Риски и что делать, если

- Vulkan-encoder всё же крашится → `use_bf16_storage` вместо fp16, либо workspace_allocator как в YOLOX; последний откат — CPU int8 (база сохранена git `2825837`/`afa32eb`).
- Vulkan неточен (decoder зацикливается) → decoder уже CPU; проверить конверсию Mat между backends (encoder-GPU→decoder-CPU).
- Шум logcat → стрим в файл обязателен.
- Нет Vulkan-драйвера → fallback CPU int8 автоматически (`get_gpu_count()==0`).

---

## 7. Чек-лист действий

- [x] ЭКСП-1: Vulkan-encoder =ON, `subgroup_ops=false`, decoder CPU. Замерено: int8-Vulkan 6.5–6.9 s (без краша, decoder корректен). Коммит `bb2937c`. → в проде выключен (см. §2).
- [ ] **ЭКСП-5: потоковый пайплайн/чанкинг** (UX-мгновенность 1–2 s) — главный следующий шаг.
- [ ] **ЭКСП-3: 4-bit + KV-quant** (следующий после потокового, ~4 s).
- [ ] WER-прогон фикс-набора после каждого.
- [x] **PR5: бенч-стенд готов** (генератор wav + BenchSttTest + CSV). Ждёт прогона на устройстве — см. §10.
- [ ] PR5: живой прогон матрицы int8/fp32 (3 повторения) → заполнить §10.2.
- [ ] Если стабильно и <3 s → ROADMAP Track 3 done.

---

## 8. Оборудование / контекст (актуально для бенч-прогонов)

- OPPO arm64-v8a, 8× AArch64 CPU, Adreno GPU (ColorOS).
- Stack: official ncnn master (`0a4e85a`), **NCNN_VULKAN=ON** (собран; glslang submodule подтянут).
- Сборка: только из vcvars64 (иначе `vulkan-shaders-gen-configure` FAILED). JAVA_HOME=`C:\Program Files\Android\Android Studio\jbr`.
- APK debug `org.opencode.mobile.debug` (run-as работает).

---

## 9. Наработки из индустрии (gh_grep)

- PhotonCamera `ncnnMl.cpp`: `use_vulkan_compute=true` + `use_subgroup_ops=false` + fp16 — рабочий паттерн Adreno (subgroup = причина краша на Adreno/Mali).
- ncnn_llm (futz12): вынос encoder на Vulkan + bf16-storage, decoder отдельной сетью.
- YOLOX-android: `use_vulkan_compute=true` + `workspace_allocator` + `use_packing_layout`.
- whisper.cpp ggml-vulkan — валидация Vulkan-пути для Whisper.

## 10. Бенч-стенд (PR5) — воспроизводимые замеры латентности

Зачем: старые замеры (§1–2) снимались вручную (AUTOSTT + разбор logcat), их сложно
повторять и сравнивать. PR5 добавляет **инструментированный стенд**: фикс-набор wav,
тест-класс и CSV-выгрузку. Прогон полностью автономный (модель уже на устройстве).

### 10.1 Состав стенда

| Компонент | Что | Где |
|---|---|---|
| Генератор | ставит silence/noise/tone/jfk + (опц.) ru | `tools/gen_bench_wavs.py` |
| Набор wav | 16 кГц mono PCM16 | `app/src/androidTest/assets/bench/` |
| Бенч-тест | init → warmup → 3×транскрипция, медиана | `BenchSttTest.kt` |
| Выход | logcat `STTBENCH` + CSV | `/sdcard/Android/data/org.opencode.mobile.debug/files/stt-bench.csv` |

Модели (обе лежат в `files/models/`):
- **int8**: `ncnn-turbo/` — прод-каталог (encoder int8, 694 МБ bin);
- **fp32**: `ncnn-bench-fp32/` — копия `ncnn-turbo/` БЕЗ файлов `*_encoder_int8.*`
  (load() тогда берёт fp32-encoder 1219 МБ). Оба варианта уже конвертированы:
  `tools/ncnn-int8/turbo/`.

Ограничение: `g_whisper` в C++ — синглтон; конфиги считаются ПОСЛЕДОВАТЕЛЬНО
(release() между). Потоки: `setThreads(8)` после init (encoder gemm фиксируется
при load — для матрицы «потоки» нужна пересборка с другими значениями в
`nativeInit`: encoder=8/decoder=4 сейчас).

### 10.2 Матрица (заполняется живым прогоном на устройстве)

| Модель | Вход | t₁ | t₂ | t₃ | медиана | Текст (≈) |
|---|---|---|---|---|---|---|
| int8-CPU | silence | | | | | (пусто) |
| int8-CPU | noise | | | | | (пусто) |
| int8-CPU | tone | | | | | — |
| int8-CPU | jfk (en) | | | | | — |
| fp32-CPU | silence | | | | | |
| fp32-CPU | noise | | | | | |
| fp32-CPU | tone | | | | | |
| fp32-CPU | jfk (en) | | | | | |

База для сверки (29.08–02.09, 48k сэмплов, lang=ru, **int8-CPU**): fbank ≈ 0.8 с,
encoder ≈ 8.8 с, decoder ≈ 0.5 с, полный цикл ≈ 10.1 с; fp32-CPU encoder ≈ 17.9 с;
int8-Vulkan (эксп.) ≈ 6.5 с. Бенч-стенд меряет ПОЛНЫЙ ncnn-цикл (как юзер видит).

### 10.3 Как прогнать

```bash
# 1. Сгенерировать wav-набор (или уже в git) + закинуть fp32-копию на устройство:
python tools/gen_bench_wavs.py
# на устройстве (после первого прогона int8), один раз:
adb shell run-as org.opencode.mobile.debug sh -c 'cp -r files/models/ncnn-turbo files/models/ncnn-bench-fp32 && rm files/models/ncnn-bench-fp32/whisper_turbo_encoder_int8.ncnn.*'
# 2. Собрать и прогнать (устройство по adb):
#    ./gradlew :app:connectedDebugAndroidTest  (классы: SmokeSttTest + BenchSttTest)
# 3. Результат:
adb shell run-as org.opencode.mobile.debug cat files/stt-bench.csv   # версия для pull:
adb pull /sdcard/Android/data/org.opencode.mobile.debug/files/stt-bench.csv .
```

Внимание: `run-as` команда с `&&` ломается в PowerShell — выполнять её через
`adb shell` целиком в кавычках либо bash-оболочкой.

---
