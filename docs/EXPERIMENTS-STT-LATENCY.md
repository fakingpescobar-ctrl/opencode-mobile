# Эксперименты по ускорению STT (Track 3)

`opencode-mobile` — локальное распознавание Whisper Large-v3-Turbo на Android (OPPO, 8 ядер ARM CPU + Adreno GPU).
Цель этого документа — **план исследования и тестов**, чтобы выйти с текущего `encoder ≈ 8.8 c` (CPU, int8) на `полное распознавание 2–3 c` без потери качества (WER).

Дата: 02.09.2026. Статус: план.

---

## 1. Текущее состояние (базовая линия)

| Метрика | Значение | Режим |
|---|---|---|
| fbank | ~750 ms | CPU fp32 |
| **encoder** | **8.82 s** | CPU int8 (block-quant), 8 потоков |
| decoder | ~0.5–0.6 s | CPU fp16/32, KV-cache, 7 шагов · ~81 ms/шаг |
| **полный ncnn** | ~10.1 s | 48k сэмплов, lang=ru |
| целя | **2–3 s** | — |

Промежуточные замеры:
- encoder int8 ≈ 9.03 s (4 потока) → 8.82 s (8 потоков) — потоки дали лишь ~210 ms ⇒ **уперлись в ширину памяти/потолочную производительность CPU**, не в потоки.
- fp32 encoder ≈ 17.9 s. int8 дал ~2×.

**Вывод базы:** чисто CPU int8 для LargeV3-Turbo encoder почти исчерпано. Резкий скачок к 2–3 c требует **гетерогенного исполнения** (вынос части на GPU/NPU).

---

## 2. Гипотезы и направления (по наработкам индустрии)

Из кодовой базы/паттернов (gh_grep, реальные проекты):

1. **Vulkan-encoder + CPU-decoder** — самый реальный путь.
   - ncnn позволяет задавать `opt.use_vulkan_compute` **на уровень отдельного `ncnn::Net`**. У нас encoder и decoder — разные Net ⇒ можно «энкодер на GPU, декодер на CPU».
   - **PhotonCamera** (`eszdman/PhotonCamera`, продакшн): на Adreno/Mali Vulkan + fp16 работает, но **`use_subgroup_ops = false`** — прямо в коде: *«subgroup ops disabled (crash on Adreno/Mali)»*.
   - **Вероятная причина нашего прошлого краша `net.cpp:1361`** — subgroup-ops, а не Vulkan сам по себе. Значит есть шанс включить Vulkan-encoder с `subgroup_ops=false`.
   - **ncnn_llm / YOLOX-android** — проверенные схемы `use_vulkan_compute=true` + `workspace_allocator` + `use_packing_layout`.

2. **fp16/bf16 storage на Vulkan-encoder** — на GPU даёт доп. ускорение (tensor-core). Риск точности уже на int8 низкий.

3. **4-bit (INT4/FP4/MXFP4) + KV-cache-квантизация** — следующий уровень после int8 (грузит память в 2× меньше). Максимальный эффект — на GPU.

4. **NPU/DSP (Snapdragon QNN/Hexagon)** — может целиком взять энкодер, но нетривиальная конвертация ncnn→QNN из нашего стека. Отложить (справочно).

5. **Потоковый пайплайн / чанкинг** — распознавать чанки по 10–15 c в фоне ⇒ юзер видит результат почти мгновенно, даже если raw latency одного файла остаётся ≈9 c. Улучшение перцептивной латентности.

---

## 3. Критерий успеха и методология замеров

### Цель
- **encoder ≤ 2.5 s** (при текущих ~3× ускорении GPU это реалистично).
- **полное распознавание ≤ 3 s**.
- **WER не хуже текущего** (или не более +1–2 п.п.).

### Как замеряем (воспроизводимо, на устройстве)
Диагностика через **nohup logcat → файл `/sdcard/stt.log`** + grep `NcnnWhisper` (логи `fbank=/encoder=/decoder=` уже добавлены в `ncnn_jni.cpp`). Буфер logcat на OPPO заливается AONLog/radio — стрим в файл обязателен.

Шаги:
1. `adb install -r app-debug.apk`
2. `adb shell am force-stop org.opencode.mobile.debug`
3. запуск AUTOSTT (на `test.f32`) ИЛИ ручной голосовой триггер (микрофон в UI).
4. снять `fbank= / encoder= / decoder=` из `/sdcard/stt.log`.
5. повторить 3 прогона, взять **минимум/медиану** (шум CPU-автодросслинга).

### Качество (WER)
- Прогнать фиксированный набор фраз на `test.f32` + реальных голосовых (рус/англ, шум).
- Сравнить текст до/после изменения. Критерий — **не потерять смысл** (не только word-match).

---

## 4. Эксперименты (по порядку приоритета)

### ЭКСП-1: Vulkan-encoder с subgroup_ops=OFF, decoder CPU (главный)
- **Что:** в `ncnn_jni.cpp` включить `encoder.opt.use_vulkan_compute = true`, `encoder.opt.use_subgroup_ops = false`, `use_fp16_*` для encoder. decoder остаётся CPU (`use_vulkan_compute=false`).
- **Заодно:** `ncnn::get_gpu_count()>0` guard; `workspace_allocator`/`shader_storage_buffer` для Vulkan (как в YOLOX).
- **Ожидание:** encoder 8.8 → 2–4 s (2–3×).
- **Риск:** краш net.cpp:1361; зацикливание decoder (признак неточности GPU-состояний — тогда decoder НЕ трогаем, он CPU).
- **Проверка точности:** WER по фикс-набору.

### ЭКСП-2: если ЭКСП-1 крашится — отключить fp16_arithmetic, оставить fp16_storage
- Подбор: сначала `use_fp16_storage=true`, `use_fp16_arithmetic=false` (арифметика fp32, хранение fp16 — стабильнее на Adreno).
- Найти минимум fp16-опций, чтобы работало и не сыпалось.

### ЭКСП-3: 4-bit encoder + KV-cache квантизация
- Только после того как Vulkan-encoder подтвердил стабильность. Дать доп. 1.5–2×.

### ЭКСП-4: распределение потоков по ядрам (set_cpu_powersave / big.LITTLE)
- Попробовать привязать encoder к быстрым big-ядрам (`ncnn::set_cpu_powersave(1)`), decoder — на средней группе.
- Ожидаемо небольшое (память-бандвит limit), но дёшево.

### ЭКСП-5: потоковый пайплайн (UX-латентность) — справочно, потом
- Чанкинг + параллельон: первый чанк → encoder → decoder, пока юзер говорит.
- Не снижает raw latency, но делает продукт «мгновенным» на слух.

### ЭКСП-6 (справочно, отложено): NPU/QNN.
- Только если Vulkan не даст нужного скачка; конвертация ncnn→QNN — отдельная ветка.

---

## 5. Риски и что делать, если

- **Vulkan-encoder крашится** (net.cpp:1361) → точнее: греп по logcat исключает `subgroup` — если всё равно падает, пробуем `use_bf16_storage` вместо fp16 или откатываемся к CPU int8 (базовая линия сохранена в git коммите `2825837`).
- **Vulkan-encoder неточен** (decoder зацикливается) → decoder уже на CPU, проверить только связку вход/выход между encoder-GPU и decoder-CPU (преобразование Mat между backends).
- **Шум в logcat** → стрим в файл (`/sdcard/stt.log`) обязателен.
- Нет Vulkan-драйвера/не 0 GPU → fallback на CPU int8 автоматически (`ncnn::get_gpu_count()==0`).

---

## 6. Чек-лист действий

- [ ] Правка `ncnn_jni.cpp`: Vulkan-encoder (ЭКСП-1), `subgroup_ops=false`, гвардия GPU.
- [ ] Пересборка apk (vcvars; скрипт `run-ncnn-gradle-release.ps1`).
- [ ] Три прогона AUTOSTT + ручной голос; снять fbank/encoder/decoder из `/sdcard/stt.log`.
- [ ] WER-прогон фикс-набора фраз.
- [ ] Если стабильно и <3 s → коммит + обновить ROADMAP (Track 3 done).
- [ ] Если не достигли 2–3 s → 4-bit (ЭКСП-3) → NPU (ЭКСП-6).

---

## 7. Оборудование / контекст

- Устройство: OPPO (arm64-v8a), 8 ядер CPU, Adreno GPU (ColorOS/Android).
- Stack: ncnn (official Tencent master, commit `0a4e85a`), VULKAN=OFF сейчас в CMakeLists — для ЭКСП-1 нужен Vulkan-нейтивный соборот ncnn. Проверить, что `use_vulkan_compute` не требует пересборки ncnn с Vulkan (нет — Vulkan собран, включается на уровне opt сети; CMake VULKAN=OFF влияло на компиляцию — для Vulkan-слоёв надо вернуть VULKAN=ON).
- Сборка: только из vcvars64 (иначе `vulkan-shaders-gen-configure` FAILED). JAVA_HOME=`C:\Program Files\Android\Android Studio\jbr`.
- APK debug: `org.opencode.mobile.debug` (run-as работает).

---

## 8. Ссылки/наработки (из gh_grep)

- PhotonCamera `ncnnMl.cpp`: `use_vulkan_compute=true` + `use_subgroup_ops=false` + fp16 — рабочий паттерн Adreno (комментарий про краш на Adreno/Mali от subgroup).
- ncnn_llm (futz12): вынос encoder/vision на Vulkan + bf16-storage, decoder в отдельной сети.
- YOLOX-android: `use_vulkan_compute=true` + `workspace_allocator` + `use_packing_layout`.
- whisper.cpp ggml-vulkan backend — валидация, что Vulkan-путь реален для Whisper.