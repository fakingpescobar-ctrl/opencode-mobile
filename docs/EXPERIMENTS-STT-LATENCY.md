# Эксперименты по ускорению STT (Track 3)

`opencode-mobile` — локальное распознавание Whisper Large-v3-Turbo на Android (OPPO, 8 ядер ARM CPU + Adreno GPU).
Цель — выйти с текущего `encoder ≈ 8.8 c` (CPU, int8) на «полное распознавание 2–3 c» без потери качества (WER).

Дата: 02.09.2026 (обновлено 25.09.2026). Статус: **ЭКСП-1 выполнен — Vulkan-encoder int8 ~27% быстрее CPU (6.45 vs 8.82 s), но НЕ ×3; Vulkan в проде выключен** (см. §2 «Что в проде»). **ЭКСП-5 выполнен — VAD-сегментация + чанкинг: длинное аудио (37 с) транскрибируется полностью (RTF≈2.3 на int8-CPU), галлюцинация «Продолжение следует…» устранена** (см. §11). База: **encoder int8-CPU 8.82 s**.

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
| int8-CPU | silence | 6814 | 6775 | 7059 | **6814** | Продолжение следует… (галл.) |
| int8-CPU | noise | 6987 | 6884 | 6892 | **6892** | Хороший вечер DimaTorzok (галл.) |
| int8-CPU | tone | 7479 | 8228 | 8281 | **8228** | Поехали! |
| int8-CPU | jfk (en) | 7386 | 7263 | 7390 | **7386** | В реке, текущей мимо деревни… |
| fp32-CPU | silence | 14583 | 14506 | 14621 | **14583** | Продолжение следует… (галл.) |
| fp32-CPU | noise | 14576 | 14552 | 14694 | **14576** | Хороший вечер DimaTorzok (галл.) |
| fp32-CPU | tone | 14469 | 14463 | 14535 | **14469** | Поехали! |
| fp32-CPU | jfk (en) | 15190 | 15129 | 15614 | **15190** | В реке, текущей мимо деревни… |

Полный прогон: `:app:connectedDebugAndroidTest` вручную (am instrument), 2 теста OK,
24.09.2026, OPPO CPH2747 (arm64). CSV: `docs/stt-bench-2026-09-24.csv`.

Выводы:
- **int8-CPU 6.8–8.2 s полный цикл** — заметно лучше старой базы «≈10.1 s» (потоки
  `setThreads(8)` в дело: старые замеры шли до фиксации потоков). Фактически почти
  догоняет ЭКСП-1 Vulkan (6.5 s) без GPU-рисков. int8 vs fp32 = **2.1×** — выбор
  int8 в проде подтверждён (декодер при этом не int8 и не страдает).
- Сигнал-вал: вход почти не влияет (silence 1.5s → 6.8s; jfk 10s → 7.4s) — платится
  фиксированный накладной encoder+load, а не длина речи. Для UX важнее пайплайн,
  чем ещё раз конвертация.
- **Галлюцинации на тишине/шуме** («Продолжение следует…», «Хороший вечер
  DimaTorzok») у int8 и fp32 ОДИНАКОВО — движок без VAD/энерго-фильтра. Проблема
  не в int8, а в Whisper Turbo в целом → нужен входной VAD или фильтр «пустого»
  (энергия < порога → вернуть пусто), кандидат в ЭКСП-5.
- Воспроизводимость отличная: разброс 1–7% (int8 jfk 7263–7390).

### 10.3 Как прогнать

```bash
# 1. Собрать APK С нативкой (cridical: обычный gradle с -PskipNativeBuild даёт APK
#    БЕЗ whisper-библиотек → dlopen fail):
./build-install.ps1          # vcvars + :app:assembleDebug (без скипа)
./gradlew :app:assembleDebugAndroidTest   # тест-APK, без скипа
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk

# 2. Доставка моделей. ПРОВЕРЕНО: run-as НЕ видит /sdcard (FUSE ColorOS) —
#    только через /data/local/tmp + chmod:
#    (файлы моделей: tools/ncnn-int8/turbo/ — полный int8+fp32 конверт)
adb push tools/ncnn-int8/turbo /data/local/tmp/ncnn-turbo    # после удаления fp32 encoder
adb push fp32-копия /data/local/tmp/ncnn-bench-fp32          # без *_encoder_int8.*
adb shell "chmod -R 777 /data/local/tmp/ncnn-turbo /data/local/tmp/ncnn-bench-fp32 && run-as org.opencode.mobile.debug sh -c 'mkdir -p files/models && cp -r /data/local/tmp/ncnn-turbo files/models/ && cp -r /data/local/tmp/ncnn-bench-fp32 files/models/'"
# ВНИМАНИЕ: install -r на ColorOS стирает данные приложения — раскатку моделей
# делать ПОСЛЕ установки APK, и после неё НЕ переустанавливать.

# 3. Прогон (2 теста: BenchSttTest + SmokeSttTest), результат текст в logcat STTBENCH:
adb logcat -c
adb shell am instrument -w -r org.opencode.mobile.debug.test/androidx.test.runner.AndroidJUnitRunner

# 4. Результат:
adb pull /sdcard/Android/data/org.opencode.mobile.debug/files/stt-bench.csv .
```

---

## 11. ЭКСП-5: VAD-сегментация + чанкинг (анти-галлюцинации и потеря хвоста >30 с)

Зачем: §10 показал, что (а) движок галлюцинирует на тишине/шуме («Продолжение
следует…», «Хороший вечер DimaTorzok»), (б) ncnn-encoder фиксирован на 30 с —
аудио длиннее обрезается, хвост теряется. ЭКСП-5 добавляет входной VAD
(SpeechSegmenter) и по-сегментную транскрипцию с конкатенацией (ChunkedTranscriber).

### 11.1 Состав

| Компонент | Описание |
|---|---|
| `stt/SpeechSegmenter.kt` | VAD: кадры 30 мс, RMS; порог = max(10-й перцентиль ×3, MIN_RMS 0.015); voiced-маска с прилипанием (1 из 2 соседей); сегменты: PRE_ROLL_MS 150 + речь + PAD_MS 450; MIN_SPEECH_MS 400; жёсткий лимит MAX_SEGMENT_MS 28 с (без переполнения энкодера) |
| `stt/ChunkedTranscriber.kt` | `transcribe(context, samples, model, engine)`: сегменты → WhisperTranscribeService (whisper-движок добивает до 3 с паддингом, ncnn — фикс. 30 с) → конкатенация; ошибка сегмента валит всё |
| `ChatOverlay.stopVoice` | вся запись теперь идёт через ChunkedTranscriber (было: обрезка до 30 с + «Продолжение следует»…) |
| `BenchSttTest.benchChunkedLong` | вход `bench/long.wav` (37 с = 3×jfk + 2 паузы по 2 с, шум 0.02): assert ≥2 сегмента, текст непуст, нет «Продолжение следует» |

### 11.2 Результат живого прогона (25.09.2026, OPPO CPH2747, ncnn-turbo int8-CPU)

```
OK (1 test), 84.8 s  →  12 сегментов, покрытие 0–33990 мс (весь трек!)
BENCH_ROW chunked,long,84658,---,---,84658,
  "И так, мои дорогие американцы. Не спрашивай! что ваша страна может сделать для вас.
   Попросите, что вы можете сделать для вашего страны. … (все 9 фраз jfk собраны)"
```

- RTF ≈ 2.3 (84.6 с на 37 с аудио, 12 отдельных прогонов сегментов).
- Хвост >30 с больше НЕ теряется — весь трек распознан, «Продолжение следует…» нет.
- На стыках пауз встречаются короткие дубли-галлюцинации («Не скажи!», «ПОДПИШИСЬ
  НА КАНАЛ!», сегменты 1 с) — поток не теряется, кандидат на доработку: отбрасывать
  1-сегментные куски с rms на границе шума (MIN_SPEECH грань 400 мс).

### 11.3 Как прогнать

```bash
adb logcat -c
adb shell am instrument -w -r -e class org.opencode.mobile.BenchSttTest#benchChunkedLong \
  org.opencode.mobile.debug.test/androidx.test.runner.AndroidJUnitRunner
# результат: logcat CHUNKED + STTBENCH (BENCH_ROW chunked,long,…)
```

JVM-тесты сегментера (без устройства): `:app:testDebugUnitTest -PskipNativeBuild=true`
(SpeechSegmenterTest, 5/5: пауза-разделитель, тишина, шум+хлопок, лимит 28 с, pre-roll).

### 11.4 Выводы

- VAD-порог от 10-го перцентиля адаптивен к уровню записи; равномерный тон/синус
  без амплитудной модуляции VAD считает шумом (тест с модуляцией слогов 3.5 Гц).
- ncnn-движок: сегменты независимы (промпта нет) — склейка конкатенацией корректна.
- Прод-кандидат: микрофон — VAD → чанкинг уже в `ChatOverlay.stopVoice` (ЭКСП-5
  включён в основной пайплайн записи).

---

## 12. ЭКСП-6: пофазный профиль + lazy-чанкинг (25.09.2026)

### 12.1 Замер (живой, OPPO CPH2747, ncnn-turbo int8-CPU, 8 потоков)

Профиль фаз из C++ (JNI `nativeLatencyProfile`: fbank/encoder/decoder/steps,
заполняются в `transcribe` в `g_last_*_ms`), CSV:
`docs/stt-bench-2026-09-25-phases.csv` (дубль во внутреннем filesDir
`run-as ... cat files/bench/stt-bench.csv` + logcat STTBENCH):

| вход | медиана | fbank | encoder | decoder | шаги |
|------|---------|-------|---------|---------|------|
| jfk 10с | 7643 | 407 | 6042 | 1078 | 38 |
| long 37с | 8009 | 417 | 5729 | 1764 | 68 |
| noise 1.5с | 7848 | 599 | 6335 | 691 | 12 |
| silence 1.5с | 7682 | 467 | 6516 | 551 | 7 |
| tone 3с | 7599 | 474 | 6560 | 562 | 5 |

Вывод: encoder = 5.7–6.6с = 78–85% общей задержки и НЕ зависит от длины входа
(1.5с тишины → 6.5с encoder). «Восемь секунд на фразу» = 6.5с encoder + ~1с
decoder + overhead. fbank ничтожен; декодер ~26–79мс/шаг; галлюцинации на
не-речи (noise/silence/tone) добавляют лишние шаги декодера.

### 12.2 Lazy-чанкинг (продуктовый фикс)

Прод-чанкинг умножал стоимость: каждый сегмент платил полный encoder
(ЭКСП-5: 12 сегментов long.wav = 84.6с на 37с клип). Фикс в `ChunkedTranscriber`:
клип ≤ 28с (лимит ncnn 30с минус запас) на ncnn-движке выполняется ОДНИМ
прогоном без VAD-сегментации (`MAX_SINGLE_PASS_SAMPLES = 28 * 16000`).

Проверка `benchLazyShort`: клип 15.5с (jfk 11с + silence 1.5с + tone 3с) — при
старом VAD-пути было бы ≥2 сегмента (≈2×encoder ≈ 15с+), lazy даёт тёплый
одиночный прогон **8657 мс** (~1.7×). Текст распознан целиком. Тест использует
прогрев (холодный init контекста ~3–4с к скорости одного прогона не относится
— в проде контекст живёт между распознаваниями).

### 12.3 Троттлинг

Во втором прогоне (после 5 подряд тяжёлых транскрипций) encoder разогрелся
с 6.0 до 7.4с (tone 7423мс) — учитывать при сравнении серийных прогонов.

### 12.4 Vulkan: закрыто (ЭКСП-1, 02.09.2026)

GPGPU-путь УЖЕ пробован: int8-Vulkan encoder 6.45-6.9с vs FP32-Vulkan 11.2с vs
int8-CPU (8 потоков, сегодня) 6.0-6.6с. CPU int8 на 8 потоках = паритет с
Vulkan-вариантом, БЕЗ рисков: Vulkan-encoder зависил с decoder на живой речи
(шаг kvidx=16, конверсия больших KV-состояний GPU→CPU, реальный голос вешал
распознавание навсегда). Решение 02.09: encoder на CPU int8 навсегда;
ускорение Vulkan — отдельная большая задача с мостом KV, не окупается.

---
