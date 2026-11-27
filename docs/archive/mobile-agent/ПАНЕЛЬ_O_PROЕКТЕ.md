# Панель «О проекте» — готовый текст

Автозаполнение через `PATCH /repos/fakingpescobar-ctrl/opencode-mobile` пробовалось
дважды: токен из `/storage/emulated/0/Documents/ghtoken_mobile_agent.txt` возвращает
`403 Resource not accessible by personal access token`. Права на метаданные
репозитория у этого токена нет. README уже обновлён коммитом `17191f24994b…`.
Ниже — значения для ручной вставки Максом на странице репозитория.

## Короткое описание (Description)

```
Детерминированное ядро памяти для opencode на Android: FTS5, резидентный LLM-сопроцессор, MCP, без root и внешних зависимостей.
```

## Ссылка на сайт (Website)

```
https://github.com/fakingpescobar-ctrl/opencode-mobile
```

## Темы (Topics)

```
opencode, android, memory, fts5, mcp, llm, offline, kotlin
```

## Что уже сделано автоматически

- `README.md` на главной — актуален для apk-54, размер 25 126 б,
  blob `92f18e57e98d…`, коммит `17191f24994b…` с `[skip ci]`.
- В таблицу этапов добавлены apk-53 (временны́е факты) и apk-54
  (детерминированный FTS), обновлён граф Mermaid и раздел про ядро.
- Повторная проверка после документального коммита: `index_sha`
  `64333190ebdf54ae` стабилен, `updated=0`, core **1.000/gate pass**,
  LLM `ready/resident`.

Осталось только вставить три поля выше в интерфейсе GitHub — это не требует
ни сборки, ни нового коммита.
