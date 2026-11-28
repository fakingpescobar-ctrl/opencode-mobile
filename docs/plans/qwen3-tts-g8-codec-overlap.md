# Qwen3-TTS: G8 — перекрытие CodecDecode с AR-стадиями (CPH2747, 29.11.2026)

## Goal

Проверить, убирает ли ≥10% задержки асинхронный запуск `CodecDecode`: чанк N кодируется на отдельном воркере, пока Talker/CodePredictor строят чанк N+1. Это единственный оставшийся рычаг с потолком выше порога: сейчас `27 + 45 + 64 ≈ 136 мс/кадр` складываются последовательно, 10% RTF = 13.6 мс/кадр, а все ядерные микроправки (G7, TransConv F16, im2col work size, flash-attn, F16 KV) дают 1–4% по оценке.

## Non-goals

- не менять `release-apk.yml`, APK wiring, подпись, versionCode, теги `apk-*`, установку APK;
- не менять дефолтный TTS (Supertonic) и `MOBILE_AGENT/*`, `README.md`;
- не повторять G1c/G1d (ARM arch), G2 (KleidiAI), G3 (глобальные 8 потоков), G5a/G5b (квант кодера), G6 (ширина чанка), G7 (F16 dot);
- не включать SVE2/SME2;
- не менять модель, веса, число потоков, dtype, ширину чанка, внимание и ggml.

## Constraints

- база: ACCEPT-ed G4 codec8, `qwen-tts` `595035cd49902412b1085f5a6cdeec5aa5cdf7ef7ae49f498fb3fa3d3921e202`, `libggml-cpu.so.1.0.0` `b0bc63b93b2d789aaefdf0893b534044563561e40e8c8db856c91a60434a711f`;
- исходники: `ServeurpersoCom/qwentts.cpp @ 2edb35f87b709a070b2d5379f77e5c52a1daa585`, ggml `ef4555b7aede4467a5de803b103615e0a5f30dd8`;
- research-only `workflow_dispatch`, один прогон = один вердикт;
- сборка musl aarch64 через Zig wrapper и флаги G4/G6/G7 байт-в-байт; ggml не патчится;
- ADB-демон не перезапускать; фоновые прогоны только device-side `nohup` + done-сентинел; без UI-автоматизации;
- порты: base `4215`, overlap `4217` (свежие; 4199/4211/4213 уже использовались).

## Единственная переменная

Один файл `src/pipeline-tts.cpp`: `tts_engine_codec_flush` перестаёт быть синхронным вызовом внутри `tts_engine_step`. Вместо этого готовые строки чанка копируются в снимок задачи, и один выделенный воркер вызывает `pipeline_codec_decode_stream_batch` и `on_chunk` асинхронно, пока основной поток продолжает AR. Всё остальное байт-в-байт как в базе: число потоков (4 AR / 8 codec), dtype, `CODEC_STREAM_CLASSES`, KV, attention, ggml, измеритель.

Обязательные условия корректности патча (деталь реализации, не новые переменные): снимок кодов/флагов/колбэка на момент постановки в очередь; FIFO; дренаж воркера перед `tts_engine_codec_admit`, перед выводом завершившихся слотов и в `tts_engine_free`; отложенный write-back отказа `on_chunk` через atomic-маску; `codec_ms` накапливается воркером и списывается на кадр на дренаже. Любая правка вне `src/pipeline-tts.cpp` или изменение ggml означают выход за гейт и откат.

## Пороги (фиксируются до правок и сборки)

**Скрининг** (точка решения, 3×`warm-short` + 3×`warm-medium` на вариант, base → overlap):
- OPEN FULL MATRIX только если медиана RTF улучшилась **≥10% одновременно** в `screen-short` и `screen-medium`;
- guard: `TalkerDecode` и `CodePredictor` мс/кадр не хуже базы более чем на 10% в обеих категориях;
- 6/6 HTTP 200, `done=0`, s16le 24 kHz mono, peak >0.01, rms >0.001, 0 crash/hang.

Иначе **DROP без полной матрицы**, A2 не запускается.

**Полная матрица** (28 запросов: cold-first 1, warm-short 2, warm-short-2 10, warm-medium 10, warm-long 5), A1 base → overlap → A2 base при дрейфе A1→вариант >10%:
- ≥10% улучшения RTF med в `warm-short`, `warm-short-2`, `warm-medium`, `warm-long` относительно swinging baseline A1/A2;
- 28/28 HTTP 200, 0 crash/hang, s16le 24 kHz mono, peak >0.01, rms >0.001;
- ±30% по аудио (длина/peak/rms) относительно базы;
- cold-first PCM ≤796.4 мс;
- guard Talker/CodePredictor ≤10% регресса на кадр.

ACCEPT только при выполнении всех порогов. Равный или худший результат → DROP.

## Доказательство попадания патча

Патч в `src/pipeline-tts.cpp` меняет `libqwen.so`, а не `qwen-tts`/`libggml-cpu`: байт-идентичность `qwen-tts` `595035cd...` и `libggml-cpu` `b0bc63b9...` обязательна у обоих вариантов, а sha `libqwen.so` у overlap обязан отличаться от базы. Дополнительно в логе загрузки проверяется маркер G8. Device-side скрипт сверяет все три sha перед каждым прогоном.

## План работ

1. Зафиксировать этот план коммитом и пушем **до любой правки кода**.
2. Добавить research-workflow `.github/workflows/build-qwentts-arm-overlap.yml` с вариантами `baseline | overlap`, повторяющий G4-патч кодера и флаги сборки G7; в варианте `overlap` применяется только патч `pipeline-tts.cpp`.
3. Собрать оба варианта в CI, проверить manifest/sha, скачать артефакты, подготовить device-side каталог `qwen3-tts-arm-research/g8` со скриптами и sha-пинами.
4. Скрининг base → overlap; решение по зафиксированной точке выше.
5. При OPEN: полная матрица и расчёт медиан через `gate-calc.py`; при DROP: записать вердикт без матрицы.
6. Записать `docs/research/QWEN3_TTS_G8_CODEC_OVERLAP_GATE.md`, `git diff --check`, коммит и пуш только research-артефактов; обновить память.
