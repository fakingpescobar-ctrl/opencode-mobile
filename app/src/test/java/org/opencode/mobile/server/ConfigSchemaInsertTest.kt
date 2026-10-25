package org.opencode.mobile.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Вставка managed-блока после `$schema`.
 *
 * Дефект, ради которого тест появился: замена всегда добавляла запятую после
 * блока, а запятую у самой схемы считала необязательной. Конфиг, где `$schema` —
 * последнее поле, превращался в `"$schema": "..."` без разделителя перед
 * `"mcp"`, то есть в невалидный JSON. Проверка перед записью
 * (`looksLikeManagedConfig`) считает только скобки, поэтому битый файл
 * записывался, и приложение навсегда залипало на «Starting Open Code server».
 */
class ConfigSchemaInsertTest {
    private val block = "\"mcp\": {\n    \"memory\": {}\n  }"

    @Test
    fun `схема не последняя - блок встаёт между полями с запятыми`() {
        val text = "{\n  \"\$schema\": \"https://opencode.ai/config.json\",\n  \"theme\": \"dark\"\n}"

        val out = OpencodeRuntime.insertAfterSchemaForTest(text, block)

        assertEquals(
            "{\n  \"\$schema\": \"https://opencode.ai/config.json\",\n  $block,\n  \"theme\": \"dark\"\n}",
            out,
        )
    }

    @Test
    fun `схема последнее поле без запятой - разделитель перед блоком появляется`() {
        val text = "{\n  \"\$schema\": \"https://opencode.ai/config.json\"\n}"

        val out = OpencodeRuntime.insertAfterSchemaForTest(text, block)

        assertEquals(
            "{\n  \"\$schema\": \"https://opencode.ai/config.json\",\n  $block\n}",
            out,
        )
    }

    @Test
    fun `схема последняя с висячей запятой - второй запятой не появляется`() {
        val text = "{\n  \"\$schema\": \"https://opencode.ai/config.json\",\n}"

        val out = OpencodeRuntime.insertAfterSchemaForTest(text, block)

        assertEquals(
            "{\n  \"\$schema\": \"https://opencode.ai/config.json\",\n  $block\n}",
            out,
        )
    }

    @Test
    fun `без схемы вставка не выдумывает поле`() {
        val out = OpencodeRuntime.insertAfterSchemaForTest("{\n  \"theme\": \"dark\"\n}", block)

        assertEquals(null, out)
    }

    @Test
    fun `скобки после вставки сбалансированы`() {
        val text = "{\n  \"\$schema\": \"https://opencode.ai/config.json\"\n}"

        val out = OpencodeRuntime.insertAfterSchemaForTest(text, block).orEmpty()

        assertTrue("скобки разъехались: $out", out.count { it == '{' } == out.count { it == '}' })
    }
}
