package org.opencode.mobile.server

import android.content.Context

/**
 * Выбранная пользователем рабочая папка — единственное, что здесь хранится.
 *
 * Отдельный файл настроек, а не `chat_overlay`: значение читает сервис при
 * старте, а не только UI, и общий файл с чатом означал бы, что сброс настроек
 * чата однажды унесёт и рабочую папку.
 *
 * Хранится строка, а не `File`: путь мог быть на съёмной SD-карте, которой
 * сейчас нет. Решение «путь ещё годен» принимает [Workspace.resolveWithStatus]
 * при каждом старте, и там же видно причину отказа.
 */
object WorkspacePrefs {
    private const val PREFS = "workspace"
    private const val KEY_ROOT = "root"

    /** Сохранённый путь или null, если пользователь ничего не выбирал. */
    fun saved(context: Context): String? =
        prefs(context)
            .getString(KEY_ROOT, null)
            ?.takeIf { it.isNotBlank() }

    /** Запомнить путь. null — вернуть значение по умолчанию. */
    fun save(
        context: Context,
        path: String?,
    ) {
        val editor = prefs(context).edit()
        if (path == null) editor.remove(KEY_ROOT) else editor.putString(KEY_ROOT, path)
        editor.apply()
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
