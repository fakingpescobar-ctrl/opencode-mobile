# Qwen3-TTS: G5a — квантование codec GGUF `Q4_K_M` → `Q8_0`

## Goal

Проверить при заранее зафиксированных порогах, даёт ли замена **только квантования модели кодера** ≥10% улучшения RTF без регресса Talker/CodePredictor и без потери качества аудио. Гейт research-only: результат — ACCEPT или DROP с формальным вердиктом, без изменения APK и дефолтного TTS.

## Non-goals

- не менять `release-apk.yml`, APK wiring, подпись, versionCode, теги `apk-*`, установку APK;
- не менять дефолтный TTS (Supertonic);
- не трогать `README.md`, `MOBILE_AGENT/ЖУРНАЛ.md`, `MOBILE_AGENT/СОСТОЯНИЕ.md`;
- не повторять G1d, G2, G3, G4 и не открывать Vulkan-гейт A (он остаётся отложенным по `qwen3-tts-g5-vulkan-codec-feasibility.md`);
- не менять talker-модель, бинари, флаги сборки и не пересматривать product-гейт `RTF ≤ 1.0`.

## Constraints

- единственная переменная гейта: файл codec GGUF. Бинари — байт-в-байт из G4 codec8 (`qwen-tts` sha256 `595035cd49902412b1085f5a6cdeec5aa5cdf7ef7ae49f498fb3fa3d3921e202`);
- база: `ServeurpersoCom/qwentts.cpp @ 2edb35f`, ggml `ef4555b7aede4467a5de803b103615e0a5f30dd8`;
- talker: `qwen-talker-0.6b-base-Q4_K_M.gguf`, sha256 HF LFS `4b468ec7b1f62b90ef4ca316c0aa57deadfd54b2cf9651703ea753cedaf04226` — без изменений;
- codec baseline: `qwen-tokenizer-12hz-Q4_K_M.gguf`, sha256 `cf3788b4d50aaa665fb6e57c170396aae03a3555fea52d2b5d0cda902d658039` (совпадает с устройством);
- codec вариант: `qwen-tokenizer-12hz-Q8_0.gguf`, 291 150 624 байт, sha256 HF LFS `1883beeed99348fc35e23dd225e9082f93f6f8c109330a33d935baa8acdbfd94`;
- новый CI-build **не нужен**: исходники и флаги не меняются, бинари копируются из G4 с проверкой sha. Любой добавляемый workflow — только `workflow_dispatch`;
- пороги наследуются от G4: ≥10% улучшения RTF med в `warm-short`, `warm-short-2` (10 повторов), `warm-medium`, `warm-long`; guard Talker/CodePredictor — не хуже baseline более чем на 10% на кадр; cold-first PCM ≤ 796.4 мс; 28/28 HTTP 200, 0 crash/hang, s16le 24 kHz mono, peak > 0.01;
- сравнение — только против **свежего** baseline с теми же бинарями и Q4_K_M, запущенного непосредственно перед вариантом; при подозрении на дрейф — повтор baseline (A2);
- замеры device-side в фоне, с done-сентинелом: ADB нестабилен под sustained load.

## Current behaviour

- `bench-arm-resident.mjs:12` жёстко задаёт `CODEC = .../qwen-tokenizer-12hz-Q4_K_M.gguf`; `:155` пишет имя модели в JSON.
- На устройстве в `files/qwen3-tts-06b-bench/models/` лежат оба Q4_K_M файла; sha256 codec совпал с HF LFS oid `cf3788b4…`.
- HF-репозиторий `Serveurperso/Qwen3-TTS-GGUF` содержит `qwen-tokenizer-12hz-Q8_0.gguf` (291 150 624 байт, LFS `1883beee…`) — рядом с уже используемым Q4_K_M (254 974 752 байт, LFS `cf3788b4…`).
- В G4 используется резидентный `tts-server`, 28 запросов: cold-first 1, warm-short 2, warm-short-2 10, warm-medium 10, warm-long 5. Измеритель пишет `rtf`, `firstPcmMs`, `peak`, `rms`, `stderrTail` с `[Perf]`-строками.
- G4 codec8 — текущий лучший research-вариант по RTF; поэтому G5a строится **поверх его бинарей**, а не поверх 4-поточного baseline.

## Proposed design

1. Measurement harness получает env `QWEN_CODEC_MODEL` (по умолчанию прежний Q4_K_M); это не переменная гейта — правка одинакова для обеих сторон сравнения. Поле `model` в JSON отражает фактическое имя codec-файла.
2. В `files/qwen3-tts-arm-research/g5a/` копируются бинари из `g4/codec8/bin` с сохранением `.sha256`; создаются два out-каталога и run-скрипт с вариантами `q4` (порт 4191) и `q8` (порт 4193). Перед каждым прогоном скрипт проверяет sha бинаря **и** codec-модели.
3. Прогон `q4` (fresh baseline), затем сразу `q8`; при дрейфе — повтор `q4`. Один прогон = один JSON = один вердикт.
4. Сравнить медианы RTF, first PCM, пер-кадровые стадии и аудио-метрики; записать вердикт в `docs/research/QWEN3_TTS_G5A_CODEC_QUANT_GATE.md`.

Альтернатива, отклонённая: сравнивать Q8_0 против G4 A1/A2 напрямую — туда попадут и дрейф стенда, и разница в потоках, то есть гейт перестанет быть одно-переменным.

## Interfaces

- `QWEN_CODEC_MODEL` — абсолютный путь к codec GGUF в `bench-arm-resident.mjs`; default = прежний Q4_K_M.
- `QWEN_VARIANT` — `g5a-q4` / `g5a-q8`; `QWEN_PORT` — 4191 / 4193; `QWEN_OUT` — `g5a/out/q4` / `g5a/out/q8`.
- JSON: поле `model` становится `qwen-talker-0.6b-base-Q4_K_M + <fact codec>`, остальные поля без изменений.

## Steps

1. Скачать `qwen-tokenizer-12hz-Q8_0.gguf`, проверить размер 291 150 624 и sha256 `1883beee…`, положить в `files/qwen3-tts-06b-bench/models/`. Verify: `sha256sum` на устройстве.
2. Подготовить `g5a/`: скопировать бинари из `g4/codec8/bin`, положить обновлённый bench и run-скрипт, проверить sha-пины. Verify: smoke `q4` и `q8`, оба HTTP 200, peak > 0.01, в логе один и тот же `[Load] Codec backend: CPU (CPU threads: 8)`.
3. Запустить `q4` matrix (28 запросов) device-side в фоне с `bg-matrix.done`. Verify: `DONE 28`, done=0, 28/28 HTTP 200.
4. Запустить `q8` matrix тем же способом. Verify: то же.
5. При подозрении на дрейф повторить `q4` (A2). Verify: сравнить медианы RTF `q4` до/после.
6. Рассчитать медианы, дельты, guard, пер-кадровые стадии и аудио-метрики; записать вердикт. Verify: `docs/research/QWEN3_TTS_G5A_CODEC_QUANT_GATE.md`.
7. `git diff --check`, коммит и пуш только research-документации. Verify: `git status` — без APK/CI-release/MOBILE_AGENT.

## Risks

- Q8_0 больше по размеру → возможен рост cold-first PCM; порог 796.4 мс остаётся в силе.
- Качество может измениться: помимо `peak > 0.01` сравнивать `rms` и форму сигнала на одинаковых промптах; при заметной деградации — DROP даже при выигрыше RTF.
- Выигрыша по RTF может не быть: Q8_0 дороже по памяти/пропускной способности, чем Q4_K_M.
- Дрейф стенда (в G4 A1→A2 до +19% RTF) и нестабильность ADB под нагрузкой → обязательны свежий baseline и done-сентинел.
- Откат: research-артефакты остаются вне APK; product-гейт не пересматривается.

## Open questions

1. Если G5a даст DROP — пробовать ли `Q4_0` как следующий одно-переменный гейт? Решение Макса после вердикта.
2. Если Q8_0 выиграет по RTF, но ухудшит качество — ACCEPT research-результата или DROP по аудио? Порог качества зафиксирован как «заметная деградация → DROP», окончательную оценку даёт прослушивание Макса.
