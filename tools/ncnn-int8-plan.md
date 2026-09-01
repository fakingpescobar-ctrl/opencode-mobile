# План: int8-квантование энкодера whisper-large-v3-turbo (ncnn, Android)

**Дата:** 01.09.2026 (продолжение работ от 31.08.2026)
**Цель:** encoder < 8с, полная транскрипция < 10с на OPPO CPH2747 (CPU), текст 1:1 с fp32 на тест-фразах «Привет, брат, проверка связи.» / «Расскажи анекдот про цыгана.»
**Базлайн:** fbank 0.8с + encoder fp32 18–19с + decoder ~1с (KV-кэш) ≈ 20с. Vulkan-путь закрыт (драйвер, 31.08).

## Ключевые факты (проверено по исходникам, 31.08–01.09)

Структура энкодера (по `whisper_base_encoder.ncnn.param`, turbo аналогичен ×32 слоёв, d=1280, ffn=5120):

| Тип слоёв | Кол-во (turbo) | MACs (доля) | Квантуемо ncnn2int8? |
|---|---|---|---|
| `Gemm` (fc1/fc2) | 64 | ~629 G (55%) | **Да** — `quantize_gemm()` (ncnn2int8.cpp:599), веса B→int8 (per-tensor scale), активации A квантуются динамически в `gemm_BT_arm_int8` (gemm_arm.cpp:6868) |
| `MultiHeadAttention` (q/k/v/o проекций внутри) | 32 | ~315 G (28%) | **Да** — `quantize_multiheadattention()` (ncnn2int8.cpp:706), веса q/k/v per-channel + out per-tensor, скейлы из таблицы `_param_0..3`; рантайм int8 через `create_pipeline` (multiheadattention_arm.cpp:401) |
| `Convolution1D` (conv1/conv2) | 2 | ~3.2 G (0.3%) | **Нет** — tools не знают тип; остаются fp32/fp16 |
| QK^T и attn·V (внутри MHA) | 64 gemm | ~184 G (16%) | **Нет** — остаются fp32 (qk_gemm/qkv_gemm без quantize_term, multiheadattention_arm.cpp:269–355) |

**Главное упрощение:** в энкодере **нет** `Convolution`/`InnerProduct`/`ConvolutionDepthWise`, а Gemm/MHA в этом клоне ncnn используют **weight-only int8 + динамическую квантизацию активаций** → **калибровочный датасет для ncnn2table НЕ нужен** (проблема «3000×128 вход в ncnn2table» отпадает). `ncnn2table` в 3-аргументной форме (без датасета) генерирует weight-only таблицу для MHA (ncnn2table.cpp:2127–2131, `save_table` пишет MHA безусловно).

Прочее:
- `ncnn2int8` пишет не-int8 слои с fp16-storage (hardcoded `storage_type = 1`, ncnn2int8.cpp:1063) → out-param magic будет 7863011. На устройстве грузится с `use_fp16_*=false` (turbo-ветка JNI) — ncnn конвертирует fp16→fp32 при загрузке, int8-веса не трогаются. Работает, но +время загрузки.
- Base-файлы в `tools/ncnn-convert/` — fp32 (magic 7767517). Turbo-артефактов конвертации на PC **нет** (grep по repo: только комментарии в export_ncnn.py) — модель на устройстве в `files/models/ncnn-turbo/`, base name `whisper_turbo` (WhisperTranscribeService.kt:160–161).
- `.venv`: pnnx wheel есть (`Lib/site-packages/pnnx/pnnx.exe`), torch/transformers есть.
- JNI: `set_num_threads(4)` **до** load — gemm фиксирует nT при загрузке (ncnn_jni.cpp:526–528, gemm_arm.cpp:6848–6853).
- Ожидание по скорости: при ×2 int8 на fc+проекциях (83% MACs) encoder ≈ 10–12с (чистая арифметика ~10.8с, плюс MHA int8 ОТКЛЮЧАЕТ packing — support_packing=false/use_packing_layout=false в multiheadattention_arm.cpp:403-406, проекции могут замедлиться сильнее ожиданий); при ×2.5 (маловероятно) ≈ 7–9с. Цель <8с — на грани, см. рычаги в Шаге 6 и риски.

---

## Шаг 0. Инвентаризация (завтра утром, ~30 мин)

```powershell
# 0.1 Что лежит на устройстве (точные имена + первый байт magic param)
adb shell ls -la /data/data/<PKG>/files/models/ncnn-turbo/          # <PKG> узнать: adb shell pm list packages | findstr opencode
adb shell "head -c 16 /data/data/<PKG>/files/models/ncnn-turbo/whisper_turbo_encoder.ncnn.param | od -c | head -2"
# 7767517 = fp32, 7863011 = fp16-storage. Для ncnn2int8 подходит любой (load_model сам конвертит).
# Ожидаем 12 файлов: whisper_turbo_{fbank,encoder,embed_token,embed_position,decoder,proj_out}.ncnn.{param,bin} + whisper_vocab.txt

# 0.2 turbo-артефакты где-либо на PC
Get-ChildItem C:\Projects\opencode-mobile\.cache -Recurse -Filter "*turbo*" | Select FullName
Get-ChildItem C:\Projects\opencode-mobile -Recurse -Include "*turbo*.param","*turbo*.bin","*turbo*.pt" -ErrorAction SilentlyContinue | Select FullName,Length

# 0.3 venv живой
C:\Projects\opencode-mobile\tools\ncnn-convert\.venv\Scripts\python.exe -c "import torch, pnnx, transformers; print(torch.__version__)"
C:\Projects\opencode-mobile\tools\ncnn-convert\.venv\Scripts\python.exe -c "import torchaudio; print(torchaudio.__version__)"   # [ПРОВЕРИТЬ] нужен для ре-конвертации fbank

# 0.4 Состав слоёв turbo-энкодера (после pull):
#    Select-String -Path whisper_turbo_encoder.ncnn.param -Pattern "^(Convolution1D|Gemm|MultiHeadAttention)" | Group-Object {($_.Line -split '\s+')[0]}
#    Ожидание: Convolution1D=2, Gemm=64, MultiHeadAttention=32. Имена слоёв — для таблицы.
```

## Шаг 1. Turbo ncnn-модель в fp32 на PC (1–2ч, вариант A; 3–5ч, вариант B)

**Вариант A (основной): снять с устройства.**
```powershell
New-Item -ItemType Directory -Force C:\Projects\opencode-mobile\tools\ncnn-int8\turbo
adb pull /data/data/<PKG>/files/models/ncnn-turbo/ C:\Projects\opencode-mobile\tools\ncnn-int8\turbo\
# Если /data/data недоступен (run-as только для debuggable): собрать деба-APK или канал ModelDownloader.
# Проверки: magic param (Шаг 0.1); если 7863011 (fp16) — прогнать ncnnoptimize (для чистоты лучше fp32),
#           bin encoder: fp32 ≈ 2.5GB, fp16 ≈ 1.3GB [ПРОВЕРИТЬ фактический размер]
```

**Вариант B (если pull невозможен): переконвертация.** Правка `tools/ncnn-convert/export_ncnn.py`:
```python
model_name = "whisper-large-v3-turbo"   # строка 12
num_mel_bins = 128                       # строка 13
d_model = 1280                           # строка 14
```
Запуск (в .venv, из `tools/ncnn-convert/`): `python export_ncnn.py`. Требования: RAM ≥ 16GB, ~10GB диска, HF доступен (safetensors ~1.6GB). Скрипт сам прогоняет pnnx → ncnn fp32 и патчит decoder-param (KV-кэш + Gemm 7=0) — для encoder патчи не нужны. Если `torchaudio` нет: `pip install torchaudio --index-url https://download.pytorch.org/whl/cpu`.

## Шаг 2. Сборка tools/quantize на Windows (~1ч)

```powershell
cmd /c "call `"C:\Program Files\Microsoft Visual Studio\2022\Community\VC\Auxiliary\Build\vcvars64.bat`" && cmake -S C:\Projects\opencode-mobile\third_party\ncnn -B C:\Projects\opencode-mobile\third_party\ncnn\build_tools -DNCNN_VULKAN=OFF -DNCNN_BUILD_TOOLS=ON -DNCNN_BUILD_EXAMPLES=OFF -DNCNN_BUILD_TESTS=OFF -DNCNN_INT8=ON -DNCNN_WEIGHT_QUANT=OFF && cmake --build C:\Projects\opencode-mobile\third_party\ncnn\build_tools --target ncnn2table ncnn2int8 ncnnoptimize --config Release -- -m:8"
```
Результат: `build_tools\tools\quantize\Release\{ncnn2table.exe,ncnn2int8.exe}`, `build_tools\tools\ncnnoptimize\Release\ncnnoptimize.exe`.
`ncnnoptimize` — приведение fp16-бина в fp32: `ncnnoptimize.exe in.param in.bin out.param out.bin 0` (0=fp32, 1=fp16). [ПРОВЕРИТЬ] к int8-выходу ncnn2int8 НЕ применять.

## Шаг 3. Калибровка и валидационный вход (2–3ч)

**Для квантования датасет не нужен** (см. факты). Нужен для **локальной проверки качества** int8 vs fp32.

**3.1 Скрипт `tools/ncnn-int8/make_calib.py`**: 10–20 русских wav 2–8с 16к (записать с телефона; включить 2 тишины 30с — NaN-кейсы):
- mel точно как on-device fbank (копия `FbankDirectCallWrapper` из export_ncnn.py:37–70): pad до 480000 сэмплов, hann n_fft=400, hop=160, center=True reflect, power=2.0, mel_filters от `WhisperFeatureExtractor` turbo (128 mel), `clamp(min=1e-10).log10()`, `max(log_spec, max-8)`, `(x+4)/4`;
- фреймы 0..2999 (drop last frame — как extract_fbank_feature);
- NaN-защита: `x[~np.isfinite(x)] = -10.0`;
- сохранить `calib/NNNN.npy` **float32, shape (128, 3000)** C-order (совместимо с `read_npy(shape=[3000,128])`).

**3.2 НЕ делаем в первой итерации** — патчи ncnn2table не нужны (активационных таблиц Gemm/MHA не читают).

## Шаг 4. Квантование + локальная валидация (2–3ч)

```powershell
cd C:\Projects\opencode-mobile\tools\ncnn-int8
# 4.1 weight-only таблица (только MHA-веса; датасет не нужен)
..\..\third_party\ncnn\build_tools\tools\quantize\Release\ncnn2table.exe turbo\whisper_turbo_encoder.ncnn.param turbo\whisper_turbo_encoder.ncnn.bin turbo\whisper_turbo_encoder.table method=kl
# Ожидание: таблица 32×4 строк attention_*_param_{0,1,2,3}

# 4.2 int8
..\..\third_party\ncnn\build_tools\tools\quantize\Release\ncnn2int8.exe turbo\whisper_turbo_encoder.ncnn.param turbo\whisper_turbo_encoder.ncnn.bin turbo\whisper_turbo_encoder_int8.ncnn.param turbo\whisper_turbo_encoder_int8.ncnn.bin turbo\whisper_turbo_encoder.table
# Ожидание: "quantize_gemm gemm_0..63" + "quantize_multiheadattention attention_0..31"; out-param magic 7863011; bin ~50% исходного при fp16-пулле, ~25% при fp32-пулле (зависит от того, что придёт с устройства)
```

**4.3 Локальный харнесс:** `tools/ncnn-int8/{CMakeLists.txt,compare_encoder.cpp}` — отдельный cmake-проект с `add_subdirectory(../../third_party/ncnn ncnn-build)` (NCNN_VULKAN=OFF), линк к ncnn. Прогнать обе сети на `calib/*.npy` (npy.hpp из tools/quantize, вход Mat w=3000 h=128, fp32, threads=8), выход: cosine similarity и max|Δ| по блобу out0 + время.
**Критерии:** cosine ≥ 0.99 на каждой фразе, нет NaN/Inf, max|Δ| ≤ 0.05–0.1 (ориентир после LayerNorm). Если cosine < 0.98 — partial int8 (риски б). Скорость на PC: int8/fp32 ≥ 1.8× (ориентир; решает устройство).

## Шаг 5. Интеграция в JNI (~1ч)

`whisperlib/src/main/jni/ncnn/ncnn_jni.cpp`, `Whisper::load` (строки 172–243).

1. **Имена файлов:** пробуем `p + "_encoder_int8.ncnn.param/bin"`, при отсутствии/int8-load ошибке — откат на обычные (реализовать ЦИКЛОМ из двух кандидатов, см. п.3; сниппет ниже — иллюстрация логики):
```cpp
std::string enc_param = p + "_encoder_int8.ncnn.param";
std::string enc_bin   = p + "_encoder_int8.ncnn.bin";
FILE* f = fopen(enc_param.c_str(), "rb");
if (f) { fclose(f); NCNN_PHASE("encoder int8: ON (%s)", enc_param.c_str()); }
else   { enc_param = p + "_encoder.ncnn.param"; enc_bin = p + "_encoder.ncnn.bin"; }
if (encoder.load_param(enc_param.c_str()) != 0) return -1;
if (encoder.load_model(enc_bin.c_str()) != 0) return -1;
```
2. **fp16-флаги:** turbo-ветка уже `fp16=false` — для int8 правильно, не менять.
3. **Роллбэк:** удаление/переименование int8-файлов на устройстве → автоматический откат на fp32; при ошибке `load_model` int8 — fallback на fp32 (цикл из двух кандидатов).
4. **Потоки:** `set_num_threads(4)` → 6, проверить и 8 (ncnn_jni.cpp:528), замерить оба.
5. Kotlin не трогаем: base «whisper_turbo» уже передаётся, int8-файлы лежат рядом.

## Шаг 6. Тест на устройстве (~1ч + итерации)

СНАЧАЛА пересобрать нативный код (Шаг 5 правит C++): `cmd /c C:\Projects\opencode-mobile\build_ncnn.bat` + `adb install -r` — без этого детект int8 не активируется.

```powershell
adb push C:\Projects\opencode-mobile\tools\ncnn-int8\turbo\whisper_turbo_encoder_int8.ncnn.param /data/data/<PKG>/files/models/ncnn-turbo/
adb push C:\Projects\opencode-mobile\tools\ncnn-int8\turbo\whisper_turbo_encoder_int8.ncnn.bin   /data/data/<PKG>/files/models/ncnn-turbo/
adb logcat -c; adb logcat -s NcnnWhisper
```
Прогон: 5× каждая фраза с ПАУЗОЙ 15-20с между прогонами (защита от троттлинга CPU на CPH2747 — иначе 5-й прогон может надуть encoder-ms и забракуешь годный int8), следить за `ncnn phase encoder=...ms` и `encoder int8: ON`.

**Критерии приёмки:**
- encoder < 8000мс, полная транскрипция < 10с;
- текст 1:1 с fp32 на обеих фразах, все 5 прогонов;
- нет раннего EOT / обрезаний / «Раскар»-эффекта;
- decoder ≈ 1с, нет NaN-fix спама из fbank;
- p95 RSS не выше fp32;
- ЗАМЕРИТЬ холодную загрузку int8-модели (fp16→fp32 конвертация в bin + int8-пиплайны добавляют секунды на init) — зафиксировать, но не блокировать приёмку без острой необходимости.

## Риски и план Б

**(а) Качество int8 деградирует (ранний EOT, «Раскар», пропуски слов).**
- **Б1. Partial int8 — только fc-Gemm, MHA в fp32:** ДОБАВИТЬ в `quantize_multiheadattention()` (ncnn2int8.cpp:706) первой строкой `if (getenv("SKIP_MHA")) return 0;`. Пересобрать target ncnn2int8 (минуты).
- **Б2. Partial int8 — только MHA, fc в fp32:** аналогично ДОБАВИТЬ `if (getenv("SKIP_GEMM")) return 0;` в `quantize_gemm()` (ncnn2int8.cpp:599). ВНИМАНИЕ: сейчас env-чеков в tools/quantize НЕТ (проверено 01.09) — оба патча надо добавлять самому. Таблицу передаём всегда (без неё MHA-калибровка падает).
- **Б3. Откат на base int8** (512-мерный, точнее держит): тот же пайплайн на `whisper_base_encoder.ncnn.*` (base encoder ≈ 4–5с и так).
- **Б4. Ре-конвертация энкодера с SDPA вместо MHA** (SDPA имеет int8 с динамической активацией — quantize_sdpa, ncnn2int8.cpp:826) — большой объём, последний рубеж.

**(б) ncnn2table/ncnn2int8 падают на нашем графе.**
- OOM при 2.5GB-бине (нужно ~6–8GB RAM — закрыть лишнее); Unicode-пути (работать из ASCII-путей); OMP на Windows (`OMP_NUM_THREADS=8`).
- Замена ncnn2table: python-скрипт, читающий MHA-веса из .bin и пишущий `_param_0..3` (формат — ncnn2table.cpp:210–362; q/k/v: 1280 скейлов `127/absmax` по строкам, out: один). ~100 строк.

**(в) int8 не даёт < 8с.**
- Оценка сверху: QK^T/attn·V (16% MACs) остаются fp32 → потолок ×1.7–2.2 общего времени. Реалистично encoder 8–12с, транскрипция 10–13с.
- Рычаги: потоки 6→8; `opt.use_packing_layout`; принять 10–12с как «turbo-точно» и держать base как быстрый режим; Б4 (SDPA int8).
- **НЕ возвращать Vulkan-encoder** в этой связке: декодер на Vulkan сломан, гибрид не выигрывает против int8-CPU (отвергнуто 31.08).

## Хронология (реалистично)

| Этап | Время | Выход |
|---|---|---|
| 0. Инвентаризация | 0.5ч | точные пути/имена на устройстве, magic |
| 1. turbo fp32 на PC | 1–2ч (pull) / +3ч (reconvert) | `tools/ncnn-int8/turbo/whisper_turbo_encoder.ncnn.*` |
| 2. Сборка tools | 1ч | ncnn2table.exe, ncnn2int8.exe, ncnnoptimize.exe |
| 3. make_calib.py + записи | 2ч | calib/*.npy (128×3000) |
| 4. table + int8 + compare | 2–3ч | int8 param/bin, cosine-отчёт |
| 5. JNI | 1ч | int8-детект + роллбэк в ncnn_jni.cpp |
| 6. Тест на устройстве | 1–2ч | замеры encoder/полной транскрипции |
| Буфер план-Б (1–2 итерации) | 3–4ч | partial int8 или приём компромисса |
| **Итого** | **~1.5–2 дня** | |

**Чек-лист «стоп-условий»:** cosine < 0.98 на ≥1 фразе → Б1/Б2; encoder > 12с → рычаги (в); ранний EOT на устройстве при cosine ≥ 0.99 → копать decoder-чувствительность (сравнить encoder-states с PyTorch-дампами из .cache/encdump-test, если сохранились).