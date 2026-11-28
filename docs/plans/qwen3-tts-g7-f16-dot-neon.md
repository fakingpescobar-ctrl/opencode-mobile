# Qwen3-TTS: гейт G7 — NEON F16→F32 widening в `ggml_vec_dot_f16_f32` (CPH2747, 29.11.2026)

## Goal

Проверить, даёт ли замена скалярного преобразования F16→F32 в hottest-dot ядре декодера на в-регистральное NEON-преобразование ≥10% улучшения RTF на стеке ACCEPT-ed G4 codec8. Результат — формальный ACCEPT/DROP research-документом. APK, release CI и дефолтный Supertonic не меняются.

После G5a/G5b (квант кодера) и G6 (ширина чанка) это первый гейт, который бьёт не по весам и не по размеру батча, а по **реализации арифметики** в hottest-path.

## Non-goals

- APK wiring, `release-apk.yml`, подпись, versionCode, теги `apk-*`, установка APK, смена дефолтного TTS.
- Повтор ISA/`GGML_CPU_ARM_ARCH` (G1d DROP), KleidiAI (G2 DROP), глобальных 8 потоков (G3 DROP), квант кодера (G5a/G5b DROP), ширины чанка (G6 DROP).
- Перекрытие чанков: отдельный разбор показал, что `QwenDACStreamState` уже хранит хвосты всех causal-свёрток и transconv; повторных MAC нет, верхняя граница выигрыша ~1%. Гейт по этому направлению **не** открывается.
- Q8_0 + repack для DAC-свёрток: `ggml_call_mul_mat` задаёт `nb[1] = k * type_size`, что неверно для block-типов; плюс правка `dst_type` im2col и проверка качества — несколько переменных. Отложено.
- `GGML_IM2COL_WORK_SIZE` 16→64 МБ и TransConv F32→F16: дешёвые, но ожидаемый эффект ниже порога; оставляются как follow-up **после** этого гейта.
- Изменение модели, talker'а, числа потоков, расписания ранков, формата PCM и измерителя.

## Constraints

- **Единственная переменная:** `ggml/src/ggml-cpu/vec.cpp`, функция `ggml_vec_dot_f16_f32` (строки 380–457). Ни один другой файл qwentts.cpp или ggml не правится.
- База — ACCEPT-ed G4 codec8: отдельный 8-поточный CPU-бэкенд кодера, Talker/Predictor на 4 потоках, `qwen-tts` sha `595035cd49902412b1085f5a6cdeec5aa5cdf7ef7ae49f498fb3fa3d3921e202`.
- Models: talker `qwen-talker-0.6b-base-Q4_K_M.gguf` sha `4b468ec7…`, codec `qwen-tokenizer-12hz-Q4_K_M.gguf` sha `cf3788b4…` — те же, что в G4/G5/G6.
- Сборка research-only через **новый** `.github/workflows/build-qwentts-arm-f16dot.yml` с вариантами `baseline | f16dot`. Существующий `build-qwentts-arm-codec8.yml` не трогается: G4/G6 остаются воспроизводимыми байт-в-байт.
- CMake-флаги, тулчейн Zig 0.13 musl aarch64, ggml `ef4555b`, qwentts `2edb35f` — байт-в-байт как в G4/G6. `GGML_CPU_ARM_ARCH` не задаётся, `GGML_CPU_KLEIDIAI=OFF`.
- Проверка CI-шага правки: `git -C /tmp/qwentts/ggml diff --name-only` строго `ggml/src/ggml-cpu/vec.cpp`; маркер `G7 f16dot` присутствует; `git -C /tmp/qwentts diff --name-only` строго `src/pipeline-codec.cpp` (G4 codec8). Любая другая правка = fail.
- Пороги зафиксированы до прогона и не меняются (раздел "Пороги" ниже).
- Замер только device-side в фоне с done-сентинелом; `kill-server`/`start-server` запрещены; фоновый `nohup` внутри устройства, не `adb shell … &`.
- Research-артефакты: `$DEVICE/files/qwen3-tts-arm-research/g7/`; APK и `MOBILE_AGENT/*` не править.

## Current behaviour (проверено по исходникам 29.11.2026)

- `qwen_conv1d` (`src/causal-trans-conv.h:142`) → `ggml_conv_2d_direct` → `GGML_OP_CONV_2D` → `ggml_compute_forward_conv_2d_impl` (`ggml/src/ggml-cpu/ops.cpp:7261`).
- `ops.cpp:7354`: `ggml_call_mul_mat(kernel_type=F16, src1_type=F32, …, tmp, knl_data, gemm_output)`. Значит src0 = F16-ядро, src1 = F32 patch-буфер.
- `ggml-cpu.c:225-230`: `type_traits_cpu[F16].vec_dot = ggml_vec_dot_f16_f32`, `vec_dot_type = F32`. Преобразования src1 нет (`src1->type == vec_dot_type`), значит каждый dot идёт прямо в `ggml_vec_dot_f16_f32`.
- `vec.cpp:425-450` (`GGML_SIMD`, ветка ARM): `float tx[64]`; для каждого блока из 64 элементов — 64 **скалярных** `GGML_CPU_FP16_TO_FP32` + вызов `ggml_vec_dot_f32(64, …)`. Для типичного `knl_n = k*IC = 7*64 = 448` это 7 вызовов функции и 448 скалярных преобразований **на каждый из ~10⁶ dot-вызовов** кадра. Исходник сам помечает это TODO: «convert F16 to F32 in-register and fuse the dot; also avoid the per-chunk call overhead for tiny k».
- Готовый NEON-путь есть: `simd-mappings.h:324-349` (`GGML_F32Cx4_LOAD` = `vcvt_f32_f16(vld1_f16(…))`, `GGML_F32Cx4_FMA` = `vfmaq_f32`) и `vec.cpp:264-378` (`ggml_vec_dot_f16`). Сборка идёт без `-march`, поэтому `__ARM_FEATURE_FP16_VECTOR_ARITHMETIC` не определён и аккумуляция остаётся **F32** — численного изменения по сравнению с текущим путём нет.
- G4: RTF med short `1.7680` / short-2 `1.8367` / medium `1.9362` / long `2.1362`; cold-first `437 мс`; `CodecDecode` ≈58% времени; 28/28 HTTP 200.
- Дрейф стенда в G5a доходил до +49% RTF между A1 и A2 → обязателен свежий baseline и A2 при дрейфе >10%.

## Proposed design

Один гейт: `f16dot` против свежего `baseline` (codec8) на тех же бинарях G4, моделях и измерителе.

Патч добавляет ARM-ветку **до** текущей `GGML_SIMD`-ветки в `ggml_vec_dot_f16_f32`:

```c
#elif defined(__ARM_NEON) && defined(__ARM_FEATURE_FMA) && defined(__ARM_FP16_FORMAT_IEEE)
    // G7 f16dot: в-регистральное расширение F16 -> F32,
    // без stack-буфера и вызова ggml_vec_dot_f32 на каждые 64 элемента.
    {
        const int np = n & ~15;
        float32x4_t acc0 = vdupq_n_f32(0.0f);
        float32x4_t acc1 = vdupq_n_f32(0.0f);
        float32x4_t acc2 = vdupq_n_f32(0.0f);
        float32x4_t acc3 = vdupq_n_f32(0.0f);
        for (int j = 0; j < np; j += 16) {
            const float32x4_t x0 = vcvt_f32_f16(vld1_f16((const __fp16 *)(x + j +  0)));
            const float32x4_t x1 = vcvt_f32_f16(vld1_f16((const __fp16 *)(x + j +  4)));
            const float32x4_t x2 = vcvt_f32_f16(vld1_f16((const __fp16 *)(x + j +  8)));
            const float32x4_t x3 = vcvt_f32_f16(vld1_f16((const __fp16 *)(x + j + 12)));
            acc0 = vfmaq_f32(acc0, x0, vld1q_f32(y + j +  0));
            acc1 = vfmaq_f32(acc1, x1, vld1q_f32(y + j +  4));
            acc2 = vfmaq_f32(acc2, x2, vld1q_f32(y + j +  8));
            acc3 = vfmaq_f32(acc3, x3, vld1q_f32(y + j + 12));
        }
        sumf += vaddvq_f32(vaddq_f32(vaddq_f32(acc0, acc1), vaddq_f32(acc2, acc3)));
        i = np;
    }
#elif defined(GGML_SIMD)
```

Остаток `n % 16` обрабатывается существующим скалярным циклом (`vec.cpp:452-454`). Ни type traits (`ggml-cpu.c:225-230`), ни patch-буфер (`ops.cpp:7296/7323/7349/7354`) не меняются: риск перезаписи `params->wdata` и block-stride ошибок Q8_0 полностью исключён.

Ожидаемый механизм: 448 скалярных преобразований + 7 вызовов функции на dot заменяются на 112 NEON `vcvt` + 112 NEON `vfmaq` без вызовов. Доля `CodecDecode` ~84% MAC в DAC-свёртках k=7.

Число: F32-аккумуляция сохранена, веса и активации не переквантуются; ожидается битовая близость waveform к baseline (в пределах шума сэмплирования).

## Пороги (зафиксированы до прогона)

| Метрика | ACCEPT | DROP |
|---|---|---|
| RTF med `warm-short` (2), `warm-short-2` (10), `warm-medium`, `warm-long` | ≥10% улучшения в **каждой** категории против свежего baseline | <10% или хуже хотя бы в одной |
| Talker/CodePredictor | не хуже baseline более чем на 10% **на кадр** | >10% регресса |
| Первый PCM `cold-first` | ≤ 796.4 мс | > 796.4 мс |
| Стабильность | 28/28 HTTP 200, 0 crash/hang, s16le 24 kHz mono | любой 4xx/5xx, обрыв, crash/hang |
| Аудио | peak > 0.01, rms > 0.001, peak и rms med в пределах ±30% от baseline | тишина/мусор/иной формат/выход за ±30% |

При дрейфе A1→вариант >10% по любой стадии запускается A2 и вердикт строится на обеих парах (урок G5a). Равный или худший результат → DROP и откат research-артефакта; «почти получилось» не оставляем.

Точка решения на скрининге (зафиксирована здесь же): 3×`warm-short` + 3×`warm-medium`; если `f16dot` не даёт ≥10% улучшения `CodecDecode` **мс/кадр** хотя бы в одной из двух категорий — полную матрицу не открывать, DROP по скринингу.

## Interfaces

- Новый research-only dispatch-workflow `.github/workflows/build-qwentts-arm-f16dot.yml`: `inputs.variant` = `baseline | f16dot`.
  - Оба варианта применяют G4 codec8-патч `src/pipeline-codec.cpp`.
  - `f16dot` дополнительно применяет патч к `ggml/src/ggml-cpu/vec.cpp` с маркером `G7 f16dot`.
  - Проверка: `qwen-tts` у `baseline` == `595035cd…`; у `f16dot` != `595035cd…`; `libggml-cpu.so.1.0.0` у вариантов **различается** (доказательство, что patched dot попал в сборку); ggml pin `ef4555b`.
- env измерителя без изменений: `QWEN_BIN`, `QWEN_CODEC_MODEL`, `QWEN_OUT`, `QWEN_VARIANT`, `QWEN_PORT`, `QWEN_LOADER`, `QWEN_NATIVE_LIB`.
- Порты: baseline `4199`, вариант `4201` (4191/4193/4195/4197 заняты предыдущими гейтами).
- Device root: `/data/user/0/org.opencode.mobile.debug/files/qwen3-tts-arm-research/g7/` с `bin-base/`, `bin-f16dot/`, `out/base/`, `out/f16dot/`.
- Режимы измерителя: `screen` (6 запросов, только go/no-go) и `matrix` (28 запросов, вердикт). Done-сентинел `out/<variant>/bg-<mode>.done`, exit 0, `DONE N` в логе.
- Локальные JSON/логи: `C:\Users\BIGBRO\AppData\Local\Temp\opencode\g7-results\`.

## Steps

1. Записать этот план — verify: файл в git, `git diff --check` чистый.
2. Добавить research-workflow `build-qwentts-arm-f16dot.yml`; verify: `git diff --check`, `baseline`-ветка даёт тот же sha `595035cd…`, `f16dot`-ветка меняет ровно один файл ggml и маркер на месте.
3. Dispatch `baseline` и `f16dot`, дождаться success, скачать артефакты; verify: manifest с sha, ggml pin, `libggml-cpu.so` различается. Инфраструктурный отказ тулчейна вердиктом не считается.
4. Подготовить устройство: `g7/bin-base` = копия g6/bin-codec8 (sha-пин `595035cd…`), `g7/bin-f16dot` = новые бинари; те же GGUF с sha-проверкой; адаптированные bench/run/bg/cleanup. Verify: sha на устройстве, legacy-каталоги g4/g5a/g5b/g6 не тронуты.
5. **Скрининг:** `base/screen` и `f16dot/screen` (по 6 запросов); verify: 6/6 HTTP 200, `done=0`, backend 4/8, peak>0.01. Сравнить `CodecDecode` мс/кадр и RTF. При отрицательном результате — DROP без полной матрицы.
6. При положительном скрининге: `base` A1 (28), `f16dot` (28), при дрейфе >10% — A2 `base`; все device-side под `nohup` + done-сентинел. Verify: `DONE 28`, 28/28 HTTP 200.
7. Расчёт медиан RTF, first PCM, stage мс/кадр, guard, дрейфа, peak/rms; вердикт в `docs/research/QWEN3_TTS_G7_F16_DOT_NEON_GATE.md`. Verify: `git diff --check`, коммит и пуш только research-артефактов.

## Risks

- **Автовекторизация существующего цикла:** Zig `-O3` может частично векторизовать `float tx[64]`, тогда выигрыш будет меньше оценённого. Это честно измерится скринингом; порог не подгоняется.
- **Аккумуляция F16:** если сборка всё же определит `__ARM_FEATURE_FP16_VECTOR_ARITHMETIC`, наш `${x}` грузится через `vcvt_f32_f16` и аккумуляция остаётся F32 — риск отсутствует; F16-аккумуляция используется только в `ggml_vec_dot_f16`, который здесь **не** вызывается.
- **Регресс Talker/Predictor:** правка глобальная для всех F16×F32 dot, но она строго эквивалентна текущему F32-результату; guard на кадр всё равно проверяется.
- **Дрейф стенда:** до +49%; A/B/A обязателен для полной матрицы.
- **ADB:** только точечные read-команды; фоновый `nohup` внутри устройства, не `adb shell … &`; демон не перезапускается.

## Open questions

- Нет. Порог, переменная и точка решения зафиксированы до измерений.
