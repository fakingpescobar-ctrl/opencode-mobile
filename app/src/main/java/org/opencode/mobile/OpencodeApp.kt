package org.opencode.mobile

import android.app.Application
import android.content.Context
import org.opencode.mobile.installer.AppInstallBridge
import org.opencode.mobile.server.Workspace
import java.io.File

/**
 * Глобальный контекст приложения. Здесь же определяем каталоги,
 * которым отдаём opencode (HOME/XDG_*).
 *
 * ВАЖНО: при наличии «Доступа ко всем файлам» HOME/XDG_DATA/CACHE уезжают на
 * ВНЕШНЕЕ хранилище (Documents/OpencodeTerminal/opencode), а не во внутренний
 * sandbox. Внутренний раздел (filesDir, 751 МБ) заполнен на 100% — раньше модель
 * упиралась в него и жаловалась на ограничение рабочего каталога, хотя bash-pwd
 * процесса serve уже был внешним (pb.directory).
 *
 * ИСКЛЮЧЕНИЕ — XDG_CONFIG_HOME, он всегда в приватном filesDir: opencode держит
 * там конфиг и node_modules, а bun install обязан создавать symlink, который
 * sdcardfs не поддерживает. Причина и цена решения — в [ServerConfig.init].
 */
class OpencodeApp : Application() {
    override fun onCreate() {
        super.onCreate()
        ServerConfig.init(applicationContext)
        AppInstallBridge.start(applicationContext)
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

        /** [opencodeBase] лежит на внешнем хранилище (есть «Доступ ко всем файлам»). */
        private var externalBase = false

        const val PORT = 4096

        /** Имя файла конфига opencode. */
        const val CONFIG_FILE = "opencode.jsonc"

        /**
         * Каталог эталона конфига. Имя сознательно НЕ `opencode-config` и без
         * вложенного `opencode/` внутри — форма пути здесь условие
         * работоспособности, а не стилистика.
         *
         * Зеркало раньше лежало по `opencode-config/opencode/opencode.jsonc`,
         * то есть ровно по форме корня конфига opencode
         * (`Global.Path.config = <base> + "/opencode"`). На sdcardfs opencode
         * принимал наш эталон за свой каталог, создавал рядом `.gitignore`,
         * запускал `bun install` и 18 раз за 30.09–01.10 падал на EACCES при
         * создании symlink в `node_modules/.bin` (на sdcardfs симлинки невозможны),
         * оставляя 37–49 МБ полуразобранных зависимостей после каждой попытки.
         *
         * Пока эталон лежит по пути, который opencode распознаёт как свой корень,
         * фоновая установка зависимостей будет падать снова. Регрессия закрыта
         * тестом ConfigMirrorPathTest.
         */
        const val MIRROR_DIR = "config-mirror"

        fun init(context: Context) {
            appFiles = context.filesDir
            // Есть «Доступ ко всем файлам»? Тогда HOME/XDG_DATA/CACHE — на внешнее
            // хранилище (Documents/OpencodeTerminal/opencode), рядом с workspace. Иначе
            // внутренний sandbox (app без прав — данные остаются в filesDir).
            externalBase = Workspace.usingExternal(context)
            if (externalBase) {
                opencodeBase = File(Workspace.resolve(context), "opencode").apply { mkdirs() }
            } else {
                opencodeBase = appFiles
            }
            opencodeHome = File(opencodeBase, "opencode-home")
            opencodeData = File(opencodeBase, "opencode-data")
            opencodeCache = File(opencodeBase, "opencode-cache")

            // XDG_CONFIG_HOME — исключение, и НАМЕРЕННО остаётся в приватном filesDir.
            //
            // opencode держит в Global.Path.config (= XDG_CONFIG_HOME + "/opencode")
            // и конфиг, и node_modules в ОДНОМ каталоге — разделить нельзя, это
            // upstream-решение (packages/opencode/src/config/config.ts). А bun install
            // всегда создаёт symlink в node_modules/.bin, и на sdcardfs симлинки не
            // поддерживаются в принципе. При opencodeConfig на внешнем хранилище
            // установка падала с EACCES на symlink — 18 раз за 30.09–01.10, 37 МБ
            // полуразобранного node_modules оставались после каждой попытки.
            //
            // Переносить сюда БД и память нельзя: они обязаны переживать переустановку
            // APK, а filesDir стирается целиком. Конфиг — можно: он целиком
            // генерируется приложением (OpencodeRuntime.ensureMcpConfig, идемпотентно,
            // атомарно tmp+rename), пользовательских данных в нём нет.
            //
            // Старый конфиг с внешнего хранилища подтягиваем один раз, чтобы не потерять
            // правки, сделанные до разделения (миграция, не синхронизация: дальше
            // приложение пишет только сюда, иначе снова появились бы две копии).
            opencodeConfig = File(appFiles, "opencode-config").apply { mkdirs() }
            // Источник миграции — СТАРОЕ расположение конфига, не путь эталона
            // ([MIRROR_DIR]). Его больше не создаём: читаем оттуда только один раз.
            migrateConfigFrom(File(opencodeBase, "opencode-config"))

            listOf(opencodeHome, opencodeConfig, opencodeData, opencodeCache).forEach { it.mkdirs() }
        }

        /**
         * Эталонная копия конфига в общей папке — то, что читается по USB.
         *
         * Приватный `opencodeConfig` переехал на ext4, и вместе с ним из виду ушёл
         * конфиг: adb его не читает, а ПК-агент сверяет конфиг именно так (всё
         * диагностическое по приоритету 3 сделано по файлам в Documents).
         *
         * Направление строго ОДНОСТОРОННЕЕ: приватный каталог — источник, этот
         * файл — эталон для диффов, миграций и отката. Обратной синхронизации
         * намеренно нет: она снова размножила бы конфиг в два места, то есть
         * вернула бы ту поломку, которую устраняет перенос.
         *
         * null, когда приватный каталог и есть эталон (нет прав на внешнее
         * хранилище) — тогда зеркало было бы записью файла в себя же.
         */
        fun mirroredConfigFile(): File? {
            if (!externalBase) return null
            return mirrorFileFor(opencodeBase)
        }

        /**
         * Путь эталона под [base]. Именно эта функция, а не конкатенация на месте
         * вызова, держит единственное правило формы пути (см. [mirrorFileFor]).
         */
        fun mirrorFileFor(base: File): File = File(base, "$MIRROR_DIR/$CONFIG_FILE")

        /**
         * Разовая миграция конфига с внешнего хранилища в приватный каталог.
         *
         * Копируется только `opencode.jsonc` и только если приватного ещё нет.
         * node_modules НЕ переносим: на внешнем хранилище он всегда полуразобранный
         * (установка падала на symlink), переносить мусор незачем — opencode
         * поставит заново на ext4, где симлинки работают.
         *
         * Односторонняя и одноразовая намеренно: обратной синхронизации быть не
         * должно, иначе конфиг снова размножится в два места — ровно та поломка,
         * которую эта правка устраняет.
         *
         * Условие переноса одно, составным: путь другой, цели ещё нет, источник есть.
         * Три ранних выхода подряд читались как три возврата — detekt прав, условие
         * читается как одно утверждение «переносить имеет смысл только тогда».
         *
         * Запись через tmp+rename с бэкапом прежнего содержимого: миграция выглядит
         * безобидной, но она единственный шаг, который затирает данные, и повторный
         * запуск на неисправленном устройстве не должен усугубить дело.
         */
        private fun migrateConfigFrom(legacyBase: File) {
            val target = File(opencodeConfig, "opencode/opencode.jsonc")
            val legacy = File(legacyBase, "opencode/opencode.jsonc")
            val worthMigrating =
                legacyBase.absolutePath != opencodeConfig.absolutePath &&
                    !target.exists() &&
                    legacy.exists()
            if (!worthMigrating) return
            runCatching {
                val text = legacy.readText()
                target.parentFile?.mkdirs()
                target.readText()?.let { previous ->
                    File(target.parentFile, "opencode.jsonc.bak").writeText(previous)
                }
                val tmp = File(target.parentFile, "opencode.jsonc.tmp")
                tmp.writeText(text)
                check(tmp.renameTo(target)) { "config migration tmp->rename failed" }
                check(target.readText() == text) { "config migration verification failed" }
            }.onFailure {
                File(target.parentFile, "opencode.jsonc.tmp").delete()
                android.util.Log.w("OpencodeApp", "config migration failed: ${it.message}")
            }
        }
    }
}
