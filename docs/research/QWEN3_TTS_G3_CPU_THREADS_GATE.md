# Qwen3-TTS: research-гейт G3 `cpu8` (8 CPU-потоков, CPH2747, 28.11.2026)

**Итог: гейт ПРОВАЛЕН (DROP).** Увеличение числа потоков CPU-бэкенда с 4 до 8 ускорило CodecDecode на 31–42%, но замедлило Talker и CodePredictor настолько, что в long RTF стал **хуже** baseline (+2.8%), а короткий прогон по 10 повторам дал лишь −1.6% вместо требуемых −10%. Первый PCM cold-first вырос до 3828 мс (порог 796.4 мс). Research-workflow удалён с `main`; APK wiring, CI-release и установка APK не открывались, Supertonic остаётся default.

Документ продолжает серию `QWEN3_TTS_ISA_GATE.md` / `QWEN3_TTS_G2_KLEIDIAI_GATE.md`. Он не переписывает G1c/G1d/G2 и не меняет product-гейт `RTF ≤ 1.0`.

## 1. Граница и точка отсчёта

Разрешено было: plan `docs/plans/qwen3-tts-codec-cpu-threads.md`, research-workflow `.github/workflows/build-qwentts-arm-cpu8.yml`, этот документ, scratch-файлы на устройстве. Не трогались: `release-apk.yml`, APK, подпись, versionCode, теги `apk-*`, `README.md`, `MOBILE_AGENT/ЖУРНАЛ.md`, `MOBILE_AGENT/СОСТОЯНИЕ.md`, модели и эталонные `bin/` / `out-resident/`.

База: `ServeurpersoCom/qwentts.cpp @ 2edb35f`, ggml `ef4555b7aede4467a5de803b103615e0a5f30dd8`, модель `0.6B Base Q4_K_M`, `GGML_CPU_KLEIDIAI=OFF`, `GGML_CPU_ARM_ARCH` не задаётся (не повторяем G1d). FA уже присутствует в базе через предка `76ec9de648cd093db5ea904b8257644d7b3b8e63`.

Единственное различие между вариантами — одна строка в `src/backend.h:31`:

```c
// baseline
int n = (int) std::thread::hardware_concurrency() / 2;
// cpu8
int n = (int) std::thread::hardware_concurrency();
```

| Артефакт | sha256 |
|---|---|
| `qwen-tts` baseline | `5a54104f239545d8079f6a4c88f20248eb46020835261185e1e55c304401b445` |
| `qwen-tts` cpu8 | `0a1278d8d0c48a1c62803c941528f4d6cdca66e0c33134e2fa80733b5f92331b` |

Baseline побайтово совпал с G2. Все три `libggml*.so.1.0.0` идентичны в обоих артефактах (`d80869ac…`, `b0bc63b9…`, `0e5ea20a…`), то есть гейт действительно одно-переменный; изменились только четыре бинаря `qwentts.cpp`.

Smoke на устройстве подтвердил применение правки: в логе baseline `(CPU threads: 4)`, cpu8 `(CPU threads: 8)`, оба HTTP 200. Значит `hardware_concurrency()` вернул 8, как и ожидалось, — гейт инфраструктурно состоялся.

## 2. Пороги (зафиксированы до прогона, не менялись)

| Метрика | ACCEPT | DROP |
|---|---|---|
| RTF med short / medium / long | ≥10% улучшения в каждой категории | <10% или хуже хотя бы в одной |
| Первый PCM med (cold/short) | ≤ 796.4 мс | > 796.4 мс |
| Стабильность | 28/28 HTTP 200, 0 crash/hang, s16le 24 kHz mono | любой 4xx/5xx, обрыв, crash/hang |
| RU/WAV sanity | PCM ненулевой, peak > 0.01 | тишина/мусор/иной формат |

## 3. Инфраструктура (не считается вердиктом)

- CI: baseline run `46131574078`, cpu8 run `46131576096`, оба `success`. Patch-шаг нашёл точную строку; manifest с sha сохранён в артефактах.
- Device-путь: `/data/user/0/org.opencode.mobile.debug/files/qwen3-tts-arm-research/g3/{baseline,cpu8}/out`, 28-запросный `bench-arm-resident.mjs`, один резидентный `tts-server`, порты 4187/4189, sha-пины перед каждым прогоном.
- ADB уходил в offline во время cpu8-прогона; фоновый сборщик на ПК прервался, но сам бенч через `nohup` на устройстве завершился: `DONE 28`, exit 0, JSON `resident-matrix.json` забран после восстановления USB. Как инфраструктурный сбой прогон не отбрасывался: DEVICE-side sentinel и данные целы.
- Попытка подключиться по Wi‑Fi осложнилась VPN `tun_incy` на ПК и не совпадающими сетями (`10.0.0.x` у телефона против `192.168.1.x` у ПК). К вердикту отношения не имеет.
- A3 (повтор baseline) не запускался: он нужен был только при подозрении на дрейф, а вердикт устойчив и без него (см. раздел 5).

## 4. Результаты

Все 28 запросов в обоих прогонах — HTTP 200, 0 crash/hang, PCM s16le 24 kHz mono, peak > 0.01.

**Эталон — свежий baseline A2 этого же дня**, а не G2/A1. Дрейф A1 → A2 по медианам RTF: cold-first 2.6902 → 2.7826 (+3.4%), short 2.5882 → 2.7712 (+7.1%), medium 2.6699 → 3.0051 (+12.6%), long 3.0117 → 3.1785 (+5.5%). Поэтому сравнение с историческими числами G2 не используется для порога.

| Категория | RTF med A2 | RTF med cpu8 | Изменение | Порог | Проверка |
|---|---:|---:|---:|---|---|
| short (`warm-short`, 2 повтора) | 2.7712 | 2.4299 | −12.3% | ≤ 2.4941 | PASS |
| short (`warm-short-2`, 10 повторов) | 2.7269 | 2.6834 | −1.6% | ≤ 2.4941 | **FAIL** |
| medium | 3.0051 | 2.5638 | −14.7% | ≤ 2.7046 | PASS |
| long | 3.1785 | 3.2663 | **+2.8% (хуже)** | ≤ 2.8607 | **FAIL** |

Порог короткого прогона в G2 считался по тем же двум записям `warm-short`; здесь для robustness добавлен 10-повторный `warm-short-2`, который показывает, что кажущийся выигрыш −12.3% не воспроизводится на большей выборке.

| Метрика | baseline A2 | cpu8 | Проверка |
|---|---:|---:|---|
| Первый PCM, cold-first | 513 мс | **3828 мс** | FAIL (> 796.4) |
| Первый PCM, warm-short | 426 мс | 385 мс | PASS |
| Первый PCM, warm-medium | 1088 мс | 687 мс | не является порогом; лучше |
| Первый PCM, warm-long | 1880 мс | 1270 мс | не является порогом; лучше |
| Стабильность | 28/28 | 28/28 | PASS |
| Аудио | peak > 0.01 | peak > 0.01 | PASS |

### Stage-профиль (медианы, мс)

| Категория | Стадия | baseline A2 | cpu8 | Изменение |
|---|---|---:|---:|---:|
| short | TalkerDecode | 546 | 1158 | +112.0% |
| short | CodePredictor | 921 | 1490 | +61.9% |
| short | CodecDecode | 2265 | 2104 | −7.1% |
| medium | TalkerDecode | 2781 | 4423 | +59.0% |
| medium | CodePredictor | 4559 | 5521 | +21.1% |
| medium | CodecDecode | 11 608 | 6782 | −41.6% |
| long | TalkerDecode | 8960 | 14 866 | +65.9% |
| long | CodePredictor | 13 340 | 18 725 | +40.4% |
| long | CodecDecode | 33 996 | 23 362 | −31.3% |

Доля CodecDecode упала с 57.1 / 58.5 / 58.6% до 42.5 / 39.6 / 39.5%. Гипотеза «больше потоков ↗ CodecDecode» подтвердилась; но 8 потоков одновременно ухудшают autoregressive-стадии. В long экономия на CodecDecode ~10.6 с перекрывается потерей Talker+Predictor ~11.3 с, поэтому Total вырос с 57 976 до 59 190 мс.

## 5. Вердикт и рассуждение

**DROP.** Порог ≥10% улучшения в каждой категории не взят: long хуже baseline на 2.8%, 10-повторный short — всего −1.6%. Дополнительно не пройден порог первого PCM cold-first (3828 мс против 796.4 мс). По правилам 59/61 — откат, без повторных прогонов «на удачу» и без подгонки порогов.

Контр-аргумент «виноват дрейф стенда» не спасает гейт:

- свежий baseline A2 уже включает дрейф и всё равно проигрывает cpu8 в long;
- ухудшение не равномерное, а структурное: Prefill и CodecDecode быстрее, Talker и Predictor медленнее на 21–112%. Это похоже на конкуренцию 8 потоков с `opencode serve`, UI и системой на 8-ядерном чипе без SMT, а не на шум;
- если бы дрейф объяснял long, cpu8 должен был бы показать RTF ≤ 2.8607, то есть на 12% лучше замеренного A2 при уже ухудшенном эталоне. Такого разброса на одном стенде за один день нет.

Гипотеза «8 потоков снимут доминирование CodecDecode» **частично подтверждена**, но как продуктовая правка не годится: выигрыш кодера съедается регрессией остального пайплайна. Следующий research-гейт, если Макс его откроет, должен менять сам CodecDecode (например F16 → Q8_0/Q4_0), а не количество потоков.

**Откат:** research-workflow `.github/workflows/build-qwentts-arm-cpu8.yml` удалён с `main`. Артефакты и scratch-скрипты остаются только как diagnostics и в APK/CI-release не попадают. Product-гейт `RTF ≤ 1.0` не пересматривался.

## 6. Доказательства

- JSON: device `$ROOT/baseline/out/resident-matrix.json` и `$ROOT/cpu8/out/resident-matrix.json` (по 28 записей), локальные копии в scratch; `bg-matrix.done = 0` и `DONE 28`.
- CI: run `46131574078` (baseline success), `46131576096` (cpu8 success), manifest с sha бинарников.
- Измеритель: `bench-arm-resident.mjs`, протокол 28 запросов, один резидентный `tts-server`, sha-пины перед каждым прогоном.
- Строки `[Perf]` из stderr `tts-server` (`src/pipeline-tts.cpp:458-486`) и `[Load] … (CPU threads: 4|8)` из `src/backend.h:164` — без патча исходников приложения.
