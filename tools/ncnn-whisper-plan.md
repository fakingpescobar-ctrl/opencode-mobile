---
status: in-progress
phase: 1
updated: 2026-08-30
---

# План: ncnn-whisper (Vulkan) → opencode-mobile

## Goal
Перевести STT opencode-mobile на ncnn Vulkan (Adreno GPU) вместо CPU whisper.cpp, сохранив whisper.cpp как fallback, достигнув usable STT <10с на OPPO.

## Context & Decisions
| Decision | Rationale | Source |
|----------|-----------|--------|
| Добавить ncnn параллельно, не заменять whisper.cpp | Ноль риска сломать рабочий STT, A/B замер, откат флагом | `ref:ncnn-plan-general` + `ref:ncnn-plan-critic` |
| Статическая сборка ncnn из исходников (git clone) | maven/jitpack на ncnn нет, GitHub releases заблокированы; static → нет нового .so в APK | `ref:ncnn-plan-general` |
| Рантайм-классы Whisper/Tokenizer из Tencent/ncnn/examples/whisper.cpp | ПОДТВЕРЖДЕНО вручную: файл 28KB, HTTP 200, классы Whisper/Tokenizer/Result на месте (строки 88, 295) | `ref:verify-examples-whisper` |
| nihui/ncnn-android-whisper = ТОЛЬКО конвертер (README + export_ncnn.py) | НЕ содержит app-кода; использовать только для экспорта моделей | `ref:ncnn-plan-critic` + `ref:verify-readme` |
| Стартовая модель whisper-base (не turbo) | Конвертация ~5GB RAM вместо 16GB, ~190MB файлы | `ref:ncnn-plan-general` |
| Вход модели — std::vector<short> (int16), жёсткий кап 30с (480000 сэмплов) | Подтверждено в ncnn whisper.cpp: transcribe(vector<short>), waveform(480000) | `ref:verify-examples-whisper` |
| Доставка ncnn-base через ModelDownloader (HF), НЕ в assets | assets раздул бы APK до ~300MB+; один механизм доставки с turbo | `ref:ncnn-plan-critic` |

## Phase 1: Разведка + Конвертация модели [IN PROGRESS]
- [ ] **1.1 Проверка готового рантайма в Tencent/ncnn** ← CURRENT
  - Файл `examples/whisper.cpp` из `Tencent/ncnn@master` (ПОДТВЕРЖДЁН: 28KB, классы `class Whisper` стр.295, `class Tokenizer` стр.88, `transcribe(vector<short>, lang, text)` стр.301).
  - Скопировать полный `examples/whisper.cpp` (28432 байт) в workspace для изучения: `C:\Users\OLD\AppData\Local\Temp\opencode\ncnn_whisper.cpp` уже скачан.
  - Уточнить токен ru: `token_lang_first` + индекс ru (проверить в ncnn whisper.cpp, критик утверждал 50261 для ru).
- [ ] 1.2 Установка окружения: `py -3 -m pip install torch torchaudio transformers pnnx` (CPU-индекс `--index-url https://download.pytorch.org/whl/cpu`; torchvision НЕ нужен)
- [ ] 1.3 Скачать конвертер через jsdelivr: `curl -L -o export_ncnn.py https://cdn.jsdelivr.net/gh/nihui/ncnn-android-whisper@master/export_ncnn.py`
- [ ] 1.4 Правки export_ncnn.py: раскомментить whisper-base (num_mel_bins=80, d_model=512); Windows-фикс `os.system("python3 -c '...'")` → `subprocess.run([sys.executable, "-c", ...])` с double-quotes
- [ ] 1.5 Запуск `py -3 export_ncnn.py` → выход `whisper_base_{fbank,encoder,embed_token,embed_position,decoder,proj_out}.ncnn.{param,bin}` (12 файлов) 
- [ ] 1.6 **CHECKPOINT после конвертации:** декодер .param должен содержать `MultiHeadAttention` с `7=1` (KV-cache) и Gemm `7=0` — иначе JNI-декодер будет мусорным, СТОП
- [ ] 1.7 vocab: `vocab.json` от `openai/whisper-base` → `whisper_vocab.txt` — ровно порядок keys vocab.json (50257 строк base), НИЧЕГО не дописывать (сдвиг id сломает декодер); байтовые `<0xXX>` уже есть в vocab.json, не потерять при конверсии; special-токены decode() берёт из констант кода, не из txt

## Phase 2: Сборка ncnn arm64-v8a Vulkan (статическая) [PENDING]
- [ ] 2.1 `git clone --recurse-submodules --depth 1 https://github.com/Tencent/ncnn.git third_party/ncnn` (glslang submodule обязателен; ~100-200MB, ретраи на DPI)
- [ ] 2.2 cmake: `-DNCNN_VULKAN=ON -DNCNN_BUILD_TOOLS=OFF -DNCNN_BUILD_EXAMPLES=OFF -DNCNN_SHARED=OFF`, ABI arm64-v8a, ANDROID_PLATFORM по minSdk (сверить — libvulkan с API 24+, OPPO Android 16 ок) → `libncnn.a`
- [ ] 2.3 Добавить `third_party/` в `.gitignore`

## Phase 3: JNI-слой ncnn [PENDING]
- [ ] 3.1 `whisperlib/src/main/jni/ncnn/ncnn_jni.cpp` — перенести классы Whisper/Tokenizer + fbank/encoder/embed_token/embed_position/decoder/proj_out неты ИЗ `Tencent/ncnn/examples/whisper.cpp` (ПОДТВЕРЖДЁН источник). Правки:
  - `load(modelDir)`: абсолютные пути `<modelDir>/whisper_base_*.ncnn.param|bin` + vocab; язык настраиваемый, дефолт "ru"
  - **mel-бины НЕ параметризовать**: fbank — своя ncnn-компонента (1 из 6), мел-фильтры (80/128) выпекаются в её .bin при экспорте → грузишь правильную пару файлов, рассинхрона нет
  - JNI: `nativeNcnnInit(modelDir):Boolean`, `nativeNcnnTranscribe(samples:FloatArray, lang:String):String` (float→short `clamp[-1,1]` затем `round(*32767)` → `vector<short>`; сборка байтовых токенов кириллицы в UTF-8 в C++; вернуть NewStringUTF), `nativeNcnnFree()` (вызвать `destroy_gpu_instance()` последним)
  - `create_gpu_instance()` ДО конструирования любой `ncnn::Net` (все 6 сетей load()); чек `ncnn::get_gpu_count()==0` → лог + `use_vulkan_compute=false` (CPU-фоллбек)
  - `net.opt.use_vulkan_compute=true`; **`num_threads=4-6`** (Snapdragon 1+5+2 big-ядра) — честный A/B с whisper.cpp (8 потоков)
  - Декодер greedy + **жёсткий max-step cap ~224-448 токенов** (иначе нет eot → вечный цикл/зависший JNI)
  - link-флаги: `-Wl,-z,max-page-size=16384` (page-size, NDK r27 дефолт 4KB)
- [ ] 3.2 CMake `whisperlib/src/main/jni/whisper/CMakeLists.txt` ДОБАВИТЬ (не трогая существующие таргеты whisper/whisper_v8fp16_va): `add_subdirectory(NCNN_DIR)`, `add_library(ncnnwhisper SHARED ncnn_jni.cpp)`, `target_link_libraries(ncnnwhisper ncnn vulkan log android)`
- [ ] 3.3 Kotlin `NcnnWhisperContext.kt` (com.whispercpp.whisper): `createFromFilesDir(dir)` — файлы модели тянуть из filesDir (ModelDownloader), `nativeNcnnInit`; `suspend transcribeData(FloatArray):String` на Dispatchers.Default

## Phase 4: Kotlin-интеграция [PENDING]
- [ ] 4.1 `WhisperTranscribeService`: кэш `HashMap<String, Any>`, `obtainContext` ветвится по движку, ключ `"ncnn:base"`
- [ ] 4.2 ChatOverlay.kt: SharedPreferences `"chat_overlay"`, ключ `stt_engine` (рядом `stt_model`, строка 172); UI кнопка NCNN
- [ ] 4.3 Замер `SystemClock.elapsedRealtime()` вокруг transcribeData → `Log.d("VOICE", "ncnn: ${ms}мс")`

## Phase 5: Тест на OPPO [PENDING]
- [ ] 5.1 Сборка + adb install + `logcat -s VOICE ModelDL`
- [ ] 5.2 **Прогревочный прогон** (первый GPU-инференс на Adreno компилит пайплайны ncnn — медленно) перед замерами
- [ ] 5.3 Фразы: техтермины / числа / длинная 15-20с / тишина+шум (галлюцинации — у ncnn НЕТ no_speech фильтра)
- [ ] 5.4 **30с+ запись**: рантайм НЕ режет на окна (лишь truncates до 480000 + паддит нулями). Для v1 — лимит записи ~25-28с в AudioRecorder/UI; нарезка с overlap → Phase 6 backlog. 5.4 = «запись >30с предсказуемо капится»
- [ ] 5.5 Метрика release→текст, ncnn-base vs whisper.cpp-base; критерий: usable <10с, читабелен, 0 крашей на 5 запросах

## Phase 6: Оптимизация [PENDING]
- [ ] 6.1 small ~540MB той же процедурой (28 mel-вариант)
- [ ] 6.2 turbo (переключить в корне whisper.cpp блок turbo-токенов: translate=50359, transcribe=50360, startoflm=50361, startofprev=50362, nocaptions=50363, notimestamps=50364, timestamps 50365–51865; mel=128 запечён в fbank .bin; ~3GB RAM влезает) — СВОЙ ПОЛНЫЙ комплект 12 файлов + vocab; доставка через ModelDownloader со СВОЕГО HF-репозитория (HF работает), тянет ВСЕ 12 файлов
- [ ] 6.3 fp16-флаги ncnn на Adreno (`use_fp16_packed/storage`) — замер
- [ ] 6.4 beam search — СВОЯ реализация (не флажок), отдельная задача, низкий приоритет
- [ ] 6.5 Если ncnn победил — флаг по умолчанию, whisper.cpp как fallback

## Notes
- 2026-08-30: Проверено вручную — Tencent/ncnn master содержит официальный `examples/whisper.cpp` (HTTP 200, 28KB), классы Whisper/Tokenizer/Result на месте. Критик ошибочно искал их в nihui/ncnn-android-whisper (там только конвертер). Источник рантайма — ncnn, НЕ nihui-репо. `ref:verify-examples-whisper`
- 2026-08-30: Вход ncnn — `std::vector<short>` (int16), кап 30с (480000 сэмплов) — подтверждено в исходнике. AudioRecorder даёт FloatArray -1..1 → конвертить round(*32767) в C++. `ref:verify-examples-whisper`
- 2026-08-30: Критик REJECT → правки: фикс источника рантайма, ModelDownloader вместо assets, GPU lifecycle (destroy_gpu_instance), num_threads, прогрев-пайплайн, 30с-нарезка, байтовые токены кириллицы, мета-проверки после конвертации, время среза заложено с запасом (3-5 дней). `ref:ncnn-plan-critic`
- 2026-08-30: Критик APPROVE (2-й прогон). Поправки внесены: turbo token ids (50359/50360/50361/50362/50363/50364/50365–51865), 30с решение = лимит записи 25-28с (не нарезка в рантайме), greedy max-step cap 224-448, vocab.txt без дописок (50257 строк), create_gpu_instance до Net+get_gpu_count чек, clamp[-1,1] перед *32767, page-size=16384, ncnn статикой +8-15MB в .so, base fp32 ~290-310MB download (показать размер в UI), fbank mel не параметризовать (выпечен). `ref:ncnn-plan-critic-2`
