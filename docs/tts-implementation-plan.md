# План: озвучка ответов (SupertonicTTS 3 int8) в opencode-mobile

Дата: 03.10.2026. Требования: `ISSUES_FOR_PC_AGENT_part19.md` §12.3, §12.5.
Замеры: `2026-10-03-supertonic3-bench-sm8850.md`. Голос: `--sid=0` (выбрал юзер).

## 1. Решение по способу запуска движка

Кандидатов было два.

| | CLI-процесс | JNI внутри приложения |
|---|---|---|
| Что | `sherpa-onnx-offline-tts` как subprocess из `filesDir` | `libsherpa-onnx-jni.so` + `libonnxruntime.so` в `jniLibs` |
| APK | +24 МБ (бинарь) | +44 МБ (для arm64: 23 + 21) |
| Модель | грузится **каждый вызов** | один раз, живёт в процессе |
| TTFA предложения | 1.2 с загрузки + 1.3 с синтеза = **~2.5 с** | **~1.3 с** |
| Отмена | убивать процесс | сброс очереди, fade-out |
| Статус | работает, но не тянет ориентир «первый звук ~1 с» | выбран |

CLI умеет ровно один вызов на процесс: в `--help` нет ни stdin-режима, ни
`--max-duration`, ни `--sample-rate`. То есть для варианта «озвучка по мере генерации»
каждое предложение стоило бы 2.5 с вместо 1.3 с. **Берём JNI.**

Нулевые копии уже есть локально: `bench\models\jniLibs\arm64-v8a\libsherpa-onnx-jni.so`
(23 МБ) и `bench\models\sherpa-onnx-v1.13.8-android-aarch64-termux-shared\lib\libonnxruntime.so`
(21 МБ). Их достаточно — ничего докачивать не нужно.

## 2. Чего ещё нет: Kotlin API

У sherpa-onnx нет maven-артефакта — Kotlin API лежит исходниками в
`sherpa-onnx/kotlin-api/*.kt` (MIT). Их надо положить в проект:
`OfflineTts.kt`, `OfflineTtsConfig.kt`, `GeneratedAudio.kt`, плюс `WaveReader.kt`
(нужен для JNI-сигнатуры `OfflineTts.acceptWaveFile`).

## 3. Структура пакета `tts/`

```
tts/
  TtsConfig.kt          engine off|system|sherpa, model, sid, speed  → prefs
  TtsVoice.kt           список голосов: supertonic sid 0..4, piper ruslan/…
  SupertonicTts.kt      обёртка над sherpa-onnx OfflineTts: init, warmup,
                        synthesize(text) → FloatArray PCM, numThreads=2, sid
  SystemTts.kt          android.speech.tts → WAV → FloatArray (фолбэк)
  SentenceChunker.kt    буфер текста → предложения по . ! ? \n ; : с лимитом
                        длины; первый чанок режется короче ради TTFA
  TtsSpeaker.kt         один worker: очередь, fade-out, stop(), единственная
                        точка владения AudioTrack
  AudioTrackPlayer.kt   AudioTrack 44100/stereo/PCM16, fade-out по громкости
```

## 4. Поток данных

```
LLM-чанки → SentenceChunker → TtsSpeaker.enqueue(sentence)
                                    ↓ (один worker)
                          SupertonicTts.synthesize → FloatArray
                                    ↓
                          AudioTrackPlayer.play (fade-in 30 мс)
                                    ↓
                          конец очереди → тишина
stop()  → очередь.clear() + fade-out 120 мс
```

Один worker, последовательная очередь — как требовал §12.5 и как уже сделано для STT.

## 5. Настройки

Рядом с `stt_engine` / `stt_model` в том же `prefs`:

| ключ | значения | дефолт |
|---|---|---|
| `tts_engine` | `off` / `system` / `sherpa` | `off` |
| `tts_model` | каталог модели | `supertonic-3-tts-int8` |
| `tts_sid` | 0..4 (supertonic) | **`0`** |
| `tts_speech_rate` | 0.5..2.0 | 1.0 |

`--sid` отдельным ключом, не внутри `tts_voice` — иначе смена голоса требует
пересборки APK. Это ровно то замечание, которое прислал мобильный агент.

## 6. Модель

Каталог `files/models/tts/supertonic-3-tts-int8/` (138 МБ), скачивание тем же
`stt/ModelDownloader.kt`, что и для Whisper. В APK не класть.

Первый запуск: пока грузится — `warmup()`, 1.2 с. Дальше движок тёплый.

## 7. Что уже подтверждено замерами

| Параметр | Значение | Откуда |
|---|---|---|
| `--num-threads` | 2 | 0.32 RTF против 0.58 на 4 потоках |
| скорость | 3.3x быстрее реального времени | 23.0 с на 76.3 с аудио |
| память | 309 МБ / 8 с аудио, 428 МБ / 76 с | +1.75 МБ на секунду |
| первый звук | ~1.3 с на предложение | не «до 1 с», ориентир честно повышен |
| формат | 44100 Гц, 2 канала | из WAV бинаря |

## 8. Порядок работ

1. `TtsConfig` + `TtsVoice` + ключи в prefs.
2. `SentenceChunker` с тестами на границах предложений.
3. `SupertonicTts` (JNI), проверка на устройстве тем же вопросом, что в бенче.
4. `AudioTrackPlayer` + `TtsSpeaker` с очередью и fade-out.
5. Выключатель в панели `ChatOverlay` рядом с настройками STT.
6. Проверка отмены: стоп на середине → тишина.
7. `runTtsTest` перевести на новый движок (§12.4 — регресс-тест STT на синтетической речи).

## 9. Открытые риски

- `libsherpa-onnx-jni.so` весит 23 МБ и тянет `libonnxruntime.so` 21 МБ — APK
  растёт примерно на 44 МБ. APK уже ~301 МБ. Если это неприемлемо, откат на
  CLI-процесс с TTFA 2.5 с.
- arm64-only: на x86-эмуляторе TTS не запустится, нужен graceful degrade в `system`.