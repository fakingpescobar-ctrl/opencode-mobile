# CPH2747: архитектура устройства как база для форков llama.cpp и Qwen3-TTS (29.11.2026)

## Статус и границы

Исследовательский документ, не продуктовая правка. Не трогаются `release-apk.yml`, APK wiring, подпись, versionCode, теги `apk-*`, установка APK, дефолтный Supertonic и `MOBILE_AGENT/*`. Телефон опрашивался только через ADB (read-only `getprop`, `/proc`, `/sys`, запуск собственных зондов); UI-автоматизация не применялась, ADB-демон не перезапускался.

Цель: зафиксировать проверенное железо и его возможности до начала работы над **собственным форком llama.cpp и собственным форком Qwen3-TTS**, чтобы оптимизация была под конкретный SoC, а не «generic ARM».

## 1. Идентификация

| Параметр | Значение (с устройства) |
|---|---|
| Модель | OnePlus CPH2747, device `OP611FL1` |
| SoC | Qualcomm **SM8850**, platform `canoe` = Snapdragon 8 Elite Gen 5 |
| RAM | `MemTotal` 15 502 100 kB (~14.8 GiB), `MemAvailable` ~6.0 GiB в момент замера |
| Android | 17 (`ro.build.version.sdk=37`), сборка `B.R4T3.50a3663-29f0eba-29fce1c` |
| Kernel | `6.12.69-android16-6-g5fd39602326e-ab16372235-4k`, clang 19.0.1 / LLD 19.0.1, `SMP PREEMPT` |
| Драйвер GPU | `com.qualcomm.qti.gpudrivers.canoe.api36` |

## 2. CPU: 8 ядер Oryon, две группы по частоте и ёмкости

`CPU implementer=0x51` (Qualcomm), `CPU part=0x002` (Oryon), `CPU architecture=8`, **SMT нет** (`thread_siblings_list` = один номер на ядро).

| Ядра | `core_id` | `cpu_capacity` | max freq | min freq | `CPU variant` |
|---|---|---|---:|---:|---|
| 0–5 | 0–5 | 762 | 3 628 800 kHz | 384 000 kHz | 0x2 |
| 6–7 | 0, 1 (второй кластер) | 1024 | 4 608 000 kHz | 768 000 kHz | 0x3 |

Это важно: **2 «prime» ядра на 4.608 ГГц и 6 «mid» на 3.629 ГГц**, ёмкость 1024 против 762 (отношение 1.34). Все 8 онлайн, affinity процесса — `0-7`.

Практический вывод: последовательный decode (batch=1) чувствителен к частоте критического пути и должен жить на ядрах 6–7; throughput-работа (codec conv, prefill) должна идти на 0–5. Глобальная «одинаковая» политика потоков (G3) проиграла именно поэтому, а G4 выиграл, дав кодеру отдельный 8-поточный бэкенд.

## 3. ISA: SVE2 есть, SME есть, **SME2 отсутствует**

Замер нативно через `getauxval` (зонд `cpu-probe.c`):

| Флаг | Значение |
|---|---|
| `HWCAP` | `0x00000000ffffffff` |
| `HWCAP2` | `0x000040003cbb73ef` |
| SVE / SVE2 / SVE-I8MM / SVE-BF16 | **1 / 1 / 1 / 1** |
| SVE2P1 | 0 |
| I8MM / BF16 | 1 / 1 |
| SME | **1** |
| **SME2 / SME2P1** | **0 / 0** |
| SME-I8I32 / F16F32 / B16F32 / F32F32 | 1 / 1 / 1 / 1 |
| SME-FA64 / I16I64 / F64F64 | 0 / 0 / 0 |
| SVE VL | **128 бит** (16 байт) |
| SME VL | **512 бит** (64 байт) |

`/proc/cpuinfo` это подтверждает: `... sve2 sveaes svepmull svesha3 svesm4 svei8mm svebf16 i8mm bf16 ... sme smei8i32 smef16f32 smeb16f32 smef32f32 ...`, но отдельного `sme2` нет.

Два следствия, которые меняют план форка:

1. SVE на Oryon имеет VL=128 бит — **та же ширина, что NEON**; выигрыш SVE2 в кодогенерации/предикатах, а не в doubling throughput. Поэтому ожидать ≥10% от замены NEON→SVE2 в горячем DOT нереалистично.
2. KleidiAI-ядра `..._sme2_mopa` / `..._sme2_mla` **не запустятся**: для них нужен `-DGGML_CPU_ARM_ARCH=armv9.2-a+sme2`, а железо сообщает `SME2=0`. Доступны только SME v1 (`..._sme_mopa`) и SVE2/I8MM/BF16-пути. Это прямо опровергает «очевидный» рецепт сборки под SME2.

Исторический гейт G1c/G1d пробовал `-DGGML_CPU_ARM_ARCH=armv8.6-a+dotprod+i8mm` через Zig и получил −4.55% / +5.27% / −1.62% RTF (DROP). Это **не тот же эксперимент**: там был ARMv8.6 без SVE2/SME и без KleidiAI, плюс Zig 0.13 не понимал `-march=armv8.6`. Корректная попытка — ARMv9 (`armv9-a+sve2+i8mm+bf16+sme`) через **NDK clang**, а не повтор G1.

## 4. GPU: Adreno 840, Vulkan 1.4, кооперативные матрицы

Нативный Vulkan-зонд (`vk-probe.c`, собран NDK r27 / API 33, запущен на устройстве):

| Параметр | Значение |
|---|---|
| Device | `Adreno (TM) 840`, vendor `0x5143`, deviceID `0x44050a31` |
| Vulkan API | **1.4.295** (dumpsys: `vulkanVersion=0x404000`), драйвер `2150932533` |
| ICD | `/vendor/lib64/hw/vulkan.adreno.so`, loader `/system/lib64/libvulkan.so`; `/dev/dri/renderD128` |
| Queue families | 4: `[0] flags=0x19f count=4 ts=48` (compute+graphics+transfer), `[1] 0x6`, `[2] 0x8`, `[3] 0x400` |
| Subgroup | size **64**, ops `0x6ff`, stages `0xf0` |
| Feature bits | `shaderFloat16=1`, `shaderInt8=1`, `shaderInt64=1`, `shaderInt16=1`, `shaderFloat64=0` |
| Лимиты | shared memory 32 KiB, storage buffer 2 GiB, workgroup `1024³`, 1024 invocation |
| Расширений | **190** |

Ключевые расширения:

- `VK_KHR_cooperative_matrix v2` — **есть**;
- `VK_QCOM_cooperative_matrix_conversion` — **нет**;
- `VK_KHR_shader_integer_dot_product v1`, `VK_EXT_subgroup_size_control v2`, `VK_EXT_pipeline_robustness v1`, `VK_ARM_tensors v1`, `VK_KHR_shader_float16_int8 v1` — есть.

18 комбинаций coop-matrix, в том числе:

- `64×64×16` F16→F16, `64×64×8` F32→F32;
- `64×64×32` **UINT8→UINT32** и **SINT8→SINT16** (A=7/B=7/C=9/R=9 и A=3/B=3/C=5/R=5);
- `64×32×16` F16 и `64×32×8` F32; scope = subgroup (3).

**Ловушка для форка.** В ggml (пин `ef4555b`, `ggml-vulkan.cpp:125-143`) Qualcomm-ветка выбирается только если **одновременно** есть `VK_KHR_cooperative_matrix` и `VK_QCOM_cooperative_matrix_conversion`. На нашем устройстве второго нет → `vk_device_architecture` = `OTHER`, а не `QUALCOMM_ADRENO`. При этом `coopmat_support` выставляется отдельно (`ggml-vulkan.cpp:4312-4319`) просто по наличию KHR-расширения, так что generic coopmat-путь остаётся доступен, но Adreno-специфичные ветки не включатся. Для форка это либо патч детекции, либо осознанный отказ от Adreno-специализации; универсальный coopmat-путь при этом остаётся рабочим.

Шейдеры, нужные именно для TTS-кодека, в ggml-vulkan присутствуют: `mul_mat_vec*.comp`, `flash_attn_cm1/cm2.comp`, `im2col.comp`, `conv_transpose_1d.comp`, `conv2d_dw/mm.comp`.

## 5. Hexagon cDSP: есть, но не «просто включить»

На устройстве обнаружены `/dev/fastrpc-cdsp`, `/dev/fastrpc-adsp`, `/dev/remoteproc-cdsp-md`, `/dev/rdbg-cdsp`, `/vendor/lib64/libcdsprpc.so`, `/vendor/lib64/libsysmon_cdsp_skel.so`, `/vendor/lib64/libnspextensiongenericqnnservice.so`, `/vendor/lib/rfsa/adsp`.

Системных `libQnn*.so` **нет**. Это хорошо для `ggml-hexagon`: он не использует QNN, а понижает ggml-граф в HTP-opbatch со своими HVX/HMX-ядрами. Но для сборки нужны Hexagon SDK 6.7.0.0 + Hexagon tools, генерация skel-библиотек (`v73/v75/v79/v81/v85`) и работа через FastRPC `_dom=cdsp`. Версия skel под конкретный cDSP неизвестна — это отдельный spike с высоким риском (SELinux/подпись/совместимость).

## 6. Тепловой и инструментальный контекст

- `sys.thermal.enable=false`, `sys.oplus.high.performance=0`, `vendor.gfx.perf_hint=CL_PERF_HINT_NORMAL_QCOM`, `init.svc.thermal-engine=running`. Тепловая обратная связь в prop'ах выключена → любой замер RTF обязан логировать температуру рядом с метрикой, иначе «выигрыш» легко окажется холодным стартом.
- На хосте: NDK **27.1.12297006** (clang 19, Vulkan headers + stub `libvulkan.so`, API 21–35), cmake, ninja, git, Python, Node. **Нет** glslc и Vulkan SDK (для shaders ggml-vulkan понадобятся); `vulkaninfo.exe` есть только для хост-GPU и для устройства бесполезен. Zig 0.13 доступен в CI (сборка musl aarch64), но его `-mcpu` модель уже ломала G1c.

## 7. Карта «железо → бэкенд» для двух форков

| Подсистема | llama.cpp (LLM decode / prefill) | Qwen3-TTS (AR + codec) | Вердикт |
|---|---|---|---|
| 2 prime Oryon @4.608 | критический путь decode, batch=1 | хвостовые слои AR с малым параллелизмом | приоритет №1 для латентности |
| 6 mid Oryon @3.629 | prefill, параллельные GEMM | conv1d/convT кодера | приоритет №1 для throughput |
| SVE2 / I8MM / BF16 | квантованные dot (`Q4/Q8`) | малые matmul | реально, но потолок ~1–5% на этом коде |
| SME v1 (VL=512) | пока только через KleidiAI `..._sme_mopa` | нет готовых ядер | spike, не гейт |
| SME2 | недоступно (`HWCAP2` бит 37 = 0) | недоступно | **не планировать** |
| Adreno 840 Vulkan | prefill, дольше — coopmat decode | convT/im2col/MUL_MAT кодера | средний риск; requires glslc + патч детекции |
| Hexagon cDSP | ggml-hexagon HMX W8A8 | теоретически граф кодера | высокий риск, отдельный spike |

Архитектура форков: **один общий слой бэкенда + два тонких потребителя**. Общее — топологический threadpool (чтение `cpu_capacity` из sysfs), affinity-планировщик (prime/mid), диспетчер ISA по hwcaps, форматы квантования и gate-runner с логированием температуры. llama.cpp-форк добавляет только регистрацию бэкенда и политику потоков; Qwen3-TTS-форк — свои операции кодера (`conv1d`, `conv_transpose_1d`, Snake, iSTFT), которых в ggml-cpu нет. Оба пинятся на один коммит ggml, чтобы гейты оставались одно-переменными.

## 8. Первые spike-эксперименты (до гейта ≥10%)

1. **CPU ISA, не повторяя G1:** собрать ggml через NDK clang 19 с `-DGGML_CPU_KLEIDIAI=ON -DGGML_CPU_ARM_ARCH=armv9-a+sve2+i8mm+bf16+sme` (не `+sme2`!), проверить `libggml-cpu.so` на наличие символов `kai_run_matmul_clamp_*_sve_*` / `_sme_mopa`, затем `test-backend-ops` и только после этого screening. Дешёвая проверка тулчейна — 20 строк SVE2-dot + SME tile на устройстве: SIGILL/неверный результат виден за минуту.
2. **Vulkan:** patched detection (`QUALCOMM_ADRENO` при наличии KHR coopmat) + coopmat matmul `64×64×32` SINT8→SINT16; измерить latency и hang, только потом подключать кодер. Обязателен таймаут: ggml-vulkan на Adreno известен подвесами.
3. **Affinity (самая дешёвая переменная):** `sched_setaffinity` — кодер на 0–5, AR на 6–7, при том же числе потоков. Это не повтор G3/G4: потоков столько же, меняется только размещение.
4. **Thermal guard:** в gate-runner рядом с RTF писать `thermal_zone*/temp` и частоты `cpu6/cpufreq/scaling_cur_freq`, `cpu0/...`; сравнивать только одинаковые температурные окна.

Порядок gating сохраняется прежним: одна переменная, порог ≥10% RTF med, план и точка решения фиксируются до правок, FAIL на скрининге → DROP без полной матрицы.

## 9. Что не повторять

G1c/G1d (`GGML_CPU_ARM_ARCH` в старой формулировке ARMv8.6 через Zig), G2 KleidiAI в старой сборке без SVE2/SME-флагов, G3 глобальные 8 потоков, G5a Q8_0 кодера, G5b Q4_K_S/Q3_K_S, G6 ширину чанка, G7 F16 dot, G8 async overlap. SME2/SME2P1 не брать — на этом кремнии их нет.

## 10. Сырые доказательства

| Файл | Содержимое |
|---|---|
| `arch-dump.txt` | `/proc/cpuinfo`, topology, capacity/freq, meminfo, getprop, kernel |
| `gpu-npu-probe.txt` | ICD, KGSL, Hexagon-устройства и библиотеки, dumpsys gpu |
| `vk-probe-out.txt` | device name, API, queues, 190 extensions, 18 coop-matrix combos |
| `cpu-probe`, `vk-probe` | нативные зонды, собранные NDK r27 (API 33), запускались через `adb shell` |

Локальные копии: `C:\Users\BIGBRO\AppData\Local\Temp\opencode\` (зонды и вывод). Артефакт в git — только этот документ.
