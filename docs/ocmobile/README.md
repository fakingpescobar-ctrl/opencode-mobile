# Унаследованное из `ocmobile` (28.09.2026)

Папка `C:\Projects\ocmobile` — **не форк и не копия** этого проекта. Это отдельная
попытка сделать мобильный клиент opencode (Kotlin, модульная архитектура
`core/network` + `core/model` + `core/data` + `feature/terminal`), которая была
заброшена в пользу этого репозитория. Каталог удалён 28.09.2026, полный архив
исходников лежит в `archive-ocmobile-expo-20260927.zip`.

Сам Kotlin-код ocmobile **не переносился** — эта реализация функционально беднее
текущей (нет ncnn/Whisper, нет STT-бенчей, нет minVerify-контура). Но несколько
артефактов там были уникальными и полезными, они перенесены сюда.

## Что здесь и зачем

| Файл | Что это | Зачем нужно |
|---|---|---|
| `openapi-spec.json` | Полная OpenAPI-спецификация `opencode serve` (467 KB), снята с живого сервера v1.18.18 | Контракт API. Сверяться при правках `LocalOpenCodeClient` / SSE-клиента |
| `api-notes.md` | Рукописные заметки по API: `prompt_async` → 204 + SSE, блокирующий `POST /session/:id/message` как fallback, **`effort` = поле `variant`**, порядок SSE-событий, два сигнала завершения | Самое ценное. Неочевидные грабли, уже оплаченные экспериментом |
| `PLAN-ocmobile.md` | Дизайн- план клиента (248 строк): maximum primitive, две кнопки (модель + effort low/med/high) | Историческая справка по UX-идеям, которые ещё не реализованы здесь |
| `fixtures/*.json` | Реальные ответы сервера, снятые живьём: `agent.json` (88 KB), `command.json` (69 KB), `sessions-list.json` (63 KB), `config_providers.json` (31 KB), `file-content.json`, `find-text.json`, `session-messages.json` и др. | Фикстуры для юнит-тестов парсинга DTO и SSE без обращения к сети |
| `fixtures/sse-events.log.txt` | 63 KB живой SSE-поток событий (`/event`) | Эталон порядка и формы событий |

**Внимание:** фикстуры отражают API на 28.08.2026 (serve v1.18.18). При изменении
моделей DTO сверять сначала с `openapi-spec.json`, потом обновлять фикстуры.

Связанный инструмент — `tools/mobile-bridge/` (мост с ПК на `opencode serve`
внутри телефона через `adb forward`, порт 4096 — тот же, что у этого приложения).
