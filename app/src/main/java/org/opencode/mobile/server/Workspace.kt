package org.opencode.mobile.server

import android.content.Context
import android.os.Build
import android.os.Environment
import java.io.File

/**
 * Резолвер рабочей директории opencode serve.
 *
 * Раньше путь был зашит: внешний `Documents/OpencodeTerminal`, если есть
 * «Доступ ко всем файлам», иначе песочница. Теперь рабочую папку выбирает
 * пользователь ([WorkspacePrefs]), и этот объект отвечает на два разных
 * вопроса, которые раньше были одним:
 *
 *  - [resolve] — где модель работает (cwd процесса serve). Настраивается.
 *  - [dataRoot] — где живут дом opencode, память и журнал Молтбука. НЕ
 *    настраивается: иначе смена рабочей папки выглядела бы как «память
 *    пропала», хотя данные лежат на месте.
 *
 * Разделение — не косметика: `opencodeBase` раньше вычислялся от рабочей
 * папки, и перенос рабочей папки унёс бы за собой HOME/XDG_DATA/CACHE, то есть
 * сессии и память агента.
 */
object Workspace {
    /** Имя каталога по умолчанию внутри Documents. */
    private const val DEFAULT_DIR = "OpencodeTerminal"

    /** Внутренний fallback (тот же путь, что был раньше). */
    fun internal(context: Context): File = File(context.filesDir, "workspace").apply { mkdirs() }

    /** Каталог данных по умолчанию: Documents/OpencodeTerminal. */
    fun externalRoot(): File? = File(Environment.getExternalStorageDirectory(), "Documents/$DEFAULT_DIR")

    /** Есть ли у процесса право «Доступ ко всем файлам» (API 30+). */
    fun hasAllFilesAccess(): Boolean = Build.VERSION.SDK_INT >= 30 && Environment.isExternalStorageManager()

    /**
     * База для дома opencode и данных, которые должны переживать переустановку.
     *
     * Считается от [externalRoot], а не от рабочей папки: выбор рабочей папки
     * не должен уносить память и сессии. null — права нет, базой будет filesDir.
     */
    fun dataRoot(context: Context): File? = if (hasAllFilesAccess()) externalRoot() else null

    /** Рабочая папка по умолчанию (когда пользователь ничего не выбирал). */
    fun defaultDir(context: Context): File {
        val external = externalRoot()
        return if (hasAllFilesAccess() && external != null) external else internal(context)
    }

    /** Что получилось из выбранной папки: путь, запрос и причина отказа. */
    data class Resolution(
        val dir: File,
        val requested: String?,
        val problem: String?,
    ) {
        /** Рабочая папка лежит вне песочницы приложения. */
        fun isExternal(context: Context): Boolean = !dir.absolutePath.startsWith(context.filesDir.absolutePath + "/")
    }

    /** Рабочая директория: выбранная пользователем, если она годна, иначе по умолчанию. */
    fun resolve(context: Context): File = resolveWithStatus(context).dir

    /**
     * Полный ответ резолвера: что просили, что получили и почему.
     *
     * Причина нужна, чтобы отказ не выглядел тихим: «папка не годится» без
     * строки в диагностике читается как «настройка не сохранилась», а сервер
     * при этом молча работает в старой папке.
     */
    fun resolveWithStatus(context: Context): Resolution {
        val requested = WorkspacePrefs.saved(context)
        if (requested == null) return Resolution(defaultDir(context), null, null)
        val prepared = WorkspacePath.check(requested)
        val dir = prepared.dir
        // `when`, а не второй `?: return`: detekt считает каждый return в теле,
        // и три штуки — уже нарушение ReturnCount.
        return when {
            dir != null -> Resolution(dir, requested, null)
            else -> Resolution(defaultDir(context), requested, prepared.problem)
        }
    }

    /**
     * true — если есть право на внешнее хранилище (данные можно держать снаружи).
     *
     * Про рабочую папку это ничего не говорит: её путь может быть где угодно,
     * поэтому UI показывает именно [Resolution.dir], а не этот флаг.
     */
    fun usingExternal(context: Context): Boolean = hasAllFilesAccess() && externalRoot() != null

    /** Готовые варианты для диалога выбора: подпись и путь. */
    fun candidates(context: Context): List<Pair<String, String>> {
        val base = Environment.getExternalStorageDirectory()
        val list =
            mutableListOf(
                "Documents/OpencodeTerminal" to File(base, "Documents/$DEFAULT_DIR").absolutePath,
                "Documents" to File(base, "Documents").absolutePath,
                "Download" to File(base, "Download").absolutePath,
            )
        sdCardRoot(context)?.let { list += "SD-карта" to it.absolutePath }
        return list
    }

    /**
     * Корень съёмной SD-карты, если она есть.
     *
     * `getExternalFilesDirs` отдаёт и основной том, и карту; путь карты — это
     * всё до `/Android/`. Отдельного API нет, а прямой доступ к корню карты на
     * Android 11+ не гарантирован, поэтому кандидат проверяется тем же
     * [WorkspacePath.prepare], что и путь, введённый руками: предложить
     * вариант, который не примется, — хуже, чем не предлагать вовсе.
     */
    private fun sdCardRoot(context: Context): File? {
        val primary = Environment.getExternalStorageDirectory().absolutePath
        // Цепочку разбиваем на шаги: ktlint (chain-method-continuation) требует,
        // чтобы в многострочной цепочке и первый `.` был на новой строке, а
        // `context\n    .getExternalFilesDirs` читается хуже, чем три переменные.
        val dirs = context.getExternalFilesDirs(null).filterNotNull()
        val roots = dirs.map { it.absolutePath.substringBefore("/Android/") }
        val sd = roots.firstOrNull { it.isNotEmpty() && it != primary }
        return sd?.let { File(it) }
    }
}
