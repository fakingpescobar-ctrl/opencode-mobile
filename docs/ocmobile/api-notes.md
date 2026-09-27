# API Notes — спайк P-1 (opencode serve v1.18.18)

> Дата: 2026-08-28. Сервер: `opencode serve` (v1.18.18) на 127.0.0.1:4097 (спайк) и 100.119.28.14:4096 (боевой, Tailscale).
> Auth: HTTP Basic, username `opencode` (env `OPENCODE_SERVER_USERNAME`), пароль env `OPENCODE_SERVER_PASSWORD`.

## 1. Схема API (актуальная, из /doc)

Полный OpenAPI-спек: `docs/openapi-spec.json` (478KB, swagger). Живые примеры: `core/model/fixtures/*.json`.

Ключевые отличия от оригинала PLAN.md:
- `/session/:id/prompt` НЕ существует → используем `/session/:id/prompt_async` (204) + SSE `/event`.
- Существует блокирующий `POST /session/:id/message` — **вернул полный ответ за 7.5s**. Идеальный fallback (без polling!):
  `POST /session/:id/message` тело как у prompt_async → 200 `{info, parts}` после завершения.
- Есть `/api/*` namespace (v2, `/api/session/:id/wait` и др.) — стабильнее, но для P0 не нужен.
- `/session/:id/message?limit=N` (GET) — история для ресинка.

## 2. Стриминг (подтверждено live)

`POST /session/:id/prompt_async` → **204 No Content** (ответа в теле нет).
Обновления — SSE `GET /event`:

```
server.connected                       (первое, properties:{})
server.heartbeat                       (каждые ~30с — keepalive, важен для OkHttp read timeout)
session.created
session.updated                        (info: Session — модель/статус/токены)
session.status    {"type":"busy"}      (начало генерации)
message.updated                        (info: Message, role=user)
message.part.updated                   (part text user + СИНТЕТИЧЕСКИЕ — см. п.6)
message.updated                        (info: Message, role=assistant)
message.part.updated                   (part step-start {snapshot})
message.part.updated                   (part reasoning {text:""})
message.part.delta   {messageID, partID, field:"text", delta:"<append>"}  ← стриминг
message.part.updated                   (part reasoning — финальный снапшот)
message.part.updated                   (part text — финальный снапшот, time.end)
message.part.updated                   (part step-finish {reason:"stop", tokens})
message.updated                        (assistant completed, time.completed)
session.status    {"type":"idle"}
session.idle                           ← КОНЕЦ
session.updated                        (финальные tokens/cost)
session.diff
```

**Правило рендеринга:** `message.part.delta` — куски текста по полю `field`. Приходят для reasoning и text.
Части text/snapshot накапливаются (append). Финальные `part.updated` приходят со снапшотом целиком —
можно сверять/заменять.

**Два сигнала завершения** (оба наблюдались):
1. `message.part.updated` с part `step-finish` (reason: "stop", tokens/cost).
2. `session.idle` event.
Для fallback-пути (`POST /message`) ответ приходит целиком в теле — завершение = факт ответа 200.

## 3. Effort = поле `variant` (важнейшая находка)

В API нет поля `reasoning_effort`. Вместо него — **`variant` в теле запроса**:

```json
POST /session/:id/prompt_async
{
  "parts": [{"type":"text","text":"..."}],
  "model": {"providerID":"zai-coding-plan","modelID":"glm-5.3-flash"},
  "variant": "low"           // ← уровень reasoning effort
}
```

Список доступных вариантов — из `GET /config/providers` (поле `variants` у модели), человекопонятно:

| Модель | Provider | variants |
|---|---|---|
| glm-5.3-flash | zai-coding-plan, opencode-go | low, high, max |
| glm-5.3 | zai-coding-plan, opencode-go | low, high, max |
| glm-5.2 | zai-coding-plan, opencode-go | high, max |
| hy3-free | opencode | low, medium, high |
| deepseek-v4-flash | opencode-go | low, high, max |
| gpt-5.6-luna | opencode-go | none..max |
| **big-pickle** | **opencode** | **нет вариантов** (effort недоступен) |

Каждый вариант — тело `{"reasoningEffort":"low"}` (источник: packages/core/src/plugin/variant.ts:
для glm-5.2 генерит high/max; в конфиге провайдера можно объявить любые).

**Вывод для клиента:** кнопка effort — список вариантов активной модели из `/config/providers`:
- big-pickle → скрыть кнопку (нет вариантов), либо показать disabled.
- Универсальный маппинг: вариант выбирается из `variants` объекта модели; если пусто — effort не применим.
На сервер вариант передаётся как строка `variant`.

## 4. Сессии

- `POST /session` body `{"parentID"?,"title"?,"directory"?}` → 200 Session. (directory — рабочая папка сессии!)
- `GET /session` → массив (сортируем по time.updated desc).
- `GET /session/:id` → Session (model.variant виден после запроса).
- `GET /session/status` → `{ <sessionID>: {type:"busy"|"idle"} }`.
- `DELETE /session/:id` → true.
- `POST /session/:id/abort` → 200 true (проверено, работает).
- `PATCH /session/:id` body `{"title"?,"permission"?,"time":{"archived"?}}`.

Сессия фиксирует последние model+variant: после запроса variant сохраняется в session.model.variant.

## 5. Файлы / поиск

- `GET /file?path=<abs>` → FileNode[] {name, type: "file"|"directory", ~}. Windows-пути URL-encode (`%5C`).
- `GET /file/content?path=<abs>` → `{"type":"text","content":"..."}`.
- `GET /find?pattern=<re>&directory=<abs>` — **directory ОБЯЗАТЕЛЕН** (без него сканирует корень, таймаут).
  Формат: `[{path:{text}, lines:{text}, line_number, absolute_offset, submatches:[{match:{text},start,end}]}]`.
- `GET /find/file?query=&directory=&limit=` → string[] путей.
- `GET /find/symbol?query=&directory=` → Symbol[] (пусто для Kotlin-проекта без LSP).
- `GET /file/status` → git-статус (пусто в не-git).

## 6. Синтетические части (ВАЖНО для UI)

Сервер инжектит в user-сообщение части с `synthetic:true` (supermemory-контекст и т.п.).
Клиент: рендерить их как системные (серым/префиксом `⎇`), НЕ как текст юзера. Не отправлять обратно.

## 7. Коды и ошибки

- 204 — prompt_async принят. 200 — блокирующий message. 401 — неверный пароль.
- SSE: OkHttp EventSource, read timeout НЕ ставить меньше 60-90с (heartbeat ~30с спасает).
- 404 — сессия удалена; 400 — невалидный body (additionalProperties:false — лишние поля).

## 8. Fallback-режим (итоговый)

Не нужен polling! `POST /session/:id/message` блокирует до ответа (7.5s на короткий промпт).
Ресинк после разрыва: `GET /session/:id/message` — полная история сообщений с parts.
Таймаут блокирующего запроса: 60s+ (long-read).

## 9. Инфраструктура

- Боевой сервер юзера: `opencode serve --hostname 100.119.28.14 --port 4096` (Tailscale, для телефона).
- Спайк-сервер: `docs/start-spike-serve.ps1` — стартует из `C:\` (НЕ из проекта! конфликт инстансов) на 4097.
- Слушатель SSE: `docs/sse-listen.ps1` → `docs/sse-events.log`.
- Спека: `docs/openapi-spec.json`; типы: репо anomalyco/opencode `packages/sdk/js/src/gen/types.gen.ts`.