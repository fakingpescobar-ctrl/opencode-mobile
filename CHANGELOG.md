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
- Модели: `ggml-base.bin` (141 МБ, в APK), `large-v3-turbo` (574 МБ, опц.).
- Ассеты шрифтов: `JetBrainsMono-Regular.ttf` (моно для ответов модели).

**Сборка / инфраструктура**
- Gradle: `gradlew.bat :app:packageDebug --offline -x lint`
- `JAVA_HOME = C:\Program Files\Android\Android Studio\jbr`
- adb: `C:\Users\OLD\AppData\Local\Android\Sdk\platform-tools\adb.exe`
- Профилирование: `dumpsys gfxinfo`, `top -H`, `/proc/<pid>/stat`.
  `simpleperf` **не работает** на OPPO/OnePlus — кернел блокирует
  `cpu-cycles/instructions` (perf locked, нужен root).

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