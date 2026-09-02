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
- `base` (141MB) вшита в APK. Варианты:
  - a. **App Bundle + on-demand** (Play требует AAB) — модель как отдельный asset.
  - b. **Lazy-загрузка**: base качается при первом запуске (как turbo через
    `ModelDownloader`), APK = только `libopencode.so` + ncnn + код → ~60-80MB.
- Рекомендация: **(b)** — первый запуск тянет base, потом кэшируется.
  `ModelDownloader` уже есть — расширить на base.

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
внешние сервисы не ходят.

### 4. Встроенный HTTP-прокси в приложение
- В `OpencodeServerService` поднять локальный прокси-слушатель (opencode на
  порту 4096; прокси — отдельный порт, например 8081).
- Задать opencode/musl env `HTTPS_PROXY=http://127.0.0.1:8081` + `ALL_PROXY`.
- Прокси гонит наружу через системный Android стек (Java/Kotlin
  `HttpURLConnection`/OkHttp — эф лузят Netd, `/etc/resolv.conf` не нужен).
- Набросок уже есть: `tools/connect_proxy.py` — довести до компонента в сервисе.

### 5. DNS решение
- Прокси резолвит через системный `InetAddress` — обходит проблему musl.
- Снимает хвост «DNS / исходящая сеть» из README (строчки ~215-218).

**Готово когда:** внешний `webfetch`/`git` через opencode ходит в сеть на
реальном телефоне без ручного прокси-конфига.

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
| 1. Release-контур | **В процессе** — R8 + shrinkResources включены (APK 374 → 274 MB, пакет чистый `org.opencode.mobile`, smoke OK). Осталось: lazy-загрузка base (~70-80MB) и upload-key подпись |
| 2. Сеть без прокси | Не начат (есть набросок `connect_proxy.py`) |
| 3. STT turbo на CPU | Частично (KV-cache внедрён; int8 — план) |