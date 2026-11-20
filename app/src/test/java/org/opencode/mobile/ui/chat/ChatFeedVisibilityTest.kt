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
 * В v2 compaction/system/idle приходит в той же ленте, что и реплики.
 * Нормализация помечает их ролью `system`, а [keepInFeed] обязан не пустить их
 * в видимые сообщения. Ошибки при этом остаются отдельной ролью `error`.
 */
class ChatFeedVisibilityTest {
    @Test
    fun `compaction не становится видимой строкой чата`() {
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
