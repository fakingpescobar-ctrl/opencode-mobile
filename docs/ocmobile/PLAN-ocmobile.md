# План: OC Mobile — нативный Android-клиент к opencode («чат + файлы/код»)

> Рабочая папка: `C:\Projects\ocmobile`. Стек: **Kotlin + Jetpack Compose** (нативный).
> Арх-контекст: клиент подключается к headless-серверу `opencode serve` (HTTP API на :4096),
> сервер сам авторизован на модель `opencode/big-pickle` (hosted). big-pickle — закрытый контур,
> публичного OpenAI API нет; доступ возможен только через сервер opencode (или Zen-шлюз — см. заметку).
> Этот план — «My server» режим + файлы/код. Пометка `[CRITIC#N]` — учтённые замечания ревью.

## 0. Резюме выбора стека

**Kotlin + Jetpack Compose (нативный Android).** Причины: SSE-стриминг (OkHttp EventSource → Flow) first-class;
LazyColumn для списков чата; прямой доступ к Keystore/biometric/фону; сильные Compose-либы подсветки/markdown.
Единственный таргет — Android, сервер сам ходит за моделью → без мультиплатформенного оверхеда.
Архитектура: **MVVM + UDF (односторонний поток), single-activity, NavHost, Hilt DI**.

> Проект разворачиваемый на чистом месте. Параллельный существующий Expo-набросок `C:\Projects\OPENCODE_MOBILE`
> (Big Pickle через Zen) НЕ используется — нативный Kotlin строится с нуля. Zen-шлюз (`https://opencode.ai/zen/v1`,
> OpenAI-совместимый, без авторизации, модель `big-pickle`) можно позже добавить как третий backend-режим
> (облачный, без сервера) — в P3.

## 1. Стек и библиотеки

| Роль | Библиотека | Версия-ориентир* |
|---|---|---|
| UI | Jetpack Compose + Material 3 | BOM 2026.08 |
| Язык/сборка | Kotlin, AGP | 2.2.x / 8.11+ |
| SDK | minSdk 29, target/compileSdk 36 | — |
| REST | Retrofit + kotlinx.serialization converter | 3.x |
| HTTP/SSE | OkHttp + `okhttp-sse` (EventSource) | 5.x |
| JSON | kotlinx-serialization-json | 1.9.x |
| DI | Hilt | 2.57+ |
| Навигация | androidx.navigation:navigation-compose | 2.9+ |
| Настройки | DataStore (Preferences) + Android Keystore (AES) | 1.1+ |
| Markdown в чате | multiplatform-markdown-renderer (mikepenz, M3) | 0.3x |
| Подсветка кода | WebView + highlight.js (offline assets); альт. Chromatic | P1 |
| Картинки (part-file) | Coil | 3.x |
| Кэш (P3) | Room | 2.7+ |
| Тесты | JUnit4, MockWebServer, Turbine, MockK, coroutines-test, compose-ui-test | актуальные |

\* на старте — последние стабильные; жёстко не привязываться.

## 2. Структура проекта

```
ocmobile/
├── app/                      # Application, MainActivity, NavHost, DI-граф, bottom nav
├── core/
│   ├── common/               # Result-обёртки, диспатчеры, ошибки, утилиты
│   ├── model/                # DTO (kotlinx.serialization) + доменные модели + мапперы
│   ├── network/              # Retrofit-интерфейс, OkHttp, SSE-клиент, интерсепторы
│   ├── data/                 # репозитории, SettingsRepository (DataStore + Keystore)
│   └── designsystem/         # тема, компоненты (CodeBlock, ToolCard…)
└── feature/
    ├── terminal/            # ОДИН TerminalScreen: вывод + ввод + slash-команды + effort
    ├── connect/             # первичный ConnectScreen (адрес, username, password, health-check)
    └── settings/            # (опционально) вынос настроек, если понадобится отдельный экран
```

Базовый пакет: `kz.ocmobile` (`kz.ocmobile.core.network`, `kz.ocmobile.feature.chat`…).
`core:model` не знает про Retrofit. Каждый feature-модуль: `ui/` (Screen, ViewModel).

## 3. UX: один терминал + команды (максимально просто, как opencode CLI)

**Ядро концепции:** приложение выглядит и ведёт себя как терминал opencode CLI — чёрный фон, моноширинный текст,
ввод внизу как консольная строка. Никакого «глянца», карточек и свинга. Всё управление — slash-командами.

**Единственный рабочий экран — `TerminalScreen`** (полноэкранный):
- Прокручиваемый вывод: диалог (text/reasoning/tool/file parts), статусы, результаты команд — всё как строки терминала
  (моноширинный шрифт, различение ролей цветом/префиксом, напр. `❯ user` / `◧ assistant` / `◆ tool`).
- Внизу — строка ввода (как `❯`-прокmpт). Вводишь текст → промпт модели; вводишь `/команду` → выполняет команду клиента
  (см. ниже).
- Липкая шапка/низ с **двумя единственными управляющими элементами** [требование юзера]:
  1. **Выбор модели** (из `GET /config/providers` / настроек).
  2. **Выбор reasoning effort: low | medium | high** — передаётся в запрос как `reasoning_effort`.
     (именно effort управляет скоростью/глубиной: low = быстро, medium/high = думает дольше — как в разобранном кейсе HF).

**Slash-команды** (минимум, прямолинейно):
- `/sessions` — список сессий; `/new` — создать; `/rm <id>` — удалить.
- `/models` — список доступных моделей; `/effort low|medium|high` — то же, что кнопка.
- `/files` — содержимое текущего каталога (`GET /file?path=`); `/open <path>` — читать файл (`GET /file/content`)
  (на телефоне рендер под код — моноширинный текст; подсветка — опционально, P1).
- `/grep <pat>` — поиск по тексту (`GET /find?pattern=`); `/find <name>` — поиск файла по имени; `/sym <q>` — символы.
- `/stop` — abort; `/help` — список команд.

**Вспомогательный экран (не основной): `ConnectScreen`** — только первичный ввод адреса сервера и
username/password `[CRITIC#6]`, кнопка Проверить (`GET /global/health`). После подключения живём в терминале.

**Отношение к старому набору экранов:** Sessions/Chat/Files/Search/Shell сливаются в один TerminalScreen,
различающийся только тем, какую команду/вид обрабатывает. Навигации между «экран-функциями» нет — есть один терминал.

## 4. Сетевой слой (`core/network`)

### 4.1 REST + единый HTTP-клиент `[CRITIC#5]`
- `OpenCodeApi` — Retrofit-интерфейс 1:1 к таблице (раздел 9). Динамический `baseUrl` из настроек.
- **Один общий `OkHttpClient`** для REST и SSE (один `AuthInterceptor`) — иначе `/event` получит 401. `[CRITIC#5]`
- `AuthInterceptor`: если задан username/password → `Authorization: Basic base64(user:pass)`; без пароля — заголовка нет.
  Username по умолчанию `opencode` (переопределяем `OPENCODE_SERVER_USERNAME`). `[CRITIC#6]`
- **Политика ошибок/таймаутов глобально** `[CRITIC#7]`: connect 10s, read 30s (для `/message` — long-read 60s+).
  Ретраи только идемпотентных GET (с экспоненциальным бэкоффом). Маппинг 401/404/ECONNREFUSED → человекочитаемые
  состояния (`Unauthorized` / `ServerNotFound` / `NotFound`), один общий `UiError`-сеaled по умолчанию на уровне ViewModel.

### 4.2 Стриминг — ключевая схема (подтверждено доками)
`POST /session/:id/prompt_async` → **204 No Content** (стрима в теле нет). Обновления — по глобальной SSE-шине:
- **`EventBusClient`**: OkHttp EventSource → `GET /event` (SSE, живёт бесконечно, первое — `server.connected`).
  Парсер → `SharedFlow<ServerEvent>` + `StateFlow<SseConnectionState>`.
- Reconnect: экспоненциальный бэкофф + джиттер; на IOException/5xx; сброс при смене Wi-Fi (`NetworkCallback`).
  **401 при reconnect → перелогin-подсказка в Settings, отличаем от сетевой ошибки.** `[CRITIC#5]`
- **Ресинк после разрыва/фона** `[CRITIC#3]`: при каждом reconnect и при возврате из фона — `GET /session/:id/message`
  для активной сессии, сверка/дотягивание пропущенных событий (не теряем финиш/ошибку ответа).
- **ChatViewModel**: `events.filter { it.sessionID == … }` → инкрементальное обновление part; `session.idle` → конец генерации.
  **Спайк подтвердил (v1.18.18)**: `message.part.delta {messageID, partID, field:"text", delta}` — append-куски (стриминг),
  финальный `message.part.updated` — полный снапшот (сверка/замена). `step-finish{reason,tokens}` + `session.idle` — два
  сигнала завершения. SSE-keepalive: `server.heartbeat` ~30с (важно для read-timeout OkHttp).
- **Передача reasoning effort [требование юзера]**: в API НЕТ поля `reasoning_effort`. **Effort = поле `variant`** в теле
  `prompt_async`/`message`: `"variant":"low"`. Набор вариантов модели — из `GET /config/providers` (поле `variants`,
  напр. glm-5.3-flash: low/high/max; **big-pickle вариантов не имеет** → кнопку effort скрывать/disabled).
  Источник: `packages/core/src/plugin/variant.ts` (генерит `{reasoningEffort: id}`). Спайк проверил: `variant:"high"`
  на big-pickle и `variant:"low"` на glm-5.3-flash → 204, ответы получены, variant зафиксирован в session.model.variant.
- **Fallback-режим [спайк: polling НЕ нужен]**: блокирующий `POST /session/:id/message` → 200 `{info, parts}` ПОСЛЕ
  завершения (замер: 7.5s на короткий промпт). Polling `GET /session/:id/message` — только как резервный/ресинк
  (приложение перезапустили, дотягиваем активную сессию — полная история с parts). Таймаут блокирующего: 60s+.
- **Синтетические части [спайк]**: сервер инжектит в user-части `synthetic:true` (supermemory-контекст и пр.) —
  рендерить как системные (серым, префикс `⎇`), в запрос не включать.
- Permissions: event → диалог → `POST /session/:id/permissions/:permissionID` `{response, remember?}` (формат — в спайке).

### 4.3 Аутентификация
- Сервер: `OPENCODE_SERVER_PASSWORD` → HTTP Basic; username по умолчанию `opencode`. `[CRITIC#6]`
- Креды из `SettingsRepository` (см. раздел 7).

### 4.4 TLS / cleartext / режимы подключения `[CRITIC#1]`
**Убрано противоречие:** выбираем один режим для release.
- **Рекомендуемый (release): только HTTPS** — либо Tailscale `tailscale serve --bg 443` (валидный серт `<m>.<tailnet>.ts.net`,
  нативный TLS, ноль конфига), либо reverse-proxy с валидным сертом (Caddy/nginx/NPM).
- **LAN http:// — только debug (cleartext разрешён), в release отдельная debug-cборка.**
  В Settings при небезопасном `http://` — жёлтое предупреждение «небезопасное соединение».
- CORS не применим (нативный клиент).

## 5. DTO (`core/model`)

Источник истины: **OpenAPI-спека** `http://<host>:4096/doc` + `packages/sdk/js/src/gen/types.gen.ts` (репо opencode).
Примеры JSON фиксируем в спайке P-1 → fixtures для контракт-тестов.

| DTO | Поля |
|---|---|
| `SessionDto` | id, title, parentID?, directory, time{created,updated} |
| `MessageDto` | id, sessionID, role, time, providerID, modelID, agent?, cost?, tokens? |
| `PartDto` (sealed, по `type`) | text{text}, tool{id,state,tool,input?,output?}, file{filename,mime,url}, step-start/finish, patch, snapshot, reasoning |
| `MessageWithPartsDto` | {info: MessageDto, parts: List<PartDto>} |
| `FileNodeDto` | name/path, type (file/dir) |
| `FileContentDto` | содержимое (текст или b64) |
| `FindMatchDto` | path, lines, line_number, absolute_offset, submatches |
| `SymbolDto`, `CommandDto`, `AgentDto`, `ProjectDto`, `HealthDto{healthy,version}` | по спеке |

JSON config: `ignoreUnknownKeys=true`, `encodeDefaults=false`, `explicitNulls=false` (защита от churn v0.x).

## 6. Работа с файлами

- **Браузер**: плоская навигация «вглубь» с breadcrumbs (надёжнее ленивого дерева): `GET /file?path=…`; старт — `/project/current`.
- **Windows-пути**: `C:\...` в query → строго URL-encode (`%5C`), слэши не нормализуем — сервер ждёт нативный формат. (`C:\Projects\POE2AUTO`). Спайк подтвердил.
- **Поиск [спайк]**: `GET /find?pattern=&directory=` — **directory ОБЯЗАТЕЛЕН** (без него сканирует корень → таймаут); формат match
  `{path:{text}, lines:{text}, line_number, absolute_offset, submatches:[{match:{text},start,end}]}`. `/find/file?query=&directory=&limit=`
  → string[]; `/find/symbol?query=` → Symbol[] (пусто без LSP).
- **Правки файлов**: прямого endpoint записи НЕТ (`[из критича: верно]`) — правки **только через агента** (промпт-диff) или shell. Мобильный клиент — пульт, агент — исполнитель.

## 7. Настройки и безопасность

- **DataStore (Preferences)**: baseUrl, username `[CRITIC#6]`, модель/агент, тема, fallback-режим polling.
- **Пароль/username**: НЕ в DataStore plain-text. AES-ключ в **Android Keystore**, шифрованный блоб рядом в DataStore.
  (EncryptedSharedPreferences — deprecated, не брать.) Никогда не логировать.
- **Биометрия (P3)**: `BiometricPrompt` перед показом/правкой пароля.
- Диагностика: ping `/global/health` (показать version — маркер совместимости), понятные ошибки (нет маршрута/401/таймаут).
- Подсказки в UI: держать ПК без сна, открыть порт 4096, варианты Tailscale.

## 8. Milestones

**P-1. Спайк-протокола (1–2 дня) — ОБЯЗАТЕЛЬНО до кода** ✅ **ВЫПОЛНЕН 2026-08-28 (v1.18.18)**
Запущен serve с паролем; пройдены все endpoints; снят SSE-поток `/event` (имена, payload, снапшот vs дельта);
сохранены живые JSON → fixtures; выкачана `/doc`-спека (478KB).
Готовность: `docs/api-notes.md` + `core/model/fixtures/*.json` (контракт зафиксирован).
Результаты: effort = `variant` (а не reasoning_effort); fallback = блокирующий `POST /message` (polling не нужен);
`step-finish` + `session.idle` — сигналы завершения; `synthetic:true` части; `/find` требует `directory`;
сервер serve стартует из нейтральной директории (конфликт инстансов на директорию сессии).

**P0. MVP-чат (~1.5 недели)**
Каркас (модули, DI, тема, TerminalScreen), Connect (URL+username+password, health-check), сессии через команды (list/new/rm)
в терминале, TerminalScreen: `prompt_async` + SSE → стриминг text/parts, abort (`/stop`), fallback polling с критерием завершения,
ресинк после разрыва/фона, кнопка-эффек wide (low/medium/high) и выбор модели.
Готовность: телефон в LAN ведёт диалог с big-pickle, текст стримится; рестарт сервера/Wi-Fi → переподключение без падения,
потерянные события дотягиваются. **`[CRITIC#8]` contract-тесты парсинга DTO на fixtures — acceptance-критерий P0.**

**P1. Файлы + поиск (~1.5 недели)**
Файлы+поиск через терминал: `/files`, `/open <path>` (вьюер с подсветкой/line-jump), `/grep`, `/find`, `/sym`, git-статус.
Готовность: grep находит строку в реальном репо (напр. POE2AUTO), открытие файла на строке; файл 1MB не фризит UI
(пейджинг/обрезка). `[CRITIC#8]` смок-чеклист против живого сервера в конце.

**P2. Команды, права, модель (~1 неделя)**
Slash (`GET /command` + `POST /session/:id/command`), `/shell` команда с подтверждением, permission-диалог
(`/permissions/:id`), ToolCard для tool-parts, выбор модель/агент (`/config/providers`, `/agent`), diff (`/session/:id/diff`), revert.
Готовность: slash с телефона; permission-запрос отображается/одобряется/отклоняется; diff виден.

**P3. Полировка (по остаточному)**
Room-кэш истории (offline), уведомление о `session.idle` (foreground service — осознанно из-за Doze), cost/tokens,
todo (`/session/:id/todo`), share сессии, биометрия, mDNS-discovery (`--mdns` + NSD), нативная подсветка, **третий backend-режим Zen** (big-pickle без сервера).
Готовность: contract-тесты зелёные; Wi-Fi↔LTE не роняет стрим; crash-free.

## 9. Справочник API → Retrofit-методы

| Endpoint | Retrofit-метод | Где |
|---|---|---|
| `GET /global/health` | `health()` | Settings |
| `GET /project/current` | `currentProject()` | Files-старт |
| `GET /config/providers`, `GET /agent`, `GET /command` | `providers()/agents()/commands()` | выбор модели, slash |
| `GET/POST /session`, `GET /session/status`, `GET/DELETE/PATCH /session/:id` | `listSessions()` и др. | Sessions |
| `POST /session/:id/message` | `sendMessage()` | fallback |
| `POST /session/:id/prompt_async` → 204 | `sendPromptAsync()` | основной чат |
| `POST /session/:id/abort` | `abort()` | Stop |
| `GET /session/:id/message[/:mid]` | `messages()/message()` | история, polling, ресинк |
| `POST /session/:id/command`, `/shell` | `runCommand()/runShell()` | P2 |
| `POST /session/:id/permissions/:pid` | `respondPermission()` | P2 |
| `GET /session/:id/diff`, `/todo`, `POST /session/:id/revert` | `diff()/todo()/revert()` | P2/P3 |
| `GET /file?path=`, `/file/content?path=`, `/file/status` | `listDir()/content()/fileStatus()` | Files |
| `GET /find?pattern=`, `/find/file?query=`, `/find/symbol?query=` | `findText()/findFile()/findSymbol()` | Search |
| `GET /event` (SSE) | `EventBusClient` | стриминг, статусы, permissions |
| `GET /doc` | — | dev (генерация/сверка DTO) |

Примечание: `POST /session/:id/prompt` — в актуальных доках заменили на `/session/:id/message`; `/prompt` проверить в спайке, проектировать на `/message` + `/prompt_async`.

## 10. Риски и открытые вопросы

| # | Риск/вопрос | Митигация |
|---|---|---|
| 1 | opencode v0.x — API меняется | пиновать версию сервера; fixtures-contract-тесты; version из `/global/health`; `/doc` при апгрейде |
| 2 | Формат SSE (имена, снапшот vs дельта) | закрывается спайком P-1; до этого не хардкодить имена |
| 3 | `prompt_async` = 204 + шина `/event` (не SSE в теле) | учтено; fallback — `/message` + polling |
| 4 | Формат `response` в permissions | спайк P-1 |
| 5 | `/experimental/*` нестабильны | не использовать P0–P2 |
| 6 | Cleartext в LAN (release) | решено `[CRITIC#1]`: release только HTTPS/Tailscale; http — debug |
| 7 | SSE + Doze/смена сети | коннект при активном UI; backoff-reconnect; ресинк `[CRITIC#3]`; FG-service строго P3 |
| 8 | Большие/бинарные файлы | лимит+обрезка+блэклист |
| 9 | Windows-пути в query | строгий URL-encode; спайк на реальном сервере ✅ |
| 10 | Нет endpoint записи файлов | правки только через агента/shell — в UX |
| 11 | ПК спит / firewall | инструкция в Settings |
| 12 | Инстансы на директорию (serve из проекта = disposed) | спайк-сервер стартует из нейтральной директории ✅ |
| 13 | big-pickle без effort-вариантов | кнопку effort показывать по `variants` модели из /config/providers |

---
**Порядок запуска:** P-1 спайк → P0 MVP-чат → P1 файлы+поиск → P2 команды/права → P3 полировка.
После рестарта opencode (правка роутера: critic → GLM) прогнать повторное ревью плана критиком до начала реализации.
