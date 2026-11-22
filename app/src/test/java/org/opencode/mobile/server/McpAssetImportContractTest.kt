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
        // Сопроцессорные модули — часть рабочей связки ядра, а не случайный импорт:
        // каждый из них реально лежит в assets/mcp и копируется на устройство.
        val allowed =
            setOf(
                "./provenance.js",
                "./memory-core.js",
                "./memory-index.mjs",
                "./memory-adaptive.js",
                "./memory-adaptive-store.js",
                "./memory-llm.js",
                "./memory-map.js",
            )
        val unknown = relativeImportsOf(File(mcpDir, "memory.js")).filterNot { it in allowed }
        assertTrue(
            "memory.js тянет непредусмотренные модули: $unknown",
            unknown.isEmpty(),
        )
        // Движок v2: карта памяти обязана быть и в assets, и в импортах —
        // иначе новый тул memory_map молча станет недоступным на устройстве.
        assertTrue(
            "memory-map.js должен лежать в assets/mcp",
            File(mcpDir, "memory-map.js").isFile,
        )
        assertTrue(
            "memory.js не импортирует ./memory-map.js — карта памяти не подключена",
            File(mcpDir, "memory.js").readText().contains("./memory-map.js"),
        )
        // Data-plane саморастущего корпуса: если ассет не скопирован,
        // memory_signal упадёт на импорте и MCP не поднимется вовсе.
        assertTrue(
            "memory-adaptive-store.js должен лежать в assets/mcp",
            File(mcpDir, "memory-adaptive-store.js").isFile,
        )
        assertTrue(
            "memory.js не импортирует ./memory-adaptive-store.js — буфер сигналов не подключён",
            File(mcpDir, "memory.js").readText().contains("./memory-adaptive-store.js"),
        )
    }

    @Test
    fun `именованные импорты имеют экспорт в целевом модуле`() {
        // apk-63: memory.js импортировал goldSha из memory-adaptive-store.js,
        // а сам store в APK был старым и этого экспорта не содержал. ESM падал
        // на связывании модуля — проверки «файл есть» здесь недостаточно.
        val named =
            """export\s+(?:async\s+)?(?:function|const|let|var|class)\s+([A-Za-z_][A-Za-z0-9_]*)"""
        val listed = """export\s*\{([^}]*)\}"""
        val exportPattern = Regex("$named|$listed")
        val importPattern = Regex("""import\s*\{([^}]*)\}\s*from\s*[\x22'](\.[^\x22']+)[\x22']""")
        val scripts = mcpDir.listFiles()?.filter { it.isFile && it.name.endsWith(".js") }.orEmpty()
        val problems = scripts.asSequence()
            .flatMap { script ->
                importPattern.findAll(script.readText())
                    .flatMap { m -> missingExports(script.name, m, exportPattern).asSequence() }
            }
            .toList()
        assertTrue(
            "именованных импортов без экспорта:\n" + problems.joinToString("\n"),
            problems.isEmpty(),
        )
    }

    private fun importedNames(m: MatchResult): List<String> =
        m.groupValues[1]
            .split(",")
            .map { it.trim().substringBefore(" as ").trim() }
            .filter { it.isNotEmpty() }

    private fun exportNames(e: MatchResult): List<String> {
        if (e.groupValues[1].isNotEmpty()) return listOf(e.groupValues[1])
        return e.groupValues[2].split(",").map { part ->
            val pieces = part.trim().split(" as ")
            if (pieces.size > 1) pieces[1].trim() else pieces[0].trim()
        }
    }

    private fun exportedNames(
        target: File,
        pattern: Regex,
    ): Set<String> = pattern.findAll(target.readText())
        .flatMap { e -> exportNames(e).asSequence() }
        .toSet()

    private fun missingExports(
        scriptName: String,
        m: MatchResult,
        pattern: Regex,
    ): List<String> {
        val target = File(mcpDir, m.groupValues[2])
        if (!target.isFile) return emptyList() // наличие файла проверяет первый тест
        val exports = exportedNames(target, pattern)
        return importedNames(m).filterNot(exports::contains).map { name ->
            scriptName + ": " + m.groupValues[2] + " не экспортирует " + name
        }
    }

    /** Относительные импорты вида "./x.js" и "../y/z.js". */
    private fun relativeImportsOf(file: File): List<String> {
        val pattern = Regex("""from\s+["'](\.[^"']+)["']""")
        val groups = pattern.findAll(file.readText()).map { it.groupValues[1] }
        return groups.distinct().toList()
    }
}
