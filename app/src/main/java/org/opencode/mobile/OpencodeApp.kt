package org.opencode.mobile

import android.app.Application
import android.content.Context
import java.io.File

/**
 * Глобальный контекст приложения. Здесь же определяем каталоги,
 * которым отдаём opencode (HOME/XDG_*), чтобы всё живёт внутри
 * sandbox'а приложения.
 */
class OpencodeApp : Application() {

    override fun onCreate() {
        super.onCreate()
        ServerConfig.init(applicationContext)
    }

    /** Точка входа для сервиса и UI: сконфигурированный layout каталогов. */
    object ServerConfig {
        lateinit var appFiles: File
            private set
        lateinit var opencodeHome: File
            private set
        lateinit var opencodeConfig: File
            private set
        lateinit var opencodeData: File
            private set
        lateinit var opencodeCache: File
            private set

        const val PORT = 4096

        fun init(context: Context) {
            appFiles = context.filesDir
            opencodeHome = File(appFiles, "opencode-home")
            opencodeConfig = File(appFiles, "opencode-config")
            opencodeData = File(appFiles, "opencode-data")
            opencodeCache = File(appFiles, "opencode-cache")
            listOf(opencodeHome, opencodeConfig, opencodeData, opencodeCache).forEach { it.mkdirs() }
        }
    }
}
