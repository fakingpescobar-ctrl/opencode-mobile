package org.opencode.mobile.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Проверки правки записи «moltbook» в управляемом opencode.jsonc.
 *
 * ПОЧЕМУ ФАЙЛ СУЩЕСТВУЕТ
 * [planMoltbookEntry] - единственное место, где можно тихо испортить работающую
 * установку: потерять регистрацию памяти или оставить в конфиге обрывок вместо
 * записи. Обе ошибки невидимы - сервер просто не поднимет инструмент. Поэтому
 * проверка живёт отдельно от Context и Robolectric, которого в проекте нет.
 *
 * ГЛАВНОЕ, ЧТО ЗДЕСЬ ПРОВЕРЯЕТСЯ
 * Скобки. Запись сервера содержит вложенный `headers: { ... }`, поэтому поиск первой
 * закрытой скобки обрезал бы объект mcp на середине. Ниже есть тест именно на это.
 */
class MoltbookMcpTest {
    private fun entry(
        name: String,
        url: String,
        tokenEnv: String,
    ): String =
        "\"$name\": {\n" +
            "      \"type\": \"remote\",\n" +
            "      \"url\": \"$url\",\n" +
            "      \"headers\": {\n" +
            "        \"Authorization\": \"Bearer {env:$tokenEnv}\"\n" +
            "      }\n" +
            "    }"

    /** Конфиг как его пишет OpencodeRuntime: две чужеродные записи плюс хвост. */
    private fun config(vararg entries: String): String {
        val body = entries.joinToString(",\n    ")
        return "{\n  \"\$schema\": \"https://opencode.ai/config.json\",\n" +
            "  \"mcp\": {\n    $body\n  },\n" +
            "  \"permission\": {\n    \"mobile_launch_app\": \"ask\"\n  }\n}"
    }

    private fun memoryEntry() = entry("memory", "http://127.0.0.1:4199/mcp", "MCP_MEMORY_TOKEN")

    private fun musicEntry() = entry("music", "http://127.0.0.1:4200/mcp", "MCP_MUSIC_TOKEN")

    @Test
    fun `запись молтбука это ровно то что пишет рантайм`() {
        assertTrue(MoltbookMcp.entry().contains("\"moltbook\": {"))
        assertTrue(MoltbookMcp.entry().contains("\"url\": \"http://127.0.0.1:4201/mcp\""))
        assertTrue(MoltbookMcp.entry().contains("{env:MCP_MOLTBOOK_TOKEN}"))
        assertEquals("moltbook", MoltbookMcp.NAME_KEY.trim('"'))
    }

    @Test
    fun `уже зарегистрированный плагин не трогает файл`() {
        val text = config(memoryEntry(), MoltbookMcp.entry())
        assertEquals(MoltbookEntryPlan.Keep, planMoltbookEntry(text, present = true))
    }

    @Test
    fun `отсутствующий плагин не трогает файл`() {
        assertEquals(MoltbookEntryPlan.Keep, planMoltbookEntry(config(memoryEntry()), present = false))
    }

    @Test
    fun `плагин дописывается последним и память остаётся на месте`() {
        val text = config(memoryEntry(), musicEntry())
        val plan = planMoltbookEntry(text, present = true)
        assertTrue(plan is MoltbookEntryPlan.Write)
        val written = (plan as MoltbookEntryPlan.Write).text
        assertTrue(written.contains("\"memory\""))
        assertTrue(written.contains("\"music\""))
        assertTrue(written.contains("\"moltbook\""))
        assertTrue(written.indexOf("\"moltbook\"") > written.indexOf("\"music\""))
        // Скобки сошлись: хвост конфига не потерян.
        assertTrue(written.endsWith("}"))
        assertTrue(written.contains("\"permission\""))
        assertTrue(written.contains("\"mobile_launch_app\""))
    }

    @Test
    fun `добавленная запись это ровно та же строка что и в рантайме`() {
        val plan = planMoltbookEntry(config(memoryEntry()), present = true) as MoltbookEntryPlan.Write
        assertTrue(plan.text.contains(MoltbookMcp.entry()))
    }

    @Test
    fun `вложенные скобки headers не обрезают запись`() {
        val plan = planMoltbookEntry(config(memoryEntry()), present = true) as MoltbookEntryPlan.Write
        // Обрезка на первой закрытой скобке оставила бы после headers огрызок и
        // потеряла бы закрывающую скобку самой записи.
        assertTrue(plan.text.contains("\"Authorization\": \"Bearer {env:MCP_MOLTBOOK_TOKEN}\""))
        // Запись должна закончиться своей закрывающей скобкой и отступом секции.
        // Обрезка на первой закрытой скобке оставила бы после headers огрызок и
        // скобки не сошлись бы.
        assertTrue(plan.text.contains(MoltbookMcp.entry() + "\n  }"))
    }

    @Test
    fun `удаление записи не оставляет хвоста и не трогает соседей`() {
        val text = config(memoryEntry(), MoltbookMcp.entry())
        val plan = planMoltbookEntry(text, present = false)
        assertTrue(plan is MoltbookEntryPlan.Write)
        val written = (plan as MoltbookEntryPlan.Write).text
        assertEquals(config(memoryEntry()), written)
        assertEquals(-1, written.indexOf("\"moltbook\""))
    }

    @Test
    fun `удаление записи из середины не рвёт соседей`() {
        val text = config(memoryEntry(), MoltbookMcp.entry(), musicEntry())
        val written = (planMoltbookEntry(text, present = false) as MoltbookEntryPlan.Write).text
        assertTrue(written.contains("\"memory\""))
        assertTrue(written.contains("\"music\""))
        assertEquals(-1, written.indexOf("\"moltbook\""))
        assertEquals(-1, written.indexOf("MCP_MOLTBOOK_TOKEN"))
    }

    @Test
    fun `добавление и удаление возвращают исходный файл байт в байт`() {
        val original = config(memoryEntry(), musicEntry())
        val added = (planMoltbookEntry(original, present = true) as MoltbookEntryPlan.Write).text
        assertEquals(original, (planMoltbookEntry(added, present = false) as MoltbookEntryPlan.Write).text)
    }

    @Test
    fun `чужая секция mcp не чинится и не пишется`() {
        assertEquals(MoltbookEntryPlan.Refuse, planMoltbookEntry("{\n  \"a\": 1\n}", present = true))
    }

    @Test
    fun `пустой объект mcp не чинится`() {
        val empty = "{\n  \"mcp\": {\n  }\n}"
        assertEquals(MoltbookEntryPlan.Refuse, planMoltbookEntry(empty, present = true))
    }

    @Test
    fun `незакрытая секция mcp не пишется`() {
        // Не хватает закрывающей скобки самого файла: секция mcp не сошлась.
        val broken = "{\n  \"mcp\": {\n    \"memory\": {\n      \"type\": \"remote\"\n  }\n"
        assertEquals(MoltbookEntryPlan.Refuse, planMoltbookEntry(broken, present = true))
    }

    @Test
    fun `пустой конфиг без плагина это тоже нечего менять`() {
        // present=false и записи нет - файл уже в нужном состоянии, даже если он
        // настолько чужой, что секции mcp в нём нет.
        assertEquals(MoltbookEntryPlan.Keep, planMoltbookEntry("", present = false))
    }

    @Test
    fun `пустой конфиг под плагин не пишется`() {
        assertEquals(MoltbookEntryPlan.Refuse, planMoltbookEntry("", present = true))
    }

    @Test
    fun `план всегда разрешим`() {
        val plans =
            listOf(
                planMoltbookEntry(config(memoryEntry()), present = true),
                planMoltbookEntry(config(memoryEntry()), present = false),
                planMoltbookEntry("{}", present = true),
            )
        assertNotNull(plans)
        assertEquals(3, plans.size)
    }
}
