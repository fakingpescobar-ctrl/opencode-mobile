# Миграция на OpenCode 2 (план по этапам)

Дата ревизии: **12.10.2026**. Целевая версия сервера: **opencode 2.0.27**.

Ключевой факт, определяющий всю сложность: приложение **не подключается к
установленному opencode** — бинарник сервера лежит внутри APK
(`app/src/main/jniLibs/arm64-v8a/libopencode.so`, 184.7 MB) и запускается через
`nativeLibraryDir` + `libldmusl.so`. Значит, апгрейд = замена бинаря + перенос
HTTP-слоя.

---

## Сверка версий (проверено 12.10.2026)

| Параметр | Сейчас (v1) | Цель (v2) |
|---|---|---|
| Версия | **1.18.25** (захардкожена) | **2.0.27** |
| Бинарь в APK | `libopencode.so`, 184.7 MB | `@opencode/cli-linux-arm64-musl@2.0.27`, **195.5 MB**, 2 файла |
| Источник бинаря | GitHub release tarball | **npm** (GitHub-релизов для v2 нет, `releases/tags/v2.0.27` → 404) |
| musl ARM64 | есть | есть ✅ |
| Префикс API | `/session*` | `/api/*` |
| Поток событий | **не используется**, чистый поллинг | `/api/event` + `/api/experimental/session/{id}/wait` |
| Установка в v2 | `opencode-ai` | `@opencode/cli` |

Размер бинаря растёт всего на ~11 MB — миграция технически возможна.

**Что v2 НЕ ломает:** конфиг (`~/.config/opencode/opencode.json`,
`<project>/opencode.json`, `.opencode/`), агенты, скиллы, команды, MCP. Формат v1
поддерживается нативно, конвертация не обязательна.

**Что v2 ломает (три breaking change по официальному гайду):**
1. Плагины — новый plugin API, v1-плагины не запускаются.
2. Server API и клиенты — новые контракты.
3. TUI-конфиг: слоёный `tui.json(c)` → один глобальный `cli.json`.

> Приложение использует плагины? Проверить на этапе 0. В `~/.config/opencode/opencode.json`
> есть `@tarquinen/opencode-dcp`, `opencode-worktree`, `opencode-quota`, `claude-smart`,
> `opencode-claude-auth` — но они живут на **десктопной** машине, а не на телефоне.
> Для APK-контура (bundled `serve`) вопрос снимается сам, если сервер на телефоне
> не подхватывает пользовательский конфиг с хоста.

---

## Карта эндпоинтов: v1 → v2

Приложение использует всего **8 путей** в 9 файлах. Полная таблица:

| v1 | v2 | Файлы- потребители |
|---|---|---|
| `GET /global/health` | `GET /api/info` | `RuntimeValidation.kt`, `MoltbookAlarmReceiver.kt:151` |
| `GET /session` | `GET /api/session` | `ChatOverlay.kt:946`, `:3853`, `:4396`, `MoltbookAlarmReceiver.kt:1189` |
| `POST /session` | `POST /api/session` | `ChatOverlay.kt:4243`, `MoltbookTicker.kt:1189` |
| `DELETE /session/{id}` | `DELETE /api/session/{id}` | `ChatOverlay.kt:4294` |
| `GET /session/{id}/message` | `GET /api/session/{id}/view` | `ChatOverlay.kt:4306`, `MoltbookTicker.kt:1215` |
| `POST /session/{id}/message` | `POST /api/session/{id}/prompt` | `ChatOverlay.kt:4441`, `MoltbookTicker.kt:1195` |
| `POST /session/{id}/abort` | `POST /api/session/{id}/interrupt` | `ChatOverlay.kt:4263` |
| `GET /session/status` | **удалён в v2** | `ChatNotice.kt` (целиком), `ChatOverlay.kt:3843/4277/4320/4366/4416`, `ZenQuota.kt` |

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
`/global/event` (слит в `/api/event`), `/session/status`.

---

## Новые возможности v2, которые закрывают текущие костыли

| Эндпоинт | Что закрывает в текущем коде |
|---|---|
| `POST /api/session/{id}/background` | Сессия живёт при закрытом оверлее. Убирает блок `paused`/WebView (`MainActivity.kt:208-212`) |
| `GET /api/experimental/session/{id}/wait` | Замена поллингу `GET /session/status` в `ChatNotice.kt` |
| `POST /api/session/{id}/inbox` | Очередь сообщений — снимает блокировку «агент занят → нельзя отправить» |
| `POST /api/session/{id}/form/{formID}/reply` | **Удаляет хак `OpenCodeQuestion.kt`** — там~200 строк парсинга текста вопроса агента, потому что v1 отдавал 404 на `/api/session/{id}/question/{id}/reply` |
| `GET/POST /api/session/{id}/permission` | Нативные permission-промпты вместо разбора текста в `OpenCodePermission.kt` |
| `GET /api/pair` + команда `opencode pair` | Раздача URL + логин/пароль — можно уйти от ручного adb port-forward |
| `GET /api/experimental/persistent-pty/{id}/handoff` | Тот самый handoff, который `ynison.js` моделирует руками (`handoffUntil`, `ensurePhone`) |
| `GET /api/session/{id}/context` | Инфо о контексте/токенах нормальным эндпоинтом |
| `/api/worktree` | `opencode-worktree` становится встроенным |

---

## Этап 0 — Разведка (0.5 дня)

**Боль:** неизвестно, запускается ли v2-бинарь на Android и какие реальные схемы
у ответов.

- Скачать `@opencode/cli-linux-arm64-musl@2.0.27` из npm, распаковать, проверить
  `file` / `ldd` (реально ли musl + arm64), посмотреть что за 2 файла в tarball.
- Запустить на устройстве под `libldmusl.so`, снять `opencode --version`.
- Поднять v2 `serve` локально на десктопе и **снять руками** фактические ответы по
  8 эндпоинтам. Докам не доверять: часть расхождений уже видна (см. таблицу выше).
- Проверить порт по умолчанию (`4096` захардкожен в `ServerConfig.PORT`).
- Проверить, грузит ли bundled-сервер пользовательский конфиг — от этого зависит,
  нужна ли миграция плагинов вообще.

**Готово когда:** бинарь v2 запускается на телефоне и `GET /api/info` отвечает.
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
- Детектор: пробуем `GET /api/info` — ответил → v2; нет → v1.
- `ApiPaths` становится функцией от `ServerFlavor`.

**Готово когда:** на устройстве с v1 всё работает как раньше, флаг не переключён.

---

## Этап 3 — Переезд на бинарь v2 (1 день)

- `ci.yml:62-75` — сменить источник с GitHub release на npm:
  `https://registry.npmjs.org/@opencode/cli-linux-arm64-musl/-/cli-linux-arm64-musl-2.0.27.tgz`
- Положить v2-бинарь рядом со старым (`libopencode.so`), чтобы старые установки
  не сломались до этапа 7.
- Проверить `RuntimeValidation.kt:12,35` и `RuntimeManager.kt:720` — они валидируют
  связку `libopencode.so` + musl и печатают имя бинаря в диагностике.
- Проверить `OpencodeRuntime.startServe` (`server/OpencodeRuntime.kt:112`) — флаги
  запуска могли поменяться.
- Переключить флаг на V2 на тестовом устройстве.

**Готово когда:** сервер стартует, `/api/info` отвечает. **SPA в WebView пока
сломается — это ожидаемо, чинится на этапе 6.**

**Попутно обновить:** `README.md:55`, `docs/ROADMAP.md:28`, `CHANGELOG.md:35` —
везде зашит размер 184.7 MB.

---

## Этап 4 — Перенос 8 эндпоинтов (1.5–2 дня)

Механическая работа по таблице выше. Самое дорогое — `/session/status`.

### Отдельно: `/session/status`

Его в v2 нет вообще, а на нём построен `ChatNotice.kt` целиком (детект
занятости) и логика prune в `ChatOverlay.kt:4416`.

- Шаг 1: перевести на `GET /api/experimental/session/{id}/wait` (long-poll).
- Шаг 2: когда `wait` выйдет из experimental — перевести на push `/api/event`.
- Переписать `MoltbookWakePlan.kt:133` и `MoltbookWakeTest.kt:74`.

**Тесты, завязанные на v1-пути:** `ChatNoticeTest.kt`, `MoltbookWakeTest.kt`,
`OpenCodeQuestionTest.kt` — все переписать.

**Готово когда:** все 9 файлов работают на v2, тесты зелёные, `/session/status`
нигде не упоминается.

---

## Этап 5 — Новые возможности (2–3 дня)

Порядок по соотношению «выигрыш / усилие»:

1. **Удалить `OpenCodeQuestion.kt`** целиком — v2 отдаёт нативные
   `/api/session/{id}/form` + `/form/{formID}/reply`. Минус ~200 строк хака.
2. **`OpenCodePermission.kt`** → `/api/session/{id}/permission` + `/reply`.
3. **`/background`** — сессия переживает закрытие оверлея.
4. **`/inbox`** — очередь сообщений вместо отказа «занято».
5. **`ynison.js`** — проверить, можно ли опереться на
   `/api/experimental/persistent-pty/{id}/handoff` вместо ручного `handoffUntil`.

**Готово когда:** хаков-парсеров текста в клиенте не осталось.

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

---

## Этап 7 — Чистка (0.5 дня)

- Удалить v1-бинарь из `jniLibs` → **экономия ~184 MB в APK**.
- Удалить v1-ветки из `ApiPaths` и `ServerFlavor`.
- Прогнать JS-гейт: `node app\src\test\js\ynison-announce.test.mjs` и
  `ynison-goaway.test.mjs`. **Тесты режут код по якорям** (`function neutralState() {`,
  `async function openSessionOnce(seed = null)`) — смена сигнатуры ломает гейт.
- Обновить `README.md` / `docs/ROADMAP.md` / `CHANGELOG.md` (версии, размеры).

**Готово когда:** APK без v1-бинаря, весь гейт (`build.ps1 -Task jstest`) зелёный.

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
| 6. WebView | 1 д | **возможен блокер** | да |
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