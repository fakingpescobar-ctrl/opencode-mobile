package org.opencode.mobile.server

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Формы ответов v1 и v2 — то, ради чего существует [ApiShape].
 *
 * Каждый тест — реальное тело, снятое с живого сервера, а не выдумка: в v2
 * порядок сообщений обратный, у сообщений нет `parts`, а у карты занятости
 * вообще нет отдельного эндпоинта.
 */
class ApiShapeTest {
    /** Тело v1: голый массив, сообщение — конверт `info` + `parts`. */
    private val v1Messages =
        """
        [
          {"info":{"role":"user","time":{"created":10}},"parts":[{"type":"text","text":"привет"}]},
          {"info":{"role":"assistant","providerID":"opencode","modelID":"big-pickle",
                   "time":{"created":20,"completed":30}},
           "parts":[{"type":"text","text":"ответ"},{"type":"tool","tool":"bash","state":{"output":"ok"}}]}
        ]
        """.trimIndent()

    /** Тело v2: конверт `data`, порядок от новых к старым, `content` вместо `parts`. */
    private val v2Messages =
        """
        {"data":[
          {"id":"msg_3","type":"idle","time":{"created":40},"outcome":"succeeded"},
          {"id":"msg_2","type":"assistant","time":{"created":20,"completed":30},
           "model":{"id":"big-pickle","providerID":"opencode","variant":"free"},
           "finish":"stop",
           "content":[{"type":"text","text":"ответ"},
                      {"type":"tool","name":"bash","state":{"status":"completed","output":"ok"}},
                      {"type":"reasoning","text":"думаю"}]},
          {"id":"msg_1","type":"user","time":{"created":10},"text":"привет"}
        ],"cursor":{"previous":null,"next":"abc"}}
        """.trimIndent()

    private fun withFlavor(
        flavor: ServerFlavor,
        block: () -> Unit,
    ) {
        val previous = ApiPaths.activeFlavor
        try {
            ApiPaths.install(flavor)
            block()
        } finally {
            ApiPaths.install(previous)
        }
    }

    // ---------- messages ----------

    @Test
    fun `v1 messages stay untouched`() {
        val arr = ApiShape.messages(v1Messages) ?: error("null")
        assertEquals(2, arr.length())
        val first = arr.getJSONObject(0)
        assertEquals("user", first.getJSONObject("info").getString("role"))
        assertEquals("привет", first.getJSONArray("parts").getJSONObject(0).getString("text"))
    }

    @Test
    fun `v2 messages are unwrapped and reversed to oldest first`() {
        withFlavor(ServerFlavor.V2) {
            val arr = ApiShape.messages(v2Messages) ?: error("null")
            // Три сообщения на входе, idle выброшен.
            assertEquals(2, arr.length())
            assertEquals("user", arr.getJSONObject(0).getJSONObject("info").getString("role"))
            assertEquals("assistant", arr.getJSONObject(1).getJSONObject("info").getString("role"))
        }
    }

    @Test
    fun `v2 user text becomes a text part`() {
        withFlavor(ServerFlavor.V2) {
            val arr = ApiShape.messages(v2Messages) ?: error("null")
            val part = arr.getJSONObject(0).getJSONArray("parts").getJSONObject(0)
            assertEquals("text", part.getString("type"))
            assertEquals("привет", part.getString("text"))
        }
    }

    @Test
    fun `v2 assistant content maps onto v1 parts and carries model into info`() {
        withFlavor(ServerFlavor.V2) {
            val arr = ApiShape.messages(v2Messages) ?: error("null")
            val msg = arr.getJSONObject(1)
            val info = msg.getJSONObject("info")
            val parts = msg.getJSONArray("parts")
            assertEquals("opencode", info.getString("providerID"))
            assertEquals("big-pickle", info.getString("modelID"))
            assertEquals("ответ", parts.getJSONObject(0).getString("text"))
            // Имя инструмента в v2 лежит в name — потребители ждут tool.
            assertEquals("tool", parts.getJSONObject(1).getString("type"))
            assertEquals("bash", parts.getJSONObject(1).getString("tool"))
            assertEquals("ok", parts.getJSONObject(1).getJSONObject("state").getString("output"))
            // finish заменяет ушедший из v2 step-finish.
            assertEquals("step-finish", parts.getJSONObject(parts.length() - 1).getString("type"))
        }
    }

    @Test
    fun `completed appears only for a finished turn`() {
        withFlavor(ServerFlavor.V2) {
            val streaming =
                ApiShape.messages(
                    """{"data":[{"id":"m","type":"assistant","time":{"created":5},"content":[
                       {"type":"text","text":"ещё"}]}]}""",
                ) ?: error("null")
            val info = streaming.getJSONObject(0).getJSONObject("info")
            // Ключа нет — как в v1 до конца хода, иначе lastAssistantText принял бы
            // недописанный ответ за законченный.
            assertFalse(info.getJSONObject("time").has("completed"))
        }
    }

    // ---------- isBusy ----------

    private fun busyOf(raw: String?): Boolean? = ApiShape.isBusy(raw)

    @Test
    fun `idle at head means the turn is over`() {
        assertFalse(busyOf("""{"data":[{"type":"idle"}]}""") ?: error("null"))
    }

    @Test
    fun `a pending user turn is busy`() {
        assertTrue(busyOf("""{"data":[{"type":"user","text":"поехали"}]}""") ?: error("null"))
    }

    @Test
    fun `a completed assistant turn is not busy`() {
        val raw = """{"data":[{"type":"assistant","time":{"created":1,"completed":2}}]}"""
        assertFalse(busyOf(raw) ?: error("null"))
    }

    @Test
    fun `an assistant turn without completed is busy`() {
        val raw = """{"data":[{"type":"assistant","time":{"created":1}}]}"""
        assertTrue(busyOf(raw) ?: error("null"))
    }

    @Test
    fun `a leading system message does not mask the idle behind it`() {
        // Найдено на живом сервере: system-сообщение («список инструментов
        // изменился») может лежать первым, и idle при этом не появляется вовсе.
        val raw =
            """
            {"data":[
              {"id":"msg_9","type":"system","time":{"created":9},"text":"The available tools have changed"},
              {"id":"msg_8","type":"assistant","time":{"created":8,"completed":8}}
            ]}
            """.trimIndent()
        assertFalse(busyOf(raw) ?: error("null"))
    }

    @Test
    fun `only system messages mean no turn is running`() {
        assertFalse(busyOf("""{"data":[{"type":"system","text":"x"}]}""") ?: error("null"))
    }

    @Test
    fun `an unreadable feed is unknown, not free`() {
        assertNull(busyOf(null))
        assertNull(busyOf("<html>404</html>"))
    }

    @Test
    fun `v1 feed parses as an array too`() {
        val finished = """[{"info":{"role":"assistant","time":{"created":1,"completed":2}}}]"""
        assertFalse(ApiShape.isBusy(finished) == true)
        val streaming = """[{"info":{"role":"assistant","time":{"created":1}}}]"""
        assertTrue(ApiShape.isBusy(streaming) == true)
    }

    // ---------- statusRecord ----------

    @Test
    fun `v2 busy record carries the session key`() {
        withFlavor(ServerFlavor.V2) {
            val raw = """{"data":[{"type":"assistant","time":{"created":1}}]}"""
            val record = ApiShape.statusRecord(raw, "ses_1") ?: error("null")
            assertTrue(JSONObject(record).has("ses_1"))
        }
    }

    @Test
    fun `v2 idle record is an empty map`() {
        withFlavor(ServerFlavor.V2) {
            val record = ApiShape.statusRecord("""{"data":[{"type":"idle"}]}""", "ses_1") ?: error("null")
            assertEquals(0, JSONObject(record).length())
        }
    }

    @Test
    fun `v2 unknown record is null, not an empty map`() {
        withFlavor(ServerFlavor.V2) {
            assertNull(ApiShape.statusRecord("<html>", "ses_1"))
        }
    }

    @Test
    fun `v1 status passes through untouched`() {
        withFlavor(ServerFlavor.V1) {
            val raw = """{"ses_1":{"a":1}}"""
            assertEquals(raw, ApiShape.statusRecord(raw, "ses_1"))
        }
    }

    // ---------- sessions / session ----------

    @Test
    fun `sessions unwrap the v2 data envelope`() {
        val arr = ApiShape.sessions("""{"data":[{"id":"ses_1"}],"cursor":{}}""") ?: error("null")
        assertEquals(1, arr.length())
        assertEquals("ses_1", arr.getJSONObject(0).getString("id"))
    }

    @Test
    fun `sessions still parse a bare v1 array`() {
        val arr = ApiShape.sessions("""[{"id":"ses_1"}]""") ?: error("null")
        assertEquals(1, arr.length())
    }

    @Test
    fun `an object without data is not a session list`() {
        assertNull(ApiShape.sessions("{}"))
        assertNull(ApiShape.sessions("<html>404</html>"))
    }

    @Test
    fun `a single session is wrapped in v2 too`() {
        val s = ApiShape.session("""{"data":{"id":"ses_9"}}""") ?: error("null")
        assertEquals("ses_9", s.getString("id"))
        val bare = ApiShape.session("""{"id":"ses_8"}""") ?: error("null")
        assertEquals("ses_8", bare.getString("id"))
    }

    // ---------- mcp ----------

    @Test
    fun `v2 mcp flattens the nested status object`() {
        val arr =
            ApiMcp.servers(
                """{"location":{"directory":"/"},"data":[{"name":"memory","status":{"status":"connected"}}]}""",
            ) ?: error("null")
        assertEquals(1, arr.length())
        assertEquals("memory", arr.getJSONObject(0).getString("name"))
        assertEquals("connected", arr.getJSONObject(0).getString("status"))
    }

    @Test
    fun `v1 mcp map keyed by name survives`() {
        val arr = ApiMcp.servers("""{"ynison":{"name":"ynison","status":"connected"}}""") ?: error("null")
        assertEquals("ynison", arr.getJSONObject(0).getString("name"))
        assertEquals("connected", arr.getJSONObject(0).getString("status"))
    }

    @Test
    fun `v1 mcp array survives`() {
        val arr = ApiMcp.servers("""[{"name":"moltbook","status":"connected"}]""") ?: error("null")
        assertEquals("moltbook", arr.getJSONObject(0).getString("name"))
    }

    // ---------- prompt body ----------

    @Test
    fun `v1 prompt keeps the parts envelope`() {
        withFlavor(ServerFlavor.V1) {
            val body = ApiShape.promptBody("привет")
            assertEquals("привет", body.getJSONArray("parts").getJSONObject(0).getString("text"))
        }
    }

    @Test
    fun `v2 prompt is a flat text field`() {
        withFlavor(ServerFlavor.V2) {
            val body = ApiShape.promptBody("привет")
            assertEquals("привет", body.getString("text"))
            assertFalse(body.has("parts"))
        }
    }
}
