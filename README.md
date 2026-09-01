# OpenCode Mobile — full bundle (Вариант B)

Android-приложение, которое поднимает локальный сервер **opencode serve** прямо на
устройстве (без Termux, без root) и открывает его веб-интерфейс в WebView.
«Всё в один тап».

## Идея

- Внутри APK — standalone-бинарь `opencode-linux-arm64-musl` (bun `build --compile`).
- При запуске приложение стартует foreground-сервис, который exec-ит бинарь
  `serve --port 4096` и ждёт готовности по HTTP.
- WebView открывает `http://127.0.0.1:4096/`.
- Кнопки сверху — выбор модели и `reasoning_effort` (low/medium/high).

## Что это за файлы и почему

| Файл | Роль |
|---|---|
| `jniLibs/arm64-v8a/libopencode.so` | Бинарь opencode (переименован в `lib*.so`). |
| `jniLibs/arm64-v8a/libldmusl.so` | musl-лоадер (= `ld-musl-aarch64.so.1`, он же libc). |
| `jniLibs/arm64-v8a/libc_musl.so`, `libstdcxx.so`, `libgcc_s.so` | placeholder-имена musl-libs (чтобы PackageManager их извлёк). При старте копируются в `filesDir/musl` с правильными DT_NEEDED-именами. |

### Почему именно так (важно понимать, не ломать)

1. **`useLegacyPackaging = true`** в `app/build.gradle.kts` делает `extractNativeLibs=true`.
   Без этого `.so` НЕ извлекаются на диск (маппятся из APK для dlopen) и `execve`
   невозможен.
2. **Бинарь и лоадер — в `nativeLibraryDir`** — единственное место, откуда
   `untrusted_app` на Android 10+ (targetSdk≥29) может `exec-нуть` ELF.
3. **Имена `lib*.so`** — PackageManager извлекает только такие файлы.
4. Бинарь **динамический musl**: `PT_INTERP=/lib/ld-musl-aarch64.so.1` (в системе
   пути нет). Поэтому лоадер запускается ПЕРВЫМ аргументом:
   `ld-musl ./libopencode.so serve --port 4096` — musl-лоадер это умеет.
5. **Зависимые .so** кладутся в jniLibs под placeholder-именами (`libstdcxx.so` и т.п.,
   т.к. PackageManager извлекает только `lib*.so`, а DT_NEEDED-имена вида
   `libstdc++.so.6` с суффиксом не `.so`). При старте копируются в `filesDir/musl`
   с **правильными DT_NEEDED-именами**, на них указывает `LD_LIBRARY_PATH`.

### Известные ограничения (MVP)

- **DNS**: на Android нет `/etc/resolv.conf`; musl шлёт запросы на `127.0.0.1:53`,
  bind на который недоступен приложению. Для исходящей сети нужен локальный
  HTTP-прокси с env `HTTPS_PROXY` (см. TODO ниже). Пока сервер работает только
  локально (WebView), это не критично.
- **SELinux / dlopen из filesDir**: либы копируются в `filesDir/musl`, откуда
  musl-лоадер их маппит (`mmap PROT_EXEC`). Для современного `targetSdk` это
  МОЖЕТ быть ограничено политикой (`untrusted_app`). **Обязательно smoke-тест
  на реальном устройстве до развития проекта.** Если не взлетит — cross-fallback.
- **ForegroundServiceType `specialUse`**: сервер (`OpencodeServerService`) использует
  `specialUse` (+ `FOREGROUND_SERVICE_SPECIAL_USE` + `PROPERTY_SPECIAL_USE_FGS_SUBTYPE`),
  т.к. `dataSync` на Android 15 имеет суммарный лимит ~6ч/сутки и крашит долгоживущий
  сервер (`ForegroundServiceStartNotAllowedException: Time limit already exhausted`).
  Для side-loaded MVP допустимо; для Play-публикации — пройти ревью specialUse.
- **Сабпроцессы** opencode (`rg`, `git`, LSP) на устройстве отсутствуют — часть фич
  (`/find`, git-интеграция) не будет работать. Баскан.

## Сборка

Нужен **Android Studio** (или машина с Android SDK + JDK 17).

1. Открыть папку `C:\Projects\opencode-mobile` в Android Studio.
2. Дождаться синка Gradle (подтянет wrapper 8.9, SDK).
3. `Build → Build APK(s)` (или `./gradlew assembleDebug`).
4. ARM64-устройство (физический телефон) или arm64-эмулятор.

> Бинарь 193 МБ уже лежит в `jniLibs` — APK будет ~60–65 МБ (zip-сжатие),
> на устройстве ~200 МБ после распаковки. Это ожидаемо для полного бандла.

## Smoke-тест приложения

1. Установить APK: `adb install -r app/build/outputs/apk/debug/app-debug.apk`
2. Запустить: `adb shell am start -n org.opencode.mobile.debug/org.opencode.mobile.MainActivity`
3. В `logcat` смотреть: `adb logcat -s OpencodeRuntime OpencodeServerService`
4. Ожидаем статус `RUNNING` в UI и открытый интерфейс opencode.

## TODO / следующие шаги

- [x] Каркас проекта + runtime musl-запуск
- [x] Foreground-сервис + рестарт + статус
- [x] WebView-UI + кнопки model/effort (заглушка выбора)
- [x] Критик-ревью (3 раунда: REJECT→REJECT→**APPROVE**) — все compile-error и Gradle-блокеры закрыты
- [ ] Сгенерировать gradle wrapper (`gradle wrapper`) на машине с Android SDK
- [ ] Прокидывание выбранной модели/effort в opencode (через HTTP API, Spike P-1)
- [ ] Локальный HTTP-прокси для DNS/исходящей сети (use `netd` Android)
- [ ] Бандл git/ripgrep для полной функциональности opencode
- [ ] Автозапуск (BOOT_COMPLETED), отмена — безопасное выключение
- [ ] Проверка на устройстве с 16KB pages (p_align у бинаря = 65536, ок)

## Модели по умолчанию

Провайдеры: `opencode` (big-pickle), `zai-coding-plan` (glm-5.x), `zen` (big-pickle P3),
см. `~/.config/opencode` и глобальные AGENTS.md. В APK дефолт — `auto`.
