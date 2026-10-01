package org.opencode.mobile.server

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Контракт: каждый относительный импорт в mcp-скриптах имеет файл, который
 * действительно копируется на устройство.
 *
 * ПОЧЕМУ ЭТОТ ТЕСТ НУЖЕН, А НЕ «ПОПРОБУЙ И УВИДИШЬ»
 *
 * memory.js запускается из filesDir/mem/, и туда копируются выборочные файлы
 * из assets. Скрипт в репозитории и скрипт на устройстве — разные наборы
 * файлов. Когда в memory.js добавили import "./provenance.js", скрипт на ПК
 * остался рабочим (файл рядом), а на телефоне перестал бы запускаться вовсе:
 * импортировать было бы нечего.
 *
 * Проверка на устройстве стоила бы установки APK и чтения logcat. Этот тест
 * ловит то же самое за секунду, до сборки — и ловит всегда, а не в тот день,
 * когда кто-то догадается прогнать APK.
 *
 * Правило жёсткое: относительный импорт вида "./x.js" обязан соответствовать
 * файлу в assets. Импорт из пакета ("bun:sqlite", "fs") пропускаем — их
 * даёт рантайм, а не наш каталог.
 */
class McpAssetImportContractTest {
    private val mcpDir: File by lazy {
        val candidates = listOf(
            File("src/main/assets/mcp"),
            File("app/src/main/assets/mcp"),
            File("../../../../main/assets/mcp"),
        )
        // error(), а не Assert.fail(): JUnit-овский fail возвращает void, из-за чего
        // elvis-выражение типизировался как Any и файл не компилировался.
        candidates.firstOrNull { it.isDirectory }
            ?: error("каталог assets/mcp не найден, проверяли: $candidates")
    }

    @Test
    fun `все относительные импорты имеют файл в assets`() {
        val problems = mutableListOf<String>()
        val scripts = mcpDir.listFiles()?.filter { it.isFile && it.name.endsWith(".js") }.orEmpty()
        assertTrue("в assets/mcp нет ни одного скрипта", scripts.isNotEmpty())

        for (script in scripts) {
            for (imp in relativeImportsOf(script)) {
                if (!File(mcpDir, imp).isFile) {
                    problems += script.name + ": импорт " + imp + " — файла нет в assets/mcp"
                }
            }
        }
        assertTrue(
            "относительные импорты без файла:\n" + problems.joinToString("\n"),
            problems.isEmpty(),
        )
    }

    @Test
    fun `provenance js лежит рядом с memory js`() {
        // Точечная проверка на текущую связку: memory.js обязан импортировать
        // модуль доверия, иначе provenance не применяется вовсе, и агент снова
        // начнёт действовать по чужим советам.
        assertTrue(
            "provenance.js должен лежать в assets/mcp",
            File(mcpDir, "provenance.js").isFile,
        )
        val memory = File(mcpDir, "memory.js").readText()
        assertTrue(
            "memory.js не импортирует ./provenance.js — правило доверия не подключено",
            memory.contains("provenance.js"),
        )
        // Холостое утверждение, чтобы сборка падала, если файл вдруг опустел:
        // пустой memory.js прошёл бы проверку импортов как файл без них.
        assertTrue("memory.js подозрительно мал", memory.length > 1000)
    }

    @Test
    fun `memory js тянет только предусмотренные модули`() {
        // Обратная защита: импорт, которого нет в assets, может появиться снова
        // вместе с новым модулем. Здесь перечислено, что разрешено, - и любое
        // расхождение видно прямо в diff.
        val allowed = setOf("./provenance.js")
        val unknown = relativeImportsOf(File(mcpDir, "memory.js")).filterNot { it in allowed }
        assertTrue(
            "memory.js тянет непредусмотренные модули: $unknown",
            unknown.isEmpty(),
        )
    }

    /** Относительные импорты вида "./x.js" и "../y/z.js". */
    private fun relativeImportsOf(file: File): List<String> {
        val pattern = Regex("""from\s+["'](\.[^"']+)["']""")
        val groups = pattern.findAll(file.readText()).map { it.groupValues[1] }
        return groups.distinct().toList()
    }
}
