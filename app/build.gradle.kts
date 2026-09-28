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
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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
}
