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
перестановки return-точек и подавления линтера.

## Приёмка apk-51

Пересборка всё же сделана для формального совпадения источника и сборки.

- Dispatch: run `44725146160`, `success`, коммит `ca0405dc37`.
- Релиз `apk-51`, ассет `dist-opencode-mobile-51-ca0405d.apk`,
  размер `254120818` б, sha `349905a5be53d9a2d07cfa4ffefaefd8c03f91bc28e97e01a2efcef89e1e671f`.
- Скачивание кусками (`tmp/download-apk-51-parallel.sh`), sha и размер совпали.
- Установка через системный диалог, job `5fd8e5f6…`, состояние `installed`.
- Установленный `base.apk`: размер и sha совпали с релизом, подпись та же.
- Smoke после установки: индекс `bc1b01c513a09094`, `memory_bench(core)` 1.000,
  gate `pass`, жёсткий gold 20/20, LLM `ready/resident`, модель `qwen3-0.6b-q4km`.

Данные приложения сохранились: LLM, индекс и память не пересоздавались.

## Корень дефекта `created` и apk-52

Обходной путь с выравниванием mtime работал, но требовал ручного ритуала после
каждой правки документа. Корень был в `memory-index.mjs`: при инкрементальной
переиндексации он писал `created = now` **всем** чанкам изменённого файла, хотя
id чанка включает source, номер строки и sha текста — то есть неизменившиеся
чанки легко отличить. Теперь перед удалением старых чанков их `created`
сохраняется в карту по id и подставляется обратно для совпавших id; свежее
время получают только реально новые чанки.

- Регресс-тест 2b в `memory-core.test.mjs`: после правки файла старые чанки
  сохраняют `created`, новый получает свежее время. Тесты **138/138 + 98/98 +
  109/109**, свежий жёсткий gold 20/20.
- Коммит `86d31367839b`; CI run `44728604174` success, сборка
  `44728621598` success.
- Релиз `apk-52`, ассет `dist-opencode-mobile-52-86d3136.apk`,
  `254121222` б, sha `7fe112f1a753efdb849e917599273b7a56c06b2e32d0924adce12440fee88f6e`.
  Скачан кусками, sha/размер совпали, установлен, `base.apk` совпал с релизом.
- Проверка вживую: после установки изменил шапку `СОСТОЯНИЕ.md` и вызвал
  переиндексацию **без** `touch-docs-real.mjs`. `updated=1`, recall@5 1.000,
  gate `pass`, живой gold 20/20 — раньше именно здесь вылетал g17.

Шапку `СОСТОЯНИЕ.md` наконец обновили до apk-52: дефект устранён, риск сдвига
`created` ушёл. Процедура с `touch-docs-real.mjs` остаётся в харнесе как
запасная, но штатно больше не нужна.
