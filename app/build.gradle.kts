import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jlleitschuh.gradle.ktlint")
    id("io.gitlab.arturbosch.detekt")
}

/**
 * Релизная подпись. Ключ и пароли лежат в `keystore.properties` в корне репозитория
 * (файл в `.gitignore`), сам `.jks` - вообще вне репозитория. Шаблон - `keystore.properties.example`.
 *
 * Если файла нет, release собирается с debug-подписью, чтобы `./gradlew assembleRelease` и
 * локальный smoke не падали у людей без ключа. Такая сборка публиковать нельзя, поэтому
 * здесь она помечается, а не проходит молча.
 */
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) {
        keystorePropsFile.inputStream().use { load(it) }
    }
}
val releaseStoreFile = keystoreProps.getProperty("storeFile")
val hasReleaseKey = releaseStoreFile != null && file(releaseStoreFile).exists()

// ---- Штамп сборки ----
//
// Зачем: «О программе» показывал всегда `0.1.0 (code 1)`, то есть на телефоне
// невозможно было понять, КАКАЯ сборка стоит. Сравнивать приходилось по
// sha256 APK, а это не то же самое, что «что я собрал» — sha256 меняется от
// пересборки без правки исходников (время в подписи, порядок ресурсов).
// Пользователю нужен ответ на вопрос «откуда эта сборка», а не «как называется
// файл», поэтому в BuildConfig кладём ИМЕННО ТО, из чего собрали: хэш
// дерева-источника и время сборки.
//
// Оба значения считаются ОДИН РАЗ на конфигурирование, и это обязательно, а не
// для скорости: если читать git на каждый task, то `--offline`/повторный
// запуск дадут другое время, и Gradle пересоберёт BuildConfig, а с ним и весь
// APK, даже когда исходники не менялись. Считаем на старте сессии — тогда
// повторный запуск на неизменённом дереве даёт тот же BuildConfig и сборка
// остаётся up-to-date.
val gitSha: String =
    runCatching {
        val proc = ProcessBuilder("git", "rev-parse", "--short=8", "HEAD")
            .directory(rootProject.projectDir)
            .redirectErrorStream(true)
            .start()
        val out = proc.inputStream.bufferedReader().use { it.readText() }.trim()
        if (proc.waitFor() != 0) throw IllegalStateException("git rev-parse -> $out")
        out
    }.getOrElse { "nogit" }

// Локальный git-репозиторий может быть с изменениями в рабочем дереве - тогда
// короткий хэш врёт (он указывает на последний коммит, а не на то, что
// собрано). Считаем сами файлы и помечаем, если дерево грязное.
//
// Неотслеживаемые файлы ТОЖЕ считаются грязными, и это не педантизм: новый
// .kt в app/src/main попадает в APK, но при --untracked-files=no он не виден
// - и метка сборки врала бы ровно в том случае, ради которого добавлена.
val gitStatus: String =
    runCatching {
        val proc = ProcessBuilder("git", "status", "--porcelain")
            .directory(rootProject.projectDir)
            .redirectErrorStream(true)
            .start()
        val out = proc.inputStream.bufferedReader().use { it.readText() }.trim()
        if (proc.waitFor() != 0) throw IllegalStateException("git status -> $out")
        out
    }.getOrElse { "" }

val gitDirty: Boolean = gitStatus.isNotEmpty()

// Хэш содержимого правок, а не «грязно/чисто». Булева было бы мало: дерево с
// тремя правками в 10:00 и с тремя ДРУГИМИ правками в 14:00 даёт один и тот же
// `dirty=true`, метка совпала бы, и вторая сборка байт-в-байт перезаписала бы
// первую - то есть ровно то, ради чего имя APK стало датой и хэшем, снова
// перестало бы работать.
//
// `git diff HEAD` смотрит только на ОТСЛЕЖИВАЕМЫЕ файлы, поэтому добавление нового
// .kt в app/src/main в него не попадает вообще: такой файл меняет содержимое APK,
// а хэш остаётся прежним. Значит имя не меняется и новая сборка затирает старую.
// Поэтому untracked добавляются вручную: список имён плюс содержимое, но с
// потолком на файл - в untracked легко случайно оказывается дамп или архив, а
// читать сотни мегабайт на каждом запуске Gradle нельзя. Файл крупнее потолка
// вклад даёт по имени и длине: этого хватает, чтобы отличить одно дерево от
// другого, и не стоит ничего.
val UNTRACKED_FILE_LIMIT = 1024L * 1024L
val treeHash: String = runCatching {
    val md = MessageDigest.getInstance("MD5")
    val proc = ProcessBuilder("git", "diff", "HEAD")
        .directory(rootProject.projectDir)
        .redirectErrorStream(true)
        .start()
    val buf = ByteArray(64 * 1024)
    while (true) {
        val n = proc.inputStream.read(buf)
        if (n <= 0) break
        md.update(buf, 0, n)
    }
    proc.waitFor()
    md.update(gitStatus.toByteArray())
    val untracked = ProcessBuilder("git", "ls-files", "--others", "--exclude-standard")
        .directory(rootProject.projectDir)
        .redirectErrorStream(true)
        .start()
    untracked.inputStream.bufferedReader().use { reader ->
        while (true) {
            val rel = reader.readLine() ?: break
            if (rel.isBlank()) continue
            val f = rootProject.file(rel)
            md.update(rel.toByteArray())
            if (!f.isFile) {
                md.update("dir".toByteArray())
            } else if (f.length() > UNTRACKED_FILE_LIMIT) {
                md.update("big:${f.length()}".toByteArray())
            } else {
                md.update(f.readBytes())
            }
        }
    }
    untracked.waitFor()
    md.digest().joinToString("") { "%02x".format(it) }.take(8)
}.getOrElse { "nohash" }

// Время в UTC +5 (Алматы) без секунд: точность до минуты достаточна, чтобы
// отличить две сборки в один день, а разница с UTC-меткой в телефоне не сбивает.
// Формат ISO-подобный и сортируемый: `2026-10-10T22:13+05:00`.
//
// ВАЖНО, метка кешируется, а не пересчитывается на каждом запуске. `Instant.now()`
// здесь означал бы, что BuildConfig.BUILD_STAMP меняется при ЛЮБОМ запуске
// Gradle: задача generateBuildConfig видела бы новое значение, пересобирала
// файл, и следом пересобирался весь APK - up-to-date не срабатывал бы никогда.
// Комментарий выше про «считаем один раз на сессию» был ровно про это свойство,
// но сам `Instant.now()` ему противоречил.
//
// Ключ кеша - пара (git sha, хэш содержимого правок). Пока коммит и содержимое
// дерева те же, метка та же, и пересборка честно остаётся no-op. Изменился любой
// байт исходников - метка новая: тогда содержимое сборки действительно другое,
// и имя APK обязано отличаться, иначе история сборок снова схлопнется в один файл.
// Файл лежит в build/, который в .gitignore и который `gradlew clean` сносит -
// после чистой сборки метка будет новой, и это верно: пересобрано с нуля.
//
// Обойти кеш вручную (пересобрать то же дерево под новой меткой):
//   .\gradlew.bat -PforceBuildStamp=true :app:assembleDebug
val stampCache = rootProject.file("build/build-stamp.txt")
val forceStamp = (findProperty("forceBuildStamp") as String?)?.toBoolean() == true
val stampKey = "$gitSha/$treeHash"
val nowStamp = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mmxxx")
    .withZone(ZoneId.of("Asia/Almaty"))
    .format(Instant.now())
val buildStamp: String = run {
    val cached = runCatching { stampCache.readText().trim() }.getOrNull()
    if (!forceStamp && cached != null && cached.startsWith("$stampKey ")) {
        cached.removePrefix("$stampKey ")
    } else {
        // Отказ записи кеша НЕ молчим: иначе каждая следующая сборка получит
        // новую метку, generateDebugBuildConfig перезапишется, и весь APK будет
        // пересобираться при каждом запуске - ровно та деградация, ради которой
        // кеш и заведён. Сообщение в консоли позволяет понять, что происходит.
        runCatching {
            stampCache.parentFile.mkdirs()
            stampCache.writeText("$stampKey $nowStamp")
        }.onFailure {
            logger.warn(
                "build-stamp cache is not writable (${it.message}); " +
                    "the build will not stay up-to-date between runs",
            )
        }
        nowStamp
    }
}

android {
    namespace = "org.opencode.mobile"
    compileSdk = 36

    // AGP создаёт androidTest-вариант ТОЛЬКО для одного build type - того, что
    // указан здесь. Дефолт - "debug", а в debug минификации нет вообще, то есть
    // ВСЕ device-тесты проекта проверяли сборку без R8. Release-пайплайн на
    // устройстве не запускался ни разу, и обфускация могла сломать что-то,
    // что видно только в момент выполнения.
    //
    // Сделано переключаемым, чтобы не сломать обычный цикл разработки:
    //   ./gradlew connectedAndroidTest
    //   ./gradlew -PsttTestBuildType=minVerify connectedMinVerifyAndroidTest
    //
    // ВАЖНО, 27.09: connectedMinVerifyAndroidTest НЕ подходит для STT, хотя
    // Gradle рапортует результат зелёным. Две причины сразу:
    //   1. Gradle переустанавливает APK, а `adb install -r` на ColorOS стирает
    //      data-директорию пакета вместе с моделями из files/models.
    //   2. У minVerify свой applicationIdSuffix=".minverify" => свой пустой
    //      data-dir, а модели залиты в org.opencode.mobile.debug.
    // Тесты упираются в assumeTrue и дают ЗЕЛЁНЫЙ, но пустой результат.
    //
    // Проверялось так: в XML было skipped="5" time="0.469" при реальном
    // времени теста 517 с. "5/5 за 0.5 с" - это не результат. Отдельно ловушка:
    // "OK (5 tests)" am instrument печатает и для ПРОПУЩЕННЫХ тестов, поэтому
    // зелёный код возврата не доказывает, что хоть что-то исполнялось.
    //
    // РЕАЛЬНЫЙ прогон R8-варианта (install APK -> доставить модели -> am instrument):
    //   pwsh -ExecutionPolicy Bypass -File run_stt_bench_minverify.ps1
    // Скрипт сам сверяет, что строки бенча реально появились, а не были отброшены.
    // Проверено 27.09: 10 BENCH_ROW + 2 LANG_ROW на минифицированной сборке.
    //
    // Основным путём остаётся connectedDebugAndroidTest.
    testBuildType = providers.gradleProperty("sttTestBuildType").getOrElse("debug")

    defaultConfig {
        applicationId = "org.opencode.mobile"
        minSdk = 28
        targetSdk = 36
        versionCode = 3
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Штамп сборки в BuildConfig: `BuildConfig.GIT_SHA`, `BUILD_STAMP`,
        // `GIT_DIRTY`. Читает их DiagnosticsScreen («О программе»).
        // Значения - константы времени компиляции, поэтому обычные строки.
        buildConfigField("String", "GIT_SHA", "\"$gitSha\"")
        buildConfigField("String", "BUILD_STAMP", "\"$buildStamp\"")
        buildConfigField("boolean", "GIT_DIRTY", "$gitDirty")
    }

    signingConfigs {
        if (hasReleaseKey) {
            create("release") {
                storeFile = file(releaseStoreFile!!)
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = if (hasReleaseKey) {
                signingConfigs.getByName("release")
            } else {
                logger.warn(
                    "release-key НЕ НАЙДЕН (нет keystore.properties или файла ключа) - " +
                        "сборка подписывается debug-ключом и публиковать её НЕЛЬЗЯ. " +
                        "См. keystore.properties.example.",
                )
                signingConfigs.getByName("debug")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            // logcat-friendly
            applicationIdSuffix = ".debug"
            // Отличительное имя в UI, чтобы debug-сборка не путалась с release.
            resValue("string", "app_name", "OpenCode Mobile · Debug")
        }

        // minVerify: release-пайплайн, но запускаемый на устройстве.
        //
        // Нужен потому, что connectedAndroidTest по умолчанию гоняет debug, где
        // R8 выключен. Значит release можно было собрать, подписать и выкатить
        // в Play, ни разу не запустив на телефоне.
        //
        // От release отличается тремя вещами, и все три осознанные:
        //   applicationIdSuffix   - ставится отдельным пакетом рядом с debug
        //                             и release, ничего не ломая
        //   signingConfig = debug  - initWith копирует и ключ release'а, а
        //                             подписывать отладочный артефакт боевым
        //                             ключом незачем: он ставится на телефон и
        //                             может утечь вместе с телефоном
        //   isDebuggable = false  - см. блок предупреждения ниже, это не
        //                             косметика, а условие работоспособности
        // R8, shrinkResources и правила из proguard-rules.pro - как в release.
        //
        // ВАЖНО, 27.09: isDebuggable здесь БЫЛО true, и это молча убивало всю
        // проверку. AGP отключает optimization и obfuscation для debuggable
        // сборок, поэтому при isDebuggable=true R8 НЕ ЗАПУСКАЕТСЯ ВООБЩЕ - при
        // том, что isMinifyEnabled=true унаследован от release. Gradle при этом
        // лишь пишет warning, а сборка остаётся зелёной.
        //
        // Доказано по артефакту, а не по флагу: в APK не оказалось НИ ОДНОГО
        // однобуквенного обфусцированного пакета, а
        // Lcom/whispercpp/whisper/NcnnWhisperLib; лежал с полным именем, как и
        // org/opencode/mobile/stt/WhisperTranscribeService. Прежнее утверждение
        // в этом комментарии "R8 (13 dex -> 2)" было неверным: два dex не
        // доказывают минификацию, а полные имена классов её опровергают.
        //
        // Последствие было не косметическим: release уходит к пользователям с
        // теми же правилами и с R8, а правила из
        // proguard-minverify-rules.pro из-за этого НИ РАЗУ не проверялись ни на
        // чём. Именно эту дыру и закрывает isDebuggable=false.
        //
        // Побочный эффект: run-as при isDebuggable=false не работает, поэтому
        // модели в пакет заливает сам тест (androidTest/SttModelBootstrap.kt)
        // из /data/local/tmp, а не скрипт снаружи. На /sdcard run-as и так не
        // смотрит - FUSE на ColorOS.
        create("minVerify") {
            initWith(getByName("release"))
            applicationIdSuffix = ".minverify"
            isDebuggable = false
            signingConfig = signingConfigs.getByName("debug")
            resValue("string", "app_name", "OpenCode Mobile · MinVerify")
            // whisperlib не имеет build type minVerify - без этого падает
            // variant matching (просит minVerifyApiElements, а библиотека
            // публикует только debug* и release*).
            matchingFallbacks.add("release")
            // Файлы перечислены явно, а не только дополнение. proguardFiles
            // дописывает в список, но полагаться на это не хочется: если
            // proguard-android-optimize.txt исчезнет из списка, сборка
            // останется зелёной, а сломается только в момент запуска - JNI ищет
            // символы по имени, и обфусцированный NcnnWhisperLib не найдётся.
            //
            // Прежняя пометка "Проверено по dex: Lcom/...присутствует" как
            // доказательство работы keep-правила была пустой: при
            // isDebuggable=true R8 не запускался, поэтому полное имя там
            // лежало в любом случае, независимо от правил. Теперь, когда
            // isDebuggable=false, та же проверка становится настоящей.
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
                "proguard-minverify-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    // Без этого android.jar в unit-тестах кидает RuntimeException("Stub!") на любой
    // реальный вызов Android-API. Нужно для тестов WhisperTranscribeService: там
    // инициализатор компаньона зовёт Handler(Looper.getMainLooper()) ещё до входа
    // в тестируемый код. Методы-заглушки начинают возвращать дефолты вместо throw.
    testOptions {
        unitTests {
            isReturnDefaultValues = true
        }
    }

    buildFeatures {
        compose = true
        // BuildConfig (для BuildConfig.DEBUG: WebView remote debugging только в debug).
        buildConfig = true
    }

    packaging {
        jniLibs {
            // КРИТИЧНО: бинарь opencode запускается через execve из nativeLibraryDir.
            // При extractNativeLibs=false (совр. дефолт AGP) .so НЕ извлекаются на диск
            // (маппятся из APK для dlopen) -> execve невозможен.
            // useLegacyPackaging=true => android:extractNativeLibs="true" => файлы
            // распаковываются при установке в /data/app/.../lib/arm64/.
            // Это ЕДИНСТВЕННОЕ место, откуда untrusted_app может exec-нуть ELF.
            useLegacyPackaging = true
        }
    }

    // Подкладываем готовые бинари (musl-сборка opencode + зависимые libc/libstdc++/libgcc)
    // Кладутся в nativeLibraryDir. Имена строго lib*.so.
    sourceSets["main"].jniLibs.srcDirs("src/main/jniLibs")

    // Имя APK = дата-время + короткий git-хэш, чтобы каталог outputs сам отвечал
    // на вопрос «какая сборка это». Раньше все сборки назывались app-debug.apk и
    // перезаписывали друг друга: в истории оставалось одно имя и ноль истории.
    applicationVariants.all {
        // Имя варианта читаем ЗДЕСЬ и сохраняем: внутри outputs.all та же метка
        // `all` уже занята внешним блоком, и обращение к variant изнутри
        // нерезолвится (Kotlin DSL путает receiver'ы вложенных all).
        val variantName = buildType.name
        outputs.forEach { outputSpec ->
            // В AGP 8 `VariantOutput` не даёт setter имени файла напрямую -
            // он есть только на внутреннем BaseVariantOutputImpl. Если вдруг
            // реализация сменится, оставляем дефолтное имя и сборка не падает.
            val output = outputSpec as? com.android.build.gradle.internal.api.BaseVariantOutputImpl
            if (output != null) {
                val stampPart = buildStamp.replace(':', '-').replace('T', '_')
                val dirtyPart = if (gitDirty) "-dirty" else ""
                output.outputFileName =
                    "opencode-$variantName-$stampPart-$gitSha$dirtyPart.apk"
            }
        }
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2026.01.00"))
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    // Готовая векторная иконка палитры (Icons.Filled.Palette) для кнопки выбора
    // цвета текста. Library прогнана R8/minify в release, деб-APK чуть больше — ок.
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.4")
    // Транспорт до сессий, публикуемых по протоколу media3 (androidx.media3.session.*):
    // Яндекс Музыка, YouTube Music и другие современные плееры. Платформенный
    // MediaBrowser к ним не подключается — это другой Binder, нужен свой клиент.
    implementation("androidx.media3:media3-session:1.5.1")
    implementation(project(":whisperlib"))
    // Терминальный эмулятор для TUI opencode (Termux lib, не приложение):
    // рендер ANSI-вывода + PTY-управление.
    implementation("com.github.termux.termux-app:terminal-view:v0.118.3")
    debugImplementation("androidx.compose.ui:ui-tooling")
    // Instrumented smoke-тест нативного слоя (PR3): nativeInit -> nativeTranscribe.
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    // JVM-тесты (ЭКСП-5): чистый Kotlin — SpeechSegmenter без устройства.
    testImplementation("junit:junit:4.13.2")
    // Моки Android-поверхности для unit-тестов WhisperTranscribeService:
    // android.jar в JVM-тестах — заглушки, поэтому нужен и mockk, и
    // returnDefaultValues ниже (иначе Handler/Looper кидают "Stub!").
    // testImplementation — в APK не попадает.
    testImplementation("io.mockk:mockk:1.13.13")
    // org.json из android.jar на JVM не имеет рабочей реализации.
    testImplementation("org.json:json:20250517")
}

// ---- Линтеры (ktlint: формат/аккуратность; detekt: качество/«запахи») ----
// Baseline'ы: старые нарушения прощены, CI падает только на НОВЫЕ.
// Обновить baseline:     ./gradlew :app:ktlintGenerateBaseline :app:detektBaseline
ktlint {
    baseline.set(file("config/ktlint/ktlint-baseline.xml"))
}

detekt {
    baseline = file("config/detekt/detekt-baseline.xml")
    buildUponDefaultConfig = true
    // Без этого detekt работал на дефолтах (maxLineLength 120, FunctionNaming
    // без @Composable), которые расходились с .editorconfig. См. config/detekt/detekt.yml.
    config.setFrom(file("config/detekt/detekt.yml"))
}
