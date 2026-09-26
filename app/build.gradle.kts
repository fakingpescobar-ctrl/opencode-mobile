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
        // От release отличается ровно двумя вещами, и обе необходимы:
        //   isDebuggable = true   - работает run-as, можно залить модели
        //   applicationIdSuffix   - ставится отдельным пакетом рядом с debug
        //                             и release, ничего не ломая
        // R8, shrinkResources и правила из proguard-rules.pro - как в release.
        //
        // Проверено 27.09.2026: R8 (13 dex -> 1 dex, 1292 -> 550 классов),
        // STT отдаёт английский с первого сегмента, JNI-биндинг жив.
        create("minVerify") {
            initWith(getByName("release"))
            applicationIdSuffix = ".minverify"
            isDebuggable = true
            resValue("string", "app_name", "OpenCode Mobile · MinVerify")
            // whisperlib не имеет build type minVerify - без этого падает
            // variant matching (просит minVerifyApiElements, а библиотека
            // публикует только debug* и release*).
            matchingFallbacks.add("release")
            // Перечислены явно все три, а не только дополнение: proguardFiles
            // в этом DSL ведёт себя неоднозначно, а потерять дефолтный
            // proguard-android-optimize.txt нельзя - именно он сохраняет имена
            // классов с native-методами, и без него JNI не найдёт символы.
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
