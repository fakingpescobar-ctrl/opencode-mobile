package org.opencode.mobile.server

import android.content.Context
import android.util.Log
import java.io.File

/**
 * Стартовый комплект для свежей установки.
 *
 * APK несёт код Memory Core и инструкции по работе с ним, но не operator-log
 * разработчика. Файлы ниже — минимальный каркас, который агент развивает сам.
 * Пишем только отсутствующие файлы: пользовательские правки и журнал не трогаем.
 */
object WorkspaceSeed {
    private const val TAG = "WorkspaceSeed"

    private data class Item(val asset: String, val relative: String)

    /** Инструкция кладётся в cwd serve — оттуда её автоматически читает OpenCode. */
    private val AGENT_ITEM = Item("agent/AGENTS.md", "AGENTS.md")

    /** Документы индексируются ядром из MCP_DOCS_DIR (= externalRoot/MOBILE_AGENT). */
    private val MEMORY_ITEMS =
        listOf(
            Item("agent/rules.md", "ПРАВИЛА.md"),
            Item("agent/state.md", "СОСТОЯНИЕ.md"),
            Item("agent/journal.md", "ЖУРНАЛ.md"),
            Item("agent/harness.md", "ХАРНЕС.md"),
        )

    /** [workspace] — cwd serve; документы памяти идут в MCP_DOCS_DIR, а не в чужой проект. */
    fun ensure(context: Context, workspace: File) {
        writeIfMissing(context, AGENT_ITEM, workspace)
        val docsRoot = Workspace.externalRoot()?.takeIf { Workspace.usingExternal(context) } ?: return
        val dir = File(docsRoot, "MOBILE_AGENT")
        for (item in MEMORY_ITEMS) writeIfMissing(context, item, dir)
    }

    private fun writeIfMissing(context: Context, item: Item, dir: File) {
        val dest = File(dir, item.relative)
        if (dest.exists()) return
        runCatching {
            dir.mkdirs()
            context.assets.open(item.asset).use { input ->
                dest.outputStream().use { output -> input.copyTo(output) }
            }
        }.onFailure { error ->
            Log.w(TAG, "не удалось записать ${item.relative}: ${error.message}")
        }
    }
}
