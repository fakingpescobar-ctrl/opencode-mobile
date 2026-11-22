# OpenCode Terminal

Рабочая директория на внешнем хранилище (Documents/OpencodeTerminal).

## Мобильный агент

Основная документация и состояние — в `MOBILE_AGENT/СОСТОЯНИЕ.md`,
хронология — в `MOBILE_AGENT/ЖУРНАЛ.md`. Контур саморастущего gold-корпуса
(сплиты, дедуп, карантин, квоты, пара `index_sha`/`gold_sha`, таблицы и
схемы) — в `MOBILE_AGENT/РОСТ_ЗОЛОТОГО_КОРПУСА.md`.

Ядро памяти остаётся детерминированным FTS5-слоем с legacy fallback по
умолчанию (`DEFAULT_MODE="legacy"`). Адаптивный слой
`MOBILE_AGENT/opencode/memory-core/memory-adaptive-store.js` реализован как
opt-in data-plane в app-private `filesDir` под hash-замком и не включается
автоматически. Исследования Hindsight (rerank/embed/decision/hybrid-gate)
живут в `MOBILE_AGENT/tools/llama-bench/` и в дефолт ядра не попадают.

### Проверки перед сборкой

```
sh MOBILE_AGENT/opencode/run-core-tests.sh
```

Ожидаемо: ядро, adaptive-store, интеграция и GGUF 109/0 — всё зелёное.
Staged-ассеты в `MOBILE_AGENT/tmp/apk-integrate/mcp/` должны байтно совпадать
с источниками в `MOBILE_AGENT/opencode/memory-core/`.

### Сборка

Активная сборка — `apk-62` (релиз `Release APK`, run #58, head `81c7689`),
установлена 22.11 через `mobile_app_install(action="local")`. CI собирает
Debug APK как проверку, но установимый APK с нативами даёт только
воркфлоу `Release APK`. Следующий Release — только по отдельному подтверждению
Макса.
