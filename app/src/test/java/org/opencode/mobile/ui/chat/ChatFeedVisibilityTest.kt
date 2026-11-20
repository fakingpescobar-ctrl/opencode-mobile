package org.opencode.mobile.ui.chat

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.opencode.mobile.server.ApiShape

/**
 * Системные ответы не должны лезть в наш чат.
 *
 * В v2 system/idle/compaction приходят в той же ленте, что и реплики.
 * [keepInFeed] не пускает их в видимые сообщения, даже если у нормализованного
 * конверта нашёлся текст. Ошибки при этом остаются отдельной ролью `error`.
 */
class ChatFeedVisibilityTest {
    @Test
    fun `compaction без текста не попадает в нормализованную ленту`() {
        val raw = JSONObject()
            .put(
                "data",
                JSONArray().put(
                    JSONObject()
                        .put("type", "compaction")
                        .put("id", "msg_compact")
                        .put("summary", "длинное служебное саммари"),
                ),
            )
        val arr = ApiShape.messages(raw.toString())
        assertNotNull(arr)
        assertEquals(0, arr!!.length())
    }

    @Test
    fun `system с текстом не становится видимой строкой чата`() {
        val raw = JSONObject()
            .put(
                "data",
                JSONArray().put(
                    JSONObject()
                        .put("type", "system")
                        .put("id", "msg_system")
                        .put("text", "служебный ответ"),
                ),
            )
        val arr = ApiShape.messages(raw.toString())
        assertNotNull(arr)
        val role = arr!!.getJSONObject(0).getJSONObject("info").optString("role")
        assertEquals("system", role)
        assertFalse(keepInFeed(role, hasText = true, finish = false))
    }

    @Test
    fun `живой ответ и ошибка остаются, tool-only шаг скрывается`() {
        assertTrue(keepInFeed("assistant", hasText = true, finish = false))
        assertTrue(keepInFeed("error", hasText = false, finish = false))
        assertFalse(keepInFeed("assistant", hasText = false, finish = true))
    }
}
