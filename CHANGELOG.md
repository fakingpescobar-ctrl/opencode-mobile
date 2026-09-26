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

**Release-подпись — долг закрыт**
Раньше `release` собирался с `signingConfigs.getByName("debug")`, и своего keystore в проекте
не было: публиковать было нельзя. Теперь `app/build.gradle.kts` читает `keystore.properties`
из корня и при наличии ключа подписывает release им. Ключ (RSA 4096, PKCS12, alias
`opencode-release`, 10000 дней) лежит **вне репозитория**; `keystore.properties`, `*.jks` и
`*.keystore` — в `.gitignore`, в репозиторий попадает только `keystore.properties.example`.
Проверено `apksigner verify --print-certs`: release-APK подписан новым ключом
(SHA-256 `4fda00b2…5e56`), а не debug.

Если `keystore.properties` нет — например, на CI — сборка не падает, а продолжает
использовать debug-подпись и печатает предупреждение `release-key НЕ НАЙДЕН …
публиковать её НЕЛЬЗЯ`. Такой APK совместим только с ранее установленным debug-вариантом.

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

### 7. Долгая речь резалась на 12 сегментов и уходила в ~120 с
**Симптом:** запись `long.wav` (37 с) распознаётся непропорционально долго.
**Причина:** `SpeechSegmenter` держал `PAD_MS=450` одновременно за порогом
разрыва и padding'ом. Сегмент в 0.61 с тишины не считался разрывом, поэтому
37 с резались на 12 кусков. ncnn-encoder платит фиксированные ~6.5 с на **любой**
вход — 12 сегментов означали 12 encoder'ов, то есть ~120 с вместо ~16 с.
**Решение:** порог разрыва вынесен в `splitGapMs` (900 мс), `PAD_MS`/`padFrames`
удалены. Регрессионный тест на синтетике пауз 0.61 с даёт ровно 2 сегмента
(на старом пороге — 12).
**Файлы:** `SpeechSegmenter.kt`, `SpeechSegmenterTest.kt`.

### 8. `finalize()` выгружал общую модель из-под живого контекста
**Симптом:** lazy-инициализация ncnn падала — модель выгружалась, пока ею
пользовались.
**Причина:** `NcnnWhisperContext.finalize()` звал `release()`, а GC вызывает
`finalize()` в произвольный момент. Контексты делят один глобальный `g_whisper`,
поэтому чужая сборка мусора выгружала модель у ещё работающего.
**Решение:** `finalize()` удалён, владение через счётчик ссылок в JNI —
`nativeInit` на той же модели инкрементит, `nativeFree` декрементит и
уничтожает на нуле.
**Долг:** общего mutex пока нет, и `nativeInit` с другой моделью всё ещё
сбрасывает старый `g_whisper` при `refcount > 0`.
**Файлы:** `NcnnWhisperContext.kt`, `ncnn_jni.cpp`.

### 9. Английская речь переводилась на русский
**Симптом:** латинский текст распознавался и возвращался русским.
**Причина:** `lang = "ru"` был зашит в ncnn-путь.
**Решение:** 99 языковых токенов, `transcribe_auto` и JNI
`nativeTranscribeAuto`. `encoder_states` считается **один раз**, язык читается
дополнительным префиллом `[sot]` (+~40 мс) вместо второго encoder'а. Язык
защёлкивается на сессию, граница сессии — `ChunkedTranscriber.transcribe()`.
Порог по уверенности не введён намеренно: референсный
`whisper_lang_auto_detect` тоже делает голый argmax, а неоткалиброванный порог
ломает определение языка сильнее, чем помогает. Вероятность пишется в лог.
**Файлы:** `ncnn_jni.cpp`, `NcnnWhisperContext.kt`, `WhisperTranscribeService.kt`.

### 10. Фраза повторялась трижды подряд
**Симптом:** на коротких клипах с микропаузами декодер выдаёт одну фразу 3×.
**Причина:** жадный декодер без какого-либо подавления повторов.
**Решение:** `apply_repetition_penalty()` перед `argmax` — токен, закрывающий уже
встречавшуюся тройку, получает `-INF`, выданный языковой токен мягко штрафуется
(делится на 1.15). Штраф делением, а не запретом: жёсткий бан выкашивал бы
легитимные повторы. Prompt не затрагивается (`start = ids.size()`).
**Файл:** `ncnn_jni.cpp`.

### 11. Тест рапортовал «3/3 passed» при двух фактических конфигах
**Симптом:** зелёный прогон бенча, хотя строки `fp32` в CSV не было вовсе.
**Причина:** отсутствие fp32-модели логалось `Log.w` и не влияло на исход теста.
Хуже того, каталог с обоими наборами дал бы ложь: ncnn пробует int8 первым и
молча взял бы int8 под меткой «fp32».
**Решение:** отсутствие модели — `assertTrue`. Добавлен
`NcnnModelValidator.EncoderVariant{INT8,FP32,NONE}`, и тест проверяет, что в
каталоге fp32 нет int8-файлов.
**Файлы:** `BenchSttTest.kt`, `NcnnModelValidator.kt`.

### 12. Линтер падал на коде, который не меняли
**Симптом:** ~25 ошибок ktlint, включая строки в неизменённых функциях.
**Причина (два разных механизма):**
- `ktlint-baseline.xml` привязан к **номерам строк**. Любая вставка кода выше
  базлайновой строки сдвигает нумерацию, и давно забазлайненные нарушения
  «воскресают».
- `detekt-baseline.xml` привязан к **тексту сниппета**. `ktlintFormat`
  переформатировал файл целиком (trailing commas, expression bodies), сигнатуры
  перестали совпадать — detekt начал ругаться на `ReturnCount`, `UseCheckOrError`,
  `MaxLineLength` в давно базлайненном коде.
**Решение:** `ktlintFormat` откачен (он задел ещё 5 файлов, которых правка не
касалась), правки внесены руками в стиле файла, перегенерирован **только**
ktlint-baseline (`ktlintGenerateBaseline`): записи 100 → 63, часть была
устаревшей.
**Правило на будущее:** не запускать `ktlintFormat` на этом проекте без нужды;
после правки файла, который есть в ktlint-baseline, проверять diff baseline —
в нём должны быть только сдвиги номеров строк, никаких новых правил.
**Файлы:** `app/config/ktlint/ktlint-baseline.xml`.


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