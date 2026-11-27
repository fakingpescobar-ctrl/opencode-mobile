# Полностью задокументированный ритуал сборки APK с телефона через GitHub API (без ПК)

## 1) Токен и файл, GitHub Contents API: редактирование файлов и коммит

- **Токен**: `/storage/emulated/0/Documents/ghtoken_mobile_agent.txt` — не публиковать (правило 43, 51 ХАРНЕС.md).
- **Авторизация API**: `Authorization: Bearer <TOKEN>`, `Accept: application/vnd.github+json`, `Content-Type: application/json`, `X-GitHub-Api-Version: 2022-11-28` (gh-commit-part104.mjs стр. 6-11, gh-push-many.mjs стр. 45-51).

### Эндпоинты и процедура редактирования файлов и коммита (gh-push-many.mjs):

1. **Получить голову (ref)**:
   `GET /repos/{REPO}/git/ref/heads/main` → получаем `object.sha` (голову коммита).

2. **Получить текущий коммит и дерево**:
   `GET /repos/{REPO}/git/commits/{headSha}` → получаем `tree.sha` (базовое дерево).

3. **Создаем блобы для правленных файлов**:
   `POST /git/blobs` с body `{ content: "<base64-байты>", encoding: "base64" }` → возвращает `sha` каждого блоба.

4. **Создаем новое дерево**:
   `POST /git/trees` с body `{ base_tree: "<базовое-дерево>", tree: [{ path, mode: "100644", type: "blob", sha: <блоб-ша> }, ...] }` → возвращает новое `tree.sha`.

5. **Создаем коммит**:
   `POST /git/commits` с body `{ message: "<сообщение>", tree: "<новое-дерево>", parents: [headSha] }` → возвращает `commit.sha`.

6. **Обновляем ref (ветку)**:
   `PATCH /git/refs/heads/main` с body `{ sha: "<новый-commit-sha>", force: false }`.

7. **Верификация после коммита** (автоматически в gh-push-many.mjs.lines 116-131):
   Для каждого файла делается `GET /repos/{REPO}/contents/{remote}?ref=main` с заголовком `Cache-Control: no-cache`.
   Сравнивается `sha256` байт файла на диске с `sha` из ответа API — должно быть `байт в байт`.

### Рабочий токен читается скриптами так (gh-commit-part104.mjs стр. 2-5):
```js
const TOKEN = (await Bun.file("/storage/emulated/0/Documents/ghtoken_mobile_agent.txt").text()).trim();
const H = { Authorization: "Bearer " + TOKEN, Accept: "application/vnd.github+json", ... };
```

### Эндпоинт запуска сборки (workflow_dispatch)
Основной workflow: `release-apk.yml` (MOBILE_AGENT/opencode/moltbook/gh-workflows/release-apk.yml, стр. 1-234).

- Триггер `workflow_dispatch` (стр. 13-18): можно передать `note` — "что в сборке пойдёт в заметки релиза".
- Триггер `push` с тегами `apk-*` (стр. 20): `tags: [ "apk-*" ]`.
- `permissions: contents: write` (стр. 23).

### Запуск сборки из телефона (gh-release-dispatch.mjs стр. 1-40):
```bash
gh-release-dispatch.mjs "<заметка>" [ref]
```
Делает `POST /repos/{REPO}/actions/workflows/release-apk.yml/dispatches` с headers из токена и body `{ ref, inputs: { note } }`.

### Ожидание завершения (gh-watch-run.mjs стр. 1-15):
Пolls `GET /repos/{REPO}/actions/runs/{RUN_ID}` каждые 25 секунд до 24 попыток. Ожидает `status === "completed"`. Выходит с кодом 0 при success, 2 при не-success, 3 если не завершился, 4 если прогон не найден.

### Номер версии (rules 43-44, release-apk.yml стр. 99-107):
- `versionCode = 4 + GITHUB_RUN_NUMBER` — пересчитывается на каждой сборке.
- `versionName = "0.1.0-b${GITHUB_RUN_NUMBER}"`.
- Пример: apk-65 → versionCode 65, run_number = 61 (4 + 61 = 65).

### Скачивание артефакта/APK (gh-release-download.mjs, правила 45):
`gh-release-download.mjs apk-NN <путь_в_Download>` — скачивает артефакт по частям, сверяет sha256 и размер с GitHub. При обрыве `ECONNRESET` есть `download-apk-parallel.sh` (хотя в журнале про apk-60 скачивали "кусками по 4 МБ через API-эндпоинт ассета").

## 2) Как запускается сборка: workflow_dispatch, inputs, ожидание и скачивание

### Триггер из телефона:
`gh-release-dispatch.mjs "<заметка>" [ref]` отправляет запрос на диспетчеризацию workflow_dispatch с указанием ref (по умолчанию `main`) и текстового комментария.

### Внутри workflow `release-apk.yml` (стр. 29-197):

**Jobs: `apk`** (стр. 30-33):
- `runs-on: ubuntu-latest`, timeout 60 min.
- `env: EXPECTED_CERT_SHA256: ""` (опциональный отпечаток ключа).

**Шаги:**
1. **Checkout** (action/checkout@v4).
2. **JDK 17** (setup-java).
3. **Gradle cache**.
4. **SDK platform 36 / build-tools**.
5. **Ядро и нативы по пинам** (tools/fetch-natives.sh).
6. **Ключ подписи** — ищет `secrets.ANDROID_DEBUG_KEYSTORE_BASE64`, иначе `signing/debug.keystore`, иначе fallback.
7. **versionCode/versionName из номера запуска** (стр. 99-107):
   ```bash
   code=$(( 4 + ${GITHUB_RUN_NUMBER} ))
   sed -i -E "s/^([[:space:]]*)versionCode = [0-9]+/\1versionCode = ${code}/" app/build.gradle.kts
   ```
8. **Сборка APK** (стр. 109-112):
   ```bash
   ./gradlew :app:assembleDebug -PskipNativeBuild=true --no-daemon --console=plain
   ```
9. **Проверка APK — ядро внутри** (стр. 114-145): проверяет размер (>220МБ), наличие `libopencode.so` и остальных нативов, сверяет ассеты плагинов (`assets/mcp/moltbook.js 334297`, `assets/mcp/memory.js 86023`) байт в байт с эталоном, выдает `sha256sum APK`.
10. **Отпечаток подписи** (стр. 147-182): проверяет, что APK подписан тем же ключом, что и хранится в `signing/`.
11. **Переименовать APK** (стр. 184-188): `opencode-mobile-${APK_VERSION_CODE}-${7-символов-GITHUB_SHA}.apk`.
12. **Upload artifact** (actions/upload-artifact@v4), хранение 30 дней.
13. **Релиз** (стр. 198-234): `gh release create "apk-${APK_VERSION_CODE}" "dist-${APK_NAME}" --notes-file notes.md`.

### Как дождаться и скачать артефакт/APK (из ЖУРНАЛ.md и скриптов):

- **Сверка sha/размера** (verify-install.sh, правило 303-307 ХАРНЕС.md):
  ```bash
  sh MOBILE_AGENT/opencode/opencode-cache/verify-install.sh <sha256> <size>
  ```
  Пишет `opencode/opencode-cache/install-etaron.json`. Результат `match=yes` или `match=no`.

- **Скачивание** (ЖУРНАЛ.md записи про apk-60):
  > "APK докачан кусками по 4 МБ через API-эндпоинт ассета (CDN отдавал 502/обрывы), sha подтверждён, подпись `196cdc94…` совпала."

- **Скачивание через скрипт** (правило 45):
  ```bash
  MOBILE_AGENT/opencode/opencode-cache/opencode/gh-release-download.mjs apk-NN <путь_в_Download>
  ```

- **Проверка статуса run** (правило 44, ЖУРНАЛ.md):
  > "Ждать `gh-watch-run.mjs <run_id>` до `completed/success`."

## 3) Как ставить APK на устройство, проверка версии apk-NN, где в ЖУРНАЛе фиксируется номер сборки

### Установка APK (правило 46 ХАРНЕС.md, ЖУРНАЛ.md записи):

**Не работает**:
- `pm install -r /storage/emulated/0/Download/...` — `system_server` не читает FUSE, `Error: Unable to open file`.
- `tools.mobile_tools.mobile_app_install` без параметров `path`, `sha256`, `size_bytes`.

**Верный путь** (правило 46):
```bash
tools.mobile_tools.mobile_app_install({action:"local", path:"<абсолютный_путь_в_Download>", sha256:"<хеш>", size_bytes:<размер>})
```
Результат: `state:"awaiting_user", requires_user_tap:true` — Макс (или пользователь) подтверждает системный диалог.

После тапа:
```bash
tools.mobile_tools.mobile_app_install_status({job_id: r.id})
```
Возвращает `state:"installed", "INSTALL_SUCCEEDED"`.

### Примеры из ЖУРНАЛ.md:

- **apk-60**: "Установлен через `mobile_app_install(action=local)` после подтверждения Макса."
- **apk-61**: "Release #57 собрал `dist-opencode-mobile-61-94ec726.apk` (sha ...) — APK докачан кусками, sha/подпись совпали, установлен."
- **apk-62**: "APK установлен через `mobile_app_install(action="local")", статус `installed`, версия `0.1.0-b58` (versionCode 62)."
- **apk-64**: "apk-64 установлен поверх битой apk-63 вручную, данные сохранились."
- **apk-65**: "Установили через `mobile_app_install(action=local)` после подтверждения Макса."

### Проверка версии сборки apk-NN (где в ЖУРНАЛе фиксируется):

- **verify-install.sh** (правило 303): Сверяет `sha256` и `size` установленного `base.apk` с GitHub. Вывод `match=yes/no`.
- **Живые проверки** (ЖУРНАЛ.md pro apk-65):
  > `:4096 → 200`, `tools/list` на `:4199` показывает новую схему, `memory_map` → 23 источника / 727 чанков, `memory_signal → s003`.
- **Номер сборки в ЖУРНАЛе** (правило 18, 48 ХАРНЕС.md):
  > Любое изменение в рантайме, плагине, правилах или конфиге **в тот же момент** дописывается в `MOBILE_AGENT/ЖУРНАЛ.md`: что изменено, чем доказано, что осталось. Затем обновить `MOBILE_AGENT/СОСТОЯНИЕ.md` (тема + пункт чеклиста).
  > Правило 51: "Память читать самому, без напоминания. В начале любой нетривиальной задачи и **до** того, как что-то искать инструментами, прочитать подряд: `MOBILE_AGENT/СОСТОЯНИЕ.md`..."

### Номер сборки фиксируется в ЖУРНАЛе так (примеры):

- `## 22.11 — apk-60` — заголовок записи.
- `## 22.11 — apk-61` — следующий заголовок.
- `## 22.11 — apk-62: витрина/доки и adaptive data-plane доставлены` — стр. 1440 ЖУРНАЛ.md.
- `## 22.11 — apk-64: починка контракта ассетов и защита от повтора` — стр. 1534.
- `## 22.11 — apk-65: схема `memory_signal` и приёмка после переустановки` — стр. 1621.
- Записи содержат: что сделано, результат, sha256 APK, versionCode, живые проверки.

## 4) Примеры прошлых циклов apk-60..apk-65 с командами/шагами

### apk-60 (стр. 1099-1131 ЖУРНАЛ.md):
- CI #44907368995 зелёный, Release #56 собрал `apk-60`.
- `dist-opencode-mobile-60-f195736.apk`, sha `504dee60…e4875`, размер 254 203 494 б.
- Скачан кусками по 4 МБ через API-эндпоинт ассета (CDN была 502/обрывы).
- Подпись `196cdc94…` совпала.
- Установлен через `mobile_app_install(action="local")`.
- Живые проверки: `:4096 → 200`, MCP `:4199/:4200/:4201` слушают.
- `files/mem/memory-index.mjs` sha `5aa8cc1f…4b78b7` и `memory-core.js` sha `f847a90e…bb70` совпали со staged-эталонами — v4-ядро доехало.
- Найден дефект: сброс `serve` не привёл к отчёту в Telegram (было исправлено в apk-61).
- Исправление (apk-61): отправка перенесена в `OpencodeServerService.onRuntimeState()`.

### apk-61 (стр. 1113-1131 ЖУРНАЛ.md):
- CI #44913262217 зелёный, Release #57 собрал `dist-opencode-mobile-61-94ec726.apk`.
- sha `887f02a664cf715866c90b7ca0e51ce5866d9216f76b436f010fbb7054074140`, 254 202 502 байт.
- APK докачан кусками, sha/подпись совпали, установлен.
- Живые проверки: `:4096 → 200`, MCP `:4199/:4200/:4201`, sha `memory-index.mjs 5aa8cc1f…` и `memory-core.js f847a90e…` совпали со staged.
- Финальный тест автоотчёта: `kill -TERM` процесса `serve` → в `shared_prefs/failure_reporter.xml` появились `install_id b609a380-ae41-4d1a-8bdc-5426b64459de`, `last_fingerprint SERVER_CRASHED|61|94ec726a`, `last_sent_at=1795274489565`; очередь `files/failures/` пуста.
- Макс подтвердил сообщение в группе Telegram.

### apk-62 (стр. 1444-1520 ЖУРНАЛ.md):
- After green CI on head `81c7689` (#44999808363) по явному подтверждению Макса запущен workflow `Release APK` (run #58).
- Собран `dist-opencode-mobile-62-81c7689.apk`: 254 215 373 байт, sha256 `68ac4084…`, подпись `196cdc94…` — совпало с notes релиза.
- СкачиваниеGoing in 4 MiB chunks through release-assets API with range 206 verification.
- Установлен через `mobile_app_install(action="local")`, статус `installed`, версия `0.1.0-b58` (versionCode 62).
- Живые ассеты `memory-adaptive-store.js` и `memory-gold.jsonl` байтно совпадают со staged.
- MCP `memory_map`/`memory_suggest` отвечают.
- Тесты на свечем runtime: 299/0 + 18/0 + 126/0 + GGUF 109/0.
- Осталось: гибрид research, Tier 3 не включаем; следующий Release — только по отдельному подтверждению.

### apk-63 (mentioned in apk-64 section, not a successful release):
- **Битой сборкой** — в `memory.js` импортировал `goldSha`, а попавший в APK `store` его не экспортировал. ESM падал на связывании модуля, поэтому `:4199` не поднимался.
- **Проблема**: `import { recordSignalCandidate, goldSha from "./memory-adaptive-store.js"}` в `memory.js` был, но в `memory-adaptive-store.js` не было экспорта `goldSha`.
- В apk-62 этой пары нет вовсе.
- Статическая проверка APK подтвердила: в `assets/mcp/memory.js:25` импорт есть, в `memory-adaptive-store.js` экспорта нет; в apk-62 этой пары нет вовсе.
- Была предпринята попытка ручного отката на apk-62.

### apk-64 (стр. 1534-1610 ЖУРНАЛ.md):
- **Проблема**: в apk-63 новый `memory.js` (`import { recordSignalCandidate, goldSha from "./memory-adaptive-store.js"}`) попал в APK вместе со старым ассетом store без экспорта `goldSha`. ESM падал на связывании модуля, поэтому `:4199` не поднимался.
- **Что сделано**: после ручного отката на apk-62 (`0.1.0-b58`) инструменты verified-live: `:4199 → 401 Bearer`, `mobile_list_apps` ok, `memory_index_stats` ok.
- Сверили `main` со staged: из 12 ассетов расходился ровно один — `memory-adaptive-store.js` (16090 б на `main`, 17884 б staged с `goldSha()`).
- Атомарным коммитом `8a7b490c30a3…` запушили два файла с байтной перечиткой:
  - `app/src/main/assets/mcp/memory-adaptive-store.js` (17884 б)
  - `app/src/test/java/org/opencode/mobile/server/McpAssetImportContractTest.kt` — добавлен тест «каждый именованный импорт имеет экспорт в целевом модуле».
- Раньше тест проверял только наличие файла, поэтому apk-63 проходил CI.
- Логику теста проверили отдельно: на `main` после пуша 61 импорт / 0 проблем, на ассетах apk-63 — ровно 1 проблема (`goldSha`).
- **Результат**: после пуша `main` перечитан заново — все 12 ассетов байтно равны staged.
- Release APK dispatch: run `45026424484`, sha `8a7b490c30…`.
- Релиз `apk-64` опубликован (run #60): ассет `dist-opencode-mobile-64-8a7b490.apk`, 254 219 465 б, sha256 `43655bb66ea7a31a302d74a721d527782e48d0c7c60ee906fbd960d14b0b8cbf`, версия 64, ядро 2.3.0, подпись `196cdc94…`.
- **Живая приёмка**: apk-64 установлен поверх битой apk-63 вручную, данные сохранились. `:4096 → 200`, `:4199` и `:4201` отвечают, `memory_map → 22 источника / 698 чанков`, `memory_suggest` работает. Вызов `memory_signal` с полями `query`, `origin: miss`, `expect_source` вернул `candidate: {ok:true, id:"s001", eligible:false}`.
- Статическая сверка APK: `memory.js 8f9c6a05…` и store `56669c0e…` байтно равны staged, `export function goldSha` присутствует.
- SOСТОЯНИЕ и README обновлены под apk-64.
- **Правило на будущее**: перед каждым Release сверять не только sha ассетов, но и импорт/экспорт контракт; staged-копии обновлять до запуска CI, а не после. Новый APK без отдельного подтверждения Макса не собирать.

### apk-65 (стр. 1621-1678 ЖУРНАЛ.md):
- **Вопрос Макса**: «после всех переделок и фиксов разве не надо пересобрать APK?»
- **Ответ**: большинство правок (доки, офлайн-инструменты, Kotlin-тест) в APK не попадают, но один рантайм-недочёт остался — `memory_signal` принимал додавочные поля, а `inputSchema` их не объявляла. Клиент по схеме мог их не послать.
- **Что сделано**: в `tmp/memory.js` дополнен `inputSchema` тула `memory_signal` полями `query`, `candidate_chunk_ids`, `expected_chunk_id`, `expect_source`, `expect_kind`, `origin` (enum `miss`/`control`). Обязательные поля, имя тула и логика записи кандидата не тронуты. Staged `tmp/apk-integrate/mcp/memory.js` перезаписан байтно (`01d25ce1…`), CJK-guard чистый.
- **Сборка и установка**: CI `45047909568` на `6e312ae7be76…` зелёный, Release run `45048668080` (#61), versionCode 65, ассет `dist-opencode-mobile-65-6e312ae.apk` (254 219 821 б, sha `6eb3272384a9ef7a4f4ac764d74cb0d74d49a32ec6578f89f4c46886b470dc55`, подпись `196cdc94…`). Скачали кусками по 4 МиБ через release-assets API с сверкой sha; внутри APK `memory.js = 01d25ce1…`, store = `56669c0e…`. Установили через `mobile_app_install(action=local)` после подтверждения Макса.
- **Живая приёмка**: `:4096 → 200`; `tools/list` на `:4199` показывает новую схему; `memory_map → 23 источника / 727 чанков`; `memory_signal → s003`. Токен `:4199` снова берём из окружения процесса serve (одноразовый при старте), `MCP_MEMORY_DIR` тот же. Пульс: 3 raw из 10. Тесты 299/0 + 38/0 + 39/0 + 131/0 + GGUF 109/0.
- **Результат**: apk-65 — активная сборка; apk-64 осталась предыдущей рабочей. P.5 прошлой записи пересмотрен: Release был нужен ради схемы тула. Дефолт ядра и контракт MCP-имен не менялись.

## 5) Правила обновления СОСТОЯНИЕ.md, ЖУРНАЛ.md, README.md после правки

### Правило 18 (ПРАВИЛА.md, стр. 72-78):
> Любое изменение в рантайме, плагине, правилах или конфиге **в тот же момент** дописывается в `MOBILE_AGENT/ЖУРНАЛ.md`: что изменено, чем доказано, что осталось. Затем обновить `MOBILE_AGENT/СОСТОЯНИЕ.md` (тема + пункт чеклиста).

### Правило 48 (ПРАВИЛА.md, стр. 309-313):
> **Память — сразу.** В тот же момент дописать в `MOBILE_AGENT/ЖУРНАЛ.md` (что изменено, чем доказано, что осталось), обновить `MOBILE_AGENT/СОСТОЯНИЕ.md` (версия, sha, ссылка на run) и `MOBILE_AGENT/ХАРНЕС.md` (если поменялись пути). Брифингов для PC-агента больше нет — пишем только в `MOBILE_AGENT/`.

### Правило 51 (ПРАВИЛА.md, стр. 328-337):
> **Память читать самому, без напоминания.** В начале любой нетривиальной задачи и **до** того, как что-то искать инструментами, прочитать подряд: `MOBILE_AGENT/СОСТОЯНИЕ.md` (текущее состояние), `MOBILE_AGENT/ПРАВИЛА.md` (эти правила), `MOBILE_AGENT/ЖУРНАЛ.md` (последние 2–3 записи), `MOBILE_AGENT/ХАРНЕС.md` (пути). Если вопрос про советы Moltbook или старые переделки — дополнительно `docs/MOLTBOOK_ADVICE.md` и `КАТАЛОГ_ПЕРЕДЕЛОК.md`. Ответ, уже записанный в памяти, не перевыводить заново. Если живое состояние расходится с памятью — верить живому, но тут же поправить память. Макс не должен говорить «посмотри в памяти»: это постоянная инструкция.

### Обновление README.md (фигурирует в текущем README.md, стр. 1-599):
- Активная сборка фиксируется на главной странице:
  > **Активная сборка: apk-65**, установлена и принята вживую: `versionCode 65`, ассет `dist-opencode-mobile-65-6e312ae.apk`, 254 219 821 байт, sha256 `6eb3272384a9ef7a4f4ac764d74cb0d74d49a32ec6578f89f4c46886b470dc55`, подпись `196cdc94…` (та же, что у apk-59…64 — пользовательские данные сохраняют).
- Структура этапов (таблица apk-12 → apk-65).
- Инфографика по локальным моделям.
- Правила обновления: когда правились доки (commit `87dfc708`), были использованы Contents API с байтной перечиткой: `локальный и удалённый sha f697b160c1ce8e7969c30084ca0c3df834ff66dea64af12ffa7c3a73fc1c5e5b, cmp — идентично`. Картинка `social-preview.png` в репозитории на месте.
- Важное примечание (стр. 58-59): "Полная хронология — MOBILE_AGENT/ЖУРНАЛ.md, текущее состояние — MOBILE_AGENT/СОСТОЯНИЕ.md".

### Обновление СОСТОЯНИЕ.md (стр. 1-599):
- Дата и версия: `Дата: 2026-11-22 (apk-65 установлен и проверен вживую; ...)`.
- **Текущий рубеж (22.11, apk-65)**: активная сборка, sha, versionCode, ассеты, head commit, versionCode 65.
- **Инфографика по локальным моделям**: добавлен `assets/llm-model-comparison.svg`.
- Что закрыто в цикле: реворк ядра памяти, метрики, живая сверка, самодиагностика, лимиты отчётов.
- Правило обновления (стр. 14-16): "Обязательный ритуал после каждой переделки: сразу обновлю этот файл и `ЖУРНАЛ.md`, а если менялась сборка или витрина проекта — ещё и `README.md`. Не откладывать «на потом»: пользователь именно на этом настаивал 21.11."

### Последовательность обновления (вывод из всех записей):

1. **Правка кода/конфига** → сразу в `ЖУРНАЛ.md` (что изменили, что доказали, что осталось).
2. **Обновить `СОСТОЯНИЕ.md`** (тема + чеклист пункта).
3. **Если менялась сборка/витрина** → обновить `README.md` (активная сборка sha, versionCode, ссылка на run).
4. **Git commit & push** через GitHub API (gh-push-many.mjs) с токеном из `ghtoken_mobile_agent.txt`.
5. **Запустить сборку** `gh-release-dispatch.mjs "<заметка>"`.
6. **Дождаться зеленого CI** `gh-watch-run.mjs <run_id>`.
7. **Скачать APK** `gh-release-download.mjs apk-NN <путь>` или частями через API.
8. **Установить APK** `tools.mobile_tools.mobile_app_install({action:"local", path, sha256, size_bytes})` с тапом в системном диалоге.
9. **Проверить установку** `verify-install.sh <sha256> <size>` → `match=yes`.
10. **Проживые проверки** на телефоне (MCP endpoints, asset shas).
11. **Завершить запись в ЖУРНАЛ.md** с результатами живых проверок.

### Пример из pratica (стр. 58-59 README.md):
> "На архитектурном рубеже (ещё не в APK): закрытое ядро для нового пользователя. Принцип — APK несёт код ядра и стартовый gold, а не наш operator-log. Память растёт из действий самого пользователя: `ingest` → `signal` → курация → `approve`. Обсуждаемые правки под отдельное подтверждение: `MCP_DOCS_DIR=""` в релизе, SQLite в app-private `filesDir`, версионированный export/import-бандл с hash. apk-65 не трогаем; apk-66 — только по отдельному слову Макса."

> "Последнее обновление витрины (22.11): полная структура из коммита `87dfc708` (34 251 б, 464 строки, 6 графиков, 93 строки таблиц) возвращена в `README.md` после того, как коммит `00f13b58` (`docs: vitriina i sborka debug apk`) удалил 453 строки и оставил голую страницу. Поверх добавлены apk-62…65, адаптивный data-plane, саморастущий gold, гибрид/RRF-эксперименты, закрытое ядро как архитектурный рубеж и Moltbook-тик. Итог: 41 255 б, 595 строк, **9 Mermaid-диаграмм**, 120 строк таблиц, CJK-чисто. Запушено через Contents API с байтной перечиткой: локальный и удалённый sha `f697b160c1ce8e7969c30084ca0c3df834ff66dea64af12ffa7c3a73fc1c5e5b`, `cmp` — идентично. Картинка `social-preview.png` (380 890 б) в репозитории на месте. Новый APK не собирался: правилась только витрина/доки."