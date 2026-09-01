plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
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
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            // logcat-friendly
            applicationIdSuffix = ".debug"
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
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.4")
    implementation(project(":whisperlib"))
    debugImplementation("androidx.compose.ui:ui-tooling")
}
