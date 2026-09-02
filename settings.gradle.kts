pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // terminal-view / terminal-emulator (терминальный эмулятор для TUI opencode)
        maven(url = "https://jitpack.io")
    }
}

rootProject.name = "opencode_mobile"
include(":app")
include(":whisperlib")
