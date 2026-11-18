## Структура проекта

```
app/
  src/main/
    java/org/opencode/mobile/
      MainActivity.kt            # точка входа, WebView, статус сервера
      OpencodeApp.kt             # глобальный конфиг каталогов (sandbox + $XDG_CONFIG_HOME)
      server/
        OpencodeServerService.kt # foreground-сервис: жизненный цикл serve
        OpencodeRuntime.kt       # запуск musl-лоадером + ensureMcpConfig (память MCP)
        RuntimeManager.kt        # стейт-машина запуска: память → serve → HEALTHY (backoff)
        RuntimeState.kt          # состояния рантайма
        RuntimeValidation.kt     # диагностика: порты, memoryHttpOk() (протокол MCP), модели
        ProcessSupervisor.kt     # рестарты с backoff + контроль процессов
        Workspace.kt             # пути к workspace/конфигу (external storage)
        ServerAuth.kt            # Basic Auth: генерация пароля, заголовки, Keystore/prefs
        LocalOpenCodeClient.kt   # единый HTTP-клиент serve (get/post/postAsync/delete + auth)
        ApiPaths.kt              # пути API opencode 2.x (/api/…)
        ApiShape.kt              # ответ 2.x → вид 1.x: конверт {data}, порядок, части
        ApiStatus.kt             # занятость сессии (в 2.x /session/status удалён)
        ApiMcp.kt                # три формы ответа /api/mcp → плоский список
        ApiAsk.kt                # запросы разрешений и вопросов + ответ на них
        AskBlocks.kt             # разбор questions[] из вызова question
        ServerFlavorProbe.kt     # сверка версии сервера с версией в APK (в лог)
        PermissionPlan.kt        # дописать "question":"ask" в управляемый конфиг
        Jsonc.kt, JsonBody.kt    # разбор jsonc-конфига и «это вообще JSON?»
        V1Leftovers.kt           # вычистить мёртвые node_modules 1.x из конфига
        Retention.kt             # JS-рантайм приложения (musl-Bun) + harness
        Ipv4Proxy.kt             # исходящая сеть (CONNECT-туннель, IPv4-first)
      stt/
        WhisperTranscribeService.kt  # foreground-сервис распознавания
        SpeechSegmenter.kt           # VAD-сегментация речи (ЭКСП-5)
        ChunkedTranscriber.kt        # чанкинг: сегменты → отдельные распознавания
        SttModels.kt                 # каталог моделей (сейчас одна — small) + замеры
        ModelDownloader.kt           # скачивание ggml-small (lazy, resume, integrity SHA-256)
        SttBenchReceiver.kt          # отладочный вход: WAV → та же очередь (для бенчей)
        SttWav.kt                    # разбор RIFF/WAVE
      engine/
        EngineCatalog.kt             # состав и размеры скачиваемых движков
        EngineStore.kt               # скачивание движка (resume, SHA-256, chmod +x)
        EngineLibs.kt                # System.load в порядке зависимостей
      installer/
        AppInstallBridge.kt          # loopback RPC для MCP: /v1/apps, /v1/media, /v1/account
        AppInstallWire.kt            # провод моста: тело запроса, коды ошибок, ответ на запуск
        ApkInstaller.kt              # скачивание/проверка APK → системный PackageInstaller
        InstalledAppController.kt    # PackageManager: список и запуск launcher-приложений
      media/
        MediaControlController.kt    # MediaSession: status/control/like без запуска activity
        MediaWire.kt                 # формы медиа-ответов моста (verified живёт внутри media)
      ui/
        ChatOverlay.kt           # нативный чат поверх WebView + настройки
        chat/
          ChatApi.kt             # снимок чата: /message + /mcp + плашка → ChatSnapshot
          SessionOps.kt          # сессии: создать, прервать, удалить, вычистить пустые
          PromptOps.kt           # промпт, очередь, ответ на запрос сервера (ask)
          ChatStallTracker.kt    # зависание хода, бюджет повторов, пауза поллинга
          ChatRows.kt            # строки ленты: thinking, live-tool, плашка, завис, отказ
          HeaderIndicators.kt    # шапка: статус/пинг, Zen-квота, контекст, MCP-список
          VoiceInput.kt          # запись WAV, распознавание речи, вибро, звук ответа
          MessageBubbles.kt      # реплика: заголовок роли, печать по буквам, TTS-выбор
          ChatAppearance.kt      # шрифт/цвет ответов, форма и палитра полос + правила записи в prefs
          VoiceSettings.kt       # движок распознавания и модель whisper: миграция ncnn, отбраковка
          TtsSettings.kt         # озвучка: движок/голос/sid, включённость = engine != Off
          TtsNarration.kt        # курсор озвучки: какой оборот и реплика уже отданы
          ElevenKeySettings.kt   # ключ ElevenLabs: черновик не чистится при сбое
          ChatSnapshotDelta.kt   # что считать изменением снапшота (гейт перерисовки)
        AskCardView.kt           # вёрстка карточки вопроса/разрешения
        SttModelPicker.kt        # выбор модели STT (нескачанная не выбирается)
        EmptyChatArt.kt          # дождь на пустом экране (рисуется кодом)
        DiagnosticsScreen.kt     # экран диагностики (порты/MCP/движки/модели)
        theme/Theme.kt
      tts/                      # озвучка: SpeechFactory, TtsNarrator, Supertonic/ElevenLabs
      social/                   # мобильный агент moltbook: тик, будильник, watchdog
    assets/                       # шрифты, MCP-ассеты (memory/moltbook/ynison), harness
    jniLibs/arm64-v8a/           # нативы НЕ в git: opencode + musl/Bun/GCC + sherpa (см. fetch-opencode-binary.ps1)
 whisperlib/                      # JNI-обёртки whisper.cpp
   src/main/jni/
     whisper/                     # whisper.cpp (ggml; use_gpu + flash_attn + квант-гейт)
     whisper/CMakeLists.txt       # переменные внешних путей: WHISPER_LIB_DIR
docs/
  MIGRATION-V2.md                # переход на opencode 2: что поменялось, что отменили
  ROADMAP.md                     # треки к релизу + статусы
  VOICE-REPORT.md                # голос: замеры всех моделей, выбор small, разбор краша ncnn
  ncnn-vulkan-crash-report.md    # баг-репорт в ncnn (Adreno 840, SIGSEGV)
  EXPERIMENTS-STT-LATENCY.md     # замеры STT-латентности, ЭКСП-5 (чанкинг)
  stt-bench-2026-09-24.csv       # бенч int8 vs fp32 (24.09.2026)
tools/
  fetch-opencode-binary.ps1      # скачать все 8 нативов (opencode, musl, Bun, GCC, sherpa) — в git их нет
  adb-auth-common.ps1            # пароль serve из /proc + готовый Basic-заголовок
  stt-accuracy.ps1               # бенч точности STT без участия человека (WER по эталону)
build.ps1                        # локальная сборка на Windows (альтернатива CI); основной путь — .github/workflows/release-apk.yml
```

---

