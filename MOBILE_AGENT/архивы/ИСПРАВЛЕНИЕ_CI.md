# Полный разбор: как полный CI стал зелёным (20.11)

## Старт

Пуш `566a2315` поднял полный workflow `CI` (run `44716822523`), и он упал на
шаге «Build (Kotlin compile) + линт». APK `apk-50` при этом собрался отдельным
workflow `Собрать APK с ядром` (run `44706492923`, success), так что установленная
сборка не пострадала. Было три независимых слоя предсуществующего долга: detekt,
ktlint и JVM-тесты. Каждый следующий слой проявлялся только после того, как
чинился предыдущий — Gradle останавливался на первой упавшей задаче.

## Слой 1 — detekt: 6 замечаний

Точный список из `tmp/ci-run-428.log`:

| Файл | Правило | Суть |
|---|---|---|
| `MemoryTools.kt:20` | `TooManyFunctions` | 11 функций, лимит 11 |
| `MemoryGraphView.kt:45` | `CyclomaticComplexMethod` | сложность 16 при лимите 15 |
| `MemoryCorePanel.kt:224` | `CyclomaticComplexMethod` | сложность 20 при лимите 15 |
| `OpencodeRuntime.kt:553` | `ReturnCount` | 3 return в `moltbookReportDir` |
| `DiagnosticsScreen.kt:1420` | `ReturnCount` | 3 return в `fmtBytes` |
| `MemoryGraphView.kt:85` | `LoopWithTooManyJumpStatements` | два `continue` в цикле рёбер |

Правка (`df17a9ab8e5c`): пороги не трогались. `moltbookReportDir` и `fmtBytes`
переписаны в одну точку выхода; цикл рёбер получил явную проверку `a != null &&
b != null` вместо `?: continue`; на трёх функциях оставлены `@Suppress` с
пояснением, почему ветвления — это состояния интерфейса, а не бизнес-логика.
detekt ушёл в 0 замечаний.

## Слой 2 — ktlint: 62 нарушения

Run `44719450158` показал, что detekt зелёный, но `:app:ktlintMainSourceSetCheck`
упал на 62 нарушения в 8 файлах (`MemoryGraphView` 20, `MemoryCorePanel` 16,
`MemoryTools` 11, `GgufModels` 6, `GgufStore` 5, `DiagnosticsScreen` 2,
`OpencodeRuntime` 1, `MemoryWire` 1). Все они предсуществующие; единственное
новое — моё тело-выражение в `fmtBytes`, исправленное в коде.

В `app/build.gradle.kts` прямо описан штатный путь: `ktlintGenerateBaseline`.
Поэтому 61 нарушение внесено в `app/config/ktlint/ktlint-baseline.xml`
(коммит `27bbc768c53c`), а не переписано «для красоты» в десятках чужих файлов.
Генератор — `tmp/detekt-fix/gen-baseline.mjs`, вход — отчёт из артефакта
`lint-reports` run'а `44719450158`. Baseline был 71 запись в 5 файлах, стал 132
записи в 13 файлах. Лог — `tmp/ci-run-430.log`, отчёт — `tmp/lint-430/`.

## Слой 3 — JVM-тесты: 3 падения

Run `44720767354`: сборка и линтеры прошли, `:app:testDebugUnitTest` дал
`1028 tests completed, 3 failed`:

- `GgufCatalogTest.rejectsBadHashUrlSizeAndDuplicates` — `row(id = "bad\"id")`
  давал битый JSON (`Expected a ',' or '}'`), вместо проверки валидации.
- `GgufCatalogTest.rejectsTraversalAndNonGguf` — `c:\x.gguf` в фикстуре давал
  невалидный JSON-escape `\x`.
- `McpAssetImportContractTest` — список разрешённых модулей знал только
  `./provenance.js`, а
  `memory.js` теперь тянет ещё четыре модуля сопроцессора:
  `./memory-core.js`, `./memory-index.mjs`, `./memory-adaptive.js`,
  `./memory-llm.js`. Все четыре реально лежат в `assets/mcp`.

Правка (`2989366f9151`): в фикстурах экранированы кавычка и бэкслэш так, чтобы
JSON был корректным и отклонение приходило от `GgufCatalog.parse`; allow-list
расширен до пяти модулей. Лог — `tmp/ci-run-431.log`.

## Результат

Run `44722134952` — `conclusion=success`, обе задачи зелёные:
`Assemble Debug APK` (16 шагов, 0 провалов) и `Native build (windows,
whisper.cpp + ncnn, CPU)` (12 шагов, 0 провалов).

Цепочка коммитов: `df17a9ab8e5c` → `27bbc768c53c` → `2989366f9151`.

## Что НЕ менялось

Ядро памяти, веса ранжирования, `MemoryMcp.toolCounts()`, `assets/mcp/*` и
модель LLM не тронуты. Все Kotlin-правки семантически нейтральны: это
перестановки return-точек и подавления линтера. Поэтому функционально
установленный `apk-50` совпадает с текущим исходником; вопрос пересборки APK
для формального совпадения источника и установленной сборки остаётся отдельным.
