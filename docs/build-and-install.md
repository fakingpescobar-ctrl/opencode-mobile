## Сборка

### Требования
- **Android Studio** (или Android SDK + JDK 17) с SDK Platform 36.
- ARM64-устройство (физический телефон) или arm64-эмулятор.
- Файл `local.properties` с путём к SDK (или переменная `ANDROID_HOME`) —
  не коммитится в git, в клоне его нет. Нужен только для локальной сборки.
- **Для релизной сборки PC не нужен.** GitHub Actions
  (`.github/workflows/release-apk.yml`) сам тянет нативы по пинам из
  `tools/natives.json`, собирает APK, проверяет, что ядро внутри, подписывает
  постоянным ключом и публикует релиз `apk-N`. Локальный PowerShell 7 —
  альтернатива для отладки, а не обязательное условие.

> ⚠️ **Собирать с `-SkipNative`, если нет whisper.cpp и VulkanSDK.** Нативка
> whisper собирается из исходников и требует внешних зависимостей, которых в
> репозитории нет; без флага сборка упадёт на конфигурации CMake. Подробности
> в «Про бинари» ниже.

### Сборка в GitHub — основной путь, без PC

1. **Запустить** `.github/workflows/release-apk.yml`: Actions → *Release APK* →
   *Run workflow* (или push тега `apk-*`). Push в `main` сам по себе релиз не
   публикует — workflow ждёт явного запуска или тега.
2. **Дождаться зелёного прогона.** Workflow проверяет наличие `libopencode.so`,
   `libbun-musl.so`, musl-библиотек внутри APK, размер больше 220 МБ, размеры
   ассетов плагинов и отпечаток подписи против эталона. Красный прогон релиз
   не публикует.
3. **Забрать APK** из релиза `apk-N` (или артефакта `apk-N`, хранится 30 дней).
   Имя вида `dist-opencode-mobile-N-<короткий-sha>.apk`; в заметках релиза —
   versionCode, ядро, отпечаток подписи и sha256 файла.
4. **Сверить перед установкой.** Агент на телефоне сверяет sha256 и размер
   скачанного файла с заметками, а установленный `base.apk` — с тем же хешем;
   путь берётся из `pm path`, а не заучивается (каталог меняется при каждой
   установке). Установку APK и включение службы доступности выполняет человек:
   из процесса `untrusted_app` эти действия недоступны.

Локальные шаги ниже остаются для отладки на Windows/PC.

### Локальная сборка (альтернатива)

0. **Скачать нативы** (в git их нет, см. «Про бинари» ниже):
   ```powershell
   pwsh -File tools\fetch-opencode-binary.ps1
   ```
   Без этого шага сборка пройдёт, а APK будет без сервера.
1. Открыть папку проекта в Android Studio, дождаться синка Gradle.
2. Собрать: `Build → Build APK(s)` или из терминала:
   ```bash
   ./gradlew :app:assembleDebug -PskipNativeBuild=true
   ```
   На Windows есть обёртка `build.ps1` (Android Studio JDK, offline, лог
   в `build-inst.log`):
   ```powershell
   pwsh -File build.ps1 -Task debug -SkipNative
   ```
3. APK появится в `app/build/outputs/apk/debug/opencode-debug-<дата>-<git-sha>[-dirty].apk` — в имени метка времени и хэш коммита, иначе установленные сборки не отличить друг от друга. Старые сборки не перезаписываются, поэтому имя берётся последним по времени:
   ```powershell
   Get-ChildItem app/build/outputs/apk/debug/*.apk | Sort-Object LastWriteTime -Desc | Select-Object -First 1
   ```
4. Установить:
   ```powershell
   adb install -r (Get-ChildItem app/build/outputs/apk/debug/opencode-debug-*.apk | Sort-Object LastWriteTime -Desc | Select-Object -First 1).FullName
   ```
   или просто скопировать APK на телефон и открыть.

### Про бинари (важно)

- `app/src/main/jniLibs/arm64-v8a/` — **в git НЕ лежит** (`.gitignore`), туда
  кладутся все нативы: `libopencode.so`, `libldmusl.so`, `libc_musl.so`,
  `libstdcxx.so`, `libgcc_s.so`, `libbun-musl.so`, `libsherpa-onnx-jni.so`,
  `libonnxruntime.so`. Из свежего клона рабочее
  приложение не соберётся, а **сборка при этом не упадёт**: Gradle просто не
  найдёт файла и соберёт APK без сервера — приложение стартует, opencode не
  запускается, и причина неочевидна.
- С 16.11 `libopencode.so` в APK — **резерв**, а не единственный источник:
  приложение может обновить ядро само и запускать копию из `filesDir/core/`
  (см. [«Обновление ядра»](../README.md#обновление-ядра-из-вшито-в-apk-в-качает-само)).
  Лоадер `libldmusl.so` и musl-libs всегда берутся из APK — скачать их взамен
  нельзя, они запускают ядро и грузят его зависимости.
- Поэтому **весь комплект** нативов ставится одной командой:
  ```powershell
  pwsh -File tools\fetch-opencode-binary.ps1           # скачать и положить
  pwsh -File tools\fetch-opencode-binary.ps1 -Check    # только проверить
  pwsh -File tools\fetch-opencode-binary.ps1 -Force    # скачать заново
  pwsh -File tools\fetch-opencode-binary.ps1 -SkipTts  # без sherpa/onnxruntime (46 МБ)
  .\build.ps1 -Task binary                             # то же через build.ps1
  ```
  Скрипт тянет все восемь файлов из четырёх публичных источников, каждый
  сверяет по размеру **и sha256** (размер у разных сборок совпадает, поэтому
  проверяется именно хеш), а в конце прогоняет ту же проверку, что и `-Check`.
  Источники:

  | файл | откуда |
  |---|---|
  | `libopencode.so` | npm `@opencode/cli-linux-arm64-musl` — **релизов на GitHub для 2.x не существует вообще**, официальный установщик бинарь не качает |
  | `libbun-musl.so` | npm `@oven/bun-linux-aarch64-musl` (не библиотека, а сам bun: ELF типа `ET_EXEC`) |
  | `libc_musl.so`, `libldmusl.so` | Alpine v3.21 musl — `ld-musl-aarch64.so.1` под двумя именами, побайтово один файл |
  | `libstdcxx.so`, `libgcc_s.so` | Alpine v3.21 GCC-рантайм (бинарь opencode **не статический**) |
  | `libonnxruntime.so` | GitHub-релиз sherpa-onnx 1.13.8, сборка `android-aarch64-termux-shared` |
  | `libsherpa-onnx-jni.so` | тот же релиз, но `sherpa-onnx-static-link-onnxruntime-1.13.8.aar` — **не** из `.tar.bz2`, там лежит другая сборка на 4.7 МБ |

  `-Check` дополнительно ищет в `libopencode.so` маркеры musl
  (`libstdc++.so.6`, `libgcc_s.so.1`), чтобы не принять windows-сборку или 1.x
  за 2.x. Рядом скрипт пишет `natives.json` — что откуда и с каким хешем.
- `debug` и `release` **проверяют комплектность и хеши нативов перед сборкой**
  и падают со списком того, чего не хватает или что не сходится. Обойти —
  `-SkipBinary`. Если офлайн-речь не нужна — `-SkipTts`: он убирает 46 МБ и
  проверку этих двух файлов тоже (иначе отказ от речи пришлось бы отменять
  `-SkipBinary`).
- Версия бинаря opencode живёт в `gradle.properties`, строкой `opencode.binaryVersion`.
  Это единственный источник правды: его читают скрипт загрузки, `app/build.gradle.kts`
  (`BuildConfig.OPENCODE_VERSION`, его показывает экран «О программе») и `build.ps1`.
  Смена версии = смена этой строки + прогон скрипта. Версии остальных нативов
  (bun 1.4.0, sherpa-onnx 1.13.8, Alpine v3.21) закреплены пинами прямо в скрипте.
- **whisper-нативка (`whisperlib`) из репозитория НЕ собирается.** Нужны
  внешние зависимости, которых в git нет: **whisper.cpp** (путь по умолчанию
  жёстко зашит как `C:/Projects/whisper.cpp`, менять через
  `-PwhisperCppDir=...`) и **VulkanSDK** от LunarG (`-PvulkanSdkDir=...`) плюс
  Visual Studio с «Desktop development with C++». Сборка сверяет коммит
  whisper.cpp с пинном из `whisperlib/src/main/jni/whisper/WHISPER_PIN` и
  падает при расхождении.
  ⇒ **Из свежего клона собирайте с `-SkipNative`** (то же делает CI — на
  ubuntu нет ни whisper.cpp, ни VulkanSDK). Распознавание речи при этом
  отключается, всё остальное работает.
  Проверено: `git clone` + скрипт нативов + `build.ps1 -Task debug -SkipNative`
  даёт рабочий APK, сервер отвечает, агент отвечает.
- Модель STT (`ggml-small.bin`) в assets **не хранится** — качается по требованию
  через `ModelDownloader` в `filesDir/models/`. Движок whisper.cpp (86 МБ) тоже
  качается отдельно, в `filesDir/engines/whisper/` — оба набора берутся из
  GitHub-релиза движков и проверяются по размеру и SHA-256.

---

## Установка готового APK

Установка:

> ⚠️ Два пакета сосуществуют: **release** (`org.opencode.mobile`, «OpenCode Mobile») и
> **debug** (`org.opencode.mobile.debug`, «OpenCode Mobile · Debug»). Отличаются по названию
> под значком.

> APK из GitHub Actions (`apk-N`) собирается как `assembleDebug` с постоянным
> ключом: это пакет `org.opencode.mobile.debug`, но подписан он не ключом
> раннера, а тем же сертификатом, что и предыдущие сборки. Сверяйте sha256 и
> отпечаток из заметок релиза — расхождение значит, что поверх не встанет.

- **Debug** («OpenCode Mobile · Debug»):
  ```powershell
  adb install -r (Get-ChildItem app/build/outputs/apk/debug/opencode-debug-*.apk | Sort-Object LastWriteTime -Desc | Select-Object -First 1).FullName
  adb shell monkey -p org.opencode.mobile.debug -c android.intent.category.LAUNCHER 1
  ```
- **Release** («OpenCode Mobile», R8-минифицирован, подписан release-ключом):
  ```bash
  # Один раз: создать ключ и заполнить шаблон (сам файл — секрет, в .gitignore).
  keytool -genkeypair -v -keystore ~/keys/opencode-release.jks -storetype PKCS12 \
          -keyalg RSA -keysize 4096 -validity 10000 -alias opencode-release
  cp keystore.properties.example keystore.properties   # вписать путь и пароли

  gradlew.bat :app:assembleRelease -x lint
  adb install -r (Get-ChildItem app/build/outputs/apk/release/opencode-release-*.apk | Sort-Object LastWriteTime -Desc | Select-Object -First 1).FullName
  adb shell monkey -p org.opencode.mobile -c android.intent.category.LAUNCHER 1
  ```

  **Ключ и пароли никогда не коммитятся.** `keystore.properties` и `*.jks`/`*.keystore`
  в `.gitignore`. Потерянный ключ = невозможность выпустить обновление: Play потребует
  нового приложения с другим `applicationId`. Держи бэкап ключа отдельно от репозитория.

  Если `keystore.properties` нет (например, на CI), release собирается с debug-подписью
  и печатает предупреждение `release-key НЕ НАЙДЕН ... публиковать её НЕЛЬЗЯ`. Такую
  сборку публиковать нельзя — она совместима только с уже установленным debug.
  Проверить, чем подписан APK:
  ```bash
  $ANDROID_HOME/build-tools/36.0.0/apksigner.bat verify --print-certs (Get-ChildItem app/build/outputs/apk/release/opencode-release-*.apk | Sort-Object LastWriteTime -Desc | Select-Object -First 1).FullName
  ```

### minVerify: проверить release-пайплайн на устройстве

Обычные device-тесты гоняют `debug`, где R8 выключен. Поэтому есть отдельный
build type `minVerify`: release с R8 и `shrinkResources`, но `debuggable` и с
суффиксом пакета — встаёт рядом с debug, ничего не ломая.

```bash
# обычный цикл разработки (быстро, без R8)
gradlew.bat connectedAndroidTest

# проверка того, что R8 не сломал STT
gradlew.bat -PsttTestBuildType=minVerify connectedMinVerifyAndroidTest
```

`testBuildType` переключается через `-P`, поэтому androidTest-вариант
существует только для одного build type за раз, и оба пути остаются
доступными.

Keep-правила — в `app/proguard-minverify-rules.pro`, на release они не
влияют. **Без них инструментация не запустится:** R8 оптимизирует по графу
вызовов приложения и не видит тестовый APK, поэтому выкидывает members,
которые вызывает только тест. Отсюда `-keep,allowoptimization` — имена нужны
для линковки теста, но оптимизация остаётся включённой, её мы и проверяем.

Перед выкладкой стоит убедиться, что JNI-класс уцелел при обфускации:
```bash
# Lcom/whispercpp/whisper/WhisperLib$Companion; должен ПРИСУТСТВОВАТЬ в classes.dex
```
Его спасает правило из дефолтного `proguard-android-optimize.txt`:
`-keepclasseswithmembernames,includedescriptorclasses class * { native <methods>; }`

Подробности — CHANGELOG, п. 18.

Логи смотреть:
```bash
adb logcat -s OpencodeRuntime OpencodeServerService OpencodeWebView VOICE RuntimeValidation ChatOverlay
```

> ⚠️ **После переустановки (uninstall → install)**: сессии/история/память на
> внешнем хранилище сохраняются, но **авторизация сбрасывается** — serve
> сгенерирует новый пароль, и движок с моделью STT нужно скачать заново
> (`filesDir` стирается). Проверка: «OpenCode server — Running», мониторинг
> чата работает с новым паролем автоматически.

---

