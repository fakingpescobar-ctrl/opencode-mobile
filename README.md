# OpenCode Mobile

<p align="center">
  <img src="social-preview.png" alt="OpenCode Mobile — превью" width="100%">
</p>

Android-приложение, которое запускает **opencode serve** прямо на устройстве
(без Termux, без root) и даёт полноценный чат с AI-моделью в нативном интерфейсе.

`Kotlin` · `Jetpack Compose` · `WebView` · `ncnn`(STT)` · `musl`

---

## Что это

Обычно opencode работает на десктопе (CLI/TUI) или на сервере. Это приложение
упаковывает сам бинарь opencode внутрь APK и поднимает локальный HTTP-сервер
прямо на телефоне/планшете. Поверх сервера — два слоя UI:

1. **WebView** с веб-интерфейсом opencode (тот же, что в браузере).
2. **Нативный чат-оверлей** поверх WebView — так как официальная SPA не
   рендерит ленту сообщений в окружении WebView, чат реализован нативно:
   приложение опрашивает локальный API opencode каждые 2 секунды и рисует
   переписку сам (Compose).

Итого: «всё в один тап» — открыл приложение, написал сообщение, получил ответ
от модели, которая считается локально на устройстве.

> Это экспериментальный / демонстрационный проект (MVP). Подробности по
> параметрам сборки и известным ограничениям — в конце.

---

## Возможности

### Чат
- Полноценная переписка с моделью в нативном интерфейсе (Compose).
- Поле ввода, оптимистичная отправка, автопрокрутка (с умной остановкой,
  когда пользователь сам листает историю вверх).
- Индикатор «думает» (анимированные полоски) пока модель размышляет.
- Вибрация при завершении ответа.
- Поддержка **вопросов от модели**: если модель спрашивает выбор/уточнение —
  приложение показывает варианты-кнопки и поле для своего ответа.

### Локальная память (MCP)
- Вместе с serve поднимается второй процесс — **memory.js**: MCP-сервер
  локальной памяти (`127.0.0.1:4199/mcp`, streamable HTTP), хранилище в
  `$XDG_CONFIG_HOME/opencode/memory/`. Модель получает memory-инструменты
  (запоминать/искать по прошлым сессиям).
- Индикатор **«N MCP»** в шапке чата — сколько MCP-серверов зарегистрировано
  в serve (запрос `GET /mcp`). Если конфиг потерялся — `ensureMcpConfig()`
  сам допишет секцию `mcp.memory` в `opencode.jsonc` до старта serve
  (фикс «0 MCP»). Проверка в диагностике — протокольная: `GET /mcp` → 2xx
  (не просто TCP-порт).

### Голосовой ввод (STT) — два движка
1. **Системный Android (Google)** — встроенный распознаватель, не требует
   загрузки моделей, нужен доступ к сети.
2. **NCNN (локально, int8-encoder)** — тот же whisper через ncnn, офлайн:
   CPU int8 encoder (block-quant) ~2× быстрее fp32, KV-cache в decoder.
   Движок **whisper.cpp/ggml убран из настроек** (legacy: `use_gpu=false`,
   скрыт) — рабочий локальный путь только через ncnn.

Движок выбирается в настройках (шестерёнка в шапке чата).

**Потоковый пайплайн (ЭКСП-5)**: VAD-сегментация + чанкинг — длинная речь
режется на сегменты, каждый распознаётся отдельно (первые слова видны на
1–2 с, без потери хвоста на записи >30 с, меньше галлюцинаций).
Классы: `SpeechSegmenter`, `ChunkedTranscriber`. Подробности —
`docs/EXPERIMENTS-STT-LATENCY.md`.

### Модель STT
- **large-v3-turbo (574 МБ)** — единственная рабочая модель; скачивается по
  требованию из панели настроек (прогресс, докачка при разрыве, проверка
  целостности — SHA-256 + размер в sidecar `.size`).
- **base (141 МБ)** — **убрана/не выбирается**: конвертация в ncnn даёт мусор
  на выходе; код остался в `ModelDownloader`, но в UI отсутствует.

### Настройки чата
- **Шрифт ответов модели** — 20+ встроенных шрифтов (тап по образцу — сразу
  применяется и сохраняется; кнопка — иконка шрифта).
- **Цвет ответов модели** — градиент-пикер (тап/драг по квадрату; кнопка —
  иконка-капля, тонируется текущим цветом ответов).
- **Диагностика STT** — «TTS-тест»: синтезирует фразу системным TTS и гонит её
  через выбранный движок распознавания. Позволяет понять — проблема в модели/пайплайне
  или в микрофоне (результат в logcat по тегу `VOICE`).

### Сервер
- Foreground-сервис держит процесс opencode serve, рестартует его с backoff
  при падении, проверяет доступность по HTTP.
- **Basic Auth**: serve доступен наружу (HTTP на `127.0.0.1:4096`), поэтому
  все запросы идут с заголовком Authorization; пароль генерируется при
  первом запуске и лежит в зашифрованных prefs (`ServerAuth`). Без пароля —
  `401 Unauthorized` (проверено на чистой установке).
- Рабочая директория (workspace, конфиг, сессии, память) — на внешнем
  хранилище `/sdcard/Documents/OpencodeTerminal/opencode/`, переживает
  переустановку приложения (в отличие от prefs/моделей).
- Уведомление с кнопкой «Stop» и статусом процесса.
- Диагностика (`RuntimeValidation`): порты serve/память, протокольная
  проверка MCP (`/mcp` → 2xx), наличие и валидность ncnn-моделей — экран
  «Диагностика» в настройках.

---

## Техническое устройство (как это работает)

### Запуск opencode на Android без root

Android-ядро позволяет `execve` только с **PIE**-бинарём, и только из
`nativeLibraryDir` для `untrusted_app`. Поэтому:

| Файл (`app/src/main/jniLibs/arm64-v8a/`) | Роль |
|---|---|
| `libopencode.so` | Бинарь opencode (musl-сборка, переименован в `lib*.so`) |
| `libldmusl.so` | musl-лоадер (это `ld-musl-aarch64.so.1`, он же libc) |
| `libc_musl.so`, `libstdcxx.so`, `libgcc_s.so` | musl-libs под placeholder-именами |

Ключевые моменты:

1. **`useLegacyPackaging = true`** в `app/build.gradle.kts` — заставляет
   `extractNativeLibs=true`. Без этого `.so` не извлекаются на диск (маппятся из
   APK для dlopen) и `execve` невозможен.
2. Бинарь — **динамический musl**: в нём `PT_INTERP=/lib/ld-musl-aarch64.so.1`,
   которого в системе нет. Поэтому лоадер запускается первым аргументом:
   `ld-musl ./libopencode.so serve --port 4096`.
3. **Имена `lib*.so`** — PackageManager извлекает только такие файлы; при старте
   зависимые либы копируются в `filesDir/musl` с правильными DT_NEEDED-именами,
   на них указывает `LD_LIBRARY_PATH`.

### Локальная память MCP и Basic Auth

| Процесс | Порт | Роль |
|---|---|---|
| `libopencode.so serve` | `127.0.0.1:4096` | сам сервер opencode (HTTP API, Basic Auth) |
| `libbun-musl.so memory.js` | `127.0.0.1:4199` | MCP-сервер локальной памяти (streamable HTTP/SSE) |

- Модель видит память, только если serve **зарегистрировал** MCP-сервер.
  Регистрация — секция `"mcp": { "memory": { "type": "remote", "url": ... } }`
  в `$XDG_CONFIG_HOME/opencode/opencode.jsonc` (тот же формат, что на
  десктопе: `~/.config/opencode/opencode.json`). `OpencodeRuntime.ensureMcpConfig()`
  дописывает её идемпотентно (атомарно tmp+rename) до старта serve — фикс
  «0 MCP» (пустой конфиг, когда память слушала порт, но не была зарегистрирована).
- Индикатор «N MCP» в шапке чата = ответ `GET /mcp` с сервера (через
  `LocalOpenCodeClient` с авторизацией; без заголовков serve отвечает 401).
- Память хранится в `$XDG_CONFIG_HOME/opencode/memory/` (json-каталог прошлых
  сессий), переживает переустановку приложения.

### STT на CPU (ncnn, int8)

На ColorOS/OPPO фоновые compute-потоки душатся до 1–5% CPU. Поэтому
распознавание выполняется в **foreground-сервисе** (`WhisperTranscribeService`) —
так ОС даёт процессу нормальный приоритет. Модель кэшируется в память между
вызовами (грузится один раз), что критично для тяжёлого turbo (574 МБ).

Производительность (OPPO, 02.09.2026): **encoder int8 ≈ 8.82 s** (fp32 — 17.9 s;
Vulkan-эксперимент 6.45 s — стабилен, но в проде выключен), decoder с KV-cache
~0.5–0.6 s → полное распознавание ~10 s. Бенч 24.09.2026: int8 6.8–8.2 s vs
fp32 14.5–15.2 s (~2.1×). Подробности: `docs/EXPERIMENTS-STT-LATENCY.md`,
`docs/stt-bench-2026-09-24.csv`.

Запись: `AudioRecord` → PCM16 16кГц → нормализация пика (телефоны пишут тихо) →
подкладка тишины (whisper врёт на очень коротких клипах) → распознавание →
текст в поле ввода.

---

## Сборка

### Требования
- **Android Studio** (или Android SDK + JDK 17) с SDK Platform 36.
- ARM64-устройство (физический телефон) или arm64-эмулятор.
- Файл `local.properties` с путём к SDK (не коммитится в git).

### Шаги
1. Открыть папку проекта в Android Studio, дождаться синка Gradle.
2. Собрать: `Build → Build APK(s)` или из терминала:
   ```bash
   ./gradlew :app:assembleDebug
   ```
   На Windows есть обёртка `build.ps1` (Android Studio JDK, offline, лог
   в `build-inst.log`):
   ```powershell
   powershell -ExecutionPolicy Bypass -File build.ps1 -Task debug
   ```
3. APK появится в `app/build/outputs/apk/debug/app-debug.apk`.
4. Установить:
   ```bash
   adb install -r app/build/outputs/apk/debug/app-debug.apk
   ```
   или просто скопировать APK на телефон и открыть.

### Про бинари (важно)

- `app/src/main/jniLibs/arm64-v8a/` — **в git**: prebuilt `libopencode.so` (~184МБ,
  opencode, musl-сборка), musl-libs, `libbun-musl.so`; приложение собирается и
  запускается из них напрямую (CI проверяет их наличие).
- whisper-нативка (`whisperlib`) **собирается при сборке приложения** из
  исходников: `third_party/ncnn` (in-repo) + **внешний** `whisper.cpp` (вне репо)
  и VulkanSDK (Windows). Пути конфигурируются: `-PwhisperCppDir=...`,
  `-PvulkanSdkDir=...`, отключение нативки — `-PskipNativeBuild=true`
  (используется в CI — ubuntu не имеет ни whisper.cpp, ни VulkanSDK).
- Модели whisper (turbo) в assets **не хранятся** — качаются по требованию через
  `ModelDownloader` в `filesDir/models/` (ncnn-формат: `.ncnn.param/.bin`,
  int8-вариант энкодера — automatically).

---

## Установка готового APK

Установка:

> ⚠️ Два пакета сосуществуют: **release** (`org.opencode.mobile`, «OpenCode Mobile») и
> **debug** (`org.opencode.mobile.debug`, «OpenCode Mobile · Debug»). Отличаются по названию
> под значком.

- **Debug** («OpenCode Mobile · Debug»):
  ```bash
  adb install -r app/build/outputs/apk/debug/app-debug.apk
  adb shell monkey -p org.opencode.mobile.debug -c android.intent.category.LAUNCHER 1
  ```
- **Release** («OpenCode Mobile», R8-минифицирован, подписан debug-ключом для локального теста):
  ```bash
  gradlew.bat :app:assembleRelease -x lint
  adb install -r app/build/outputs/apk/release/app-release.apk
  adb shell monkey -p org.opencode.mobile -c android.intent.category.LAUNCHER 1
  ```

Логи смотреть:
```bash
adb logcat -s OpencodeRuntime OpencodeServerService OpencodeWebView VOICE RuntimeValidation ChatOverlay
```

> ⚠️ **После переустановки (uninstall → install)**: сессии/история/память на
> внешнем хранилище сохраняются, но **авторизация сбрасывается** — serve
> сгенерирует новый пароль, и ncnn-модели STT нужно раскатать заново
> (`filesDir` стирается). Проверка: «OpenCode server — Running», мониторинг
> чата работает с новым паролем автоматически.

---

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
        Ipv4Proxy.kt             # исходящая сеть (CONNECT-туннель, IPv4-first)
      stt/
        WhisperTranscribeService.kt  # foreground-сервис распознавания
        SpeechSegmenter.kt           # VAD-сегментация речи (ЭКСП-5)
        ChunkedTranscriber.kt        # чанкинг: сегменты → отдельные распознавания
        NcnnModelValidator.kt        # валидация набора ncnn-моделей
        ModelDownloader.kt           # скачивание turbo (lazy, resume, integrity SHA-256)
        NcnnModelDownloader.kt       # ncnn-turbo набор (16 файлов) из GitHub Releases
      ui/
        ChatOverlay.kt           # нативный чат поверх WebView + настройки
        DiagnosticsScreen.kt     # экран диагностики (порты/MCP/модели)
        theme/Theme.kt
    assets/                       # ТОЛЬКО шрифты + MCP-память (моделей нет)
    jniLibs/arm64-v8a/           # prebuilt opencode-бинарь + musl-libs (в git)
whisperlib/                      # JNI-обёртки: ncnn (рабочий) + whisper.cpp (legacy)
  src/main/jni/
    whisper/                     # whisper.cpp-gw (использует ggml; use_gpu=false)
    whisper/CMakeLists.txt       # переменные внешних путей: WHISPER_LIB_DIR, NCNN_DIR
    ncnn/                        # ncnn-whisper (int8 encoder, KV-cache) — основной путь
docs/
  ROADMAP.md                     # три трека к релизу + статусы
  EXPERIMENTS-STT-LATENCY.md     # замеры ncnn: int8/Vulkan/KV, ЭКСП-5 (чанкинг)
  stt-bench-2026-09-24.csv       # бенч int8 vs fp32 (24.09.2026)
tools/
  ncnn-whisper-plan.md           # план интеграции ncnn-whisper
  ncnn-int8-plan.md              # int8-квантование (внедрено) + тулзы квантования
  ncnn-int8/                     # quantize_block.py, compare_encoder.cpp (хост-бенч)
build.ps1                        # сборка на Windows: debug/release (Android Studio JDK)
```

---

## 🚀 Оптимизации и история изменений

Актуальные работы по производительности UI/рендера (подробно — [CHANGELOG.md](./CHANGELOG.md)):

| # | Что сделали | Результат |
|---|-------------|-----------|
| 1 | Инкрементный парсинг ленты + адаптивный поллинг (400/900 мс) | Парсинг ушёл (58 cached / 0 parsed) |
| 2 | Приостановка фонового WebView (SPA) | Frame-спайки: 99-перц. 21→9 мс, janky 6.87→0.33% |
| 3 | Убраны вечные анимации индикаторов | UI CPU 33→2%, RenderThread 10→0% |
| 4 | Пауза поллинга и анимаций, когда Activity в фоне | Фоновый CPU ~0% |
| 5 | Эффективное мигание MCP (дискретный пульс вместо 60fps) | Мигание возвращено, CPU ~8% (фон 0%) |
| 6 | Все UI-кнопки на готовых Material-иконках (extended) | Микрофон, отправка, stop, шестерёнка, шрифт, цвет — векторные, читаемые |
| 7 | R8-минификация + shrinkResources в release | APK 374 → 274 MB (R8), затем lazy-вынос base → **147 MB** (debug подпись для локального smoke; пакет чистый `org.opencode.mobile`) |
| 8 | base-модель вынесена из assets в lazy-скачивание | base/turbo качаются по требованию; APK больше не тащит 141MB whisper |

**Итог:** UI CPU ~38% → ~1.5-8% (×5–25), рендер 99-перц. 9 мс, janky 0.33%,
фон ~0%. Единственный хвост — внутренний `opencode serve` (~35%, код движка, не наш).

---

## 🗺 Дорожная карта

План по доведению клиента до «продукта для чужих телефонов» (release-контур,
сеть без ручного прокси, STT turbo на CPU) — см. [docs/ROADMAP.md](./docs/ROADMAP.md).

---

## Тонкости / известные ограничения (MVP)

- **DNS / исходящая сеть — решено встроенным прокси**: бинарь opencode (musl/bun)
  не резолвит IPv6->IPv4 fallback и не читает `/etc/resolv.conf` на Android.
  В приложении поднимается встроенный `Ipv4Proxy` (CONNECT-туннель на
  `127.0.0.1:3128`), который резолвит строго по IPv4 через системный стек Netd;
  `HTTPS_PROXY` ставится автоматически при запуске `serve`. Внешние сервисы
  (`mcp.context7.com`, `mcp.grep.app`, `registry.npmjs.org`, модели) работают
  без ручного прокси-конфига. См. `Ipv4Proxy.kt`.
- **Сабпроцессы opencode** (`rg`, `git`, языковые серверы) на устройстве
  отсутствуют — часть фич (`/find`, git-интеграция) не работает.
- **ForegroundServiceType `specialUse`**: сервера и STT используют `specialUse`
  (+ `PROPERTY_SPECIAL_USE_FGS_SUBTYPE`). Для side-loaded MVP допустимо; для
  публикации в Play требуется прохождение ревью этого типа.
- **SELinux**: маппинг либ из `filesDir` (`mmap PROT_EXEC`) может быть ограничен
  политикой `untrusted_app` — проверено на реальном OPPO, на других устройствах
  стоит делать smoke-тест.
- **Performance STT**: полное распознавание turbo ncnn ~10 s (encoder int8 8.82 s).
  Внедрены foreground-сервис + int8-encoder + **VAD-чанкинг** (первые слова 1–2 s);
  бенч на устройстве: int8 ~2.1× fp32 (см. `docs/EXPERIMENTS-STT-LATENCY.md`,
  `docs/stt-bench-2026-09-24.csv`). Осталось: формальный WER-бенч.
- **Vulkan (GPU)**: экспериментально (int8-Vulkan 6.45 s, стабилен), но в проде
  выключен — включение = `encoder.opt.use_vulkan_compute=true` в `ncnn_jni.cpp`.

---

## Лицензия и статус

Экспериментальный личный проект. API и внутренности могут меняться. Не является
официальным продуктом opencode.
