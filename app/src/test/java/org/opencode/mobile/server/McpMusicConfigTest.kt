package org.opencode.mobile.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Миграция записи «music» в управляемом opencode.jsonc.
 *
 * Проверяемое место — единственное, где правка конфига способна тихо сломать уже
 * работающую установку: файл генерируется как строковый блок и узнаётся точным
 * совпадением, поэтому ошибка на символ означает либо потерю регистрации (сервер молча
 * не появляется в UI), либо отказ записи (конфиг остаётся прежним). На устройстве это
 * выглядело бы как «после обновления перестала работать память», и поймать это можно
 * было бы только там.
 *
 * Эталон берётся у самого [OpencodeRuntime], а не зашит в тест: иначе тест сравнивал бы
 * планировщик с копией строки, и правка формата в приложении тихо оставила бы тест
 * зелёным на выводе, который приложение уже не пишет.
 */
class McpMusicConfigTest {
    private val withoutMusic = OpencodeRuntime.mcpBlockForTest()
    private val withMusic = OpencodeRuntime.mcpBlockWithMusic()

    /**
     * Минимальный управляемый конфиг: блок mcp плюс permission-ключ, без которого
     * запись отвергается.
     *
     * Склеивается конкатенацией, а не raw-строкой с trimIndent: блок опознаётся точным
     * совпадением подстроки, а trimIndent сдвигает его первую строку относительно
     * остальных и ломает опознание. Соблазн «просто отступы в тесте» тут стоил бы
     * пяти красных тестов, причём красных не там, где ошибка.
     */
    private fun config(block: String): String =
        "{\n  $block,\n" +
            "  \"permission\": {\n" +
            "    \"mobile_launch_app\": \"ask\"\n" +
            "  }\n" +
            "}"

    @Test
    fun `без подключения и без записи music конфиг не трогаем`() {
        val plan = OpencodeRuntime.planMusicEntry(config(withoutMusic), null)

        assertEquals(MusicConfigPlan.Keep, plan)
    }

    @Test
    fun `подключили Яндекс - запись music появляется`() {
        val plan = OpencodeRuntime.planMusicEntry(config(withoutMusic), "token")

        val written = (plan as MusicConfigPlan.Write).text
        assertTrue("ожидалась запись music", written.contains(MUSIC_KEY))
        assertTrue("память должна остаться", written.contains("\"memory\""))
        assertTrue("телефон должен остаться", written.contains("\"mobile_tools\""))
    }

    @Test
    fun `отключили Яндекс - запись music исчезает`() {
        val plan = OpencodeRuntime.planMusicEntry(config(withMusic), null)

        val written = (plan as MusicConfigPlan.Write).text
        assertFalse("music не должна остаться", written.contains(MUSIC_KEY))
        assertTrue("память должна остаться", written.contains("\"memory\""))
        assertTrue("телефон должен остаться", written.contains("\"mobile_tools\""))
    }

    @Test
    fun `уже подключено и зарегистрировано - конфиг не трогаем`() {
        val plan = OpencodeRuntime.planMusicEntry(config(withMusic), "token")

        assertEquals(MusicConfigPlan.Keep, plan)
    }

    @Test
    fun `смена токена не переписывает конфиг`() {
        // В конфиге лежит ссылка {env:MCP_YNISON_TOKEN}, а не значение, поэтому новый
        // UUID на диске конфига не интересует. Если это перестанет быть так, тест упадёт
        // на первой же смене токена за виток - как раз тогда, когда это и случилось бы.
        val plan = OpencodeRuntime.planMusicEntry(config(withMusic), "другой-токен")

        assertEquals(MusicConfigPlan.Keep, plan)
    }

    @Test
    fun `пустой токен считается отсутствием подключения`() {
        val plan = OpencodeRuntime.planMusicEntry(config(withMusic), "   ")

        assertEquals(MusicConfigPlan.Keep, plan)
    }

    @Test
    fun `чужой формат секции mcp не переписываем`() {
        val foreign = """{"mcp": {"что-то-чужое": {"type": "remote", "url": "http://example"}} }"""

        val plan = OpencodeRuntime.planMusicEntry(foreign, "token")

        assertEquals(MusicConfigPlan.Refuse, plan)
    }

    @Test
    fun `добавление music не ломает баланс скобок`() {
        // writeMemoryConfigText отказывается писать текст с несбалансированными скобками,
        // поэтому дисбаланс означал бы тихий отказ регистрации, а не ошибку в UI.
        val plan = OpencodeRuntime.planMusicEntry(config(withoutMusic), "token")
        val written = (plan as MusicConfigPlan.Write).text

        assertEquals(written.count { it == '{' }, written.count { it == '}' })
        assertEquals(written.count { it == '[' }, written.count { it == ']' })
    }

    @Test
    fun `permission-ключ переживает добавление music`() {
        // Та же причина: без него looksLikeManagedConfig отвергнет текст, и music не
        // зарегистрируется - при живом процессе и живом токене, то есть невидимо.
        val plan = OpencodeRuntime.planMusicEntry(config(withoutMusic), "token")
        val written = (plan as MusicConfigPlan.Write).text

        assertTrue(written.contains("\"mobile_launch_app\""))
    }

    @Test
    fun `withdrawn-медиа-ключ не возвращается в конфиг`() {
        // mobile_media_control выведен из тулз, и permission на него - уже мусор:
        // opencode принимает неизвестный ключ молча, поэтому его легко не заметить
        // и потом удивляться, почему мёртвый тулз снова «защищён».
        val plan = OpencodeRuntime.planMusicEntry(config(withoutMusic), "token")
        val written = (plan as MusicConfigPlan.Write).text

        assertFalse(
            "withdrawn mobile_media_control не должен писаться в permission",
            written.contains("mobile_media_control"),
        )
    }

    @Test
    fun `музыка получила свой bearer, а не токен памяти`() {
        val plan = OpencodeRuntime.planMusicEntry(config(withoutMusic), "token")
        val written = (plan as MusicConfigPlan.Write).text
        val musicEntry = written.substringAfter(MUSIC_KEY)

        assertTrue(
            "music должен защищаться своим MCP_YNISON_TOKEN",
            musicEntry.contains("MCP_YNISON_TOKEN"),
        )
        assertFalse(
            "токен Яндекса не должен попадать в конфиг",
            musicEntry.contains("MCP_MEMORY_TOKEN"),
        )
    }

    private companion object {
        const val MUSIC_KEY = "\"${YnisonMcp.NAME}\""
    }
}
