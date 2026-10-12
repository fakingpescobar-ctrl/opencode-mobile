package org.opencode.mobile.server

import java.io.File

/**
 * Вынос мусора, оставшегося от opencode 1.x.
 *
 * Зачем. v1 был гибридом bun+npm и ставил плагинные зависимости прямо в каталог
 * конфига: `node_modules/`, `package.json`, `package-lock.json`. v2 их не читает
 * вообще, но они остаются на диске — 62 МБ, которые ничего не делают.
 *
 * Хуже мёртвого веса — вредный. В `node_modules/@opencode-ai/sdk/package.json`
 * честно написано `"version": "1.18.25"`, и это самая естественная вещь, которую
 * можно прочитать на вопрос «какая версия opencode». Агент так и отвечал: 1.18,
 * уверенно и неверно, потому что цифра настоящая — просто от того пакета.
 * Правильный источник — `GET /api/info` живого сервера, а он отдаёт 2.0.27.
 *
 * Почему по признаку манифеста, а не безусловно. Если завтра v2 поставит свои
 * зависимости (например, для локального MCP-сервера), он напишет свой
 * `package.json` — уже без `@opencode-ai`. Тогда дерево чужое и трогать его
 * нельзя. Признак «манифест принадлежит v1» надёжно отделяет одно от другого,
 * и при сомнении мы просто ничего не удаляем.
 */
internal object V1Leftovers {
    /** Скоуп пакетов, которые ставил только v1. */
    private const val SCOPE = "@opencode-ai"

    private const val NPM_DIR = "node_modules"
    private const val MANIFEST = "package.json"
    private const val LOCKFILE = "package-lock.json"

    private const val TAG = "OpencodeRuntime"

    /** Мбайт в байтах — для читаемой записи в лог. */
    private const val BYTES_IN_MB = 1024L * 1024L

    /**
     * Файлы и каталоги, которые можно удалить: всё дерево npm вместе с манифестом
     * и локом, если манифест помечен скоупом v1.
     *
     * Только чтение — ничего не удаляет, чтобы решение можно было проверить тестом.
     */
    fun leftoversIn(configHome: File): List<File> {
        val manifest = File(configHome, MANIFEST)
        if (!manifest.isFile || !isV1Manifest(manifest)) return emptyList()
        return listOf(File(configHome, NPM_DIR), manifest, File(configHome, LOCKFILE)).filter { it.exists() }
    }

    /**
     * Признак манифеста v1: в нём есть пакет скоупа `@opencode-ai`.
     *
     * Осознанно простая проверка подстрокой: манифест пишет npm, а не мы, и его
     * формат стабилен. Разбирать его json-парсером ради одной строки дороже, чем
     * риск ошибиться на формате, который к нам отношения не имеет.
     */
    fun isV1Manifest(manifest: File): Boolean = runCatching { manifest.readText() }.getOrNull()?.contains("$SCOPE/") == true

    /**
     * Удаляет найденное и пишет в лог, сколько освободилось.
     *
     * Ошибка не поднимается: это уборка, а не условие работоспособности. Не
     * удалось — ну и не удалось, сервер всё равно должен подняться.
     */
    fun purge(configHome: File): Int {
        val targets = leftoversIn(configHome)
        if (targets.isEmpty()) return 0
        var freed = 0L
        var removed = 0
        targets.forEach { target ->
            runCatching {
                // Считаем ДО удаления: после deleteRecursively размера уже нет.
                freed += sizeOf(target)
                if (target.deleteRecursively()) removed++
            }.onFailure { error ->
                android.util.Log.w(TAG, "не удалось убрать остатки v1: ${target.name} (${error.message})")
            }
        }
        if (removed > 0) {
            android.util.Log.i(
                TAG,
                "Removed v1 install leftovers: $removed entries, ${freed / BYTES_IN_MB} MB " +
                    "(stale 1.x version strings could mislead the agent)",
            )
        }
        return removed
    }

    /** Размер дерева до удаления. Ошибка чтения даёт 0 — это только лог. */
    private fun sizeOf(target: File): Long =
        runCatching {
            if (target.isFile) {
                target.length()
            } else {
                target.walkTopDown().filter { it.isFile }.sumOf { it.length() }
            }
        }.getOrDefault(0L)
}
