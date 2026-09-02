package org.opencode.mobile

import android.app.Application
import android.content.Context
import org.opencode.mobile.server.Workspace
import java.io.File

/**
 * Глобальный контекст приложения. Здесь же определяем каталоги,
 * которым отдаём opencode (HOME/XDG_*).
 *
 * ВАЖНО: при наличии «Доступа ко всем файлам» HOME/XDG_* данные opencode
 * уезжают на ВНЕШНЕЕ хранилище (Documents/OpencodeTerminal/opencode), а не
 * во внутренний sandbox. Внутренний раздел (filesDir, 751 МБ) заполнен на
 * 100% — раньше модель упиралась в него и жаловалась на ограничение рабочего
 * каталога, хотя bash-pwd процесса serve уже был внешним (pb.directory).
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
        /** База, под которой лежат home/config/data/cache. */
        lateinit var opencodeBase: File
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
            // Есть «Доступ ко всем файлам»? Тогда HOME/XDG_* — на внешнее хранилище
            // (Documents/OpencodeTerminal/opencode), рядом с workspace. Иначе внутренний
            // sandbox (app без прав — данные остаются в filesDir).
            if (Workspace.usingExternal(context)) {
                opencodeBase = File(Workspace.resolve(context), "opencode").apply { mkdirs() }
            } else {
                opencodeBase = appFiles
            }
            opencodeHome = File(opencodeBase, "opencode-home")
            opencodeConfig = File(opencodeBase, "opencode-config")
            opencodeData = File(opencodeBase, "opencode-data")
            opencodeCache = File(opencodeBase, "opencode-cache")
            listOf(opencodeHome, opencodeConfig, opencodeData, opencodeCache).forEach { it.mkdirs() }
        }
    }
}
