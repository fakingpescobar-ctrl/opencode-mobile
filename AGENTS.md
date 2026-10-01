# opencode-mobile — правила для ассистента

## Как говорить с агентом на телефоне (НЕ через UI)

Агент на телефоне — это `opencode serve` на порту **4096**. К нему есть прямой REST.
**UI-автоматизация запрещена.** Ни `adb shell input text`, ни `uiautomator dump`, ни тапы по координатам.

Полный рабочий пример (проверен 30.09.2026, все ответы HTTP 200):

### 1. Форвард (пересоздавать каждый раз — он отваливается сам)

```powershell
$adb="C:\Users\OLD\AppData\Local\Android\Sdk\platform-tools\adb.exe"
& $adb devices                                            # 3B15AX00M9E00000  (OnePlus CPH2747)
& $adb shell pidof org.opencode.mobile.debug              # приложение живо?
& $adb forward tcp:4098 tcp:4096                         # форвард
```

### 2. Пароль сервера

Пароль **не** лежит в prefs — там AES-256/GCM + AndroidKeyStore, снаружи не прочитать.
Он передаётся в `OPENCODE_SERVER_PASSWORD` env дочернего процесса serve, а UID у него
тот же, что у приложения — поэтому читается через `run-as`:

```powershell
# найти pid (скрипт файлом: adb shell не любит сложные однострочники с циклом)
'for p in /proc/[0-9]*; do if grep -qa OPENCODE_SERVER_PASSWORD $p/environ 2>/dev/null; then echo FOUND $p; fi; done'
  -> sh /data/local/tmp/find.sh   =>  FOUND /proc/15442

# прочитать
'tr "\0" "\n" < /proc/15442/environ | grep OPENCODE_SERVER_PASSWORD'
  -> OPENCODE_SERVER_PASSWORD=<значение в репозиторий НЕ пишем, читать только так>
```

Basic auth = `base64("opencode:" + пароль)`.

> Пароль генерируется `UUID.randomUUID()` при первом запуске и хранится зашифрованным.
> В debug-сборках может быть фиксированным — **не зашивать это в скрипты намертво**,
> каждый раз читать из `/proc`. Готовая функция — `tools/adb-auth-common.ps1`
> → `Get-OpencodeServePassword` (+ `Get-OpencodeAuthHeader`, уже с готовым Basic).

### 3. Запросы

```powershell
$h = @{ Authorization = "Basic " + [Convert]::ToBase64String([Text.Encoding]::ASCII.GetBytes("opencode:$pwd")) }
$h["Content-Type"] = "application/json"

Invoke-RestMethod "http://127.0.0.1:4098/global/health" -Headers $h          # {"healthy":true,...}
$s = Invoke-RestMethod "http://127.0.0.1:4098/session" -Headers $h             # id, slug, time.updated
$body = @{ parts = @(@{ type="text"; text="текст" }) } | ConvertTo-Json -Depth 6
Invoke-RestMethod -Method POST "http://127.0.0.1:4098/session/$($s[0].id)/message" `
    -Headers $h -Body ([Text.Encoding]::UTF8.GetBytes($body)) -TimeoutSec 240
```

**POST долгий** — opencode отвечает только после завершения генерации. Таймаут 240с,
обрывать нельзя.

### Почему не UI (уроки 30.09.2026)

| проблема | как через REST |
|---|---|
| `adb shell input text` не умеет кириллицу | любой UTF-8 |
| ответ агента выковыривался из XML-дампа, кириллица ломалась в консоли | чистый JSON |
| Яндекс.Музыка посреди теста перехватывала `topResumedActivity` | ничего не перехватывает |
| координаты кнопок прыгают с клавиатурой | 0 тапов |

### Главная ошибка

`401` от `tools\mobile-bridge\bridge.mjs` — это **другой** сервер (бридж установки APK),
а НЕ `opencode serve`. Принять его за «агент недоступен напрямую» и уйти в UI — неверный вывод.
**Сначала прямой канал, потом UI.**

---

## У opencode-mobile НЕТ своего плеера

Проверено grep (30.09.2026): в `app/src/main/java` **ноль** вхождений `MediaPlayer`,
`AudioTrack`, `ExoPlayer`, `AudioFocus`. Приложение **не производит звук**.

```
агент (opencode) ──► ynison.js (мост, только команды) ──► Яндекс.Музыка ru.yandex.music
```

Единственный источник звука — приложение Яндекс.Музыки на телефоне. `ynison.js` — пульт.

`SELF_ID` (`AWcmWI5vAH`, title "OpenCode") объявляет `can_be_player: true` — это требование
протокола для claim в handoff, а не «я умею играть». Убрать нельзя → будет `400090001`.

**Не говорить «наш плеер», «конфликт плееров», «два плеера» — плеер ровно один.**
Если Яндекс.Музыка играет что-то своё, это не конфликт с нами, это просто текущий трек.

---

## Логи и отладка

Живых логов **два**, оба пишутся, не путать:

| путь | что внутри | размер/свежесть (30.09.2026) |
|---|---|---|
| `/data/data/org.opencode.mobile.debug/files/opencode.log` | лог **Android-рантайма**: Ynison-трейсы `[ynison]`, MCP, serve-запуск | ~35 КБ |
| `/storage/emulated/0/Documents/OpencodeTerminal/opencode/opencode-data/opencode/log/opencode.log` | лог **самого opencode serve**: его сессии, тул-вызовы, ответы модели | ~1.2 МБ, свежее |

- Ynison-трейсы: `run-as org.opencode.mobile.debug cat /data/data/org.opencode.mobile.debug/files/opencode.log | grep ynison`
- Если внутренний вдруг не пишет — брать внешний из Documents, он свежее.
- **Логи НЕ обрезаются между прогонами.** При разборе искать последний `phone already online` /
  `announced server state back`, иначе видишь ошибки ПРОШЛЫХ сборок и делаешь неверный вывод.
  Именно так я чуть не объявил «seed не помог», увидев 400030001 от старой сборки.
- Конфиг Ynison на устройстве: `/data/data/org.opencode.mobile.debug/files/mem/ynison.js`
  (это копия из assets — после установки APK старая может остаться, проверять маркеры через grep).

## Ynison: измеренные ограничения протокола

Не догадки, а замеры на живом сервере:

1. **400030002** `Unspecified repeat mode is not allowed.` — рвёт сокет, если в handshake нет
   валидного `player_queue.options.repeat_mode`. Телефон публикует `UNSPECIFIED`, что ОТ телефона
   нормально и ОТ нас фатально. Лечится ровно одним enum → `NONE`.
2. **400030001** `Empty playable list is restricted` — пустой `playable_list` отвергается
   и в handshake, и в `update_player_state`. Не edge case, а блокировка.
3. **400030001** `Player is not paused, but queue is probably empty (currentIndex=-1, size=0)` —
   `paused:false` + пустой список.
4. Claim в handoff требует `can_be_player:true` — нельзя ставить `false`, даже чтобы «не мешать».
5. Телефон игнорирует `paused:false` при ненулевом `progress_ms`; работает только `"0"`.
6. `update_player_state` сервер периодически роняет даже байт-в-байт валидный — запись
   не гарантия, её надо подтверждать и ретраить (`handoffUntil`).

**Ловушка, из-за которой был тупик:** если очередь аккаунта пуста, НИ ОДИН тул не может открыть
сессию — даже read-only status. И `music_play` тоже не мог: он публиковал handshake ДО того,
как узнать, что собирается записать. Отсюда и «не работает вообще ничего».

Решение — **seed**: состояние, которое вызов и так собирается записать, передаётся в
`openSession` → `openSessionOnce` → `handoff` → `handoffUntil` → `ensurePhone` и публикуется
в handshake. Приоритет выбора: `live || cache || seed || neutralState()`; если выбранное оказалось
пустым — откат на seed.

## Тесты

```powershell
node app\src\test\js\ynison-announce.test.mjs      # 22 теста: announce/seed/cache/repeat
pwsh -File .\build.ps1 -Task jstest                 # весь JS-гейт
pwsh -File .\build.ps1 -Task debug                  # сборка APK
adb install -r -d app\build\outputs\apk\debug\app-debug.apk
```

- `ynison-announce.test.mjs` вырезает блок кода по якорям вида `function neutralState() {`
  и склеивает через `new Function`. **Смена сигнатуры = сломанный якорь.**
- `ynison-goaway.test.mjs` якорится на `async function openSessionOnce(seed = null)` —
  добавление параметра ломает `jstest` с «якорь не найден».

## Параметры экрана для правок UI ( ОБЯЗАТЕЛЬНО считать ДО вёрстки)

Целевая платформа: **OPPO CPH2747**, Android 16.

| Параметр | Значение |
|---|---|
| Разрешение | 1272 × 2772 px |
| Density | 560 dpi = **3.5 px/dp** |
| Ширина экрана | **363 dp** (не 411! узкий телефон) |
| Высота экрана | 792 dp |
| font_scale | 1.0 |

**Главное правило: в Compose-шапке ширины в dp почти нет.**

- Корневой `Column` в `ChatOverlay` имеет `padding(horizontal = 12.dp)` → на ряд остаётся **339 dp**.
- Ряд иконок в шапке: 6 иконок по 22 dp + 7 зазоров по 4 dp = 160 dp, чип MCP (~75 dp)
  и `QuotaBadge` в `weight(1f)`. Бейджу остаётся ~100 dp.
- **Любой Text в шапке обязан иметь `softWrap = false, maxLines = 1, overflow = TextOverflow.Ellipsis`.**
  Иначе при нехватке ширины Compose переносит текст на вторую строку и шапка
  вздваивается по высоте (реально было: `● Online 69m` / `s` — 02.10.2026).
- Добавление ЛЮБОЙ иконки в шапку: сначала посчитай dp, потом пиши. Иначе та же поломка.
- Зазоры в шапке — `padding(start = 4.dp)`; в строке ввода ниже остались `8.dp`
  (там другое место, не путать — это ~строки 1851+ в `ChatOverlay.kt`).

Проверка вёрстки БЕЗ скриншота (vision-mcp в этом окружении может быть недоступен):

```powershell
$adb="C:\Users\OLD\AppData\Local\Android\Sdk\platform-tools\adb.exe"
& $adb shell uiautomator dump /sdcard/ui.xml
& $adb pull /sdcard/ui.xml "$env:TEMP\ui.xml"
# дальше распарсить bounds: у элементов ОДНОЙ строки одинаковый y и h
```

Ориентиры по координатам шапки (px, 3.5 px/dp): `Online` x=91, пинг x=240,
чип MCP x=535, иконки с шагом 91 px начиная с x=653.

## DeepSeek critic

`node C:\Users\OLD\.config\opencode\plugin\critic.mjs "<промпт>" --file <file> --quiet`
Критик — голый чат без инструментов: **весь код слать в промпте**, файлы читать не сможет.
Прокси: `C:\Projects\deepseek-chat-proxy\start.bat`, `http://127.0.0.1:8085/v1`.
Exit code: 0=APPROVE, 1=REJECT, 2=нет ответа/ошибка.

Если прокси отдаёт **401** — протухли куки браузера, нужен `import_cookies.py` с ручным
логином пользователя. Это инфраструктура, а не код — ревью делай сам и говори об этом честно.