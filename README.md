# OpenCode Terminal

Рабочая директория на внешнем хранилище (Documents/OpencodeTerminal).

## Мобильный агент

Основная документация и состояние — в `MOBILE_AGENT/СОСТОЯНИЕ.md`,
хронология — в `MOBILE_AGENT/ЖУРНАЛ.md`. Контур саморастущего gold-корпуса
(сплиты, дедуп, карантин, квоты, пара `index_sha`/`gold_sha`, таблицы и
схемы) — в `MOBILE_AGENT/РОСТ_ЗОЛОТОГО_КОРПУСА.md`. Куратор
`MOBILE_AGENT/tools/curate-gold.mjs` и базовый прогон
`MOBILE_AGENT/tools/baseline.mjs` и пульс `MOBILE_AGENT/tools/signal-status.mjs`
работают только офлайн; гипотезы сравниваются внутри одной пары хэшей, дефолт
ядра остаётся legacy. Тул `memory_signal` теперь пишет кандидатов в data-plane
буфер (успех = `control`, провал = `miss`); с apk-64 это работает и вживую —
живой вызов вернул `candidate` с id и флагом `eligible`.

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

Активная сборка — `apk-64` (релиз `Release APK`, run #60, head `8a7b490`),
установлена 22.11, versionCode 64, ядро opencode 2.3.0. Приёмка: `:4096` → 200,
MCP `:4199/:4201` отвечают, `memory_map` видит 22 источника / 698 чанков,
`memory_signal` пишет живого кандидата в data-plane буфер. apk-63 был отбракован
из-за рассинхрона ассетов (`memory.js` импортировал несуществующий экспорт
`goldSha`), починено в `8a7b490` с усилением контрактного теста. CI собирает
Debug APK как проверку, но установимый APK с нативами даёт только
воркфлоу `Release APK`. Следующий Release — только по отдельному подтверждению
Макса.
