package org.opencode.mobile.server

import android.content.Context
import android.os.Build
import android.os.Environment
import java.io.File

/**
 * Резолвер рабочей директории opencode serve.
 *
 * Цель: модель работала с НАСТОЯЩИМИ файлами юзера на внешнем хранилище
 * (Documents), а не с песочницей во внутреннем store (где корень забит на 100%).
 *
 * Логика:
 *  - если дан «Доступ ко всем файлам» (MANAGE_EXTERNAL_STORAGE, API 30+) —
 *    workspace = /storage/emulated/0/Documents/OpencodeTerminal;
 *  - иначе (юзер не дал права) — безопасный fallback на внутренний
 *    <filesDir>/workspace, чтобы app работал без диалога.
 *
 * Флаг [usingExternal] позволяет UI показать, работаем ли по-настоящему
 * с внешними файлами, и подсказать открыть настройки «Доступа ко всем файлам».
 */
object Workspace {

    /** Внутренний fallback (тот же путь, что был раньше). */
    fun internal(context: Context): File =
        File(context.filesDir, "workspace").apply { mkdirs() }

    /** Настоящий внешний каталог юзера (Documents/OpencodeTerminal). */
    fun externalRoot(): File? {
        val base = Environment.getExternalStorageDirectory()
        return File(base, "Documents/OpencodeTerminal")
    }

    /** Есть ли у процесса право «Доступ ко всем файлам» (API 30+). */
    fun hasAllFilesAccess(): Boolean =
        Build.VERSION.SDK_INT >= 30 && Environment.isExternalStorageManager()

    /** Итоговая рабочая директория: внешняя при праве, иначе внутренний fallback. */
    fun resolve(context: Context): File {
        val external = externalRoot()
        return if (hasAllFilesAccess() && external != null) {
            external.apply { mkdirs() }
        } else {
            internal(context)
        }
    }

    /** true — если workspace указывает на внешнее хранилище (не песочницу). */
    fun usingExternal(context: Context): Boolean =
        hasAllFilesAccess() && externalRoot() != null
}