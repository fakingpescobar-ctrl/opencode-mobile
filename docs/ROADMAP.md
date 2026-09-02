# Дорожная карта: opencode-mobile → продукт для чужих телефонов

Три трека по честным хвостам. Каждый — шаги, файлы, критерий готовности.

Порядок внедрения: **Трек 1 (release) → Трек 2 (сеть) → Трек 3 (turbo)**.
Release-контур блокирует «дать человеку», сеть блокирует «мобильный opencode»,
турбо — перф.

---

## Трек 1 — Release-контур (самый важный, делаем первым)

**Боль:** debug-APK 392MB, пакет `.debug`, `isMinifyEnabled=false`.

### 1. Release-минифицирование с `.so` без поломки
- Включить `isMinifyEnabled=true` + `shrinkResources=true` в `release` блоке
  (`app/build.gradle.kts`, строчки ~20-26).
- **Критично:** `libopencode.so` и whisper `.so` лежат в `jniLibs` — R8 их не
  трогает (только dex), но проверить, что `packaging { jniLibs.useLegacyPackaging }`
  не слетел (execve из `nativeLibraryDir`).
- Добавить `keep`-правила для рефлексии (opencode serve пути) в
  `proguard-rules.pro`.
- Ожидание: `material-icons-extended` (2000+ иконок) R8 выкинет неиспользуемые
  → экономия ~20-30MB dex.

### 2. Честный размер модели
- ✅ **base вынесена из APK** в lazy-скачивание (как turbo). APK: 374 → 274 (R8) → **147 MB**.
- Реальный предел — ~147MB, т.к. `libopencode.so` (184 MB) — это сам opencode,
  вынести нельзя. Дальнейшие варианты:
  - **App Bundle / on-demand** (Play требует AAB) — ещё ужимает загрузку.
  - Опционально: **убрать `libbun-musl.so` (70MB)** если opencode может работать
    без bun-браузинга (рискованно, требует проверки запуска serve без bun).

### 3. Release-сборка и подпись
- `applicationIdSuffix` убрать из release (сейчас только debug имеет `.debug` —
  ок, release будет чистый `org.opencode.mobile`).
- Создать signing config (upload key), беймпинг `versionCode`/`versionName` (0.1.x).
- Собрать `assembleRelease`, прогнать smoke-тест на устройстве: запуск, чат, STT.

**Готово когда:** `assembleRelease` даёт APK/AAB ~100MB (или lazy ~70MB),
пакет чистый, всё работает под smoke-тестами на телефоне.

---

## Трек 2 — Сеть без ручного прокси

**Боль:** на Android нет `/etc/resolv.conf`, musl шлёт на `127.0.0.1:53`,
внешние сервисы не ходят без прокси.

**✅ Реализовано.** Встроенный `Ipv4Proxy` (CONNECT-туннель на `127.0.0.1:3128`,
IPv4-first через `InetAddress`/Netd) + `HTTPS_PROXY`/`HTTP_PROXY`/`NO_PROXY` в
`OpencodeRuntime.startServe`. Подтверждено на устройстве: туннели к
`mcp.context7.com`, `mcp.grep.app`, `registry.npmjs.org` открываются автоматически.

Остатки (опционально):
- Прогнать явный `webfetch`/`git` из чата на реальном телефоне как регресс-тест.
- `tools/connect_proxy.py` — legacy-помощник (автономный), можно удалить,
  приложение прокси встроено.

**Готово:** внешние вызовы opencode ходят в сеть без ручного прокси-конфига.

---

## Трек 3 — STT turbo на CPU

**Боль:** большие модели душатся до 1-5% (ColorOS/OPPO), turbo медленный.

### 6. ncnn-int8 для энкодера
- Следовать планам: `tools/ncnn-int8-plan.md` + `tools/ncnn-whisper-plan.md`.
  int8-квантование ncnn-энкодера убирает память/латентность.
- KV-cache уже внедрён — int8 сверху добавит.

### 7. Многопоточность / foreground
- `WhisperTranscribeService` уже держит приоритет. Оценить `setThreads` ncnn
  (1-2 ядра), тюнинг `VOICE_COMMUNICATION` vs `MIC` под железо.
- Бенч: base/turbo на int8, замерить WER и латентность на реальном OPPO.

**Готово когда:** turbo на int8 заметно быстрее (ориентир ≥2× латентности)
без критичного падения WER.

---

## Статус

| Трек | Статус |
|------|--------|
| 1. Release-контур | **В процессе** — R8 + shrinkResources включены (374 → 274 → **147 MB**), base вынесена в lazy, пакет чистый `org.opencode.mobile`, smoke OK. Осталось: upload-key подпись и опционально ужать `libopencode.so`/bun |
| 2. Сеть без прокси | **Выполнен** — встроенный `Ipv4Proxy` (CONNECT, IPv4-first); туннели к context7/grep/npm открываются автоматически. Остаётся удалить legacy `connect_proxy.py` |
| 3. STT turbo на CPU | Частично (KV-cache внедрён; int8 — план) |