package org.opencode.mobile.server

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Формы ответов локальной памяти: конверт `tools/call`, список записей, счётчики.
 *
 * Проверяемость здесь не косметика. Отказы памяти приходят с HTTP 200, и
 * разница только в теле; ошибка разбора выглядит на экране диагностики ровно
 * как «память пуста». Значит без этих тестов «кнопка забыть не удаляет» или
 * «список всегда пустой» ловятся только глазами на устройстве - и, скорее
 * всего, не ловятся вовсе.
 *
 * Тесты чистые: ни сети, ни Android, только строки, которые сервер реально
 * отдаёт (формы сняты с `memory.js`: `handleMessage` -> `content[0].text`,
 * `list()` -> `{memories, apply_rule, scan_truncated}`, `stats()` -> счётчики).
 */
class MemoryWireTest {
    private fun envelope(
        text: String,
        isError: Boolean = false,
    ): String {
        val item = JSONObject().put("type", "text").put("text", text)
        val content = JSONArray().put(item)
        val result = JSONObject().put("content", content).put("isError", isError)
        return JSONObject()
            .put("jsonrpc", "2.0")
            .put("id", 1)
            .put("result", result)
            .toString()
    }

    @Test
    fun `запрос собирается как tools-call с аргументами`() {
        val body = JSONObject(MemoryWire.call("local_memory_list", JSONObject().put("limit", 20), id = 7))
        assertEquals("2.0", body.getString("jsonrpc"))
        assertEquals(7, body.getInt("id"))
        assertEquals("tools/call", body.getString("method"))
        val params = body.getJSONObject("params")
        assertEquals("local_memory_list", params.getString("name"))
        assertEquals(20, params.getJSONObject("arguments").getInt("limit"))
    }

    @Test
    fun `текст инструмента достаётся из конверта`() {
        assertEquals("""{"memories":[]}""", MemoryWire.toolText(envelope("""{"memories":[]}""")))
    }

    @Test
    fun `ошибка JSON-RPC в корне не превращается в пустой список`() {
        val body =
            JSONObject()
                .put("jsonrpc", "2.0")
                .put("id", 1)
                .put("error", JSONObject().put("code", -32603).put("message", "DB is locked"))
                .toString()
        val error = runCatching { MemoryWire.toolText(body) }.exceptionOrNull()
        assertTrue("ожидали отказ, получили ${error?.message}", error is IllegalStateException)
        assertEquals("DB is locked", error?.message)
    }

    @Test
    fun `isError с текстом отдаёт текст причины`() {
        val error = runCatching { MemoryWire.toolText(envelope("no such memory: mem:1", isError = true)) }
        assertEquals("no such memory: mem:1", error.exceptionOrNull()?.message)
    }

    @Test
    fun `isError без текста всё равно отказ, а не пустая память`() {
        val error = runCatching { MemoryWire.toolText(envelope("", isError = true)) }
        assertTrue(error.isFailure)
        assertTrue(
            "сообщение не должно быть пустым: ${error.exceptionOrNull()?.message}",
            !error.exceptionOrNull()?.message.isNullOrBlank(),
        )
    }

    @Test
    fun `ответ без result - ошибка, а не пустой список`() {
        val body = JSONObject().put("jsonrpc", "2.0").put("id", 1).toString()
        assertTrue(runCatching { MemoryWire.toolText(body) }.isFailure)
    }

    @Test
    fun `не JSON - понятная причина, а не JSONException наружу`() {
        val error = runCatching { MemoryWire.toolText("<html>502</html>") }.exceptionOrNull()
        assertTrue(error is IllegalStateException)
        assertTrue("сообщение: ${error?.message}", error?.message?.startsWith("ответ памяти - не JSON") == true)
    }

    @Test
    fun `список разбирается со всеми полями записи`() {
        val text =
            """
            {"memories":[{"id":"mem:1","content":"правило проекта","type":"learned-pattern",
            "tags":"ui,chat","project":"opencode-mobile","created":1761000000000,
            "provenance":"own","trust":"own","superseded":0,"superseded_by":""}],"scan_truncated":false}
            """.trimIndent()
        val records = MemoryWire.records(text)
        assertEquals(1, records.size)
        val record = records.single()
        assertEquals("mem:1", record.id)
        assertEquals("правило проекта", record.content)
        assertEquals("learned-pattern", record.type)
        assertEquals("opencode-mobile", record.project)
        assertEquals("ui,chat", record.tags)
        assertEquals(1761000000000L, record.created)
        assertEquals("own", record.trust)
        assertFalse(record.superseded)
    }

    @Test
    fun `вытесненная запись узнаётся по числу и по идентификатору замены`() {
        val byFlag = MemoryWire.records("""{"memories":[{"id":"mem:1","content":"a","superseded":1}]}""")
        val byId = MemoryWire.records("""{"memories":[{"id":"mem:2","content":"b","superseded_by":"mem:9"}]}""")
        assertTrue(byFlag.single().superseded)
        assertTrue(byId.single().superseded)
    }

    @Test
    fun `пустая память - это пустой список, а не отказ`() {
        assertEquals(emptyList<MemoryWire.Record>(), MemoryWire.records("""{"memories":[]}"""))
        assertEquals(emptyList<MemoryWire.Record>(), MemoryWire.records("{}"))
    }

    @Test
    fun `запись без служебных полей не роняет разбор`() {
        val record = MemoryWire.records("""{"memories":[{"id":"mem:1","content":"только текст"}]}""").single()
        assertEquals("", record.type)
        assertEquals("", record.project)
        assertEquals(0L, record.created)
        assertFalse(record.superseded)
    }

    @Test
    fun `счётчики разбираются, включая раскладку доверия`() {
        val text =
            """
            {"memories":42,"edges":17,"by_trust":{"own":20,"operator":5,"external":10,"unknown":7},
            "superseded":3,"auto_applyable":25,"needs_human":17,"apply_rule":"..."}
            """.trimIndent()
        val stats = MemoryWire.stats(text)
        assertEquals(42, stats.memories)
        assertEquals(17, stats.edges)
        assertEquals(3, stats.superseded)
        assertEquals(25, stats.autoApplyable)
        assertEquals(17, stats.needsHuman)
    }
}
