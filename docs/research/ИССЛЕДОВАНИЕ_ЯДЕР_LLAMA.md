# ИССЛЕДОВАНИЕ ЯДЕР И ФОРКОВ LLAMA.CPP (21.11.2026)

Источник: GitHub API + README репозиториев. Скрипты: `tmp/research-kernels*.mjs`.
**Ничего не выбрано** — данные для замера на нашем железе.

## 1. Главная находка: ядра решают больше, чем квант

В官方文档 llama.cpp (`docs/android.md`) есть `examples/llama.android` — официальный
Android-байндинг с hardware acceleration **до SME2 на Arm**, с автоопределением железа
в рантайме. Это значит: наш замер должен включать не только «какую модель», но и
«какое ядро».

Ключевые CMake-флаги (из репо `FASTCHIP/lfm3-android`, проверено на Snapdragon 8 Gen 1):
```
-DGGML_NATIVE=OFF -DGGML_CPU_ALL_VARIANTS=ON -DGGML_CPU_KLEIDIAI=ON
```
Для одного устройства можно точнее: `-DGGML_CPU_ARM_ARCH="armv8.2-a+dotprod+i8mm+bf16+fp16"`
(универсальная сборка стоит ~5% на Samsung).

## 2. Реальные замеры от FASTCHIP/lfm3-android (LFM3-1.7B, Snapdragon 8 Gen 1)

| Ядро | prefill (t/s) | decode (t/s) |
|---|---|---|
| `dotprod+i8mm` | 69.7 | 14.5 |
| **+ KleidiAI** | **100.7** | **23.1** |

То есть **+44% prefill и +59% decode только от ядра**. У нашего OnePlus CPH2747
(Android 17, 8 ядер 4.6/3.6 ГГц — заметно свежее 8 Gen 1) потенциал выше.
KleidiAI — от ARM (`ARM-software/kleidiai`), лицензия в зеркале не указана.

## 3. Обзор ядер/форков

| Проект | ★ | Лицензия | Суть |
|---|---|---|---|
| **ggml-org/llama.cpp** | 135 743 | MIT | Оригинал. CPU-бэкенд с NEON/dotprod/i8mm + ALL_VARIANTS + KleidiAI (опция) |
| **cactus-compute/engine** | 6 198 | NOASSERTION | 2bit-кванты CQ1/CQ2/CQ3/CQ4, ARM NEON SIMD ядра, рантайм под consumer-девайсы. Есть `cactus benchmark --android` |
| **cactus-compute/cactus** | 14 835 | Apache-2.0 |_sub50MB agentic/voice/vision для consumer-устройств |
| **microsoft/BitNet** | 42 230 | MIT | 1-bit LLM, **1.37–5.07x на ARM** над F16; I2_S ядра; BitNet-embedding-0.6B/270M |
| **ikawrakow/ik_llama.cpp** | 3 407 | MIT | SOTA-кванты + лучше CPU-производительность; IQK GEMM; **ARM_NEON или лучше** |
| **AmesianX/TurboQuant** | 92 | MIT | KV-cache сжатие 5.2x (TurboQuant, ICLR 2026 DeepMind). ⚠️ Проект **закрыт** («laid to rest»), port в vLLM не удался |
| **BoFan-tunning/llama.cpp-MTP-TurboQuant** | 149 | MIT | MTP + TurboQuant, «2–5x», но требует GGUF со встроенным MTP-heads и **CUDA/RTX** — нам не подходит |
| **xentron-bit/llama-android-prebuilt** | 7 | - | Готовые .so под Android arm64 (Vulkan+OpenCL+CPU fallback), но **без KleidiAI** |
| **FASTCHIP/lfm3-android** | 1 | MIT | llama.cpp dotprod+i8mm+KleidiAI в APK без Gradle; замеры выше |
| **Marc-Dvci/fastpath64** | 1 | MIT | 2.12x prefill IQ4_XS на Arm Neoverse через smmla-repack (custom kernel) |

## 4. Что применимо к нам (без root, в APK)

1. **Кандидат №1 — оригинальный llama.cpp с `GGML_CPU_ALL_VARIANTS=ON + KLEIDIAI=ON`.**
   Проверенный прирост +59% decode. Это и используем как baseline в бенче.
2. **Кандидат №2 — ik_llama.cpp**: лучше CPU + SOTA-кванты, MIT, активен (обновлён сегодня).
   Можно собрать вторую `.so` и сравнить на тех же GGUF.
3. **Кандидат №3 — cactus engine**: 2bit CQ-кванты и ARM NEON ядра, уже есть готовая
   модель `cactus needle` (61 МБ). Но лицензия NOASSERTION — требует отдельной проверки.
4. **Отсечь**: BitNet (нужны 1-bit модели, отдельный рантайм, нет GGUF под наш вопросы),
   TurboQuant-форки (CUDA/закрыты), MTP-форки (нужны спец-GGUF + GPU).

## 5. Важный вывод
Выбор модели **нельзя** делать отдельно от выбора ядра: LFM3-1.7B на KleidiAI
(23.1 t/s) может обогнать Qwen3-0.6B на базовом NEON. Поэтому бенч должен мерить
**пару «модель × ядро»**, а не модель в одиночку.
