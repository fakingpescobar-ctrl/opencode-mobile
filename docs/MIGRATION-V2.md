# Миграция на OpenCode 2 (план по этапам)

Дата ревизии: **12.10.2026**. Целевая версия сервера: **opencode 2.0.27**.

Ключевой факт, определяющий всю сложность: приложение **не подключается к
установленному opencode** — бинаррь сервера лежит внутри APK
(`app/src/main/jniLibs/arm64-v8a/libopencode.so`, 195.5 MB) и запускается через
`nativeLibraryDir` + `libldmusl.so`. Значит, апгрейд = замена бинаря + перенос
HTTP-слоя.

---

## Сверка версий (проверено 12.10.2026)

| Параметр | Сейчас (v1) | Цель (v2) |
|---|---|---|
| Версия | **1.18.25** (захардкожена) | **2.0.27** |
| Нативы в APK | `libopencode.so` + `libbun-musl.so`, 184.7 + 70.2 MB | `@opencode/cli-linux-arm64-musl@2.0.27` → `libopencode.so` 195.5 MB; `libbun-musl.so` 70.2 MB остаётся (JS-рантайм приложения, не opencode) |
| musl-рантайм | `libldmusl.so` (0.7 MB) | плюс Alpine `libstdc++.so.6` (2.63 MB) + `libgcc_s.so.1` (0.13 MB) — **в v2 бинарник не статический** |
| Источник бинаря | GitHub release tarball | **npm** (GitHub-релизов для v2 нет, `releases/tags/v2.0.27` → 404) |
| musl ARM64 | есть | есть ✅ |
| Префикс API | `/session*` | `/api/*` |
| Порт по умолчанию | 4096 | **4099** (замерено) |
| Поток событий | **не используется**, чистый поллинг | `/api/event` SSE доступен; `/api/experimental/session/{id}/wait` в 2.0.27 **отсутствует** (404) |
| Установка в v2 | `opencode-ai` | `@opencode/cli` |

Бинарь толстеет всего на ~11 MB, а взамен приходит 2.76 MB musl-рантайма GCC —
миграция технически возможна. `libbun-musl.so` (70.2 MB) при этом **остаётся**:
он не нужен opencode, но нужен приложению как JS-рантайм для `Retention.kt:179-180`
и harness-скриптов.

**Что v2 НЕ ломает:** конфиг (`~/.config/opencode/opencode.json`,
`<project>/opencode.json`, `.opencode/`), агенты, скиллы, команды, MCP. Формат v1
поддерживается нативно, конвертация не обязательна.

**Что v2 ломает (три breaking change по официальному гайду):**
1. Плагины — новый plugin API, v1-плагины не запускаются.
2. Server API и клиенты — новые контракты.
3. TUI-конфиг: слоёный `tui.json(c)` → один глобальный `cli.json`.

> **Ответ на вопрос про плагины (замерено 12.10.2026):** приложение плагинов
> не использует вообще — работает через MCP из конфига, и v2 их поднимает как есть.
> В десктопном `~/.config/opencode/opencode.json` есть `@tarquinen/opencode-dcp`,
> `opencode-worktree`, `opencode-quota`, `claude-smart`, `opencode-claude-auth` —
> но это десктопная машина, в APK-контур они не попадают.

---

## Карта эндпоинтов: v1 → v2

Приложение использует всего **8 путей** в 9 файлах. Полная таблица:

| v1 | v2 | Файлы- потребители |
|---|---|---|
| `GET /global/health` | `GET /api/info` | `RuntimeValidation.kt`, `MoltbookAlarmReceiver.kt:151` |
| `GET /session` | `GET /api/session` | `ChatOverlay.kt:946`, `:3853`, `:4396`, `MoltbookAlarmReceiver.kt:1189` |
| `POST /session` | `POST /api/session` | `ChatOverlay.kt:4243`, `MoltbookTicker.kt:1189` |
| `DELETE /session/{id}` | `DELETE /api/session/{id}` | `ChatOverlay.kt:4294` |
| `GET /session/{id}/message` | **путь тот же**, только `/api/` + новая форма ответа | `ChatOverlay.kt:4306`, `MoltbookTicker.kt:1215` |
| `POST /session/{id}/message` | `POST /api/session/{id}/prompt` | `ChatOverlay.kt:4441`, `MoltbookTicker.kt:1195` |
| `POST /session/{id}/abort` | `POST /api/session/{id}/interrupt` | `ChatOverlay.kt:4263` |
| `GET /session/status` | **удалён**, замена — `type:"idle"` в списке сообщений | `ChatNotice.kt` (целиком), `ChatOverlay.kt:3843/4277/4320/4366/4416`, `ZenQuota.kt` |

### Что показала разведка (замерено на живом v2.0.27, 12.10.2026)

**Ловушка, ломающая всю диагностику:** любой неизвестный GET-путь отдаёт **HTTP 200 + HTML SPA**
(catch-all). Проверено: `/global/health` → 200 + html, `/session/status` → 200 + html,
`/doc` → 200 + html. На POST неизвестный путь → **405**. Значит проверка «жив ли сервер»
по HTTP-коду в v2 не работает вообще — надо смотреть тело ответа.

| Путь | Ответ v2.0.27 (реально) |
|---|---|
| `GET /api/info` | `{version, pid, urls[], paths{tmp}, capabilities{persistentPty}}`. Поля `healthy` **нет** |
| `GET /api/session` | `{data:[…]}` — **обёртка `data`**, в v1 был голый массив |
| `POST /api/session` | тело `{}` достаточно. Ответ `{data:{id, projectID, cost, tokens, time, location}}` |
| `GET /api/session/{id}` | тот же объект сессии. **После завершения** появляются `outcome` и `time.idle` |
| `GET /api/session/{id}/message` | `{data:[…], cursor:{previous,next}}`. **Порядок — от новых к старым** |
| `POST /api/session/{id}/prompt` | тело `{text:"…"}`, **не** `{parts:[…]}`. Ответ `{data:{…,type:"user",payload:{text},delivery:"steer"}}` |
| `POST /api/session/{id}/interrupt` | `{interrupted:true\|false}` |
| `DELETE /api/session/{id}` | 204, тела нет |
| `GET /api/event` | SSE. Первое событие `{type:"server.connected"}`, дальше `: heartbeat` |
| `GET /api/mcp` | `{location, data:[{name, status{status:"connected"}}]}` |
| `GET /api/agent` | `{location, data:[…]}` |

Сообщения в v2 (объекты в `data`):

```jsonc
// пользовательское — text НА ВЕРХНЕМ УРОВНЕ, а не в parts[]
{ "id": "msg_…", "time": {"created": 1791…}, "text": "…", "type": "user" }

// ассистентское — content[] вместо parts[]
{ "id": "msg_…", "time": {"created":…, "streamed":…, "completed":…},
  "type": "assistant", "agent": "build", "model": {"id":…, "providerID":…},
  "content": [{"type":"text","text":"OK"}],
  "snapshot": {"start":…, "end":…, "files":[]},
  "finish": "stop", "rawFinish": "stop", "cost": 0,
  "tokens": {"input":…, "output":…, "reasoning":…, "cache":{"read":…,"write":…}} }

// НОВЫЙ псевдо-тип — замена /session/status
{ "id": "msg_…", "time": {"created":…}, "type": "idle", "outcome": "succeeded" }
```

Эндпоинты из официальной таблицы, **которых нет в 2.0.27** (проверено руками, отдают 404):

| Из документации v2 | Реальность 2.0.27 |
|---|---|
| `GET /api/session/{id}/view` | **404.** Сообщения читаются через `/api/session/{id}/message` |
| `GET /api/experimental/session/{id}/wait` | **404.** Замена status — `type:"idle"` в списке сообщений |
| `/api/session/{id}/summarize` → `/compact` | не проверялось |
| `GET /api/openapi`, `/api/openapi.json`, `/api/spec` | все **404**, спеки в рантайме нет |

Файлы с вызовами `LocalOpenCodeClient` (по убыванию):

| Файл | Вызовов |
|---|---|
| `ChatOverlay.kt` | 17 |
| `MoltbookTicker.kt` | 7 |
| `MoltbookWake.kt` | 6 |
| `MoltbookWatchdogReceiver.kt` | 3 |
| `ZenQuota.kt` | 3 |
| `MoltbookAlarmReceiver.kt` | 2 |
| `OpenCodeQuestion.kt` | 2 |
| `LocalOpenCodeClient.kt` | сам клиент |
| `OpenCodePermission.kt` | 1 |

**Удалённые в v2 без замены:** `/tui/*`, `/instance/dispose`, `/doc`,
`/global/event` (слит в `/api/event`).

**Не 404, а ловушка:** удалённые v1-пути под GET отдают 200 + HTML (catch-all SPA),
под POST — 405. Нельзя отличить «эндпоинта нет» от «эндпоинт есть» по коду ответа.

**Ошибки формата — полезны.** Валидация v2 отдаёт точный путь до проблемы:
`{"_tag":"InvalidRequestError","message":"Expected a string starting with \"ses\"\n  at [\"sessionID\"]","kind":"Params"}`
и `{"_tag":"InvalidRequestError","message":"Missing key\n  at [\"text\"]","kind":"Payload"}`.
Именно так был найден реальный формат `prompt` — по `Missing key at ["text"]`.

---

## Новые возможности v2, которые закрывают текущие костыли

| Эндпоинт | Что закрывает в текущем коде |
|---|---|
| `POST /api/session/{id}/background` | Сессия живёт при закрытом оверлее. Убирает блок `paused`/WebView (`MainActivity.kt:208-212`) |
| `POST /api/session/{id}/inbox` | Очередь сообщений — снимает блокировку «агент занят → нельзя отправить». Уже есть частично: `prompt` возвращает `delivery:"steer"` |
| `POST /api/session/{id}/form/{formID}/reply` | **Удаляет хак `OpenCodeQuestion.kt`** — там~200 строк парсинга текста вопроса агента, потому что v1 отдавал 404 на `/api/session/{id}/question/{id}/reply` |
| `GET/POST /api/session/{id}/permission` | Нативные permission-промпты вместо разбора текста в `OpenCodePermission.kt` |
| `GET /api/pair` + команда `opencode pair` | Раздача URL + логин/пароль — можно уйти от ручного adb port-forward |
| `GET /api/experimental/persistent-pty/{id}/handoff` | Тот самый handoff, который `ynison.js` моделирует руками (`handoffUntil`, `ensurePhone`) |
| `GET /api/session/{id}/context` | Инфо о контексте/токенах нормальным эндпоинтом |
| `/api/worktree` | `opencode-worktree` становится встроенным |

---

## Этап 0 — Разведка (0.5 дня)

**Статус: выполнен на ПК 12.10.2026.** Осталась только проверка бинаря на телефоне.

### Что сделано и измерено

- Скачаны `@opencode/cli-linux-arm64-musl@2.0.27` (91.2 MB) и
  `@opencode/cli-windows-x64@2.0.27` (95.2 MB), распакованы в
  `%TEMP%\opencode\ocv2\`.
- **v2 — один статический бинарник, а не тройка .so.** Внутри tarball ровно
  `package/bin/opencode`: 195.50 MB для musl-arm64 и `opencode.exe` 202.97 MB для
  windows-x64. **`libbun-musl.so` в v2 не существует.** Нынешняя схема
  `libopencode.so` + `libbun-musl.so` + `libldmusl.so` превращается максимум в два
  файла: `libopencode.so` + `libldmusl.so`.
- `opencode.exe --version` → `opencode v2.0.27`.
- **Порт по умолчанию — `4099`, не `4096`.** `ServerConfig.PORT` в приложении
  придётся либо переопределять флагом `--port`, либо принимать 4099.
- Сервер печатает пароль в stdout: `server password <32 симв.>` и
  `server listening on http://127.0.0.1:4099`.
- Все 8 эндпоинтов сняты руками, схемы — в таблице «Что показала разведка» выше.

**Главный вопрос этапа закрыт: плагины портировать НЕ нужно.**
`GET /api/config` показал, что v2 читает `~/.config/opencode/opencode.json`
и видит определённых там агентов; `GET /api/mcp` вернул все пять серверов из моего
конфига в статусе `connected` (`codebase-memory-mcp`, `context7`, `gh_grep`,
`local-memory`, `playwright`). То есть MCP из конфига запускается как есть.

**Пути тоже не нужно переделывать:** `opencode debug paths` подчиняется тем же
`XDG_CONFIG_HOME` / `XDG_DATA_HOME`, которые приложение уже выставляет в
`OpencodeRuntime.kt:170-171` и `:583-584`. Значит приватный
`files/opencode-config/opencode/` продолжает работать.

### Что осталось

- [ ] Запустить musl-arm64 бинарник на устройстве под `libldmusl.so`,
      снять `opencode --version`. Единственный гейт «миграция отменяется / идём дальше».
- [ ] Проверить, что `serve` на Android не пытается лезть в сеть за
      `bin` / `repos` и не ломается без `persistentPty`
      (на Windows `capabilities.persistentPty=false`).

### Найденный баг v2, ломающий логи

Флаг `--standalone` объявлен в `opencode --help`, но **`serve` его не принимает**:

```
$ opencode --standalone serve --port 4999
ERROR  Unrecognized flag: --standalone in command opencode serve
```

Проверено и до `serve` (root-флаг), и в позиции после. При этом
`--print-logs` в help гласит «server logs require --standalone» — то есть
**логи сервера в 2.0.27 получить нельзя**. Наш `RuntimeValidation`/`RuntimeManager`
не должен полагаться на серверные логи.

**Готово когда:** бинарник v2 запускается на телефоне и `GET /api/info` отвечает.
Если не запускается — миграция отменяется, дальше не идём.

---

## Этап 1 — Изоляция HTTP-слоя (0.5 дня)

**Боль:** путиendpoints-ов — строковые литералы, размазаны по 9 файлам. Без этого
этапы 3–4 превратятся в ад.

- Новый `app/src/main/java/org/opencode/mobile/server/ApiPaths.kt` — объект со
  всеми 8 путями.
- `LocalOpenCodeClient` (`app/src/main/java/org/opencode/mobile/server/LocalOpenCodeClient.kt:26`)
  принимает `ApiPaths`, а не строку. Сам клиент уже тонкий: `get`/`delete`/`post`/
  `postAsync` + `auth` — правка хирургическая.
- Прогнать `pwsh -File .\build.ps1 -Task jstest` — поведение не меняется ни на байт.

**Готово когда:** APK собирается, тесты зелёные, diff только структурный.

---

## Этап 2 — Feature flag версии сервера (0.5 дня)

**Боль:** 1.18.25 и 2.0.27 должны работать в одном APK, иначе нечем откатываться.

- `ServerConfig` → `ServerFlavor.V1 | ServerFlavor.V2`.
- Детектор: `GET /api/info` и **проверка тела** — на v2 это JSON с полем `version`.
  Проверять только HTTP-код нельзя: неизвестный путь на v2 отдаёт 200 + HTML SPA,
  то есть такой детектор объявит v1 сервером v2. Критерий: тело парсится как JSON
  и содержит `version` вида `2.x`.
- `ApiPaths` становится функцией от `ServerFlavor`.
- Учесть порт: v2 по умолчанию слушает **4099**. Либо всегда передаём `--port`,
  либо храним порт в одном месте и для v1, и для v2.

**Готово когда:** на устройстве с v1 всё работает как раньше, флаг не переключён.

---

## Этап 3 — Переезд на бинарник v2 (1 день)

- `ci.yml:62-75` — сменить источник с GitHub release на npm:
  `https://registry.npmjs.org/@opencode/cli-linux-arm64-musl/-/cli-linux-arm64-musl-2.0.27.tgz`
- **Схема .so меняется:** в v2 один бинарник, `libbun-musl.so` не нужна.
  Значит `OpencodeRuntime.kt` (`BIN_NAME`/`BUN_NAME`/`LOADER_NAME`) и проверки в
  `RuntimeValidation.kt`/`RuntimeManager.kt:720` надо править — иначе APK продолжит
  тащить 70 MB мёртвого веса и ждать файла, которого больше нет.
- **Бинарь v2 не статический — нужны ещё два .so из Alpine.** Под родным
  `libldmusl.so` он падает с `Error loading shared library libstdc++.so.6`.
  Системные библиотеки Android не подходят: `libc++.so`/`libgcc_s.so` связаны с
  bionic, а не с musl (нет `__register_atfork`, `_Unwind_*`, `__errno`).
  Рабочий рецепт — взять musl-линкованный GCC-рантайм Alpine v3.22 aarch64
  (проверено на устройстве, `opencode --version` → `opencode v2.0.27`):
  - `https://dl-cdn.alpinelinux.org/alpine/v3.22/main/aarch64/libstdc%2B%2B-14.2.0-r6.apk`
    → `usr/lib/libstdc++.so.6.0.33` (2 754 992 b) → в APK как `libstdc++.so.6`
  - `https://dl-cdn.alpinelinux.org/alpine/v3.22/main/aarch64/libgcc-14.2.0-r6.apk`
    → `usr/lib/libgcc_s.so.1` (133 008 b) → в APK как `libgcc_s.so.1` + симлинк `libgcc_s.so`
  Alpine `.apk` — это multi-stream gz tar, распаковывается обычным `tar -xzf`.
  Установщик v2 (`https://opencode.ai/v2/install`) их не везёт: он рассчитывает
  на хост Alpine. Итого в APK: `libopencode.so` 195.5 MB + 2.63 MB рантайма GCC.
- Положить v2-бинарник рядом со старым (`libopencode.so`), чтобы старые установки
  не сломались до этапа 7.
- Проверить `RuntimeValidation.kt:12,35` и `RuntimeManager.kt:720` — они валидируют
  связку `libopencode.so` + musl и печатают имя бинаря в диагностике.
- Проверить `OpencodeRuntime.startServe` (`server/OpencodeRuntime.kt:112`) — флаги
  запуска могли поменяться. Учесть: `--standalone` в v2 объявлен, но `serve` его
  отвергает, так что флаг запускать нельзя.
- Переключить флаг на V2 на тестовом устройстве.

**Готово когда:** сервер стартует, `/api/info` отвечает. **SPA в WebView пока
сломается — это ожидаемо, чинится на этапе 6.**

**Попутно обновить:** `README.md:55`, `docs/ROADMAP.md:28`, `CHANGELOG.md:35` —
везде зашит размер 184.7 MB.

### Что показала сборка с v2 (12.10.2026, устройство)

APK `opencode-debug-2026-10-12_20-38+05-00-d26d871a.apk`, 226.3 MB,
sha256 `d234668cd428ec2b2a593b7227762b9d08eedc6da00dd981a90c6b3926cb3c65`,
versionCode 3. Установлен поверх v1, сессия пережила обновление.

**Обвязку трогать не пришлось.** `libstdcxx.so` (2 754 992 б) и `libgcc_s.so`
(133 008 б) в APK уже были Alpine-ными — размеры совпали с измеренными на
этапе 0 байт в байт. `OpencodeRuntime.ensureMuslLibs` раскладывает их в
`filesDir/musl` под именами из DT_NEEDED, и v2-бинарник (204 999 888 б) после
подмены `libopencode.so` поехал без единой правки Kotlin.

**`libbun-musl.so` остаётся.** Это не рантайм opencode: его exec'ит
`Retention.kt:179-180` и харнесс-скрипты (`tools/run-verify.sh:39-45`,
`tools/verify.mjs:59-61`, `assets/harness/selfharness.js:292`), чтобы гонять
`.mjs` прямо на устройстве. Удалять нельзя.

**Ничего качать в CI не надо.** Раннее утверждение про `ci.yml:62-75`, где будто
скачиваются пресобранные `.so`, неверно: `ci.yml:58-65` просто
зовёт `gradlew :app:assembleDebug -PskipNativeBuild=true`, а `:75-80`
прямо говорят, что нативные либы в git не лежат (`app/src/main/jniLibs/` в
`.gitignore`) и собираются локально. Править в CI нечего.

**Конфиг приложения v2 читает как есть.** `GET /api/config` вернул все три
MCP-сервера из `files/opencode-config/opencode/opencode.jsonc` с уже
подставленными токенами (`{env:MCP_MEMORY_TOKEN}` развернулся). Значит
портировать конфиг или плагины не нужно — вопрос этапа 0 закрыт окончательно.

**Детектор версии работает на устройстве.** В logcat:
`ServerFlavor: сервер 2.0.27 -> V2`.

**Ловушка, о которой надо помнить:** `GET /api/mcp` вернул `{"data":[]}`,
хотя конфиг на месте и токены подставлены. В v2 MCP-серверы подключаются
лениво, при старте сессии, а не при старте serve. Пустой список на холодном
старте — не признак поломки; проверять надо после того, как поехала сессия.

**WebView не заблокирован.** `GET /` отдаёт SPA v2: 7 202 б, `<title>OpenCode</title>`,
бандл переехал на `/_assets/*` (rolldown). Поверхность загрузки цела; сам
рендер проверяется на этапе 6.

---

## Этап 4 — Перенос 8 эндпоинтов (1.5–2 дня)

Механическая работа по таблице выше. Самое дорогое — `/session/status`.

### Отдельно: `/session/status`

Его в v2 нет вообще, а на нём построен `ChatNotice.kt` целиком (детект
занятости) и логика prune в `ChatOverlay.kt:4416`.

**Замена нашлась в списке сообщений, а не в отдельном эндпоинте.** В v2 в потоке
сообщений появляется псевдо-сообщение:

```json
{ "id": "msg_…", "time": {"created": …}, "type": "idle", "outcome": "succeeded" }
```

Пока такого сообщения в последних N записях нет — сессия занята. Дополнительно у
объекта сессии после завершения появляются `outcome` (`succeeded` / `failed`) и
`time.idle`.

- Шаг 1: детект занятости перевести на «есть ли `type:"idle"` новее последнего
  сообщения пользователя».
- Шаг 2 (позже, по желанию): перейти с поллинга на SSE `GET /api/event` — поток
  живой, отдаёт `server.connected` и heartbeat.
- Переписать `MoltbookWakePlan.kt:133` и `MoltbookWakeTest.kt:74`.

**Тесты, завязанные на v1-пути:** `ChatNoticeTest.kt`, `MoltbookWakeTest.kt`,
`OpenCodeQuestionTest.kt` — все переписать.

**Готово когда:** все 9 файлов работают на v2, тесты зелёные, `/session/status`
нигде не упоминается.

---

### Что показала реализация этапа 4 (12.10.2026)

Ключевое решение: **потребителей не форкали по версии.** Вместо этого добавлен слой,
который приводит v2 НАЗАД к форме v1, и дальше всё приложение о версии не знает.

| Файл | Что делает |
|---|---|
| `server/ApiShape.kt` | `promptBody`, `sessions`, `session`, `messages`, `isBusy`, `statusRecord` — разбор без сети |
| `server/ApiStatus.kt` | `reachable`, `forSession`, `record` — поход по сети там, где в v2 эндпоинта нет |
| `server/ApiMcp.kt` | три формы `GET /mcp` → плоский `[{name,status}]` |
| `server/JsonBody.kt` | `asJsonArray` / `asJsonObject`: «не разобралось» ≠ «пусто» |

**`messages()` разворачивает v2 обратно в v1-конверт:** снимает `{data:[…]}`
(разворот привязан к наличию конверта, а не к флагу — решает форма тела), переворачивает
порядок (v2 отдаёт от новых к старым), `content[]` → `parts[]`, `text` → текстовая
часть, имя инструмента `name` → `tool`, `finish` → `step-finish`. Сообщение v1
узнаётся по `info.role` и проходит насквозь нетронутым.

**Три правила, которые нашлись на живом сервере, а не в документации:**

1. **`type:"system"` маскирует `idle`.** Сохранённое сообщение «список инструментов
   изменился» может лежать в начале ленты, и `idle` при этом не появляется вовсе.
   Наивное «новейшее == idle ⇒ свободна» объявило бы такую сессию занятой навсегда.
   Правило: идём от новых к старым, `system` пропускаем, решение принимает первый
   содержательный тип.
2. **`time.completed` кладём только когда ход реально завершён** — ровно как в v1.
   На `has("completed")` завязан `MoltbookTicker.lastAssistantText`; положим ноль —
   и недописанный ответ был бы принят за законченный.
3. **`ApiStatus.record()` в v2 стоит 1 + N запросов** (список сессий + лента на
   каждую), поэтому он зовётся только там, где нужны все сессии сразу: чистка
   сирот и планировщик moltbook. Для одной сессии — `forSession()` с единственным
   GET. `reachable()` намеренно дешёвый: он спрашивает список сессий, а не карту
   занятости.

**Что осталось на v1 и почему это нормально:** `OpenCodeQuestion.kt` и
`OpenCodePermission.kt` работают только с `ApiPaths.active.legacy`, который в v2
возвращает `null`. Это соответствует «Шагу 5» — v2 отдаёт нативные `/permission` и
формы, и тогда выпиляются оба хака.

**Проверено:** `ApiShapeTest.kt` — 26 тестов на реальных телах v1 и v2, включая
все три формы `/mcp` и ловушку с ведущим `system`.

---

## Этап 5 — Новые возможности (2–3 дня)

**Итог: сделано 2 из 5, остальные три отменены с причиной.**

1. **Карточка разрешения** на родном `/api/session/{id}/permission` + `/reply`.
   Заодно покрывает и вопросы агента — отдельный механизм не нужен.
2. **`/inbox`** → не эндпоинт, а поле `delivery` в теле `POST /prompt`.
3. ~~`/background`~~ — **отменено.** Замерено: описание метода гласит
   «Move active foreground backgroundable tools for this session into background
   observation». Это про фоновые инструменты, а не про закрытие оверлея. У
   безголового сервера на телефонеforeground-инструмента не бывает.
4. ~~`/form`~~ — **отменено.** Вопросы приходят через `/permission` с
   `action:"question"`, а `/api/session/{id}/form` так и остаётся пустым
   (проверено на живом сервере при висящем вопросе). Это ложное обещание
   этого же документа, а не возможность.
5. ~~`ynison.js` / handoff~~ — **отменено.** Ynison на телефоне не подключён
   вовсе: `MCP_YNISON_TOKEN` пуст, порт 4200 не слушается, `ynison.js` не
   раскладывается. Нечего переводить на новый API.

**Готово когда:** хаков-парсеров текста в клиенте не осталось. — **да.**

### Что показала реализация этапа 5 (13.10.2026)

**Один механизм, а не два.** И вопрос агента, и команда под `ask` приходят
одним списком `GET /api/session/{id}/permission`:

```
{"id":"per_…","sessionID":"ses_…","action":"shell","resources":["date"],
 "save":["date *"],"source":{"type":"tool","messageID":"msg_…","id":"call_…"}}
{"id":"per_…","sessionID":"ses_…","action":"question","resources":["*"],
 "source":{"type":"tool","messageID":"msg_…","id":"call_…"}}
```

**Текст вопроса лежит не здесь, а в ленте сообщений.** У запроса
`resources:["*"]`, а сам вопрос — в tool-части того же сообщения. Склейка
однозначна: `permission.source.id` == `toolPart.id`. Для команды склейка не
нужна — команда уже в `resources`.

Ответ: `POST …/permission/{requestID}/reply`, тело `{"decision":…}` — ключ
именно `decision`, не `reply`. Значения: `once` / `always` / `reject`. Ответ
приходит кодом **204**, поэтому прежний `post` (он требовал ровно 200) не
годится — добавлен `LocalOpenCodeClient.postAccepted` с приёмом 2xx.
Ключи конфига, которые реально срабатывают, — `bash` и `question` (а не
`execute`: `execute` это имя инструмента в ленте, а не идентификатор
разрешения; и `bash` даёт `action:"shell"`, потому что в бинаре есть таблица
нормализации `[["bash","shell"],["task","subagent"],["apply_patch","patch"]]`).

**Ошибка в карточке съедала всё.** Ответ уходил в лог одной строкой
«Ответ не ушёл», потому что `postAccepted` глотал исключение в
`catch (_: Exception) { return false }`. Теперь причина печатается: код
ответа с телом и класс исключения.

**Карточку не было видно — и это была вёрстка, а не данные.** Она была
последним элементом списка, а автопрокрутка уезжала на `messages.size`, что
пока идёт генерация — это строка «думаю». Карточка оказывалась на 1–3 позиции
за краем. Теперь она закреплена над полем ввода.

**Главная находка: ответ на вопрос в саму карточку НЕ продолжает ход.**
`{"decision":"once","message":"…"}` снимает запрос, но инструмент навсегда
остаётся `status=running`, а сессия для `ApiStatus.isBusy` выглядит занятой
навсегда — новые сообщения в неё не доходят. Проверено вручную и из
приложения.

Рабочая последовательность — **ответ обычным сообщением с
`delivery:"queue"`, затем `reject` висящему запросу**. На застрявшей сессии
`ses_ecda34d73ffeRp5MfmbET3UHQI` это дало: `pending` 1 → 0, `TOOL question`
→ `status=error`, очередь доставила «Выбираю вариант А», и модель ответила
`finish=stop` — «Принято: выбран вариант А». Сессия вышла из мёртвого
состояния сама. Для команды ничего этого не делается — там ходит `once`.

**Скрытая беда, которую стоит знать:** сервер иногда оставляет `question` в
`status=running` и не создаёт запрос разрешения вовсе (`pending=0`). Тогда
карточке нечего показать, а ход для `ApiStatus.isBusy` остаётся занятым
навсегда: новые сообщения доставляются в мёртвый ход и не выполняются.
`/interrupt` не помогает. Спасает только новая сессия. Это уже сторона
сервера, но приложение обязано это учитывать, а не считать сессию занятой
вечно.

### Приложение обязано само закреплять `"question": "ask"`

Находка 13.10.2026, после того как этап 5 закрылся: карточка вопроса
перестала появляться, и виноват был не код карточки.

Управляемый конфиг писал в секцию `permission` только
`mobile_launch_app`. Пока ключа `question` нет, инструмент question **не
запрашивает разрешение вовсе**: он стартует, упирается в ожидание ответа и
навсегда остаётся в `status:running`, а `GET /api/session/{id}/permission`
возвращает пустой список. `pending` пуст — карточке показывать нечего, и
симптом выглядит как «карточка опять не вызвалась».

- Измерено: `pending=0` при живом `TOOL question status=running`.
- После закрепления ключа: `pending=1, action:"question"`, и в logcat
  `FETCH(cached) … ask=1`.
- Ключ закрепляется тем же нефатальным шагом, что и модель
  (`syncQuestionPermission` рядом с `syncModelEntry`): отказ означает «карточка
  останется немой», а не «не запускаемся».
- Разбор — в `PermissionPlan.kt` (план вставки) и `JsoncMember.kt` (границы
  объекта по ключу). Чужое решение не перебивается: ключ `question` уже в
  секции `permission` — план `Keep`; секции `permission` нет вовсе — `Refuse`
  с записью в лог.

### Честная оговорка про `delivery`

`delivery` — поле тела `POST /prompt`, а не отдельный эндпоинт: `steer`
(по умолчанию) вплетает сообщение в идущий ход, `queue` ставит отдельным
ходом. Обычные сообщения приложение шлёт как есть — без поля, то есть
`steer`: принудительный `queue` для всего ленты означал бы, что ответ
пользователя больше не может вмешаться в идущую генерацию. `queue`
используется только для ответа на вопрос, и только потому, что следом идёт
`reject` запроса: ответ должен стать отдельным ходом, иначе инструмент
остаётся в `running`.

---

## Этап 6 — WebView под SPA v2 (1 день)

**Главный риск всего плана.** `MainActivity.kt:309-441` грузит
`http://127.0.0.1:{PORT}/` — это SPA opencode целиком, и в v2 оно переписано.

- Проверить передачу `Authorization` в `loadUrl` (`MainActivity.kt:415-416`).
- SPA v2 может требовать WebSocket вместо polling.
- Если SPA v2 в WebView на Android не едет — это блокер **этапа 6**, а не всей
  миграции. Решение: `ChatOverlay` (нативный Compose) остаётся единственным
  путём, WebView выпиливается.

**Готово когда:** либо SPA работает в WebView, либо принято решение о выпиливании.

### Что показала проверка этапа 6 (14.10.2026)

Проверено вживую на CPH2747 (APK с v2.0.27, сервер `GET /api/info` → `2.0.27`).

**SPA v2 в Chromium-движке рендерится полноценно.** Тот же сервер открыт
Playwright-ом с Basic-auth: `readyState=complete`, `#root` содержит 2 ребёнка,
`document.body.innerText` отдаёт реальный интерфейс — «Проекты / Добавить проект /
Настройки / Помощь» и список сессий. То есть совместимость SPA v2 с Blink
подтверждена, технического блокера нет.

**В WebView приложения SPA не рендерится — и это намеренно, а не поломка.**
Замер через DevTools-протокол (`adb forward` → `localabstract:webview_devtools_remote_<pid>`):
страница загружена (`title=OpenCode`, `url=http://127.0.0.1:4096/`, DOM из 77 узлов,
`#root` на месте с верным классом), но `readyState` застрял на `interactive`,
`#root` пуст, JS-исключений нет. Контрольный замер: `setTimeout(800ms)` не сработал
за 6 секунд — **таймеры заморожены**.

Причина ровно одна: `TerminalScreen` всегда передаёт `OpencodeWebView(paused = true)`
(`MainActivity.kt:212`), а ветка `paused` зовёт `wv.onPause()` + `wv.pauseTimers()`
(`MainActivity.kt:425-430`). SPA полностью перекрыт нативным `ChatOverlay`, поэтому
WebView специально погашен, чтобы не давать frame-спайки от бесконечной перерисовки
SPA под чатом. React-бутстрап не запускается именно из-за остановки таймеров —
сними флаг, и он поедет (совместимость доказана выше).

**Вывод: WebView не выпиливается.** Он не блокер и не мёртвый вес — он остаётся
загруженным «на будущее» для запросов к серверу и как готовая поверхность, если
понадобится показать полный интерфейс SPA. Нативный `ChatOverlay` — единственный
боевой путь, но это решение UI, а не следствие несовместимости v2.

**Как повторить замер на устройстве:**

```powershell
$adb="C:\Users\OLD\AppData\Local\Android\Sdk\platform-tools\adb.exe"
& $adb shell cat /proc/net/unix | Select-String webview_devtools   # → @webview_devtools_remote_<pid приложения>
& $adb forward tcp:9222 localabstract:webview_devtools_remote_<pid>
# GET http://127.0.0.1:9222/json  → список страниц (title, url, webSocketDebuggerUrl)
# дальше Runtime.evaluate по ws://127.0.0.1:9222/devtools/page/<id>
```

---

## Этап 7 — Чистка (0.5 дня)

- **Экономии мегабайт здесь нет, и оценка «−255 MB» была неверной.**
  `libopencode.so` v1 заменили на v2 ещё в этапе 3, а не держали рядом, так что
  удалять нечего. `libbun-musl.so` (70.2 MB) нужен независимо от v2 — это JS-рантайм
  для `Retention.kt:179-180` и harness-скриптов, а не что-то нужное opencode.
  Реальные нативы на диске весят 314.1 MB, APK — 226.3 MB, разница сжатие.
  Единственный оставшийся рычаг размера — `libsherpa-onnx-jni.so` + `libonnxruntime.so`
  (44.2 MB) ради офлайн STT/TTS; это продуктовое решение, а не чистка.
- Убрать v1-ветки из `ApiPaths` и `ServerFlavor`.
- Прогнать JS-гейт: `node app\src\test\js\ynison-announce.test.mjs` и
  `ynison-goaway.test.mjs`. **Тесты режут код по якорям** (`function neutralState() {`,
  `async function openSessionOnce(seed = null)`) — смена сигнатуры ломает гейт.
- Обновить `README.md` / `docs/ROADMAP.md` / `CHANGELOG.md` (версии, размеры).

**Готово когда:** весь гейт (`build.ps1 -Task verify`, `-Task jstest`) зелёный, в коде
нет ни одного упоминания v1.

### Что показала реализация этапа 7 (13.10.2026)

v1-обвязка удалена целиком, контракт HTTP остался один:

- `ApiPaths` — 189 строк превратились в 58: ушли `enum ServerFlavor`, интерфейс,
  `ApiPathsV1`/`ApiPathsV2`, `LegacyV1`/`LegacyV2` и три пути (`status()`, `health()`,
  `legacy`), у которых после чистки не осталось ни одного места вызова. Теперь это
  объект с `SESSIONS`, `MCP` и четырьмя путями, собираемыми из идентификатора сессии.
- `ServerFlavorProbe` больше не переключает вкус, а сверяет версию: `GET /api/info`
  против `BuildConfig.OPENCODE_VERSION`, и при расхождении пишет предупреждение.
  Эта проверка осталась не ради красоты — именно отказ сверяться показал бы
  расхождение между бинарём в APK и тем, что реально запустился.
- `ApiShape.messages` больше не смотрит на флаг версии: нормализация идёт всегда, а
  разворот ленты по-прежнему определяется **формой ответа** (наличием обёртки `data`),
  а не объявленной версией. Флаг врёт, форма — нет.
- Удалены `OpenCodeQuestion.kt` и `OpenCodePermission.kt` с тестами: на v2 вопросы
  приходят нативными формами, а разрешения имеют свой эндпоинт. Из `ChatOverlay.kt`
  вырезано около 380 строк — карточки вопросов и разрешений, кэш разрешений,
  `answerQuestion`/`rejectQuestion`/`respondPermission`, ветка ответа на вопрос в
  `send()` и блокировка ввода, пока висит разрешение. Отдельно из `stopGen()` ушла
  отправка `REJECT` перед `abort`.
- Ошибки всё ещё видны: `asErrorText` даёт роль `error`, а не «Система», и причина
  не попадает ни в озвучку, ни в счётчик ответов.

Проверка на устройстве: APK 226.3 MB, `GET /api/info` → 2.0.27, ответ на промпт
приходит с `finish=stop`, лента дорисовывается, падений нет.

---

## Сводка

| Этап | Время | Риск | Обратимость |
|---|---|---|---|
| 0. Разведка | 0.5 д | блокер, но дешёвый | да |
| 1. Изоляция HTTP | 0.5 д | нет | полная |
| 2. Feature flag | 0.5 д | нет | полная |
| 3. Бинарь v2 | 1 д | средний | откат флагом |
| 4. Эндпоинты | 1.5–2 д | высокий (`/session/status`) | откат флагом |
| 5. Новые возможности | 2–3 д | нет | каждая фича отдельно |
| 6. WebView | 1 д | блокера нет: SPA рендерится в Chromium, в WebView гасится намеренно | да |
| 7. Чистка | 0.5 д | нет | необратима |

**Итого: ≈ 8–10 рабочих дней.**

---

## Рекомендация по порядку

- **Этапы 0–3 одним заходом (2.5 дня).** За них выясняется почти всё, включая
  WebView — и обратная дорога дешёвая.
- **Не смешивать** миграцию со сменой модели или редизайном UI. Одно за раз.
- **Не прыгать в этап 5 раньше этапа 4.** Старый поллинг должен уйти первым,
  иначе получится два слоя состояния.

## Ссылки

- V1 → V2 миграция: <https://opencode.ai/v2/docs/migrate-v1/>
- V2 API reference: <https://opencode.ai/v2/docs/api/>
- V1 server API: <https://opencode.ai/docs/server/>
- Актуальная версия v2: `https://opencode.ai/update/api/latest/cli/npm`
