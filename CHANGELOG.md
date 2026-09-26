# Changelog — OpenCode Mobile

Хронология работ, ошибки и их решения. Документ отражает текущее состояние
ветки `main` (июнь 2026 — сентябрь 2026).

---

## Стек и зависимости

**Язык / UI**
- Kotlin + Jetpack Compose (BOM 2026.01.00)
- Material3, activity-compose 1.11.0, lifecycle-runtime-ktx 2.9.4
- WebView (встроенный SPA opencode — фоновый, рендер приостановлен)

**Движок**
- `opencode serve` (bun-рантайм, бинарник `libopencode.so`) — запускается
  локально на устройстве без Termux/root через загрузчик `ld-musl` (`ln.so`).
- Дополнительный процесс `libbun-musl.so memory.js` — локальная память MCP
  как HTTP/TCP-сервер.
- Порт сервера: `4096` (см. `OpencodeApp.ServerConfig.PORT`).

**Инференс на устройстве (STT)**
- `whisper.cpp` + `ncnn` (CPU/NEON fp16 + KV-cache).
- Модели: `base` и `large-v3-turbo` — lazy-скачивание по требованию (в APK моделей нет).
- Отдельный NCNN-набор turbo: **15 файлов, ~2.33 ГБ** (`whisper_turbo_*.ncnn.bin/param`),
  качается с GitHub Releases по требованию, `MIN_FREE_BYTES = 3 ГБ` на старте.
- Ассеты шрифтов: `JetBrainsMono-Regular.ttf` (моно для ответов модели).

**Размер debug APK (287.8 МБ) — это НЕ модели**

Модельных файлов (`.bin` / `.param` / ggml) в APK нет вообще. Весь объём — native-библиотеки:

| Файл | Размер |
|------|--------|
| `lib/arm64-v8a/libopencode.so` (движок) | 184.7 МБ |
| `lib/arm64-v8a/libbun-musl.so` (рантайм) | 70.2 МБ |
| `lib/arm64-v8a/libggml-vulkan.so` | 68.5 МБ |
| `lib/arm64-v8a/libncnnwhisper.so` | 47.2 МБ |
| `lib/arm64-v8a/libglslang.so` | 32.3 МБ |
| `classes.dex` + 9.9k dex | ~65 МБ |

Отсюда практический вывод: `connectedAndroidTest` на debug **не запускаем** — на установку
и прогон уходит столько, что быстрее проверить на живой установленной копии.

**Release-подпись — известный долг**
`app/build.gradle.kts`: `release` собирается с `isMinifyEnabled`/`isShrinkResources`, но
подписывается `signingConfigs.getByName("debug")` — своего release-keystore в проекте нет.
Сборка и smoke работают, но публиковать так нельзя: сменив ключ, обновить уже установленное
приложение невозможно.

**Сборка / инфраструктура**
- Gradle: `gradlew.bat :app:packageDebug --offline -x lint`
- `JAVA_HOME = C:\Program Files\Android\Android Studio\jbr`
- adb: `C:\Users\OLD\AppData\Local\Android\Sdk\platform-tools\adb.exe`
- Профилирование: `dumpsys gfxinfo`, `top -H`, `/proc/<pid>/stat`.
  `simpleperf` **не работает** на OPPO/OnePlus — кернел блокирует
  `cpu-cycles/instructions` (perf locked, нужен root).

---

## Сентябрь 2026 — аккаунт Яндекс Музыки: логин и плейлист

### Вход по OAuth (PKCE) + «Моё»
- `mobile_yandex_login` — PKCE без client secret, код живёт 10 минут
  (`CODE_LIFETIME_MILLIS`), refresh хранится в зашифрованных prefs.
- `mobile_yandex_profile`, `mobile_yandex_liked` — профиль и библиотека «Мой плейлист».
- Токены переживают перезапуск; при `401` refresh обновляется, повтор — один раз.
- Commit `04322b0`, CI `36203590417` — success.

### Плейлист целиком (`479178b`)
- Три инструмента: `mobile_yandex_playlists`, `mobile_yandex_playlist`,
  `mobile_yandex_play_playlist` + три маршрута моста
  (`GET /v1/account/yandex/playlists`, `GET .../playlist`, `POST .../playlist/play`).
- `PLAYLIST_HEAD = 5` — запускаем **первый** трек, а не жмём «Слушать» у плейлиста:
  кнопка продолжает сохранённую очередь (для `D.N.B` — с трека #10).
- Инвариант `started=true`: в MediaSession играет один из первых пяти API-треков.
- `YandexPlaylistLaunchActivity` — прозрачный relay для deep link: у моста нет видимого
  окна, и Android иначе создаёт цель, но не выводит её задачу наверх.
- UI-матчинг, доведённый на живом устройстве:
  - `TopmostMatch` вместо `preferLargest` — строка списка (1272×266) и мини-плеер
    (1272×277) содержат один трек, и мини-плеер всегда шире, то есть «самый большой»
    узел — не тот, который нужно тапать;
  - `MatchSearch` — точный clickable-узел приоритетнее кликабельного предка;
  - `tappableAncestor` с `ANCESTOR_HOPS = 24` (строка трека требует ~14 подъёмов);
  - `MediaUiNodeMatcher` больше не отбрасывает label с `clickable = false`.
- Live E2E (kind=1001): `ok=true`, `started=true`, `now_playing="YOUR LOVE" / Bullet Tooth`,
  позиция растёт, остаётся `PlaylistScreenActivity`. CI `36228758621` — success.

### Три правки про честность состояния (`a984dba`)

| Что | Симптом | Причина |
|-----|---------|---------|
| `offRpcPool` | «ui job did not answer inside its budget» после ~60 с ожидания, реальная ошибка терялась | Исключение бросалось в поток, `future` не завершался никогда; executor — `newSingleThreadExecutor`, поэтому один сбой намертво вешал весь ui-click до перезапуска |
| `readPlaylistLibrary` | Плейлист стартовал с середины, потом сам обвинялся в «ранние треки недоступны» | Порядок оставался delivery-порядком, хотя head брался первыми пятью `trackIds`, а позиция подписывалась из `originalIndexes` |
| `failure()` | `now_playing` всегда `null` — агент думал, что музыка не играет вообще | Наблюдённая сессия не доходила до payload |

Попутно: тест `the playlist order comes from original index` **врал своим именем** —
проверял `[2, 0, 1]`, то есть delivery-порядок.

### Ошибки и их решения — сентябрь

**7. Deep link «открыл приложение, но плейлист не открылся»**
**Симптом:** `yandexmusic://` доставлен, `ok=true`, а `PlaylistScreenActivity` так и не появилась.
**Причина:** у моста нет видимого окна; Android создаёт целевую Activity, но не выводит её
задачу наверх — ссылка выглядит доставленной, а эффекта нет.
**Решение:** прозрачная `YandexPlaylistLaunchActivity` с `FLAG_ACTIVITY_NEW_TASK` как relay.
**Файлы:** `YandexPlaylistLaunchActivity.kt`, `AndroidManifest.xml`, `themes.xml`.

**8. Тап уходил в мини-плеер, а не в строку списка**
**Симптом:** `NotFound` при верном плейлисте на экране.
**Причина:** строка трека и мини-плеер содержат один и тот же заголовок; `preferLargest`
выбирал мини-плеер (1272×277 > 1272×266).
**Решение:** `TopmostMatch` + `MediaUiTarget.preferTopmost`.

---

## Сентябрь 2026 — релизный контур: Basic Auth, память MCP, STT-чанкинг

### Basic Auth (PR1)
- `serve` защищён паролем: генерация при первом запуске, хранение в
  зашифрованных prefs, заголовок `Authorization` на каждом запросе
  (`ServerAuth.kt`). Без пароля — `401 Unauthorized`.
- Все HTTP-точки чата переведены на единый клиент
  `LocalOpenCodeClient.kt` (get/post/postAsync/delete + централизованный auth) —
  из `ChatOverlay.kt` вынесено ~90 строк дублирующего `HttpURLConnection`
  (в т.ч. инлайн GET/DELETE сессий и локальный `get()`).

### Локальная память MCP (PR2)
- `memory.js` (бандл opencode) запускается вторым процессом как streamable
  HTTP-сервер на `127.0.0.1:4199/mcp`; хранилище — `$XDG_CONFIG_HOME/opencode/memory/`.
- Индикатор **«N MCP»** в шапке чата = `GET /mcp` с serve.
- **Баг «0 MCP»**: причиной был пустой `opencode.jsonc` на устройстве —
  serve не знал о памяти (память слушала порт, инструменты модели недоступны).
  Фикс — `OpencodeRuntime.ensureMcpConfig()`: идемпотентная дозапись секции
  `mcp.memory` (remote, 4199) до старта serve, атомарно (tmp+rename);
  работает и на чистой установке. Подтверждено: соединение serve→memory
  (ESTABLISHED на 0x1067=4199).

### RuntimeManager / диагностика (PR4)
- `RuntimeManager` — явная стейт-машина: память → serve → HEALTHY с backoff.
- `RuntimeValidation` — экран диагностики: порты, наличие моделей,
  **протокольная** проверка памяти `memoryHttpOk()` — `GET /mcp` → 2xx
  (раньше был только TCP-коннект, мог матчиться чужой сокет).
- HEALTHY-фикс: стадия переводится в HEALTHY по фактическому успешному
  старту serve.

### STT: VAD-сегментация + чанкинг (ЭКСП-5)
- `SpeechSegmenter` (VAD по RMS) режет длинную речь на сегменты,
  `ChunkedTranscriber` распознаёт каждый отдельно (первые слова видны на
  1–2 с, хвост >30 с не теряется, меньше галлюцинаций на тишине).
- Бенч на устройстве (24.09.2026): int8 6.8–8.2 s vs fp32 14.5–15.2 s
  (**~2.1×**), CSV в `docs/stt-bench-2026-09-24.csv`.

### Прочее
- e2e чистая установка (uninstall → install): сервер Running, новый пароль
  (401 без auth), чат работает, память MCP отвечает 2xx, модели раскатаны.
  Сессии/история на внешнем хранилище переживают переустановку, auth и
  ncnn-модели — сбрасываются.
- RM-отладочные логи вычищены из `RuntimeManager`/`OpencodeServerService`.

---

## Оптимизации производительности (этапы 1–5)

| # | Что | Результат | Файл |
|---|-----|-----------|------|
| 1 | Инкрементный парсинг ленты + адаптивный поллинг 400/900 мс | Парсинг ушёл (58 cached / 0 parsed) | `ChatOverlay.kt` |
| 2 | Приостановка фонового WebView (SPA) | Frame-спайки: 99-перц. 21→9 мс, janky 6.87→0.33% | `MainActivity.kt` |
| 3 | Убраны вечные анимации индикаторов | UI CPU 33→2%, RenderThread 10→0% | `ChatOverlay.kt` |
| 4 | Пауза поллинга и анимаций, когда Activity в фоне | Фоновый CPU ~0% | `ChatOverlay.kt` |
| 5 | Эффективное мигание MCP (дискретный пульс) | Мигание возвращено, CPU ~8% (фон 0%) | `ChatOverlay.kt` |

### Итоговые метрики

- UI CPU (foreground, idle): **~8%** при включённом мигании MCP; **~1.5%** без MCP.
- Фон (свёрнуто): **~0%** (поллинг и анимации паузятся по lifecycle).
- Рендер: медиана кадра ~5–7 мс, 99-перц. **9 мс**, janky **0.33%**.
- Внутренний `opencode serve` ест ~35% CPU — это **внутренний код движка**
  (bun), не наш код; на рендер/отклик не влияет, НЕ трогаем.

---

## Ошибки и их решения

### 1. Двойной звук при ответе модели
**Симптом:** при ответе модели играли ДВА звука — «непонятный» и системный тон.
**Диагноз:** первый звук — встроенный `sound-notify` SPA opencode в WebView
через Web Audio (AAudio, `USAGE_MEDIA`, piid:119); второй — наш
`playNotificationSound` (`USAGE_NOTIFICATION`, piid:135).
**Решение:** `mediaPlaybackRequiresUserGesture = true` (блок автоплея WebView)
+ JS-инъекция «мут» в `onPageFinished` (нулевой gain + `audio/video muted`).
Плюс канал сервера сделан тихим (`setSound(null)`, `setSilent(true)`).
**Файлы:** `MainActivity.kt`, `OpencodeServerService.kt`.

### 2. Звук/вибрация раньше текста
**Симптом:** вибрация опережала отрисовку ответа на 1.5–2 с.
**Причина:** `delay(90)` не гарантировал фактическую отрисовку `LazyColumn`.
**Решение:** вибрация привязана к **фактической видимости** ответа —
`snapshotFlow { listState.layoutInfo.visibleItemsInfo.any { it.index == idx } }`
блокируется, пока элемент не станет реально видимым.
Элемент: `LaunchedEffect(lastResp)` в `ChatOverlay.kt`.

### 3. Frame-спайки при поллинге (99-перц. 21 мс, janky 6.87%)
**Симптом:** частые подтормаживания рендера кадров, CPU UI 38%.
**Причина:** фоновый WebView/SPA бесконечно перерисовывал интерфейс под
нативным чатом (не парсинг — он на `Dispatchers.IO`).
**Решение:** `WebView.onPause() + pauseTimers() + visibility=INVISIBLE`, когда
чат активен. Результат: 99-перц. 9 мс, janky 0.33%.
**Файл:** `MainActivity.kt` (`OpencodeWebView(paused)`).

### 4. Постоянный CPU ~33% от мигающей точки MCP
**Симптом:** UI CPU 30–41% в простое, hot thread — `RenderThread` (9.5%).
**Причина:** `rememberInfiniteTransition` тикает **каждый кадр 60fps** даже с
медленным tween — RenderThread постоянно занят перерисовкой индикатора.
**Решение (этап 3):** убрали вечные анимации.
**Решение (этап 5, возврат мигания):** дискретный пульс через
`mutableFloatStateOf` + `LaunchedEffect` с `delay(120)` (~8 тиков/с по синусу),
только когда `total > 0`, + пауза в фоне. Плавно на глаз, CPU ~8%.
**Файл:** `ChatOverlay.kt` (`MCPIndicator`).

### 5. Краш LazyColumn «Key … was already used» при дубликатах
**Симптом:** при дублирующихся сообщениях (напр. дважды команда «Стой»)
кастомный `key` из контента ронял `LazyColumn`.
**Решение:** позиционный ключ (без кастомного — `items(msgs)`). Лента
append-only, добавление в конец не трогает существующие позиции — безопасно.
**Файл:** `ChatOverlay.kt`.

### 6. Недоступность simpleperf на устройстве
**Симптом:** `simpleperf` не работает (`cpu-cycles`/`instructions` не
поддерживаются).
**Причина:** OPPO/OnePlus заблокировали perf_event на locked-кернеле.
**Решение:** диагностика только через наблюдательные средства: `top -H`
(потоки + TIME+), `dumpsys gfxinfo`, delta `utime+stime` из `/proc/<pid>/stat`,
context-switches из `/proc/<pid>/status`. Это хватило для локализации всех трюков.

---

## Справка по замерам

**gfxinfo** (строгий протокол):
```
adb shell am force-stop org.opencode.mobile.debug
adb shell monkey -p org.opencode.mobile.debug -c android.intent.category.LAUNCHER 1
adb shell dumpsys gfxinfo org.opencode.mobile.debug reset
# подождать 25 с
adb shell dumpsys gfxinfo org.opencode.mobile.debug
```
`gfxinfo` накапливает статистику — **всегда сбрасывать перед срезом**.

**CPU процесса** (среднее за интервал, надёжнее `top`):
```
# delta utime+stime из /proc/<pid>/stat за N секунд
```