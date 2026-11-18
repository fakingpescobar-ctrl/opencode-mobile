package org.opencode.mobile.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Политика «чужой конфиг не трогаем» — часть контракта, а не деталь реализации.
 *
 * [OpencodeRuntime.ensureMemorySection] намеренно отказывается переписывать
 * секцию `mcp`, которую не распознал как свою: тихая замена могла бы включить
 * неаутентифицированный доступ к локальным портам. Перед переходом на
 * структурный JSONC-merge (этап 2 плана) это поведение надо зафиксировать,
 * чтобы рефакторинг не поменял его случайно.
 */
class ConfigRefusalTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `неизвестная форма mcp не переписывается`() {
        val file = tmp.newFile("opencode.jsonc")
        val original = """
            {
              "${'$'}schema": "https://opencode.ai/config.json",
              "mcp": {
                "custom": {}
              }
            }
        """.trimIndent() + "\n"
        file.writeText(original)

        val changed = OpencodeRuntime.ensureMemorySection(file)

        assertFalse("чужой конфиг не должен переписываться", changed)
        assertEquals(original, file.readText())
    }
}
