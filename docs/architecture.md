## Техническое устройство (как это работает)

### Запуск opencode на Android без root

Android-ядро позволяет `execve` только с **PIE**-бинарём, и только из
`nativeLibraryDir` для `untrusted_app`. Поэтому:

| Файл (`app/src/main/jniLibs/arm64-v8a/`) | Роль |
|---|---|
| `libopencode.so` | Бинарь opencode **2.0.27** (musl-сборка arm64, 195.5 МБ, переименован в `lib*.so`) |
| `libldmusl.so` | musl-лоадер (это `ld-musl-aarch64.so.1`, он же libc) |
| `libstdcxx.so`, `libgcc_s.so` | **Alpine** GCC-рантайм под placeholder-именами (2.63 + 0.13 МБ) |
| `libc_musl.so` | musl libc под placeholder-именем |
| `libbun-musl.so` | musl-Bun — **JS-рантайм самого приложения** (`Retention.kt`, harness-скрипты), не opencode |

Ключевые моменты:

1. **`useLegacyPackaging = true`** в `app/build.gradle.kts` — заставляет
   `extractNativeLibs=true`. Без этого `.so` не извлекаются на диск (маппятся из
   APK для dlopen) и `execve` невозможен.
2. Бинарь — **динамический musl**: в нём `PT_INTERP=/lib/ld-musl-aarch64.so.1`,
   которого в системе нет. Поэтому лоадер запускается первым аргументом:
   `ld-musl ./libopencode.so serve --port 4096`.
3. **Имена `lib*.so`** — PackageManager извлекает только такие файлы; при старте
   зависимые либы копируются в `filesDir/musl` с правильными DT_NEEDED-именами,
   на них указывает `LD_LIBRARY_PATH`.
4. **Бинарь 2.x не статический.** Ему нужен GCC-рантайм именно Alpine
   v3.22 (`libstdc++.so.6.0.33`, `libgcc_s.so.1`) — поэтому в APK лежат они.
   Системные `libc++.so`/`libgcc_s.so` Android'а **не подходят**: они сами
   линкуются против bionic, и на устройстве это даёт
   `Error loading shared library libstdc++.so.6`, а с подменой на системные —
   нехватку `__register_atfork`, `_Unwind_*`, `__system_property_get`.
   Официальный установщик opencode 2.x этих библиотек не везёт: он рассчитан
   на хост Alpine.

### Локальная память MCP и Basic Auth

| Процесс | Порт | Роль |
|---|---|---|
| `libopencode.so serve` | `127.0.0.1:4096` | сам сервер opencode (HTTP API, Basic Auth) |
| `libbun-musl.so memory.js` | `127.0.0.1:4199` | MCP-сервер локальной памяти (streamable HTTP/SSE) |

- Модель видит память, только если serve **зарегистрировал** MCP-сервер.
  Регистрация — секция `"mcp": { "memory": { "type": "remote", "url": ... } }`
  в `$XDG_CONFIG_HOME/opencode/opencode.jsonc` (тот же формат, что на
  десктопе: `~/.config/opencode/opencode.json`). `OpencodeRuntime.ensureMcpConfig()`
  дописывает её идемпотентно (атомарно tmp+rename) до старта serve — фикс
  «0 MCP» (пустой конфиг, когда память слушала порт, но не была зарегистрирована).
- Индикатор «N MCP» в шапке чата = ответ `GET /api/mcp` с сервера (через
  `LocalOpenCodeClient` с авторизацией; без заголовков serve отвечает 401).
- Память хранится в `$XDG_CONFIG_HOME/opencode/memory/` (json-каталог прошлых
  сессий), переживает переустановку приложения.

### STT: foreground-сервис и GPU

На ColorOS/OPPO фоновые compute-потоки душатся до 1–5% CPU. Поэтому
распознавание выполняется в **foreground-сервисе** (`WhisperTranscribeService`) —
так ОС даёт процессу нормальный приоритет. Модель кэшируется в памяти между
вызовами (грузится один раз).

Сам whisper считает на **GPU** телефона: `GGML_VULKAN=ON`, `flash_attn=true`,
8 потоков. Модель `small` (465 МБ) на OnePlus CPH2747 / Adreno 840:

| бэкенд | проход | rtf | WER (7 клипов) |
|---|---|---|---|
| **GPU** | **~2 с** | 0,20–0,37 | **11,3 %** |
| CPU | ~4,8 с | 0,46–0,86 | 11,3 % |

Качественно GPU и CPU дают **одинаковый** результат, разница только в
скорости (×2,2). Полные таблицы по всем моделям и разбор выбора —
[docs/VOICE-REPORT.md](VOICE-REPORT.md).

Запись: `AudioRecord` → PCM16 16кГц → нормализация пика (телефоны пишут тихо) →
подкладка тишины (whisper врёт на очень коротких клипах) → распознавание →
текст в поле ввода.

---

