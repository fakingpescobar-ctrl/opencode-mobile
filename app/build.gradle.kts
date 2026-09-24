plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jlleitschuh.gradle.ktlint")
    id("io.gitlab.arturbosch.detekt")
}

android {
    namespace = "org.opencode.mobile"
    compileSdk = 36

    defaultConfig {
        applicationId = "org.opencode.mobile"
        minSdk = 28
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("debug") // тестовая подпись для локального smoke; заменить на release-key
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
