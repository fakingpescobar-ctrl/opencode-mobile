package org.opencode.mobile.server

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Вставка разрешения по границе корневого `mcp`.
 *
 * Проверяется не только «ключ появился», но и то, что вокруг него ничего не
 * изменилось: потеря music или moltbook на устройстве выглядит как «сервер
 * поднялся, но в списке MCP его нет», и по логам это не отличить от отсутствия
 * токена.
 */
class LaunchPermissionEditTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun managedConfig(block: String): String = "{\n  \"\$schema\": \"https://opencode.ai/config.json\",\n  $block\n}"

    private fun configWithMoltbook(block: String): String {
        val close = block.lastIndexOf('}')
        val body = block.substring(0, close).trimEnd()
        return managedConfig(body + ",\n    " + MoltbookMcp.entry() + "\n  }")
    }

    @Test
    fun `разрешение вставляется после корневого mcp`() {
        val written = insertLaunchPermissionAfterMcp(managedConfig(OpencodeRuntime.mcpBlockForTest()), MOBILE_LAUNCH_PERMISSION_ENTRY)

        val out = JSONObject(written!!)
        assertEquals("ask", out.getJSONObject("permission").getString("mobile_launch_app"))
        assertEquals("https://opencode.ai/config.json", out.getString("\$schema"))
        assertTrue(out.getJSONObject("mcp").has(MemoryMcp.MEMORY_NAME))
        assertTrue(out.getJSONObject("mcp").has(MemoryMcp.TOOLS_NAME))
    }

    @Test
    fun `соседний корневой ключ не теряется`() {
        val text = "{\n  ${OpencodeRuntime.mcpBlockForTest()},\n  \"model\": \"opencode/big-pickle\"\n}"

        val out = JSONObject(insertLaunchPermissionAfterMcp(text, MOBILE_LAUNCH_PERMISSION_ENTRY)!!)

        assertEquals("opencode/big-pickle", out.getString("model"))
        assertEquals("ask", out.getJSONObject("permission").getString("mobile_launch_app"))
    }

    @Test
    fun `moltbook после телефона не мешает вставке`() {
        // Именно этот порядок сломал старую замену: готовый блок mcp в тексте
        // не находился, разрешение не добавлялось, а метод возвращал true.
        val config = configWithMoltbook(OpencodeRuntime.mcpBlockForTest())
        val written = insertLaunchPermissionAfterMcp(config, MOBILE_LAUNCH_PERMISSION_ENTRY)!!

        assertTrue(written.contains("\"${MoltbookMcp.NAME}\""))
        val out = JSONObject(written)
        assertEquals("ask", out.getJSONObject("permission").getString("mobile_launch_app"))
        assertTrue(out.getJSONObject("mcp").has(MoltbookMcp.NAME))
    }

    @Test
    fun `музыка остаётся зарегистрированной`() {
        val written = insertLaunchPermissionAfterMcp(managedConfig(OpencodeRuntime.mcpBlockWithMusic()), MOBILE_LAUNCH_PERMISSION_ENTRY)!!

        val out = JSONObject(written)
        assertTrue(out.getJSONObject("mcp").has(YnisonMcp.NAME))
        assertEquals("ask", out.getJSONObject("permission").getString("mobile_launch_app"))
    }

    @Test
    fun `комментарии и форматирование сохраняются`() {
        val text = "{\n  // корневой комментарий\n  \"mcp\": {\n    \"memory\": {} // внутри\n  }\n}"

        val written = insertLaunchPermissionAfterMcp(text, MOBILE_LAUNCH_PERMISSION_ENTRY)!!

        assertTrue(written.contains("// корневой комментарий"))
        assertTrue(written.contains("// внутри"))
        assertEquals(1, Regex("\"mobile_launch_app\": \"ask\"").findAll(written).count())
        assertEquals(written.count { it == '{' }, written.count { it == '}' })
    }

    @Test
    fun `вложенный mcp не принимается за корневой`() {
        val text = "{\"note\": {\"mcp\": {\"memory\": {}}}, \"mcp\": {\"memory\": {}}}"

        val out = JSONObject(insertLaunchPermissionAfterMcp(text, MOBILE_LAUNCH_PERMISSION_ENTRY)!!)

        assertFalse(out.getJSONObject("note").has("permission"))
        assertEquals("ask", out.getJSONObject("permission").getString("mobile_launch_app"))
    }

    @Test
    fun `нет объекта mcp или скобка не закрыта - отказ`() {
        assertEquals(null, insertLaunchPermissionAfterMcp("{\"model\": \"x\"}", MOBILE_LAUNCH_PERMISSION_ENTRY))
        assertEquals(null, insertLaunchPermissionAfterMcp("{\"mcp\": null}", MOBILE_LAUNCH_PERMISSION_ENTRY))
        assertEquals(null, insertLaunchPermissionAfterMcp("{\"mcp\": {", MOBILE_LAUNCH_PERMISSION_ENTRY))
    }

    @Test
    fun `ensureMemorySection добавляет разрешение один раз`() {
        val file = tmp.newFile("opencode.jsonc")
        file.writeText(managedConfig(OpencodeRuntime.mcpBlockForTest()))

        assertTrue(OpencodeRuntime.ensureMemorySection(file))
        val first = file.readText()
        assertTrue(OpencodeRuntime.ensureMemorySection(file))

        assertEquals(first, file.readText())
        assertEquals(1, Regex("\"mobile_launch_app\"").findAll(first).count())
    }
}
